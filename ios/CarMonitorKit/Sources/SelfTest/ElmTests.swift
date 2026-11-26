import Foundation
import CarMonitorKit

/// A scripted adapter: answers AT commands with OK and known requests with canned CAN frames.
final class FakeEcu: ElmTransport, @unchecked Sendable {
    var sent: [String] = []
    var replies: [String: String] = [
        "010C": "7E804410C0BB8000000", "010C1": "7E804410C0BB8000000",
        "010D": "7E803410D32000000", "010D1": "7E803410D32000000",
        "010C0D": "7E806410C0BB80D3200",
        "0902": "7E8101449020149424D\r7E82143344B46413630\r7E82231323334353637",
        "22042B": "7E8037F2278000000\r7E80562042B013F0000",
    ]
    private var pending = ""
    func send(_ line: String) async throws {
        sent.append(line)
        if line == "ATZ" { pending = "ELM327 v1.5"; return }
        if line.hasPrefix("AT") { pending = "OK"; return }
        pending = replies[line] ?? "NO DATA"
    }
    func readUntilPrompt(timeout: Duration) async throws -> String { defer { pending = "" }; return pending + "\r\r" }
}

func elmTests() async throws {
    if case .positive(let d, let from, _) = Elm327.parse(req: "010C", raw: "7E804410C1AF8000000\r\r", rax: "7E8") {
        eq(d, Data([0x1A, 0xF8]), "SF positive"); eq(from, "7E8", "from")
    } else { check(false, "SF positive") }
    eq(Elm327.parse(req: "22FFFF", raw: "7E8037F2231000000\r", rax: "7E8"), .negative(service: 0x22, nrc: 0x31), "NRC 31")
    if case .positive(let d, _, _) = Elm327.parse(req: "22042B", raw: "7E8037F2278000000\r7E80562042B013F0000\r", rax: "7E8") {
        eq(d, Data([0x01, 0x3F]), "78 then positive")
    } else { check(false, "78 then positive") }
    if case .positive(let d, _, let frames) = Elm327.parse(req: "0902", raw: "7E8101449020149424D\r7E82143344B46413630\r7E82231323334353637\r", rax: "7E8") {
        eq(d.count, 18, "multi-frame VIN"); check(frames > 1, "frames counted")
    } else { check(false, "multi-frame VIN") }
    eq(Elm327.parse(req: "010C", raw: "NO DATA\r\r", rax: "7E8"), .noData, "NO DATA")
    if case .error = Elm327.parse(req: "010C", raw: "?\r\r", rax: "7E8") {} else { check(false, "'?' is an error") }
    eq(Elm327.parse(req: "010C", raw: "7E904410C1AF8000000\r", rax: "7E8"), .noData, "other ECU ignored")
    if case .error = Elm327.parse(req: "22042B", raw: "7E80562042C013F0000\r", rax: "7E8") {} else { check(false, "DID echo mismatch") }
    if case .positive = Elm327.parse(req: "010C", raw: "7E8 04 41 0C 1A F8 00 00 00\r", rax: "7E8") {} else { check(false, "spaces tolerated") }
    eq(Elm327.parse(req: "010C", raw: "", rax: "7E8"), .noData, "empty is no data")

    let fake = FakeEcu()
    let elm = Elm327(transport: fake)
    try await elm.initialise()
    eq(fake.sent.first, "ATZ", "init starts with ATZ")
    check(fake.sent.contains("ATCAF1") && !fake.sent.contains("ATCAF0"), "CAF1 only")
    let adapter = await elm.adapterId
    eq(adapter, "ELM327 v1.5", "adapter id")
    let before = fake.sent.count
    var threw = false
    do { _ = try await elm.request("2E042B01") } catch is RequestGuard.Refused { threw = true }
    check(threw, "write refused")
    threw = false
    do { _ = try await elm.request("010C", hdr: "18DA10F1", rax: nil) } catch is RequestGuard.Refused { threw = true }
    check(threw, "29-bit header refused")
    eq(fake.sent.count, before, "nothing sent for refused requests")
    if case .positive(let d, _, _) = try await elm.request("010C") { eq(d, Data([0x0B, 0xB8]), "RPM request") } else { check(false, "RPM request") }
    check(fake.sent.contains("ATSH7E0") && fake.sent.contains("ATCRA7E8"), "header set before request")
    _ = try await elm.request("010C")
    eq(fake.sent.last, "010C1", "second time uses a response count")
    if case .positive(let d, _, _) = try await elm.request("22042B") { eq(d, Data([0x01, 0x3F]), "pending then positive") } else { check(false, "22 request") }
    eq(try await elm.request("0105"), .noData, "unsupported gives no data")
}
