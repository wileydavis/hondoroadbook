package fold.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CORE-07: a road OpenStreetMap posts as private is never chosen while a public way connects, however much
 * longer the public way is; it is used only when nothing else does (or the rider put a point on it), and a
 * road private to motor vehicles only does not keep a bicycle off it.
 */
class PrivateAccessTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)
    private val CB = GraphFormat.CAR or GraphFormat.BIKE

    // A(0,0) — B(1000,0) straight through a private way 0; the public way goes round: A — (0,1000) — (1000,1000) — B
    private fun graph(privateFlags: Int) = Fixtures.graph(listOf(
        listOf(p(0.0, 0.0), p(1000.0, 0.0)),
        listOf(p(0.0, 0.0), p(0.0, 1000.0)), listOf(p(0.0, 1000.0), p(1000.0, 1000.0)), listOf(p(1000.0, 1000.0), p(1000.0, 0.0)),
    ), wayFlags = { wi -> if (wi == 0) CB or privateFlags else CB })

    private fun length(g: Graph, profile: Profile, from: DoubleArray, to: DoubleArray): RouteResult {
        val mr = MultiRouter(listOf(Router(g)))
        val a = Fixtures.ll(from[0], from[1]); val b = Fixtures.ll(to[0], to[1])
        val sa = mr.snap(a[0], a[1], profile); val sb = mr.snap(b[0], b[1], profile)
        assertNotNull(sa); assertNotNull(sb)
        return assertNotNull(mr.route(sa, sb, profile), "routes")
    }

    @Test fun publicDetourBeatsPrivateShortcut() {
        val g = graph(GraphFormat.PRIVATE or GraphFormat.PRIVATE_BIKE)
        for (profile in listOf(Profile.FAST, Profile.DIRT, Profile.RIDE)) {
            val rr = length(g, profile, p(0.0, 10.0), p(1000.0, 10.0))   // both ends on the public ways
            assertTrue(rr.lengthMeters > 2500.0, "${profile.label}: took the public way round (${"%.0f".format(rr.lengthMeters)} m), not the 980 m private shortcut")
        }
    }

    @Test fun privateIsUsedWhenNothingElseConnects() {
        // a point on the private way itself: the only way to reach it is along it
        val g = graph(GraphFormat.PRIVATE or GraphFormat.PRIVATE_BIKE)
        val rr = length(g, Profile.FAST, p(10.0, 0.0), p(500.0, 0.0))
        assertTrue(rr.lengthMeters < 600.0, "reaches a point on the private road along it (${"%.0f".format(rr.lengthMeters)} m)")
    }

    @Test fun motorOnlyPrivateDoesNotDetourABicycle() {
        val g = graph(GraphFormat.PRIVATE)   // motor_vehicle=private, bicycle=yes
        val bike = length(g, Profile.RIDE, p(0.0, 10.0), p(1000.0, 10.0))
        assertTrue(bike.lengthMeters < 1100.0, "bicycle rides straight through (${"%.0f".format(bike.lengthMeters)} m)")
        val car = length(g, Profile.FAST, p(0.0, 10.0), p(1000.0, 10.0))
        assertTrue(car.lengthMeters > 2500.0, "a car goes round (${"%.0f".format(car.lengthMeters)} m)")
    }
}
