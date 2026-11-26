import Foundation
import CoreLocation
import CarMonitorKit

/// The logger's life cycle: wait for the adapter, initialise it, poll the car into the drive CSV, upload to
/// the CarMonitor integration, and go back to waiting when the car switches off. Same shape as the Android service.
@MainActor
final class Session: ObservableObject {
    struct Live: Identifiable { let id: String; let name: String; let value: String }

    @Published var state = "Stopped"
    @Published var running = false
    @Published var connected = false
    @Published var unsent = 0
    @Published var live: [String: Live] = [:]
    @Published var logLines: [String] = []
    @Published var found: [BleLink.Found] = []

    let settings = Settings()
    let ble = BleLink()
    let location = Location()
    private var task: Task<Void, Never>?
    private var uplinkTask: Task<Void, Never>?
    private var uploaderFor: (String, Bool)?
    private var cachedUploader: Uploader?
    private var csv: CsvLog?

    static var docs: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var drivesDir: URL {
        let d = docs.appendingPathComponent("drives")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    init() {
        ble.onLog = { [weak self] s in Task { @MainActor in self?.log(s) } }
        ble.onFound = { [weak self] f in
            Task { @MainActor in
                guard let self else { return }
                if let i = self.found.firstIndex(where: { $0.id == f.id }) { self.found[i] = f } else { self.found.append(f) }
            }
        }
        location.onFix = { [weak self] l in
            Task { @MainActor in
                guard let self, let csv = self.csv else { return }
                let ts = Int64(l.timestamp.timeIntervalSince1970 * 1000)
                csv.row(ts: ts, key: "gps", signal: "GPS_LAT", value: String(format: "%.7f", l.coordinate.latitude), raw: "")
                csv.row(ts: ts, key: "gps", signal: "GPS_LON", value: String(format: "%.7f", l.coordinate.longitude), raw: "")
                csv.row(ts: ts, key: "gps", signal: "GPS_ACC_M", value: String(format: "%.0f", l.horizontalAccuracy), raw: "")
                if l.speed >= 0 { csv.row(ts: ts, key: "gps", signal: "GPS_SPEED_KMH", value: String(format: "%.1f", l.speed * 3.6), raw: "") }
            }
        }
        uplinkTask = Task { await uplinkLoop() }
        if settings.autoStart && settings.adapterId != nil { start() }
    }

    func log(_ s: String) {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
        logLines.append(f.string(from: Date()) + " " + s)
        if logLines.count > 300 { logLines.removeFirst(logLines.count - 300) }
    }

    // MARK: logging

    func start() {
        guard task == nil else { return }
        running = true
        if settings.gps { location.requestPermission() }
        task = Task { await loop() }
    }

    func stop() {
        task?.cancel(); task = nil
        running = false
        ble.disconnect()
        state = "Stopped"
    }

    private func loop() async {
        while !Task.isCancelled {
            guard let id = settings.adapterId else {
                state = "Pick your adapter in Setup"
                try? await Task.sleep(for: .seconds(5)); continue
            }
            state = "Waiting for the car…"
            var drive: CsvLog?
            do {
                try await ble.connect(id)
                state = "Talking to the adapter…"
                let elm = Elm327(transport: ble, log: { s in Task { @MainActor in self.log(s) } })
                try await elm.initialise()
                let cmds = try loadSignals().forYear(settings.year == 0 ? nil : settings.year)
                let key = settings.vehicleKey
                let logger0 = Logger(elm: elm, commands: cmds, csv: nil, onValue: { _, _, _ in })
                var supported = settings.supported(for: key)
                if supported == nil {
                    state = "Finding what the car supports…"
                    let found = try await logger0.discover()
                    if !found.isEmpty { settings.setSupported(found, for: key) }
                    supported = found
                }
                guard let keys = supported, !keys.isEmpty else {
                    state = "The car isn't answering (ignition off?)"
                    ble.disconnect()
                    try await Task.sleep(for: .seconds(10)); continue
                }
                let used = cmds.filter { keys.contains($0.key) }
                let names = Dictionary(used.flatMap { c in c.signals.map { ($0.id, ($0.name, HaDefs.haUnit($0.fmt.unit) ?? "")) } }, uniquingKeysWith: { a, _ in a })
                let d = try CsvLog(dir: Session.drivesDir)
                drive = d; csv = d
                let logger = Logger(elm: elm, commands: used, csv: d, log: { s in Task { @MainActor in self.log(s) } }) { id, v, _ in
                    let text: String
                    switch v { case .num(let x): text = Logger.fmtNum(x); case .text(let s): text = s }
                    Task { @MainActor in
                        let n = names[id] ?? (id, "")
                        self.live[id] = Live(id: id, name: n.0, value: text + (n.1.isEmpty ? "" : " " + n.1))
                    }
                }
                await logger.use(supported: keys)
                if let up = uploader() { await up.setDefs(HaDefs.defs(for: used)); await up.setStatus("online") }
                if settings.gps { location.start() }
                state = "Logging"
                log("logging \(used.count) commands to \(d.url.lastPathComponent)")
                try await logger.run(stop: { Task.isCancelled })
            } catch {
                if !Task.isCancelled { log("stopped: \(error.localizedDescription)") }
            }
            location.stop()
            csv = nil
            drive?.close()
            if let d = drive, (try? FileManager.default.attributesOfItem(atPath: d.url.path)[.size] as? Int) ?? 0 <= 30 {
                try? FileManager.default.removeItem(at: d.url)   // header only: nothing was logged
            }
            if let up = uploader() { await up.setStatus("offline") }
            live = [:]
            ble.disconnect()
            if !Task.isCancelled { try? await Task.sleep(for: .seconds(3)) }
        }
    }

    /// SAEJ1979 and the generic fixes (bundled), then the chosen vehicle's OBDb file (downloaded in Setup).
    func loadSignals() throws -> SignalSet {
        var sets: [SignalSet] = []
        for (name, file) in [("SAEJ1979", "SAEJ1979"), ("fixes", "_fixes")] {
            if let u = Bundle.main.url(forResource: file, withExtension: "json") { sets.append(try SignalSet.parse(name: name, json: try Data(contentsOf: u))) }
        }
        if !settings.vehicleRepo.isEmpty {
            let u = Session.docs.appendingPathComponent("obdb/" + settings.vehicleRepo + "-" + settings.vehicleFile)
            if let data = try? Data(contentsOf: u) { sets.append(try SignalSet.parse(name: settings.vehicleRepo, json: data)) }
        }
        return SignalSet.merge(name: "vehicle", sets)
    }

    // MARK: Home Assistant

    func uploader(past: Bool? = nil) -> Uploader? {
        let u = settings.webhookUrl
        guard u.hasPrefix("http"), let url = URL(string: u) else { return nil }
        let p = past ?? settings.uploadPast
        if past == nil, let f = uploaderFor, f.0 == u, f.1 == p, let up = cachedUploader { return up }
        let up = Uploader(url: url, dir: Session.drivesDir, uploadPast: p, log: { s in Task { @MainActor in self.log(s) } })
        if past == nil { uploaderFor = (u, p); cachedUploader = up }
        return up
    }

    /// Every 3 s: send new rows; while idle, an empty post every 30 s keeps Connected honest.
    private func uplinkLoop() async {
        var tick = 0
        while !Task.isCancelled {
            if let up = uploader() {
                if running || tick % 10 == 0 { await up.flush() }
                if !running && tick % 10 == 0 { await up.probe() }
                connected = await up.connected
                unsent = await up.pending().count
            } else { connected = false }
            tick += 1
            try? await Task.sleep(for: .seconds(3))
        }
    }

    func uploadDrives() async {
        guard let up = uploader(past: true) else { log("Set the webhook URL first"); return }
        let todo = await up.pending().count
        if todo == 0 { log("Every drive is already in Home Assistant"); return }
        var rows = 0
        for _ in 0..<50 { let n = await up.flush(); rows += n; if n == 0 { break } }
        let left = await up.pending().count
        log(left == 0 ? "Uploaded \(todo) drives (\(rows) readings)" : "Uploaded \(rows) readings; \(left) drives left: \(await up.lastError ?? "will retry")")
        unsent = await uploader()?.pending().count ?? 0
    }

    func testWebhook() async -> String {
        guard let url = URL(string: settings.webhookUrl), settings.webhookUrl.hasPrefix("http") else { return "Paste the webhook URL first" }
        do {
            let (code, _) = try await Uploader.httpPost(url, Data(#"{"drive":"test","rows":[]}"#.utf8))
            if (200..<300).contains(code) { connected = true; return "The integration answered." }
            return code == 404 ? "Webhook not found (404): check the URL" : "Integration test failed: HTTP \(code)"
        } catch {
            return "Can't reach Home Assistant: \(error.localizedDescription)"
        }
    }

    // MARK: vehicle

    func searchVehicles(_ q: String) async throws -> [VehicleRef] {
        var all: [VehicleRef] = []
        for page in 1...5 {
            let (data, _) = try await URLSession.shared.data(from: Catalog.reposPageURL(page))
            let refs = Catalog.parseReposPage(data)
            all += refs
            if refs.count < 90 { break }
        }
        return Catalog.search(all, query: q)
    }

    func chooseVehicle(_ v: VehicleRef) async throws {
        let (listing, _) = try await URLSession.shared.data(from: Catalog.dirURL(repo: v.repo))
        let file = Catalog.chooseFile(Catalog.parseDirListing(listing), year: settings.year == 0 ? nil : settings.year)
        let (data, _) = try await URLSession.shared.data(from: Catalog.rawURL(repo: v.repo, file: file))
        _ = try SignalSet.parse(name: v.repo, json: data)   // refuse a broken file before saving it
        let dir = Session.docs.appendingPathComponent("obdb")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try data.write(to: dir.appendingPathComponent(v.repo + "-" + file))
        settings.vehicleRepo = v.repo; settings.vehicleFile = file; settings.vehicleLabel = v.label
        log("vehicle: \(v.label) (\(file))")
    }
}
