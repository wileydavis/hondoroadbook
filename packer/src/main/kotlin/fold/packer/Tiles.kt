package fold.packer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*

/**
 * Minimal PMTiles v3 reader/writer that extracts a bounding box from a remote (or local) archive
 * using HTTP range requests, so no Go CLI is needed. Tiles are copied byte-for-byte (still gzip-compressed).
 */
object PmTiles {
    class Entry(val tileId: Long, var offset: Long, val length: Int, val runLength: Int)

    class Header(val b: ByteArray) {
        private val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        init { require(b.size >= 127 && b[0] == 'P'.code.toByte() && b[1] == 'M'.code.toByte()) { "not a PMTiles archive" }; require(b[7].toInt() == 3) { "unsupported PMTiles version ${b[7]}" } }
        val rootOff get() = bb.getLong(8); val rootLen get() = bb.getLong(16)
        val metaOff get() = bb.getLong(24); val metaLen get() = bb.getLong(32)
        val leafOff get() = bb.getLong(40); val leafLen get() = bb.getLong(48)
        val tileOff get() = bb.getLong(56); val tileLen get() = bb.getLong(64)
        val internalCompression get() = b[97].toInt(); val tileCompression get() = b[98].toInt(); val tileType get() = b[99].toInt()
        val minZoom get() = b[100].toInt(); val maxZoom get() = b[101].toInt()
    }

    // ---- tile ids (Hilbert), ported from the reference implementation
    fun zxyToTileId(z: Int, x: Long, y: Long): Long {
        var acc = ((1L shl z) * (1L shl z) - 1) / 3
        var n = z - 1
        var tx = x; var ty = y
        var a = if (z == 0) 0L else 1L shl n
        while (a > 0) {
            val c = tx and a; val l = ty and a
            acc += ((3 * c) xor l) * (1L shl n)
            // rotate
            if (l == 0L) {
                if (c != 0L) { tx = a - 1 - tx; ty = a - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            n--; a = a shr 1
        }
        return acc
    }

    // ---- varints
    private fun readVarint(b: ByteArray, pos: IntArray): Long {
        var shift = 0; var result = 0L
        while (true) { val v = b[pos[0]++].toInt() and 0xFF; result = result or ((v and 0x7F).toLong() shl shift); if (v < 0x80) return result; shift += 7 }
    }
    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
        out.write(v.toInt())
    }

    fun deserialize(b: ByteArray): List<Entry> {
        val pos = IntArray(1)
        val n = readVarint(b, pos).toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += readVarint(b, pos); ids[i] = last }
        val runs = IntArray(n) { readVarint(b, pos).toInt() }
        val lens = IntArray(n) { readVarint(b, pos).toInt() }
        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val v = readVarint(b, pos)
            val off = if (v == 0L && i > 0) out[i - 1].offset + out[i - 1].length else v - 1
            out.add(Entry(ids[i], off, lens[i], runs[i]))
        }
        return out
    }

    fun serialize(entries: List<Entry>): ByteArray {
        val o = ByteArrayOutputStream()
        writeVarint(o, entries.size.toLong())
        var last = 0L
        for (e in entries) { writeVarint(o, e.tileId - last); last = e.tileId }
        for (e in entries) writeVarint(o, e.runLength.toLong())
        for (e in entries) writeVarint(o, e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) writeVarint(o, 0) else writeVarint(o, e.offset + 1)
        }
        return o.toByteArray()
    }

    fun gzip(b: ByteArray): ByteArray { val o = ByteArrayOutputStream(); GZIPOutputStream(o).use { it.write(b) }; return o.toByteArray() }
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }
    private fun decompress(b: ByteArray, method: Int) = when (method) { 0, 1 -> b; 2 -> gunzip(b); else -> throw IllegalStateException("unsupported internal compression $method") }

    /** Byte-range source: remote http(s) or a local file. */
    interface Source { fun read(offset: Long, length: Long): ByteArray }

    class HttpSource(private val url: String) : Source {
        private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(30)).build()
        override fun read(offset: Long, length: Long): ByteArray {
            var attempt = 0
            while (true) {
                try {
                    val req = HttpRequest.newBuilder(URI(url)).header("Range", "bytes=$offset-${offset + length - 1}").timeout(java.time.Duration.ofMinutes(5)).GET().build()
                    val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    if (resp.statusCode() != 206) throw IllegalStateException("HTTP ${resp.statusCode()} for range $offset+$length (server must support range requests)")
                    val body = resp.body()
                    if (body.size.toLong() != length) throw IllegalStateException("short read ${body.size} of $length")
                    return body
                } catch (e: Exception) {
                    if (++attempt >= 6) throw e
                    Thread.sleep(1500L * attempt)
                }
            }
        }
    }

    class FileSource(file: File) : Source {
        private val raf = RandomAccessFile(file, "r")
        override fun read(offset: Long, length: Long): ByteArray { val b = ByteArray(length.toInt()); synchronized(raf) { raf.seek(offset); raf.readFully(b) }; return b }
    }

    class Progress(val done: AtomicLong = AtomicLong(), val bytes: AtomicLong = AtomicLong())

    /**
     * Extract every tile intersecting [minLon,minLat,maxLon,maxLat] for zooms min..max into [out].
     */
    fun extract(src: Source, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, out: File, log: (String) -> Unit, zoomCap: Int = 15) {
        val head = src.read(0, 16384)
        val h = Header(head.copyOf(127))
        require(h.tileType == 1 || h.tileType == 6) { "source is not a vector tile archive" }
        val maxZ = min(h.maxZoom, zoomCap); val minZ = h.minZoom
        val rootBytes = if (h.rootOff + h.rootLen <= head.size) head.copyOfRange(h.rootOff.toInt(), (h.rootOff + h.rootLen).toInt()) else src.read(h.rootOff, h.rootLen)
        val root = deserialize(decompress(rootBytes, h.internalCompression))
        val meta = decompress(src.read(h.metaOff, h.metaLen), h.internalCompression)

        // wanted tile ids
        val wanted = ArrayList<Long>()
        for (z in minZ..maxZ) {
            val n = 1L shl z
            val x0 = lonToX(minLon, z).coerceIn(0, n - 1); val x1 = lonToX(maxLon, z).coerceIn(0, n - 1)
            val y0 = latToY(maxLat, z).coerceIn(0, n - 1); val y1 = latToY(minLat, z).coerceIn(0, n - 1)
            for (x in x0..x1) for (y in y0..y1) wanted.add(zxyToTileId(z, x, y))
        }
        val wantedArr = wanted.toLongArray(); wantedArr.sort()
        log("tiles: z$minZ–z$maxZ, ${wantedArr.size} tile addresses in bbox")

        // walk root -> leaves, keep matching entries
        val found = HashMap<Long, Entry>() // tileId -> entry
        fun consider(e: Entry) {
            if (e.runLength == 0) return
            // ids covered: [tileId, tileId+runLength)
            var i = lowerBound(wantedArr, e.tileId)
            while (i < wantedArr.size && wantedArr[i] < e.tileId + e.runLength) { found[wantedArr[i]] = e; i++ }
        }
        var leaves = 0
        for ((idx, e) in root.withIndex()) {
            if (e.runLength == 0) {
                val nextId = if (idx + 1 < root.size) root[idx + 1].tileId else Long.MAX_VALUE
                // any wanted id in [e.tileId, nextId)?
                val i = lowerBound(wantedArr, e.tileId)
                if (i < wantedArr.size && wantedArr[i] < nextId) {
                    val leaf = deserialize(decompress(src.read(h.leafOff + e.offset, e.length.toLong()), h.internalCompression))
                    leaves++
                    for (le in leaf) consider(le)
                }
            } else consider(e)
        }
        log("tiles: ${found.size} tiles present (read $leaves leaf directories)")

        // unique contents, sorted by source offset, merged into ranges
        val unique = found.values.distinctBy { it.offset }.sortedBy { it.offset }
        val totalBytes = unique.sumOf { it.length.toLong() }
        log("tiles: ${unique.size} unique tiles, ${totalBytes / 1024 / 1024} MB to fetch")
        val ranges = ArrayList<LongArray>() // [start, end)
        for (u in unique) {
            val s = h.tileOff + u.offset; val e = s + u.length
            if (ranges.isNotEmpty() && s - ranges.last()[1] <= 512 * 1024 && e - ranges.last()[0] <= 16L * 1024 * 1024) ranges.last()[1] = e else ranges.add(longArrayOf(s, e))
        }
        log("tiles: ${ranges.size} range requests")

        // fetch ranges in parallel into a scratch file, remember where each unique tile landed
        val scratch = File(out.absolutePath + ".tiles.tmp")
        val newOffset = HashMap<Long, Long>() // source offset -> offset in scratch
        val raf = RandomAccessFile(scratch, "rw"); raf.setLength(0)
        val pool = Executors.newFixedThreadPool(8)
        val progress = Progress()
        var cursor = 0L
        val placements = ArrayList<Pair<LongArray, Long>>() // range -> scratch offset
        for (r in ranges) { placements.add(r to cursor); cursor += (r[1] - r[0]) }
        raf.setLength(cursor)
        val t0 = System.currentTimeMillis()
        // every fetch is a future: a failed range fails the whole extraction instead of leaving a hole of zero bytes
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val futures = placements.map { (r, dst) -> pool.submit {
            if (failed.get() != null) return@submit
            val data = src.read(r[0], r[1] - r[0])
            if (data.size.toLong() != r[1] - r[0]) throw java.io.IOException("short read: got ${data.size} of ${r[1] - r[0]} bytes at ${r[0]}")
            synchronized(raf) { raf.seek(dst); raf.write(data) }
            val d = progress.done.incrementAndGet(); progress.bytes.addAndGet(data.size.toLong())
            if (d % 50 == 0L || d == ranges.size.toLong()) log("tiles: $d/${ranges.size} ranges, ${progress.bytes.get() / 1024 / 1024} MB (${(System.currentTimeMillis() - t0) / 1000}s)")
        } }
        pool.shutdown()
        try {
            for (f in futures) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { failed.compareAndSet(null, e.cause ?: e) }
        } finally { pool.shutdownNow() }
        failed.get()?.let { raf.close(); scratch.delete(); throw java.io.IOException("tile fetch failed: ${it.message}", it) }
        require(progress.done.get() == ranges.size.toLong()) { "tile fetch incomplete: ${progress.done.get()} of ${ranges.size} ranges" }
        // map each unique tile to its scratch offset
        var ri = 0
        for (u in unique) {
            val s = h.tileOff + u.offset
            while (ri < placements.size && placements[ri].first[1] <= s) ri++
            val (range, dst) = placements[ri]
            newOffset[u.offset] = dst + (s - range[0])
        }

        // build output entries in tile-id order, run-length compressing identical consecutive contents
        val ids = found.keys.toLongArray(); ids.sort()
        val outEntries = ArrayList<Entry>()
        val outOffsetOfSrc = HashMap<Long, Long>()
        var tileCursor = 0L
        // we write tile data in order of first use so the archive is clustered
        val writeOrder = ArrayList<Entry>()
        for (id in ids) {
            val e = found[id]!!
            var off = outOffsetOfSrc[e.offset]
            if (off == null) { off = tileCursor; outOffsetOfSrc[e.offset] = off; tileCursor += e.length; writeOrder.add(e) }
            val lastE = outEntries.lastOrNull()
            if (lastE != null && lastE.offset == off && lastE.tileId + lastE.runLength == id) outEntries[outEntries.size - 1] = Entry(lastE.tileId, lastE.offset, lastE.length, lastE.runLength + 1)
            else outEntries.add(Entry(id, off, e.length, 1))
        }
        val (rootDir, leafDirs) = buildDirectories(outEntries)
        val metaGz = gzip(meta)

        // assemble: header(127) | root | metadata | leaves | tiles
        val rootOff = 127L; val metaOff = rootOff + rootDir.size; val leafOff = metaOff + metaGz.size; val tileOff = leafOff + leafDirs.size
        RandomAccessFile(out, "rw").use { o ->
            o.setLength(0)
            o.write(header(h, rootOff, rootDir.size.toLong(), metaOff, metaGz.size.toLong(), leafOff, leafDirs.size.toLong(), tileOff, tileCursor,
                ids.size.toLong(), outEntries.size.toLong(), writeOrder.size.toLong(), minZ, maxZ, minLon, minLat, maxLon, maxLat))
            o.write(rootDir); o.write(metaGz); o.write(leafDirs)
            val buf = ByteArray(4 shl 20)
            for (e in writeOrder) {
                val so = newOffset[e.offset]!!
                synchronized(raf) { raf.seek(so); raf.readFully(buf, 0, e.length) }
                o.write(buf, 0, e.length)
            }
        }
        raf.close(); scratch.delete()
        log("tiles: wrote ${out.name} (${out.length() / 1024 / 1024} MB, ${ids.size} tiles)")
        verify(out, ids.size, h.tileCompression, log)
    }

    /** Reopens a written archive and checks that every addressed tile is readable and looks like a tile. */
    fun verify(out: File, expectedTiles: Int, tileCompression: Int, log: (String) -> Unit) {
        val fs = FileSource(out)
        val head = fs.read(0, 127); val h = Header(head)
        val root = deserialize(decompress(fs.read(h.rootOff, h.rootLen), h.internalCompression))
        var addressed = 0L; var checked = 0
        val raf = RandomAccessFile(out, "r")
        try {
            fun checkTile(e: Entry) {
                if (e.length <= 0) throw IllegalStateException("tile ${e.tileId} has no bytes")
                if (checked < 4000 || checked % 97 == 0) {
                    val b = ByteArray(min(2, e.length)); raf.seek(h.tileOff + e.offset); raf.readFully(b)
                    if (tileCompression == 2 && (b[0].toInt() and 0xFF != 0x1f || b[1].toInt() and 0xFF != 0x8b)) throw IllegalStateException("tile ${e.tileId} is not gzip data (${b[0]},${b[1]}): the download was incomplete")
                }
                checked++
            }
            for (r in root) {
                if (r.runLength == 0) {       // leaf directory
                    val leaf = deserialize(decompress(fs.read(h.leafOff + r.offset, r.length.toLong()), h.internalCompression))
                    for (e in leaf) { addressed += e.runLength; checkTile(e) }
                } else { addressed += r.runLength; checkTile(r) }
            }
        } finally { raf.close() }
        if (addressed != expectedTiles.toLong()) throw IllegalStateException("archive addresses $addressed tiles, expected $expectedTiles")
        log("tiles: verified $addressed tiles")
    }

    /** Finds the newest daily Protomaps planet build reachable from here (tries today backwards). */
    fun resolveProtomapsBuild(log: (String) -> Unit): String {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        var day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        repeat(21) {
            val url = "https://build.protomaps.com/" + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".pmtiles"
            try {
                val req = HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-6").timeout(java.time.Duration.ofSeconds(30)).GET().build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
                if (resp.statusCode() == 206 && String(resp.body()) == "PMTiles") { log("tiles: using $url"); return url }
            } catch (_: Exception) {}
            day = day.minusDays(1)
        }
        throw IllegalStateException("Could not find a Protomaps daily build; pass --tiles-url or --tiles")
    }

    private fun lowerBound(a: LongArray, v: Long): Int { var lo = 0; var hi = a.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }; return lo }
    private fun lonToX(lon: Double, z: Int): Long = floor((lon + 180.0) / 360.0 * (1L shl z)).toLong()
    private fun latToY(lat: Double, z: Int): Long { val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511)); return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1L shl z)).toLong() }

    /** Root directory must fit in the first 16 KB; otherwise spill into leaf directories. Returns (rootGz, leavesConcat). */
    private fun buildDirectories(entries: List<Entry>): Pair<ByteArray, ByteArray> {
        val rootGz = gzip(serialize(entries))
        if (rootGz.size <= 16384 - 127) return rootGz to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream(); val rootEntries = ArrayList<Entry>()
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, min(entries.size, i + leafSize))
                val gz = gzip(serialize(chunk))
                rootEntries.add(Entry(chunk[0].tileId, leaves.size().toLong(), gz.size, 0))
                leaves.write(gz)
                i += leafSize
            }
            val root = gzip(serialize(rootEntries))
            if (root.size <= 16384 - 127) return root to leaves.toByteArray()
            leafSize *= 2
        }
    }

    /** Writes a complete archive from in-memory tiles (ids → encoded, already compressed as [tileCompression] says). */
    fun writeArchive(out: File, tiles: Map<Long, ByteArray>, minZ: Int, maxZ: Int, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, tileType: Int, tileCompression: Int, metadataJson: String) {
        val ids = tiles.keys.sorted()
        val entries = ArrayList<Entry>(ids.size)
        val order = ArrayList<Long>(ids.size)
        var cursor = 0L
        val seen = HashMap<String, Long>()     // identical tiles stored once
        for (id in ids) {
            val data = tiles[id]!!
            val key = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest(data))
            val off = seen[key] ?: run { seen[key] = cursor; order.add(id); val o = cursor; cursor += data.size; o }
            val last = entries.lastOrNull()
            if (last != null && last.offset == off && last.tileId + last.runLength == id) entries[entries.size - 1] = Entry(last.tileId, last.offset, last.length, last.runLength + 1)
            else entries.add(Entry(id, off, data.size, 1))
        }
        val (rootDir, leafDirs) = buildDirectories(entries)
        val metaGz = gzip(metadataJson.toByteArray())
        val rootOff = 127L; val metaOff = rootOff + rootDir.size; val leafOff = metaOff + metaGz.size; val tileOff = leafOff + leafDirs.size
        RandomAccessFile(out, "rw").use { o ->
            o.setLength(0)
            o.write(headerBytes(tileCompression, tileType, rootOff, rootDir.size.toLong(), metaOff, metaGz.size.toLong(), leafOff, leafDirs.size.toLong(), tileOff, cursor,
                ids.size.toLong(), entries.size.toLong(), order.size.toLong(), minZ, maxZ, minLon, minLat, maxLon, maxLat))
            o.write(rootDir); o.write(metaGz); o.write(leafDirs)
            for (id in order) o.write(tiles[id]!!)
        }
    }

    private fun header(src: Header, rootOff: Long, rootLen: Long, metaOff: Long, metaLen: Long, leafOff: Long, leafLen: Long, tileOff: Long, tileLen: Long,
                       addressed: Long, entries: Long, contents: Long, minZ: Int, maxZ: Int, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): ByteArray =
        headerBytes(src.tileCompression, src.tileType, rootOff, rootLen, metaOff, metaLen, leafOff, leafLen, tileOff, tileLen, addressed, entries, contents, minZ, maxZ, minLon, minLat, maxLon, maxLat)

    private fun headerBytes(tileCompression: Int, tileType: Int, rootOff: Long, rootLen: Long, metaOff: Long, metaLen: Long, leafOff: Long, leafLen: Long, tileOff: Long, tileLen: Long,
                       addressed: Long, entries: Long, contents: Long, minZ: Int, maxZ: Int, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): ByteArray {
        val b = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray()); b.put(3)
        b.putLong(rootOff); b.putLong(rootLen); b.putLong(metaOff); b.putLong(metaLen); b.putLong(leafOff); b.putLong(leafLen); b.putLong(tileOff); b.putLong(tileLen)
        b.putLong(addressed); b.putLong(entries); b.putLong(contents)
        b.put(1) // clustered
        b.put(2) // internal compression gzip
        b.put(tileCompression.toByte()); b.put(tileType.toByte())
        b.put(minZ.toByte()); b.put(maxZ.toByte())
        b.putInt((minLon * 1e7).roundToInt()); b.putInt((minLat * 1e7).roundToInt()); b.putInt((maxLon * 1e7).roundToInt()); b.putInt((maxLat * 1e7).roundToInt())
        b.put(min(maxZ, 10).toByte()); b.putInt(((minLon + maxLon) / 2 * 1e7).roundToInt()); b.putInt(((minLat + maxLat) / 2 * 1e7).roundToInt())
        return b.array()
    }
}
