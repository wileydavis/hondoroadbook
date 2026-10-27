import UIKit

/// The map's own markers, drawn in code at the screen's scale: the puck, the pin, the star mark, the via and
/// start dots and the round cue signs. Same shapes and sizes as the Android bitmaps.
enum Icons {
    private static func image(_ w: CGFloat, _ h: CGFloat, _ draw: (CGContext, CGFloat, CGFloat) -> Void) -> UIImage {
        let r = UIGraphicsImageRenderer(size: CGSize(width: w, height: h))
        return r.image { ctx in draw(ctx.cgContext, w, h) }
    }

    static func puck(night: Bool) -> UIImage {
        let s: CGFloat = 44
        return image(s, s) { c, w, h in
            let cx = w / 2, cy = h / 2
            let p = UIBezierPath()
            p.move(to: CGPoint(x: cx, y: cy - s * 0.42)); p.addLine(to: CGPoint(x: cx + s * 0.3, y: cy + s * 0.34)); p.addLine(to: CGPoint(x: cx, y: cy + s * 0.16)); p.addLine(to: CGPoint(x: cx - s * 0.3, y: cy + s * 0.34)); p.close()
            p.lineJoinStyle = .round; p.lineWidth = 3
            c.setFillColor(Theme.accent.resolved(night).cgColor); p.fill()
            c.setStrokeColor(UIColor.white.cgColor); p.stroke()
        }
    }

    static func pin(night: Bool) -> UIImage {
        let w: CGFloat = 36, h: CGFloat = 48
        return image(w, h) { c, _, _ in
            let cx = w / 2, r = w * 0.4
            let p = UIBezierPath(arcCenter: CGPoint(x: cx, y: r + 2), radius: r, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            let tail = UIBezierPath(); tail.move(to: CGPoint(x: cx - r * 0.6, y: r * 1.6)); tail.addLine(to: CGPoint(x: cx, y: h - 2)); tail.addLine(to: CGPoint(x: cx + r * 0.6, y: r * 1.6)); tail.close()
            p.append(tail); p.lineWidth = 3
            c.setFillColor(Theme.accent.resolved(night).cgColor); p.fill()
            c.setStrokeColor(UIColor.white.cgColor); p.stroke()
            c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: cx - r * 0.35, y: r + 2 - r * 0.35, width: r * 0.7, height: r * 0.7))
        }
    }

    static func mark(night: Bool) -> UIImage {
        let s: CGFloat = 26
        return image(s, s) { c, w, h in
            let cx = w / 2, cy = h / 2, r = s * 0.42
            let p = UIBezierPath()
            for i in 0..<10 { let ang = Double.pi / 2 + Double(i) * Double.pi / 5; let rr = i % 2 == 0 ? r : r * 0.45; let pt = CGPoint(x: cx + rr * cos(ang), y: cy - rr * sin(ang)); if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) } }
            p.close(); p.lineWidth = 2; p.lineJoinStyle = .round
            c.setFillColor(Theme.track.resolved(night).cgColor); p.fill()
            c.setStrokeColor(UIColor.white.cgColor); p.stroke()
        }
    }

    static func dot(_ size: CGFloat, radius: CGFloat, color: UIColor, stroke: CGFloat) -> UIImage {
        image(size, size) { c, w, h in
            let r = CGRect(x: w / 2 - radius, y: h / 2 - radius, width: radius * 2, height: radius * 2)
            c.setFillColor(color.cgColor); c.fillEllipse(in: r)
            c.setStrokeColor(UIColor.white.cgColor); c.setLineWidth(stroke); c.strokeEllipse(in: r)
        }
    }

    static func start() -> UIImage { dot(24, radius: 24 * 0.34, color: Theme.start, stroke: 3) }
    static func via(night: Bool) -> UIImage { dot(22, radius: 22 * 0.32, color: Theme.route.resolved(night), stroke: 3) }
    /// cue marker: a small round sign, orange for a danger, ink for everything else
    static func cue(danger: Bool, night: Bool) -> UIImage { dot(22, radius: 22 * 0.4, color: danger ? Theme.accent.resolved(night) : Theme.ink.resolved(night), stroke: 2) }
}

extension UIColor {
    func resolved(_ night: Bool) -> UIColor { resolvedColor(with: UITraitCollection(userInterfaceStyle: night ? .dark : .light)) }
}
