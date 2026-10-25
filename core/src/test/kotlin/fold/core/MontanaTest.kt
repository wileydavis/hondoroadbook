package fold.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MontanaTest {
    private val pts = mapOf("Manhattan" to doubleArrayOf(45.8566, -111.3322), "Bozeman" to doubleArrayOf(45.6770, -111.0429), "Missoula" to doubleArrayOf(46.8721, -113.9940),
        "BigSky" to doubleArrayOf(45.2846, -111.3683), "RedLodge" to doubleArrayOf(45.1858, -109.2468), "Glendive" to doubleArrayOf(47.1053, -104.7125), "WestYellowstone" to doubleArrayOf(44.6621, -111.1041),
        "Billings" to doubleArrayOf(45.7833, -108.5007), "Jackson" to doubleArrayOf(43.4799, -110.7624), "Cody" to doubleArrayOf(44.5263, -109.0565), "Sheridan" to doubleArrayOf(44.7972, -106.9562), "Cheyenne" to doubleArrayOf(41.1400, -104.8202))

    private fun run(mr: MultiRouter, prof: Profile, a: String, b: String): Route {
        val t0 = System.nanoTime()
        val sa = mr.snap(pts[a]!![0], pts[a]!![1], prof); val sb = mr.snap(pts[b]!![0], pts[b]!![1], prof, 6000.0)
        assertNotNull(sa, a); assertNotNull(sb, b)
        val rr = mr.route(sa, sb, prof)
        val t1 = System.nanoTime()
        assertNotNull(rr, "$a->$b")
        val r = Route.fromGraphRoute(rr, mr, prof)
        val turns = r.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }
        val packsUsed = rr.segPack.toSet().size
        println("%-5s %-10s -> %-15s %6.1f km %5.0f min  %5d ms  %3d cues packs=%d: %s".format(prof, a, b, r.length / 1000, r.timeSeconds / 60, (t1 - t0) / 1_000_000, turns.size, packsUsed,
            turns.take(5).joinToString("; ") { "${it.turn}@${(it.at / 1000).toInt()}km ${it.name}" }))
        for (i in 1 until r.size) assertTrue(r.cum[i] - r.cum[i - 1] < 15000.0, "gap at $i")
        return r
    }

    @Test fun montanaRoutes() {
        val p = System.getenv("FOLD_MT") ?: return
        val mr = MultiRouter(listOf(Router(Graph.open(File(p, "graph.bin")))))
        for ((a, b) in listOf("Manhattan" to "Bozeman", "Manhattan" to "Missoula", "Manhattan" to "BigSky", "Bozeman" to "RedLodge", "Missoula" to "Glendive", "Manhattan" to "WestYellowstone")) run(mr, Profile.FAST, a, b)
        for ((a, b) in listOf("Manhattan" to "Bozeman", "Bozeman" to "BigSky")) run(mr, Profile.RIDE, a, b)
        val idx = SearchFile.open(File(p, "search.tsv"))
        for (q in listOf("manhattan", "bozeman", "main st manhattan", "106 e main st manhattan", "manhattan main", "hondo", "big sky", "frontage rd belgrade", "ennis")) {
            val t0 = System.nanoTime(); val hits = idx.query(q, 4)
            println("'$q' (${(System.nanoTime() - t0) / 1_000_000} ms): " + hits.joinToString(" | ") { "${it.kind}:${it.label}" })
            assertTrue(hits.isNotEmpty(), q)
        }
    }

    @Test fun crossBorder() {
        val p = System.getenv("FOLD_MT") ?: return
        val w = System.getenv("FOLD_WY") ?: return
        val gm = Graph.open(File(p, "graph.bin")); val gw = Graph.open(File(w, "graph.bin"))
        // sanity: shared junctions exist
        var shared = 0
        for (n in 0 until gw.nodeCount step 97) if (gm.nodeByOsmId(gw.osmId(n)) >= 0) shared++
        println("WY nodes sampled that also exist in MT: $shared")
        assertTrue(shared > 0)
        val mr = MultiRouter(listOf(Router(gm), Router(gw)))
        val r1 = run(mr, Profile.FAST, "Bozeman", "Cody")
        val r2 = run(mr, Profile.FAST, "Manhattan", "Jackson")
        val r3 = run(mr, Profile.FAST, "Billings", "Sheridan")
        val r4 = run(mr, Profile.FAST, "Manhattan", "Cheyenne")
        val r5 = run(mr, Profile.FAST, "Cheyenne", "Manhattan")
        assertTrue(r4.length in 900_000.0..1_200_000.0, "MT->Cheyenne ${r4.length}")
        run(mr, Profile.RIDE, "Billings", "Sheridan")
    }
}
