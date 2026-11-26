import Foundation

/// Names, HA units and expiry for the integration's sensors, as the Android app derives them (HomeAssistant.kt).
public enum HaDefs {
    private static let unit: [String: String] = ["celsius": "°C", "kilopascal": "kPa", "kilometersPerHour": "km/h", "kilometers": "km", "percent": "%", "volts": "V", "rpm": "rpm", "minutes": "min", "seconds": "s", "bars": "bar", "degrees": "°", "gramsPerLiter": "g/L", "millimeters": "mm", "gramsPerSecond": "g/s", "litersPerHour": "L/h", "newtonMeters": "N·m", "hertz": "Hz", "milliamperes": "mA", "amps": "A", "kilowatts": "kW", "kilowattHours": "kWh", "liters": "L", "psi": "psi"]

    public static func haUnit(_ obdb: String?) -> String? { obdb.flatMap { unit[$0] } }

    /// Expiry follows the poll tier: fast 60 s, medium 180 s, slow 900 s.
    public static func defs(for commands: [Command]) -> [String: SignalDef] {
        var out: [String: SignalDef] = [:]
        for c in commands {
            let expire = [2: 60, 1: 180, 0: 900][Logger.tier(c)]!
            for s in c.signals where !s.hidden {
                let text = s.fmt.map != nil
                out[s.id] = SignalDef(name: s.name, unit: text ? nil : haUnit(s.fmt.unit), expire: expire, text: text)
            }
        }
        return out
    }
}
