package fold.core

import kotlin.math.abs

enum class Turn(val spoken: String) {
    DEPART("Depart"), STRAIGHT("Straight on"), SLIGHT_LEFT("Slight left"), SLIGHT_RIGHT("Slight right"), LEFT("Left"), RIGHT("Right"),
    SHARP_LEFT("Sharp left"), SHARP_RIGHT("Sharp right"), UTURN("U-turn"), KEEP_LEFT("Keep left"), KEEP_RIGHT("Keep right"), ROUNDABOUT("Roundabout"),
    /** an authored point of interest with words, no direction */
    NOTE("Note"),
    /** an authored hazard: washout, cattle guard, water crossing… level 1–3 */
    DANGER("Danger"),
    ARRIVE("Arrive")
}

class Maneuver(
    val turn: Turn,
    /** meters from route start */
    val at: Double,
    /** index of the route vertex where it happens */
    val vertex: Int,
    val name: String,
    /** signed turn angle, + = right */
    val angle: Double,
    val exit: Int = 0,
    /** authored words to say (a roadbook note) */
    val note: String = "",
    /** danger level 1–3, 0 for none */
    val level: Int = 0
) {
    /** What to say when the cue comes into range. */
    fun phrase(): String {
        val base = when (turn) {
            Turn.ROUNDABOUT -> if (exit > 0) "Roundabout, exit $exit" else "Roundabout"
            Turn.DANGER -> if (level >= 3) "Danger" else "Caution"
            Turn.NOTE -> ""
            else -> turn.spoken
        }
        return listOf(base, note).filter { it.isNotBlank() }.joinToString(". ")
    }
}

/** An authored cue read from a GPX waypoint. */
class Cue(val lat: Double, val lon: Double, val turn: Turn, val note: String, val level: Int = 0) {
    companion object {
        /**
         * The GPX convention: a waypoint's <type> names the cue — "cue:LEFT", "cue:SHARP_RIGHT", "danger:2", "note" —
         * and <desc> (or <name>) carries the words. Waypoints with no recognised type are ignored, so marks
         * and campsites in the same file don't become cues.
         */
        fun fromWaypoint(lat: Double, lon: Double, type: String?, name: String, desc: String): Cue? {
            val t = (type ?: "").trim().lowercase()
            val text = desc.ifBlank { name }.trim()
            return when {
                t.startsWith("cue:") -> { val turn = try { Turn.valueOf(t.substring(4).uppercase()) } catch (_: Throwable) { return null }; Cue(lat, lon, turn, text) }
                t.startsWith("danger") -> Cue(lat, lon, Turn.DANGER, text, t.substringAfter(':', "2").toIntOrNull()?.coerceIn(1, 3) ?: 2)
                t == "note" -> if (text.isEmpty()) null else Cue(lat, lon, Turn.NOTE, text)
                else -> null
            }
        }
        fun typeString(turn: Turn, level: Int) = when (turn) { Turn.DANGER -> "danger:$level"; Turn.NOTE -> "note"; else -> "cue:${turn.name}" }
    }
}

/** A polyline with cumulative distances and turn cues. Immutable after construction. */
class Route(val lats: DoubleArray, val lons: DoubleArray, val maneuvers: List<Maneuver>, val timeSeconds: Double, val isTrack: Boolean) {
    val cum: DoubleArray = DoubleArray(lats.size).also { c ->
        for (i in 1 until lats.size) c[i] = c[i - 1] + Geo.fastDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }
    val length: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]
    val size get() = lats.size

    /** Bearing of segment starting at vertex i (clamped). */
    fun segmentBearing(i: Int): Double {
        val a = i.coerceIn(0, lats.size - 2)
        return Geo.bearing(lats[a], lons[a], lats[a + 1], lons[a + 1])
    }

    companion object {
        fun fromGraphRoute(r: RouteResult, mr: MultiRouter, profile: Profile): Route {
            val lats = r.lats; val lons = r.lons
            val cum = DoubleArray(lats.size); for (i in 1 until lats.size) cum[i] = cum[i - 1] + Geo.fastDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
            val man = ArrayList<Maneuver>()
            man.add(Maneuver(Turn.DEPART, 0.0, 0, if (r.segEdge.size > 1) mr.graph(r.segPack[1]).edgeName(r.segEdge[1]) else "", 0.0))
            var i = 1
            while (i < lats.size - 1) {
                val eIn = r.segEdge[i]; val eOut = r.segEdge[i + 1]
                val node = r.vertexNode[i]
                if (eIn == eOut || node < 0 || r.segPack[i] != r.segPack[i + 1]) { i++; continue }
                val g = mr.graph(r.segPack[i]); val router = mr.routers[r.segPack[i]]
                val fIn = g.edgeFlags(eIn); val fOut = g.edgeFlags(eOut)
                // Roundabout: collapse the whole roundabout traversal into one maneuver at its entry.
                if (fIn and GraphFormat.ROUNDABOUT == 0 && fOut and GraphFormat.ROUNDABOUT != 0) {
                    var j = i + 1; var exits = 0
                    while (j < lats.size - 1) {
                        val en = r.vertexNode[j]; val eo = r.segEdge[j + 1]
                        if (r.segPack[j + 1] != r.segPack[i]) break
                        if (en >= 0) {
                            if (g.edgeFlags(eo) and GraphFormat.ROUNDABOUT == 0) { exits++; break }
                            // count exits passed: outgoing non-roundabout edges at this node
                            exits += countExits(g, en, profile)
                        }
                        j++
                    }
                    val leaveEdge = if (j + 1 < r.segEdge.size) r.segEdge[j + 1] else eOut
                    man.add(Maneuver(Turn.ROUNDABOUT, cum[i], i, g.edgeName(leaveEdge), 0.0, maxOf(1, exits)))
                    i = j + 1; continue
                }
                val bIn = bearingInto(lats, lons, i); val bOut = bearingOutOf(lats, lons, i)
                val ang = Geo.turnAngle(bIn, bOut)
                val options = router.outgoingOptions(node, eIn, profile)
                val nameIn = g.edgeName(eIn); val nameOut = g.edgeName(eOut)
                val t = classify(ang, options, nameIn, nameOut, g, node, eIn, eOut, bIn, profile)
                if (t != null) man.add(Maneuver(t, cum[i], i, nameOut, ang))
                i++
            }
            val last = lats.size - 1
            man.add(Maneuver(Turn.ARRIVE, cum[last], last, "", 0.0))
            return Route(lats, lons, mergeClusters(man, lats, lons), r.timeSeconds, false)
        }

        /**
         * Junctions modelled as several nodes a few meters apart (divided roads, slip lanes, parking aisles)
         * produce chains of cues. Collapse any chain with gaps under 30 m into one cue judged by the overall
         * change of heading between entering the first node and leaving the last. A near-straight overall
         * result (a jog across a divided road) is dropped entirely: on a paper map that is "straight on".
         */
        internal fun mergeClusters(man: List<Maneuver>, lats: DoubleArray, lons: DoubleArray): List<Maneuver> {
            val out = ArrayList<Maneuver>()
            var i = 0
            while (i < man.size) {
                val m = man[i]
                if (m.turn == Turn.DEPART || m.turn == Turn.ARRIVE || m.turn == Turn.ROUNDABOUT) { out.add(m); i++; continue }
                var j = i
                while (j + 1 < man.size && man[j + 1].turn != Turn.ARRIVE && man[j + 1].turn != Turn.ROUNDABOUT && man[j + 1].at - man[j].at < 30.0) j++
                if (j == i) { out.add(m); i++; continue }
                val first = man[i]; val lastM = man[j]
                val bIn = bearingInto(lats, lons, first.vertex); val bOut = bearingOutOf(lats, lons, lastM.vertex)
                val ang = Geo.turnAngle(bIn, bOut); val a = abs(ang)
                val t = when {
                    a >= 150 -> Turn.UTURN
                    a >= 110 -> if (ang < 0) Turn.SHARP_LEFT else Turn.SHARP_RIGHT
                    a >= 40 -> if (ang < 0) Turn.LEFT else Turn.RIGHT
                    a >= 20 -> if (ang < 0) Turn.SLIGHT_LEFT else Turn.SLIGHT_RIGHT
                    else -> null
                }
                if (t != null) out.add(Maneuver(t, first.at, first.vertex, lastM.name, ang))
                i = j + 1
            }
            return out
        }

        private fun countExits(g: Graph, node: Int, profile: Profile): Int {
            var c = 0
            for (slot in g.adjStart(node) until g.adjEnd(node)) {
                val he = g.adj(slot); val e = he shr 1
                val f = g.edgeFlags(e)
                if (f and GraphFormat.ROUNDABOUT != 0) continue
                val dir = if (g.edgeFrom(e) == node) 0 else 1
                if (dir == 1 && f and GraphFormat.ONEWAY != 0) continue
                if (Speeds.speed(profile, f) <= 0.0) continue
                c++
            }
            return c
        }

        /** Decide whether the transition at a junction deserves a cue. */
        private fun classify(ang: Double, options: Int, nameIn: String, nameOut: String, g: Graph, node: Int, eIn: Int, eOut: Int, bIn: Double, profile: Profile): Turn? {
            val a = abs(ang)
            if (options == 0) return if (a >= 150.0) Turn.UTURN else null   // the road simply continues (a bend); only a switchback is worth a word
            // the other ways out of this junction: how far each deviates from straight ahead, and from our exit
            val bOut = (bIn + ang + 360.0) % 360.0
            val ourCls = g.edgeFlags(eOut) and GraphFormat.CLASS_MASK
            var minDevStraight = 999.0      // the straightest alternative, measured from our arriving heading
            var minDiffFromOurs = 999.0; var otherSide = 0.0
            for (slot in g.adjStart(node) until g.adjEnd(node)) {
                val he = g.adj(slot); val e = he shr 1
                if (e == eIn || e == eOut) continue
                val f = g.edgeFlags(e)
                val dir = if (g.edgeFrom(e) == node) 0 else 1
                if (dir == 1 && f and GraphFormat.ONEWAY != 0) continue
                if (Speeds.speed(profile, f) <= 0.0) continue
                val cls = f and GraphFormat.CLASS_MASK
                val k = if (dir == 0) 1 else g.edgePointCount(e) - 2
                val b = Geo.bearing(g.nodeLat(node), g.nodeLon(node), g.edgePointLat(e, k), g.edgePointLon(e, k))
                // driveways and tracks off a real road don't make it a junction worth a cue
                val minor = cls >= 13 && ourCls <= 11
                if (!minor) minDevStraight = minOf(minDevStraight, abs(Geo.turnAngle(bIn, b)))
                val d = Geo.turnAngle(bOut, b)
                if (abs(d) < minDiffFromOurs) { minDiffFromOurs = abs(d); otherSide = d }
            }
            val sameRoad = nameOut == nameIn && nameIn.isNotEmpty()
            if (sameRoad) {
                // Staying on the same road. A bend is not a turn, even a 90° one, unless some other road
                // continues straighter than ours and could be mistaken for it.
                val straighterLeaves = minDevStraight < a - 25.0
                if (!straighterLeaves) return if (a >= 150.0) Turn.UTURN else null
            }
            if (a >= 150.0) return Turn.UTURN
            if (a >= 110.0) return if (ang < 0) Turn.SHARP_LEFT else Turn.SHARP_RIGHT
            if (a >= 40.0) return if (ang < 0) Turn.LEFT else Turn.RIGHT
            if (a >= 20.0) return if (ang < 0) Turn.SLIGHT_LEFT else Turn.SLIGHT_RIGHT
            if (sameRoad) return null
            // Nearly straight onto a differently named road: a fork if another branch is close to our heading.
            if (minDiffFromOurs < 45.0) return if (otherSide > 0) Turn.KEEP_LEFT else Turn.KEEP_RIGHT
            return null
        }

        /** Bearing of travel arriving at vertex i, using a lookback of ~20 m for stability. */
        internal fun bearingInto(lats: DoubleArray, lons: DoubleArray, i: Int): Double {
            var j = i - 1; var d = 0.0
            while (j > 0 && d < 20.0) { d += Geo.fastDistance(lats[j], lons[j], lats[j + 1], lons[j + 1]); j-- }
            if (d < 20.0 && j == 0) return Geo.bearing(lats[0], lons[0], lats[i], lons[i])
            return Geo.bearing(lats[j + 1], lons[j + 1], lats[i], lons[i])
        }
        internal fun bearingOutOf(lats: DoubleArray, lons: DoubleArray, i: Int): Double {
            var j = i + 1; var d = 0.0
            while (j < lats.size - 1 && d < 20.0) { d += Geo.fastDistance(lats[j - 1], lons[j - 1], lats[j], lons[j]); j++ }
            return Geo.bearing(lats[i], lons[i], lats[j], lons[j])
        }

        /** A GPX track: cues synthesized from the geometry alone. */
        /**
         * A GPX track. With authored cues the roadbook is the author's: cues are snapped to the line and used as
         * given (plus arrival). Without any, cues are synthesised from the geometry.
         */
        fun fromTrack(lats: DoubleArray, lons: DoubleArray, name: String = "Track", cues: List<Cue> = emptyList()): Route {
            require(lats.size >= 2)
            if (cues.isNotEmpty()) {
                val cum = DoubleArray(lats.size); for (i in 1 until lats.size) cum[i] = cum[i - 1] + Geo.fastDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
                val man = ArrayList<Maneuver>()
                man.add(Maneuver(Turn.DEPART, 0.0, 0, name, 0.0))
                val proj = DoubleArray(4)
                // segments bucketed by 0.01° cell so a thousand cues on a hundred-thousand-point track stay cheap
                val cell = 0.01
                val grid = HashMap<Long, IntArrayList>()
                // a segment spanning many cells (a long straight desert road in a sparse file) is not bucketed:
                // it goes on a short list that every cue checks, so a hazard halfway along it is never lost
                val longs = IntArrayList()
                for (i in 0 until lats.size - 1) {
                    val la0 = kotlin.math.floor(kotlin.math.min(lats[i], lats[i + 1]) / cell).toLong(); val la1 = kotlin.math.floor(kotlin.math.max(lats[i], lats[i + 1]) / cell).toLong()
                    val lo0 = kotlin.math.floor(kotlin.math.min(lons[i], lons[i + 1]) / cell).toLong(); val lo1 = kotlin.math.floor(kotlin.math.max(lons[i], lons[i + 1]) / cell).toLong()
                    if ((la1 - la0 + 1) * (lo1 - lo0 + 1) > 400) { longs.add(i); continue }
                    for (a in la0..la1) for (b in lo0..lo1) grid.getOrPut((a shl 32) xor (b and 0xFFFFFFFFL)) { IntArrayList() }.add(i)
                }
                for (c in cues) {
                    // nearest point on the line, as a vertex index (cues further than 150 m from the line are dropped)
                    var best = Double.MAX_VALUE; var bi = -1; var bt = 0.0
                    val ca = kotlin.math.floor(c.lat / cell).toLong(); val co = kotlin.math.floor(c.lon / cell).toLong()
                    for (a in ca - 1..ca + 1) for (b in co - 1..co + 1) {
                        val segs = grid[(a shl 32) xor (b and 0xFFFFFFFFL)] ?: continue
                        for (k in 0 until segs.size) {
                            val i = segs[k]
                            Geo.projectOnSegment(c.lat, c.lon, lats[i], lons[i], lats[i + 1], lons[i + 1], proj)
                            if (proj[1] < best) { best = proj[1]; bi = i; bt = proj[0] }
                        }
                    }
                    for (k in 0 until longs.size) {
                        val i = longs[k]
                        Geo.projectOnSegment(c.lat, c.lon, lats[i], lons[i], lats[i + 1], lons[i + 1], proj)
                        if (proj[1] < best) { best = proj[1]; bi = i; bt = proj[0] }
                    }
                    if (bi < 0 || best > 150.0) continue
                    val vertex = if (bt < 0.5) bi else bi + 1
                    val at = cum[bi] + bt * (cum[bi + 1] - cum[bi])
                    man.add(Maneuver(c.turn, at, vertex, name, 0.0, note = c.note, level = c.level))
                }
                man.sortBy { it.at }
                man.add(Maneuver(Turn.ARRIVE, cum[lats.size - 1], lats.size - 1, "", 0.0))
                return Route(lats, lons, man, 0.0, true)
            }
            val keep = Simplify.douglasPeucker(lats, lons, 6.0)
            val cum = DoubleArray(lats.size); for (i in 1 until lats.size) cum[i] = cum[i - 1] + Geo.fastDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
            val man = ArrayList<Maneuver>()
            man.add(Maneuver(Turn.DEPART, 0.0, 0, name, 0.0))
            var lastAt = -1e9
            for (k in 1 until keep.size - 1) {
                val i = keep[k]
                val bIn = Geo.bearing(lats[keep[k - 1]], lons[keep[k - 1]], lats[i], lons[i])
                val bOut = Geo.bearing(lats[i], lons[i], lats[keep[k + 1]], lons[keep[k + 1]])
                val ang = Geo.turnAngle(bIn, bOut)
                val a = abs(ang)
                val t = when {
                    a >= 150 -> Turn.UTURN
                    a >= 110 -> if (ang < 0) Turn.SHARP_LEFT else Turn.SHARP_RIGHT
                    a >= 45 -> if (ang < 0) Turn.LEFT else Turn.RIGHT
                    else -> null
                } ?: continue
                if (cum[i] - lastAt < 25.0) continue // merge jitter
                man.add(Maneuver(t, cum[i], i, name, ang)); lastAt = cum[i]
            }
            man.add(Maneuver(Turn.ARRIVE, cum[lats.size - 1], lats.size - 1, "", 0.0))
            return Route(lats, lons, man, 0.0, true)
        }
    }
}

object Simplify {
    /** Returns indices of retained vertices (always includes first and last). Tolerance in meters. */
    fun douglasPeucker(lats: DoubleArray, lons: DoubleArray, tol: Double): IntArray {
        val n = lats.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = IntList(); stack.add(0); stack.add(n - 1)
        val proj = DoubleArray(4)
        while (stack.size > 0) {
            val b = stack.removeLast(); val a = stack.removeLast()
            var maxD = -1.0; var idx = -1
            for (i in a + 1 until b) {
                Geo.projectOnSegment(lats[i], lons[i], lats[a], lons[a], lats[b], lons[b], proj)
                if (proj[1] > maxD) { maxD = proj[1]; idx = i }
            }
            if (idx >= 0 && maxD > tol) { keep[idx] = true; stack.add(a); stack.add(idx); stack.add(idx); stack.add(b) }
        }
        val out = IntList(); for (i in 0 until n) if (keep[i]) out.add(i)
        return out.toArray()
    }
}

/** A tiny growable int array for indexes. */
class IntArrayList { private var a = IntArray(4); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i] }
