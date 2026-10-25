package fold.packer

import fold.core.Graph
import fold.core.GraphFormat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Overview road tiles: the small roads and trails that the Protomaps basemap only carries from zoom
 * 12–13, drawn from the routing graph at zooms 8–11 so a rider zoomed out still sees where the
 * two-tracks and trails go. One vector layer "roads" with kind = minor | service | track | path and
 * an `unpaved` flag; written as roads.pmtiles next to the basemap tiles.
 */
object Overview {
    private const val EXTENT = 4096
    private const val BUFFER = 64
    private val KIND = arrayOf("minor", "service", "track", "path")

    /** Which kind an edge class is at a zoom, or -1 to leave it out. */
    private fun kindAt(cls: Int, z: Int): Int = when (cls) {
        10, 11, 15 -> if (z >= 9) 0 else -1   // unclassified, residential, road
        12 -> if (z >= 10) 0 else -1          // living_street
        13 -> if (z >= 11) 1 else -1          // service
        14 -> 2                               // track
        16, 17, 20 -> if (z >= 10) 3 else -1  // cycleway, path, bridleway
        else -> -1
    }

    fun build(g: Graph, out: File, log: (String) -> Unit, minZ: Int = 8, maxZ: Int = 11) {
        val tiles = HashMap<Long, ByteArrayOutputStream>()      // tileId -> encoded features
        val counts = HashMap<Long, Int>()
        val pts = DoubleArray(2 * 512)
        for (z in minZ..maxZ) {
            val n = 1L shl z
            var features = 0
            val tol = if (z >= 11) 1.5 else 2.5
            for (e in 0 until g.edgeCount) {
                val kind = kindAt(g.edgeClass(e), z); if (kind < 0) continue
                val unpaved = g.edgeFlags(e) and GraphFormat.UNPAVED != 0
                val pc = g.edgePointCount(e); if (pc < 2) continue
                val m = min(pc, 512)
                // world coordinates in tile units at this zoom
                var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                for (k in 0 until m) {
                    val i = if (m == pc) k else (k.toLong() * (pc - 1) / (m - 1)).toInt()
                    val x = lonToWorld(g.edgePointLon(e, i), z); val y = latToWorld(g.edgePointLat(e, i), z)
                    pts[2 * k] = x; pts[2 * k + 1] = y
                    if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                }
                val tx0 = floor((minX - BUFFER) / EXTENT).toLong().coerceIn(0, n - 1); val tx1 = floor((maxX + BUFFER) / EXTENT).toLong().coerceIn(0, n - 1)
                val ty0 = floor((minY - BUFFER) / EXTENT).toLong().coerceIn(0, n - 1); val ty1 = floor((maxY + BUFFER) / EXTENT).toLong().coerceIn(0, n - 1)
                for (ty in ty0..ty1) for (tx in tx0..tx1) {
                    val ox = tx * EXTENT.toDouble(); val oy = ty * EXTENT.toDouble()
                    val parts = clip(pts, m, ox - BUFFER, oy - BUFFER, ox + EXTENT + BUFFER, oy + EXTENT + BUFFER)
                    if (parts.isEmpty()) continue
                    val id = PmTiles.zxyToTileId(z, tx, ty)
                    val buf = tiles.getOrPut(id) { ByteArrayOutputStream() }
                    for (part in parts) {
                        val simp = simplify(part, tol)
                        if (simp.size < 4) continue
                        val feat = encodeFeature(simp, ox, oy, kind, unpaved) ?: continue
                        Pb.bytes(buf, 2, feat); counts[id] = (counts[id] ?: 0) + 1; features++
                    }
                }
            }
            log("overview z$z: $features features")
        }
        // wrap each tile's features in a layer, gzip, and write the archive
        val encoded = HashMap<Long, ByteArray>(tiles.size)
        for ((id, feats) in tiles) {
            val layer = ByteArrayOutputStream()
            Pb.varint(layer, 15, 2)                       // version
            Pb.string(layer, 1, "roads")
            layer.write(feats.toByteArray())              // features (already tagged field 2)
            Pb.string(layer, 3, "kind"); Pb.string(layer, 3, "unpaved")
            for (k in KIND) { val v = ByteArrayOutputStream(); Pb.string(v, 1, k); Pb.bytes(layer, 4, v.toByteArray()) }
            run { val v = ByteArrayOutputStream(); Pb.varint(v, 7, 1); Pb.bytes(layer, 4, v.toByteArray()) }   // true
            Pb.varint(layer, 5, EXTENT.toLong())
            val tile = ByteArrayOutputStream(); Pb.bytes(tile, 3, layer.toByteArray())
            encoded[id] = PmTiles.gzip(tile.toByteArray())
        }
        val meta = """{"name":"roads overview","format":"pbf","vector_layers":[{"id":"roads","fields":{"kind":"String","unpaved":"Boolean"}}]}"""
        PmTiles.writeArchive(out, encoded, minZ, maxZ, g.minLon, g.minLat, g.maxLon, g.maxLat, tileType = 1, tileCompression = 2, metadataJson = meta)
        log("overview: wrote ${out.name} (${out.length() / 1024} KB, ${encoded.size} tiles)")
    }

    private fun lonToWorld(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1L shl z) * EXTENT
    private fun latToWorld(lat: Double, z: Int): Double { val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511)); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1L shl z) * EXTENT }

    /** Clips a polyline (x0,y0,x1,y1,…) to a rectangle; returns the pieces inside. */
    private fun clip(p: DoubleArray, n: Int, x0: Double, y0: Double, x1: Double, y1: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(); var cur = ArrayList<Double>()
        fun inside(x: Double, y: Double) = x >= x0 && x <= x1 && y >= y0 && y <= y1
        for (i in 0 until n - 1) {
            var ax = p[2 * i]; var ay = p[2 * i + 1]; var bx = p[2 * i + 2]; var by = p[2 * i + 3]
            // Liang–Barsky
            var t0 = 0.0; var t1 = 1.0; val dx = bx - ax; val dy = by - ay; var ok = true
            for (edge in 0 until 4) {
                val pk = when (edge) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
                val qk = when (edge) { 0 -> ax - x0; 1 -> x1 - ax; 2 -> ay - y0; else -> y1 - ay }
                if (pk == 0.0) { if (qk < 0) { ok = false; break } }
                else { val r = qk / pk; if (pk < 0) { if (r > t1) { ok = false; break }; if (r > t0) t0 = r } else { if (r < t0) { ok = false; break }; if (r < t1) t1 = r } }
            }
            if (!ok) { if (cur.size >= 4) out.add(cur.toDoubleArray()); cur = ArrayList(); continue }
            val sx = ax + t0 * dx; val sy = ay + t0 * dy; val ex = ax + t1 * dx; val ey = ay + t1 * dy
            if (cur.isEmpty() || t0 > 0.0) { if (cur.size >= 4) out.add(cur.toDoubleArray()); cur = ArrayList(); cur.add(sx); cur.add(sy) }
            cur.add(ex); cur.add(ey)
            if (t1 < 1.0) { if (cur.size >= 4) out.add(cur.toDoubleArray()); cur = ArrayList() }
        }
        if (cur.size >= 4) out.add(cur.toDoubleArray())
        return out
    }

    /** Douglas–Peucker on a flat x,y array. */
    private fun simplify(p: DoubleArray, tol: Double): DoubleArray {
        val n = p.size / 2; if (n <= 2) return p
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var bestD = tol * tol
            val ax = p[2 * a]; val ay = p[2 * a + 1]; val bx = p[2 * b]; val by = p[2 * b + 1]
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            for (i in a + 1 until b) {
                val px = p[2 * i]; val py = p[2 * i + 1]
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + t * dx; val qy = ay + t * dy; val d = (px - qx) * (px - qx) + (py - qy) * (py - qy)
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) { keep[best] = true; stack.add(intArrayOf(a, best)); stack.add(intArrayOf(best, b)) }
        }
        val out = ArrayList<Double>(); for (i in 0 until n) if (keep[i]) { out.add(p[2 * i]); out.add(p[2 * i + 1]) }
        return out.toDoubleArray()
    }

    private fun encodeFeature(p: DoubleArray, ox: Double, oy: Double, kind: Int, unpaved: Boolean): ByteArray? {
        val n = p.size / 2
        val geom = ArrayList<Int>(2 * n + 2)
        var px = 0; var py = 0; var written = 0
        for (i in 0 until n) {
            val x = Math.round(p[2 * i] - ox).toInt(); val y = Math.round(p[2 * i + 1] - oy).toInt()
            if (i > 0 && x == px && y == py) continue
            geom.add(zigzag(x - px)); geom.add(zigzag(y - py)); px = x; py = y; written++
        }
        if (written < 2) return null
        val f = ByteArrayOutputStream()
        val tags = ByteArrayOutputStream(); Pb.rawVarint(tags, 0); Pb.rawVarint(tags, kind.toLong()); if (unpaved) { Pb.rawVarint(tags, 1); Pb.rawVarint(tags, 4) }
        Pb.bytes(f, 2, tags.toByteArray())
        Pb.varint(f, 3, 2)                                 // LINESTRING
        val g = ByteArrayOutputStream()
        Pb.rawVarint(g, (1 or (1 shl 3)).toLong()); Pb.rawVarint(g, geom[0].toLong()); Pb.rawVarint(g, geom[1].toLong())      // MoveTo ×1
        Pb.rawVarint(g, (2 or ((written - 1) shl 3)).toLong()); for (i in 2 until geom.size) Pb.rawVarint(g, geom[i].toLong())  // LineTo ×(n-1)
        Pb.bytes(f, 4, g.toByteArray())
        return f.toByteArray()
    }
    private fun zigzag(v: Int) = (v shl 1) xor (v shr 31)
}

/** Just enough protobuf. */
object Pb {
    fun rawVarint(o: ByteArrayOutputStream, v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    fun varint(o: ByteArrayOutputStream, field: Int, v: Long) { rawVarint(o, ((field shl 3) or 0).toLong()); rawVarint(o, v) }
    fun bytes(o: ByteArrayOutputStream, field: Int, b: ByteArray) { rawVarint(o, ((field shl 3) or 2).toLong()); rawVarint(o, b.size.toLong()); o.write(b) }
    fun string(o: ByteArrayOutputStream, field: Int, s: String) = bytes(o, field, s.toByteArray(Charsets.UTF_8))
}
