package fold.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dirt mode chooses by preference, not by the clock: an unpaved detour up to about three times longer beats the paved road. */
class DirtPreferenceTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)
    // A(0,0) — B(2000,0): a paved primary straight across (class 4); and an unpaved unclassified road round the hills, 2.5× longer
    private fun graph() = Fixtures.graph(listOf(
        listOf(p(0.0, 0.0), p(2000.0, 0.0)),
        listOf(p(0.0, 0.0), p(0.0, 1500.0)), listOf(p(0.0, 1500.0), p(2000.0, 1500.0)), listOf(p(2000.0, 1500.0), p(2000.0, 0.0)),
    ), cls = 0, wayFlags = { wi -> if (wi == 0) 4 or GraphFormat.CAR or GraphFormat.BIKE else 10 or GraphFormat.CAR or GraphFormat.BIKE or GraphFormat.UNPAVED })

    private fun route(profile: Profile): RouteResult {
        val g = graph(); val mr = MultiRouter(listOf(Router(g)))
        val a = Fixtures.ll(0.0, 10.0); val b = Fixtures.ll(2000.0, 10.0)
        val sa = mr.snap(a[0], a[1], profile); val sb = mr.snap(b[0], b[1], profile)
        return assertNotNull(mr.route(assertNotNull(sa), assertNotNull(sb), profile))
    }

    @Test fun dirtTakesTheUnpavedWayRound() {
        val rr = route(Profile.DIRT)
        assertTrue(rr.lengthMeters > 4500.0, "Dirt went round on the dirt (${"%.0f".format(rr.lengthMeters)} m)")
        assertTrue(rr.timeSeconds > 300.0, "and the ETA is the real one, not the nominal clock")
    }

    @Test fun fastAndBackroadsTakeThePavement() {
        for (pr in listOf(Profile.FAST, Profile.BACKROADS)) { val rr = route(pr); assertTrue(rr.lengthMeters < 2100.0, "${pr.label} stayed on the primary (${"%.0f".format(rr.lengthMeters)} m)") }
    }
}
