package fold.core

/** Manifest of a .mappack: a stored (uncompressed) zip holding pack.txt, tiles.pmtiles, graph.bin, search.tsv. */
class PackManifest(val name: String, val format: Int, val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double, val created: String, val notes: String) {
    fun serialize(): String = buildString {
        append("name=").append(name).append('\n')
        append("format=").append(format).append('\n')
        append("bbox=").append(minLat).append(',').append(minLon).append(',').append(maxLat).append(',').append(maxLon).append('\n')
        append("created=").append(created).append('\n')
        append("notes=").append(notes.replace('\n', ' ')).append('\n')
    }

    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        const val FILE = "pack.txt"
        const val TILES = "tiles.pmtiles"
        const val GRAPH = "graph.bin"
        const val SEARCH = "search.tsv"
        const val FORMAT = 2
        val REQUIRED = listOf(FILE, TILES, GRAPH, SEARCH)

        fun parse(text: String): PackManifest? {
            val m = HashMap<String, String>()
            for (line in text.lines()) { val i = line.indexOf('='); if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim() }
            val name = m["name"] ?: return null
            val fmt = m["format"]?.toIntOrNull() ?: return null
            val b = m["bbox"]?.split(',')?.map { it.toDoubleOrNull() ?: return null } ?: return null
            if (b.size != 4) return null
            return PackManifest(name, fmt, b[0], b[1], b[2], b[3], m["created"] ?: "", m["notes"] ?: "")
        }
    }
}
