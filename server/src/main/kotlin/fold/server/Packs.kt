package fold.server

import fold.core.Graph
import fold.core.Router
import fold.core.SearchFile
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One roads pack as the catalog describes it. */
class CatalogPack(val id: String, val name: String, val region: String, val url: String, val built: String, val bytes: Long, val bbox: DoubleArray /* minLon,minLat,maxLon,maxLat */) {
    fun intersects(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) = !(bbox[2] < minLon || bbox[0] > maxLon || bbox[3] < minLat || bbox[1] > maxLat)
}

class PoiEntry(val lat: Double, val lon: Double, val prom: Int, val cat: String, val kind: String, val name: String, val extra: String)
class Loaded(val pack: CatalogPack, val graph: Graph, val router: Router, val search: SearchFile?, val pois: List<PoiEntry>? = null) { @Volatile var lastUsed = System.currentTimeMillis() }

/**
 * The packs this instance can route on. Graphs are fetched on first use straight out of the .mappack
 * zip (stored entries, so a byte range of the archive is the file) and kept on local disk until the
 * disk fills, oldest first. Nothing is loaded that no request has asked for: an instance that only
 * ever routes in Montana holds Montana.
 */
open class PackSet(private val catalogUrl: String, private val dir: File, private val minFreeBytes: Long = 1L shl 30) {
    // the map of loaded packs and the catalog are guarded by short locks; a download never holds either,
    // so /warm and /health answer while a pack is on its way and two requests for one pack share one download
    private val lock = Any()
    private var catalog: List<CatalogPack> = emptyList()
    private var catalogAt = 0L
    private val loaded = LinkedHashMap<String, Loaded>()
    private val inflight = HashMap<String, java.util.concurrent.CompletableFuture<Loaded>>()

    init { dir.mkdirs() }

    open fun catalog(): List<CatalogPack> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (catalog.isEmpty() || now - catalogAt > 60 * 60 * 1000L) {
                try { catalog = fetchCatalog(); catalogAt = now } catch (e: Throwable) { if (catalog.isEmpty()) throw e; System.err.println("catalog refresh failed: ${e.javaClass.simpleName}") }
            }
            return catalog
        }
    }

    fun loadedIds(): List<String> = synchronized(lock) { loaded.keys.toList() }
    /** every loaded pack's router, for freeing search memory after a request */
    fun loadedRouters(): List<Router> = synchronized(lock) { loaded.values.map { it.router } }
    fun isLoaded(id: String): Boolean = synchronized(lock) { loaded.containsKey(id) }
    fun loadingIds(): List<String> = synchronized(lock) { inflight.keys.toList() }

    // two warmers (a cold pack is a 20 s download; one should not hold up its neighbour), a short bounded queue, and
    // one job per pack: a map wandered over the same state a hundred times schedules its pack once
    private class WarmJob(val id: String, val body: () -> Unit) : Runnable { override fun run() = body() }
    private val scheduled = java.util.Collections.synchronizedSet(HashSet<String>())
    private val warmer = java.util.concurrent.ThreadPoolExecutor(2, 2, 60, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(8),
        { r -> Thread(r, "warm").apply { isDaemon = true } },
        { r, _ -> if (r is WarmJob) scheduled.remove(r.id) })    // queue full: forget it; the next warm may ask again
    /** Starts loading the packs for a box in the background, so a route asked for in a moment finds them ready. Returns what is loaded and what is on its way. */
    fun warm(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Pair<List<CatalogPack>, List<CatalogPack>> {
        val want = catalog().filter { it.intersects(minLon, minLat, maxLon, maxLat) }
        val ready = want.filter { isLoaded(it.id) }
        val pending = want.filter { !isLoaded(it.id) }
        for (p in pending) {
            if (!scheduled.add(p.id)) continue
            warmer.execute(WarmJob(p.id) { try { ensure(p) } catch (e: Throwable) { System.err.println("warm ${p.id}: ${e.javaClass.simpleName}") } finally { scheduled.remove(p.id) } })
        }
        return ready to pending
    }
    fun warmQueued(): Int = scheduled.size

    /** Packs whose bbox meets the given one, loaded (downloading if needed). */
    fun forBbox(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): List<Loaded> {
        val want = catalog().filter { it.intersects(minLon, minLat, maxLon, maxLat) }
        return want.map { ensure(it) }
    }
    /** The same, but only what is already in memory: nothing is downloaded, and the rest is warmed in the background. */
    fun forBboxLoaded(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): List<Loaded> {
        val want = catalog().filter { it.intersects(minLon, minLat, maxLon, maxLat) }
        warm(minLon, minLat, maxLon, maxLat)
        return synchronized(lock) { want.mapNotNull { loaded[it.id]?.also { l -> l.lastUsed = System.currentTimeMillis() } } }
    }

    fun ensure(p: CatalogPack): Loaded {
        val mine: java.util.concurrent.CompletableFuture<Loaded>
        var theirs: java.util.concurrent.CompletableFuture<Loaded>? = null
        synchronized(lock) {
            loaded[p.id]?.let { if (it.pack.built == p.built && it.pack.bytes == p.bytes) { it.lastUsed = System.currentTimeMillis(); return it } else { loaded.remove(p.id) } }
            theirs = inflight[p.id]
            mine = java.util.concurrent.CompletableFuture(); if (theirs == null) inflight[p.id] = mine
        }
        theirs?.let { return it.get() }                    // someone else is fetching it: wait for theirs, outside the lock
        try {
            val l = load(p)
            synchronized(lock) { loaded[p.id] = l; inflight.remove(p.id) }
            mine.complete(l); return l
        } catch (e: Throwable) {
            synchronized(lock) { inflight.remove(p.id) }
            mine.completeExceptionally(e); throw e
        }
    }

    private fun load(p: CatalogPack): Loaded {
        val d = File(dir, p.id)
        val stamp = File(d, "built"); val graphFile = File(d, "graph.bin"); val searchFile = File(d, "search.tsv"); val poisFile = File(d, "pois.tsv")
        val fresh = stamp.exists() && stamp.readText().trim() == "${p.built}/${p.bytes}" && graphFile.exists()
        if (!fresh) {
            d.deleteRecursively(); d.mkdirs()
            synchronized(lock) { makeRoom(p.bytes / 4) }
            val t0 = System.currentTimeMillis()
            ZipRange(p.url).use { z ->
                z.extract("graph.bin", graphFile)
                try { z.extract("search.tsv", searchFile) } catch (e: Throwable) { System.err.println("${p.id}: no search file (${e.javaClass.simpleName})") }
                if (z.has("pois.tsv")) try { z.extract("pois.tsv", poisFile) } catch (e: Throwable) { System.err.println("${p.id}: no place index (${e.javaClass.simpleName})") }
            }
            stamp.writeText("${p.built}/${p.bytes}")
            System.err.println("loaded ${p.id}: ${graphFile.length() / 1_000_000} MB in ${(System.currentTimeMillis() - t0) / 1000} s")
        }
        val g = Graph.open(graphFile.path)
        val s = if (searchFile.exists()) try { SearchFile.open(searchFile.path) } catch (_: Throwable) { null } else null
        val pois = if (poisFile.exists()) try { poisFile.useLines { lines -> lines.mapNotNull { ln -> val t = ln.split('\t'); if (t.size < 6) null else PoiEntry(t[0].toInt() / 1e7, t[1].toInt() / 1e7, t[2].toIntOrNull() ?: 1, t[3], t[4], t[5], t.getOrElse(6) { "" }) }.toList() } } catch (_: Throwable) { null } else null
        return Loaded(p, g, Router(g), s, pois)
    }

    /** Drops the least recently used packs from memory and disk until there is room. */
    private fun makeRoom(need: Long) {
        while (dir.usableSpace < minFreeBytes + need) {
            val victim = loaded.values.minByOrNull { it.lastUsed }
            if (victim != null) { loaded.remove(victim.pack.id); File(dir, victim.pack.id).deleteRecursively(); System.err.println("evicted ${victim.pack.id}"); continue }
            // nothing in memory to drop: clear stale directories from an earlier life of this disk
            val stale = dir.listFiles()?.filter { it.isDirectory && !loaded.containsKey(it.name) }?.minByOrNull { it.lastModified() } ?: break
            stale.deleteRecursively()
        }
    }

    private fun fetchCatalog(): List<CatalogPack> {
        val text = Http.getText(catalogUrl)
        val root = Json.parse(text) as Map<*, *>
        val arr = root["packs"] as List<*>
        return arr.mapNotNull { o ->
            o as Map<*, *>
            if ((o["kind"] as? String ?: "roads") != "roads") return@mapNotNull null
            val bb = (o["bbox"] as? List<*>)?.map { (it as Double) }?.toDoubleArray() ?: return@mapNotNull null
            if (bb.size != 4) return@mapNotNull null
            val name = o["name"] as String
            CatalogPack(o["id"] as String, name, o["region"] as? String ?: name, o["url"] as String, o["built"] as? String ?: "", (o["bytes"] as? Double)?.toLong() ?: 0L, bb)
        }
    }
}

object Http {
    fun open(url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "roadbook-router/1")
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code != 200 && code != 206) { c.disconnect(); throw IllegalStateException("HTTP $code for $url") }
        return c
    }
    fun getText(url: String): String { val c = open(url); try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }
    fun getBytes(url: String, from: Long, toInclusive: Long): ByteArray {
        val c = open(url, "bytes=$from-$toInclusive")
        try { require(c.responseCode == 206) { "server ignored the range request" }; return c.inputStream.readBytes() } finally { c.disconnect() }
    }
}

/**
 * Reads single entries out of a remote zip by HTTP range requests. The .mappack format stores its
 * entries uncompressed, so an entry is a contiguous byte range of the archive.
 */
class ZipRange(private val url: String) : AutoCloseable {
    private class Entry(val name: String, val size: Long, val localHeader: Long)
    private val entries: Map<String, Entry>
    val totalSize: Long

    init {
        // the tail holds the end-of-central-directory record; 64 KB covers any comment we would write.
        // Suffix ranges (bytes=-N) are refused by some hosts, so learn the size first, then ask plainly.
        val probe = Http.open(url, "bytes=0-0")
        val cr: String?
        try { cr = probe.getHeaderField("Content-Range"); probe.inputStream.readBytes() } finally { probe.disconnect() }
        totalSize = cr?.substringAfter('/')?.toLongOrNull() ?: throw IllegalStateException("no Content-Range from $url")
        val tailLen = minOf(65536L, totalSize)
        val tail = Http.getBytes(url, totalSize - tailLen, totalSize - 1)
        val tailStart = totalSize - tail.size
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) if (tb.getInt(i) == 0x06054b50) { eocd = i; break }
        require(eocd >= 0) { "not a zip: $url" }
        var cdSize = (tb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL)
        var cdOff = (tb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL)
        if (cdOff == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            // zip64: the locator sits just before the EOCD and points at the zip64 EOCD record
            val loc = eocd - 20
            require(loc >= 0 && tb.getInt(loc) == 0x07064b50) { "zip64 locator missing" }
            val e64 = tb.getLong(loc + 8)
            val rec = if (e64 >= tailStart) tail.copyOfRange((e64 - tailStart).toInt(), (e64 - tailStart).toInt() + 56) else Http.getBytes(url, e64, e64 + 55)
            val rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
            require(rb.getInt(0) == 0x06064b50) { "zip64 EOCD missing" }
            cdSize = rb.getLong(40); cdOff = rb.getLong(48)
        }
        val cd = if (cdOff >= tailStart) tail.copyOfRange((cdOff - tailStart).toInt(), (cdOff - tailStart + cdSize).toInt()) else Http.getBytes(url, cdOff, cdOff + cdSize - 1)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val map = LinkedHashMap<String, Entry>()
        var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
            val method = b.getShort(p + 10).toInt() and 0xFFFF
            var csize = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var usize = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
            var lho = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            // zip64 extra field: 64-bit values, present only for the fields that overflowed, in this order
            var q = p + 46 + nameLen; val qEnd = q + extraLen
            while (q + 4 <= qEnd) {
                val id = b.getShort(q).toInt() and 0xFFFF; val len = b.getShort(q + 2).toInt() and 0xFFFF
                if (id == 1) { var r = q + 4
                    if (usize == 0xFFFFFFFFL) { usize = b.getLong(r); r += 8 }
                    if (csize == 0xFFFFFFFFL) { csize = b.getLong(r); r += 8 }
                    if (lho == 0xFFFFFFFFL) { lho = b.getLong(r) } }
                q += 4 + len
            }
            require(method == 0 || method == 8) { "unexpected zip method $method for $name" }
            if (method == 0) map[name] = Entry(name, usize, lho) else System.err.println("skipping compressed entry $name")
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = map
    }

    fun has(name: String) = entries.containsKey(name)

    /** Streams one stored entry into a file. */
    fun extract(name: String, out: File) {
        val e = entries[name] ?: throw IllegalArgumentException("$name not in archive")
        // local header: 30 fixed bytes, then the name and an extra field whose lengths only it knows
        val lh = Http.getBytes(url, e.localHeader, e.localHeader + 29)
        val lb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
        require(lb.getInt(0) == 0x04034b50) { "bad local header for $name" }
        val nameLen = lb.getShort(26).toInt() and 0xFFFF; val extraLen = lb.getShort(28).toInt() and 0xFFFF
        val dataStart = e.localHeader + 30 + nameLen + extraLen
        val tmp = File(out.path + ".part"); tmp.delete()
        var have = 0L; var attempts = 0
        while (have < e.size) {
            attempts++
            require(attempts <= 8) { "gave up fetching $name after $attempts tries" }
            try {
                val c = Http.open(url, "bytes=${dataStart + have}-${dataStart + e.size - 1}")
                try {
                    require(c.responseCode == 206) { "server ignored the range request" }
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.seek(have)
                        c.inputStream.use { ins -> val buf = ByteArray(1 shl 20); while (true) { val n = ins.read(buf); if (n < 0) break; raf.write(buf, 0, n); have += n } }
                    }
                } finally { c.disconnect() }
            } catch (ex: java.io.IOException) { System.err.println("$name: ${ex.message}, resuming at $have") }
        }
        require(have == e.size) { "$name: got $have of ${e.size} bytes" }
        out.delete(); require(tmp.renameTo(out)) { "cannot place $out" }
    }

    override fun close() {}
}
