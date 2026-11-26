import Foundation

/// Hard allowlist for everything written to the ELM327. A copy of the Android core's RequestGuard.kt:
/// both must agree with spec/guard-vectors.json. Anything not explicitly read-only is refused.
///
/// Allowed services: 01 (1-6 PIDs), 03, 07, 09 (one PID), 22 (one 2-byte DID), each optionally followed by the
/// ELM327's single-digit response count. AT commands only from the list below; 11-bit CAN headers only.
public enum RequestGuard {
    public struct Refused: Error, CustomStringConvertible {
        public let message: String
        public var description: String { message }
    }

    private static let serviceLen: [String: Set<Int>] = ["01": Set(1...6), "03": [0], "07": [0], "09": [1], "22": [2]]

    private static let atAllowed: [String] = [
        "^Z$", "^WS$", "^D$", "^I$", "^RV$", "^DP$", "^DPN$",
        "^E[01]$", "^L[01]$", "^S[01]$", "^H[01]$",
        "^CAF1$",   // CAF0 would turn "0104" into a raw Mode 04 (clear DTCs) frame
        "^AT[012]$", "^ST[0-9A-F]{2}$", "^SP[0-9A-C]$", "^TP[0-9A-C]$",
        "^SH[0-9A-F]{3}$",   // 11-bit CAN header only
        "^CRA[0-9A-F]{3}$", "^CRA$", "^CFC[01]$",
    ]

    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: .regularExpression) != nil
    }

    public static func normalise(_ cmd: String) -> String {
        cmd.replacingOccurrences(of: " ", with: "").uppercased().trimmingCharacters(in: .whitespaces)
    }

    public static func check(_ raw: String) throws {
        let c = normalise(raw)
        if c.isEmpty { throw Refused(message: "empty command") }
        if c.unicodeScalars.contains(where: { $0 == "\r" || $0 == "\n" }) { throw Refused(message: "embedded newline") }
        if c.hasPrefix("AT") {
            let arg = String(c.dropFirst(2))
            if !atAllowed.contains(where: { matches(arg, $0) }) { throw Refused(message: "AT command not allowed: \(c)") }
            return
        }
        if !c.unicodeScalars.allSatisfy({ "0123456789ABCDEF".unicodeScalars.contains($0) }) {
            throw Refused(message: "not a hex request: \(c)")
        }
        // Optional ELM327 response count: one trailing digit 1-F after a whole number of bytes.
        let body = c.count % 2 == 1 && c.last != "0" ? String(c.dropLast()) : c
        if body.isEmpty || body.count % 2 != 0 { throw Refused(message: "not a hex request: \(c)") }
        let svc = String(body.prefix(2))
        guard let lens = serviceLen[svc] else { throw Refused(message: "service \(svc) is not on the read-only allowlist") }
        if !lens.contains((body.count - 2) / 2) { throw Refused(message: "bad length for service \(svc): \(c)") }
    }

    public static func isAllowed(_ raw: String) -> Bool { (try? check(raw)) != nil }

    /// Only 11-bit CAN headers and receive filters.
    public static func routeAllowed(hdr: String, rax: String?) -> Bool {
        matches(hdr.uppercased(), "^[0-9A-F]{3}$") && (rax == nil || matches(rax!.uppercased(), "^[0-9A-F]{3}$"))
    }
}
