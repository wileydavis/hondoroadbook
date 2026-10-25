package fold.core

import kotlin.math.max
import kotlin.math.min

/** One GPS fix. speed in m/s (<0 unknown), bearing degrees (<0 unknown), accuracy meters. */
class Fix(val lat: Double, val lon: Double, val speed: Double, val bearing: Double, val timeMs: Long, val accuracy: Double)

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
    val segment: Int
)

/**
 * Follows a Route from GPS fixes. Pure logic, no platform code. Call [update] per fix and
 * [view] whenever you render; view() dead-reckons between fixes so the bar moves at frame rate.
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
    private val proj = DoubleArray(4)

    val isOnRoute get() = onRoute

    fun update(fix: Fix) {
        val lf = lastFix
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

        prevFix = lf; lastFix = fix

        // snap in a window around the current segment, falling back to a global search
        var best = snapWindow(fix, max(0, seg - 3), min(route.size - 2, seg + 80))
        val tol = max(35.0, 2.0 * fix.accuracy)
        if (best == null || best[1] > tol) {
            val global = snapWindow(fix, 0, route.size - 2)
            if (global != null && (best == null || global[1] < best[1])) best = global
        }
        if (best == null) return
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
    }

    /** returns [t, dist, lat, lon, segIndex] of the nearest segment in [a,b], or null */
    private fun snapWindow(fix: Fix, a: Int, b: Int): DoubleArray? {
        var bestD = Double.MAX_VALUE; var out: DoubleArray? = null
        for (i in a..b) {
            Geo.projectOnSegment(fix.lat, fix.lon, route.lats[i], route.lons[i], route.lats[i + 1], route.lons[i + 1], proj)
            // mild preference for staying near the current segment when distances tie
            val penalty = if (i < seg) 0.5 * (seg - i).coerceAtMost(40) else 0.0
            val d = proj[1] + penalty
            if (d < bestD) { bestD = d; out = doubleArrayOf(proj[0], proj[1], proj[2], proj[3], i.toDouble()) }
        }
        return out
    }

    private fun advanceManeuver() {
        val ms = route.maneuvers
        while (nextIdx < ms.size - 1 && along > ms[nextIdx].at + 10.0) { nextIdx++; windowMeters = -1.0; barMax = 0.0 }
        // arrival: within 20 m of the end, or past it
        if (along >= route.length - 20.0 && offDist < 40.0) arrived = true
    }

    /** Dead-reckoned view for rendering at [nowMs]. */
    fun view(nowMs: Long): NavView {
        val lf = lastFix
        val next = route.maneuvers.getOrNull(nextIdx)
        if (lf == null) {
            return NavView(true, false, 0.0, next, next?.at ?: 0.0, 0.0, route.length, eta(route.length), route.lats[0], route.lons[0], 0.0, 0.0, 0.0, 0.0, 0)
        }
        var est = along
        if (onRoute) {
            val dt = ((nowMs - lf.timeMs) / 1000.0).coerceIn(0.0, 3.0)
            est = along + speedEma * dt
        }
        val nextAt = next?.at ?: route.length
        if (next != null && next.turn != Turn.ARRIVE) est = min(est, nextAt + 5.0) else est = min(est, route.length)
        val dist = max(0.0, nextAt - est)
        // activation window: freeze the meters-to-go when the cue first comes into range
        val range = max(80.0, speedEma * lookAheadSeconds).coerceAtMost(600.0)
        if (windowMeters < 0 && dist <= range) windowMeters = max(dist, 1.0)
        var progress = if (windowMeters < 0 || !onRoute) 0.0 else (1.0 - dist / windowMeters).coerceIn(0.0, 1.0)
        if (onRoute) { if (progress > barMax) barMax = progress else progress = barMax }
        val remaining = max(0.0, route.length - est)
        val (plat, plon) = if (onRoute) interpolate(est) else Pair(lf.lat, lf.lon)
        return NavView(onRoute, arrived, est, next, dist, progress, remaining, eta(remaining), plat, plon, heading, speedEma, offDist, offBearing, seg)
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
