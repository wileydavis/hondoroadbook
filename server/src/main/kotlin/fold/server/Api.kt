package fold.server

import fold.core.Geo
import fold.core.GraphFormat
import fold.core.IntList
import fold.core.MultiRouter
import fold.core.Profile
import fold.core.Route
import fold.core.RouteResult
import fold.core.Turn
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class ApiError(val status: Int, message: String, val extra: Map<String, String> = emptyMap()) : RuntimeException(message)

/**
 * The planner's questions, answered with the phone's code. One search at a time: the core's search
 * state is per pack and not shared between threads, and a planner at human speed never queues deep.
 */
class Api(private val packs: PackSet) {
    private val lock = ReentrantLock(true)

    private fun <T> routing(f: () -> T): T {
        if (!lock.tryLock(30, TimeUnit.SECONDS)) throw ApiError(503, "The router is busy; try again in a moment")
        try { return f() } finally { lock.unlock() }
    }

    private fun profile(q: Map<String, String>): Profile {
        val m = q["mode"]?.uppercase() ?: "FAST"
        return try { Profile.valueOf(m) } catch (_: Throwable) { throw ApiError(400, "unknown mode $m; one of " + Profile.values().joinToString(", ") { it.name }) }
    }

    private fun points(q: Map<String, String>): List<DoubleArray> {
        val raw = q["pts"] ?: throw ApiError(400, "pts=lon,lat;lon,lat;… is required")
        val pts = raw.split(';', '|').filter { it.isNotBlank() }.map { s ->
            val p = s.split(','); if (p.size != 2) throw ApiError(400, "bad point '$s'")
            val lon = p[0].toDoubleOrNull(); val lat = p[1].toDoubleOrNull()
            if (lon == null || lat == null || !lon.isFinite() || !lat.isFinite() || lat < -90 || lat > 90 || lon < -180 || lon > 180) throw ApiError(400, "bad point")
            doubleArrayOf(lat, lon)
        }
        if (pts.size < 2) throw ApiError(400, "at least two points")
        if (pts.size > 60) throw ApiError(400, "at most 60 points")
        // a trip, not a continent: the legs together may not exceed 4,000 km as the crow flies
        var crow = 0.0; for (i in 1 until pts.size) crow += Geo.fastDistance(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        if (crow > 4_000_000.0) throw ApiError(400, "That is more than 4,000 km between the points; plan it in stages")
        return pts
    }

    /** Packs for a set of points: their envelope, padded so a route may swing well outside it. */
    private fun packsFor(pts: List<DoubleArray>): List<Loaded> {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in pts) { minLat = minOf(minLat, p[0]); maxLat = maxOf(maxLat, p[0]); minLon = minOf(minLon, p[1]); maxLon = maxOf(maxLon, p[1]) }
        val padLat = maxOf(0.3, (maxLat - minLat) * 0.2); val padLon = maxOf(0.4, (maxLon - minLon) * 0.2)
        val want = packs.catalog().filter { it.intersects(minLon - padLon, minLat - padLat, maxLon + padLon, maxLat + padLat) }
        if (want.isEmpty()) throw ApiError(404, "No map pack covers this area yet")
        // a pack that only touches the padding may fail to load without stopping the route; one holding a point may not
        val list = ArrayList<Loaded>()
        for (p in want) {
            try { list.add(packs.ensure(p)) } catch (e: Throwable) {
                val holds = pts.any { it[1] in p.bbox[0]..p.bbox[2] && it[0] in p.bbox[1]..p.bbox[3] }
                System.err.println("pack ${p.id} unavailable: ${e.message}")
                if (holds) throw ApiError(503, "The ${p.region} map is not available right now (${e.message})")
            }
        }
        if (list.isEmpty()) throw ApiError(503, "No map pack could be loaded for this area")
        return list
    }

    /** Wakes the service and starts loading the packs under a box; cheap to call on every map move. */
    fun warm(q: Map<String, String>): String {
        val bbox = q["bbox"]?.let { raw ->
            val b = raw.split(',').map { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: throw ApiError(400, "bad bbox") }
            if (b.size != 4 || b[0] > b[2] || b[1] > b[3] || b[2] - b[0] > 12.0 || b[3] - b[1] > 12.0 || b.any { Math.abs(it) > 180 }) throw ApiError(400, "bad bbox")
            b
        } ?: run {
            val lon = finite(q["lon"], 180.0) ?: throw ApiError(400, "bbox or lon/lat required"); val lat = finite(q["lat"], 90.0) ?: throw ApiError(400, "bbox or lon/lat required")
            listOf(lon - 0.5, lat - 0.5, lon + 0.5, lat + 0.5)
        }
        val (ready, pending) = packs.warm(bbox[0], bbox[1], bbox[2], bbox[3])
        val loading = packs.loadingIds()
        return Json.obj("ready" to Json.arr(ready.map { Json.str(it.region) }), "loading" to Json.arr(pending.map { Json.str(it.region) }), "fetching" to Json.arr(pending.filter { it.id in loading }.map { Json.str(it.region) }))
    }

    fun health(): String = Json.obj("ok" to "true", "loaded" to Json.arr(packs.loadedIds().map { Json.str(it) }), "catalog" to packs.catalog().size.toString())

    fun route(q: Map<String, String>): String = routing {
        val pts = points(q); val profile = profile(q)
        val loaded = packsFor(pts)
        val mr = MultiRouter(loaded.map { it.router })
        loaded.forEach { it.lastUsed = System.currentTimeMillis() }
        val t0 = System.currentTimeMillis()
        // leg by leg, remembering where each leg starts in the joined line
        // each point snaps to the nearest road or trail that actually leads somewhere, of any class the mode can use
        val snaps = pts.mapIndexed { i, p -> mr.snapConnected(p[0], p[1], profile, 3000.0) ?: throw ApiError(422, "Point ${i + 1} is more than 3 km from any road or trail", mapOf("leg" to i.toString())) }
        val legs = ArrayList<RouteResult>()
        for (i in 0 until pts.size - 1) {
            val leg = mr.route(snaps[i], snaps[i + 1], profile)
                ?: throw ApiError(422, "No ${profile.label} route between point ${i + 1} and point ${i + 2}", mapOf("leg" to i.toString()))
            legs.add(leg)
        }
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val se = IntList(); val sp = IntList(); val vn = IntList()
        val legStart = IntArray(legs.size + 1)
        var len = 0.0; var time = 0.0
        for ((li, l) in legs.withIndex()) {
            legStart[li] = maxOf(0, lats.size - 1)
            val start = if (li == 0) 0 else 1
            for (i in start until l.lats.size) { lats.add(l.lats[i]); lons.add(l.lons[i]); se.add(l.segEdge[i]); sp.add(l.segPack[i]); vn.add(if (i == 0) -1 else l.vertexNode[i]) }
            len += l.lengthMeters; time += l.timeSeconds
        }
        legStart[legs.size] = lats.size - 1
        val rr = RouteResult(lats.toDoubleArray(), lons.toDoubleArray(), se.toArray(), sp.toArray(), len, time, vn.toArray())
        val route = Route.fromGraphRoute(rr, mr, profile)
        val ms = System.currentTimeMillis() - t0

        val coords = StringBuilder("[")
        for (i in rr.lats.indices) { if (i > 0) coords.append(','); coords.append('[').append(Json.num(rr.lons[i])).append(',').append(Json.num(rr.lats[i])).append(']') }
        coords.append(']')
        val cues = route.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }.map { m ->
            Json.obj("i" to m.vertex.toString(), "lon" to Json.num(rr.lons[m.vertex]), "lat" to Json.num(rr.lats[m.vertex]), "at" to Json.num(m.at, 1),
                "turn" to Json.str(m.turn.name), "name" to Json.str(if (m.turn == Turn.ROUNDABOUT) "exit ${m.exit}" else m.name), "angle" to Json.num(m.angle, 0), "exit" to m.exit.toString())
        }
        // runs of one road class and surface along the line, for the surface bar and the coloured line
        val runs = ArrayList<String>()
        var runStart = 0; var runCls = -1; var runUnpaved = false; var runM = 0.0; var runName = ""
        fun flush(end: Int) { if (end > runStart) runs.add(Json.obj("i" to runStart.toString(), "j" to end.toString(), "cls" to Json.str(GraphFormat.CLASSES.getOrElse(runCls) { "road" }), "unpaved" to runUnpaved.toString(), "m" to Json.num(runM, 1), "name" to Json.str(runName))) }
        for (i in 1 until rr.lats.size) {
            val e = rr.segEdge[i]; val p = rr.segPack[i]
            val flags = if (e >= 0 && p >= 0) mr.graph(p).edgeFlags(e) else 0
            val cls = flags and GraphFormat.CLASS_MASK; val unp = flags and GraphFormat.UNPAVED != 0
            val name = if (e >= 0 && p >= 0) mr.graph(p).edgeName(e) else ""
            if (cls != runCls || unp != runUnpaved || name != runName) { flush(i - 1); runStart = i - 1; runCls = cls; runUnpaved = unp; runM = 0.0; runName = name }
            runM += Geo.fastDistance(rr.lats[i - 1], rr.lons[i - 1], rr.lats[i], rr.lons[i])
        }
        flush(rr.lats.size - 1)
        Json.obj(
            "mode" to Json.str(profile.name), "length" to Json.num(len, 1), "time" to Json.num(time, 0), "ms" to ms.toString(),
            "coords" to coords.toString(),
            "legs" to Json.arr((0 until legs.size).map { Json.obj("start" to legStart[it].toString(), "end" to legStart[it + 1].toString(), "length" to Json.num(legs[it].lengthMeters, 1), "time" to Json.num(legs[it].timeSeconds, 0)) }),
            "cues" to Json.arr(cues),
            "surface" to Json.arr(runs),
            "packs" to Json.arr(loaded.map { Json.str(it.pack.id) }),
            "snapped" to Json.arr(snaps.map { Json.obj("lon" to Json.num(it.lon), "lat" to Json.num(it.lat), "distance" to Json.num(it.distance, 0)) })
        )
    }

    /** What is under a point: the nearest road or trail of any kind and what the graph knows about it. */
    fun snap(q: Map<String, String>): String = routing {
        val lon = finite(q["lon"], 180.0) ?: throw ApiError(400, "lon required"); val lat = finite(q["lat"], 90.0) ?: throw ApiError(400, "lat required")
        val max = q["max"]?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(10.0, 5000.0) ?: 1500.0
        val loaded = packs.forBbox(lon - 0.02, lat - 0.02, lon + 0.02, lat + 0.02)
        if (loaded.isEmpty()) throw ApiError(404, "No map pack covers this point yet")
        val mr = MultiRouter(loaded.map { it.router })
        val s = mr.snapConnected(lat, lon, Profile.DIRT, max) ?: throw ApiError(404, "Nothing within ${max.toInt()} m")
        val g = mr.graph(s.pack); val flags = g.edgeFlags(s.edge)
        Json.obj("lon" to Json.num(s.lon), "lat" to Json.num(s.lat), "distance" to Json.num(s.distance, 1), "name" to Json.str(g.edgeName(s.edge)),
            "cls" to Json.str(GraphFormat.CLASSES.getOrElse(flags and GraphFormat.CLASS_MASK) { "road" }), "unpaved" to (flags and GraphFormat.UNPAVED != 0).toString(),
            "oneway" to (flags and GraphFormat.ONEWAY != 0).toString(), "car" to (flags and GraphFormat.CAR != 0).toString(), "bike" to (flags and GraphFormat.BIKE != 0).toString(),
            "pack" to Json.str(loaded[s.pack].pack.id), "region" to Json.str(loaded[s.pack].pack.region))
    }

    /**
     * Place and address search, the same index the phone uses. "manhattan, mt" or "manhattan montana"
     * searches Montana wherever the map is; otherwise the packs under the map (its bounds, or 2° around
     * a point) are searched, nearest first among equal scores.
     */
    fun search(q: Map<String, String>): String {
        var text = q["q"]?.trim() ?: throw ApiError(400, "q required")
        if (text.length < 2) return Json.obj("hits" to "[]")
        val lon = finite(q["lon"], 180.0); val lat = finite(q["lat"], 90.0)
        if ((q["lon"] != null || q["lat"] != null) && (lon == null || lat == null)) throw ApiError(400, "bad lon/lat")
        // a bbox is four finite ordered numbers covering at most 12° on a side; anything else is refused before any pack is touched
        val bbox = q["bbox"]?.let { raw ->
            val b = raw.split(',').map { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: throw ApiError(400, "bad bbox") }
            if (b.size != 4 || b[0] > b[2] || b[1] > b[3] || b[2] - b[0] > 12.0 || b[3] - b[1] > 12.0 || b.any { Math.abs(it) > 180 }) throw ApiError(400, "bad bbox")
            b
        }
        // a state named at the end of the query picks the packs
        val words = text.lowercase().replace(Regex("[,.]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        var region: String? = null
        if (words.size >= 2) {
            val last1 = words.last(); val last2 = words.takeLast(2).joinToString(" ")
            region = STATES[last2]?.also { text = words.dropLast(2).joinToString(" ") } ?: STATES[last1]?.also { text = words.dropLast(1).joinToString(" ") }
        }
        val loaded: List<Loaded> = when {
            region != null -> packs.catalog().filter { it.region.equals(region, ignoreCase = true) }.mapNotNull { try { packs.ensure(it) } catch (e: Throwable) { System.err.println("search: ${it.id} unavailable: ${e.message}"); null } }
            bbox != null -> packs.forBbox(bbox[0], bbox[1], bbox[2], bbox[3])
            lon != null && lat != null -> packs.forBbox(lon - 2.0, lat - 2.0, lon + 2.0, lat + 2.0)
            else -> emptyList()
        }
        if (region != null && loaded.isEmpty()) throw ApiError(404, "No map pack for $region yet")
        if (text.isBlank()) return Json.obj("hits" to "[]")
        val hits = ArrayList<Triple<Int, Double, Pair<fold.core.Hit, String>>>()
        for (l in loaded) {
            val sf = l.search ?: continue
            for ((score, h) in sf.queryScored(text, 15)) {
                val d = if (lon != null && lat != null) Geo.fastDistance(lat, lon, h.lat, h.lon) else 0.0
                hits.add(Triple(score, d, h to l.pack.region))
            }
        }
        val best = hits.sortedWith(compareByDescending<Triple<Int, Double, Pair<fold.core.Hit, String>>> { it.first }.thenBy { it.second }).take(12)
        return Json.obj("hits" to Json.arr(best.map { (_, d, hr) -> val h = hr.first; Json.obj("kind" to Json.str(h.kind.toString()), "name" to Json.str(h.name), "context" to Json.str(h.context), "region" to Json.str(hr.second), "lon" to Json.num(h.lon), "lat" to Json.num(h.lat), "distance" to Json.num(d, 0)) }))
    }

    private fun finite(s: String?, max: Double): Double? = s?.toDoubleOrNull()?.takeIf { it.isFinite() && Math.abs(it) <= max }

    companion object {
        /** State codes and names → the catalog's region names. */
        val STATES: Map<String, String> = HashMap<String, String>().apply {
            val pairs = listOf("al" to "Alabama", "ak" to "Alaska", "az" to "Arizona", "ar" to "Arkansas", "ca" to "California", "co" to "Colorado", "ct" to "Connecticut", "de" to "Delaware", "dc" to "District of Columbia", "fl" to "Florida", "ga" to "Georgia", "hi" to "Hawaii", "id" to "Idaho", "il" to "Illinois", "in" to "Indiana", "ia" to "Iowa", "ks" to "Kansas", "ky" to "Kentucky", "la" to "Louisiana", "me" to "Maine", "md" to "Maryland", "ma" to "Massachusetts", "mi" to "Michigan", "mn" to "Minnesota", "ms" to "Mississippi", "mo" to "Missouri", "mt" to "Montana", "ne" to "Nebraska", "nv" to "Nevada", "nh" to "New Hampshire", "nj" to "New Jersey", "nm" to "New Mexico", "ny" to "New York", "nc" to "North Carolina", "nd" to "North Dakota", "oh" to "Ohio", "ok" to "Oklahoma", "or" to "Oregon", "pa" to "Pennsylvania", "ri" to "Rhode Island", "sc" to "South Carolina", "sd" to "South Dakota", "tn" to "Tennessee", "tx" to "Texas", "ut" to "Utah", "vt" to "Vermont", "va" to "Virginia", "wa" to "Washington", "wv" to "West Virginia", "wi" to "Wisconsin", "wy" to "Wyoming", "bc" to "Baja California", "bcs" to "Baja California", "baja" to "Baja California")
            for ((code, name) in pairs) { put(code, name); put(name.lowercase(), name) }
            put("baja california", "Baja California"); put("baja california sur", "Baja California")
        }
    }
}
