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
    /** the name of the live copy kept in the person's own folder, so the collection survives the app and the phone */
    val mirrorName = "Roadbook marks.gpx"

    init { load() }

    @Synchronized fun load() {
        marks = try { if (file.exists()) parse(file.readText()) else emptyList() } catch (_: Throwable) { emptyList() }
    }

    /** Each change is written before it becomes visible; a failed write leaves memory and file as they were. */
    @Synchronized fun add(name: String, note: String, lat: Double, lon: Double): Mark {
        val m = Mark(System.currentTimeMillis(), name, note, lat, lon, System.currentTimeMillis())
        val next = marks + m; save(next); marks = next; return m
    }
    @Synchronized fun update(m: Mark, name: String, note: String) {
        val oldName = m.name; val oldNote = m.note
        m.name = name; m.note = note
        try { save(marks) } catch (e: Throwable) { m.name = oldName; m.note = oldNote; throw e }
    }
    @Synchronized fun remove(m: Mark) { val next = marks.filter { it.id != m.id }; save(next); marks = next }
    @Synchronized fun burn() { file.delete(); marks = emptyList(); mirror() }

    /**
     * Merges waypoints from any GPX into the collection: a point within 5 m of a mark with the same name is the same
     * mark (its note is kept if the new one is empty). Returns how many were new.
     */
    @Synchronized fun merge(text: String): Int {
        val incoming = parse(text)
        val cur = ArrayList(marks); var added = 0; var i = 0L
        for (m in incoming) {
            val same = cur.firstOrNull { it.name == m.name && fold.core.Geo.fastDistance(it.lat, it.lon, m.lat, m.lon) < 5.0 }
            if (same != null) { if (same.note.isEmpty() && m.note.isNotEmpty()) same.note = m.note; continue }
            cur.add(Mark(System.currentTimeMillis() + i++, m.name, m.note, m.lat, m.lon, if (m.created > 0) m.created else System.currentTimeMillis())); added++
        }
        if (added > 0 || incoming.isNotEmpty()) { save(cur); marks = cur }
        return added
    }

    fun gpx(list: List<Mark> = marks): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Fold\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (m in list) {
            append("  <wpt lat=\"").append(m.lat).append("\" lon=\"").append(m.lon).append("\">\n")
            append("    <name>").append(esc(m.name)).append("</name>\n")
            if (m.note.isNotEmpty()) append("    <desc>").append(esc(m.note)).append("</desc>\n")
            append("    <time>").append(java.time.Instant.ofEpochMilli(m.created)).append("</time>\n")
            append("  </wpt>\n")
        }
        append("</gpx>\n")
    }

    /** Atomic: the new file is written beside the old one and renamed over it, so a crash never leaves a torn marks.gpx. */
    private fun save(list: List<Mark>) {
        if (list.isEmpty()) { file.delete(); mirror(list); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gpx(list))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}") }
        mirror(list)
    }

    /** Best effort: the same GPX written as "Roadbook marks.gpx" in the folder the person chose, replaced on every change. */
    private fun mirror(list: List<Mark> = marks) {
        val tree = try { FilePicker.folder(ctx) } catch (_: Throwable) { null } ?: return
        try {
            val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            var doc: Uri? = null
            ctx.contentResolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == mirrorName) { doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); break }
            }
            if (list.isEmpty()) { doc?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) }; return }
            val target = doc ?: DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/gpx+xml", mirrorName) ?: return
            ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(gpx(list).toByteArray()) }
        } catch (_: Throwable) {}
    }
    /** Called once a folder is chosen or changed, so the copy appears there. */
    fun syncMirror() { mirror() }

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
        private val typeRe = Regex("<type>(.*?)</type>")
        /** Plain waypoints only: a roadbook's cues (type cue:…, danger, note) are not marks. */
        fun parse(text: String): List<Mark> {
            val out = ArrayList<Mark>()
            var i = 0L
            for (m in wpt.findAll(text)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue; val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                val body = m.groupValues[3]
                val type = typeRe.find(body)?.groupValues?.get(1)?.trim()?.lowercase() ?: ""
                if (type.startsWith("cue:") || type.startsWith("danger") || type == "note") continue
                val name = nameRe.find(body)?.groupValues?.get(1)?.let { unesc(it) } ?: "Mark"
                val note = descRe.find(body)?.groupValues?.get(1)?.let { unesc(it) } ?: ""
                val t = timeRe.find(body)?.groupValues?.get(1)?.let { try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Throwable) { null } } ?: 0L
                out.add(Mark(t + i++, name, note, lat, lon, t))
            }
            return out
        }
    }
}
