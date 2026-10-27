package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NAV-01/02/03: course progress, cue delivery and GPS confidence on synthetic courses. */
class FollowerTest {
    private fun p(e: Double, n: Double) = doubleArrayOf(e, n)
    private val square = listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, 900.0), p(0.0, 900.0), p(0.0, 0.0))   // 3.8 km closed loop

    @Test fun loopStartIsNotArrival() {
        val r = Fixtures.track(square)
        val f = Follower(r)
        // first fix 2 m north of the start, which is also the end of the loop
        val s = Fixtures.ll(0.0, 2.0)
        f.update(Fix(s[0], s[1], 0.0, -1.0, 1_000_000L, 3.0))
        val v = f.view(1_000_050L)
        assertFalse(v.arrived, "a fix at the start must not finish the loop")
        assertTrue(v.along < 50.0, "matched near the start, along=${v.along}")
        assertEquals(0, v.segment)
    }

    @Test fun loopRiddenEndsAtArrivalInOrder() {
        val r = Fixtures.track(square)
        val f = Follower(r)
        val fixes = Fixtures.ride(square, 15.0, noise = 2.0)
        var arrivedAt = -1
        for ((i, fx) in fixes.withIndex()) { f.update(fx); if (f.view(fx.timeMs).arrived && arrivedAt < 0) arrivedAt = i }
        assertTrue(arrivedAt > fixes.size * 0.9, "arrival only at the very end (fix $arrivedAt of ${fixes.size})")
        val v = f.view(fixes.last().timeMs); assertTrue(v.arrived); assertTrue(v.remaining < 25.0)
    }

    @Test fun outAndBackStaysOnTheRightLeg() {
        val out = listOf(p(0.0, 0.0), p(1100.0, 0.0), p(1100.0, 30.0), p(0.0, 30.0))   // out along y=0, back along y=30
        val r = Fixtures.track(out)
        val f = Follower(r)
        val fixes = Fixtures.ride(out, 12.0, noise = 3.0)
        var maxAlong = 0.0; var regress = 0
        for (fx in fixes) { f.update(fx); val a = f.view(fx.timeMs).along; if (a < maxAlong - 60.0) regress++; maxAlong = Math.max(maxAlong, a) }
        val v = f.view(fixes.last().timeMs)
        assertTrue(v.along > r.length - 40.0, "progress reached the end: along=${v.along} of ${r.length}")
        assertTrue(v.arrived, "arrived after the return leg")
        assertTrue(regress <= 2, "progress never jumps back to the outbound leg ($regress regressions)")
    }

    @Test fun dangerAtTheTurnIsDeliveredWithIt() {
        val line = listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, 1000.0))
        val cues = listOf(Fixtures.cue(1000.0, 0.0, Turn.LEFT), Fixtures.cue(1000.0, 0.0, Turn.DANGER, "washout", 3))
        val r = Fixtures.track(line, cues)
        val f = Follower(r)
        val seen = LinkedHashSet<String>()
        for (fx in Fixtures.ride(line, 20.0)) { f.update(fx); val v = f.view(fx.timeMs); seen.add(v.group.joinToString("+") { it.turn.name }); for (m in f.drainMissed()) seen.add("missed:" + m.turn.name) }
        assertTrue(seen.any { it == "LEFT+DANGER" || it == "DANGER+LEFT" }, "the turn and the danger form one instruction: $seen")
        assertFalse(seen.any { it.startsWith("missed:") }, "nothing was dropped: $seen")
        val v0 = Follower(r).view(0L); assertTrue(v0.phrase().contains("washout"), v0.phrase())
    }

    @Test fun sparseFixesCrossingTwoCuesReportTheSecond() {
        val line = listOf(p(0.0, 0.0), p(3000.0, 0.0))
        val cues = listOf(Fixtures.cue(1000.0, 0.0, Turn.RIGHT), Fixtures.cue(1400.0, 0.0, Turn.DANGER, "cattle guard", 2))
        val r = Fixtures.track(line, cues)
        val f = Follower(r)
        // 40 m/s with a fix every 20 s: 800 m per fix, both cues crossed in one update
        var t = 1_000_000L
        for (e in listOf(0.0, 800.0, 1600.0, 2400.0)) { val q = Fixtures.ll(e, 0.0); f.update(Fix(q[0], q[1], 40.0, 90.0, t, 5.0)); t += 20_000 }
        val missed = f.drainMissed()
        assertEquals(listOf(Turn.DANGER), missed.map { it.turn }, "the danger crossed in the same update as the turn is reported, not dropped")
    }

    @Test fun staleGpsIsNotLiveGuidance() {
        val line = listOf(p(0.0, 0.0), p(3000.0, 0.0))
        val r = Fixtures.track(line)
        val f = Follower(r)
        val q = Fixtures.ll(500.0, 0.0)
        assertEquals(FixState.NONE, f.view(0L).fixState)
        f.update(Fix(q[0], q[1], 30.0, 90.0, 1_000_000L, 5.0))
        assertEquals(FixState.VALID, f.view(1_001_000L).fixState)
        val later = f.view(1_000_000L + 600_000L)
        assertEquals(FixState.STALE, later.fixState)
        assertEquals(0.0, later.speed, "stale speed is not shown as current")
        assertTrue(later.fixAge > 500.0)
        assertTrue(later.along < 500.0 + 30.0 * 3.0 + 1.0, "dead reckoning stops: along=${later.along}")
        // poor accuracy is flagged
        f.update(Fix(q[0], q[1], 30.0, 90.0, 1_700_000L, 120.0))
        assertEquals(FixState.DEGRADED, f.view(1_700_500L).fixState)
    }

    @Test fun outOfOrderAndTeleportingFixesAreIgnored() {
        val line = listOf(p(0.0, 0.0), p(3000.0, 0.0))
        val f = Follower(Fixtures.track(line))
        val a = Fixtures.ll(100.0, 0.0); val b = Fixtures.ll(2900.0, 0.0)
        assertTrue(f.update(Fix(a[0], a[1], 20.0, 90.0, 1_000_000L, 5.0)))
        assertFalse(f.update(Fix(a[0], a[1], 20.0, 90.0, 999_000L, 5.0)), "older timestamp rejected")
        assertFalse(f.update(Fix(b[0], b[1], 20.0, 90.0, 1_001_000L, 5.0)), "2.8 km in one second rejected")
        assertEquals(2, f.rejectedFixes)
        assertTrue(f.view(1_001_000L).along < 150.0)
        // a second fix agreeing with the wild one confirms it: the position really moved
        assertTrue(f.update(Fix(b[0], b[1], 20.0, 90.0, 1_002_000L, 5.0)), "confirmed jump accepted")
        assertTrue(f.view(1_002_000L).along > 2800.0)
        // and after a long gap a jump is a reacquisition straight away
        val f2 = Follower(Fixtures.track(line))
        f2.update(Fix(a[0], a[1], 20.0, 90.0, 1_000_000L, 5.0))
        assertTrue(f2.update(Fix(b[0], b[1], 20.0, 90.0, 1_000_000L + 120_000L, 5.0)))
    }
}
