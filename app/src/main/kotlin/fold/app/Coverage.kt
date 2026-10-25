package fold.app

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Coverage overlay for zoomed-out views: everything NOT inside an installed pack is fogged,
 * and each pack is labeled at the center of its area. Built from the packs' bounding boxes.
 *
 * The fog is the world rectangle minus the union of the pack boxes, emitted as disjoint cells
 * (so overlapping neighbour states never punch double holes). Fill antialiasing is off on the
 * layer, so cell seams don't show.
 */
object Coverage {
    private const val LAT_MAX = 85.0

    fun fog(packs: List<Pack>): FeatureCollection {
        if (packs.isEmpty()) return FeatureCollection.fromFeatures(arrayOf(rect(-180.0, -LAT_MAX, 180.0, LAT_MAX)))
        val xs = sortedSetOf(-180.0, 180.0); val ys = sortedSetOf(-LAT_MAX, LAT_MAX)
        for (p in packs) { val m = p.manifest; xs.add(m.minLon.coerceIn(-180.0, 180.0)); xs.add(m.maxLon.coerceIn(-180.0, 180.0)); ys.add(m.minLat.coerceIn(-LAT_MAX, LAT_MAX)); ys.add(m.maxLat.coerceIn(-LAT_MAX, LAT_MAX)) }
        val xa = xs.toDoubleArray(); val ya = ys.toDoubleArray()
        val feats = ArrayList<Feature>()
        for (j in 0 until ya.size - 1) {
            val y0 = ya[j]; val y1 = ya[j + 1]; val cy = (y0 + y1) / 2
            var runStart = -1
            for (i in 0 until xa.size) {
                val covered = if (i < xa.size - 1) { val cx = (xa[i] + xa[i + 1]) / 2; packs.any { val m = it.manifest; cx > m.minLon && cx < m.maxLon && cy > m.minLat && cy < m.maxLat } } else true
                if (!covered && runStart < 0) runStart = i
                if (covered && runStart >= 0) { feats.add(rect(xa[runStart], y0, xa[i], y1)); runStart = -1 }
            }
        }
        return FeatureCollection.fromFeatures(feats)
    }

    fun labels(packs: List<Pack>): FeatureCollection = FeatureCollection.fromFeatures(packs.map { p ->
        val m = p.manifest
        Feature.fromGeometry(Point.fromLngLat((m.minLon + m.maxLon) / 2, (m.minLat + m.maxLat) / 2)).also { it.addStringProperty("name", p.name) }
    })

    fun outlines(packs: List<Pack>): FeatureCollection = FeatureCollection.fromFeatures(packs.map { p -> val m = p.manifest; rect(m.minLon, m.minLat, m.maxLon, m.maxLat) })

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double): Feature =
        Feature.fromGeometry(Polygon.fromLngLats(listOf(listOf(Point.fromLngLat(x0, y0), Point.fromLngLat(x1, y0), Point.fromLngLat(x1, y1), Point.fromLngLat(x0, y1), Point.fromLngLat(x0, y0)))))
}
