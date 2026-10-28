package fold.core

import kotlin.math.max

/**
 * What the platform does on the controller's behalf. The controller decides *when* a pip, a chime or a
 * phrase is due; the phone turns that into an AudioTrack, an AVAudioSession, a headset — its business.
 */
interface NavSink {
    fun tone(kind: ToneKind, level: Int = 0, progress: Double = 0.0)
    /** speak now (replaces whatever is being said) */
    fun say(phrase: String)
    /** synthesise ahead of time so the words start the moment they are due */
    fun prepare(phrase: String)
    /** the controller's state changed in a way worth showing (a new view, arrival, a checkpoint written) */
    fun changed(c: NavController) {}
    /**
     * The rider should look now (a cue's window opened, the route was lost) or may stop looking (the turn is
     * made, the line is found again). In battery mode the phone wakes the screen for `true` and lets it sleep
     * after `false`; on iOS the same moments drive a Live Activity alert.
     */
    fun attention(on: Boolean) {}
}

/**
 * What the ride needs from the platform right now, so a battery-saving phone can give exactly that and no
 * more. [fixIntervalMs] is the longest gap between position fixes the follower can tolerate before the next
 * cue (a bike far from its next turn needs one every 15 s, not every second); [awake] says whether the CPU must
 * stay up between fixes (pips are due, or the route is lost) or may sleep until the next fix wakes it. On
 * Android this becomes the location request interval and a wake lock; on iOS the desired-accuracy ladder.
 */
class Demand(val fixIntervalMs: Long, val awake: Boolean)

enum class ToneKind { PIP, TURN, OFF_ROUTE, BACK, ARRIVE, DANGER, NOTE }

/**
 * One navigation session, with no platform in it: a route, its follower, the cue-delivery state (what has
 * been announced, chimed, passed) and the rules for pips, chimes and speech. Feed it fixes and ticks; it
 * tells the [NavSink] what to play and hands any screen a [NavView] to draw. It can write itself to a
 * checkpoint and come back from one, so a dead process resumes the ride where it was.
 *
 * Single-threaded by contract: call [update] and [tick] from one thread (the platform's main loop).
 */
class NavController(val route: Route, val isTrack: Boolean, val destName: String, val profileName: String, private val sink: NavSink) {
    val follower = Follower(route)
    /** set by the platform: whether to play sounds at all, and whether a voice is ready to speak */
    var sound = false
    var speechReady = false
    /** battery mode: ease the GPS between cues and only ask for the screen when a turn is near */
    var batterySaver = false
    /** the worst speed the rider might reach between fixes, for the fix-interval guarantee (bikes 10 m/s, motors 30) */
    var worstSpeed = 30.0
    private var attentionOn = false
    /** the last accepted fix, for the screen and for a checkpoint */
    var lastFix: Fix? = null; private set
    var ended = false; private set

    // cue delivery state, moved verbatim from the Android activity's soundTick
    private var nextPipAt = 0L
    private var cuedManeuver: Maneuver? = null
    private var turnChimed = false
    private var wasOffRoute = false
    private var arrivedChimed = false
    private var spoken = false
    private var prevGroup: List<Maneuver> = emptyList()

    fun update(fix: Fix) { if (ended) return; lastFix = fix; follower.update(fix) }

    fun view(nowMs: Long): NavView = follower.view(nowMs)

    /** The periodic tick: computes the view, delivers any sound that is due, returns the view for the screen. */
    @Throws(Throwable::class) fun tick(nowMs: Long): NavView {
        val v = follower.view(nowMs)
        if (!ended) soundTick(v, nowMs)
        return v
    }

    fun end() { ended = true; if (attentionOn) { attentionOn = false; sink.attention(false) } }

    private fun attention(on: Boolean) { if (on != attentionOn) { attentionOn = on; sink.attention(on) } }

    /**
     * The fix cadence and wakefulness the ride needs now. Outside battery mode: one fix a second, always awake.
     * In battery mode: at least six fixes before the next cue at [worstSpeed], never longer than 15 s apart,
     * and one a second inside 300 m or 60 s of a cue, whenever the route is lost, and for the last 2 km of
     * the ride; the CPU may sleep between fixes except while a cue window is open or the route is lost.
     */
    fun demand(nowMs: Long): Demand {
        if (!batterySaver || ended) return Demand(1000L, true)
        val v = follower.view(nowMs)
        val gap = v.next?.let { it.at - v.along } ?: (route.length - v.along)
        val busy = !v.onRoute || v.progress > 0.0 || v.fixState != FixState.VALID || gap < 300.0 || gap / max(1.0, v.speed) < 60.0 || v.remaining < 2000.0
        if (busy) return Demand(1000L, true)
        val interval = (gap / worstSpeed / 6.0 * 1000.0).toLong().coerceIn(1000L, 15_000L)
        return Demand(interval, false)
    }

    /** Interval between pips for a given progress: 1.6 s far out, 0.25 s at the turn. */
    fun pipIntervalMs(progress: Double): Long = (1600 - 1350 * progress.coerceIn(0.0, 1.0)).toLong()

    private fun hazardsOf(ms: List<Maneuver>) = ms.filter { it.turn == Turn.DANGER || it.turn == Turn.NOTE }
    private fun announcePassed(ms: List<Maneuver>) {
        if (ms.isEmpty()) return
        // one combined phrase, so none cuts another off (each say() replaces the last)
        if (sound) { if (ms.any { it.turn == Turn.DANGER }) sink.tone(ToneKind.DANGER, ms.maxOf { it.level }) else sink.tone(ToneKind.NOTE) }
        if (speechReady) sink.say("Passed. " + ms.joinToString(". ") { it.phrase() })
    }

    private fun soundTick(v: NavView, now: Long) {
        // cues crossed without having been the current instruction (sparse fixes at speed): say them late rather than never
        announcePassed(hazardsOf(follower.drainMissed()))
        if (!sound) return
        if (v.fixState == FixState.NONE || v.fixState == FixState.STALE) return
        if (v.arrived) { if (!arrivedChimed) { arrivedChimed = true; sink.tone(ToneKind.ARRIVE); attention(true) }; return }
        if (!v.onRoute) { if (!wasOffRoute) { wasOffRoute = true; sink.tone(ToneKind.OFF_ROUTE); attention(true) }; return }
        if (wasOffRoute) { wasOffRoute = false; sink.tone(ToneKind.BACK); attention(false) }
        val m = v.next ?: return
        if (m !== cuedManeuver) {
            // the previous instruction's window never opened (passed it between two fixes): deliver its hazards now
            if (cuedManeuver != null && !spoken) announcePassed(hazardsOf(prevGroup))
            cuedManeuver = m; prevGroup = v.group; turnChimed = false; spoken = false; nextPipAt = 0L
            attention(false)                                    // the last turn is made; the screen may sleep again
            if (m.turn != Turn.ARRIVE && speechReady) sink.prepare(v.phrase())
        }
        if (m.turn == Turn.ARRIVE) return
        if (v.progress <= 0.0) return
        // the window just opened (~15 s out): say what is coming, then the pips take over
        if (!spoken) { spoken = true; attention(true); if (speechReady) { val ph = v.phrase(); sink.say(ph); nextPipAt = now + 1500 + 60L * ph.length } }
        // stopped in the approach (a stop sign, waiting for a gap): after a few seconds the pips go quiet, and the
        // chime waits too; both come back the moment the wheels turn
        if (v.stoppedMs >= STOP_QUIET_MS) { nextPipAt = now + 300; return }
        // the chime marks the moment to turn in, not the junction itself: about two seconds of road at this speed
        // (the GPS runs a second behind, and the bars go before the wheel is on the new road), never under 15 m
        if (v.distToNext <= chimeLead(v.speed)) {
            if (!turnChimed) { turnChimed = true; val lead = v.hazard ?: m; when (lead.turn) { Turn.DANGER -> sink.tone(ToneKind.DANGER, lead.level); Turn.NOTE -> sink.tone(ToneKind.NOTE); else -> sink.tone(ToneKind.TURN) } }
            return
        }
        if (now >= nextPipAt) { sink.tone(ToneKind.PIP, progress = v.progress); nextPipAt = now + pipIntervalMs(v.progress) }
    }

    // ---- checkpoint ------------------------------------------------------------------------------------------------

    /**
     * The session as text: a few header lines, a blank line, then the route as a GPX with its cues. A planned
     * route comes back as a track-like route (the same line, the same cues, no graph), with the destination kept
     * so the phone can offer a reroute. Progress is kept so a repeated section resolves to the right pass.
     */
    fun checkpoint(nowMs: Long): String {
        val v = follower.view(nowMs)
        val cues = route.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }.map { m ->
            // a track's maneuvers carry their words in note (name is the track's); a planned route's turns carry the road in name
            Cue(route.lats[m.vertex], route.lons[m.vertex], m.turn, if (route.isTrack) m.note else m.note.ifEmpty { m.name }, m.level)
        }
        val gpx = Gpx.writeTrack(destName.ifEmpty { "Roadbook" }, route.lats, route.lons, null, cues)
        val n = route.size - 1
        return buildString {
            append("roadbook-nav 1\n")
            append("track=").append(isTrack).append('\n')
            append("dest=").append(destName.replace('\n', ' ')).append('\n')
            append("profile=").append(profileName).append('\n')
            append("destLat=").append(route.lats[n]).append('\n')
            append("destLon=").append(route.lons[n]).append('\n')
            append("along=").append(if (v.onRoute) v.along else -1.0).append('\n')
            append('\n')
            append(gpx)
        }
    }

    companion object {
        /** standing still this long in an approach silences the pips until you move again */
        const val STOP_QUIET_MS = 3000L
        /** How far before a turn the chime sounds, in metres: 15 at walking pace, ~38 at 50 km/h, 70 at highway speed. */
        fun chimeLead(speedMps: Double): Double = (8.0 + speedMps * 2.0).coerceIn(15.0, 70.0)

        /** Builds a session back from [checkpoint] text, or null when the text is not one. */
        @Throws(Throwable::class) fun restore(text: String, sink: NavSink): NavController? {
            if (!text.startsWith("roadbook-nav 1\n")) return null
            val cut = text.indexOf("\n\n"); if (cut < 0) return null
            val head = HashMap<String, String>()
            for (line in text.substring(0, cut).lines().drop(1)) { val i = line.indexOf('='); if (i > 0) head[line.substring(0, i)] = line.substring(i + 1) }
            val tr = try { Gpx.parse(text.substring(cut + 2)) } catch (_: Throwable) { null } ?: return null
            if (tr.lats.size < 2) return null
            val route = Route.fromTrack(tr.lats, tr.lons, head["dest"] ?: "Roadbook", tr.cues)
            val c = NavController(route, head["track"] == "true", head["dest"] ?: "", head["profile"] ?: "", sink)
            head["along"]?.toDoubleOrNull()?.let { if (it >= 0) c.follower.resumeAt(it) }
            return c
        }
    }
}
