package fold.core
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan mode: stop names survive a GPX round trip; a reworded router turn replaces the router's words and survives a re-plan. */
class PlanCuesTest {
    private fun straight(): Route {
        // a north-running line with one router turn 500 m in
        val lats = DoubleArray(100) { 45.0 + it * 0.0001 }; val lons = DoubleArray(100) { -111.0 }
        val cum = DoubleArray(100); for (i in 1 until 100) cum[i] = cum[i - 1] + Geo.fastDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val man = listOf(Maneuver(Turn.DEPART, 0.0, 0, "", 0.0), Maneuver(Turn.LEFT, cum[45], 45, "Main St", -90.0), Maneuver(Turn.ARRIVE, cum[99], 99, "", 0.0))
        return Route(lats, lons, man, 600.0, false)
    }

    @Test fun rewordedTurnReplacesTheRouters() {
        val r = straight()
        val reworded = Cue(r.lats[45], r.lons[45], Turn.LEFT, "Left at the red barn")
        val planned = Route.withCues(r, listOf(reworded))
        val lefts = planned.maneuvers.filter { it.turn == Turn.LEFT }
        assertEquals(1, lefts.size, "the router's turn and the reworded one must merge into one")
        assertEquals("Main St", lefts[0].name); assertEquals("Left at the red barn", lefts[0].note)
        assertEquals("Left. Left at the red barn", lefts[0].phrase())
        // a note elsewhere is simply added
        val withNote = Route.withCues(r, listOf(Cue(r.lats[20], r.lons[20], Turn.NOTE, "Fuel on the right")))
        assertEquals(4, withNote.maneuvers.size)
    }

    @Test fun authoredCuesKeepTheRidersWordsOnly() {
        val r = straight()
        val routers = Cue(r.lats[45], r.lons[45], Turn.LEFT, "Main St")        // as a saved file carries it
        val bare = Cue(r.lats[45], r.lons[45], Turn.LEFT, "")
        val mine = Cue(r.lats[45], r.lons[45], Turn.LEFT, "Left at the red barn")
        val gone = Cue(r.lats[10], r.lons[10], Turn.RIGHT, "Old Rd")           // no such turn any more: kept as authored
        val kept = Route.authoredCues(listOf(routers, bare, mine, gone), r)
        assertEquals(listOf(mine, gone), kept)
    }

    @Test fun stopNamesRoundTrip() {
        val lats = DoubleArray(50) { 45.0 + it * 0.001 }; val lons = DoubleArray(50) { -111.0 }
        val text = Gpx.writeRoute("Loop", "RIDE", doubleArrayOf(45.0, 45.02, 45.049), doubleArrayOf(-111.0, -111.0, -111.0), listOf("Home", "Lolo Pass", "Home"), lats, lons)
        val t = Gpx.parse(text)!!
        assertEquals(listOf("Home", "Lolo Pass", "Home"), t.rteNames)
        assertEquals("Lolo Pass", t.rteName(1)); assertEquals("", t.rteName(7))
        assertTrue(t.cues.isEmpty())
    }
}
