package fold.app

import android.content.Context
import android.net.Uri
import fold.core.Graph
import fold.core.PackManifest
import fold.core.Router
import fold.core.SearchFile
import fold.core.MultiRouter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** One installed region: tiles for the map, a graph for routing, an index for search. */
class Pack(val dir: File, val manifest: PackManifest) {
    val tiles: File get() = File(dir, PackManifest.TILES)
    @Volatile var router: Router? = null
    @Volatile private var search: SearchFile? = null
    val name get() = manifest.name
    val sizeBytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized fun loadRouter(): Router? {
        router?.let { return it }
        return try { Router(Graph.open(File(dir, PackManifest.GRAPH))).also { router = it } } catch (e: Throwable) { android.util.Log.w("Fold", "graph open failed for $name", e); null }
    }
    @Synchronized fun loadSearch(): SearchFile? {
        search?.let { return it }
        return try { SearchFile.open(File(dir, PackManifest.SEARCH)) } catch (e: Throwable) { null }?.also { search = it }
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
        val routers = cur.mapNotNull { p -> p.loadRouter() ?: run { errs.add("Routing data for ${p.name} could not be opened — re-import that pack"); null } }
        routerErrors = errs
        return MultiRouter(routers).also { multi = it; multiFor = cur }
    }
    /** Pack index (as used by the router) of an installed pack, or -1. */
    fun routerPacks(): List<Pack> = multiFor.filter { it.router != null }

    fun scan(): List<Pack> {
        val found = ArrayList<Pack>()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            try {
                val mf = File(d, PackManifest.FILE)
                if (!mf.exists()) return@forEach
                val m = PackManifest.parse(mf.readText()) ?: return@forEach
                if (PackManifest.REQUIRED.all { File(d, it).exists() }) found.add(Pack(d, m))
            } catch (_: Throwable) {}
        }
        packs = found
        return found
    }

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
                if (manifest.format != PackManifest.FORMAT) throw IllegalStateException(if (manifest.format < PackManifest.FORMAT) "This pack was made with an older packer — rebuild it with the current fold-packer.jar" else "Pack format ${manifest.format} is newer than this app")
                for (req in PackManifest.REQUIRED) if (zip.getEntry(req) == null) throw IllegalStateException("Pack is missing $req")
                val safe = manifest.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
                // same name = same region: the new pack replaces the old one once it is fully unpacked
                val dir = File(root, safe)
                val replacing = dir.exists()
                val staging = File(root, ".staging-$safe").apply { deleteRecursively(); mkdirs() }
                val total = PackManifest.REQUIRED.sumOf { zip.getEntry(it).size.coerceAtLeast(0L) }
                val free = root.usableSpace
                if (free < total + (64L shl 20)) throw IllegalStateException("Not enough space: pack needs ${total / (1024 * 1024)} MB, phone has ${free / (1024 * 1024)} MB free")
                var done = 0L
                val buf = ByteArray(1 shl 20)
                for (req in PackManifest.REQUIRED) {
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
                if (replacing) {
                    // drop any open handles on the old files, then swap directories
                    packs.firstOrNull { it.dir == dir }?.let { it.router = null }
                    multi = null; multiFor = emptyList()
                    val old = File(root, ".old-$safe").apply { deleteRecursively() }
                    if (!dir.renameTo(old)) throw IllegalStateException("Could not replace the old pack")
                    old.deleteRecursively()
                }
                if (!staging.renameTo(dir)) throw IllegalStateException("Could not move pack into place")
                scan()
                return packs.first { it.dir == dir }
            }
        } finally {
            try { pfd?.close() } catch (_: Throwable) {}
            tmp.delete()
            root.listFiles()?.filter { it.name.startsWith(".staging-") || it.name.startsWith(".old-") }?.forEach { it.deleteRecursively() }
        }
    }

    fun delete(p: Pack) { p.dir.deleteRecursively(); scan() }

    fun packFor(lat: Double, lon: Double): Pack? = packs.firstOrNull { it.contains(lat, lon) }
}
