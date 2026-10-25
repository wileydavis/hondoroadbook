package fold.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A pencil mark on the map: a point with a name and a note. */
class Mark(val id: Long, var name: String, var note: String, val lat: Double, val lon: Double, val created: Long)

/**
 * The only thing Fold writes besides packs and settings, and only when you tap SAVE.
 * Kept as a plain GPX file (marks.gpx) in the app's private storage: export it to your folder
 * or burn it, both from the Maps screen.
 */
class MarkStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "marks.gpx")
    @Volatile var marks: List<Mark> = emptyList(); private set

    init { load() }

    @Synchronized fun load() {
        marks = try { if (file.exists()) parse(file.readText()) else emptyList() } catch (_: Throwable) { emptyList() }
    }

    @Synchronized fun add(name: String, note: String, lat: Double, lon: Double): Mark {
        val m = Mark(System.currentTimeMillis(), name, note, lat, lon, System.currentTimeMillis())
        marks = marks + m; save(); return m
    }
    @Synchronized fun update(m: Mark, name: String, note: String) { m.name = name; m.note = note; save() }
    @Synchronized fun remove(m: Mark) { marks = marks.filter { it.id != m.id }; save() }
    @Synchronized fun burn() { marks = emptyList(); file.delete() }

    fun gpx(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Fold\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (m in marks) {
            append("  <wpt lat=\"").append(m.lat).append("\" lon=\"").append(m.lon).append("\">\n")
            append("    <name>").append(esc(m.name)).append("</name>\n")
            if (m.note.isNotEmpty()) append("    <desc>").append(esc(m.note)).append("</desc>\n")
            append("    <time>").append(java.time.Instant.ofEpochMilli(m.created)).append("</time>\n")
            append("  </wpt>\n")
        }
        append("</gpx>\n")
    }

    private fun save() { if (marks.isEmpty()) file.delete() else file.writeText(gpx()) }

    /** Writes marks.gpx into the folder the person granted. Returns the file name written. */
    fun exportTo(tree: Uri): String {
        val name = "fold-marks-" + java.time.LocalDate.now().toString() + ".gpx"
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/gpx+xml", name) ?: throw IllegalStateException("Could not create file")
        ctx.contentResolver.openOutputStream(doc)?.use { it.write(gpx().toByteArray()) } ?: throw IllegalStateException("Could not write file")
        return name
    }

    companion object {
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        private val wpt = Regex("<wpt\\s+lat=\"([^\"]+)\"\\s+lon=\"([^\"]+)\">(.*?)</wpt>", RegexOption.DOT_MATCHES_ALL)
        private val nameRe = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
        private val descRe = Regex("<desc>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL)
        private val timeRe = Regex("<time>(.*?)</time>")
        fun parse(text: String): List<Mark> {
            val out = ArrayList<Mark>()
            var i = 0L
            for (m in wpt.findAll(text)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue; val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                val body = m.groupValues[3]
                val name = nameRe.find(body)?.groupValues?.get(1)?.let { unesc(it) } ?: "Mark"
                val note = descRe.find(body)?.groupValues?.get(1)?.let { unesc(it) } ?: ""
                val t = timeRe.find(body)?.groupValues?.get(1)?.let { try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Throwable) { null } } ?: 0L
                out.add(Mark(t + i++, name, note, lat, lon, t))
            }
            return out
        }
    }
}
