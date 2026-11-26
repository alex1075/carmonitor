import Foundation
import CarMonitorKit

final class FakeHook: @unchecked Sendable {
    var bodies: [[String: Any]] = []
    var code = 200
    var have: [String: Int64] = [:]
    func post(_ url: URL, _ body: Data) async throws -> (Int, Data) {
        let j = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        bodies.append(j)
        if code != 200 { return (code, Data()) }
        let drive = j["drive"] as! String
        let reply: [String: Any] = ["ok": true, "have": have[drive].map { ["start": 0, "end": $0] as Any } ?? NSNull()]
        return (200, try JSONSerialization.data(withJSONObject: reply))
    }
    var rowPosts: [[[Any]]] { bodies.compactMap { $0["rows"] as? [[Any]] }.filter { !$0.isEmpty } }
}

func uploaderTests() async throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cm-up-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir) }
    let f = dir.appendingPathComponent("drive-20261125-082845.csv")
    let t = Int64(1_795_595_335_000)
    try Data(("ts_ms,key,signal,value,raw\n\(t),7E0:010C,RPM,750,\n\(t),7E0:22042B,raw,319,013F\n" +
              "\(t + 1),gps,GPS_LAT,55.95,\n\(t + 2),7E0:0101,DTC,P0401,\n\(t + 3),7E0:010D,VSS,5").utf8).write(to: f)
    let hook = FakeHook()
    let up = Uploader(url: URL(string: "https://ha.example/api/webhook/x")!, dir: dir, uploadPast: true, post: hook.post)
    await up.setDefs(["RPM": SignalDef(name: "Engine RPM", unit: "rpm", expire: 60)])
    eq(await up.pending().map { $0.lastPathComponent }, ["drive-20261125-082845.csv"], "pending file")
    let n = await up.flush()
    eq(n, 3, "three whole rows accepted (raw row and partial line left out)")
    eq(hook.bodies.first?["drive"] as? String, "20261125-082845", "drive id from file name")
    eq((hook.bodies.first?["rows"] as? [Any])?.count, 0, "first asks what HA has")
    let rows = hook.rowPosts.first ?? []
    eq(rows.count, 3, "rows posted")
    check(rows.contains { ($0[1] as? String) == "RPM" && ($0[2] as? Double) == 750 && ($0[0] as? Int64) == t }, "RPM row numeric with its time")
    check(rows.contains { ($0[1] as? String) == "DTC" && ($0[2] as? String) == "P0401" }, "text value kept as text")
    check(hook.bodies.contains { ($0["defs"] as? [String: Any])?["RPM"] != nil }, "defs sent")
    let connected = await up.connected
    check(connected, "connected after 2xx")
    let before = hook.rowPosts.count
    eq(await up.flush(), 0, "nothing new")
    eq(hook.rowPosts.count, before, "no rows re-sent")
    let h = try FileHandle(forWritingTo: f); try h.seekToEnd(); try h.write(contentsOf: Data("0,\n".utf8)); try h.close()
    eq(await up.flush(), 1, "completed last line sent")
    eq(await up.pending().count, 0, "nothing pending")
    let h2 = try FileHandle(forWritingTo: f); try h2.seekToEnd(); try h2.write(contentsOf: Data("\(t + 9),7E0:01".utf8)); try h2.close()
    eq(await up.pending().count, 0, "a partial last line (app killed mid-write) is not pending")

    // HA already has a drive this phone never sent: skipped, not re-sent.
    let g = dir.appendingPathComponent("drive-20261126-090000.csv")
    try Data("ts_ms,key,signal,value,raw\n\(t + 50),7E0:010C,RPM,800,\n".utf8).write(to: g)
    hook.have["20261126-090000"] = t + 50
    let posts = hook.rowPosts.count
    _ = await up.flush()
    eq(hook.rowPosts.count, posts, "drive HA has is skipped")
    eq(await up.pending().count, 0, "skipped drive no longer pending")

    // A failing webhook keeps everything for later.
    let k = dir.appendingPathComponent("drive-20261127-090000.csv")
    try Data("ts_ms,key,signal,value,raw\n\(t + 90),7E0:010C,RPM,900,\n".utf8).write(to: k)
    hook.code = 404
    eq(await up.flush(), 0, "404 accepts nothing")
    let err = await up.lastError
    check(err?.contains("404") == true, "404 explained: \(err ?? "nil")")
    eq(await up.pending().count, 1, "still pending after 404")
    hook.code = 200
    eq(await up.flush(), 1, "sent once HA is back")

    // Files older than the first use count only with uploadPast.
    let dir2 = FileManager.default.temporaryDirectory.appendingPathComponent("cm-up2-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: dir2, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir2) }
    let old = dir2.appendingPathComponent("drive-20200101-000000.csv")
    try Data("ts_ms,key,signal,value,raw\n1577836800000,7E0:010C,RPM,800,\n".utf8).write(to: old)
    try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_577_836_800)], ofItemAtPath: old.path)
    let fresh = Uploader(url: URL(string: "https://ha.example/x")!, dir: dir2, uploadPast: false, post: hook.post)
    eq(await fresh.pending().count, 0, "old drive ignored without uploadPast")
    eq(Uploader.driveId("drive-2026 11.csv"), "2026_11", "drive id sanitised")
}
