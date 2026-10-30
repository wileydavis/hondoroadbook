import SwiftUI
import FoldCore

/// The turn glyph and the approach bar are one thing: an arrow whose shaft is the bar. The grey track is the
/// whole turn shape; the red fill grows along it, in the direction of the turn, and meets the red arrowhead at
/// the moment of the turn. The same shapes, in a 100×100 box, as the Android ArrowView.
struct ArrowView: View {
    let turn: Turn
    var exit: Int32 = 0
    var progress: Double = 1

    static func unitPath(_ t: Turn) -> Path {
        var p = Path()
        func m(_ x: CGFloat, _ y: CGFloat) { p.move(to: CGPoint(x: x, y: y)) }
        func l(_ x: CGFloat, _ y: CGFloat) { p.addLine(to: CGPoint(x: x, y: y)) }
        func q(_ cx: CGFloat, _ cy: CGFloat, _ x: CGFloat, _ y: CGFloat) { p.addQuadCurve(to: CGPoint(x: x, y: y), control: CGPoint(x: cx, y: cy)) }
        switch t {
        case .straight, .depart: m(50, 92); l(50, 24)
        case .right: m(32, 92); l(32, 48); q(32, 32, 48, 32); l(74, 32)
        case .left: m(68, 92); l(68, 48); q(68, 32, 52, 32); l(26, 32)
        case .slightRight: m(42, 92); l(42, 56); q(42, 44, 50, 36); l(70, 18)
        case .slightLeft: m(58, 92); l(58, 56); q(58, 44, 50, 36); l(30, 18)
        case .keepRight: m(46, 92); l(46, 60); q(46, 48, 52, 40); l(62, 22)
        case .keepLeft: m(54, 92); l(54, 60); q(54, 48, 48, 40); l(38, 22)
        case .sharpRight: m(36, 92); l(36, 40); q(36, 22, 50, 30); l(72, 52)
        case .sharpLeft: m(64, 92); l(64, 40); q(64, 22, 50, 30); l(28, 52)
        case .uturn: m(66, 92); l(66, 46); p.addArc(center: CGPoint(x: 50, y: 46), radius: 16, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: true); l(34, 70)
        case .roundabout: m(50, 92); l(50, 72); p.addArc(center: CGPoint(x: 50, y: 52), radius: 18, startAngle: .degrees(90), endAngle: .degrees(-90), clockwise: true); l(50, 14)
        case .arrive: m(50, 92); l(50, 30)
        default: m(50, 92); l(50, 46)   // NOTE, DANGER
        }
        return p
    }

    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height)
            let sw = s * 0.13
            let t = CGAffineTransform(translationX: (size.width - s) / 2, y: (size.height - s) / 2).scaledBy(x: s / 100, y: s / 100)
            let path = ArrowView.unitPath(turn).applying(t)
            let track = Color(Theme.barBg), accent = Color(Theme.accent), card = Color(Theme.card), ink = Color(Theme.ink)
            let style = StrokeStyle(lineWidth: sw, lineCap: .round, lineJoin: .round)
            ctx.stroke(path, with: .color(track), style: style)
            if progress > 0 { ctx.stroke(path.trimmedPath(from: 0, to: CGFloat(min(1, progress))), with: .color(accent), style: style) }
            // the end of the shaft and the direction it points
            let end = path.currentPoint ?? CGPoint(x: size.width / 2, y: size.height / 2)
            let before = path.trimmedPath(from: 0, to: 0.985).currentPoint ?? end
            var tx = end.x - before.x, ty = end.y - before.y
            let len = max(0.001, hypot(tx, ty)); tx /= len; ty /= len
            if turn == .note || turn == .danger {
                // a sign at the end of the shaft: triangle with ! for danger, round sign with i for a note
                let x = end.x, y = end.y - s * 0.17, r = s * 0.19
                var sign = Path()
                if turn == .danger { sign.move(to: CGPoint(x: x, y: y - r)); sign.addLine(to: CGPoint(x: x + r * 1.1, y: y + r * 0.8)); sign.addLine(to: CGPoint(x: x - r * 1.1, y: y + r * 0.8)); sign.closeSubpath() }
                else { sign.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)) }
                ctx.stroke(sign, with: .color(card), style: StrokeStyle(lineWidth: sw * 0.25, lineJoin: .round))
                ctx.fill(sign, with: .color(turn == .danger ? accent : ink))
                let glyph = Text(turn == .danger ? "!" : "i").font(.system(size: r * 1.3, weight: .bold)).foregroundColor(card)
                ctx.draw(glyph, at: CGPoint(x: x, y: y + (turn == .danger ? r * 0.25 : 0)))
                if turn == .danger && exit > 1 { ctx.draw(Text(String(repeating: "!", count: Int(min(3, exit)))).font(.system(size: r * 0.5, weight: .bold)).foregroundColor(ink), at: CGPoint(x: x + r * 1.5, y: y + r * 0.5)) }
                return
            }
            if turn == .arrive {
                // a flag at the end of the shaft instead of a head
                let x = end.x, y = end.y
                ctx.fill(Path(CGRect(x: x - sw * 0.3, y: y - s * 0.22, width: sw * 0.6, height: s * 0.22)), with: .color(ink))
                let cell = s * 0.06
                for r in 0..<3 { for c in 0..<4 where (r + c) % 2 == 0 { ctx.fill(Path(CGRect(x: x + sw * 0.3 + CGFloat(c) * cell, y: y - s * 0.22 + CGFloat(r) * cell, width: cell, height: cell)), with: .color(ink)) } }
                return
            }
            // arrowhead at the end of the shaft, pointing along it, protruding beyond the bar
            let hl = s * 0.2, hw = s * 0.15
            let nx = -ty, ny = tx
            let tip = CGPoint(x: end.x + tx * hl * 0.85, y: end.y + ty * hl * 0.85)
            let base = CGPoint(x: end.x - tx * hl * 0.15, y: end.y - ty * hl * 0.15)
            var head = Path(); head.move(to: tip); head.addLine(to: CGPoint(x: base.x + nx * hw, y: base.y + ny * hw)); head.addLine(to: CGPoint(x: base.x - nx * hw, y: base.y - ny * hw)); head.closeSubpath()
            ctx.stroke(head, with: .color(card), style: StrokeStyle(lineWidth: sw * 0.25, lineJoin: .round))
            ctx.fill(head, with: .color(accent))
            if turn == .roundabout && exit > 0 {
                let bx = (size.width + s) / 2 - s * 0.16, by = (size.height + s) / 2 - s * 0.16
                ctx.fill(Path(ellipseIn: CGRect(x: bx - s * 0.14, y: by - s * 0.14, width: s * 0.28, height: s * 0.28)), with: .color(ink))
                ctx.draw(Text("\(exit)").font(.system(size: s * 0.18, weight: .bold)).foregroundColor(card), at: CGPoint(x: bx, y: by))
            }
        }
    }
}

/// A small compass rose: the needle points to true north for the current map rotation.
struct CompassView: View {
    let bearing: Double
    var body: some View {
        Canvas { ctx, size in
            let cx = size.width / 2, cy = size.height / 2, r = min(size.width, size.height) / 2 - 1.5
            ctx.stroke(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)), with: .color(Color(Theme.inkSoft)), lineWidth: 1.5)
            var c = ctx
            c.translateBy(x: cx, y: cy); c.rotate(by: .degrees(-bearing)); c.translateBy(x: -cx, y: -cy)
            var n = Path(); n.move(to: CGPoint(x: cx, y: cy - r * 0.85)); n.addLine(to: CGPoint(x: cx - r * 0.22, y: cy)); n.addLine(to: CGPoint(x: cx + r * 0.22, y: cy)); n.closeSubpath()
            c.fill(n, with: .color(Color(Theme.accent)))
            var s = Path(); s.move(to: CGPoint(x: cx, y: cy + r * 0.85)); s.addLine(to: CGPoint(x: cx - r * 0.22, y: cy)); s.addLine(to: CGPoint(x: cx + r * 0.22, y: cy)); s.closeSubpath()
            c.fill(s, with: .color(Color(Theme.inkSoft)))
            c.draw(Text("N").font(.system(size: r * 0.55, weight: .bold)).foregroundColor(Color(Theme.ink)), at: CGPoint(x: cx, y: cy - r * 1.15))
        }
    }
}

/// Scale bar: a round distance that fits ~110 pt, with end ticks and a label.
struct ScaleView: View {
    @ObservedObject var hud: HudState
    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            Path { p in p.move(to: CGPoint(x: 0, y: 2)); p.addLine(to: CGPoint(x: 0, y: 10)); p.addLine(to: CGPoint(x: hud.scalePx, y: 10)); p.addLine(to: CGPoint(x: hud.scalePx, y: 2)) }
                .stroke(Theme.sInk, lineWidth: 2)
                .background(Path { p in p.move(to: CGPoint(x: 0, y: 2)); p.addLine(to: CGPoint(x: 0, y: 10)); p.addLine(to: CGPoint(x: hud.scalePx, y: 10)); p.addLine(to: CGPoint(x: hud.scalePx, y: 2)) }.stroke(Theme.sCard, lineWidth: 6))
                .frame(width: max(1, hud.scalePx), height: 12)
            Text(hud.scaleLabel).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.sInk).shadow(color: Theme.sCard, radius: 1).shadow(color: Theme.sCard, radius: 1)
        }
    }
}

/// The HUD card while navigating: arrow + bar, the distance, the road.
struct HudView: View {
    @ObservedObject var hud: HudState
    /// landscape: a smaller arrow and figure, so the column stays short
    var compact = false
    var body: some View {
        HStack(spacing: compact ? 10 : 14) {
            ArrowView(turn: hud.turn, exit: hud.exit, progress: hud.progress).frame(width: compact ? 72 : 96, height: compact ? 72 : 96)
            VStack(alignment: .leading, spacing: 2) {
                Text(hud.distance).font(.system(size: compact ? 32 : 40, weight: .heavy, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5).foregroundStyle(Theme.sInk)
                Text(hud.street).font(.system(size: compact ? 16 : 20, weight: .semibold)).lineLimit(2).minimumScaleFactor(0.7).foregroundStyle(Theme.sInk)
            }
            Spacer(minLength: 0)
            if !compact { CompassView(bearing: hud.compass).frame(width: 34, height: 34) }
        }
        .padding(compact ? 10 : 14)
        .glass()
    }
}
