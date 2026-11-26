import Foundation

/// J1979 lets one service 01 request ask for up to six PIDs. Only PIDs whose data length the standard fixes are
/// batched, so the reply can be split. Mirrors MultiPid.kt.
public enum MultiPid {
    public static let max = 6

    private static let len: [Int: Int] = {
        var m: [Int: Int] = [:]
        for p in [0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x11, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x33,
                  0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x52, 0x5A, 0x5B, 0x5C, 0x61, 0x62, 0x8E] { m[p] = 1 }
        for p in [0x0C, 0x10, 0x1F, 0x21, 0x22, 0x23, 0x31, 0x3C, 0x3D, 0x3E, 0x3F, 0x42, 0x43, 0x44, 0x4D, 0x4E, 0x59,
                  0x5D, 0x5E, 0x63] { m[p] = 2 }
        return m
    }()

    public static func batchable(_ c: Command) -> Bool {
        c.service == "01" && c.pid.count == 2 && Int(c.pid, radix: 16).map { len[$0] != nil } == true
    }

    /// Split a positive reply's payload (first PID's echo already removed) into PID -> data. Stops at the first
    /// unknown byte, so padding or a skipped PID just leaves that PID out.
    public static func split(pids: [String], data: Data) -> [String: Data] {
        guard let first = pids.first.flatMap({ Int($0, radix: 16) }) else { return [:] }
        let seq = [first] + data.map { Int($0) }
        let wanted = Set(pids.map { $0.uppercased() })
        var out: [String: Data] = [:]
        var i = 0
        while i < seq.count {
            let pid = seq[i]
            guard let n = len[pid], i + 1 + n <= seq.count else { break }
            let id = String(format: "%02X", pid)
            if !wanted.contains(id) || out[id] != nil { break }
            out[id] = Data(seq[(i + 1)...(i + n)].map { UInt8($0) })
            i += 1 + n
        }
        return out
    }
}
