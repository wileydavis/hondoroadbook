package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The platform-free navigation session: the sound rules, and a checkpoint that resumes where the ride was. */
class NavControllerTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)
    // two left turns, 4.2 km, a point every 50 m as a recorded track would have (cues sit on the nearest vertex)
    private val course = dense(listOf(p(0.0, 0.0), p(1500.0, 0.0), p(1500.0, 1200.0), p(0.0, 1200.0)), 50.0)
    private fun dense(pts: List<DoubleArray>, step: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; val n = Math.max(1, Math.round(Math.hypot(b[0] - a[0], b[1] - a[1]) / step).toInt()); for (k in 0 until n) out.add(p(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)) }
        out.add(pts.last()); return out
    }
    // a roadbook: the turns are authored cues (a track carries no turns of its own), plus a washout on the second leg
    private val cues = listOf(Fixtures.cue(1500.0, 0.0, Turn.LEFT, "Second Street"), Fixtures.cue(1500.0, 600.0, Turn.DANGER, "washout", 3), Fixtures.cue(1500.0, 1200.0, Turn.LEFT, "Third Street"))

    private class Rec : NavSink {
        val log = ArrayList<String>()
        override fun tone(kind: ToneKind, level: Int, progress: Double) { log.add("tone:$kind") }
        override fun say(phrase: String) { log.add("say:$phrase") }
        override fun prepare(phrase: String) { log.add("prep:$phrase") }
    }

    private fun ride(c: NavController, fixes: List<Fix>, from: Int = 0, to: Int = fixes.size) {
        for (i in from until to) { c.update(fixes[i]); c.tick(fixes[i].timeMs); c.tick(fixes[i].timeMs + 400); c.tick(fixes[i].timeMs + 800) }
    }

    @Test fun aTurnIsSpokenThenPippedThenChimed() {
        val r = Fixtures.track(course, cues)
        val rec = Rec(); val c = NavController(r, true, "Test", "FAST", rec).also { it.sound = true; it.speechReady = true }
        ride(c, Fixtures.ride(course, 15.0, noise = 1.5))
        val log = rec.log
        val says = log.filter { it.startsWith("say:") }
        assertTrue(says.any { it.contains("Left") }, "the turn was spoken: $says")
        assertTrue(says.any { it.contains("washout") }, "the hazard was spoken: $says")
        val firstSay = log.indexOfFirst { it.startsWith("say:Left") }; val firstPip = log.indexOf("tone:PIP"); val chime = log.indexOf("tone:TURN")
        assertTrue(firstSay in 0 until firstPip && firstPip < chime, "spoken ($firstSay) before pips ($firstPip) before the chime ($chime)")
        assertTrue(log.count { it == "tone:TURN" } == 2, "one chime per turn: ${log.count { it == "tone:TURN" }}")
        assertTrue(log.contains("tone:DANGER"), "the hazard buzzed")
        assertEquals("tone:ARRIVE", log.last { it.startsWith("tone:") }, "arrival last")
    }

    @Test fun checkpointResumesWithoutRepeatingWhatWasPassed() {
        val r = Fixtures.track(course, cues)
        val rec = Rec(); val c = NavController(r, true, "Test", "FAST", rec).also { it.sound = true; it.speechReady = true }
        val fixes = Fixtures.ride(course, 15.0, noise = 1.5)
        val half = fixes.size * 6 / 10       // past the first turn and the washout, well up the second leg
        ride(c, fixes, 0, half)
        val text = c.checkpoint(fixes[half - 1].timeMs)
        assertTrue(text.startsWith("roadbook-nav 1\n") && text.contains("<trkpt"), "a checkpoint with the route in it")
        val rec2 = Rec(); val c2 = assertNotNull(NavController.restore(text, rec2)).also { it.sound = true; it.speechReady = true }
        assertEquals(r.maneuvers.size, c2.route.maneuvers.size, "the same cues came back")
        assertEquals("Test", c2.destName)
        ride(c2, fixes, half, fixes.size)
        val says = rec2.log.filter { it.startsWith("say:") }
        assertTrue(says.none { it.contains("washout") }, "the washout was already passed; not spoken again: $says")
        assertTrue(says.any { it.contains("Left. Third Street") }, "the remaining turn is spoken with its words: $says")
        assertEquals("tone:ARRIVE", rec2.log.last { it.startsWith("tone:") })
    }
}
