import Foundation

/// An OBDb vehicle repo: one per make+model ("Toyota-RAV4"), plus make-level fallbacks ("Toyota"). Mirrors Catalog.kt.
public struct VehicleRef: Sendable, Equatable, Hashable, Codable {
    public let repo: String, make: String, model: String
    public init(repo: String, make: String, model: String) { self.repo = repo; self.make = make; self.model = model }
    public var isMakeFallback: Bool { model.isEmpty }
    public var label: String { model.isEmpty ? make + " (all models)" : make + " " + model }
}

public enum Catalog {
    public static let org = "OBDb"
    public static let generic = "SAEJ1979"
    private static let nonVehicle: Set<String> = [generic, "obdb.community", ".github", ".schemas", ".vehicle-template", ".meta"]
    private static let multiwordMakes = ["Mercedes-Benz", "Land-Rover", "Alfa-Romeo", "Aston-Martin", "Rolls-Royce", "Lynk-Co"]

    public static func reposPageURL(_ page: Int) -> URL { URL(string: "https://api.github.com/orgs/\(org)/repos?per_page=100&page=\(page)&sort=full_name")! }
    public static func dirURL(repo: String) -> URL { URL(string: "https://api.github.com/repos/\(org)/\(repo)/contents/signalsets/v3")! }
    public static func rawURL(repo: String, file: String) -> URL { URL(string: "https://raw.githubusercontent.com/\(org)/\(repo)/main/signalsets/v3/\(file)")! }

    public static func parseRepoName(_ name: String) -> VehicleRef? {
        if nonVehicle.contains(name) || name.hasPrefix(".") { return nil }
        if let multi = multiwordMakes.first(where: { name == $0 || name.hasPrefix($0 + "-") }) {
            var rest = String(name.dropFirst(multi.count)); if rest.hasPrefix("-") { rest.removeFirst() }
            return VehicleRef(repo: name, make: multi.replacingOccurrences(of: "-", with: " "), model: rest.replacingOccurrences(of: "-", with: " "))
        }
        guard let i = name.firstIndex(of: "-") else { return VehicleRef(repo: name, make: name, model: "") }
        return VehicleRef(repo: name, make: String(name[..<i]), model: name[name.index(after: i)...].replacingOccurrences(of: "-", with: " "))
    }

    public static func parseReposPage(_ json: Data) -> [VehicleRef] {
        ((try? JSONSerialization.jsonObject(with: json)) as? [[String: Any]] ?? []).compactMap { m in
            if (m["archived"] as? Bool) == true { return nil }
            return (m["name"] as? String).flatMap(parseRepoName)
        }
    }

    public static func parseDirListing(_ json: Data) -> [String] {
        ((try? JSONSerialization.jsonObject(with: json)) as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }.filter { $0.hasSuffix(".json") }
    }

    /// The signalset file for year: the narrowest matching "YYYY-YYYY.json" or "YYYY.json", else default.json.
    public static func chooseFile(_ files: [String], year: Int?) -> String {
        guard let y = year else { return "default.json" }
        var best: (String, Int)?
        for f in files {
            let name = f.replacingOccurrences(of: ".json", with: "")
            let parts = name.split(separator: "-").compactMap { Int($0) }
            guard f.hasSuffix(".json"), parts.count == name.split(separator: "-").count, (1...2).contains(parts.count),
                  parts.allSatisfy({ (1000...9999).contains($0) }) else { continue }
            let a = parts[0], b = parts.last!
            if a <= y && y <= b && (best == nil || b - a < best!.1) { best = (f, b - a) }
        }
        return best?.0 ?? "default.json"
    }

    public static func search(_ all: [VehicleRef], query: String, make: String? = nil) -> [VehicleRef] {
        let words = query.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return all.filter { v in
            (make == nil || v.make.lowercased() == make!.lowercased()) &&
                words.allSatisfy { v.label.lowercased().contains($0) || v.repo.lowercased().contains($0) }
        }.sorted { a, b in
            (a.make.lowercased(), a.isMakeFallback ? 0 : 1, a.model.lowercased()) < (b.make.lowercased(), b.isMakeFallback ? 0 : 1, b.model.lowercased())
        }
    }

    public static func makeFallback(for v: VehicleRef, in all: [VehicleRef]) -> VehicleRef? {
        v.isMakeFallback ? nil : all.first { $0.isMakeFallback && $0.make.lowercased() == v.make.lowercased() }
    }

    private static let wmi: [String: String] = [
        "JMZ": "Mazda", "JM1": "Mazda", "JM3": "Mazda", "1YV": "Mazda", "4F2": "Mazda", "JTD": "Toyota",
        "JTE": "Toyota", "JTM": "Toyota", "JTN": "Toyota", "SB1": "Toyota", "NMT": "Toyota", "JHM": "Honda",
        "SHH": "Honda", "SHS": "Honda", "JN1": "Nissan", "SJN": "Nissan", "VSK": "Nissan", "WVW": "Volkswagen",
        "WVG": "Volkswagen", "WV2": "Volkswagen", "3VW": "Volkswagen", "WAU": "Audi", "WUA": "Audi", "TRU": "Audi",
        "WBA": "BMW", "WBS": "BMW", "WBY": "BMW", "WMW": "MINI", "WDB": "Mercedes Benz", "WDD": "Mercedes Benz",
        "W1K": "Mercedes Benz", "W1N": "Mercedes Benz", "WF0": "Ford", "1FA": "Ford", "1FT": "Ford", "1FM": "Ford",
        "SFA": "Ford", "VF1": "Renault", "VF3": "Peugeot", "VF7": "Citroen", "ZFA": "Fiat", "ZAR": "Alfa Romeo",
        "TMB": "Skoda", "VSS": "Seat", "YV1": "Volvo", "YV4": "Volvo", "SAL": "Land Rover", "SAJ": "Jaguar",
        "KMH": "Hyundai", "TMA": "Hyundai", "KNA": "Kia", "KND": "Kia", "U5Y": "Kia", "JF1": "Subaru",
        "JF2": "Subaru", "JS2": "Suzuki", "TSM": "Suzuki", "JMB": "Mitsubishi", "JA3": "Mitsubishi", "5YJ": "Tesla",
        "7SA": "Tesla", "LRW": "Tesla", "XP7": "Tesla", "W0L": "Opel", "W0V": "Opel", "1G1": "Chevrolet",
        "1GC": "Chevrolet", "2T1": "Toyota", "4T1": "Toyota", "5YF": "Toyota", "1HG": "Honda", "2HG": "Honda",
        "1N4": "Nissan", "WP0": "Porsche", "WP1": "Porsche", "SCC": "Lotus", "LBV": "BMW", "LVS": "Ford",
        "SCA": "Rolls Royce", "SCF": "Aston Martin", "ZHW": "Lamborghini", "ZFF": "Ferrari", 
    ]

    /// Make from a VIN's World Manufacturer Identifier (common ones only).
    public static func makeFromVin(_ vin: String?) -> String? {
        guard let v = vin, v.count >= 3 else { return nil }
        return wmi[String(v.prefix(3)).uppercased()]
    }

    private static let yearCodes = Array("ABCDEFGHJKLMNPRSTVWXY123456789")
    /// Model year from VIN position 10 (North-American rule); nil for VINs without a year code.
    public static func yearFromVin(_ vin: String?) -> Int? {
        guard let v = vin, v.count == 17 else { return nil }
        let c = Array(v)
        guard let i = yearCodes.firstIndex(of: c[9]) else { return nil }
        return (c[6].isNumber ? 1980 : 2010) + i
    }
}
