package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** NAV-04: a planned route must start at the snapped start and end exactly at the snapped destination. */
class RouterEndpointTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)

    // A: (0,0) —— B: (1000,0) ——(with interior bends)—— C: (2000,0), then C —— D: (2000,1000)
    private fun chain(interior: Boolean) = Fixtures.graph(listOf(
        listOf(p(0.0, 0.0), p(1000.0, 0.0)),
        if (interior) listOf(p(1000.0, 0.0), p(1300.0, 40.0), p(1600.0, -40.0), p(2000.0, 0.0)) else listOf(p(1000.0, 0.0), p(2000.0, 0.0)),
        listOf(p(2000.0, 0.0), p(2000.0, 1000.0))
    ))

    private fun check(g: Graph, from: DoubleArray, to: DoubleArray, label: String) {
        val mr = MultiRouter(listOf(Router(g)))
        val a = Fixtures.ll(from[0], from[1]); val b = Fixtures.ll(to[0], to[1])
        val sa = mr.snap(a[0], a[1], Profile.FAST); val sb = mr.snap(b[0], b[1], Profile.FAST)
        assertNotNull(sa, "$label: start snaps"); assertNotNull(sb, "$label: end snaps")
        val rr = mr.route(sa, sb, Profile.FAST)
        assertNotNull(rr, "$label: routes")
        val n = rr.lats.size
        assertTrue(Geo.fastDistance(rr.lats[0], rr.lons[0], sa.lat, sa.lon) < 0.5, "$label: starts at the snapped start")
        assertTrue(Geo.fastDistance(rr.lats[n - 1], rr.lons[n - 1], sb.lat, sb.lon) < 0.5, "$label: ends at the snapped destination, off by ${"%.1f".format(Geo.fastDistance(rr.lats[n - 1], rr.lons[n - 1], sb.lat, sb.lon))} m")
        // geometry length agrees with the graph distance
        var geom = 0.0; for (i in 1 until n) geom += Geo.fastDistance(rr.lats[i - 1], rr.lons[i - 1], rr.lats[i], rr.lons[i])
        assertTrue(Math.abs(geom - rr.lengthMeters) < 0.02 * rr.lengthMeters + 5, "$label: geometry ${"%.0f".format(geom)} m vs reported ${"%.0f".format(rr.lengthMeters)} m")
        // no jumps
        for (i in 1 until n) assertTrue(Geo.fastDistance(rr.lats[i - 1], rr.lons[i - 1], rr.lats[i], rr.lons[i]) < 1100.0, "$label: no segment longer than an edge")
        val route = Route.fromGraphRoute(rr, mr, Profile.FAST)
        assertEquals(Turn.ARRIVE, route.maneuvers.last().turn)
        assertTrue(Geo.fastDistance(route.lats[route.maneuvers.last().vertex], route.lons[route.maneuvers.last().vertex], sb.lat, sb.lon) < 0.5, "$label: ARRIVE sits on the destination")
    }

    @Test fun destinationMidEdgeWithInteriorGeometry() = check(chain(true), p(100.0, 0.0), p(1500.0, 0.0), "interior, forward")
    @Test fun destinationMidEdgeNoInterior() = check(chain(false), p(100.0, 0.0), p(1500.0, 0.0), "straight, forward")
    @Test fun reverseDirection() = check(chain(true), p(2000.0, 800.0), p(1500.0, 0.0), "interior, arriving from the far node")
    @Test fun reverseDirectionNoInterior() = check(chain(false), p(2000.0, 800.0), p(1500.0, 0.0), "straight, arriving from the far node")
    @Test fun sameEdge() = check(chain(true), p(1100.0, 0.0), p(1800.0, 0.0), "same edge")
    @Test fun endOnThirdEdge() = check(chain(true), p(100.0, 0.0), p(2000.0, 600.0), "around the corner")

    @Test fun viaLegsJoinAtTheVia() {
        val g = chain(true); val mr = MultiRouter(listOf(Router(g)))
        val pts = listOf(p(100.0, 0.0), p(1500.0, 0.0), p(2000.0, 600.0)).map { Fixtures.ll(it[0], it[1]) }
        val rr = mr.routeVia(pts, Profile.FAST)
        assertNotNull(rr)
        // the via point lies on the joined line
        var best = Double.MAX_VALUE
        for (i in rr.lats.indices) best = minOf(best, Geo.fastDistance(rr.lats[i], rr.lons[i], pts[1][0], pts[1][1]))
        assertTrue(best < 20.0, "via on the line (snap distance), nearest vertex ${"%.1f".format(best)} m")
        val n = rr.lats.size
        assertTrue(Geo.fastDistance(rr.lats[n - 1], rr.lons[n - 1], pts[2][0], pts[2][1]) < 1.0, "ends at the destination")
    }
}
