import Foundation
import CarMonitorKit

func loggerTests() async throws {
    let lib = try SignalSet.parse(name: "SAEJ1979", json: try repoFile("app/src/main/assets/obdb/SAEJ1979.json"))
    let raw = try SignalSet.parse(name: "o", json: Data(#"{"commands":[{"hdr":"7E0","cmd":{"22":"042B"},"freq":5,"validated":false,"signals":[{"id":"CAND","fmt":{"len":16}}]}]}"#.utf8))
    let cmds = lib.commands.filter { ["010C", "010D", "0105"].contains($0.request) } + raw.commands
    let fake = FakeEcu()
    fake.replies["010C0D1"] = "7E806410C0BB80D3200"
    let elm = Elm327(transport: fake)
    try await elm.initialise()
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cm-log-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let csv = try CsvLog(dir: dir, start: Date(timeIntervalSince1970: 1_795_595_335))
    check(csv.url.lastPathComponent.hasPrefix("drive-") && csv.url.lastPathComponent.hasSuffix(".csv"), "drive file name")
    let seen = Box()
    let logger = Logger(elm: elm, commands: cmds, csv: csv, onValue: { id, v, _ in seen.add(id, v) })
    let ok = try await logger.discover()
    check(ok.contains("7E0:010C") && ok.contains("7E0:010D") && ok.contains("7E0:22042B"), "supported found: \(ok)")
    check(!ok.contains("7E0:0105"), "NO DATA command dropped")
    await logger.use(supported: ok)
    let sentBefore = fake.sent.count
    // Everything starts due: the fast batch goes first, then the slow candidate; after that nothing is due yet.
    let first = try await logger.step(force: false), second = try await logger.step(force: false), third = try await logger.step(force: false)
    check(first && second && !third, "due times respected: \(first) \(second) \(third)")
    let polled = fake.sent[sentBefore...]
    check(polled.contains("010C0D") || polled.contains("010C0D1"), "fast PIDs batched: \(polled)")
    check(!polled.contains("0105"), "unsupported never polled after discovery")
    csv.close()
    let text = try String(contentsOf: csv.url, encoding: .utf8)
    let lines = text.split(separator: "\n").map(String.init)
    eq(lines.first, "ts_ms,key,signal,value,raw", "CSV header")
    check(lines.contains { $0.hasSuffix(",7E0:010C,RPM,750,") }, "RPM row")
    check(lines.contains { $0.hasSuffix(",7E0:010D,VSS,50,") }, "VSS row")
    check(lines.contains { $0.contains(",7E0:22042B,raw,319,013F") }, "raw row for an unvalidated command")
    check(lines.contains { $0.contains(",7E0:22042B,CAND,319,013F") }, "candidate value carries raw hex")
    eq(seen.get("RPM"), .num(750), "live value callback")
    try await silenceAndRefusedTests(lib: lib)
    eq(Logger.fmtNum(12.5), "12.5", "fmt decimals"); eq(Logger.fmtNum(3), "3", "fmt integer"); eq(Logger.fmtNum(0.1234), "0.123", "fmt 3 dp")
    try? FileManager.default.removeItem(at: dir)
}

final class Box: @unchecked Sendable {
    private var v: [String: Decoded] = [:]
    private let lock = NSLock()
    func add(_ k: String, _ d: Decoded) { lock.lock(); v[k] = d; lock.unlock() }
    func get(_ k: String) -> Decoded? { lock.lock(); defer { lock.unlock() }; return v[k] }
}
