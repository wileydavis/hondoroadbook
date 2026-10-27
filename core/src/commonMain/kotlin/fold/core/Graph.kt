package fold.core

import kotlin.math.floor

/**
 * Compact road graph, memory-mappable. All multi-byte values little-endian.
 *
 * Layout (byte offsets computed from the header):
 *   header 64 B
 *   nodes      nodeCount * 8        (latE7 i32, lonE7 i32)
 *   adjStart   (nodeCount+1) * 4    CSR offsets into adj
 *   adj        2*edgeCount * 4      value = edge<<1 | dir  (dir 0: from->to, 1: to->from)
 *   edges      edgeCount * 24       from u32, to u32, lengthCm u32, flags u16, geomCount u16, nameIdx u32, geomStart u32
 *   geom       geomPointCount * 8   intermediate points (latE7, lonE7), excluding endpoints
 *   nameOff    (nameCount+1) * 4
 *   nameBytes  nameBytes
 *   gridStart  (cols*rows+1) * 4
 *   gridEntries gridEntryCount * 4  edge indices
 */
object GraphFormat {
    const val MAGIC = "FOLDGRPH"
    /** 3: access evidence by transport mode (PRIVATE for motor vehicles, PRIVATE_BIKE for bicycles); a reader for 2 would route private roads as public, so the version moved */
    const val VERSION = 3
    const val MIN_VERSION = 2
    const val HEADER = 64
    const val EDGE_REC = 24
    const val CELL_DEG = 0.02

    // flag bits
    const val CLASS_MASK = 0x1F
    const val ONEWAY = 1 shl 5
    const val CAR = 1 shl 6
    const val BIKE = 1 shl 7
    const val ROUNDABOUT = 1 shl 8
    const val UNPAVED = 1 shl 9
    const val FOOT = 1 shl 10
    /** OpenStreetMap says the road is private to motor vehicles (motorcar / motor_vehicle / vehicle / access = private), or it passes a locked or private gate: avoided while any public way exists, then routable under a heavy penalty, shown as posted private */
    const val PRIVATE = 1 shl 11
    /** the same for bicycles (bicycle / vehicle / access = private); motor_vehicle=private with bicycle=yes sets only PRIVATE */
    const val PRIVATE_BIKE = 1 shl 12
    fun privateFor(bike: Boolean) = if (bike) PRIVATE_BIKE else PRIVATE

    val CLASSES = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
        "living_street", "service", "track", "road", "cycleway", "path", "footway", "pedestrian",
        "bridleway", "steps"
    )
    fun classIndex(highway: String): Int = CLASSES.indexOf(highway)
}

/**
 * Random-access little-endian bytes behind the graph and the search index. A pack's graph is hundreds of
 * megabytes, so the platforms map the file rather than read it: chunked ByteBuffers on the JVM and Android
 * ([mapFile] in jvmCommon), mmap on iOS. All graph sections are 4-byte aligned, so a value never straddles
 * a chunk. [ArrayBytes] wraps a ByteArray for tests and small indexes.
 */
interface Bytes {
    val size: Long
    fun i32(off: Long): Int
    fun u16(off: Long): Int
    fun u8(off: Long): Int
    fun i64(off: Long): Long
    fun get(off: Long, dst: ByteArray)
}

/** Opens a file as [Bytes] without reading it whole: memory-mapped on every platform. */
expect fun mapFile(path: String): Bytes

class ArrayBytes(private val a: ByteArray) : Bytes {
    override val size: Long get() = a.size.toLong()
    override fun u8(off: Long): Int = a[off.toInt()].toInt() and 0xFF
    override fun u16(off: Long): Int { val i = off.toInt(); return (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8) }
    override fun i32(off: Long): Int { val i = off.toInt(); return (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8) or ((a[i + 2].toInt() and 0xFF) shl 16) or ((a[i + 3].toInt() and 0xFF) shl 24) }
    override fun i64(off: Long): Long = (i32(off).toLong() and 0xFFFFFFFFL) or (i32(off + 4).toLong() shl 32)
    override fun get(off: Long, dst: ByteArray) { require(off >= 0 && off + dst.size <= size) { "read past end of file (${off + dst.size} > $size)" }; a.copyInto(dst, 0, off.toInt(), off.toInt() + dst.size) }
}

class Graph private constructor(private val buf: Bytes) {
    val nodeCount: Int
    val edgeCount: Int
    val geomPointCount: Int
    val nameCount: Int
    val minLat: Double; val minLon: Double; val maxLat: Double; val maxLon: Double
    val gridCols: Int; val gridRows: Int
    private val gridEntryCount: Int
    private val nameBytesLen: Int

    private val offNodes: Long
    private val offAdjStart: Long
    private val offAdj: Long
    private val offEdges: Long
    private val offGeom: Long
    private val offNameOff: Long
    private val offNameBytes: Long
    private val offGridStart: Long
    private val offGridEntries: Long
    private val offOsmIds: Long
    private val offOsmNodes: Long

    init {
        require(buf.size >= GraphFormat.HEADER) { "graph truncated (${buf.size} bytes)" }
        val magic = ByteArray(8); buf.get(0, magic)
        require(magic.decodeToString() == GraphFormat.MAGIC) { "not a Fold graph" }
        // 2 is read as well: it carries no PRIVATE_BIKE bit, and its private ways were never bicycle-routable, so the meaning is unchanged
        require(buf.i32(8) in GraphFormat.MIN_VERSION..GraphFormat.VERSION) { "unsupported graph version" }
        nodeCount = buf.i32(12L); edgeCount = buf.i32(16L); geomPointCount = buf.i32(20L); nameCount = buf.i32(24L)
        minLat = buf.i32(28L) / 1e7; minLon = buf.i32(32L) / 1e7; maxLat = buf.i32(36L) / 1e7; maxLon = buf.i32(40L) / 1e7
        gridCols = buf.i32(44L); gridRows = buf.i32(48L); gridEntryCount = buf.i32(52L); nameBytesLen = buf.i32(56L)
        // every count must be non-negative and every section must fit; a damaged file is rejected here, never looped on
        require(nodeCount > 0 && edgeCount > 0 && geomPointCount >= 0 && nameCount > 0 && gridCols > 0 && gridRows > 0 && gridEntryCount >= 0 && nameBytesLen >= 0) { "graph header counts are invalid" }
        require(gridCols.toLong() * gridRows <= 50_000_000L) { "graph grid is implausibly large" }
        require(minLat in -90.0..90.0 && maxLat in -90.0..90.0 && minLon in -180.0..180.0 && maxLon in -180.0..180.0 && minLat <= maxLat && minLon <= maxLon) { "graph bbox is invalid" }
        offNodes = GraphFormat.HEADER.toLong()
        offAdjStart = offNodes + nodeCount.toLong() * 8
        offAdj = offAdjStart + (nodeCount + 1).toLong() * 4
        offEdges = offAdj + 2L * edgeCount * 4
        offGeom = offEdges + edgeCount.toLong() * GraphFormat.EDGE_REC
        offNameOff = offGeom + geomPointCount.toLong() * 8
        offNameBytes = offNameOff + (nameCount + 1).toLong() * 4
        offGridStart = offNameBytes + nameBytesLen
        offGridEntries = offGridStart + (gridCols.toLong() * gridRows + 1) * 4
        val gridEnd = offGridEntries + gridEntryCount.toLong() * 4
        offOsmIds = (gridEnd + 7) / 8 * 8
        offOsmNodes = offOsmIds + nodeCount.toLong() * 8
        val reverseOff = offOsmNodes + nodeCount.toLong() * 4 + ((nodeCount.toLong() * 4) % 8).let { if (it == 0L) 0L else 8 - it }
        require(reverseOff + nodeCount.toLong() * 8 <= buf.size) { "graph truncated" }
        // spot checks that catch a file whose header is intact but whose body is garbage
        require(adjStart(0) == 0 && adjEnd(nodeCount - 1) == 2 * edgeCount) { "graph adjacency table is inconsistent" }
        for (e in longArrayOf(0L, (edgeCount - 1).toLong(), (edgeCount / 2).toLong())) {
            val ei = e.toInt()
            require(edgeFrom(ei) in 0 until nodeCount && edgeTo(ei) in 0 until nodeCount) { "graph edge $ei points outside the node table" }
            require(edgeGeomStart(ei) + edgeGeomCount(ei) <= geomPointCount) { "graph edge $ei geometry is out of range" }
            require(edgeNameIdx(ei) in 0 until nameCount) { "graph edge $ei name is out of range" }
        }
    }

    /** Graph node for an OpenStreetMap node id, or -1. Binary search over the sorted on-disk index. */
    fun nodeByOsmId(id: Long): Int {
        var lo = 0; var hi = nodeCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.i64(offOsmIds + mid.toLong() * 8)
            if (v < id) lo = mid + 1 else if (v > id) hi = mid - 1 else return buf.i32(offOsmNodes + mid.toLong() * 4)
        }
        return -1
    }
    /** OSM id of a graph node (via the reverse table stored after the sorted index). */
    fun osmId(node: Int): Long = buf.i64(offOsmNodes + nodeCount.toLong() * 4 + ((nodeCount.toLong() * 4) % 8).let { if (it == 0L) 0L else 8 - it } + node.toLong() * 8)
    /** Number of adjacent half-edges. */
    fun degree(n: Int): Int = adjEnd(n) - adjStart(n)

    private fun i32(off: Long): Int = buf.i32(off)
    private fun u16(off: Long): Int = buf.u16(off)

    fun nodeLat(n: Int): Double = i32(offNodes + n.toLong() * 8) / 1e7
    fun nodeLon(n: Int): Double = i32(offNodes + n.toLong() * 8 + 4) / 1e7

    fun adjStart(n: Int): Int = i32(offAdjStart + n.toLong() * 4)
    fun adjEnd(n: Int): Int = i32(offAdjStart + (n + 1).toLong() * 4)
    /** Half-edge value at adjacency slot. */
    fun adj(slot: Int): Int = i32(offAdj + slot.toLong() * 4)

    fun edgeFrom(e: Int): Int = i32(offEdges + e.toLong() * GraphFormat.EDGE_REC)
    fun edgeTo(e: Int): Int = i32(offEdges + e.toLong() * GraphFormat.EDGE_REC + 4)
    /** Length in meters. */
    fun edgeLength(e: Int): Double = (i32(offEdges + e.toLong() * GraphFormat.EDGE_REC + 8).toLong() and 0xFFFFFFFFL) / 100.0
    fun edgeFlags(e: Int): Int = u16(offEdges + e.toLong() * GraphFormat.EDGE_REC + 12)
    fun edgeGeomCount(e: Int): Int = u16(offEdges + e.toLong() * GraphFormat.EDGE_REC + 14)
    fun edgeNameIdx(e: Int): Int = i32(offEdges + e.toLong() * GraphFormat.EDGE_REC + 16)
    fun edgeGeomStart(e: Int): Int = i32(offEdges + e.toLong() * GraphFormat.EDGE_REC + 20)
    fun edgeClass(e: Int): Int = edgeFlags(e) and GraphFormat.CLASS_MASK

    /** Other end of edge e when leaving node n. */
    fun edgeOther(e: Int, n: Int): Int { val f = edgeFrom(e); return if (f == n) edgeTo(e) else f }

    /** Number of points of edge e including both endpoints. */
    fun edgePointCount(e: Int): Int = edgeGeomCount(e) + 2

    /** k-th point of edge e in from->to order (0 = from node). */
    fun edgePointLat(e: Int, k: Int): Double {
        val gc = edgeGeomCount(e)
        return when {
            k == 0 -> nodeLat(edgeFrom(e))
            k == gc + 1 -> nodeLat(edgeTo(e))
            else -> i32(offGeom + (edgeGeomStart(e).toLong() + (k - 1)) * 8) / 1e7
        }
    }
    fun edgePointLon(e: Int, k: Int): Double {
        val gc = edgeGeomCount(e)
        return when {
            k == 0 -> nodeLon(edgeFrom(e))
            k == gc + 1 -> nodeLon(edgeTo(e))
            else -> i32(offGeom + (edgeGeomStart(e).toLong() + (k - 1)) * 8 + 4) / 1e7
        }
    }

    fun name(idx: Int): String {
        if (idx <= 0 || idx >= nameCount) return ""
        val s = i32(offNameOff + idx.toLong() * 4); val e = i32(offNameOff + (idx + 1).toLong() * 4)
        val arr = ByteArray(e - s)
        buf.get(offNameBytes + s, arr)
        return arr.decodeToString()
    }
    fun edgeName(e: Int): String = name(edgeNameIdx(e))

    fun cellCol(lon: Double): Int = floor((lon - minLon) / GraphFormat.CELL_DEG).toInt()
    fun cellRow(lat: Double): Int = floor((lat - minLat) / GraphFormat.CELL_DEG).toInt()
    fun cellEdgesStart(col: Int, row: Int): Int = i32(offGridStart + (row.toLong() * gridCols + col) * 4)
    fun cellEdgesEnd(col: Int, row: Int): Int = i32(offGridStart + (row.toLong() * gridCols + col + 1) * 4)
    fun cellEdge(slot: Int): Int = i32(offGridEntries + slot.toLong() * 4)

    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        fun open(path: String): Graph = Graph(mapFile(path))
        fun fromBytes(bytes: ByteArray): Graph = Graph(ArrayBytes(bytes))
    }
}

/** Builds a graph file from in-memory arrays. Used by the packer and tests. */
class GraphWriter {
    val nodeLat = IntList(); val nodeLon = IntList(); val nodeOsm = LongList()
    val edgeFrom = IntList(); val edgeTo = IntList(); val edgeLenCm = IntList(); val edgeFlags = IntList(); val edgeName = IntList()
    val edgeGeomStart = IntList(); val edgeGeomCount = IntList()
    val geomLat = IntList(); val geomLon = IntList()
    val names = ArrayList<String>().apply { add("") }
    private val nameIndex = HashMap<String, Int>().apply { put("", 0) }

    fun addNode(latE7: Int, lonE7: Int, osmId: Long = 0L): Int { nodeLat.add(latE7); nodeLon.add(lonE7); nodeOsm.add(osmId); return nodeLat.size - 1 }
    fun nameId(name: String): Int = nameIndex.getOrPut(name) { names.add(name); names.size - 1 }
    fun addEdge(from: Int, to: Int, lengthCm: Int, flags: Int, nameIdx: Int, geomLatE7: IntArray, geomLonE7: IntArray, geomN: Int): Int {
        edgeFrom.add(from); edgeTo.add(to); edgeLenCm.add(lengthCm); edgeFlags.add(flags); edgeName.add(nameIdx)
        edgeGeomStart.add(geomLat.size); edgeGeomCount.add(geomN)
        for (i in 0 until geomN) { geomLat.add(geomLatE7[i]); geomLon.add(geomLonE7[i]) }
        return edgeFrom.size - 1
    }

    /** Serialises the graph to any [Sink] — a file stream on the JVM (the packer), a ByteArray in tests. */
    fun write(d: Sink) {
        val n = nodeLat.size; val m = edgeFrom.size
        require(m > 0 && n > 0) { "empty graph" }
        var minLat = Int.MAX_VALUE; var minLon = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE; var maxLon = Int.MIN_VALUE
        for (i in 0 until n) { val a = nodeLat[i]; val b = nodeLon[i]; if (a < minLat) minLat = a; if (a > maxLat) maxLat = a; if (b < minLon) minLon = b; if (b > maxLon) maxLon = b }
        for (i in 0 until geomLat.size) { val a = geomLat[i]; val b = geomLon[i]; if (a < minLat) minLat = a; if (a > maxLat) maxLat = a; if (b < minLon) minLon = b; if (b > maxLon) maxLon = b }
        // pad bbox a little so edge geometry never falls outside the grid
        minLat -= 10000; minLon -= 10000; maxLat += 10000; maxLon += 10000
        val cellE7 = (GraphFormat.CELL_DEG * 1e7).toInt()
        val cols = (maxLon - minLon) / cellE7 + 1
        val rows = (maxLat - minLat) / cellE7 + 1

        // adjacency CSR
        val deg = IntArray(n + 1)
        for (e in 0 until m) { deg[edgeFrom[e]]++; deg[edgeTo[e]]++ }
        val adjStart = IntArray(n + 1); for (i in 0 until n) adjStart[i + 1] = adjStart[i] + deg[i]
        val fill = adjStart.copyOf(); val adj = IntArray(2 * m)
        for (e in 0 until m) { adj[fill[edgeFrom[e]]++] = e shl 1; adj[fill[edgeTo[e]]++] = (e shl 1) or 1 }

        // grid: each edge into every cell its bbox covers
        val cellCount = cols.toLong() * rows
        require(cellCount < Int.MAX_VALUE / 2) { "region too large for grid" }
        val gcount = IntArray(cellCount.toInt() + 1)
        fun edgeCells(e: Int, fn: (Int) -> Unit) {
            var aLat = nodeLat[edgeFrom[e]]; var bLat = aLat; var aLon = nodeLon[edgeFrom[e]]; var bLon = aLon
            fun acc(la: Int, lo: Int) { if (la < aLat) aLat = la; if (la > bLat) bLat = la; if (lo < aLon) aLon = lo; if (lo > bLon) bLon = lo }
            acc(nodeLat[edgeTo[e]], nodeLon[edgeTo[e]])
            val gs = edgeGeomStart[e]; val gc = edgeGeomCount[e]
            for (k in 0 until gc) acc(geomLat[gs + k], geomLon[gs + k])
            val c0 = (aLon - minLon) / cellE7; val c1 = (bLon - minLon) / cellE7
            val r0 = (aLat - minLat) / cellE7; val r1 = (bLat - minLat) / cellE7
            if ((c1 - c0 + 1).toLong() * (r1 - r0 + 1) <= 256) {
                for (r in r0..r1) for (c in c0..c1) fn(r * cols + c)
            } else {
                // absurdly wide edge (crosses the antimeridian): index only the cells its points fall in
                var last = -1
                fun one(la: Int, lo: Int) { val cell = ((la - minLat) / cellE7) * cols + (lo - minLon) / cellE7; if (cell != last) { fn(cell); last = cell } }
                one(nodeLat[edgeFrom[e]], nodeLon[edgeFrom[e]])
                for (k in 0 until gc) one(geomLat[gs + k], geomLon[gs + k])
                one(nodeLat[edgeTo[e]], nodeLon[edgeTo[e]])
            }
        }
        for (e in 0 until m) edgeCells(e) { gcount[it]++ }
        var totalEntries = 0L; for (i in 0 until cellCount.toInt()) totalEntries += gcount[i]
        require(totalEntries < Int.MAX_VALUE) { "grid too large ($totalEntries entries); split the region" }
        val gridStart = IntArray(cellCount.toInt() + 1); for (i in 0 until cellCount.toInt()) gridStart[i + 1] = gridStart[i] + gcount[i]
        val gfill = gridStart.copyOf(); val gridEntries = IntArray(gridStart[cellCount.toInt()])
        for (e in 0 until m) edgeCells(e) { gridEntries[gfill[it]++] = e }

        val nameBytes = names.map { it.encodeToByteArray() }
        val nameRaw = nameBytes.sumOf { it.size }
        val namePad = (4 - nameRaw % 4) % 4
        val nameTotal = nameRaw + namePad

        fun i32(v: Int) = d.i32(v)
        fun i64(v: Long) = d.i64(v)
        fun u16(v: Int) = d.u16(v)
        d.bytes(GraphFormat.MAGIC.encodeToByteArray())
        i32(GraphFormat.VERSION); i32(n); i32(m); i32(geomLat.size); i32(names.size)
        i32(minLat); i32(minLon); i32(maxLat); i32(maxLon)
        i32(cols); i32(rows); i32(gridEntries.size); i32(nameTotal); i32(0)
        for (i in 0 until n) { i32(nodeLat[i]); i32(nodeLon[i]) }
        for (v in adjStart) i32(v)
        for (v in adj) i32(v)
        for (e in 0 until m) { i32(edgeFrom[e]); i32(edgeTo[e]); i32(edgeLenCm[e]); u16(edgeFlags[e]); u16(edgeGeomCount[e]); i32(edgeName[e]); i32(edgeGeomStart[e]) }
        for (i in 0 until geomLat.size) { i32(geomLat[i]); i32(geomLon[i]) }
        var off = 0; i32(0); for (b in nameBytes) { off += b.size; i32(off) }
        for (b in nameBytes) d.bytes(b)
        repeat(namePad) { d.u8(0) }
        for (v in gridStart) i32(v)
        for (v in gridEntries) i32(v)
        // OSM id index: sorted (id, node) pairs, 8-byte aligned, then the reverse table node -> id
        val gridEnd = GraphFormat.HEADER.toLong() + n.toLong() * 8 + (n + 1).toLong() * 4 + 2L * m * 4 + m.toLong() * GraphFormat.EDGE_REC + geomLat.size.toLong() * 8 + (names.size + 1).toLong() * 4 + nameTotal + (cellCount + 1) * 4 + gridEntries.size.toLong() * 4
        repeat(((8 - gridEnd % 8) % 8).toInt()) { d.u8(0) }
        require(n < (1 shl 25)) { "too many nodes for one pack (${n}); split the region" }
        val packed = LongArray(n) { (nodeOsm[it] shl 25) or it.toLong() }
        packed.sort()
        for (v in packed) i64(v ushr 25)
        for (v in packed) i32((v and 0x1FFFFFF).toInt())
        repeat((((n.toLong() * 4) % 8).let { if (it == 0L) 0L else 8 - it }).toInt()) { d.u8(0) }
        for (i in 0 until n) i64(nodeOsm[i])
        d.flush()
    }
}

/** Growable long array without boxing. */
class LongList(cap: Int = 1024) {
    private var a = LongArray(cap)
    var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(maxOf(16, a.size * 2)); a[size++] = v }
    operator fun get(i: Int): Long = a[i]
    fun toArray(): LongArray = a.copyOf(size)
}

/** Growable int array without boxing. */
class IntList(cap: Int = 1024) {
    private var a = IntArray(cap)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(maxOf(16, a.size * 2)); a[size++] = v }
    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun toArray(): IntArray = a.copyOf(size)
    fun removeLast(): Int = a[--size]
    fun clear() { size = 0 }
}

/** Where a graph is written: little-endian, in order. */
interface Sink {
    fun i32(v: Int); fun i64(v: Long); fun u16(v: Int); fun u8(v: Int); fun bytes(b: ByteArray)
    fun flush() {}
}

/** A growing ByteArray sink, for tests and small graphs. */
class ByteArraySink : Sink {
    private var a = ByteArray(1 shl 16); private var n = 0
    private fun room(k: Int) { if (n + k > a.size) a = a.copyOf(maxOf(a.size * 2, n + k)) }
    override fun u8(v: Int) { room(1); a[n++] = v.toByte() }
    override fun u16(v: Int) { room(2); a[n++] = v.toByte(); a[n++] = (v shr 8).toByte() }
    override fun i32(v: Int) { room(4); a[n++] = v.toByte(); a[n++] = (v shr 8).toByte(); a[n++] = (v shr 16).toByte(); a[n++] = (v shr 24).toByte() }
    override fun i64(v: Long) { i32(v.toInt()); i32((v shr 32).toInt()) }
    override fun bytes(b: ByteArray) { room(b.size); b.copyInto(a, n); n += b.size }
    fun toByteArray(): ByteArray = a.copyOf(n)
}
