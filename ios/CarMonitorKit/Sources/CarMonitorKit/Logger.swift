import Foundation

/// Polls the supported commands in three tiers and writes every reading to the drive CSV.
/// A simplified LoggerEngine.kt: fast = driving signals every 0.5 s (service 01 PIDs batched up to six),
/// medium = signals HA shows (an OBDb suggestedMetric) every 5 s, slow = the rest every 60 s.
public actor Logger {
    public static let fastIds: Set<String> = ["RPM", "VSS", "ECT", "MAF", "MAFA", "FUEL_RATE", "LOAD_PCT", "THROTTLE_POS", "ENG_LOAD"]
    public static let intervals: [Int: Double] = [2: 0.5, 1: 5, 0: 60]

    struct Unit {
        let members: [Command]
        let tier: Int
        var due: Double = 0
        var batch: Bool { members.count > 1 }
        var interval: Double {
            tier == 2 ? Logger.intervals[2]! : max(Logger.intervals[tier]!, members.map { $0.freq }.max() ?? 0)
        }
    }

    private let elm: Elm327
    private let commands: [Command]
    private let csv: CsvLog?
    private let now: @Sendable () -> Date
    private let onValue: @Sendable (String, Decoded, Int64) -> Void
    private let log: @Sendable (String) -> Void
    private var units: [Unit] = []
    public private(set) var polls = 0

    public init(elm: Elm327, commands: [Command], csv: CsvLog?, now: @escaping @Sendable () -> Date = { Date() },
                log: @escaping @Sendable (String) -> Void = { _ in },
                onValue: @escaping @Sendable (String, Decoded, Int64) -> Void) {
        self.elm = elm; self.commands = commands; self.csv = csv; self.now = now; self.log = log; self.onValue = onValue
    }

    static func tier(_ c: Command) -> Int {
        if c.signals.contains(where: { fastIds.contains($0.id) }) { return 2 }
        if c.signals.contains(where: { $0.suggestedMetric != nil }) { return 1 }
        return 0
    }

    /// Probe each command once; the keys that answered positively.
    public func discover() async throws -> [String] {
        var ok: [String] = []
        for c in commands {
            if case .positive = try await elm.request(c.request, hdr: c.hdr, rax: .some(c.rax)) { ok.append(c.key) }
        }
        log("discovery: \(ok.count) of \(commands.count) commands answer")
        return ok
    }

    /// Poll only these command keys from now on.
    public func use(supported: [String]) {
        let set = Set(supported)
        let live = commands.filter { set.contains($0.key) }
        var batchable: [String: [Command]] = [:]
        var out: [Unit] = []
        for c in live {
            let t = Logger.tier(c)
            if t == 2 && c.validated && MultiPid.batchable(c) { batchable[c.hdr, default: []].append(c) } else { out.append(Unit(members: [c], tier: t)) }
        }
        for (_, list) in batchable.sorted(by: { $0.key < $1.key }) {
            var i = 0
            while i < list.count { out.append(Unit(members: Array(list[i..<min(i + MultiPid.max, list.count)]), tier: 2)); i += MultiPid.max }
        }
        units = out
    }

    /// Poll until stop() says so.
    public func run(stop: @Sendable () -> Bool) async throws {
        while !stop() && !Task.isCancelled {
            if !(try await step(force: false)) {
                let t = now().timeIntervalSince1970
                let next = units.map { $0.due }.min() ?? t + 1
                try await Task.sleep(for: .milliseconds(Int(max(10, min(1000, (next - t) * 1000)))))
            }
        }
    }

    /// Poll the most urgent due unit (highest tier, then most overdue). force: ignore due times. False if nothing was due.
    @discardableResult
    public func step(force: Bool) async throws -> Bool {
        let t = now().timeIntervalSince1970
        guard let i = units.indices.filter({ force || units[$0].due <= t })
            .max(by: { (units[$0].tier, -units[$0].due) < (units[$1].tier, -units[$1].due) }) else { return false }
        units[i].due = t + units[i].interval
        let u = units[i]
        polls += 1
        if u.batch {
            let pids = u.members.map { $0.pid }
            let r = try await elm.request("01" + pids.joined(), hdr: u.members[0].hdr, rax: .some(u.members[0].rax))
            let ts = Int64(now().timeIntervalSince1970 * 1000)
            if case let .positive(data, _, _) = r {
                let parts = MultiPid.split(pids: pids, data: data)
                for c in u.members { if let d = parts[c.pid] { handle(c, d, ts) } }
            } else {
                // The ECU or adapter can't do this batch: poll its PIDs one by one from now on.
                log("batch \(pids.joined()) failed (\(r)), polling singly")
                units.remove(at: i)
                units += u.members.map { Unit(members: [$0], tier: 2) }
            }
            return true
        }
        let c = u.members[0]
        let r = try await elm.request(c.request, hdr: c.hdr, rax: .some(c.rax))
        if case let .positive(data, _, _) = r { handle(c, data, Int64(now().timeIntervalSince1970 * 1000)) }
        return true
    }

    private func handle(_ c: Command, _ data: Data, _ ts: Int64) {
        if !c.validated {
            let u16 = Decoder.bits(data, bix: 0, len: 16) ?? Decoder.bits(data, bix: 0, len: 8)
            csv?.row(ts: ts, key: c.key, signal: "raw", value: u16.map { String($0) } ?? "", raw: data.hex)
        }
        for s in c.signals {
            guard let d = Decoder.decode(s, data) else { continue }
            let txt: String
            switch d { case .num(let v): txt = Logger.fmtNum(v); case .text(let v): txt = v }
            csv?.row(ts: ts, key: c.key, signal: s.id, value: txt, raw: c.validated ? "" : data.hex)
            onValue(s.id, d, ts)
        }
    }

    public static func fmtNum(_ v: Double) -> String {
        if v == v.rounded() && abs(v) < 1e12 { return String(Int64(v)) }
        var s = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }
}
