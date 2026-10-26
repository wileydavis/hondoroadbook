package fold.core

import java.io.ByteArrayOutputStream

/**
 * Synthetic data for the core tests, so the safety-relevant behaviour is exercised on every build
 * without the external state datasets (those tests still run when FOLD_* point at real graphs).
 */
object Fixtures {
    const val LAT0 = 45.80; const val LON0 = -111.30
    /** metres east/north of the origin → lat/lon */
    fun ll(east: Double, north: Double): DoubleArray = doubleArrayOf(LAT0 + north / 111_320.0, LON0 + east / (111_320.0 * Math.cos(Math.toRadians(LAT0))))

    /**
     * A graph from a list of ways; each way is a list of (east, north) metres. Consecutive ways share
     * nodes where their endpoints coincide. Interior points become edge geometry, not nodes.
     */
    fun graph(ways: List<List<DoubleArray>>, cls: Int = 10, flags: Int = GraphFormat.CAR or GraphFormat.BIKE, wayFlags: (Int) -> Int = { flags }): Graph {
        val w = GraphWriter()
        val nodes = HashMap<String, Int>()
        fun node(p: DoubleArray): Int { val k = "%.1f,%.1f".format(p[0], p[1]); return nodes.getOrPut(k) { val q = ll(p[0], p[1]); w.addNode((q[0] * 1e7).toInt(), (q[1] * 1e7).toInt(), nodes.size + 1L) } }
        for ((wi, way) in ways.withIndex()) {
            val a = node(way.first()); val b = node(way.last())
            var len = 0.0
            for (i in 1 until way.size) len += Math.hypot(way[i][0] - way[i - 1][0], way[i][1] - way[i - 1][1])
            val inner = way.subList(1, way.size - 1)
            val gl = IntArray(inner.size) { (ll(inner[it][0], inner[it][1])[0] * 1e7).toInt() }
            val go = IntArray(inner.size) { (ll(inner[it][0], inner[it][1])[1] * 1e7).toInt() }
            w.addEdge(a, b, (len * 100).toInt(), cls or wayFlags(wi), w.nameId("Way $wi"), gl, go, inner.size)
        }
        val out = ByteArrayOutputStream(); w.write(out)
        return Graph.fromBytes(out.toByteArray())
    }

    /** A route (as a track) from (east, north) metre points, with optional cues. */
    fun track(pts: List<DoubleArray>, cues: List<Cue> = emptyList(), name: String = "fixture"): Route {
        val lats = DoubleArray(pts.size) { ll(pts[it][0], pts[it][1])[0] }
        val lons = DoubleArray(pts.size) { ll(pts[it][0], pts[it][1])[1] }
        return Route.fromTrack(lats, lons, name, cues)
    }

    fun cue(east: Double, north: Double, turn: Turn, note: String = "", level: Int = 0): Cue { val p = ll(east, north); return Cue(p[0], p[1], turn, note, level) }

    /** Fixes along a polyline at [speed] m/s, one per second, starting at t0. */
    fun ride(pts: List<DoubleArray>, speed: Double, accuracy: Double = 5.0, t0: Long = 1_000_000L, noise: Double = 0.0, seed: Int = 1): List<Fix> {
        val out = ArrayList<Fix>()
        val rnd = java.util.Random(seed.toLong())
        var t = t0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val len = Math.hypot(b[0] - a[0], b[1] - a[1]); val steps = Math.max(1, Math.round(len / speed).toInt())
            val brg = Math.toDegrees(Math.atan2(b[0] - a[0], b[1] - a[1])).let { if (it < 0) it + 360 else it }
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                val e = a[0] + (b[0] - a[0]) * f + rnd.nextGaussian() * noise; val n = a[1] + (b[1] - a[1]) * f + rnd.nextGaussian() * noise
                val p = ll(e, n); out.add(Fix(p[0], p[1], speed, brg, t, accuracy)); t += 1000
            }
        }
        val last = ll(pts.last()[0], pts.last()[1]); out.add(Fix(last[0], last[1], speed, out.last().bearing, t, accuracy))
        return out
    }

    fun dist(route: Route, i: Int, lat: Double, lon: Double) = Geo.fastDistance(route.lats[i], route.lons[i], lat, lon)
}
