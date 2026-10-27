import ActivityKit
import SwiftUI
import WidgetKit

/// The lock-screen card and the Dynamic Island for a ride. Big type, high contrast: it is read at a
/// glance from a handlebar or a tank bag, and in battery saver it is the only thing on the dark screen.
struct RideLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RideActivityAttributes.self) { context in
            LockScreenCard(title: context.attributes.title, s: context.state)
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    TurnGlyph(turn: context.state.turn, arrived: context.state.arrived, offRoute: context.state.offRoute)
                        .frame(width: 44, height: 44)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.distance).font(.system(size: 26, weight: .bold, design: .rounded)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.instruction).font(.headline).lineLimit(1)
                        Text(context.state.remaining).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } compactLeading: {
                TurnGlyph(turn: context.state.turn, arrived: context.state.arrived, offRoute: context.state.offRoute).frame(width: 20, height: 20)
            } compactTrailing: {
                Text(context.state.distance).font(.caption.weight(.semibold)).monospacedDigit()
            } minimal: {
                TurnGlyph(turn: context.state.turn, arrived: context.state.arrived, offRoute: context.state.offRoute).frame(width: 18, height: 18)
            }
        }
    }
}

private struct LockScreenCard: View {
    let title: String
    let s: RideActivityAttributes.ContentState
    var body: some View {
        HStack(spacing: 16) {
            TurnGlyph(turn: s.turn, arrived: s.arrived, offRoute: s.offRoute)
                .frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(s.distance).font(.system(size: 34, weight: .bold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
                Text(s.instruction).font(.title3.weight(.semibold)).lineLimit(2).minimumScaleFactor(0.7)
                Text(s.remaining.isEmpty ? title : "\(s.remaining) · \(title)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .foregroundStyle(.white)
    }
}

/// A simple arrow for the turn kinds, drawn with SF Symbols so the extension needs no assets.
struct TurnGlyph: View {
    let turn: String
    let arrived: Bool
    let offRoute: Bool
    var body: some View {
        let (name, color): (String, Color) = {
            if offRoute { return ("exclamationmark.triangle.fill", Color.orange) }
            if arrived { return ("flag.checkered", Color.white) }
            switch turn {
            case "LEFT": return ("arrow.turn.up.left", .white)
            case "RIGHT": return ("arrow.turn.up.right", .white)
            case "SLIGHT_LEFT", "KEEP_LEFT": return ("arrow.up.left", .white)
            case "SLIGHT_RIGHT", "KEEP_RIGHT": return ("arrow.up.right", .white)
            case "SHARP_LEFT": return ("arrow.uturn.backward", .white)
            case "SHARP_RIGHT": return ("arrow.uturn.forward", .white)
            case "UTURN": return ("arrow.uturn.down", .white)
            case "ROUNDABOUT": return ("arrow.triangle.2.circlepath", .white)
            case "ARRIVE": return ("flag.checkered", .white)
            case "DANGER": return ("exclamationmark.triangle.fill", Color(red: 0.85, green: 0.31, blue: 0.18))
            case "NOTE": return ("info.circle.fill", .white)
            default: return ("arrow.up", .white)
            }
        }()
        Image(systemName: name).resizable().scaledToFit().foregroundStyle(color)
    }
}
