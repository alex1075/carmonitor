import Foundation

public struct SignalDef: Sendable, Equatable {
    public let name: String, unit: String?, expire: Int?, text: Bool
    public init(name: String, unit: String?, expire: Int?, text: Bool = false) {
        self.name = name; self.unit = unit; self.expire = expire; self.text = text
    }
}

/// Uplink to the CarMonitor Home Assistant integration's webhook. Mirrors WebhookUplink in Webhook.kt:
/// the drive CSVs are the queue; each file's accepted byte offset is kept in .sent (name TAB offset), so a
/// dropout only delays rows, which keep their own timestamps. Files older than the first use (.sent-since)
/// are sent only with uploadPast. A drive with no .sent record is first checked against what HA has.
public actor Uploader {
    public static let maxRows = 5000
    public typealias Post = @Sendable (URL, Data) async throws -> (Int, Data)

    private let url: URL, dir: URL, uploadPast: Bool
    private let now: @Sendable () -> Date
    private let post: Post
    private let log: @Sendable (String) -> Void
    private var defs: [String: SignalDef] = [:]
    private var status: String?
    public private(set) var connected = false
    public private(set) var lastError: String?
    private var lastOk = Date.distantPast
    private var sentFile: URL { dir.appendingPathComponent(".sent") }
    private var baselineFile: URL { dir.appendingPathComponent(".sent-since") }

    public init(url: URL, dir: URL, uploadPast: Bool, now: @escaping @Sendable () -> Date = { Date() },
                log: @escaping @Sendable (String) -> Void = { _ in }, post: @escaping Post = Uploader.httpPost) {
        self.url = url; self.dir = dir; self.uploadPast = uploadPast; self.now = now; self.log = log; self.post = post
    }

    public func setDefs(_ d: [String: SignalDef]) { defs.merge(d) { _, new in new } }
    public func setStatus(_ s: String?) { status = s }

    public static func driveId(_ fileName: String) -> String {
        var s = fileName
        if s.hasPrefix("drive-") { s.removeFirst(6) }
        if s.hasSuffix(".csv") { s.removeLast(4) }
        return String(s.replacingOccurrences(of: "[^A-Za-z0-9_.-]", with: "_", options: .regularExpression).prefix(40))
    }

    private func offsets() -> [String: Int64] {
        guard let t = try? String(contentsOf: sentFile, encoding: .utf8) else { return [:] }
        var o: [String: Int64] = [:]
        for l in t.split(separator: "\n") {
            let p = l.split(separator: "\t")
            if p.count == 2, let v = Int64(p[1]) { o[String(p[0])] = v }
        }
        return o
    }

    private func saveOffsets(_ o: [String: Int64]) {
        let text = o.map { $0.key + "\t" + String($0.value) + "\n" }.joined()
        try? Data(text.utf8).write(to: sentFile, options: .atomic)
    }

    private func driveFiles() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.hasPrefix("drive-") && $0.hasSuffix(".csv") }.sorted().map { dir.appendingPathComponent($0) }
    }

    private static func size(_ f: URL) -> Int64 { ((try? FileManager.default.attributesOfItem(atPath: f.path))?[.size] as? NSNumber)?.int64Value ?? 0 }

    /// Drive files with rows not yet accepted, oldest first.
    public func pending() -> [URL] {
        if !FileManager.default.fileExists(atPath: baselineFile.path) {
            try? Data(String(Int64(now().timeIntervalSince1970 * 1000)).utf8).write(to: baselineFile)
        }
        let since = (try? String(contentsOf: baselineFile, encoding: .utf8)).flatMap { Int64($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
        let o = offsets()
        return driveFiles().filter { f in
            let mod = ((try? FileManager.default.attributesOfItem(atPath: f.path))?[.modificationDate] as? Date) ?? .distantPast
            return (uploadPast || Int64(mod.timeIntervalSince1970 * 1000) >= since) && Uploader.wholeSize(f) > (o[f.lastPathComponent] ?? 0)
        }
    }

    /// Send what is pending; the number of rows accepted.
    @discardableResult
    public func flush() async -> Int {
        var accepted = 0
        var o = offsets()
        var rounds = 0
        var stateSent = false
        let files = pending()
        for f in files {
            let name = f.lastPathComponent
            if o[name] == nil, await alreadyThere(f) { o[name] = Uploader.size(f); saveOffsets(o); continue }
            while rounds < 40 {
                rounds += 1
                let off = o[name] ?? 0
                let (rows, used) = Uploader.readRows(f, from: off, max: Uploader.maxRows)
                if used == 0 { break }
                if !(await send(drive: Uploader.driveId(name), rows: rows, withState: true)) { return accepted }
                stateSent = true
                accepted += rows.count
                o[name] = off + used; saveOffsets(o)
            }
        }
        if !stateSent && (status != nil || !defs.isEmpty) {
            _ = await send(drive: driveFiles().last.map { Uploader.driveId($0.lastPathComponent) } ?? "live", rows: [], withState: true)
        }
        return accepted
    }

    /// An empty post when nothing went out for a minute: keeps Connected honest without changing HA's status.
    public func probe() async {
        if now().timeIntervalSince(lastOk) < 60 { return }
        _ = await send(drive: "live", rows: [], withState: false)
    }

    private func alreadyThere(_ f: URL) async -> Bool {
        guard let last = Uploader.lastTs(f) else { return false }
        guard let body = try? JSONSerialization.data(withJSONObject: ["drive": Uploader.driveId(f.lastPathComponent), "rows": []] as [String: Any]),
              let (code, reply) = try? await post(url, body), (200..<300).contains(code),
              let j = try? JSONSerialization.jsonObject(with: reply) as? [String: Any],
              let have = j["have"] as? [String: Any], let end = (have["end"] as? NSNumber)?.int64Value else { return false }
        if end >= last { log("drive \(Uploader.driveId(f.lastPathComponent)) is already in Home Assistant, not sending it again") }
        return end >= last
    }

    private func send(drive: String, rows: [[Any]], withState: Bool) async -> Bool {
        var body: [String: Any] = ["drive": drive, "rows": rows]
        let st = withState ? status : nil
        let d = withState ? defs : [:]
        if let st { body["status"] = st }
        if !d.isEmpty {
            body["defs"] = d.mapValues { v -> [String: Any] in
                var m: [String: Any] = ["name": v.name]
                if let u = v.unit { m["unit"] = u }
                if let e = v.expire, (10...86400).contains(e) { m["expire"] = e }
                if v.text { m["text"] = true }
                return m
            }
        }
        let code: Int
        do {
            let data = try JSONSerialization.data(withJSONObject: body)
            code = try await post(url, data).0
        } catch {
            fail("unreachable: \(error.localizedDescription)")
            return false
        }
        if (200..<300).contains(code) {
            connected = true; lastError = nil; lastOk = now()
            if st != nil && status == st { status = nil }
            for (k, v) in d where defs[k] == v { defs[k] = nil }
            return true
        }
        fail(code == 404 ? "webhook not found (404): check the URL" : code == 400 ? "integration rejected the data (400)" : "HTTP \(code)")
        return false
    }

    private func fail(_ why: String) {
        connected = false
        if why != lastError { log("integration: " + why) }
        lastError = why
    }

    /// Rows from byte off, whole lines only: ([ts, signal, value] rows, bytes consumed). Raw rows are skipped.
    public static func readRows(_ f: URL, from off: Int64, max: Int) -> ([[Any]], Int64) {
        guard let h = try? FileHandle(forReadingFrom: f) else { return ([], 0) }
        defer { try? h.close() }
        guard (try? h.seek(toOffset: UInt64(off))) != nil, let buf = try? h.read(upToCount: 1 << 20), !buf.isEmpty else { return ([], 0) }
        var rows: [[Any]] = []
        var used: Int64 = 0
        var start = buf.startIndex
        for i in buf.indices where buf[i] == 0x0A {
            let line = String(decoding: buf[start..<i], as: UTF8.self)
            start = i + 1; used = Int64(start - buf.startIndex)
            let p = line.split(separator: ",", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
            if p.count >= 4, let ts = Int64(p[0]), p[2] != "raw", !p[3].isEmpty,
               p[2].range(of: "^[A-Za-z0-9_]{1,64}$", options: .regularExpression) != nil {
                if let v = Double(p[3]), v.isFinite { rows.append([ts, p[2], v]) } else { rows.append([ts, p[2], String(p[3].prefix(255))]) }
            }
            if rows.count >= max { break }
        }
        return (rows, used)
    }

    /// Bytes up to and including the last newline: a partial last line (app killed mid-write) isn't sendable.
    public static func wholeSize(_ f: URL) -> Int64 {
        let len = size(f)
        guard len > 0, let h = try? FileHandle(forReadingFrom: f) else { return 0 }
        defer { try? h.close() }
        let n = min(len, 65536)
        try? h.seek(toOffset: UInt64(len - n))
        guard let buf = try? h.read(upToCount: Int(n)), let i = buf.lastIndex(of: 0x0A) else { return len - n }
        return len - n + Int64(i - buf.startIndex) + 1
    }

    /// The newest timestamp in the file's last 8 KB.
    public static func lastTs(_ f: URL) -> Int64? {
        let len = size(f)
        guard len > 0, let h = try? FileHandle(forReadingFrom: f) else { return nil }
        defer { try? h.close() }
        let n = min(len, 8192)
        try? h.seek(toOffset: UInt64(len - n))
        guard let buf = try? h.read(upToCount: Int(n)) else { return nil }
        return String(decoding: buf, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: false).dropLast()
            .compactMap { Int64($0.split(separator: ",").first ?? "") }.max()
    }

    public static let httpPost: Post = { url, body in
        var r = URLRequest(url: url, timeoutInterval: 30)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = body
        let (data, resp) = try await URLSession.shared.data(for: r)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, data)
    }
}
