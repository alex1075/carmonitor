import Foundation

/// OBDb signalset model, schema v3 (https://github.com/OBDb). Mirrors the Android core's SignalSet.kt.
/// value = raw * mul / div + add; bix is an MSB-first bit offset into the payload after the response echo.
public struct Fmt: Sendable, Equatable {
    public var bix = 0, len = 8
    public var mul = 1.0, div = 1.0, add = 0.0
    public var sign = false
    public var min: Double? = nil, max: Double? = nil
    public var nullMin: Double? = nil, nullMax: Double? = nil
    public var unit: String? = nil
    public var map: [String: String]? = nil
    public init() {}
}

public struct Signal: Sendable, Equatable {
    public let id: String, name: String, path: String?
    public let fmt: Fmt
    public let hidden: Bool
    public let suggestedMetric: String?
}

public struct YearFilter: Sendable, Equatable {
    public let from: Int?, to: Int?, years: Set<Int>
    public init(from: Int? = nil, to: Int? = nil, years: Set<Int> = []) { self.from = from; self.to = to; self.years = years }
    /// OBDb semantics: year <= to, or year >= from, or year listed.
    public func matches(_ year: Int) -> Bool {
        (to != nil && year <= to!) || (from != nil && year >= from!) || years.contains(year)
    }
}

public struct Command: Sendable, Equatable {
    public let hdr: String, rax: String?, service: String, pid: String
    public let freq: Double
    public let filter: YearFilter?
    public let debug: Bool, validated: Bool
    public let source: String
    public let signals: [Signal]
    public var request: String { service + pid }
    public var key: String { hdr + ":" + request }
    public func appliesTo(year: Int?) -> Bool { year == nil || filter == nil || filter!.matches(year!) }
}

public struct SignalSet: Sendable {
    public let name: String
    public let commands: [Command]
    public init(name: String, commands: [Command]) { self.name = name; self.commands = commands }

    public func forYear(_ year: Int?, includeDebug: Bool = false) -> [Command] {
        commands.filter { $0.appliesTo(year: year) && (includeDebug || !$0.debug) }
    }

    public func signal(_ id: String) -> (Command, Signal)? {
        for c in commands { for s in c.signals where s.id == id { return (c, s) } }
        return nil
    }

    public struct ParseError: Error { public let message: String }

    public static func parse(name: String, json: Data) throws -> SignalSet {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw ParseError(message: "\(name): not a JSON object")
        }
        let cmds = (root["commands"] as? [Any] ?? []).compactMap { parseCommand($0 as? [String: Any] ?? [:], source: name) }
        return SignalSet(name: name, commands: cmds)
    }

    /// Later sets replace earlier ones' commands with the same header and request.
    public static func merge(name: String, _ sets: [SignalSet]) -> SignalSet {
        var order: [String] = [], byKey: [String: Command] = [:]
        for s in sets { for c in s.commands { if byKey[c.key] == nil { order.append(c.key) }; byKey[c.key] = c } }
        return SignalSet(name: name, commands: order.map { byKey[$0]! })
    }

    private static func bool(_ v: Any?) -> Bool? { (v as? NSNumber).flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? $0.boolValue : nil } }
    private static func int(_ v: Any?) -> Int? { (v as? NSNumber).flatMap { bool($0) == nil ? $0.intValue : nil } }
    private static func dbl(_ v: Any?) -> Double? { (v as? NSNumber).flatMap { bool($0) == nil ? $0.doubleValue : nil } }
    private static func str(_ v: Any?) -> String? {
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }

    private static func parseCommand(_ c: [String: Any], source: String) -> Command? {
        guard let cmd = c["cmd"] as? [String: Any], let (svc, pidAny) = cmd.first, let pid = str(pidAny) else { return nil }
        let hdr = str(c["hdr"]) ?? "7E0"
        let sigs: [Signal] = (c["signals"] as? [Any] ?? []).compactMap { any in
            guard let sm = any as? [String: Any], let id = str(sm["id"]) else { return nil }
            let f = sm["fmt"] as? [String: Any] ?? [:]
            var fmt = Fmt()
            fmt.bix = int(f["bix"]) ?? 0; fmt.len = int(f["len"]) ?? 8
            fmt.mul = dbl(f["mul"]) ?? 1; fmt.div = dbl(f["div"]) ?? 1; fmt.add = dbl(f["add"]) ?? 0
            fmt.sign = bool(f["sign"]) == true
            fmt.min = dbl(f["min"]); fmt.max = dbl(f["max"])
            fmt.nullMin = dbl(f["nullmin"]); fmt.nullMax = dbl(f["nullmax"])
            fmt.unit = str(f["unit"])
            if let m = f["map"] as? [String: Any] {
                fmt.map = m.mapValues { v in str((v as? [String: Any])?["value"]) ?? str(v) ?? "" }
            }
            return Signal(id: id, name: str(sm["name"]) ?? id, path: str(sm["path"]), fmt: fmt,
                          hidden: bool(sm["hidden"]) == true, suggestedMetric: str(sm["suggestedMetric"]))
        }
        var filter: YearFilter? = nil
        if let fm = c["filter"] as? [String: Any], !fm.isEmpty {
            filter = YearFilter(from: int(fm["from"]), to: int(fm["to"]), years: Set((fm["years"] as? [Any] ?? []).compactMap { int($0) }))
        }
        return Command(hdr: hdr, rax: str(c["rax"]) ?? defaultRx(hdr), service: svc.uppercased(), pid: pid.uppercased(),
                       freq: dbl(c["freq"]) ?? 1, filter: filter, debug: bool(c["dbg"]) == true,
                       validated: bool(c["validated"]) ?? true, source: source, signals: sigs)
    }

    /// The usual ISO 15765-4 reply address: request header + 8.
    public static func defaultRx(_ hdr: String) -> String? {
        guard let v = Int(hdr, radix: 16) else { return nil }
        return String(format: "%03X", v + 8)
    }
}

public enum Decoded: Sendable, Equatable {
    case num(Double)
    case text(String)
}

public enum Decoder {
    /// [len] bits from MSB-first bit offset [bix]; nil when out of range.
    public static func bits(_ data: Data, bix: Int, len: Int) -> Int64? {
        let b = [UInt8](data)
        if len <= 0 || len > 63 || bix < 0 || bix + len > b.count * 8 { return nil }
        var v: Int64 = 0
        for i in 0..<len {
            let bit = bix + i
            v = (v << 1) | Int64((b[bit / 8] >> (7 - bit % 8)) & 1)
        }
        return v
    }

    public static func decode(_ s: Signal, _ data: Data) -> Decoded? {
        let f = s.fmt
        guard var raw = bits(data, bix: f.bix, len: f.len) else { return nil }
        if let m = f.map { return .text(m[String(raw)] ?? String(raw)) }
        if f.sign && (raw >> (f.len - 1)) & 1 == 1 { raw -= Int64(1) << f.len }
        let v = Double(raw) * f.mul / f.div + f.add
        // Outside the signal's own declared range (1 % margin): a data bug, not a reading.
        let spanRaw = (f.max ?? 0) - (f.min ?? 0)
        let span = spanRaw > 0 ? spanRaw * 0.01 : 1e-6
        if let mn = f.min, v < mn - span { return nil }
        if let mx = f.max, v > mx + span { return nil }
        if let nx = f.nullMax, v >= nx - 1e-9 { return nil }
        if let nn = f.nullMin, v <= nn + 1e-9 { return nil }
        return .num(v)
    }

    public static func decodeAll(_ c: Command, _ data: Data) -> [String: Decoded] {
        var out: [String: Decoded] = [:]
        for s in c.signals { if let d = decode(s, data) { out[s.id] = d } }
        return out
    }
}
