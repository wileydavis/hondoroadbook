package fold.packer

import fold.core.PackManifest
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) { opts[a.substring(2)] = args.getOrNull(i + 1) ?: ""; i += 2 } else i++
    }
    val pbf = opts["pbf"]?.let { File(it) }
    val tiles = opts["tiles"]?.let { File(it) }
    val outFile = opts["out"]?.let { File(it) }
    if (pbf == null || !pbf.exists() || outFile == null) {
        System.err.println(
            """
            Fold packer — turns OpenStreetMap data into a .mappack for the Fold app.

            usage: java -jar fold-packer.jar --pbf REGION.osm.pbf --out REGION.mappack [--name "Montana"]

              --pbf        OpenStreetMap extract (e.g. from https://download.geofabrik.de)
              --out        output .mappack file
              --name       display name inside the app (default: file name)
              --tiles      use an existing PMTiles basemap instead of fetching one
              --tiles-url  PMTiles archive to extract from (default: newest Protomaps daily planet build)
              --bbox       minLon,minLat,maxLon,maxLat: cut a region out of a bigger extract (roads, places and tiles)
                           e.g. Baja from mexico-latest.osm.pbf: --bbox -118.5,22.8,-109.3,32.8
              --max-zoom   highest tile zoom to include (default 15 = full detail)
              --graph-only write graph.bin and search.tsv next to --out instead of a pack (for testing)
            """.trimIndent()
        )
        System.exit(2); return
    }
    val name = opts["name"] ?: outFile.nameWithoutExtension.replaceFirstChar { it.uppercase() }
    val t0 = System.currentTimeMillis()
    val log: (String) -> Unit = { println("[${(System.currentTimeMillis() - t0) / 1000}s] $it") }

    val work = File(outFile.absoluteFile.parentFile, ".fold-packer-tmp").apply { mkdirs() }
    val graph = File(work, PackManifest.GRAPH); val search = File(work, PackManifest.SEARCH)
    val clip = opts["bbox"]?.split(',')?.map { it.trim().toDouble() }?.takeIf { it.size == 4 }?.toDoubleArray()
    if (clip != null) log("clipping roads, places and tiles to bbox %.3f,%.3f,%.3f,%.3f".format(clip[0], clip[1], clip[2], clip[3]))
    val packer = Packer(pbf, log, clip)
    packer.build(graph, search)

    if (opts.containsKey("graph-only")) {
        graph.copyTo(File(outFile.parentFile ?: File("."), "graph.bin"), true)
        search.copyTo(File(outFile.parentFile ?: File("."), "search.tsv"), true)
        work.deleteRecursively(); log("done (graph only)"); return
    }
    val tilesFile: File = if (tiles != null && tiles.exists()) tiles else {
        val bbox = opts["bbox"]?.split(',')?.map { it.trim().toDouble() }
        val (minLon, minLat, maxLon, maxLat) = if (bbox != null && bbox.size == 4) bbox else listOf(packer.minLon - 0.05, packer.minLat - 0.05, packer.maxLon + 0.05, packer.maxLat + 0.05)
        val url = opts["tiles-url"] ?: PmTiles.resolveProtomapsBuild(log)
        val src: PmTiles.Source = if (url.startsWith("http")) PmTiles.HttpSource(url) else PmTiles.FileSource(File(url))
        val f = File(work, PackManifest.TILES)
        log("tiles: extracting bbox %.3f,%.3f,%.3f,%.3f".format(minLon, minLat, maxLon, maxLat))
        PmTiles.extract(src, minLon, minLat, maxLon, maxLat, f, log, opts["max-zoom"]?.toIntOrNull() ?: 15)
        f
    }

    // overview tiles for the small roads and trails at zooms the basemap leaves them out of
    val overview = File(work, PackManifest.OVERVIEW)
    run { val g = fold.core.Graph.open(graph); Overview.build(g, overview, log) }

    val manifest = PackManifest(name, PackManifest.FORMAT, packer.minLat, packer.minLon, packer.maxLat, packer.maxLon, LocalDate.now().toString(), "source=${pbf.name}")
    val manifestFile = File(work, PackManifest.FILE).apply { writeText(manifest.serialize()) }

    log("writing ${outFile.name}")
    ZipOutputStream(outFile.outputStream().buffered(1 shl 20)).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        for ((entryName, f) in listOf(PackManifest.FILE to manifestFile, PackManifest.GRAPH to graph, PackManifest.SEARCH to search, PackManifest.TILES to tilesFile, PackManifest.OVERVIEW to overview)) {
            val e = ZipEntry(entryName)
            e.method = ZipEntry.STORED; e.size = f.length(); e.compressedSize = f.length(); e.crc = crc(f)
            zip.putNextEntry(e)
            FileInputStream(f).use { it.copyTo(zip, 1 shl 20) }
            zip.closeEntry()
        }
    }
    work.deleteRecursively()
    log("done: ${outFile.length() / 1024 / 1024} MB -> ${outFile.absolutePath}")
}

private fun crc(f: File): Long {
    val c = CRC32(); val buf = ByteArray(1 shl 20)
    FileInputStream(f).use { ins -> while (true) { val n = ins.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
    return c.value
}
