package fold.core

import fold.core.GraphFormat as F

/**
 * Route modes. Each is a way of reading the same map: which roads count as usable and how much a
 * minute on each kind of road "costs" when choosing. ETA always uses real speeds.
 */
enum class Profile(val label: String, val bike: Boolean, val color: String, val blurb: String) {
    FAST("Fast", false, "#2563EB", "Interstates and major highways whenever possible"),
    TOWING("Towing", false, "#0F766E", "Big roads only: no tracks, no dirt, no narrow streets; gentle on turns"),
    BACKROADS("Backroads", false, "#D97706", "Paved two-lane highways and county roads; avoids the interstate"),
    DIRT("Dirt", false, "#8B5A2B", "Prefers gravel and dirt roads and tracks; pavement only to connect them"),
    RIDE("Ride", true, "#16A34A", "Bicycle, fastest sensible way"),
    QUIET("Quiet ride", true, "#7C3AED", "Bicycle, bike paths and quiet streets even when slower"),
    GRAVEL("Gravel ride", true, "#65A30D", "Bicycle, prefers unpaved roads, paths and tracks");

    companion object {
        /** Older builds stored "DRIVE". */
        fun parse(name: String?): Profile = when (name) { null -> FAST; "DRIVE" -> FAST; else -> try { valueOf(name) } catch (_: Throwable) { FAST } }
    }
}

/** Speed model per road class, in m/s. Also decides which edges are usable. */
object Speeds {
    private const val KMH = 1.0 / 3.6
    // index = class (see GraphFormat.CLASSES)
    private val drive = doubleArrayOf(
        105.0, 60.0, 90.0, 55.0, 80.0, 50.0, 70.0, 45.0, 60.0, 40.0, 50.0, 35.0,
        10.0, 20.0, 20.0, 40.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    ).map { it * KMH }.toDoubleArray()
    private val ride = doubleArrayOf(
        0.0, 0.0, 16.0, 16.0, 18.0, 16.0, 18.0, 16.0, 18.0, 16.0, 18.0, 17.0,
        14.0, 14.0, 12.0, 15.0, 18.0, 11.0, 9.0, 8.0, 8.0, 0.0
    ).map { it * KMH }.toDoubleArray()

    fun maxSpeed(p: Profile) = if (p.bike) ride.max() else drive.max()

    /** Travel speed on an edge for the profile, or 0 if not usable. Includes an unpaved penalty. */
    fun speed(p: Profile, flags: Int): Double {
        val cls = flags and F.CLASS_MASK
        val unpaved = flags and F.UNPAVED != 0
        val base = if (p.bike) { if (flags and F.BIKE == 0) 0.0 else ride[cls] } else { if (flags and F.CAR == 0) 0.0 else drive[cls] }
        if (base <= 0.0) return 0.0
        // towing: nothing unpaved, no tracks, no living streets
        if (p == Profile.TOWING && (unpaved || cls == 14 || cls == 12)) return 0.0
        return if (unpaved) base * (if (p == Profile.DIRT || p == Profile.GRAVEL) 0.75 else 0.6) else base
    }

    /** Smallest weight a profile can apply; keeps the A* heuristic admissible when some roads are "cheaper than time". */
    fun minWeight(p: Profile) = when (p) { Profile.FAST, Profile.TOWING, Profile.BACKROADS -> 1.0; Profile.DIRT -> 0.6; Profile.RIDE -> 0.8; Profile.QUIET -> 0.5; Profile.GRAVEL -> 0.5 }

    /** Extra weight multiplier used only for route choice (not ETA). Tables are per road class (see GraphFormat.CLASSES). */
    fun weight(p: Profile, flags: Int): Double {
        val cls = flags and F.CLASS_MASK
        val unpaved = flags and F.UNPAVED != 0
        return when (p) {
            // relative to a motorway hour, a two-lane highway hour costs 1.65: the long way round on I-90
            // beats a slightly shorter mountain pass. Motorway stays at 1.0 so the heuristic stays exact.
            Profile.FAST -> when (cls) {
                0, 1 -> 1.0; 2, 3 -> 1.4; 4, 5 -> 1.65; 6, 7 -> 1.85; 8, 9 -> 2.1; 10, 11 -> 2.3
                12 -> 2.9; 13 -> 2.6; 14 -> 4.3; 15 -> 2.6; else -> 1.4
            }
            // towing a trailer: even stronger pull to big roads, and city streets are expensive
            Profile.TOWING -> when (cls) {
                0, 1 -> 1.0; 2, 3 -> 1.25; 4, 5 -> 1.7; 6, 7 -> 2.4; 8, 9 -> 3.2; 10, 11 -> 3.8
                13 -> 4.5; 15 -> 4.0; else -> 3.0
            }
            // motorcycle or scenic drive: paved two-lanes are the point, interstates are for when nothing else goes there
            Profile.BACKROADS -> (when (cls) {
                0, 1 -> 2.8; 2, 3 -> 1.8; 4, 5 -> 1.2; 6, 7 -> 1.0; 8, 9 -> 1.05; 10 -> 1.3; 11 -> 1.9
                12 -> 2.6; 13 -> 2.6; 14 -> 3.5; 15 -> 1.6; else -> 1.5
            }) * (if (unpaved) 1.8 else 1.0)
            // adventure: unpaved is what we came for
            Profile.DIRT -> (when (cls) {
                0, 1 -> 4.0; 2, 3 -> 2.6; 4, 5 -> 1.9; 6, 7 -> 1.5; 8, 9 -> 1.3; 10 -> 1.15; 11 -> 1.6
                12 -> 2.0; 13 -> 1.5; 14 -> 1.0; 15 -> 1.2; else -> 1.5
            }) * (if (unpaved) 0.6 else 1.0)
            Profile.RIDE -> when (cls) { 2, 3 -> 2.0; 4, 5 -> 1.4; 16 -> 0.8; 17 -> 1.3; 18 -> 1.6; 21 -> 5.0; else -> 1.0 }
            // a car-free or low-traffic route is worth a real detour; busy roads cost several times their length
            Profile.QUIET -> when (cls) {
                2, 3 -> 8.0; 4, 5 -> 5.0; 6, 7 -> 3.0; 8, 9 -> 1.8; 10 -> 1.3; 11, 12 -> 0.9; 13 -> 1.0
                14 -> 1.2; 15 -> 1.3; 16 -> 0.5; 17 -> 0.9; 18 -> 1.4; 19 -> 1.0; 20 -> 1.2; 21 -> 6.0; else -> 1.0
            }
            Profile.GRAVEL -> (when (cls) {
                2, 3 -> 8.0; 4, 5 -> 4.0; 6, 7 -> 2.4; 8, 9 -> 1.5; 10 -> 1.1; 11, 12 -> 1.0; 13 -> 1.0
                14 -> 0.8; 15 -> 1.1; 16 -> 0.7; 17 -> 0.9; 18 -> 1.4; 19 -> 1.0; 20 -> 1.0; 21 -> 6.0; else -> 1.0
            }) * (if (unpaved) 0.65 else 1.0)
        }
    }

    /** Turn penalty for a u-turn on the same edge. Towing hates them. */
    fun uturnPenalty(p: Profile) = if (p == Profile.TOWING) 180.0 else 30.0
}

/** A point snapped onto an edge of one pack's graph. */
class Snap(val pack: Int, val edge: Int, val t: Double, val lat: Double, val lon: Double, val distance: Double, val alongFrom: Double, val alongTo: Double)

class RouteResult(
    val lats: DoubleArray, val lons: DoubleArray,
    /** For each polyline vertex i>0, the edge that produced segment (i-1,i) and its pack. Index 0 = -1. */
    val segEdge: IntArray, val segPack: IntArray,
    val lengthMeters: Double, val timeSeconds: Double,
    /** graph node id at each vertex (in segPack[i]'s graph), or -1 for virtual points / interior geometry / pack hops */
    val vertexNode: IntArray
)

/** Per-pack search state and snapping. The search itself lives in [MultiRouter]. */
class Router(private val g: Graph) {
    // Search state is allocated the first time a search touches this pack and freed afterwards, so
    // fifty installed packs cost nothing until a route actually crosses them.
    internal lateinit var stamp: IntArray
    internal lateinit var gCost: DoubleArray
    internal lateinit var parent: IntArray    // half-edge used to reach node; <0 = seed/hop marker
    internal lateinit var closed: IntArray
    internal var allocated = false
    internal fun ensureState() {
        if (allocated) return
        stamp = IntArray(g.nodeCount); gCost = DoubleArray(g.nodeCount); parent = IntArray(g.nodeCount); closed = IntArray(g.nodeCount)
        allocated = true
    }
    internal fun releaseState() {
        if (!allocated) return
        allocated = false
        stamp = IntArray(0); gCost = DoubleArray(0); parent = IntArray(0); closed = IntArray(0)
    }
    private val proj = DoubleArray(4)

    fun graph() = g

    /** Nearest usable edge to a point, searching outward over grid cells up to maxMeters. */
    fun snap(pack: Int, lat: Double, lon: Double, profile: Profile, maxMeters: Double = 1500.0, roadsOnly: Boolean = false): Snap? {
        if (!g.contains(lat, lon)) return null
        val col = g.cellCol(lon); val row = g.cellRow(lat)
        var best: Snap? = null
        var bestD = maxMeters
        val cellMeters = F.CELL_DEG * 111_000.0
        val maxRing = (maxMeters / cellMeters).toInt() + 1
        for (ring in 0..maxRing) {
            if (best != null && bestD < (ring - 1) * cellMeters) break
            for (r in row - ring..row + ring) {
                if (r < 0 || r >= g.gridRows) continue
                for (c in col - ring..col + ring) {
                    if (c < 0 || c >= g.gridCols) continue
                    if (ring > 0 && r != row - ring && r != row + ring && c != col - ring && c != col + ring) continue
                    val s = g.cellEdgesStart(c, r); val e = g.cellEdgesEnd(c, r)
                    for (slot in s until e) {
                        val edge = g.cellEdge(slot)
                        if (Speeds.speed(profile, g.edgeFlags(edge)) <= 0.0) continue
                        if (roadsOnly && (g.edgeFlags(edge) and F.CLASS_MASK) >= 16) continue
                        val cand = snapToEdge(pack, edge, lat, lon) ?: continue
                        if (cand.distance < bestD) { bestD = cand.distance; best = cand }
                    }
                }
            }
        }
        return best
    }

    /**
     * The nearest [limit] distinct edges to a point, nearest first. A planner's click may land next to
     * a private driveway or a trail stub that leads nowhere; the caller tries these in order.
     */
    fun snapCandidates(pack: Int, lat: Double, lon: Double, profile: Profile, maxMeters: Double = 1500.0, limit: Int = 6): List<Snap> {
        if (!g.contains(lat, lon)) return emptyList()
        val col = g.cellCol(lon); val row = g.cellRow(lat)
        val cellMeters = F.CELL_DEG * 111_000.0
        val maxRing = (maxMeters / cellMeters).toInt() + 1
        val out = ArrayList<Snap>()
        for (ring in 0..maxRing) {
            for (r in row - ring..row + ring) {
                if (r < 0 || r >= g.gridRows) continue
                for (c in col - ring..col + ring) {
                    if (c < 0 || c >= g.gridCols) continue
                    if (ring > 0 && r != row - ring && r != row + ring && c != col - ring && c != col + ring) continue
                    for (slot in g.cellEdgesStart(c, r) until g.cellEdgesEnd(c, r)) {
                        val edge = g.cellEdge(slot)
                        if (Speeds.speed(profile, g.edgeFlags(edge)) <= 0.0) continue
                        val cand = snapToEdge(pack, edge, lat, lon) ?: continue
                        if (cand.distance <= maxMeters) out.add(cand)
                    }
                }
            }
        }
        return out.sortedBy { it.distance }.distinctBy { it.edge }.take(limit)
    }

    /**
     * How many nodes this profile can reach from the snap, stopping at [limit]. A small number means
     * the edge sits on an island (a gated compound, a trail fragment) that no route can leave.
     */
    fun reachable(s: Snap, profile: Profile, limit: Int): Int {
        val seen = HashSet<Int>(); val queue = ArrayDeque<Int>()
        for (n in intArrayOf(g.edgeFrom(s.edge), g.edgeTo(s.edge))) if (seen.add(n)) queue.add(n)
        while (queue.isNotEmpty() && seen.size < limit) {
            val n = queue.removeFirst()
            for (slot in g.adjStart(n) until g.adjEnd(n)) {
                val edge = g.adj(slot) shr 1
                val dir = if (g.edgeFrom(edge) == n) 0 else 1
                if (!canTraverse(edge, dir, profile)) continue
                val o = g.edgeOther(edge, n)
                if (seen.add(o)) queue.add(o)
            }
        }
        return seen.size
    }

    private fun snapToEdge(pack: Int, edge: Int, lat: Double, lon: Double): Snap? {
        val n = g.edgePointCount(edge)
        var bestD = Double.MAX_VALUE; var bestK = -1; var bestT = 0.0; var bLat = 0.0; var bLon = 0.0
        var pLat = g.edgePointLat(edge, 0); var pLon = g.edgePointLon(edge, 0)
        for (k in 1 until n) {
            val qLat = g.edgePointLat(edge, k); val qLon = g.edgePointLon(edge, k)
            Geo.projectOnSegment(lat, lon, pLat, pLon, qLat, qLon, proj)
            if (proj[1] < bestD) { bestD = proj[1]; bestK = k; bestT = proj[0]; bLat = proj[2]; bLon = proj[3] }
            pLat = qLat; pLon = qLon
        }
        if (bestK < 0) return null
        var along = 0.0
        pLat = g.edgePointLat(edge, 0); pLon = g.edgePointLon(edge, 0)
        for (k in 1 until n) {
            val qLat = g.edgePointLat(edge, k); val qLon = g.edgePointLon(edge, k)
            val segLen = Geo.fastDistance(pLat, pLon, qLat, qLon)
            if (k == bestK) { along += segLen * bestT; break }
            along += segLen
            pLat = qLat; pLon = qLon
        }
        val total = g.edgeLength(edge)
        val t = if (total > 0) (along / total).coerceIn(0.0, 1.0) else 0.0
        return Snap(pack, edge, t, bLat, bLon, bestD, along, maxOf(0.0, total - along))
    }

    internal fun canTraverse(edge: Int, dir: Int, profile: Profile): Boolean {
        val f = g.edgeFlags(edge)
        if (Speeds.speed(profile, f) <= 0.0) return false
        if (dir == 1 && (f and F.ONEWAY) != 0) return false
        return true
    }

    internal fun edgeCost(edge: Int, profile: Profile, meters: Double): Double {
        val f = g.edgeFlags(edge)
        return meters / Speeds.speed(profile, f) * Speeds.weight(profile, f)
    }

    /** Does a node offer alternatives for this profile, other than the incoming edge? */
    fun outgoingOptions(node: Int, incomingEdge: Int, profile: Profile): Int {
        var c = 0
        for (slot in g.adjStart(node) until g.adjEnd(node)) {
            val he = g.adj(slot); val edge = he shr 1
            if (edge == incomingEdge) continue
            val dir = if (g.edgeFrom(edge) == node) 0 else 1
            if (canTraverse(edge, dir, profile)) c++
        }
        return c
    }
}

/**
 * A* across any number of loaded packs. Packs that overlap (neighbouring states) share junctions:
 * a node is matched to the same OpenStreetMap node in another pack and the search simply continues
 * there. Where a road was cut at an extract boundary, a dead-end within 40 m of the other pack's
 * road is bridged too.
 */
class MultiRouter(val routers: List<Router>) {
    private var gen = 0
    private val hopFrom = HashMap<Int, Int>()   // key(dest) -> key(source) for pack hops

    companion object {
        const val NODE_BITS = 25
        const val NODE_MASK = (1 shl NODE_BITS) - 1
        private const val HOP = -3
        fun key(pack: Int, node: Int) = (pack shl NODE_BITS) or node
        fun packOf(key: Int) = key ushr NODE_BITS
        fun nodeOf(key: Int) = key and NODE_MASK
    }

    fun graph(p: Int) = routers[p].graph()

    /** Nearest road across all packs that cover the point. */
    fun snap(lat: Double, lon: Double, profile: Profile, maxMeters: Double = 1500.0, roadsOnly: Boolean = false): Snap? {
        var best: Snap? = null
        for (p in routers.indices) {
            val s = routers[p].snap(p, lat, lon, profile, maxMeters, roadsOnly) ?: continue
            if (best == null || s.distance < best.distance) best = s
        }
        return best
    }

    /** Nearest candidates across all packs covering the point, nearest first. */
    fun snapCandidates(lat: Double, lon: Double, profile: Profile, maxMeters: Double = 1500.0, limit: Int = 6): List<Snap> {
        val all = ArrayList<Snap>()
        for (p in routers.indices) all.addAll(routers[p].snapCandidates(p, lat, lon, profile, maxMeters, limit))
        return all.sortedBy { it.distance }.take(limit)
    }

    /** The nearest candidate that is not on an island of fewer than [minReach] nodes; the nearest of all if every one is. */
    fun snapConnected(lat: Double, lon: Double, profile: Profile, maxMeters: Double = 1500.0, minReach: Int = 400): Snap? {
        val cands = snapCandidates(lat, lon, profile, maxMeters)
        return cands.firstOrNull { routers[it.pack].reachable(it, profile, minReach) >= minReach } ?: cands.firstOrNull()
    }

    /**
     * For long drives (> 150 km) the search first ignores residential, service and track roads except
     * within 30 km of either end — the way a person reads a road atlas — and falls back to the full
     * network only if that finds nothing.
     */
    /**
     * Snap both ends and route. For bicycle profiles a point may snap to an isolated path fragment
     * (a trail loop, a sidewalk stub); if that yields nothing, re-snap to proper roads and try again.
     */
    fun routeBetween(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, profile: Profile, destRadius: Double = 6000.0): RouteResult? {
        val a = snap(fromLat, fromLon, profile) ?: return null
        val b = snap(toLat, toLon, profile, destRadius) ?: return null
        route(a, b, profile)?.let { return it }
        if (!profile.bike) return null
        val a2 = snap(fromLat, fromLon, profile, 1500.0, true) ?: return null
        val b2 = snap(toLat, toLon, profile, destRadius, true) ?: return null
        return route(a2, b2, profile)
    }

    fun route(from: Snap, to: Snap, profile: Profile, maxExplored: Int = 40_000_000): RouteResult? {
        try {
            val crow = Geo.fastDistance(from.lat, from.lon, to.lat, to.lon)
            if (!profile.bike && crow > 150_000.0) {
                routeInner(from, to, profile, maxExplored, 30_000.0)?.let { return it }
            }
            return routeInner(from, to, profile, maxExplored, -1.0)
        } finally {
            for (r in routers) r.releaseState()
        }
    }

    /** Routes through a list of points in order (start, vias…, destination) and joins the legs. */
    fun routeVia(points: List<DoubleArray>, profile: Profile): RouteResult? {
        require(points.size >= 2)
        val legs = ArrayList<RouteResult>()
        for (i in 0 until points.size - 1) {
            val leg = routeBetween(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1], profile) ?: return null
            legs.add(leg)
        }
        if (legs.size == 1) return legs[0]
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val se = IntList(); val sp = IntList(); val vn = IntList()
        var len = 0.0; var time = 0.0
        for ((li, l) in legs.withIndex()) {
            val start = if (li == 0) 0 else 1   // the next leg starts where the last one ended
            for (i in start until l.lats.size) { lats.add(l.lats[i]); lons.add(l.lons[i]); se.add(l.segEdge[i]); sp.add(l.segPack[i]); vn.add(if (i == 0) -1 else l.vertexNode[i]) }
            len += l.lengthMeters; time += l.timeSeconds
        }
        return RouteResult(lats.toDoubleArray(), lons.toDoubleArray(), se.toArray(), sp.toArray(), len, time, vn.toArray())
    }

    private fun routeInner(from: Snap, to: Snap, profile: Profile, maxExplored: Int, minorRadius: Double): RouteResult? {
        if (from.pack == to.pack && from.edge == to.edge) {
            val f = graph(from.pack).edgeFlags(from.edge)
            if (to.t >= from.t || (f and F.ONEWAY) == 0) return buildSameEdge(from, to, profile)
        }
        gen++
        hopFrom.clear()
        val heap = MinHeap()
        val gTo = graph(to.pack); val rTo = routers[to.pack]
        val goalKeys = intArrayOf(key(to.pack, gTo.edgeFrom(to.edge)), key(to.pack, gTo.edgeTo(to.edge)))
        val toFlags = gTo.edgeFlags(to.edge)
        val goalExtra = doubleArrayOf(
            rTo.edgeCost(to.edge, profile, to.alongFrom),
            if (toFlags and F.ONEWAY != 0) -1.0 else rTo.edgeCost(to.edge, profile, to.alongTo)
        )
        val tLat = to.lat; val tLon = to.lon; val sLat = from.lat; val sLon = from.lon
        val vmax = Speeds.maxSpeed(profile) / Speeds.minWeight(profile)
        val prune = minorRadius > 0
        // what counts as "minor" far from the ends: dirt mode keeps tracks, everyone else keeps up to unclassified
        val pruneClass = if (profile == Profile.DIRT) 15 else 10

        fun relax(k: Int, cost: Double, par: Int, la: Double, lo: Double) {
            val r = routers[packOf(k)]; val n = nodeOf(k)
            r.ensureState()
            if (r.stamp[n] == gen && (r.closed[n] == 1 || cost >= r.gCost[n])) return
            r.stamp[n] = gen; r.gCost[n] = cost; r.parent[n] = par; r.closed[n] = 0
            heap.push(cost + Geo.fastDistance(la, lo, tLat, tLon) / vmax, k)
        }

        val gFrom = graph(from.pack); val rFrom = routers[from.pack]
        val fromFlags = gFrom.edgeFlags(from.edge)
        run { val n = gFrom.edgeTo(from.edge); relax(key(from.pack, n), rFrom.edgeCost(from.edge, profile, from.alongTo), -1, gFrom.nodeLat(n), gFrom.nodeLon(n)) }
        if (fromFlags and F.ONEWAY == 0) { val n = gFrom.edgeFrom(from.edge); relax(key(from.pack, n), rFrom.edgeCost(from.edge, profile, from.alongFrom), -2, gFrom.nodeLat(n), gFrom.nodeLon(n)) }

        var bestTotal = Double.MAX_VALUE; var bestGoal = -1; var bestGoalIdx = -1
        var explored = 0
        val multi = routers.size > 1
        while (!heap.isEmpty()) {
            if (heap.topKey() >= bestTotal) break
            val k = heap.pop()
            val p = packOf(k); val n = nodeOf(k); val r = routers[p]; val g = graph(p)
            if (r.stamp[n] == gen && r.closed[n] == 1) continue
            r.closed[n] = 1
            if (++explored > maxExplored) break
            val gn = r.gCost[n]
            for (gi in 0..1) if (goalKeys[gi] == k && goalExtra[gi] >= 0) {
                val tot = gn + goalExtra[gi]
                if (tot < bestTotal) { bestTotal = tot; bestGoal = k; bestGoalIdx = gi }
            }
            val la = g.nodeLat(n); val lo = g.nodeLon(n)
            val allowMinor = !prune || Geo.fastDistance(la, lo, tLat, tLon) < minorRadius || Geo.fastDistance(la, lo, sLat, sLon) < minorRadius
            val s = g.adjStart(n); val e = g.adjEnd(n)
            for (slot in s until e) {
                val he = g.adj(slot); val edge = he shr 1; val dir = he and 1
                if (!allowMinor && (g.edgeFlags(edge) and F.CLASS_MASK) > pruneClass) continue
                if (!r.canTraverse(edge, dir, profile)) continue
                val m = g.edgeOther(edge, n)
                val ng = gn + r.edgeCost(edge, profile, g.edgeLength(edge)) + (if (r.parent[n] >= 0 && (r.parent[n] shr 1) == edge) Speeds.uturnPenalty(profile) else 0.0)
                relax(key(p, m), ng, he, g.nodeLat(m), g.nodeLon(m))
            }
            // hop into neighbouring packs that cover this point
            if (multi) {
                val osm = g.osmId(n)
                for (q in routers.indices) {
                    if (q == p) continue
                    val gq = graph(q)
                    if (!gq.contains(la, lo)) continue
                    var m = if (osm != 0L) gq.nodeByOsmId(osm) else -1
                    var extra = 1.0
                    if (m < 0 && e - s == 1) {
                        // dead end at a clipped boundary: bridge to the nearest road of the other pack
                        val sn = routers[q].snap(q, la, lo, profile, 40.0)
                        if (sn != null) { m = if (sn.t < 0.5) gq.edgeFrom(sn.edge) else gq.edgeTo(sn.edge); extra = 5.0 }
                    }
                    if (m < 0) continue
                    val kq = key(q, m)
                    val rq = routers[q]
                    rq.ensureState()
                    val cost = gn + extra
                    if (rq.stamp[m] == gen && (rq.closed[m] == 1 || cost >= rq.gCost[m])) continue
                    hopFrom[kq] = k
                    relax(kq, cost, HOP, gq.nodeLat(m), gq.nodeLon(m))
                }
            }
        }
        if (bestGoal < 0) return null
        return buildPath(from, to, bestGoal, bestGoalIdx, profile)
    }

    private fun buildSameEdge(from: Snap, to: Snap, profile: Profile): RouteResult {
        val g = graph(from.pack); val e = from.edge
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        val forward = to.t >= from.t
        val n = g.edgePointCount(e)
        lats.add(from.lat); lons.add(from.lon)
        var along = 0.0
        val tmin = minOf(from.alongFrom, to.alongFrom); val tmax = maxOf(from.alongFrom, to.alongFrom)
        val inner = ArrayList<DoubleArray>()
        var pLat = g.edgePointLat(e, 0); var pLon = g.edgePointLon(e, 0)
        for (k in 1 until n - 1) {
            val qLat = g.edgePointLat(e, k); val qLon = g.edgePointLon(e, k)
            along += Geo.fastDistance(pLat, pLon, qLat, qLon)
            if (along > tmin && along < tmax) inner.add(doubleArrayOf(qLat, qLon))
            pLat = qLat; pLon = qLon
        }
        if (!forward) inner.reverse()
        for (p in inner) { lats.add(p[0]); lons.add(p[1]) }
        lats.add(to.lat); lons.add(to.lon)
        val len = kotlin.math.abs(to.alongFrom - from.alongFrom)
        val sp = Speeds.speed(profile, g.edgeFlags(e))
        val segEdge = IntArray(lats.size) { if (it == 0) -1 else e }
        return RouteResult(lats.toDoubleArray(), lons.toDoubleArray(), segEdge, IntArray(lats.size) { from.pack }, len, len / sp, IntArray(lats.size) { -1 })
    }

    private fun buildPath(from: Snap, to: Snap, goalKey: Int, goalIdx: Int, profile: Profile): RouteResult {
        // walk parents back to a seed; steps are (key, half-edge or HOP)
        val keys = IntList(); val pars = IntList()
        var k = goalKey
        while (true) {
            keys.add(k)
            val r = routers[packOf(k)]; val n = nodeOf(k)
            val par = r.parent[n]
            pars.add(par)
            if (par == -1 || par == -2) break
            k = if (par == HOP) hopFrom[k]!! else key(packOf(k), graph(packOf(k)).edgeOther(par shr 1, n))
        }
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val segE = IntList(); val segP = IntList(); val vn = IntList()
        lats.add(from.lat); lons.add(from.lon); segE.add(-1); segP.add(from.pack); vn.add(-1)
        var length = 0.0; var time = 0.0
        run {
            val firstNode = nodeOf(keys[keys.size - 1])
            val fe = from.edge; val g = graph(from.pack)
            val towardTo = firstNode == g.edgeTo(fe)
            appendPartial(from.pack, fe, from.alongFrom, towardTo, true, lats, lons, segE, segP, vn)
            vn[vn.size - 1] = firstNode
            val l = if (towardTo) from.alongTo else from.alongFrom
            length += l; time += l / Speeds.speed(profile, g.edgeFlags(fe))
        }
        for (i in keys.size - 2 downTo 0) {
            val par = pars[i]; val kk = keys[i]; val p = packOf(kk); val endNode = nodeOf(kk)
            if (par == HOP) {
                // same physical junction in the next pack: no geometry, just switch packs
                val g = graph(p)
                val dlat = g.nodeLat(endNode); val dlon = g.nodeLon(endNode)
                if (Geo.fastDistance(lats[lats.size - 1], lons[lons.size - 1], dlat, dlon) > 1.0) {
                    lats.add(dlat); lons.add(dlon); segE.add(segE[segE.size - 1]); segP.add(segP[segP.size - 1]); vn.add(-1)
                }
                vn[vn.size - 1] = -1
                continue
            }
            val edge = par shr 1; val dir = par and 1
            val g = graph(p)
            appendEdge(p, edge, dir == 0, lats, lons, segE, segP, vn)
            vn[vn.size - 1] = endNode
            val l = g.edgeLength(edge)
            length += l; time += l / Speeds.speed(profile, g.edgeFlags(edge))
        }
        run {
            val te = to.edge; val g = graph(to.pack)
            val fromFromNode = goalIdx == 0
            appendArrival(to.pack, te, to, fromFromNode, lats, lons, segE, segP, vn)
            val l = if (fromFromNode) to.alongFrom else to.alongTo
            length += l; time += l / Speeds.speed(profile, g.edgeFlags(te))
        }
        return RouteResult(lats.toDoubleArray(), lons.toDoubleArray(), segE.toArray(), segP.toArray(), length, time, vn.toArray())
    }

    private fun appendEdge(p: Int, e: Int, forward: Boolean, lats: ArrayList<Double>, lons: ArrayList<Double>, segE: IntList, segP: IntList, vn: IntList) {
        val g = graph(p); val n = g.edgePointCount(e)
        if (forward) for (k in 1 until n) { lats.add(g.edgePointLat(e, k)); lons.add(g.edgePointLon(e, k)); segE.add(e); segP.add(p); vn.add(-1) }
        else for (k in n - 2 downTo 0) { lats.add(g.edgePointLat(e, k)); lons.add(g.edgePointLon(e, k)); segE.add(e); segP.add(p); vn.add(-1) }
    }

    /**
     * The last piece: from the node we reached ([fromFromNode] = the edge's from-node, else its to-node)
     * along the edge to the snapped destination, ending exactly at the snap. Interior points are the
     * ones between that node and the snap, in travel order.
     */
    private fun appendArrival(p: Int, e: Int, to: Snap, fromFromNode: Boolean,
                              lats: ArrayList<Double>, lons: ArrayList<Double>, segE: IntList, segP: IntList, vn: IntList) {
        val g = graph(p); val n = g.edgePointCount(e)
        val pts = ArrayList<DoubleArray>()
        var along = 0.0
        var pLat = g.edgePointLat(e, 0); var pLon = g.edgePointLon(e, 0)
        for (k in 1 until n - 1) {
            val qLat = g.edgePointLat(e, k); val qLon = g.edgePointLon(e, k)
            along += Geo.fastDistance(pLat, pLon, qLat, qLon)
            val take = if (fromFromNode) along < to.alongFrom else along > to.alongFrom
            if (take) pts.add(doubleArrayOf(qLat, qLon))
            pLat = qLat; pLon = qLon
        }
        if (!fromFromNode) pts.reverse()
        for (pt in pts) { lats.add(pt[0]); lons.add(pt[1]); segE.add(e); segP.add(p); vn.add(-1) }
        lats.add(to.lat); lons.add(to.lon); segE.add(e); segP.add(p); vn.add(-1)
    }

    private fun appendPartial(p: Int, e: Int, alongFrom: Double, towardTo: Boolean, leaving: Boolean,
                              lats: ArrayList<Double>, lons: ArrayList<Double>, segE: IntList, segP: IntList, vn: IntList) {
        val g = graph(p); val n = g.edgePointCount(e)
        val pts = ArrayList<DoubleArray>()
        var along = 0.0
        var pLat = g.edgePointLat(e, 0); var pLon = g.edgePointLon(e, 0)
        for (k in 1 until n - 1) {
            val qLat = g.edgePointLat(e, k); val qLon = g.edgePointLon(e, k)
            along += Geo.fastDistance(pLat, pLon, qLat, qLon)
            val take = if (towardTo) along > alongFrom else along < alongFrom
            if (take) pts.add(doubleArrayOf(qLat, qLon))
            pLat = qLat; pLon = qLon
        }
        if (!towardTo) pts.reverse()
        if (leaving) {
            for (pt in pts) { lats.add(pt[0]); lons.add(pt[1]); segE.add(e); segP.add(p); vn.add(-1) }
            val end = if (towardTo) g.edgeTo(e) else g.edgeFrom(e)
            lats.add(g.nodeLat(end)); lons.add(g.nodeLon(end)); segE.add(e); segP.add(p); vn.add(end)
        } else {
            pts.reverse()
            for (pt in pts) { lats.add(pt[0]); lons.add(pt[1]); segE.add(e); segP.add(p); vn.add(-1) }
        }
    }
}

/** Binary min-heap keyed by double, carrying an int payload. */
class MinHeap(cap: Int = 1024) {
    private var keys = DoubleArray(cap); private var vals = IntArray(cap); private var n = 0
    fun isEmpty() = n == 0
    fun topKey() = keys[0]
    fun push(k: Double, v: Int) {
        if (n == keys.size) { keys = keys.copyOf(n * 2); vals = vals.copyOf(n * 2) }
        var i = n++; keys[i] = k; vals[i] = v
        while (i > 0) { val p = (i - 1) shr 1; if (keys[p] <= keys[i]) break; swap(i, p); i = p }
    }
    fun pop(): Int {
        val r = vals[0]; n--
        if (n > 0) { keys[0] = keys[n]; vals[0] = vals[n]; var i = 0
            while (true) { val l = 2 * i + 1; val rr = l + 1; var m = i
                if (l < n && keys[l] < keys[m]) m = l
                if (rr < n && keys[rr] < keys[m]) m = rr
                if (m == i) break; swap(i, m); i = m } }
        return r
    }
    private fun swap(a: Int, b: Int) { val k = keys[a]; keys[a] = keys[b]; keys[b] = k; val v = vals[a]; vals[a] = vals[b]; vals[b] = v }
}
