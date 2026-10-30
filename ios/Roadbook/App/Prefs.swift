import Foundation
import FoldCore
import UIKit

/// The only persisted settings. No history, no destinations, no tracks. Same keys and defaults as the Android app.
final class Prefs: ObservableObject {
    private let d = UserDefaults.standard

    @Published var night: Bool { didSet { d.set(night, forKey: "night") } }
    @Published var metric: Bool { didSet { d.set(metric, forKey: "metric"); Units.metric = metric } }
    /// draw installed terrain (hillshade) packs under the roads
    @Published var terrain: Bool { didSet { d.set(terrain, forKey: "terrain") } }
    /// the noon-sun map: black ground, wide bright roads, big labels, nothing else; dark interface, least power
    @Published var sun: Bool { didSet { d.set(sun, forKey: "sun") } }
    /// which layer file the map is drawn from right now
    var flavor: String { sun ? "sun" : night ? "dark" : "light" }
    /// draw the packs' places (fuel, camping, water, beds, repair, food) on the map when zoomed in
    @Published var places: Bool { didSet { d.set(places, forKey: "places") } }
    @Published var sound: Bool { didSet { d.set(sound, forKey: "sound") } }
    /// battery saver: the screen dims and the phone may lock; the lock screen carries the next cue and lights for it
    @Published var batterySaver: Bool { didSet { d.set(batterySaver, forKey: "battery") } }
    /// the interface orientation to hold ("" = follow the phone)
    @Published var orientation: String { didSet { d.set(orientation, forKey: "orientation") } }
    @Published var profileName: String { didSet { d.set(profileName, forKey: "profile") } }
    /// modes shown on the compare screen (Profile names)
    @Published var modes: [String] { didSet { d.set(modes, forKey: "modes") } }
    /// the rider has seen the terms once (first launch)
    var accepted: Bool { get { d.bool(forKey: "accepted") } set { d.set(newValue, forKey: "accepted") } }

    var profile: Profile {
        get { Profile.companion.parse(name: profileName) }
        set { profileName = newValue.name }
    }
    var modeSet: [Profile] {
        get { let m = modes.compactMap { Profiles.byName($0) }; return m.isEmpty ? [Profile.fast] : Profiles.all.filter { m.contains($0) } }
        set { modes = newValue.map { $0.name } }
    }

    init() {
        night = d.bool(forKey: "night")
        metric = d.bool(forKey: "metric")
        terrain = d.object(forKey: "terrain") == nil ? true : d.bool(forKey: "terrain")
        places = d.object(forKey: "places") == nil ? true : d.bool(forKey: "places")
        sun = d.bool(forKey: "sun")
        sound = d.bool(forKey: "sound")
        batterySaver = d.bool(forKey: "battery")
        orientation = d.string(forKey: "orientation") ?? ""
        profileName = d.string(forKey: "profile") ?? "FAST"
        modes = d.stringArray(forKey: "modes") ?? ["FAST", "BACKROADS", "RIDE"]
        Units.metric = metric
    }
}

/// The routing modes, in the order the compare screen shows them.
enum Profiles {
    static let all: [Profile] = [.fast, .towing, .backroads, .dirt, .ride, .quiet, .gravel]
    static func byName(_ n: String) -> Profile? { all.first { $0.name == n } }
    static func color(_ p: Profile) -> UIColor { UIColor(hex: p.color) }
}

extension UIColor {
    convenience init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces); if s.hasPrefix("#") { s.removeFirst() }
        var v: UInt64 = 0; Scanner(string: s).scanHexInt64(&v)
        if s.count == 8 { self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: CGFloat((v >> 24) & 0xFF) / 255) }
        else { self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1) }
    }
}
