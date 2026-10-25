package fold.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * In-app file picker: the person grants Fold one folder (Downloads, say) once; after that TRACK and
 * MAPS list the matching files in it with a Cancel button, instead of bouncing through the system picker.
 */
object FilePicker {
    class Entry(val name: String, val uri: Uri, val size: Long)
    class Listing(val matches: List<Entry>, val others: List<String>, val error: String?)

    fun folder(ctx: Context): Uri? = Prefs(ctx).folder?.let { Uri.parse(it) }?.takeIf { uri ->
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    /** The folder, only if the grant that survives a reboot includes writing (older grants were read-only). */
    fun writableFolder(ctx: Context): Uri? = Prefs(ctx).folder?.let { Uri.parse(it) }?.takeIf { uri ->
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    fun rememberFolder(ctx: Context, uri: Uri) {
        // both flags: a read-only persisted grant lets TRACK list files but makes every save fail after a reboot
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        }
        Prefs(ctx).folder = uri.toString()
    }

    fun list(ctx: Context, tree: Uri, extensions: List<String>): Listing {
        val out = ArrayList<Entry>(); val others = ArrayList<String>(); var error: String? = null
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val cur = ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            if (cur == null) error = "The folder provider returned nothing"
            cur?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val size = if (c.isNull(2)) 0L else c.getLong(2); val mime = c.getString(3) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { others.add("$name/"); continue }
                    // match ".mappack" anywhere in the name too, so "montana (1).mappack" and odd browser renames still show
                    if (extensions.none { name.lowercase().contains(it) }) { others.add(name); continue }
                    out.add(Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), size))
                }
            }
        } catch (e: Throwable) { error = e.toString() }
        out.sortBy { it.name.lowercase() }
        return Listing(out, others, error)
    }

    /**
     * Shows the picker. [onChooseFolder] must launch the system folder chooser; the caller calls [show] again afterwards.
     */
    fun show(act: Activity, title: String, extensions: List<String>, what: String, onChooseFolder: () -> Unit, onPick: (Entry) -> Unit, onPickAll: ((List<Entry>) -> Unit)? = null, onSystemPicker: (() -> Unit)? = null, extra: Pair<String, () -> Unit>? = null) {
        val ctx = act
        val tree = folder(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        var dlgRef: AlertDialog? = null
        if (extra != null) root.addView(android.widget.Button(ctx).apply { text = extra.first; textSize = 14f; setOnClickListener { dlgRef?.dismiss(); extra.second() } })
        if (tree == null) {
            root.addView(TextView(ctx).apply { textSize = 16f; text = "Choose the folder on this phone where your $what are (Downloads works well). Fold will remember it and list the files here from now on." })
            val b0 = AlertDialog.Builder(ctx).setTitle(title).setView(root)
                .setPositiveButton("CHOOSE FOLDER") { _, _ -> onChooseFolder() }
                .setNegativeButton(R.string.cancel, null)
            if (onSystemPicker != null) b0.setNeutralButton("BROWSE…") { _, _ -> onSystemPicker() }
            dlgRef = b0.show()
            return
        }
        val listing = list(ctx, tree, extensions)
        val entries = listing.matches
        val folderName = try { tree.lastPathSegment?.substringAfter(':')?.ifEmpty { "Downloads" } ?: "folder" } catch (_: Throwable) { "folder" }
        root.addView(TextView(ctx).apply { textSize = 13f; text = "In $folderName" ; setPadding(0, 0, 0, dp(ctx, 6)) })
        if (entries.isEmpty()) {
            val why = when {
                listing.error != null -> "Fold could not read this folder: ${listing.error}"
                listing.others.isEmpty() -> "This folder looks empty to Fold. If the files are there, try CHANGE FOLDER and pick it again (or BROWSE… to pick the file directly)."
                else -> "No ${extensions.joinToString(" / ")} files here. Fold sees: " + listing.others.take(8).joinToString(", ") + if (listing.others.size > 8) " …" else ""
            }
            root.addView(TextView(ctx).apply { textSize = 15f; text = why })
        }
        val list = ListView(ctx)
        list.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, entries.map { "${it.name}   ${it.size / (1024 * 1024)} MB" })
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.45).toInt()))
        val b = AlertDialog.Builder(ctx).setTitle(title).setView(root)
            .setNeutralButton("CHANGE FOLDER") { _, _ -> onChooseFolder() }
            .setNegativeButton(R.string.cancel, null)
        if (onPickAll != null && entries.isNotEmpty()) b.setPositiveButton("IMPORT ALL (${entries.size})") { _, _ -> onPickAll(entries) }
        else if (onSystemPicker != null) b.setPositiveButton("BROWSE…") { _, _ -> onSystemPicker() }
        val dlg = b.create(); dlgRef = dlg
        list.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); onPick(entries[pos]) }
        dlg.show()
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
