package fold.packer

import fold.core.Geo
import fold.core.GraphFormat
import fold.core.GraphWriter
import fold.core.IntList
import fold.core.TextNorm
import java.io.File
import java.io.PrintWriter
import kotlin.math.floor
import kotlin.math.roundToInt

/** Tag interpretation: which ways are roads, how they may be used. */
object Tagging {
    private val noAccess = setOf("no", "private", "agricultural", "forestry", "delivery", "customers", "military", "emergency")
    private val unpavedSurfaces = setOf("unpaved", "gravel", "dirt", "ground", "grass", "sand", "compacted", "fine_gravel", "earth", "mud", "pebblestone", "rock", "woodchips", "gravel;dirt", "dirt/sand", "ice", "snow", "salt", "grass_paver")

    private fun resolve(tags: Map<String, String>, keys: List<String>, default: Boolean): Boolean {
        for (k in keys) { val v = tags[k] ?: continue; return !noAccess.contains(v) }
        return default
    }
    /** access=private (the owner's say-so) for motor vehicles: kept in the graph, flagged, penalised. "no" and the rest stay out. */
    private fun isPrivate(tags: Map<String, String>): Boolean {
        for (k in listOf("motorcar", "motor_vehicle", "vehicle", "access")) { val v = tags[k] ?: continue; return v == "private" }
        return false
    }

    /** Returns flags for the way, or -1 if it is not a routable road. Handles direction normalization via [reverse]. */
    class WayInfo(val flags: Int, val reverse: Boolean, val name: String)

    fun classify(tags: Map<String, String>): WayInfo? {
        val hw = tags["highway"] ?: return null
        val cls = GraphFormat.classIndex(hw)
        if (cls < 0) return null
        if (tags["area"] == "yes" && cls >= 16) return null
        var flags = cls
        val carDefault = cls <= 15
        val bikeDefault = cls !in setOf(0, 1, 21)
        var car = resolve(tags, listOf("motorcar", "motor_vehicle", "vehicle", "access"), carDefault)
        var bike = resolve(tags, listOf("bicycle", "vehicle", "access"), bikeDefault)
        val private = cls <= 15 && !car && isPrivate(tags)
        if (private) car = true
        if (cls > 15) car = false
        if (cls == 21) bike = false
        if (tags["motorroad"] == "yes") bike = false
        if (!car && !bike) return null
        if (car) flags = flags or GraphFormat.CAR
        if (bike) flags = flags or GraphFormat.BIKE
        if (private) flags = flags or GraphFormat.PRIVATE
        if (resolve(tags, listOf("foot", "access"), cls != 0 && cls != 1)) flags = flags or GraphFormat.FOOT

        var reverse = false
        val junction = tags["junction"]
        val roundabout = junction == "roundabout" || junction == "circular"
        if (roundabout) flags = flags or GraphFormat.ROUNDABOUT
        when (tags["oneway"]) {
            "yes", "true", "1" -> flags = flags or GraphFormat.ONEWAY
            "-1", "reverse" -> { flags = flags or GraphFormat.ONEWAY; reverse = true }
            "no", "false", "0" -> {}
            else -> if (roundabout || cls == 0) flags = flags or GraphFormat.ONEWAY
        }
        val surface = tags["surface"]
        val tracktype = tags["tracktype"]
        val unpaved = when {
            surface != null -> unpavedSurfaces.contains(surface) || surface.startsWith("gravel") || surface.startsWith("dirt")
            tracktype != null -> tracktype != "grade1"
            else -> cls == 14 || cls == 17 || cls == 20
        }
        if (unpaved) flags = flags or GraphFormat.UNPAVED
        val name = tags["name"] ?: tags["ref"]?.replace(";", " / ") ?: ""
        return WayInfo(flags, reverse, name)
    }
}

class RoadWay(val nodes: LongArray, val flags: Int, val name: String)
class AddrWay(val firstNode: Long, val label: String, val city: String)

/** A place a rider asks about. Prominence 1–3: how far away it is still worth hearing of (a cafe: close; fuel: far). */
class Poi(val cat: String, val name: String, val kind: String, val prom: Int, val extra: String) {
    companion object {
        fun of(tags: Map<String, String>): Poi? {
            val amenity = tags["amenity"]; val shop = tags["shop"]; val tourism = tags["tourism"]; val natural = tags["natural"]; val place = tags["place"]
            val name = (tags["name"] ?: tags["brand"] ?: tags["operator"] ?: "").replace('\t', ' ').replace('\n', ' ').trim().take(80)
            val hours = (tags["opening_hours"] ?: "").take(60).replace('\t', ' ')
            fun extra(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(" · ")
            return when {
                amenity == "fuel" -> Poi("fuel", name, "fuel", 3, hours)
                tourism == "camp_site" -> Poi("camping", name, if (tags["backcountry"] == "yes") "backcountry" else "campground", 3, extra(when (tags["fee"]) { "no" -> "free"; "yes" -> "fee"; else -> "" }, tags["operator"]?.take(40) ?: ""))
                tourism == "caravan_site" -> Poi("camping", name, "RV park", 2, extra(when (tags["fee"]) { "no" -> "free"; else -> "" }))
                tourism == "camp_pitch" -> Poi("camping", name, "camp pitch", 1, "")
                tourism in setOf("hotel", "motel", "hostel", "guest_house", "chalet", "alpine_hut") -> Poi("lodging", name, tourism!!.replace('_', ' '), 2, tags["phone"]?.take(24) ?: "")
                amenity == "drinking_water" -> Poi("water", name, if (tags["drinking_water"] == "no") "tap, not potable" else "tap", 1, "")
                natural == "spring" -> Poi("water", name, "spring", 1, "")
                tags["man_made"] == "water_well" -> Poi("water", name, "well", 1, "")
                shop == "motorcycle" || shop == "motorcycle_repair" -> Poi("repair", name, if (shop == "motorcycle") "motorcycle shop" else "motorcycle repair", 3, hours)
                shop == "car_repair" || amenity == "car_repair" || shop == "tyres" -> Poi("repair", name, "car repair", 2, hours)
                shop in setOf("supermarket", "convenience", "general") -> Poi("food", name, shop!!, 2, hours)
                shop == "bakery" -> Poi("food", name, "bakery", 1, hours)
                amenity in setOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_court") -> Poi("food", name, amenity!!.replace('_', ' '), 1, hours)
                place == "city" || place == "town" -> Poi("town", name, place, 3, tags["population"]?.take(9) ?: "")
                place == "village" -> Poi("town", name, "village", 2, tags["population"]?.take(9) ?: "")
                place == "hamlet" -> Poi("town", name, "hamlet", 1, "")
                else -> null
            }?.takeIf { it.name.isNotEmpty() || it.cat in setOf("water", "camping") }
        }
    }
}

/** Two-pass build of graph.bin and search.tsv from an OSM file. */
class Packer(private val osm: File, private val log: (String) -> Unit, private val clip: DoubleArray? = null) {
    /** [minLon, minLat, maxLon, maxLat] clip, or null for the whole extract. */
    private fun inside(lat: Double, lon: Double) = clip == null || (lon >= clip[0] && lon <= clip[2] && lat >= clip[1] && lat <= clip[3])
    private val useCount = LongIntMap(1 shl 20)
    private val ways = ArrayList<RoadWay>()
    private val addrWays = ArrayList<AddrWay>()
    private val places = ArrayList<Place>()
    private val addrNodes = ArrayList<Place>()
    private val pois = ArrayList<Pair<Poi, Long>>()                 // a place and the node that locates it (its own id, or a way's first node)
    private val poiNodes = ArrayList<Triple<Poi, Double, Double>>()
    private val privateGates = HashSet<Long>()

    class Place(val name: String, val kind: String, val lat: Double, val lon: Double)

    var minLat = 90.0; var minLon = 180.0; var maxLat = -90.0; var maxLon = -180.0

    private fun pass1() {
        var count = 0
        Osm.read(osm, null) { w ->
            val tags = Osm.tags(w)
            val info = Tagging.classify(tags)
            val wn = w.wayNodes
            if (info != null && wn.size >= 2) {
                val ids = LongArray(wn.size) { wn[it].nodeId }
                if (info.reverse) ids.reverse()
                for (id in ids) useCount.increment(id)
                // endpoints always junctions
                useCount.increment(ids[0]); useCount.increment(ids[ids.size - 1])
                ways.add(RoadWay(ids, info.flags, info.name))
                count++
            }
            val hn = tags["addr:housenumber"]; val st = tags["addr:street"]
            if (hn != null && st != null && wn.isNotEmpty()) {
                addrWays.add(AddrWay(wn[0].nodeId, "$hn $st", tags["addr:city"] ?: ""))
                useCount.increment(wn[0].nodeId) // ensure we keep its coordinate
                useCount.increment(wn[0].nodeId)
            }
            // a campground or a store drawn as an area: located by its first node
            if (info == null && wn.isNotEmpty()) Poi.of(tags)?.let { pois.add(it to wn[0].nodeId); useCount.increment(wn[0].nodeId); useCount.increment(wn[0].nodeId) }
        }
        log("pass 1: ${ways.size} road ways, ${addrWays.size} addressed buildings, ${pois.size} places as areas, ${useCount.size} nodes referenced")
    }

    private var clipEast = false
    private val coordIndexRaw = LongIntMap(1 shl 20)
    private val coordIndex = object { fun get(id: Long): Int { val i = coordIndexRaw.get(id); return if (i >= 0 && clipEast && coordLon[i] > 0) -1 else i }; fun put(id: Long, v: Int) = coordIndexRaw.put(id, v) }
    private val coordLat = IntList(1 shl 20); private val coordLon = IntList(1 shl 20)

    private fun pass2() {
        Osm.read(osm, { n ->
            val id = n.id
            if (!inside(n.latitude, n.longitude)) return@read
            if (useCount.get(id) >= 0) {
                coordIndex.put(id, coordLat.size)
                coordLat.add((n.latitude * 1e7).roundToInt()); coordLon.add((n.longitude * 1e7).roundToInt())
            }
            val tags = if (n.tags.isEmpty()) emptyMap() else Osm.tags(n)
            if (tags.isNotEmpty()) {
                val place = tags["place"]; val name = tags["name"]
                if (place != null && name != null && place in placeKinds) places.add(Place(name, place, n.latitude, n.longitude))
                val hn = tags["addr:housenumber"]; val st = tags["addr:street"]
                if (hn != null && st != null) addrNodes.add(Place("$hn $st", tags["addr:city"] ?: "", n.latitude, n.longitude))
                Poi.of(tags)?.let { poiNodes.add(Triple(it, n.latitude, n.longitude)) }
                // a locked or private gate on a road marks the road it sits on as posted private
                val barrier = tags["barrier"]
                if (barrier != null && (barrier == "gate" || barrier == "lift_gate" || barrier == "swing_gate" || barrier == "barrier") && (tags["locked"] == "yes" || tags["access"] == "private" || tags["motor_vehicle"] == "private")) privateGates.add(id)
            }
        }, null)
        log("pass 2: ${coordLat.size} coordinates, ${places.size} places, ${addrNodes.size} address points, ${poiNodes.size} places as points, ${privateGates.size} private gates")
    }

    private val placeKinds = setOf("city", "town", "village", "hamlet", "suburb", "neighbourhood", "locality", "isolated_dwelling")

    fun build(graphOut: File, searchOut: File, poisOut: File? = null) {
        pass1(); pass2()
        // An extract that wraps the antimeridian (Alaska's Aleutians) would make the bounding box span the planet.
        // Keep the western-hemisphere part only; the islands past 180° have no roads worth routing.
        var lo = 180.0; var hi = -180.0
        for (i in 0 until coordLat.size) { val l = coordLon[i] / 1e7; if (l < lo) lo = l; if (l > hi) hi = l }
        if (hi - lo > 180.0) {
            log("extract crosses the antimeridian: dropping ${"%d".format((0 until coordLat.size).count { coordLon[it] > 0 })} coordinates east of 180°")
            clipEast = true
        }
        buildGraph(graphOut)
        buildSearch(searchOut)
        if (poisOut != null) buildPois(poisOut)
    }

    /** pois.tsv: lat_e7, lon_e7, prominence, category, kind, name, extra — one place per line, for the planner's nearby lookup. */
    private fun buildPois(out: File) {
        var n = 0
        PrintWriter(out.bufferedWriter()).use { pw ->
            fun line(p: Poi, lat: Double, lon: Double) {
                if (!inside(lat, lon)) return
                pw.print((lat * 1e7).roundToInt()); pw.print('\t'); pw.print((lon * 1e7).roundToInt()); pw.print('\t'); pw.print(p.prom); pw.print('\t'); pw.print(p.cat); pw.print('\t'); pw.print(p.kind); pw.print('\t'); pw.print(p.name); pw.print('\t'); pw.print(p.extra); pw.print('\n'); n++
            }
            for ((p, la, lo) in poiNodes) line(p, la, lo)
            for ((p, nodeId) in pois) { val ci = coordIndex.get(nodeId); if (ci >= 0) line(p, coordLat[ci] / 1e7, coordLon[ci] / 1e7) }
        }
        log("pois.tsv: $n places, ${out.length() / 1024} KB")
    }

    private fun buildGraph(out: File) {
        val gw = GraphWriter()
        val nodeIndex = LongIntMap(1 shl 20)
        var tmpLat = IntArray(4096); var tmpLon = IntArray(4096)
        var dropped = 0
        for (w in ways) {
            val nameIdx = gw.nameId(w.name)
            val ids = w.nodes
            // split at junction nodes
            var startI = -1
            for (i in ids.indices) {
                val ci = coordIndex.get(ids[i])
                if (ci < 0) { dropped++; startI = -1; continue } // node missing from extract: break the way here
                val isJunction = useCount.get(ids[i]) >= 2 || i == 0 || i == ids.size - 1
                if (!isJunction) continue
                if (startI >= 0) {
                    // edge from ids[startI] to ids[i]
                    val a = nodeIndex.get(ids[startI]).let { if (it < 0) { val ca = coordIndex.get(ids[startI]); val n = gw.addNode(coordLat[ca], coordLon[ca], ids[startI]); nodeIndex.put(ids[startI], n); n } else it }
                    val b = nodeIndex.get(ids[i]).let { if (it < 0) { val n = gw.addNode(coordLat[ci], coordLon[ci], ids[i]); nodeIndex.put(ids[i], n); n } else it }
                    var geomN = 0
                    var len = 0.0
                    var pLat = coordLat[coordIndex.get(ids[startI])] / 1e7; var pLon = coordLon[coordIndex.get(ids[startI])] / 1e7
                    var broken = false
                    for (k in startI + 1 until i) {
                        val ck = coordIndex.get(ids[k]); if (ck < 0) { broken = true; break }
                        if (geomN == tmpLat.size) { tmpLat = tmpLat.copyOf(geomN * 2); tmpLon = tmpLon.copyOf(geomN * 2) }
                        tmpLat[geomN] = coordLat[ck]; tmpLon[geomN] = coordLon[ck]; geomN++
                        val qLat = coordLat[ck] / 1e7; val qLon = coordLon[ck] / 1e7
                        len += Geo.fastDistance(pLat, pLon, qLat, qLon); pLat = qLat; pLon = qLon
                    }
                    if (!broken) {
                        len += Geo.fastDistance(pLat, pLon, coordLat[ci] / 1e7, coordLon[ci] / 1e7)
                        if (a != b || geomN > 0) {
                            if (geomN > 65535) { geomN = 65535 }
                            var flags = w.flags
                            if (privateGates.isNotEmpty()) for (k in startI..i) if (privateGates.contains(ids[k])) { flags = flags or GraphFormat.PRIVATE; break }
                            gw.addEdge(a, b, (len * 100).roundToInt().coerceAtLeast(1), flags, nameIdx, tmpLat, tmpLon, geomN)
                        }
                    }
                }
                startI = i
            }
        }
        log("graph: ${gw.nodeLat.size} nodes, ${gw.edgeFrom.size} edges, ${gw.geomLat.size} shape points, ${gw.names.size} names" + if (dropped > 0) " ($dropped way nodes outside extract)" else "")
        out.outputStream().use { gw.write(it) }
        for (i in 0 until gw.nodeLat.size) {
            val la = gw.nodeLat[i] / 1e7; val lo = gw.nodeLon[i] / 1e7
            if (la < minLat) minLat = la; if (la > maxLat) maxLat = la; if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo
        }
        log("graph.bin: ${out.length() / 1024 / 1024} MB")
    }

    private fun buildSearch(out: File) {
        // nearest-place lookup on a coarse grid
        val bigPlaces = places.filter { it.kind == "city" || it.kind == "town" || it.kind == "village" || it.kind == "hamlet" }
        fun nearestPlace(lat: Double, lon: Double): String {
            var best = ""; var bd = 40_000.0
            for (p in bigPlaces) {
                val d = Geo.fastDistance(lat, lon, p.lat, p.lon)
                val w = when (p.kind) { "city" -> 0.5; "town" -> 0.7; "village" -> 0.85; else -> 1.0 }
                if (d * w < bd) { bd = d * w; best = p.name }
            }
            return best
        }
        val lines = ArrayList<String>()
        run {
            fun line(kind: Char, name: String, ctx: String, lat: Double, lon: Double) {
                lines.addAll(fold.core.SearchFile.lines(kind, name, ctx, (lat * 1e7).roundToInt(), (lon * 1e7).roundToInt()))
            }
            var np = 0; var ns = 0; var na = 0
            for (p in places) {
                val ctx = when (p.kind) { "city", "town" -> ""; else -> p.kind.replace('_', ' ').replaceFirstChar { it.uppercase() } }
                line('P', p.name, ctx, p.lat, p.lon); np++
            }
            val seen = HashSet<String>()
            for (w in ways) {
                if (w.name.isEmpty()) continue
                val mid = w.nodes[w.nodes.size / 2]
                val ci = coordIndex.get(mid); if (ci < 0) continue
                val lat = coordLat[ci] / 1e7; val lon = coordLon[ci] / 1e7
                val key = TextNorm.normalize(w.name) + "|" + floor(lat / 0.04).toInt() + "|" + floor(lon / 0.04).toInt()
                if (!seen.add(key)) continue
                line('S', w.name, nearestPlace(lat, lon), lat, lon); ns++
            }
            val seenA = HashSet<String>()
            for (a in addrNodes) {
                val ctx = a.kind.ifEmpty { nearestPlace(a.lat, a.lon) }
                if (!seenA.add(TextNorm.normalize(a.name + " " + ctx))) continue
                line('A', a.name, ctx, a.lat, a.lon); na++
            }
            for (a in addrWays) {
                val ci = coordIndex.get(a.firstNode); if (ci < 0) continue
                val lat = coordLat[ci] / 1e7; val lon = coordLon[ci] / 1e7
                val ctx = a.city.ifEmpty { nearestPlace(lat, lon) }
                if (!seenA.add(TextNorm.normalize(a.label + " " + ctx))) continue
                line('A', a.label, ctx, lat, lon); na++
            }
            log("search: $np places, $ns streets, $na addresses")
        }
        // the app binary-searches this file, so it must be sorted by its first column (byte order)
        lines.sort()
        PrintWriter(out, "UTF-8").use { pw -> for (l in lines) { pw.print(l); pw.print('\n') } }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
}
