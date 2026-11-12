package fold.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

/**
 * Roll charts on the phone: the planner's chart engine (web/site/chart.js) ported line for line, so a chart read on a
 * handlebar shows the same mileage and the same tulips the planner printed. The planner's fixtures check it row for
 * row (ChartFixturesTest). Only the parts a reader needs are here: settings, the rally fields of each cue, the rows,
 * the tulip's shapes and the trip meter. Laying rows out on paper stays in the planner.
 */

/** A small JSON reader for the rally fields: objects, arrays, strings, numbers, booleans, null. */
object MiniJson {
    class Bad : Exception("bad json")
    fun parse(s: String): Any? { val p = P(s); p.ws(); val v = p.value(); p.ws(); if (p.i != s.length) throw Bad(); return v }
    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw Bad()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Bad()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw Bad(); i += w.length; return v }
        fun num(): Double { val a = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++; return s.substring(a, i).toDoubleOrNull() ?: throw Bad() }
        fun str(): String {
            i++; val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad()
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> { if (i >= s.length) throw Bad(); when (val e = s[i++]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'u' -> { if (i + 4 > s.length) throw Bad(); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }; else -> b.append(e) } }
                    else -> b.append(c)
                }
            }
        }
        fun arr(): List<Any?> { i++; val out = ArrayList<Any?>(); ws(); if (i < s.length && s[i] == ']') { i++; return out }; while (true) { ws(); out.add(value()); ws(); if (i >= s.length) throw Bad(); if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; return out }; throw Bad() } }
        fun obj(): Map<String, Any?> { i++; val out = LinkedHashMap<String, Any?>(); ws(); if (i < s.length && s[i] == '}') { i++; return out }; while (true) { ws(); if (i >= s.length || s[i] != '"') throw Bad(); val k = str(); ws(); if (i >= s.length || s[i] != ':') throw Bad(); i++; ws(); out[k] = value(); ws(); if (i >= s.length) throw Bad(); if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; return out }; throw Bad() } }
    }
}

/** How a chart is set: layout A (stage-rally scroll, miles) or B (rally-raid roadbook, km), and its numbers. */
class ChartSettings(val layout: String = "A", units: String? = null, decimal: String? = null, scale: Double = 1.0, val decline: Boolean = true, val title: String = "") {
    val units: String = if (units == "mi" || units == "km") units else if (layout == "B") "km" else "mi"
    val decimal: String = if (decimal == "point" || decimal == "comma") decimal else if (layout == "B") "comma" else "point"
    val scale: Double = if (scale.isFinite() && scale > 0.5 && scale < 2) scale else 1.0
    val unitMeters get() = if (units == "km") 1000.0 else 1609.344
    companion object {
        /** From the GPX's <chart> text (Gpx.Track.chart); null or bad text gives layout A's defaults. */
        fun parse(json: String?): ChartSettings {
            val o = try { json?.let { MiniJson.parse(it) } as? Map<*, *> } catch (_: Throwable) { null } ?: return ChartSettings()
            val layout = if (o["layout"] == "B") "B" else "A"
            return ChartSettings(layout, o["units"] as? String, o["decimal"] as? String, (o["scale"] as? Double) ?: 1.0, o["decline"] != false, (o["title"] as? String)?.let { Chart.cleanText(it, 80) } ?: "")
        }
    }
}

class ChartBranch(val deg: Int, val kind: String)
class ChartSymbol(val id: String, val slot: String)
/** One cue's rally fields, as the planner stores them; every field optional, cleaned as the planner cleans them. */
class Rally(
    val at: Double? = null, val exit: Int? = null, val branches: List<ChartBranch>? = null, val path: String? = null, val caution: Int? = null,
    val info: String? = null, val symbols: List<ChartSymbol> = emptyList(), val speed: Int = 0, val sectionName: String? = null, val sectionKind: String = "transit",
    val reset: Boolean = false, val cap: Int? = null, val capShow: Boolean? = null, val zone: String = "", val wpType: String = "", val wpN: Int = 0, val edited: Boolean = false,
) {
    val startsSection get() = sectionName != null
    companion object {
        private val PATHS = setOf("road", "track", "offtrack"); private val KINDS = setOf("road", "track", "faint"); private val SLOTS = setOf("lb", "rb", "la", "ra")
        private val WP = setOf("V", "E", "M", "N", "S", "C", "P")
        private fun num(v: Any?, lo: Double, hi: Double): Double? { val d = (v as? Double) ?: (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null; return if (d.isFinite()) min(hi, max(lo, d)) else null }
        private fun int(v: Any?, lo: Double, hi: Double): Int? = num(v, lo, hi)?.let { Chart.jsRound(it).toInt() }
        /** From a cue's <rally> text (Cue.rally); null for none or bad text, exactly as the planner's cleanRally reads it. */
        fun parse(json: String?): Rally? {
            val o = try { json?.let { MiniJson.parse(it) } as? Map<*, *> } catch (_: Throwable) { null } ?: return null
            val branches = (o["branches"] as? List<*>)?.take(8)?.mapNotNull { b -> (b as? Map<*, *>)?.let { m -> int(m["deg"], -180.0, 180.0)?.let { ChartBranch(it, (m["kind"] as? String)?.takeIf { k -> k in KINDS } ?: "track") } } }
            val symbols = (o["symbols"] as? List<*>)?.take(6)?.mapNotNull { x -> (x as? Map<*, *>)?.let { m -> (m["id"] as? String)?.takeIf { Regex("^[a-z0-9_]{1,32}$").matches(it) }?.let { ChartSymbol(it, (m["slot"] as? String)?.takeIf { s -> s in SLOTS } ?: "ra") } } } ?: emptyList()
            val sec = o["section"] as? Map<*, *>
            val wp = (o["wp"] as? Map<*, *>)?.takeIf { (it["type"] as? String) in WP }
            val caution = int(o["caution"], 0.0, 3.0)?.takeIf { it > 0 }
            return Rally(
                at = num(o["at"], 0.0, 1e8)?.let { Chart.jsRound(it * 10) / 10.0 }, exit = int(o["exit"], -180.0, 180.0),
                branches = if (o["branches"] is List<*>) branches else null, path = (o["path"] as? String)?.takeIf { it in PATHS }, caution = caution,
                info = (o["info"] as? String)?.let { Chart.cleanText(it, 120) }?.takeIf { it.isNotEmpty() }, symbols = symbols,
                speed = int(o["speed"], 1.0, 300.0) ?: 0, sectionName = sec?.let { Chart.cleanText((it["name"] as? String) ?: "", 40) }, sectionKind = if (sec?.get("kind") == "stage") "stage" else "transit",
                reset = o["reset"] == true, cap = int(o["cap"], 0.0, 359.0), capShow = o["capShow"] as? Boolean, zone = (o["zone"] as? String)?.takeIf { it == "DZ" || it == "FZ" } ?: "",
                wpType = (wp?.get("type") as? String) ?: "", wpN = wp?.let { int(it["n"], 1.0, 999.0) } ?: 0, edited = o["edited"] == true,
            )
        }
    }
}

/** What the chart needs to know about a cue: where, which way, its words, its rally fields, the router's angle. */
class ChartCue(val lat: Double, val lon: Double, val turn: Turn, val level: Int, val text: String, val rally: Rally?, val angle: Double? = null, val roundaboutExit: Int = 0, val auto: Boolean = false, val id: Int = 0)

class Tulip(val exit: Int, val branches: List<ChartBranch>, val path: String, val roundabout: Int)

/** One row of the chart. Mileage figures are in hundredths of the chart's unit, rounded once, as the planner rounds them. */
class ChartRow(
    val kind: Kind, val n: Int, val at: Double, val overall: Int, val delta: Int, val decline: Int, val partialM: Double, val section: Int,
    val info: String, val caution: Int = 0, val tulip: Tulip? = null, val symbols: List<ChartSymbol> = emptyList(), val speed: Int = 0,
    val cap: Int = 0, val capShow: Boolean = false, val zone: String = "", val inZone: Boolean = false, val wpType: String = "", val wpN: Int = 0,
    val zeroOdo: Boolean = false, val resetHere: Boolean = false, val shortPartial: Boolean = false, val auto: Boolean = false, val cueId: Int = -1,
    /** banners: the section's name, kind and length (hundredths) */
    val sectionName: String = "", val sectionKind: String = "", val lengthH: Int = 0,
) {
    enum class Kind { BANNER, START, CUE, FINISH }
}

class ChartBuild(val rows: List<ChartRow>, val totalMeters: Double, val totalH: Int, val sections: Int, val settings: ChartSettings)

object Chart {
    // ---- numbers as the planner's JavaScript computes them ----------------------------------------------------------
    /** JavaScript's Math.round: halves go up (toward +∞), unlike Kotlin's round(), which goes to even */
    fun jsRound(x: Double): Double = floor(x + 0.5)
    fun cleanText(s: String, n: Int): String = s.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.substring(0, n) else it }
    private const val R_EARTH = 6371000.0
    private const val RAD = kotlin.math.PI / 180
    /** the planner's distance: equirectangular, mean latitude */
    fun dist(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double { val dLat = (lat2 - lat1) * RAD; val dLon = (lon2 - lon1) * RAD; return R_EARTH * hypot(dLat, dLon * cos((lat1 + lat2) / 2 * RAD)) }
    fun cumulative(lats: DoubleArray, lons: DoubleArray): DoubleArray { val c = DoubleArray(lats.size); for (i in 1 until lats.size) c[i] = c[i - 1] + dist(lons[i - 1], lats[i - 1], lons[i], lats[i]); return c }
    /** The chart reader's inset: the line around the rider in metres, turned so the way ahead is up, the line's point
     *  at [along] at the origin; the rider (where the GPS says, so off the line shows) and the chart's rows within reach. */
    class Sketch(val line: DoubleArray, val riderX: Double, val riderY: Double, val marks: DoubleArray)
    fun sketch(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, lat: Double, lon: Double, along: Double, rowsAt: DoubleArray, back: Double = 250.0, ahead: Double = 750.0): Sketch? {
        val n = lats.size; if (n < 2 || cum.size != n) return null
        val end = cum[n - 1]; val a = along.coerceIn(0.0, end)
        val o = pointAt(lats, lons, cum, a)
        val brg = if (a + 60 <= end) pointAt(lats, lons, cum, a + 60).let { bearing(o[0], o[1], it[0], it[1]) } else pointAt(lats, lons, cum, max(0.0, a - 60)).let { bearing(it[0], it[1], o[0], o[1]) }
        val k = cos(o[1] * RAD); val sn = sin(brg * RAD); val cs = cos(brg * RAD)
        val out = ArrayList<Double>()
        fun put(lo: Double, la: Double, to: ArrayList<Double>) { val e = (lo - o[0]) * RAD * R_EARTH * k; val nn = (la - o[1]) * RAD * R_EARTH; to.add(e * cs - nn * sn); to.add(-(e * sn + nn * cs)) }
        val from = max(0.0, a - back); val to = min(end, a + ahead)
        pointAt(lats, lons, cum, from).let { put(it[0], it[1], out) }
        for (i in 0 until n) if (cum[i] > from && cum[i] < to) put(lons[i], lats[i], out)
        pointAt(lats, lons, cum, to).let { put(it[0], it[1], out) }
        val r = ArrayList<Double>(); put(lon, lat, r)
        val marks = ArrayList<Double>(); for (m in rowsAt) if (m >= from && m <= to) pointAt(lats, lons, cum, m).let { put(it[0], it[1], marks) }
        return Sketch(out.toDoubleArray(), r[0], r[1], marks.toDoubleArray())
    }
    fun bearing(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val y = sin((lon2 - lon1) * RAD) * cos(lat2 * RAD)
        val x = cos(lat1 * RAD) * sin(lat2 * RAD) - sin(lat1 * RAD) * cos(lat2 * RAD) * cos((lon2 - lon1) * RAD)
        return ((atan2(y, x) / RAD) + 360) % 360
    }
    /** signed change of heading from b1 to b2, -180..180, right positive */
    fun turnAngle(b1: Double, b2: Double): Double { var d = (((b2 - b1) % 360 + 540) % 360) - 180; if (d == -180.0) d = 180.0; return d }
    fun hundredths(m: Double, st: ChartSettings): Int = jsRound(m * st.scale / st.unitMeters * 100).toInt()
    /** "12.34", or "12,34" with the decimal comma */
    fun fmt(h: Int, st: ChartSettings): String { val a = abs(h); val s = "${a / 100}${if (st.decimal == "comma") "," else "."}${(a % 100).toString().padStart(2, '0')}"; return if (h < 0) "-$s" else s }

    // ---- along the line ---------------------------------------------------------------------------------------------
    /** a point at [at] metres along the line, as (lon, lat) */
    fun pointAt(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, at: Double): DoubleArray {
        val n = lats.size
        if (at <= 0) return doubleArrayOf(lons[0], lats[0]); if (at >= cum[n - 1]) return doubleArrayOf(lons[n - 1], lats[n - 1])
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (cum[m] <= at) lo = m else hi = m }
        val seg = cum[hi] - cum[lo]; val t = if (seg > 0) (at - cum[lo]) / seg else 0.0
        return doubleArrayOf(lons[lo] + (lons[hi] - lons[lo]) * t, lats[lo] + (lats[hi] - lats[lo]) * t)
    }
    class Headings(val arr: Double, val dep: Double, val exit: Int)
    /** arriving from 20 m back, leaving to 20 m ahead; a roundabout judged from 50–90 m after it, past the ring */
    fun headings(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, at: Double, roundabout: Boolean): Headings {
        val total = cum[cum.size - 1]; val p = pointAt(lats, lons, cum, at)
        val back = pointAt(lats, lons, cum, max(0.0, at - 20)); val ahead = pointAt(lats, lons, cum, min(total, at + 20))
        val arr = if (at > 1) bearing(back[0], back[1], p[0], p[1]) else bearing(p[0], p[1], ahead[0], ahead[1])
        var dep = if (at < total - 1) bearing(p[0], p[1], ahead[0], ahead[1]) else arr
        if (roundabout && at + 60 < total) { val a = pointAt(lats, lons, cum, at + 50); val b = pointAt(lats, lons, cum, min(total, at + 90)); dep = bearing(a[0], a[1], b[0], b[1]) }
        return Headings(arr, dep, jsRound(turnAngle(arr, dep)).toInt())
    }
    /** The nearest point on the line to (lat, lon): its distance along the line and how far off it is. */
    fun nearest(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, lat: Double, lon: Double): DoubleArray {
        val k = cos(lat * RAD) * 111320.0
        var best = Double.MAX_VALUE; var bestAt = 0.0
        for (i in 1 until lats.size) {
            val ax = (lons[i - 1] - lon) * k; val ay = (lats[i - 1] - lat) * 111320.0; val bx = (lons[i] - lon) * k; val by = (lats[i] - lat) * 111320.0
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) max(0.0, min(1.0, -(ax * dx + ay * dy) / l2)) else 0.0
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) { best = d; bestAt = cum[i - 1] + t * (cum[i] - cum[i - 1]) }
        }
        return doubleArrayOf(bestAt, best)
    }
    /** Every pass of the line within [radius] m of a point, one per pass (200 m or more apart along the line). */
    fun passes(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, lat: Double, lon: Double, radius: Double): List<DoubleArray> {
        val k = cos(lat * RAD) * 111320.0; val hits = ArrayList<DoubleArray>()
        for (i in 1 until lats.size) {
            val ax = (lons[i - 1] - lon) * k; val ay = (lats[i - 1] - lat) * 111320.0; val bx = (lons[i] - lon) * k; val by = (lats[i] - lat) * 111320.0
            if (min(ax, bx) > radius || max(ax, bx) < -radius || min(ay, by) > radius || max(ay, by) < -radius) continue
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) max(0.0, min(1.0, -(ax * dx + ay * dy) / l2)) else 0.0
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d <= radius) hits.add(doubleArrayOf(cum[i - 1] + t * (cum[i] - cum[i - 1]), d))
        }
        hits.sortBy { it[0] }
        val out = ArrayList<DoubleArray>()
        for (h in hits) { val last = out.lastOrNull(); if (last != null && h[0] - last[0] < 200) { if (h[1] < last[1]) out[out.size - 1] = h } else out.add(h) }
        return out
    }
    /** Where a cue sits along the line: its nearest point, unless the line passes it twice and its stored distance picks the pass. */
    fun placeAt(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, lat: Double, lon: Double, stored: Double?): Double {
        val near = nearest(lats, lons, cum, lat, lon)[0]
        if (stored == null) return near
        val ps = passes(lats, lons, cum, lat, lon, 30.0)
        if (ps.size < 2) return near
        var best = ps[0]; for (h in ps) if (abs(h[0] - stored) < abs(best[0] - stored)) best = h
        return best[0]
    }

    // ---- rows ---------------------------------------------------------------------------------------------------------
    private class Placed(val c: ChartCue, val at: Double)
    private class Start(val at: Double, val name: String, val kind: String, val cue: Placed?)

    /**
     * The chart's rows, as the planner's buildRows makes them: sections restart the mileage, an odometer reset restarts
     * it without a new section, START and FINISH rows where no cue sits within 20 m, a loop's cues in ride order.
     */
    fun build(lats: DoubleArray, lons: DoubleArray, cues: List<ChartCue>, st: ChartSettings): ChartBuild {
        val cum = cumulative(lats, lons); val total = cum[cum.size - 1]
        val sorted = cues.map { Placed(it, placeAt(lats, lons, cum, it.lat, it.lon, it.rally?.at)) }.sortedBy { it.at }
        val starts = ArrayList<Start>(); starts.add(Start(0.0, st.title.ifEmpty { "Route" }, "transit", null))
        for (p in sorted) { val r = p.c.rally ?: continue; if (!r.startsSection) continue
            if (p.at < 20 && starts[0].cue == null) { starts[0] = Start(p.at, r.sectionName!!.ifEmpty { st.title.ifEmpty { "Section 1" } }, r.sectionKind, p); continue }
            starts.add(Start(p.at, r.sectionName!!.ifEmpty { "Section ${starts.size + 1}" }, r.sectionKind, p))
        }
        val explicit = starts.any { it.cue != null }
        val rows = ArrayList<ChartRow>()
        var n = 0; var inZone = false
        for ((si, sec) in starts.withIndex()) {
            val end = if (si + 1 < starts.size) starts[si + 1].at else total
            val next = starts.getOrNull(si + 1)?.cue
            val mine = sorted.filter { it !== sec.cue && it.at >= sec.at - 0.01 && (if (si + 1 < starts.size) it.at < end - 0.01 && it !== next else it.at <= end + 0.01) }
            if (explicit) rows.add(ChartRow(ChartRow.Kind.BANNER, 0, sec.at, 0, 0, 0, 0.0, si + 1, "", sectionName = sec.name, sectionKind = sec.kind, lengthH = hundredths(end - sec.at, st)))
            var origin = sec.at; var prevH = 0; var prevAt = sec.at; var endH = hundredths(end - origin, st)
            fun push(make: (overall: Int, delta: Int, decline: Int, partial: Double, n: Int) -> ChartRow, at: Double): ChartRow {
                val overall = hundredths(at - origin, st)
                val row = make(overall, overall - prevH, endH - overall, at - prevAt, ++n)
                prevH = overall; prevAt = at; rows.add(row); return row
            }
            fun cueRow(p: Placed, zero: Boolean, overall: Int, delta: Int, decline: Int, partial: Double, rn: Int, resetHere: Boolean = false): ChartRow {
                val c = p.c; val r = c.rally
                val isRb = c.turn == Turn.ROUNDABOUT
                val h = headings(lats, lons, cum, p.at, isRb)
                val exit0 = if (c.angle != null && c.angle.isFinite() && !isRb && c.turn != Turn.NOTE && c.turn != Turn.DANGER) jsRound(c.angle).toInt() else h.exit
                val path = r?.path ?: "track"
                val caution = r?.caution ?: if (c.turn == Turn.DANGER) (if (c.level > 0) c.level else 2) else 0
                var info = cleanText(r?.info ?: c.text, 120).uppercase()
                if (zero && st.layout == "A") info = if (info.isNotEmpty()) "ZERO ODO · $info" else "ZERO ODO"
                if (r?.zone == "DZ") inZone = true
                val zoned = inZone || r?.zone == "FZ"
                if (r?.zone == "FZ") inZone = false
                return ChartRow(ChartRow.Kind.CUE, rn, p.at, overall, delta, decline, partial, si + 1, info, caution,
                    Tulip(r?.exit ?: exit0, r?.branches ?: emptyList(), path, if (isRb) max(1, c.roundaboutExit) else 0),
                    r?.symbols ?: emptyList(), r?.speed ?: 0, r?.cap ?: (jsRound(h.dep).toInt() % 360), r?.capShow ?: (path == "offtrack"),
                    r?.zone ?: "", zoned, r?.wpType ?: "", r?.wpN ?: 0, zero, resetHere, auto = c.auto && r?.edited != true, cueId = c.id)
            }
            val first = mine.firstOrNull()
            if (sec.cue != null) push({ o, d, dc, pa, rn -> cueRow(sec.cue, true, o, d, dc, pa, rn) }, sec.cue.at)
            else if (first == null || first.at - sec.at > 20) push({ o, d, dc, pa, rn -> ChartRow(ChartRow.Kind.START, rn, sec.at, o, d, dc, pa, si + 1, (if (si == 0) "START" else "START " + cleanText(sec.name, 30).uppercase()) + (if (st.layout == "A") " · ZERO ODO" else ""), zeroOdo = true) }, sec.at)
            for (p in mine) {
                if (p.c.rally?.reset == true) {
                    // the row where the odometer is reset reads 0.00; its delta is still the way from the row before
                    val leg = hundredths(p.at - prevAt, st)
                    origin = p.at; endH = hundredths(end - origin, st); prevH = 0
                    push({ o, _, dc, pa, rn -> cueRow(p, false, o, leg, dc, pa, rn, resetHere = true) }, p.at)
                } else push({ o, d, dc, pa, rn -> cueRow(p, false, o, d, dc, pa, rn) }, p.at)
            }
            val last = rows.lastOrNull()
            if (last == null || last.kind == ChartRow.Kind.BANNER || end - last.at > 20)
                push({ o, d, dc, pa, rn -> ChartRow(ChartRow.Kind.FINISH, rn, end, o, d, dc, pa, si + 1, if (si + 1 < starts.size) "END " + cleanText(sec.name, 30).uppercase() else "FINISH") }, end)
        }
        // Layout B marks a short partial (under 400 m), as FIA asks
        val out = if (st.layout != "B") rows else rows.map { r ->
            if ((r.kind == ChartRow.Kind.CUE || r.kind == ChartRow.Kind.FINISH) && r.delta > 0 && r.partialM < 400 && !r.zeroOdo)
                ChartRow(r.kind, r.n, r.at, r.overall, r.delta, r.decline, r.partialM, r.section, r.info, r.caution, r.tulip, r.symbols, r.speed, r.cap, r.capShow, r.zone, r.inZone, r.wpType, r.wpN, r.zeroOdo, r.resetHere, true, r.auto, r.cueId, r.sectionName, r.sectionKind, r.lengthH)
            else r
        }
        return ChartBuild(out, total, hundredths(total, st), starts.size, st)
    }

    /** The chart for a followed track: its line and the file's cues with their rally fields. */
    fun forTrack(lats: DoubleArray, lons: DoubleArray, cues: List<Cue>, chartJson: String?): ChartBuild =
        build(lats, lons, cues.mapIndexed { i, c -> ChartCue(c.lat, c.lon, c.turn, c.level, c.note, Rally.parse(c.rally), id = i) }, ChartSettings.parse(chartJson))

    /** Does this file carry a chart? (any cue with rally fields, or chart settings) */
    fun hasChart(cues: List<Cue>, chartJson: String?): Boolean = chartJson != null || cues.any { it.rally != null }

    // ---- the tulip's shapes ---------------------------------------------------------------------------------------------
    /** What to draw, in a square of side s with its top left at (0, 0): the app picks colours by [role] ("path" or "ink"). */
    sealed class Shape { abstract val role: String }
    class Line(val pts: List<DoubleArray>, val width: Double, override val role: String, val dash: DoubleArray?) : Shape()
    /** M, L, C and Z only: [ops] holds each op as its letter code (0 move, 1 line, 2 cubic, 3 close) followed by its numbers */
    class PathShape(val ops: List<DoubleArray>, val width: Double, override val role: String, val fill: Boolean, val dash: DoubleArray?) : Shape()
    class Circle(val x: Double, val y: Double, val r: Double, val width: Double, override val role: String, val fill: Boolean) : Shape()

    class Widths(val path: Double, val road: Double, val track: Double, val dot: Double)
    private fun dirv(deg: Double) = doubleArrayOf(sin(deg * RAD), -cos(deg * RAD))
    private fun reach(p: DoubleArray, v: DoubleArray, box: DoubleArray): Double {
        var t = Double.MAX_VALUE
        if (v[0] > 1e-9) t = min(t, (box[2] - p[0]) / v[0]); if (v[0] < -1e-9) t = min(t, (box[0] - p[0]) / v[0])
        if (v[1] > 1e-9) t = min(t, (box[3] - p[1]) / v[1]); if (v[1] < -1e-9) t = min(t, (box[1] - p[1]) / v[1])
        return max(0.0, t)
    }
    private fun arrowHead(tip: DoubleArray, v: DoubleArray, w: Double): Pair<PathShape, Double> {
        val hl = w * 3.4; val hw = w * 1.7; val base = doubleArrayOf(tip[0] - v[0] * hl, tip[1] - v[1] * hl); val nx = -v[1]; val ny = v[0]
        return PathShape(listOf(doubleArrayOf(0.0, tip[0], tip[1]), doubleArrayOf(1.0, base[0] + nx * hw, base[1] + ny * hw), doubleArrayOf(1.0, base[0] - nx * hw, base[1] - ny * hw), doubleArrayOf(3.0)), 0.0, "path", true, null) to hl
    }
    /**
     * One tulip in a square of side [s]: entered from the bottom at a dot, left along the arrow at the true exit angle,
     * other ways out as thinner lines by road size, a roundabout as a ring, a reversal as a hook. The planner's
     * tulipPrims, shape for shape.
     */
    fun tulip(t: Tulip, s: Double, w: Widths): List<Shape> {
        val out = ArrayList<Shape>(); val pw = w.path
        val jx = s / 2; val jy = s * 0.55; val foot = doubleArrayOf(s / 2, s * 0.92)
        val box = doubleArrayOf(s * 0.05, s * 0.05, s * 0.95, s * 0.95)
        val dash = if (t.path == "offtrack") doubleArrayOf(pw * 2.4, pw * 1.6) else null
        val rr = if (t.roundabout > 0) s * 0.12 else 0.0
        var branches = t.branches
        if (t.roundabout > 0 && branches.isEmpty()) {
            val e = if (t.exit > -170) t.exit.toDouble() else 180.0; val n = t.roundabout; val sweep = 180 - e
            branches = (1 until n).map { j -> ChartBranch(jsRound(180 - sweep * j / n).toInt(), "road") }
        }
        for (b in branches) {
            val v = dirv(b.deg.toDouble()); val start = doubleArrayOf(jx + v[0] * rr, jy + v[1] * rr)
            val len = min(s * 0.34, reach(start, v, box)); val bw = if (b.kind == "road") w.road else w.track
            out.add(Line(listOf(start, doubleArrayOf(start[0] + v[0] * len, start[1] + v[1] * len)), bw, "ink", if (b.kind == "faint") doubleArrayOf(bw * 3, bw * 2.2) else null))
        }
        if (rr > 0) out.add(Circle(jx, jy, rr, w.road, "ink", false))
        out.add(Line(listOf(foot, doubleArrayOf(jx, jy + rr)), pw, "path", dash))
        out.add(Circle(foot[0], foot[1], w.dot / 2, 0.0, "path", true))
        val e = if (rr > 0) max(-165, min(165, t.exit)) else t.exit
        val side = if (e < 0) -1 else 1
        if (abs(e) > 150 && rr == 0.0) {
            val r = s * 0.11; val p1x = jx; val p1y = jy - s * 0.12; val p2x = p1x + side * 2 * r; val k = 4 * r / 3
            val end = doubleArrayOf(p2x, min(p1y + s * 0.32, box[3]))
            val (head, hl) = arrowHead(end, doubleArrayOf(0.0, 1.0), pw)
            out.add(PathShape(listOf(doubleArrayOf(0.0, jx, jy), doubleArrayOf(1.0, p1x, p1y), doubleArrayOf(2.0, p1x, p1y - k, p2x, p1y - k, p2x, p1y), doubleArrayOf(1.0, end[0], end[1] - hl * 0.8)), pw, "path", false, dash))
            out.add(head)
        } else {
            val v = dirv(e.toDouble()); val start = doubleArrayOf(jx + v[0] * rr, jy + v[1] * rr)
            val len = min(s * 0.38, reach(start, v, box)); val tip = doubleArrayOf(start[0] + v[0] * len, start[1] + v[1] * len)
            val (head, hl) = arrowHead(tip, v, pw)
            out.add(Line(listOf(if (rr > 0) start else doubleArrayOf(jx, jy), doubleArrayOf(tip[0] - v[0] * hl * 0.8, tip[1] - v[1] * hl * 0.8)), pw, "path", dash))
            out.add(head)
        }
        return out
    }
    /** A START or FINISH row's picture: the stem rising to a flag. */
    fun flag(finish: Boolean, s: Double, w: Widths): List<Shape> {
        val cx = s / 2
        return listOf(Line(listOf(doubleArrayOf(cx, s * 0.92), doubleArrayOf(cx, s * 0.22)), w.path, "path", null), Circle(cx, s * 0.92, w.dot / 2, 0.0, "path", true)) +
            ChartGlyphs.shapes(if (finish) "ss_finish" else "ss_start", cx + s * 0.02, s * 0.12, s * 0.4)
    }
}

/**
 * The trip meter: the chart's distance as the rider sees it, in hundredths, counted from the last section start or
 * odometer reset, exactly as a paper chart reads.
 *
 * Two sources. ROUTE: distance along the line (the follower's projection), steady, blind to GPS wander and wrong turns.
 * FREE: every metre ridden, from GPS fixes, as a rally odometer counts, wrong turns included. Both take the rider's
 * corrections (± a hundredth, or set to a row) and the chart's scale factor.
 */
class TripMeter(private val chart: ChartBuild) {
    enum class Source { ROUTE, FREE }
    var source = Source.ROUTE
    private val st = chart.settings
    /** metres along the chart (ROUTE: from the follower; FREE: summed from fixes) */
    var chartMeters = 0.0; private set
    /** the rider's correction, in hundredths of the chart's unit */
    var correctionH = 0; private set
    private var lastLat = Double.NaN; private var lastLon = Double.NaN
    /** index into chart.rows of the row the rider is at (the last one passed) */
    var rowIndex = 0; private set
    /** metres along the line where the current mileage counts from (a section start or a reset) */
    private var originAt = 0.0

    /** ROUTE source: the follower's distance along the line. */
    fun onRoute(alongMeters: Double) { if (source == Source.ROUTE) { chartMeters = alongMeters + routeOffset; update() } }
    /** ROUTE source: where the rider said they were, less where the follower put them (set by [setToRow]) */
    private var routeOffset = 0.0
    /**
     * FREE source: a GPS fix. A fix only counts when it is good (accuracy ≤ 25 m) and has moved more than its own
     * uncertainty, so a phone at a standstill does not creep.
     */
    fun onFix(lat: Double, lon: Double, accuracy: Double, speed: Double) {
        if (source != Source.FREE) return
        if (!(accuracy <= 25.0)) return
        if (lastLat.isNaN()) { lastLat = lat; lastLon = lon; return }
        val d = Chart.dist(lastLon, lastLat, lon, lat)
        if (d < max(3.0, accuracy * 0.5) && speed < 1.0) return
        chartMeters += d; lastLat = lat; lastLon = lon; update()
    }
    /** ± one hundredth (the rider's thumb), in the chart's unit */
    fun nudge(hundredths: Int) { correctionH += hundredths; update() }
    /** Set the meter to the row the rider is at: they are standing where that row is. */
    fun setToRow(i: Int) {
        val row = chart.rows.getOrNull(i) ?: return
        if (source == Source.ROUTE) routeOffset += row.at - chartMeters
        correctionH = 0; chartMeters = row.at; lastLat = Double.NaN; update()
    }
    /** Take over another meter's state (the chart was rebuilt under it: a cue edited on the way). */
    fun restoreFrom(o: TripMeter) { source = o.source; chartMeters = o.chartMeters; correctionH = o.correctionH; routeOffset = o.routeOffset; lastLat = o.lastLat; lastLon = o.lastLon; update() }
    fun switchSource(s: Source) { if (s == source) return; source = s; lastLat = Double.NaN; if (s == Source.ROUTE) routeOffset = 0.0; update() }

    /** the meter as shown: hundredths from the current origin, with the scale and the correction */
    val readingH: Int get() = Chart.hundredths(chartMeters - originAt, st) + correctionH
    val reading: String get() = Chart.fmt(readingH, st)
    /** metres along the chart with the correction applied (for scrolling) */
    val effectiveMeters: Double get() = chartMeters + correctionH / 100.0 * st.unitMeters / st.scale

    private fun update() {
        val at = effectiveMeters
        var i = 0
        for ((k, r) in chart.rows.withIndex()) { if (r.kind == ChartRow.Kind.BANNER) continue; if (r.at <= at + 0.5) i = k else break }
        rowIndex = i
        // the origin is the last section start or reset at or before here
        var o = 0.0
        for (k in 0..i) { val r = chart.rows[k]; if (r.kind == ChartRow.Kind.BANNER || r.resetHere) o = r.at }
        if (o != originAt) { originAt = o }
    }
    /** The row the rider should be looking at: the next one ahead once within [leadMeters] of it. */
    fun showRow(leadMeters: Double): Int {
        val at = effectiveMeters
        var k = rowIndex + 1
        while (k < chart.rows.size && chart.rows[k].kind == ChartRow.Kind.BANNER) k++
        return if (k < chart.rows.size && chart.rows[k].at - at <= leadMeters) k else rowIndex
    }
}
