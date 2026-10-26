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
}

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
    fun tick(nowMs: Long): NavView {
        val v = follower.view(nowMs)
        if (!ended) soundTick(v, nowMs)
        return v
    }

    fun end() { ended = true }

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
        if (v.arrived) { if (!arrivedChimed) { arrivedChimed = true; sink.tone(ToneKind.ARRIVE) }; return }
        if (!v.onRoute) { if (!wasOffRoute) { wasOffRoute = true; sink.tone(ToneKind.OFF_ROUTE) }; return }
        if (wasOffRoute) { wasOffRoute = false; sink.tone(ToneKind.BACK) }
        val m = v.next ?: return
        if (m !== cuedManeuver) {
            // the previous instruction's window never opened (passed it between two fixes): deliver its hazards now
            if (cuedManeuver != null && !spoken) announcePassed(hazardsOf(prevGroup))
            cuedManeuver = m; prevGroup = v.group; turnChimed = false; spoken = false; nextPipAt = 0L
            if (m.turn != Turn.ARRIVE && speechReady) sink.prepare(v.phrase())
        }
        if (m.turn == Turn.ARRIVE) return
        if (v.progress <= 0.0) return
        // the window just opened (~15 s out): say what is coming, then the pips take over
        if (!spoken) { spoken = true; if (speechReady) { val ph = v.phrase(); sink.say(ph); nextPipAt = now + 1500 + 60L * ph.length } }
        if (v.progress >= 0.97 || v.distToNext < 12.0) {
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
        /** Builds a session back from [checkpoint] text, or null when the text is not one. */
        fun restore(text: String, sink: NavSink): NavController? {
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
