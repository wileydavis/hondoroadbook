package fold.app

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import fold.core.Hit
import fold.core.TextNorm
import java.util.concurrent.Executors

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Address / place search over every installed pack. Runs off the UI thread; typing is never blocked. */
object SearchDialog {
    private val worker = Executors.newSingleThreadExecutor()

    /** [title] heads the search (plan mode asks "where does it start?"); [extra] is one more way out, a button with its own action. */
    fun show(act: Activity, packs: PackStore, marks: MarkStore, onPick: (Hit) -> Unit, onMark: (Mark) -> Unit, title: String? = null, extra: Pair<String, () -> Unit>? = null) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0) }
        if (title != null) root.addView(TextView(ctx).apply { text = title; textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(ctx.dp(8), 0, ctx.dp(8), ctx.dp(8)) })
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.search_hint); textSize = 20f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(ctx.dp(8), ctx.dp(14), ctx.dp(8), ctx.dp(14))
        }
        val list = ListView(ctx).apply { dividerHeight = 1 }
        val status = TextView(ctx).apply { textSize = 14f; setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6)) }
        root.addView(input); root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.5).toInt()))

        val b = AlertDialog.Builder(ctx).setView(root).setNegativeButton(R.string.cancel, null)
        if (extra != null) b.setNeutralButton(extra.first) { _, _ -> extra.second() }
        val dlg = b.create()
        var hits: List<Hit> = emptyList()
        var shownMarks: List<Mark> = emptyList()
        var generation = 0
        val adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shownMarks.size) { dlg.dismiss(); onMark(shownMarks[pos]) }
            else hits.getOrNull(pos - shownMarks.size)?.let { dlg.dismiss(); onPick(it) }
        }

        if (packs.packs.isEmpty()) status.text = ctx.getString(R.string.no_packs)

        fun showMarks(q: String) {
            val nq = TextNorm.normalize(q)
            shownMarks = marks.marks.filter { nq.isEmpty() || TextNorm.normalize(it.name + " " + it.note).contains(nq) }.sortedByDescending { it.created }
        }

        fun run(q: String) {
            val gen = ++generation
            showMarks(q)
            if (q.isBlank()) {
                adapter.clear(); hits = emptyList(); adapter.addAll(shownMarks.map { "★ " + it.name + if (it.note.isNotEmpty()) " — " + it.note else "" })
                status.text = if (shownMarks.isEmpty() && packs.packs.isNotEmpty()) "" else status.text; return
            }
            status.text = "Searching…"
            worker.execute {
                val scored = ArrayList<Pair<Int, Hit>>()
                for (p in packs.roadPacks) {
                    try { p.loadSearch()?.let { scored.addAll(it.queryScored(q, 20)) } } catch (_: Throwable) {}
                    if (gen != generation) return@execute
                }
                // rank across packs, not pack by pack: a city in Washington must beat a street in Montana
                scored.sortByDescending { it.first }
                val results = scored.take(40).map { it.second }
                act.runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    hits = results
                    adapter.clear(); adapter.addAll(shownMarks.map { "★ " + it.name + if (it.note.isNotEmpty()) " — " + it.note else "" }); adapter.addAll(results.map { it.label })
                    status.text = if (results.isEmpty()) "No matches" else ""
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { run(s?.toString() ?: "") }
        })
        run("")
        dlg.show()
        input.requestFocus()
        input.postDelayed({ (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 150)
    }
}

/** Installed packs, import, delete, units. The only screen with any settings. */
object MapsDialog {
    fun show(act: Activity, packs: PackStore, marks: MarkStore, onImport: () -> Unit, onDelete: (Pack) -> Unit, onUnits: (Boolean) -> Unit, onExportMarks: () -> Unit, onBurnMarks: () -> Unit, onDownload: (() -> Unit)? = null, onBrowse: (() -> Unit)? = null, terrainOn: Boolean = true, onTerrain: ((Boolean) -> Unit)? = null) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(8)) }
        val list = packs.packs
        if (list.isEmpty()) root.addView(TextView(ctx).apply { text = ctx.getString(R.string.no_packs); textSize = 16f; setPadding(0, 0, 0, ctx.dp(12)) })
        for (p in list) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
            val mb = p.sizeBytes / (1024 * 1024)
            row.addView(TextView(ctx).apply { text = "${p.name}\n${mb} MB · ${p.manifest.created}" + (if (p.isTerrain) " · hillshade" else ""); textSize = 17f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(ctx).apply {
                text = "REMOVE"; textSize = 13f
                setOnClickListener {
                    AlertDialog.Builder(ctx).setMessage("Remove ${p.name} from this phone?").setPositiveButton("Remove") { _, _ -> onDelete(p); (row.parent as? LinearLayout)?.removeView(row) }.setNegativeButton(R.string.cancel, null).show()
                }
            })
            root.addView(row)
        }
        for ((name, why) in packs.broken) root.addView(TextView(ctx).apply { text = "$name: damaged ($why) — re-download or re-import"; textSize = 14f; setTextColor(ctx.getColor(R.color.accent)); setPadding(0, ctx.dp(4), 0, ctx.dp(4)) })
        val units = Button(ctx).apply {
            fun label() = if (Units.metric) "UNITS: KM / M" else "UNITS: MILES / FT"
            text = label(); textSize = 14f
            setOnClickListener { onUnits(!Units.metric); text = label() }
        }
        val settings = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        settings.addView(units, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onTerrain != null && packs.packs.any { it.isTerrain }) {
            var on = terrainOn
            settings.addView(Button(ctx).apply {
                fun label() = if (on) "TERRAIN: ON" else "TERRAIN: OFF"
                text = label(); textSize = 14f
                setOnClickListener { on = !on; onTerrain(on); text = label() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(settings)
        val n = marks.marks.size
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, 0) }
        row.addView(TextView(ctx).apply { text = "Marks: $n"; textSize = 15f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(ctx).apply { text = "EXPORT GPX"; textSize = 12f; isEnabled = n > 0; setOnClickListener { onExportMarks() } })
        row.addView(Button(ctx).apply { text = "BURN"; textSize = 12f; isEnabled = n > 0; setOnClickListener {
            AlertDialog.Builder(ctx).setMessage("Delete all $n marks from this phone? There is no undo.").setPositiveButton("Burn") { _, _ -> onBurnMarks(); text = "BURN"; isEnabled = false }.setNegativeButton(R.string.cancel, null).show() } })
        root.addView(row)
        if (onBrowse != null) {
            root.addView(TextView(ctx).apply { text = "ROADBOOKS OTHERS SHARED"; textSize = 12f; letterSpacing = 0.1f; setPadding(0, ctx.dp(16), 0, ctx.dp(4)) })
            root.addView(TextView(ctx).apply { text = "The library on hondoroadbook.com, drawn over the map. Tap one to follow it or save it."; textSize = 13f })
            root.addView(Button(ctx).apply { text = "BROWSE ROADBOOKS"; textSize = 13f; tag = "browse" })
        }
        root.addView(TextView(ctx).apply { text = ctx.getString(R.string.credits); textSize = 12f; setPadding(0, ctx.dp(12), 0, 0) })
        root.addView(TextView(ctx).apply { text = "Roadbook " + BuildConfig.VERSION_NAME + " · hondoroadbook.com"; textSize = 12f; setPadding(0, ctx.dp(6), 0, 0) })
        root.addView(Button(ctx).apply { text = "SEND FEEDBACK"; textSize = 13f; setOnClickListener { FoldApp.feedback(ctx, "Maps: " + packs.packs.joinToString(", ") { it.name }) } })
        root.addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL
            addView(Button(ctx).apply { text = "TERMS"; textSize = 12f; setOnClickListener { (act as? MainActivity)?.openUrl("https://hondoroadbook.com/terms.html") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(ctx).apply { text = "PRIVACY"; textSize = 12f; setOnClickListener { (act as? MainActivity)?.openUrl("https://hondoroadbook.com/privacy.html") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) })
        root.addView(TextView(ctx).apply { text = "Roadbook stores your map packs, these settings, marks you save on purpose, and recordings you keep. The network is used only when you press a button that needs it: downloading packs, fetching or publishing a roadbook, browsing the library. No accounts, no history."; textSize = 12f; setPadding(0, ctx.dp(6), 0, 0) })
        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        val b = AlertDialog.Builder(ctx).setTitle("Maps on this phone").setView(scroll)
            .setPositiveButton(if (onDownload != null) "DOWNLOAD" else ctx.getString(R.string.import_pack)) { _, _ -> if (onDownload != null) onDownload() else onImport() }
            .setNegativeButton("CLOSE", null)
        if (onDownload != null) b.setNeutralButton("IMPORT FILE") { _, _ -> onImport() }
        val dlg = b.show()
        if (onBrowse != null) root.findViewWithTag<Button>("browse")?.setOnClickListener { dlg.dismiss(); onBrowse() }
    }
}

/** Tiny modal progress, no library needed. */
class ProgressDialogLite(ctx: Context, title: String) {
    private val text = TextView(ctx).apply { textSize = 16f; setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(16)); text = "Starting…" }
    private val dlg = AlertDialog.Builder(ctx).setTitle(title).setView(text).setCancelable(false).create()
    fun show() { dlg.show() }
    fun message(m: String) { text.text = m }
    fun setMessage(m: String) = message(m)
    /** An optional cancel button (null hides it). */
    fun setCancel(label: String?, action: (() -> Unit)?) {
        val b = dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
        if (label == null) { b?.visibility = View.GONE; return }
        if (b == null) { dlg.setButton(AlertDialog.BUTTON_NEGATIVE, label) { _, _ -> action?.invoke() } } else { b.text = label; b.visibility = View.VISIBLE; b.setOnClickListener { action?.invoke(); dlg.dismiss() } }
    }
    fun dismiss() { try { dlg.dismiss() } catch (_: Throwable) {} }
}


/** Create/edit a mark, and the tap-on-a-mark card. */
object MarkDialog {
    fun edit(act: Activity, existing: Mark?, suggestedName: String, onDone: (String, String) -> Unit) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
        val name = EditText(ctx).apply { hint = "Name"; setText(existing?.name ?: suggestedName); isSingleLine = true; textSize = 18f; setSelectAllOnFocus(true) }
        val note = EditText(ctx).apply { hint = "Note (water here, gate code, who told you about it…)"; setText(existing?.note ?: ""); minLines = 2; maxLines = 6; textSize = 16f }
        root.addView(name); root.addView(note)
        val dlg = AlertDialog.Builder(ctx).setTitle(if (existing == null) "Save mark" else "Edit mark").setView(root)
            .setPositiveButton("SAVE") { _, _ -> onDone(name.text.toString().trim().ifEmpty { "Mark" }, note.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null).create()
        dlg.show()
        name.requestFocus()
        name.postDelayed({ (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(name, InputMethodManager.SHOW_IMPLICIT) }, 150)
    }

    fun show(act: Activity, m: Mark, onRoute: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0) }
        if (m.note.isNotEmpty()) root.addView(TextView(ctx).apply { text = m.note; textSize = 16f })
        root.addView(TextView(ctx).apply { text = "%.5f, %.5f".format(m.lat, m.lon); textSize = 13f; setPadding(0, ctx.dp(8), 0, 0) })
        AlertDialog.Builder(ctx).setTitle("★ " + m.name).setView(root)
            .setPositiveButton(R.string.route_here) { _, _ -> onRoute() }
            .setNeutralButton("EDIT") { _, _ -> onEdit() }
            .setNegativeButton("DELETE") { _, _ -> AlertDialog.Builder(ctx).setMessage("Delete ${m.name}?").setPositiveButton("Delete") { _, _ -> onDelete() }.setNegativeButton(R.string.cancel, null).show() }
            .show()
    }
}
