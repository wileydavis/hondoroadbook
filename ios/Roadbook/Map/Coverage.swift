import CoreLocation
import MapLibre

/// Coverage overlay for zoomed-out views: everything NOT inside an installed pack is fogged, and each pack is
/// labeled at the center of its area. The fog is the world rectangle minus the union of the pack boxes, emitted
/// as disjoint cells (so overlapping neighbour states never punch double holes).
enum Coverage {
    private static let latMax = 85.0

    static func fog(_ packs: [Pack]) -> MLNShapeCollectionFeature {
        if packs.isEmpty { return MLNShapeCollectionFeature(shapes: [rect(-180, -latMax, 180, latMax)]) }
        var xs: Set<Double> = [-180, 180]; var ys: Set<Double> = [-latMax, latMax]
        for p in packs { let m = p.manifest; xs.insert(clamp(m.minLon, -180, 180)); xs.insert(clamp(m.maxLon, -180, 180)); ys.insert(clamp(m.minLat, -latMax, latMax)); ys.insert(clamp(m.maxLat, -latMax, latMax)) }
        let xa = xs.sorted(); let ya = ys.sorted()
        var feats: [MLNShape & MLNFeature] = []
        for j in 0..<(ya.count - 1) {
            let y0 = ya[j], y1 = ya[j + 1], cy = (y0 + y1) / 2
            var runStart = -1
            for i in 0..<xa.count {
                let covered: Bool
                if i < xa.count - 1 { let cx = (xa[i] + xa[i + 1]) / 2; covered = packs.contains { let m = $0.manifest; return cx > m.minLon && cx < m.maxLon && cy > m.minLat && cy < m.maxLat } } else { covered = true }
                if !covered && runStart < 0 { runStart = i }
                if covered && runStart >= 0 { feats.append(rect(xa[runStart], y0, xa[i], y1)); runStart = -1 }
            }
        }
        return MLNShapeCollectionFeature(shapes: feats)
    }

    static func labels(_ packs: [Pack]) -> MLNShapeCollectionFeature {
        MLNShapeCollectionFeature(shapes: packs.map { p in
            let m = p.manifest; let f = MLNPointFeature(); f.coordinate = CLLocationCoordinate2D(latitude: (m.minLat + m.maxLat) / 2, longitude: (m.minLon + m.maxLon) / 2); f.attributes = ["name": p.name]; return f
        })
    }

    static func outlines(_ packs: [Pack]) -> MLNShapeCollectionFeature {
        MLNShapeCollectionFeature(shapes: packs.map { let m = $0.manifest; return rect(m.minLon, m.minLat, m.maxLon, m.maxLat) })
    }

    private static func clamp(_ v: Double, _ a: Double, _ b: Double) -> Double { min(max(v, a), b) }

    private static func rect(_ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double) -> MLNPolygonFeature {
        var c = [CLLocationCoordinate2D(latitude: y0, longitude: x0), CLLocationCoordinate2D(latitude: y0, longitude: x1), CLLocationCoordinate2D(latitude: y1, longitude: x1), CLLocationCoordinate2D(latitude: y1, longitude: x0), CLLocationCoordinate2D(latitude: y0, longitude: x0)]
        return MLNPolygonFeature(coordinates: &c, count: UInt(c.count))
    }
}
