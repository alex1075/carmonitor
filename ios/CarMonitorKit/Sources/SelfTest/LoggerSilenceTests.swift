import Foundation
import CarMonitorKit

final class Clock: @unchecked Sendable {
    var t = Date(timeIntervalSince1970: 1_795_595_335)
    func now() -> Date { t += 5; return t }
}

/// Engine off, adapter still powered: every request says NO DATA. The logger must give up, not log forever.
/// And a command the guard refuses (from an OBDb file) must be dropped, not abort discovery.
func silenceAndRefusedTests(lib: SignalSet) async throws {
    let bad = try SignalSet.parse(name: "b", json: Data(#"{"commands":[{"hdr":"7E0","cmd":{"21":"01"},"freq":1,"signals":[{"id":"BAD","fmt":{"len":8}}]},{"hdr":"18DA10F1","cmd":{"22":"F190"},"freq":1,"signals":[{"id":"BAD2","fmt":{"len":8}}]}]}"#.utf8))
    let cmds = lib.commands.filter { ["010C", "010D"].contains($0.request) } + bad.commands
    let fake = FakeEcu()
    let elm = Elm327(transport: fake)
    try await elm.initialise()
    let clock = Clock()
    let logger = Logger(elm: elm, commands: cmds, csv: nil, now: { clock.now() }, onValue: { _, _, _ in })
    let ok = try await logger.discover()
    check(ok.contains("7E0:010C") && !ok.contains { $0.contains("2101") || $0.hasPrefix("18DA") }, "refused commands dropped: \(ok)")
    check(!fake.sent.contains("2101"), "refused command never sent")
    await logger.use(supported: ok)
    fake.replies = [:]   // the car goes quiet
    var silent = false
    do { try await logger.run(stop: { false }) } catch is Logger.Silent { silent = true }
    check(silent, "logger stops when the car stops answering")
}
