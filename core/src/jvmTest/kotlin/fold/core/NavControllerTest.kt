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

    @Test fun theChimeComesBeforeTheJunction() {
        // at 15 m/s the chime must land in the turn-in zone: well before the junction, not at it or past it
        val r = Fixtures.track(course, cues)
        val rec = Rec(); val c = NavController(r, true, "Test", "FAST", rec).also { it.sound = true; it.speechReady = false }
        val fixes = Fixtures.ride(course, 15.0, noise = 1.5)
        val chimedAt = ArrayList<Double>()
        for (f in fixes) {
            c.update(f)
            for (dt in listOf(0L, 400L, 800L)) { val before = rec.log.count { it == "tone:TURN" }; c.tick(f.timeMs + dt); if (rec.log.count { it == "tone:TURN" } > before) chimedAt.add(c.view(f.timeMs + dt).distToNext) }
        }
        assertEquals(2, chimedAt.size, "one chime per turn: $chimedAt")
        for (d in chimedAt) assertTrue(d in 14.0..NavController.chimeLead(15.0) + 1.0, "chime $d m before the junction (lead ${NavController.chimeLead(15.0)} m)")
    }

    @Test fun pipsGoQuietWhenStoppedAndComeBackWhenMoving() {
        // ride to 60 m short of the first turn, sit there for eight seconds, then move on: pips during the sit must stop
        val r = Fixtures.track(course, cues)
        val rec = Rec(); val c = NavController(r, true, "Test", "FAST", rec).also { it.sound = true; it.speechReady = false }
        val fixes = Fixtures.ride(course, 12.0, noise = 0.5)
        val firstTurn = r.maneuvers.first { it.turn != Turn.DEPART }
        val stopAt = fixes.indexOfFirst { f -> c.follower.let { true } && Geo.fastDistance(f.lat, f.lon, r.lats[firstTurn.vertex], r.lons[firstTurn.vertex]) < 60.0 }
        assertTrue(stopAt > 0)
        for (i in 0 until stopAt) { c.update(fixes[i]); c.tick(fixes[i].timeMs); c.tick(fixes[i].timeMs + 500) }
        val here = fixes[stopAt - 1]
        val pipsBefore = rec.log.count { it == "tone:PIP" }
        var pipsWhileStopped = 0
        for (k in 1..8) { val t = here.timeMs + k * 1000L; c.update(Fix(here.lat, here.lon, 0.0, -1.0, t, 5.0)); val b = rec.log.count { it == "tone:PIP" }; c.tick(t); c.tick(t + 500); if (k > 3) pipsWhileStopped += rec.log.count { it == "tone:PIP" } - b }
        assertEquals(0, pipsWhileStopped, "no pips after standing for more than ${NavController.STOP_QUIET_MS} ms")
        // moving again: the rest of the ride pips and chimes as usual
        val t0 = here.timeMs + 9000L
        for (i in stopAt until fixes.size) { val f = fixes[i]; val t = t0 + (f.timeMs - fixes[stopAt].timeMs); c.update(Fix(f.lat, f.lon, f.speed, f.bearing, t, f.accuracy)); c.tick(t); c.tick(t + 500) }
        assertTrue(rec.log.count { it == "tone:PIP" } > pipsBefore, "pips resumed once moving")
        assertEquals(2, rec.log.count { it == "tone:TURN" }, "both turns chimed")
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

/** Battery mode: the GPS eases between cues and never so far that a turn can be missed; the screen is asked for only around cues. */
class BatteryDemandTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)
    private val long = (0..200).map { p(it * 50.0, 0.0) }   // 10 km straight east, a point every 50 m
    private class Rec : NavSink {
        val attention = ArrayList<Boolean>()
        override fun tone(kind: ToneKind, level: Int, progress: Double) {}
        override fun say(phrase: String) {}
        override fun prepare(phrase: String) {}
        override fun attention(on: Boolean) { attention.add(on) }
    }

    @Test fun farFromACueTheGpsEasesAndNearItTightens() {
        val cues = listOf(Fixtures.cue(8000.0, 0.0, Turn.DANGER, "washout", 2))
        val r = Fixtures.track(long, cues)
        val rec = Rec(); val c = NavController(r, true, "Test", "RIDE", rec).also { it.sound = true; it.batterySaver = true; it.worstSpeed = 10.0 }
        val fixes = Fixtures.ride(long, 6.0)   // 6 m/s, a bike
        var eased = 0; var tight = 0; var longestGapBeforeCue = 0L
        for (f in fixes) {
            c.update(f); c.tick(f.timeMs)
            val v = c.view(f.timeMs); val d = c.demand(f.timeMs)
            if (v.along < 5000.0) { if (d.fixIntervalMs > 5000L) eased++ } else if (v.along > 7700.0 && v.along < 8000.0) { if (d.fixIntervalMs == 1000L) tight++ }
            // the guarantee: at the worst speed, at least six fixes fit before the cue
            val gap = 8000.0 - v.along; if (gap > 0 && v.along < 7900.0) assertTrue(d.fixIntervalMs <= (gap / 10.0 / 6.0 * 1000.0).toLong().coerceAtLeast(1000L), "interval ${d.fixIntervalMs} ms leaves six fixes at 10 m/s before the cue ${"%.0f".format(gap)} m away")
            longestGapBeforeCue = Math.max(longestGapBeforeCue, d.fixIntervalMs)
        }
        assertTrue(eased > 100, "the GPS eased on the long empty leg ($eased fixes at >5 s)")
        assertTrue(tight > 20, "and tightened to 1 Hz on the approach ($tight)")
        assertTrue(longestGapBeforeCue <= 15_000L, "never more than 15 s between fixes")
        assertTrue(rec.attention.first(), "the screen was asked for when the cue's window opened")
        assertTrue(rec.attention.count { it } in 1..3, "asked for around the cue and arrival, not all ride: ${rec.attention}")
    }

    @Test fun offTheRouteEverythingWakes() {
        val r = Fixtures.track(long)
        val rec = Rec(); val c = NavController(r, true, "Test", "RIDE", rec).also { it.sound = true; it.batterySaver = true; it.worstSpeed = 10.0 }
        val fixes = Fixtures.ride(long.take(60), 6.0)
        for (f in fixes) { c.update(f); c.tick(f.timeMs) }
        // wander 400 m north of the line for a while
        val last = fixes.last(); var t = last.timeMs
        for (k in 1..8) { t += 1000; val q = Fixtures.ll(3000.0 + k * 6.0, 400.0); c.update(Fix(q[0], q[1], 6.0, 90.0, t, 5.0)); c.tick(t) }
        val d = c.demand(t)
        assertEquals(1000L, d.fixIntervalMs, "lost the line: one fix a second"); assertTrue(d.awake)
        assertTrue(rec.attention.contains(true), "and the screen is asked for")
    }
}
