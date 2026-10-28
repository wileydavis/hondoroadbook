package fold.app

import android.content.Context
import android.net.Uri
import fold.core.Graph
import fold.core.PackManifest
import fold.core.Place
import fold.core.Places
import fold.core.Router
import fold.core.SearchFile
import fold.core.MultiRouter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** One installed region: tiles for the map, a graph for routing, an index for search. */
class Pack(val dir: File, val manifest: PackManifest) {
    val isTerrain get() = manifest.isTerrain
    val tiles: File get() = File(dir, if (isTerrain) PackManifest.TERRAIN else PackManifest.TILES)
    /** roads.pmtiles, when the pack was built with it */
    val overview: File? get() = File(dir, PackManifest.OVERVIEW).takeIf { it.exists() }
    /** pois.tsv, when the pack was built with it and installed since the app kept it */
    val poisFile: File? get() = File(dir, PackManifest.POIS).takeIf { it.exists() }
    @Volatile private var placeList: List<Place>? = null
    /** the pack's places, parsed once; empty when the pack has none */
    fun loadPlaces(): List<Place> {
        placeList?.let { return it }
        val l = try { poisFile?.let { Places.parse(it.readText()) } ?: emptyList() } catch (_: Throwable) { emptyList() }
        placeList = l; return l
    }
    @Volatile var router: Router? = null
    @Volatile private var search: SearchFile? = null
    val name get() = manifest.name
    val sizeBytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized fun loadRouter(): Router? {
        if (isTerrain) return null
        router?.let { return it }
        return try { Router(Graph.open(File(dir, PackManifest.GRAPH).path)).also { router = it } } catch (e: Throwable) { android.util.Log.w("Fold", "graph open failed for $name", e); null }
    }
    @Synchronized fun loadSearch(): SearchFile? {
        if (isTerrain) return null
        search?.let { return it }
        return try { SearchFile.open(File(dir, PackManifest.SEARCH).path) } catch (e: Throwable) { null }?.also { search = it }
    }
    fun contains(lat: Double, lon: Double) = manifest.contains(lat, lon)
}

class PackStore(private val ctx: Context) {
    val root: File = File(ctx.filesDir, "packs").apply { mkdirs() }

    @Volatile var packs: List<Pack> = emptyList(); private set
    @Volatile private var multi: MultiRouter? = null
    @Volatile private var multiFor: List<Pack> = emptyList()

    /** One router over every installed pack's graph; packs whose graph fails to open are skipped. */
    @Volatile var routerErrors: List<String> = emptyList(); private set
    @Synchronized fun multiRouter(): MultiRouter {
        val cur = packs
        multi?.let { if (multiFor === cur) return it }
        val errs = ArrayList<String>()
        val routers = cur.filter { !it.isTerrain }.mapNotNull { p -> p.loadRouter() ?: run { errs.add("Routing data for ${p.name} could not be opened — re-download or re-import that pack"); null } }
        routerErrors = errs
        return MultiRouter(routers).also { multi = it; multiFor = cur }
    }
    /** Pack index (as used by the router) of an installed pack, or -1. */
    fun routerPacks(): List<Pack> = multiFor.filter { it.router != null }

    /** Packs on disk that failed validation this scan, with the reason; shown under MAPS, never routed. */
    @Volatile var broken: List<Pair<String, String>> = emptyList(); private set

    /** Lists installed packs. Also finishes any replacement a crash interrupted and validates every pack before it is used. */
    fun scan(): List<Pack> {
        recover()
        val found = ArrayList<Pack>(); val bad = ArrayList<Pair<String, String>>()
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name }?.forEach { d ->
            try {
                val mf = File(d, PackManifest.FILE)
                if (!mf.exists()) { bad.add(d.name to "no pack.txt"); return@forEach }
                val m = PackManifest.parse(mf.readText()) ?: run { bad.add(d.name to "unreadable pack.txt"); return@forEach }
                val missing = m.required.filter { !File(d, it).exists() }
                if (missing.isNotEmpty()) { bad.add(m.name to "missing ${missing.joinToString()}"); return@forEach }
                found.add(Pack(d, m))
            } catch (e: Throwable) { bad.add(d.name to (e.message ?: "unreadable")) }
        }
        packs = found; broken = bad
        return found
    }

    /**
     * A replacement goes old → .old-slug, staging → slug, delete .old-slug. If the process died between
     * the first two steps the pack is in .old-slug and nothing is at slug: put it back. Leftover staging
     * directories are incomplete imports and are removed.
     */
    private fun recover() {
        root.listFiles()?.forEach { d ->
            if (!d.isDirectory) return@forEach
            if (d.name.startsWith(".old-")) {
                val live = File(root, d.name.removePrefix(".old-"))
                if (!live.exists() || !File(live, PackManifest.FILE).exists()) { live.deleteRecursively(); d.renameTo(live) } else d.deleteRecursively()
            } else if (d.name.startsWith(".staging-")) d.deleteRecursively()
        }
    }

    /** Opens the routing graph once to prove the file is sound (header, sizes, sample edges); cheap, mmap only. */
    fun validate(p: Pack): String? = if (p.isTerrain) null else try { Graph.open(File(p.dir, PackManifest.GRAPH).path); null } catch (e: Throwable) { e.message ?: "unreadable graph" }

    /** Copies and unpacks a .mappack picked by the user. Returns the new pack or throws with a readable message. */
    fun import(uri: Uri, progress: (String) -> Unit): Pack {
        val tmp = File(root, ".import.zip")
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            // Fast path: open the picked file in place through its descriptor (no copy). Fallback: copy it.
            val zipFile: ZipFile = try {
                pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ZipFile(File("/proc/self/fd/${pfd!!.fd}"))
            } catch (_: Throwable) {
                pfd?.close(); pfd = null
                progress("Copying…")
                ctx.contentResolver.openInputStream(uri)?.use { ins -> FileOutputStream(tmp).use { out -> ins.copyTo(out, 1 shl 20) } }
                    ?: throw IllegalStateException("Could not open that file")
                ZipFile(tmp)
            }
            zipFile.use { zip ->
                val mfEntry = zip.getEntry(PackManifest.FILE) ?: throw IllegalStateException("Not a .mappack (no pack.txt)")
                val manifest = PackManifest.parse(zip.getInputStream(mfEntry).readBytes().toString(Charsets.UTF_8))
                    ?: throw IllegalStateException("Bad pack.txt")
                if (manifest.format !in PackManifest.MIN_FORMAT..PackManifest.FORMAT) throw IllegalStateException(if (manifest.format < PackManifest.MIN_FORMAT) "This pack was made with an older packer — download it again" else "This pack is newer than the app — update Roadbook from hondoroadbook.com")
                for (req in manifest.required) if (zip.getEntry(req) == null) throw IllegalStateException("Pack is missing $req")
                val safe = manifest.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
                // same name = same region: the new pack replaces the old one once it is fully unpacked
                val dir = File(root, safe)
                val replacing = dir.exists()
                val staging = File(root, ".staging-$safe").apply { deleteRecursively(); mkdirs() }
                val wanted = manifest.required + listOfNotNull(PackManifest.OVERVIEW.takeIf { zip.getEntry(it) != null && !manifest.isTerrain }, PackManifest.POIS.takeIf { zip.getEntry(it) != null && !manifest.isTerrain })
                val total = wanted.sumOf { zip.getEntry(it).size.coerceAtLeast(0L) }
                val free = root.usableSpace
                if (free < total + (64L shl 20)) throw IllegalStateException("Not enough space: pack needs ${total / (1024 * 1024)} MB, phone has ${free / (1024 * 1024)} MB free")
                var done = 0L
                val buf = ByteArray(1 shl 20)
                for (req in wanted) {
                    val e = zip.getEntry(req)
                    zip.getInputStream(e).use { ins -> FileOutputStream(File(staging, req)).use { out ->
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; progress("${manifest.name}: $pct%") }
                        }
                    } }
                }
                // the new pack must open before it is allowed to replace anything
                if (!manifest.isTerrain) { try { Graph.open(File(staging, PackManifest.GRAPH).path) } catch (e: Throwable) { throw IllegalStateException("This pack's routing data is damaged (${e.message}) — it was not installed") } }
                if (replacing) {
                    // drop any open handles on the old files, then swap: the old pack is kept until the new one is in place
                    packs.firstOrNull { it.dir == dir }?.let { it.router = null }
                    multi = null; multiFor = emptyList()
                    val old = File(root, ".old-$safe").apply { deleteRecursively() }
                    if (!dir.renameTo(old)) throw IllegalStateException("Could not replace the old pack")
                    if (!staging.renameTo(dir)) { old.renameTo(dir); throw IllegalStateException("Could not move pack into place; the previous version was kept") }
                    old.deleteRecursively()
                } else if (!staging.renameTo(dir)) throw IllegalStateException("Could not move pack into place")
                scan()
                return packs.first { it.dir == dir }
            }
        } finally {
            try { pfd?.close() } catch (_: Throwable) {}
            tmp.delete()
            root.listFiles()?.filter { it.name.startsWith(".staging-") }?.forEach { it.deleteRecursively() }
        }
    }

    fun delete(p: Pack) { p.dir.deleteRecursively(); scan() }

    /** The road pack covering a point (terrain packs draw but carry no roads). */
    fun packFor(lat: Double, lon: Double): Pack? = packs.firstOrNull { !it.isTerrain && it.contains(lat, lon) }
    val roadPacks: List<Pack> get() = packs.filter { !it.isTerrain }
}
