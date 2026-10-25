package fold.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One GPS fix. speed in m/s (<0 unknown), bearing degrees (<0 unknown), accuracy meters. */
class Fix(val lat: Double, val lon: Double, val speed: Double, val bearing: Double, val timeMs: Long, val accuracy: Double)

/** How much the position can be trusted right now. */
enum class FixState {
    /** no fix yet */
    NONE,
    /** fresh and precise */
    VALID,
    /** fresh but imprecise (accuracy worse than 50 m), or a few seconds old */
    DEGRADED,
    /** nothing from the GPS for a while: guidance is frozen, not current */
    STALE
}

/** What the HUD needs, computed for a moment in time. */
class NavView(
    val onRoute: Boolean,
    val arrived: Boolean,
    val along: Double,
    val next: Maneuver?,
    val distToNext: Double,
    /** 0..1, fills as the maneuver approaches; 1 exactly at the turn */
    val progress: Double,
    val remaining: Double,
    val etaSeconds: Double,
    val lat: Double, val lon: Double, val heading: Double, val speed: Double,
    /** when off route: straight-line distance and bearing to the nearest point of the route */
    val offDist: Double, val offBearing: Double,
    /** index of the segment we are on */
    val segment: Int,
    val fixState: FixState = FixState.VALID,
    /** seconds since the last accepted fix */
    val fixAge: Double = 0.0,
    /** [next] plus every cue at the same place (within 20 m along the route), in order: one instruction */
    val group: List<Maneuver> = listOfNotNull(next)
) {
    /** everything to say for the next instruction: "Left. Danger. washout" */
    fun phrase(): String = group.joinToString(". ") { it.phrase() }
    /** the group's most important cue for the arrow: a danger outranks a turn */
    val hazard: Maneuver? get() = group.firstOrNull { it.turn == Turn.DANGER }
}

/**
 * Follows a Route from GPS fixes. Pure logic, no platform code. Call [update] per fix and
 * [view] whenever you render; view() dead-reckons between fixes so the bar moves at frame rate.
 *
 * Matching is nearest-segment, constrained by plausibility: a fix cannot move the matched point
 * further along the route than the rider could have travelled since the last fix (plus GPS error),
 * so a loop's last leg is never mistaken for its first and an out-and-back stays on the right leg.
 * Cues at one place form a group delivered as one instruction; cues crossed without being delivered
 * (a sparse fix skipping two at once) are queued in [missed] so a hazard is never silently dropped.
 */
class Follower(val route: Route, private val lookAheadSeconds: Double = 15.0) {
    private var seg = 0
    private var along = 0.0
    private var lastFix: Fix? = null
    private var prevFix: Fix? = null
    private var heading = 0.0
    private var speedEma = 0.0
    private var offCount = 0
    private var onRoute = true
    private var offDist = 0.0; private var offBearing = 0.0
    private var snapLat = route.lats[0]; private var snapLon = route.lons[0]
    private var nextIdx = 1
    private var windowMeters = -1.0   // frozen when the bar activates for the current maneuver
    private var barMax = 0.0          // bar never moves backwards within one maneuver
    private var arrived = false
    private var nearEnd = false       // matched within 300 m of the end at least once: arrival needs ordered progress
    private val proj = DoubleArray(4)
    private var rejected = 0
    private var pending: Fix? = null   // a rejected jump waiting for confirmation
    /** Cues passed since the last [drainMissed] without having been the current instruction. */
    private val missed = ArrayList<Maneuver>()

    val isOnRoute get() = onRoute
    /** fixes ignored as out-of-order or implausible */
    val rejectedFixes: Int get() = rejected

    /** Cues crossed without delivery, oldest first; cleared by the call. */
    fun drainMissed(): List<Maneuver> { if (missed.isEmpty()) return emptyList(); val m = ArrayList(missed); missed.clear(); return m }

    /** Accepts a fix unless it is out of order or physically implausible. Returns false when ignored. */
    fun update(fix: Fix): Boolean {
        val lf = lastFix
        if (lf != null) {
            val dt = (fix.timeMs - lf.timeMs) / 1000.0
            if (dt <= 0.0) { rejected++; return false }                                      // out of order / duplicate
            val jump = Geo.fastDistance(lf.lat, lf.lon, fix.lat, fix.lon)
            // faster than 120 m/s is not a motorcycle; after a long gap anything goes (reacquisition).
            // One wild fix is ignored; a second one that agrees with it means the GPS really did move us.
            if (dt < 60.0 && jump / dt > 120.0 && jump > 2.0 * (fix.accuracy + lf.accuracy)) {
                val pend = pending
                if (pend == null || Geo.fastDistance(pend.lat, pend.lon, fix.lat, fix.lon) > 3.0 * (fix.accuracy + pend.accuracy) + 150.0) { pending = fix; rejected++; return false }
                pending = null   // confirmed: fall through and accept the new position
            } else pending = null
            if (dt > 20.0) { windowMeters = -1.0; barMax = 0.0 }                               // came back after a gap: re-arm the bar
        }
        // speed: trust GPS speed, else derive
        var sp = fix.speed
        if (sp < 0 && lf != null) {
            val dt = (fix.timeMs - lf.timeMs) / 1000.0
            if (dt > 0.2) sp = Geo.fastDistance(lf.lat, lf.lon, fix.lat, fix.lon) / dt
        }
        if (sp < 0) sp = 0.0
        speedEma = if (lf == null) sp else speedEma * 0.5 + sp * 0.5
        if (sp > 1.5 && fix.bearing >= 0) heading = fix.bearing
        else if (sp > 1.5 && lf != null) heading = Geo.bearing(lf.lat, lf.lon, fix.lat, fix.lon)

        // how far along the route the rider could plausibly have moved since the last fix
        val dt = if (lf == null) 0.0 else (fix.timeMs - lf.timeMs) / 1000.0
        val plausible = if (lf == null) 60.0 else max(60.0, 1.5 * max(sp, speedEma) * dt + 2.0 * fix.accuracy + 2.0 * lf.accuracy)
        prevFix = lf; lastFix = fix

        // snap in a window around the current segment, falling back to a global search
        var best = snapWindow(fix, max(0, seg - 3), min(route.size - 2, seg + 80), plausible)
        val tol = max(35.0, 2.0 * fix.accuracy)
        if (best == null || best[1] > tol) {
            val global = snapWindow(fix, 0, route.size - 2, plausible)
            if (global != null && (best == null || global[5] < best[5])) best = global
        }
        if (best == null) return true
        val d = best[1]
        offDist = d; offBearing = Geo.bearing(fix.lat, fix.lon, best[2], best[3])
        if (d > tol) {
            offCount++
            if (offCount >= 3) onRoute = false
        } else {
            offCount = 0
            if (!onRoute) { onRoute = true; windowMeters = -1.0; barMax = 0.0 }
        }
        if (onRoute || d <= tol) {
            val newSeg = best[4].toInt()
            val newAlong = route.cum[newSeg] + best[0] * (route.cum[newSeg + 1] - route.cum[newSeg])
            seg = newSeg; along = newAlong
            snapLat = best[2]; snapLon = best[3]
            if (!onRoute) { snapLat = fix.lat; snapLon = fix.lon }
        }
        advanceManeuver()
        return true
    }

    /** returns [t, dist, lat, lon, segIndex, score] of the best segment in [a,b], or null */
    private fun snapWindow(fix: Fix, a: Int, b: Int, plausible: Double): DoubleArray? {
        var bestScore = Double.MAX_VALUE; var out: DoubleArray? = null
        for (i in a..b) {
            Geo.projectOnSegment(fix.lat, fix.lon, route.lats[i], route.lons[i], route.lats[i + 1], route.lons[i + 1], proj)
            // a segment further ahead than the rider could have travelled, or well behind, costs extra metres
            val ahead = route.cum[i] - along - plausible
            val behind = along - plausible - route.cum[i + 1]
            val penalty = 0.5 * max(0.0, ahead) + 0.5 * max(0.0, behind)
            val score = proj[1] + penalty
            if (score < bestScore) { bestScore = score; out = doubleArrayOf(proj[0], proj[1], proj[2], proj[3], i.toDouble(), score) }
        }
        return out
    }

    private fun advanceManeuver() {
        val ms = route.maneuvers
        val current = nextIdx
        // pass whole groups. The current group was the delivered instruction (its companions were spoken with
        // it); any further group crossed in the same update is reported through [drainMissed], never dropped.
        while (nextIdx < ms.size - 1 && along > groupEnd(nextIdx) + 10.0) {
            val end = groupLast(nextIdx)
            if (nextIdx != current) for (i in nextIdx..end) if (ms[i].turn != Turn.ARRIVE) missed.add(ms[i])
            nextIdx = end + 1; windowMeters = -1.0; barMax = 0.0
        }
        // arrival: within 20 m of the end, or past it — but only after having been near the end in order
        if (along >= route.length - 300.0 && onRoute) nearEnd = true
        if (along >= route.length - 20.0 && offDist < 40.0 && (nearEnd || route.length < 300.0)) arrived = true
    }

    /** index of the last maneuver in the group that starts at [i] (same place: within 20 m along the route) */
    private fun groupLast(i: Int): Int {
        val ms = route.maneuvers; var j = i
        while (j + 1 < ms.size - 1 && ms[j + 1].at - ms[i].at <= 20.0) j++
        return j
    }
    private fun groupEnd(i: Int): Double = route.maneuvers[groupLast(i)].at

    private fun fixState(nowMs: Long): Pair<FixState, Double> {
        val lf = lastFix ?: return FixState.NONE to 0.0
        val age = max(0.0, (nowMs - lf.timeMs) / 1000.0)
        return when {
            age > 15.0 -> FixState.STALE
            age > 5.0 || lf.accuracy > 50.0 -> FixState.DEGRADED
            else -> FixState.VALID
        } to age
    }

    /** Dead-reckoned view for rendering at [nowMs]. */
    fun view(nowMs: Long): NavView {
        val lf = lastFix
        val next = route.maneuvers.getOrNull(nextIdx)
        val group = if (next == null) emptyList() else route.maneuvers.subList(nextIdx, groupLast(nextIdx) + 1)
        val (state, age) = fixState(nowMs)
        if (lf == null) {
            return NavView(false, false, 0.0, next, next?.at ?: 0.0, 0.0, route.length, eta(route.length), route.lats[0], route.lons[0], 0.0, 0.0, 0.0, 0.0, 0, FixState.NONE, 0.0, group)
        }
        var est = along
        if (onRoute && state != FixState.STALE) {
            val dt = ((nowMs - lf.timeMs) / 1000.0).coerceIn(0.0, 3.0)
            est = along + speedEma * dt
        }
        val nextAt = next?.at ?: route.length
        if (next != null && next.turn != Turn.ARRIVE) est = min(est, nextAt + 5.0) else est = min(est, route.length)
        val dist = max(0.0, nextAt - est)
        // activation window: freeze the meters-to-go when the cue first comes into range
        val range = max(80.0, speedEma * lookAheadSeconds).coerceAtMost(600.0)
        if (windowMeters < 0 && dist <= range && state != FixState.STALE) windowMeters = max(dist, 1.0)
        var progress = if (windowMeters < 0 || !onRoute) 0.0 else (1.0 - dist / windowMeters).coerceIn(0.0, 1.0)
        if (onRoute) { if (progress > barMax) barMax = progress else progress = barMax }
        val remaining = max(0.0, route.length - est)
        val (plat, plon) = if (onRoute) interpolate(est) else Pair(lf.lat, lf.lon)
        val speed = if (state == FixState.STALE) 0.0 else speedEma
        return NavView(onRoute, arrived, est, next, dist, progress, remaining, eta(remaining), plat, plon, heading, speed, offDist, offBearing, seg, state, age, group)
    }

    private fun eta(remaining: Double): Double {
        val v = if (!route.isTrack && route.timeSeconds > 0) route.length / route.timeSeconds else max(speedEma, 8.0)
        return remaining / max(v, 0.5)
    }

    private fun interpolate(at: Double): Pair<Double, Double> {
        var i = seg
        while (i < route.size - 2 && route.cum[i + 1] < at) i++
        while (i > 0 && route.cum[i] > at) i--
        val a = route.cum[i]; val b = route.cum[i + 1]
        val t = if (b > a) ((at - a) / (b - a)).coerceIn(0.0, 1.0) else 0.0
        return Pair(route.lats[i] + t * (route.lats[i + 1] - route.lats[i]), route.lons[i] + t * (route.lons[i + 1] - route.lons[i]))
    }
}
