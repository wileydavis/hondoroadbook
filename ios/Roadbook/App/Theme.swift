import SwiftUI
import UIKit

/// The Android app's palette, day and night, as dynamic colors.
enum Theme {
    private static func dyn(_ light: String, _ dark: String) -> UIColor {
        UIColor { tc in tc.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) }
    }
    static let paper = dyn("#F7F4EE", "#121212")
    static let ink = dyn("#1B1B1B", "#F2F2F2")
    static let inkSoft = dyn("#5A5A5A", "#A8A8A8")
    static let card = dyn("#FFFFFF", "#1E1E1E")
    static let accent = dyn("#D8502F", "#FF7A59")
    static let route = dyn("#2563EB", "#60A5FA")
    static let routeCasing = dyn("#FFFFFF", "#000000")
    static let track = dyn("#9333EA", "#C084FC")
    static let barBg = dyn("#E5E1D8", "#333333")
    static let button = dyn("#1B1B1B", "#F2F2F2")
    static let buttonText = dyn("#FFFFFF", "#111111")
    static let glass = dyn("#F5FFFFFF", "#F51E1E1E")
    static let glassEdge = dyn("#331B1B1B", "#33F2F2F2")
    static let start = UIColor(hex: "#16A34A")

    // SwiftUI
    static var sPaper: Color { Color(paper) }
    static var sInk: Color { Color(ink) }
    static var sInkSoft: Color { Color(inkSoft) }
    static var sCard: Color { Color(card) }
    static var sAccent: Color { Color(accent) }
    static var sBarBg: Color { Color(barBg) }
    static var sGlass: Color { Color(glass) }
    static var sGlassEdge: Color { Color(glassEdge) }
    static var sButton: Color { Color(button) }
    static var sButtonText: Color { Color(buttonText) }
    static var sTrack: Color { Color(track) }
}

/// A frosted card, like the Android panels.
struct Glass: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Theme.sGlass, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.sGlassEdge, lineWidth: 1))
    }
}
extension View { func glass() -> some View { modifier(Glass()) } }

/// The big flat buttons: ink on paper, accent for the one that matters.
struct BigButton: ButtonStyle {
    var accent = false
    var compact = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: compact ? 14 : 16, weight: .bold))
            .tracking(0.5)
            .foregroundStyle(accent ? Color.white : Theme.sButtonText)
            .padding(.horizontal, compact ? 14 : 20).padding(.vertical, compact ? 10 : 14)
            .frame(maxWidth: compact ? nil : .infinity)
            .background(accent ? Theme.sAccent : Theme.sButton, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A round glyph button at the map edge (locate, night, lock, sound…).
struct RoundButton: View {
    let system: String
    var on = false
    var size: CGFloat = 48
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: system).font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(on ? Theme.sAccent : Theme.sInk)
                .frame(width: size, height: size)
                .background(Theme.sGlass, in: Circle())
                .overlay(Circle().stroke(Theme.sGlassEdge, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
