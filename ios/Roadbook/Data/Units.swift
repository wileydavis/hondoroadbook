import Foundation

/// Distance/speed formatting. Default is miles/feet; metric can be toggled in Maps. Same rounding as Android.
enum Units {
    static var metric = false

    static func distance(_ m: Double) -> String {
        if metric {
            if m < 950 { return "\(Int((m / 10).rounded()) * 10) m" }
            if m < 10_000 { return String(format: "%.1f km", m / 1000) }
            return "\(Int((m / 1000).rounded())) km"
        }
        let ft = m * 3.28084
        if ft < 950 { return "\(Int((ft / 10).rounded()) * 10) ft" }
        if ft < 52_800 { return String(format: "%.1f mi", ft / 5280) }
        return "\(Int((ft / 5280).rounded())) mi"
    }

    /// Rounded for the HUD: coarse steps so the number doesn't flicker.
    static func cue(_ m: Double) -> String {
        if metric {
            if m < 100 { return "\(Int((m / 10).rounded()) * 10) m" }
            if m < 1000 { return "\(Int((m / 50).rounded()) * 50) m" }
            if m < 10_000 { return String(format: "%.1f km", m / 1000) }
            return "\(Int((m / 1000).rounded())) km"
        }
        let ft = m * 3.28084
        if ft < 300 { return "\(Int((ft / 10).rounded()) * 10) ft" }
        if ft < 1000 { return "\(Int((ft / 50).rounded()) * 50) ft" }
        if ft < 2640 { return "\(Int((ft / 100).rounded()) * 100) ft" }
        if ft < 52_800 { return String(format: "%.1f mi", ft / 5280) }
        return "\(Int((ft / 5280).rounded())) mi"
    }

    static func speed(_ ms: Double) -> String { metric ? "\(Int((ms * 3.6).rounded())) km/h" : "\(Int((ms * 2.23694).rounded())) mph" }

    static func time(_ sec: Double) -> String {
        let m = Int((sec / 60).rounded())
        return m < 60 ? "\(m) min" : "\(m / 60) h \(m % 60) min"
    }

    static func mb(_ bytes: Int64) -> String { bytes > 1_000_000_000 ? String(format: "%.1f GB", Double(bytes) / 1e9) : "\(bytes / (1024 * 1024)) MB" }
}
