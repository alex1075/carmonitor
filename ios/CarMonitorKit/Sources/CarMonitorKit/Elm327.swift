import Foundation

/// A line link to an ELM327-compatible adapter (Bluetooth LE on iOS, a script in tests).
public protocol ElmTransport: AnyObject, Sendable {
    func send(_ line: String) async throws
    /// Text up to the '>' prompt (exclusive), or what arrived before the timeout.
    func readUntilPrompt(timeout: Duration) async throws -> String
}

public enum ObdResponse: Sendable, Equatable, CustomStringConvertible {
    /// data = payload after the positive-response echo (e.g. after "62 04 2B").
    case positive(Data, from: String?, frames: Int)
    case negative(service: Int, nrc: Int)
    case noData
    case error(String)

    public static func == (a: ObdResponse, b: ObdResponse) -> Bool {
        switch (a, b) {
        case let (.positive(d1, f1, _), .positive(d2, f2, _)): return d1 == d2 && f1 == f2
        case let (.negative(s1, n1), .negative(s2, n2)): return s1 == s2 && n1 == n2
        case (.noData, .noData): return true
        case let (.error(x), .error(y)): return x == y
        default: return false
        }
    }

    public var description: String {
        switch self {
        case let .positive(d, from, _): return "positive(\(d.hex) from \(from ?? "?"))"
        case let .negative(s, n): return String(format: "negative(%02X, NRC %02X)", s, n)
        case .noData: return "noData"
        case let .error(t): return "error(\(t))"
        }
    }
}

public extension Data {
    var hex: String { map { String(format: "%02X", $0) }.joined() }
    init?(hex: String) {
        var b: [UInt8] = []
        var i = hex.startIndex
        while i < hex.endIndex {
            guard let j = hex.index(i, offsetBy: 2, limitedBy: hex.endIndex), let v = UInt8(hex[i..<j], radix: 16) else { return nil }
            b.append(v); i = j
        }
        self.init(b)
    }
}

/// ELM327 driver: init, header management, guarded requests, ISO-TP reassembly of header-on (ATH1) CAN output.
/// Mirrors Elm327.kt. Every line sent goes through RequestGuard.check first.
public actor Elm327 {
    private let transport: ElmTransport
    private let proto: String
    private let timeout: Duration
    private let log: @Sendable (String) -> Void

    private var header: String?
    private var rxFilter: String?
    // Response counts ("010C1"): only for requests seen to answer in one frame; dropped for a request (or the
    // whole adapter) that answers badly with one.
    private var singleFrame = Set<String>(), noCount = Set<String>()
    private var countFailures = 0
    public private(set) var responseCounts = true
    public private(set) var adapterId = ""
    public private(set) var adapterWarnings: [String] = []

    public init(transport: ElmTransport, protocol proto: String = "6", timeout: Duration = .seconds(2),
                log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.transport = transport; self.proto = proto; self.timeout = timeout; self.log = log
    }

    private func at(_ cmd: String, wait: Duration? = nil) async throws -> String {
        let full = "AT" + cmd
        try RequestGuard.check(full)
        try await transport.send(full)
        return try await transport.readUntilPrompt(timeout: wait ?? timeout).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public func initialise() async throws {
        let z = try await at("Z", wait: .seconds(4))
        adapterId = z.components(separatedBy: CharacterSet.newlines).map { $0.trimmingCharacters(in: .whitespaces) }
            .last { !$0.isEmpty } ?? ""
        log("adapter: \(adapterId)")
        var bad: [String] = []
        for c in ["E0", "L0", "S0", "H1", "CAF1", "AT1", "SP" + proto, "SH7E0", "CRA7E8", "CRA"] {
            let r = try await at(c)
            if !r.contains("OK") { log("AT\(c) -> '\(r)'"); bad.append("AT" + c) }
        }
        adapterWarnings = bad
        header = nil; rxFilter = nil
    }

    public func voltage() async throws -> String { try await at("RV") }

    private func ensureHeader(_ hdr: String, _ rax: String?) async throws {
        if header != hdr { _ = try await at("SH" + hdr); header = hdr }
        if rxFilter != rax {
            if let r = rax { _ = try await at("CRA" + r) } else { _ = try await at("CRA") }
            rxFilter = rax
        }
    }

    /// Send a read-only request (e.g. "010C", "22042B") to hdr; reply expected from rax.
    public func request(_ hex: String, hdr: String = "7E0", rax: String?? = .none, retriesOnPending: Int = 2) async throws -> ObdResponse {
        let rx: String? = rax ?? SignalSet.defaultRx(hdr)
        let req = RequestGuard.normalise(hex)
        try RequestGuard.check(req)
        if !RequestGuard.routeAllowed(hdr: hdr, rax: rx) { throw RequestGuard.Refused(message: "only 11-bit CAN headers: \(hdr)") }
        try await ensureHeader(hdr, rx)
        let key = hdr + ":" + req
        if responseCounts && rx != nil && singleFrame.contains(key) && !noCount.contains(key) {
            let r = try await exchange(req + "1", req, rx)
            var busy = false
            if case let .negative(_, nrc) = r { busy = nrc == 0x78 || nrc == 0x21 }
            if case .positive = r { return r }
            if case .negative = r, !busy { return r }
            if !busy {
                noCount.insert(key)
                countFailures += 1
                if countFailures >= 5 { responseCounts = false; log("adapter: response counts unreliable, turned off") }
            }
        }
        var attempt = 0
        while true {
            let r = try await exchange(req, req, rx)
            if case let .negative(_, nrc) = r, nrc == 0x78 || nrc == 0x21, attempt < retriesOnPending {
                attempt += 1
                try await Task.sleep(for: .milliseconds(nrc == 0x78 ? 200 : 100))
                continue
            }
            if case let .positive(_, _, frames) = r, frames == 1 { singleFrame.insert(key) }
            return r
        }
    }

    private func exchange(_ sent: String, _ req: String, _ rax: String?) async throws -> ObdResponse {
        try RequestGuard.check(sent)
        try await transport.send(sent)
        return Elm327.parse(req: req, raw: try await transport.readUntilPrompt(timeout: timeout), rax: rax)
    }

    /// Parse the adapter's text output for request req. Pure, so it's testable.
    public static func parse(req: String, raw: String, rax: String?) -> ObdResponse {
        guard req.count >= 2, let svc = Int(req.prefix(2), radix: 16) else { return .error("bad request") }
        let lines = raw.components(separatedBy: CharacterSet(charactersIn: "\r\n"))
            .map { $0.replacingOccurrences(of: " ", with: "").trimmingCharacters(in: .whitespaces).uppercased() }
            .filter { !$0.isEmpty && $0 != req && !$0.hasPrefix("SEARCHING") }
        if lines.isEmpty { return .noData }
        final class Msg { let id: String; var bytes: [Int]; init(_ id: String, _ b: [Int]) { self.id = id; bytes = b } }
        var msgs: [Msg] = [], expectLen: [String: Int] = [:], open: [String: Msg] = [:]
        var errorText: String?
        for l in lines {
            guard l.range(of: "^[0-9A-F]{3}([0-9A-F]{2}){1,8}$", options: .regularExpression) != nil,
                  let payload = Data(hex: String(l.dropFirst(3))) else {
                if l.contains("NODATA") { continue }
                errorText = errorText ?? l
                continue
            }
            let id = String(l.prefix(3))
            let bytes = payload.map { Int($0) }
            switch bytes[0] >> 4 {
            case 0:
                let n = bytes[0] & 0xF
                msgs.append(Msg(id, Array(bytes.dropFirst().prefix(n)))); open[id] = nil
            case 1:
                guard bytes.count >= 2 else { continue }
                let m = Msg(id, Array(bytes.dropFirst(2)))
                expectLen[id] = ((bytes[0] & 0xF) << 8) | bytes[1]; open[id] = m; msgs.append(m)
            case 2:
                open[id]?.bytes.append(contentsOf: bytes.dropFirst())
            default: break
            }
        }
        for m in msgs { if let n = expectLen[m.id], m.bytes.count > n { m.bytes = Array(m.bytes.prefix(n)) } }
        // A multi-frame message that stopped early is not a reply.
        let short = Set(msgs.filter { m in expectLen[m.id].map { m.bytes.count < $0 } == true }.map { ObjectIdentifier($0) })
        let mine = rax != nil ? msgs.filter { $0.id == rax } : msgs
        if mine.isEmpty {
            if let e = errorText, msgs.isEmpty { return .error(e) }
            return .noData
        }
        if let pos = mine.last(where: { !$0.bytes.isEmpty && $0.bytes[0] == svc + 0x40 }) {
            if short.contains(ObjectIdentifier(pos)) { return .error("truncated") }
            let p = pos.bytes
            let echo = svc == 0x22 ? 3 : (svc == 0x01 || svc == 0x09) ? 2 : 1
            if p.count < echo { return .error("short reply") }
            let reqBytes = Data(hex: req).map { [UInt8]($0).map { Int($0) } } ?? []
            for k in 1..<echo where k >= reqBytes.count || p[k] != reqBytes[k] { return .error("echo mismatch") }
            let frames = p.count <= 7 ? 1 : 1 + p.count / 7   // first frame holds 6, then 7 each
            return .positive(Data(p.dropFirst(echo).map { UInt8($0) }), from: pos.id, frames: frames)
        }
        if let neg = mine.last(where: { $0.bytes.count >= 3 && $0.bytes[0] == 0x7F }) {
            return .negative(service: neg.bytes[1], nrc: neg.bytes[2])
        }
        return .error("unexpected: " + (mine.last?.bytes.map { String(format: "%02X", $0) }.joined(separator: " ") ?? ""))
    }
}
