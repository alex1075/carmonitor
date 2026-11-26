import Foundation
import Security

/// Choices kept on the phone. The webhook URL is the only credential, so it lives in the Keychain.
final class Settings {
    private let d = UserDefaults.standard

    var adapterId: UUID? {
        get { d.string(forKey: "adapterId").flatMap(UUID.init) }
        set { d.set(newValue?.uuidString, forKey: "adapterId") }
    }
    var adapterName: String { get { d.string(forKey: "adapterName") ?? "" } set { d.set(newValue, forKey: "adapterName") } }
    var vehicleRepo: String { get { d.string(forKey: "vehicleRepo") ?? "" } set { d.set(newValue, forKey: "vehicleRepo") } }
    var vehicleLabel: String { get { d.string(forKey: "vehicleLabel") ?? "" } set { d.set(newValue, forKey: "vehicleLabel") } }
    var vehicleFile: String { get { d.string(forKey: "vehicleFile") ?? "" } set { d.set(newValue, forKey: "vehicleFile") } }
    /// 0 = not set.
    var year: Int { get { d.integer(forKey: "year") } set { d.set(newValue, forKey: "year") } }
    var gps: Bool { get { d.object(forKey: "gps") as? Bool ?? true } set { d.set(newValue, forKey: "gps") } }
    var uploadPast: Bool { get { d.bool(forKey: "uploadPast") } set { d.set(newValue, forKey: "uploadPast") } }
    var autoStart: Bool { get { d.bool(forKey: "autoStart") } set { d.set(newValue, forKey: "autoStart") } }

    /// Command keys that answered, per vehicle and year, so discovery runs once.
    func supported(for key: String) -> [String]? { d.stringArray(forKey: "supported." + key) }
    func setSupported(_ keys: [String]?, for key: String) { d.set(keys, forKey: "supported." + key) }
    var vehicleKey: String { (vehicleRepo.isEmpty ? "generic" : vehicleRepo) + "." + String(year) }

    var webhookUrl: String {
        get { Keychain.get("webhookUrl") ?? "" }
        set { Keychain.set("webhookUrl", newValue) }
    }
}

enum Keychain {
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "carmonitor", kSecAttrAccount as String: key]
    }

    static func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String) {
        SecItemDelete(query(key) as CFDictionary)
        if value.isEmpty { return }
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
