package fold.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs against a real graph.bin built from an OSM extract (set FOLD_GRAPH). Skipped otherwise. */
class RealDataTest {
    private fun graph(): Graph? {
        val p = System.getenv("FOLD_GRAPH") ?: throw org.opentest4j.TestAbortedException("FOLD_GRAPH not set: real-data test skipped")
        val f = File(p); if (!f.exists()) throw org.opentest4j.TestAbortedException("FOLD_GRAPH missing: real-data test skipped")
        return Graph.open(f.path)
    }

    private fun describe(r: Route): String = buildString {
        append("length=%.0f m time=%.0f s maneuvers=%d\n".format(r.length, r.timeSeconds, r.maneuvers.size))
        for (m in r.maneuvers) append("  %7.0f m  %-12s %5.0f° exit=%d  %s\n".format(m.at, m.turn, m.angle, m.exit, m.name))
    }

    @Test
    fun routeAcrossBoulder() {
        val g = graph()!!
        println("graph nodes=${g.nodeCount} edges=${g.edgeCount} bbox=${g.minLat},${g.minLon}..${g.maxLat},${g.maxLon}")
        val router = MultiRouter(listOf(Router(g)))
        // Pearl St downtown -> NCAR Mesa Lab
        val t0 = System.nanoTime()
        val a = router.snap(40.0176, -105.2797, Profile.FAST); val b = router.snap(39.9781, -105.2746, Profile.FAST)
        assertNotNull(a); assertNotNull(b)
        println("snap A: edge=${a.edge} d=%.1f name=${g.edgeName(a.edge)}; snap B: edge=${b.edge} d=%.1f name=${g.edgeName(b.edge)}".format(a.distance, b.distance))
        val rr = router.route(a, b, Profile.FAST)
        val t1 = System.nanoTime()
        assertNotNull(rr)
        val route = Route.fromGraphRoute(rr, router, Profile.FAST)
        println("DRIVE Pearl->NCAR in ${(t1 - t0) / 1_000_000} ms: " + describe(route))
        assertTrue(route.length in 5000.0..12000.0, "length ${route.length}")
        // geometry continuity: no segment longer than 2 km, no jumps
        for (i in 1 until route.size) assertTrue(route.cum[i] - route.cum[i - 1] < 2000.0)

        // Ride profile, long route: Boulder -> Nederland (west, canyon)
        val c = router.snap(39.9612, -105.5097, Profile.RIDE)
        if (c != null) {
            val t2 = System.nanoTime()
            val rr2 = router.route(router.snap(40.0176, -105.2797, Profile.RIDE)!!, c, Profile.RIDE)
            val t3 = System.nanoTime()
            if (rr2 != null) {
                val r2 = Route.fromGraphRoute(rr2, router, Profile.RIDE)
                println("RIDE Boulder->Nederland in ${(t3 - t2) / 1_000_000} ms: " + describe(r2))
            } else println("Nederland unreachable in extract")
        }

        // reverse direction (one-way handling)
        val rr3 = router.route(b, a, Profile.FAST)
        assertNotNull(rr3)
        println("DRIVE NCAR->Pearl: length=%.0f".format(rr3.lengthMeters))
    }

    @Test
    fun followSimulatedDrive() {
        val g = graph()!!
        val router = MultiRouter(listOf(Router(g)))
        val a = router.snap(40.0176, -105.2797, Profile.FAST)!!; val b = router.snap(39.9781, -105.2746, Profile.FAST)!!
        val route = Route.fromGraphRoute(router.route(a, b, Profile.FAST)!!, router, Profile.FAST)
        val f = Follower(route)
        // drive at 13 m/s with 1 Hz fixes, 4 m lateral noise; check bar monotonic and hits 1 at each maneuver
        var along = 0.0; var t = 0L
        val speed = 13.0
        var lastProgress = 0.0; var lastNext: Maneuver? = null
        var maneuversHit = 0
        val rnd = java.util.Random(7)
        var frames = 0
        while (along < route.length + 30) {
            // position on route at 'along'
            var i = 0; while (i < route.size - 2 && route.cum[i + 1] < along) i++
            val seg = (route.cum[i + 1] - route.cum[i]).let { if (it > 0) ((along - route.cum[i]) / it).coerceIn(0.0, 1.0) else 0.0 }
            val lat = route.lats[i] + seg * (route.lats[i + 1] - route.lats[i]) + (rnd.nextGaussian() * 3e-5)
            val lon = route.lons[i] + seg * (route.lons[i + 1] - route.lons[i]) + (rnd.nextGaussian() * 4e-5)
            f.update(Fix(lat, lon, speed, route.segmentBearing(i), t, 5.0))
            // render at 10 fps between fixes
            for (k in 0 until 10) {
                val v = f.view(t + k * 100L)
                frames++
                assertTrue(v.onRoute, "went off route at along=$along")
                if (v.next !== lastNext) { if (lastNext != null && lastNext.turn != Turn.DEPART) { assertTrue(lastProgress > 0.85, "bar at ${lastProgress} when passing ${lastNext.turn} at ${lastNext.at}"); maneuversHit++ }; lastNext = v.next; lastProgress = 0.0 }
                else assertTrue(v.progress >= lastProgress - 0.02, "bar went backwards ${lastProgress}->${v.progress}")
                lastProgress = v.progress
            }
            along += speed; t += 1000
        }
        val end = f.view(t)
        println("follow: frames=$frames maneuversHit=$maneuversHit arrived=${end.arrived} remaining=%.0f".format(end.remaining))
        assertTrue(end.arrived)
        assertTrue(maneuversHit >= 2)

        // now go off route: jump 300 m east and stay; expect offRoute within 3 fixes
        val f2 = Follower(route); var tt = 0L
        f2.update(Fix(route.lats[0], route.lons[0], 10.0, 0.0, tt, 5.0))
        repeat(4) { tt += 1000; f2.update(Fix(route.lats[0] , route.lons[0] + 0.004, 10.0, 90.0, tt, 5.0)) }
        val v2 = f2.view(tt)
        assertTrue(!v2.onRoute); assertTrue(v2.offDist > 200)
        println("off-route detection ok: offDist=%.0f bearing=%.0f".format(v2.offDist, v2.offBearing))
    }

    @Test
    fun gpxTrackCues() {
        val g = graph()!!
        val router = MultiRouter(listOf(Router(g)))
        val a = router.snap(40.0176, -105.2797, Profile.FAST)!!; val b = router.snap(39.9781, -105.2746, Profile.FAST)!!
        val rr = router.route(a, b, Profile.FAST)!!
        // write a GPX from the route then re-read it as a track
        val sb = StringBuilder("<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><name>Test &amp; ride</name><trkseg>")
        for (i in rr.lats.indices) sb.append("<trkpt lat=\"${rr.lats[i]}\" lon=\"${rr.lons[i]}\"><ele>1600</ele></trkpt>")
        sb.append("</trkseg></trk></gpx>")
        val tr = Gpx.parse(sb.toString())!!
        val route = Route.fromTrack(tr.lats, tr.lons, tr.name)
        println("GPX '${tr.name}': " + describe(route))
        val graphRoute = Route.fromGraphRoute(rr, router, Profile.FAST)
        val realTurns = graphRoute.maneuvers.count { it.turn in setOf(Turn.LEFT, Turn.RIGHT, Turn.SHARP_LEFT, Turn.SHARP_RIGHT) }
        val trackTurns = route.maneuvers.count { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }
        println("turns: graph=$realTurns track=$trackTurns")
        assertTrue(trackTurns >= realTurns - 1)
    }

    @Test
    fun search() {
        val p = System.getenv("FOLD_SEARCH") ?: throw org.opentest4j.TestAbortedException("FOLD_SEARCH not set: real-data test skipped")
        val idx = SearchFile.open(p)
        for (q in listOf("pearl st", "1770 13th street", "boulder", "eldorado springs", "baseline rd", "flagstaff")) {
            val t0 = System.nanoTime()
            val hits = idx.query(q, 5)
            println("'$q' (${(System.nanoTime() - t0) / 1_000_000} ms): " + hits.joinToString(" | ") { "${it.kind}:${it.label}" })
            assertTrue(hits.isNotEmpty(), q)
        }
    }
}
