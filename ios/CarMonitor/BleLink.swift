import CoreBluetooth
import CarMonitorKit

enum BleError: LocalizedError {
    case off, unknownAdapter, noSerial, disconnected, cancelled
    var errorDescription: String? {
        switch self {
        case .off: return "Bluetooth is off or not allowed"
        case .unknownAdapter: return "adapter not found: pick it again in Setup"
        case .noSerial: return "this Bluetooth device has no serial (ELM327) service"
        case .disconnected: return "adapter disconnected"
        case .cancelled: return "cancelled"
        }
    }
}

/// ELM327 over Bluetooth LE, as the cheap "OBD2 BT" clones speak it. No pairing: iOS apps connect by scanning.
/// The serial service is found by its usual UUIDs (FFF0, FFE0, 18F0), else any service with a notify and a
/// write characteristic. Replies arrive in pieces; reads wait for the '>' prompt.
final class BleLink: NSObject, ElmTransport, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    struct Found: Identifiable, Hashable { let id: UUID; let name: String; var rssi: Int }

    private let queue = DispatchQueue(label: "carmonitor.ble")
    private var central: CBCentralManager!
    private let lock = NSLock()
    private var buffer = ""
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var notifyChar: CBCharacteristic?
    private var servicesLeft = 0
    private var connectCont: CheckedContinuation<Void, Error>?
    private var scanning = false
    private(set) var isConnected = false
    var onFound: ((Found) -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: queue,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "carmonitor-central"])
    }

    // MARK: scanning

    func scan() { queue.async { self.scanning = true; if self.central.state == .poweredOn { self.central.scanForPeripherals(withServices: nil) } } }
    func stopScan() { queue.async { self.scanning = false; self.central.stopScan() } }

    // MARK: connecting

    private func waitPoweredOn() async throws {
        for _ in 0..<50 {
            if central.state == .poweredOn { return }
            if central.state == .unauthorized || central.state == .unsupported { throw BleError.off }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw BleError.off
    }

    /// Connect to the adapter and find its serial characteristics. iOS keeps the connect pending until the adapter
    /// is in range and powered, even with the app in the background: that is how logging starts with the car.
    func connect(_ id: UUID) async throws {
        try await waitPoweredOn()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                queue.async {
                    guard let p = self.central.retrievePeripherals(withIdentifiers: [id]).first else {
                        cont.resume(throwing: BleError.unknownAdapter); return
                    }
                    self.connectCont?.resume(throwing: BleError.cancelled)
                    self.connectCont = cont
                    self.peripheral = p
                    p.delegate = self
                    self.writeChar = nil; self.notifyChar = nil
                    self.central.connect(p)
                }
            }
        } onCancel: {
            self.disconnect()
        }
    }

    func disconnect() {
        queue.async {
            if let p = self.peripheral { self.central.cancelPeripheralConnection(p) }
            self.isConnected = false
            self.connectCont?.resume(throwing: BleError.cancelled); self.connectCont = nil
        }
    }

    private func finishConnect(_ error: Error?) {
        guard let c = connectCont else { return }
        connectCont = nil
        if let e = error { c.resume(throwing: e) } else { isConnected = true; c.resume() }
    }

    // MARK: ElmTransport

    func send(_ line: String) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                guard self.isConnected, let p = self.peripheral, let w = self.writeChar else { cont.resume(throwing: BleError.disconnected); return }
                self.lock.lock(); self.buffer = ""; self.lock.unlock()
                let type: CBCharacteristicWriteType = w.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
                let data = Data((line + "\r").utf8)
                let n = max(20, p.maximumWriteValueLength(for: type))
                var i = 0
                while i < data.count { p.writeValue(data.subdata(in: i..<min(i + n, data.count)), for: w, type: type); i += n }
                cont.resume()
            }
        }
    }

    func readUntilPrompt(timeout: Duration) async throws -> String {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            lock.lock()
            if let r = buffer.firstIndex(of: ">") {
                let out = String(buffer[..<r])
                buffer.removeSubrange(...r)
                lock.unlock()
                return out
            }
            lock.unlock()
            if !isConnected { throw BleError.disconnected }
            try await Task.sleep(for: .milliseconds(10))
        }
        lock.lock(); defer { buffer = ""; lock.unlock() }
        return buffer
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn && scanning { c.scanForPeripherals(withServices: nil) }
        if c.state != .poweredOn { isConnected = false }
    }

    func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first { peripheral = p; p.delegate = self }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        let name = p.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        if name.isEmpty { return }
        onFound?(Found(id: p.identifier, name: name, rssi: rssi.intValue))
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        onLog?("adapter connected: \(p.name ?? "?")")
        p.discoverServices(nil)
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        finishConnect(error ?? BleError.disconnected)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        isConnected = false
        onLog?("adapter disconnected" + (error.map { ": \($0.localizedDescription)" } ?? ""))
        finishConnect(BleError.disconnected)
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        let services = p.services ?? []
        if services.isEmpty { finishConnect(BleError.noSerial); return }
        servicesLeft = services.count
        for s in services { p.discoverCharacteristics(nil, for: s) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        servicesLeft -= 1
        if servicesLeft > 0 { return }
        guard let (n, w) = BleLink.pickSerial(p.services ?? []) else { finishConnect(BleError.noSerial); return }
        notifyChar = n; writeChar = w
        onLog?("serial service \(n.service?.uuid.uuidString ?? "?"): notify \(n.uuid.uuidString), write \(w.uuid.uuidString)")
        p.setNotifyValue(true, for: n)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        if ch == notifyChar { finishConnect(error) }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard ch == notifyChar, let v = ch.value else { return }
        let s = String(decoding: v.filter { $0 != 0 }, as: UTF8.self)
        lock.lock(); buffer += s; lock.unlock()
    }

    /// Known clone layouts first, then any service with a notify and a write characteristic.
    static func pickSerial(_ services: [CBService]) -> (CBCharacteristic, CBCharacteristic)? {
        let known: [(String, String, String)] = [("FFF0", "FFF1", "FFF2"), ("FFE0", "FFE1", "FFE1"), ("18F0", "2AF0", "2AF1")]
        for (svc, nid, wid) in known {
            guard let s = services.first(where: { $0.uuid == CBUUID(string: svc) }), let chars = s.characteristics else { continue }
            if let n = chars.first(where: { $0.uuid == CBUUID(string: nid) }), let w = chars.first(where: { $0.uuid == CBUUID(string: wid) }) { return (n, w) }
        }
        for s in services {
            let chars = s.characteristics ?? []
            if let n = chars.first(where: { $0.properties.contains(.notify) }),
               let w = chars.first(where: { $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse) }) { return (n, w) }
        }
        return nil
    }
}
