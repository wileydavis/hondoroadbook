import ActivityKit
import Foundation

/// The ride on the lock screen and the Dynamic Island: the next cue, how far, and how much is left.
/// Shared between the app (which starts and updates it) and the widget extension (which draws it).
struct RideActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        /// "400 ft", "ARRIVED", "OFF TRACK", "NO GPS"
        var distance: String
        /// "Left onto Dry Creek Road", "Destination", ""
        var instruction: String
        /// the Turn name (STRAIGHT, LEFT, DANGER…) for the glyph
        var turn: String
        /// "12.4 mi · 31 min"
        var remaining: String
        var arrived: Bool
        var offRoute: Bool
    }
    /// "To Dry Creek Road" / "Following Mojave loop"
    var title: String
}
