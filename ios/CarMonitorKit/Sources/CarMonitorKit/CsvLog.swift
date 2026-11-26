import Foundation

/// One drive's CSV, in the Android format: ts_ms,key,signal,value,raw. Each row is written whole and at once,
/// so a killed app leaves at most a partial last line, which the uploader never sends.
public final class CsvLog: @unchecked Sendable {
    public let url: URL
    private let handle: FileHandle
    private let lock = NSLock()

    public static func fileName(for start: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd-HHmmss"
        return "drive-" + f.string(from: start) + ".csv"
    }

    public init(dir: URL, start: Date = Date()) throws {
        url = dir.appendingPathComponent(CsvLog.fileName(for: start))
        if !FileManager.default.fileExists(atPath: url.path) {
            try Data("ts_ms,key,signal,value,raw\n".utf8).write(to: url)
        }
        handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
    }

    public func row(ts: Int64, key: String, signal: String, value: String, raw: String) {
        let clean = value.replacingOccurrences(of: ",", with: " ").replacingOccurrences(of: "\n", with: " ")
        let line = "\(ts),\(key),\(signal),\(clean),\(raw)\n"
        lock.lock(); defer { lock.unlock() }
        try? handle.write(contentsOf: Data(line.utf8))
    }

    public func close() { lock.lock(); try? handle.close(); lock.unlock() }
}
