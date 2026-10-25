package fold.core

/**
 * Minimal, dependency-free GPX reader and writer.
 *
 * Reading: track points (trkpt) become the line to follow; route points (rtept) are kept separately
 * so a planned route can be re-routed through them with the maps on this phone.
 * Writing: a Fold route goes out as both, so any GPX viewer shows the line and Fold can rebuild it.
 */
object Gpx {
    class Track(val name: String, val lats: DoubleArray, val lons: DoubleArray, val rteLats: DoubleArray, val rteLons: DoubleArray, val mode: String?, val cues: List<Cue> = emptyList()) {
        val hasTrack get() = lats.size >= 2
        val hasRoute get() = rteLats.size >= 2
    }

    fun parse(text: String): Track? {
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        val rl = ArrayList<Double>(); val ro = ArrayList<Double>()
        var name = ""; var mode: String? = null
        val cues = ArrayList<Cue>()
        var i = 0
        val n = text.length
        var lastLat = Double.NaN; var lastLon = Double.NaN
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            val gt = text.indexOf('>', lt)
            if (gt < 0) break
            val tag = text.substring(lt + 1, gt)
            i = gt + 1
            if (tag.startsWith("trkpt")) {
                val lat = attr(tag, "lat"); val lon = attr(tag, "lon")
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    if (lat != lastLat || lon != lastLon) { lats.add(lat); lons.add(lon); lastLat = lat; lastLon = lon }
                }
            } else if (tag.startsWith("wpt")) {
                val lat = attr(tag, "lat"); val lon = attr(tag, "lon")
                val close = text.indexOf("</wpt>", i)
                if (lat != null && lon != null && close > 0) {
                    val body = text.substring(i, close)
                    fun inner(t: String): String { val a = body.indexOf("<$t>"); if (a < 0) return ""; val b = body.indexOf("</$t>", a); return if (b < 0) "" else unesc(body.substring(a + t.length + 2, b).trim()) }
                    Cue.fromWaypoint(lat, lon, inner("type"), inner("name"), inner("desc"))?.let { cues.add(it) }
                    i = close + 6
                }
            } else if (tag.startsWith("rtept")) {
                val lat = attr(tag, "lat"); val lon = attr(tag, "lon")
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) { rl.add(lat); ro.add(lon) }
            } else if (name.isEmpty() && tag == "name") {
                val close = text.indexOf("</name>", i)
                if (close > 0) { name = unesc(text.substring(i, close).trim()); i = close + 7 }
            } else if (tag == "fold:mode") {
                val close = text.indexOf("</fold:mode>", i)
                if (close > 0) { mode = text.substring(i, close).trim(); i = close + 12 }
            }
        }
        if (lats.size < 2 && rl.size < 2) return null
        return Track(name.ifEmpty { "Track" }, lats.toDoubleArray(), lons.toDoubleArray(), rl.toDoubleArray(), ro.toDoubleArray(), mode, cues)
    }

    /**
     * Writes a planned route: its points (start, vias, destination) as a GPX route, and the full
     * geometry as a track, thinned to [maxPoints] so the file stays small.
     */
    fun cueXml(c: Cue): String = "  <wpt lat=\"" + c.lat + "\" lon=\"" + c.lon + "\"><name>" + esc(c.note.ifEmpty { c.turn.spoken }) + "</name>" + (if (c.note.isNotEmpty()) "<desc>" + esc(c.note) + "</desc>" else "") + "<type>" + Cue.typeString(c.turn, c.level) + "</type></wpt>\n"

    /** A recorded or edited track with its authored cues. */
    fun writeTrack(name: String, lats: DoubleArray, lons: DoubleArray, times: LongArray?, cues: List<Cue>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Roadbook\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(esc(name)).append("</name></metadata>\n")
        for (c in cues) sb.append(cueXml(c))
        sb.append("  <trk><name>").append(esc(name)).append("</name><trkseg>\n")
        for (i in lats.indices) { sb.append("    <trkpt lat=\"").append(lats[i]).append("\" lon=\"").append(lons[i]).append("\">"); if (times != null) sb.append("<time>").append(java.time.Instant.ofEpochMilli(times[i])).append("</time>"); sb.append("</trkpt>\n") }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    fun writeRoute(name: String, mode: String, ptLats: DoubleArray, ptLons: DoubleArray, ptNames: List<String>, lineLats: DoubleArray, lineLons: DoubleArray, maxPoints: Int = 4000): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Fold\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:fold=\"https://fold.app/gpx\">\n")
        sb.append("  <metadata><name>").append(esc(name)).append("</name><extensions><fold:mode>").append(mode).append("</fold:mode></extensions></metadata>\n")
        for (k in ptLats.indices) {
            sb.append("  <wpt lat=\"").append(ptLats[k]).append("\" lon=\"").append(ptLons[k]).append("\"><name>").append(esc(ptNames.getOrElse(k) { if (k == 0) "Start" else if (k == ptLats.size - 1) "End" else "Via $k" })).append("</name></wpt>\n")
        }
        sb.append("  <rte><name>").append(esc(name)).append("</name>\n")
        for (k in ptLats.indices) sb.append("    <rtept lat=\"").append(ptLats[k]).append("\" lon=\"").append(ptLons[k]).append("\"/>\n")
        sb.append("  </rte>\n")
        sb.append("  <trk><name>").append(esc(name)).append("</name><trkseg>\n")
        val keep = if (lineLats.size <= maxPoints) IntArray(lineLats.size) { it } else Simplify.douglasPeucker(lineLats, lineLons, 8.0).let { if (it.size <= maxPoints) it else IntArray(maxPoints) { (it.toLong() * (lineLats.size - 1) / (maxPoints - 1)).toInt() } }
        for (i in keep) sb.append("    <trkpt lat=\"").append(lineLats[i]).append("\" lon=\"").append(lineLons[i]).append("\"/>\n")
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun attr(tag: String, key: String): Double? {
        val k = tag.indexOf("$key=")
        if (k < 0) return null
        val q = tag[k + key.length + 1]
        if (q != '"' && q != '\'') return null
        val start = k + key.length + 2
        val end = tag.indexOf(q, start)
        if (end < 0) return null
        return tag.substring(start, end).trim().toDoubleOrNull()
    }
}
