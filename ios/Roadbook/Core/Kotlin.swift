import Foundation
import CoreLocation
import FoldCore

/// Small bridges over the Kotlin/Native seam. The engine (routing, GPX, the follower, the cue rules) is the
/// same Kotlin that runs on Android, exported as FoldCore.framework; these helpers keep the Swift side tidy.

/// Milliseconds on a monotonic clock, for everything time-based (a wrong wall clock must never confuse dead reckoning).
func nowMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

extension KotlinDoubleArray {
    /// a copy as a Swift array (one bridge call per element; fine up to a few hundred thousand points)
    var swift: [Double] { var out = [Double](); out.reserveCapacity(Int(size)); for i in 0..<size { out.append(get(index: i)) }; return out }
    static func of(_ a: [Double]) -> KotlinDoubleArray { let k = KotlinDoubleArray(size: Int32(a.count)); for (i, v) in a.enumerated() { k.set(index: Int32(i), value: v) }; return k }
}

extension KotlinLongArray {
    static func of(_ a: [Int64]) -> KotlinLongArray { let k = KotlinLongArray(size: Int32(a.count)); for (i, v) in a.enumerated() { k.set(index: Int32(i), value: v) }; return k }
}

extension Fix {
    /// a fix from Core Location; timeMs is on the uptime clock
    convenience init(_ l: CLLocation) {
        let ageMs = Int64(Date().timeIntervalSince(l.timestamp) * 1000)
        self.init(lat: l.coordinate.latitude, lon: l.coordinate.longitude,
                  speed: l.speed >= 0 ? l.speed : -1, bearing: l.course >= 0 ? l.course : -1,
                  timeMs: nowMs() - max(0, ageMs), accuracy: l.horizontalAccuracy >= 0 ? l.horizontalAccuracy : 50)
    }
    /// a planning fix: a point with no speed, no bearing, now
    convenience init(_ c: CLLocationCoordinate2D) { self.init(lat: c.latitude, lon: c.longitude, speed: -1, bearing: -1, timeMs: nowMs(), accuracy: 50) }
}

extension Route {
    var coords: [CLLocationCoordinate2D] {
        var out = [CLLocationCoordinate2D](); out.reserveCapacity(Int(size))
        for i in 0..<size { out.append(CLLocationCoordinate2D(latitude: lats.get(index: i), longitude: lons.get(index: i))) }
        return out
    }
    var maneuverList: [Maneuver] { maneuvers }
    func coordinate(ofVertex v: Int32) -> CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lats.get(index: v), longitude: lons.get(index: v)) }
}

extension NavView {
    var groupList: [Maneuver] { group }
}

extension GpxTrack {
    var cueList: [Cue] { cues }
    var length: Double {
        var d = 0.0
        if lats.size < 2 { return 0 }
        for i in 1..<lats.size { d += Geo.shared.fastDistance(lat1: lats.get(index: i - 1), lon1: lons.get(index: i - 1), lat2: lats.get(index: i), lon2: lons.get(index: i)) }
        return d
    }
}

extension Turn {
    static let all: [Turn] = [.depart, .straight, .slightLeft, .slightRight, .left, .right, .sharpLeft, .sharpRight, .uturn, .keepLeft, .keepRight, .roundabout, .note, .danger, .arrive]
    /// "SLIGHT_LEFT" → "Slight left", as the turn list prints it
    var title: String { name.lowercased().replacingOccurrences(of: "_", with: " ").capitalizedFirst }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
    /// a safe file name
    var fileSafe: String { replacingOccurrences(of: "[^\\w .-]+", with: "_", options: .regularExpression).trimmingCharacters(in: .whitespaces) }
}

/// An error with a message the rider can read.
struct Fail: LocalizedError { let msg: String; init(_ m: String) { msg = m }; var errorDescription: String? { msg } }

extension Error {
    var text: String { (self as? LocalizedError)?.errorDescription ?? localizedDescription }
}

func fmt(_ c: CLLocationCoordinate2D) -> String { String(format: "%.5f, %.5f", c.latitude, c.longitude) }
