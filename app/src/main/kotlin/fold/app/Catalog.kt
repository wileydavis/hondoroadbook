package fold.app

import android.app.Activity
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Roadbook's one use of the network: the pack catalog and the packs it points at.
 *
 * catalog.json: { "version": 1, "updated": "YYYY-MM-DD", "packs": [ { "id", "name", "group", "kind",
 * "url", "bytes", "built", "bbox": [minLon, minLat, maxLon, maxLat], "notes" } ] }
 * kind is roads | terrain | imagery; packs of different kinds for one region sit side by side.
 *
 * Downloads resume: a partial file in the cache keeps its bytes and the next attempt asks for the rest.
 */
class CatalogPack(val id: String, val name: String, val region: String, val group: String, val kind: String, val url: String, val bytes: Long, val built: String, val notes: String) {
    val kindLabel: String get() = when (kind) { "roads" -> "Roads"; "terrain" -> "Terrain"; "imagery" -> "Imagery"; else -> kind.replaceFirstChar { it.uppercase() } }
    val blurb: String get() = when (kind) { "roads" -> "roads, trails, routing, address search"; "terrain" -> "hillshade and contours under the roads"; "imagery" -> "aerial photos"; else -> "" }
}

object Catalog {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false

    fun parse(text: String): List<CatalogPack> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("packs")
        val out = ArrayList<CatalogPack>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            out.add(CatalogPack(o.getString("id"), name, o.optString("region", name), o.optString("group", ""), o.optString("kind", "roads"), o.getString("url"), o.optLong("bytes", 0L), o.optString("built", ""), o.optString("notes", "")))
        }
        return out
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.setRequestProperty("Accept", "application/json")
        try { if (c.responseCode != 200) throw IllegalStateException("Catalog: HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun show(act: Activity, packs: PackStore, onImported: () -> Unit) {
        val ctx = act
        val url = BuildConfig.CATALOG_URL
        if (url.isEmpty()) { AlertDialog.Builder(ctx).setMessage("This build has no pack catalog.").setPositiveButton("OK", null).show(); return }
        val dlg = ProgressDialogLite(ctx, "Fetching catalog…"); dlg.show()
        worker.execute {
            val result = try { Result.success(parse(fetch(url))) } catch (e: Throwable) { Result.failure(e) }
            act.runOnUiThread {
                dlg.dismiss()
                result.onSuccess { list -> showList(act, packs, list, onImported) }
                    .onFailure { e -> AlertDialog.Builder(ctx).setTitle("Catalog unavailable").setMessage((e.message ?: e.toString()) + "\n\nYou can still copy .mappack files to the phone and import them under MAPS.").setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun state(installed: Map<String, Pack>, p: CatalogPack): String { val have = installed[p.name] ?: return ""; return if (have.manifest.created < p.built) "update" else "installed" }

    /** Regions first; tapping one shows the kinds of map available for it. */
    private fun showList(act: Activity, packs: PackStore, list: List<CatalogPack>, onImported: () -> Unit) {
        val ctx = act
        if (list.isEmpty()) { AlertDialog.Builder(ctx).setTitle("No packs yet").setMessage("The catalog is empty: the first packs are still being built. Try again later, or import a .mappack file.").setPositiveButton("OK", null).show(); return }
        val installed = packs.packs.associateBy { it.name }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8)) }
        // grouped by country, then alphabetical, so each country heading appears once
        val regions = list.groupBy { it.region }.entries.sortedWith(compareBy({ it.value.first().group }, { it.key }))
        var lastGroup: String? = null
        var dlg: AlertDialog? = null
        for ((region, kinds) in regions) {
            val g = kinds.first().group
            if (g != lastGroup) { lastGroup = g; root.addView(TextView(ctx).apply { text = g.uppercase(); textSize = 12f; letterSpacing = 0.1f; setPadding(0, ctx.dp(14), 0, ctx.dp(2)) }) }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, ctx.dp(10)); isClickable = true; isFocusable = true
                val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId) }
            val have = kinds.count { state(installed, it) == "installed" }; val upd = kinds.count { state(installed, it) == "update" }
            val sub = kinds.joinToString(" · ") { it.kindLabel + " " + it.bytes / (1024 * 1024) + " MB" } + when { upd > 0 -> " · update available"; have == kinds.size -> " · installed"; have > 0 -> " · $have of ${kinds.size} installed"; else -> "" }
            row.addView(TextView(ctx).apply { text = region + "\n" + sub; textSize = 16f; setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply { text = "›"; textSize = 22f; setPadding(ctx.dp(12), 0, 0, 0) })
            row.setOnClickListener { showRegion(act, packs, region, kinds, installed) { dlg?.dismiss(); onImported() } }
            root.addView(row)
        }
        root.addView(TextView(ctx).apply { text = "Pick a region, then the maps you want for it. Packs download to this phone and install themselves; an interrupted download resumes."; textSize = 12f; setPadding(0, ctx.dp(14), 0, 0) })
        dlg = AlertDialog.Builder(ctx).setTitle("Map packs").setView(android.widget.ScrollView(ctx).apply { addView(root) }).setNegativeButton("CLOSE", null).show()
    }

    private fun showRegion(act: Activity, packs: PackStore, region: String, kinds: List<CatalogPack>, installed: Map<String, Pack>, onDone: () -> Unit) {
        val ctx = act
        val order = listOf("roads", "terrain", "imagery")
        val sorted = kinds.sortedBy { order.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
        val checked = BooleanArray(sorted.size) { state(installed, sorted[it]) != "installed" }
        val labels = sorted.map { p -> val st = state(installed, p); p.kindLabel + " · " + p.bytes / (1024 * 1024) + " MB" + (if (st.isNotEmpty()) " · $st" else "") + (if (p.blurb.isNotEmpty()) "\n" + p.blurb else "") }.toTypedArray()
        val missing = order.filter { k -> sorted.none { it.kind == k } }.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        val b = AlertDialog.Builder(ctx).setTitle(region)
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("DOWNLOAD") { _, _ ->
                val want = sorted.filterIndexed { i, _ -> checked[i] }
                if (want.isEmpty()) return@setPositiveButton
                downloadAll(act, packs, want, onDone)
            }
            .setNegativeButton(R.string.cancel, null)
        b.show()
        if (missing.isNotEmpty()) android.widget.Toast.makeText(ctx, "$missing for $region: not built yet", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun downloadAll(act: Activity, packs: PackStore, want: List<CatalogPack>, onDone: () -> Unit) {
        if (want.isEmpty()) { onDone(); return }
        download(act, packs, want.first()) { ok -> if (ok) downloadAll(act, packs, want.drop(1), onDone) else onDone() }
    }

    private fun download(act: Activity, packs: PackStore, p: CatalogPack, onImported: (Boolean) -> Unit) {
        val ctx = act
        val dir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
        // the partial file is named for the build it belongs to: a newer catalog entry never resumes onto old bytes
        val stamp = p.built.replace(Regex("[^0-9A-Za-z]"), "")
        dir.listFiles()?.filter { it.name.startsWith(p.id + "-") && it.name.endsWith(".part") && !it.name.contains("-$stamp.") }?.forEach { it.delete() }
        val file = File(dir, p.id + "-" + stamp + ".mappack.part")
        val need = p.bytes + (64L shl 20)
        if (ctx.filesDir.usableSpace < need) { AlertDialog.Builder(ctx).setMessage("Not enough space: ${p.name} needs ${p.bytes / (1024 * 1024)} MB.").setPositiveButton("OK", null).show(); onImported(false); return }
        cancel = false
        val dlg = ProgressDialogLite(ctx, "Downloading ${p.region} · ${p.kindLabel}"); dlg.setCancel("CANCEL") { cancel = true }; dlg.show()
        worker.execute {
            try {
                var have = if (file.exists()) file.length() else 0L
                if (p.bytes > 0 && have > p.bytes) { file.delete(); have = 0L }
                if (have < p.bytes || p.bytes == 0L) {
                    val c = URL(p.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 200 && have > 0) { have = 0L; file.delete() }      // server ignored the range
                    else if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                    val total = if (code == 206) have + c.contentLengthLong else c.contentLengthLong
                    RandomAccessFile(file, "rw").use { out ->
                        out.seek(have)
                        c.inputStream.use { ins ->
                            val buf = ByteArray(1 shl 18); var last = -1L
                            while (true) {
                                if (cancel) throw InterruptedException()
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); have += n
                                val pct = if (total > 0) have * 100 / total else -1
                                if (pct != last) { last = pct; val mb = have / (1024 * 1024); act.runOnUiThread { dlg.setMessage(if (pct >= 0) "$pct% · $mb of ${total / (1024 * 1024)} MB" else "$mb MB") } }
                            }
                        }
                    }
                    c.disconnect()
                    if (total > 0 && have < total) throw IllegalStateException("Connection dropped — tap GET again to resume")
                }
                act.runOnUiThread { dlg.setMessage("Importing…"); dlg.setCancel(null, null) }
                val done = File(dir, p.id + ".mappack"); done.delete(); file.renameTo(done)
                val pack = packs.import(Uri.fromFile(done)) { s -> act.runOnUiThread { dlg.setMessage(s) } }
                done.delete()
                act.runOnUiThread { dlg.dismiss(); android.widget.Toast.makeText(ctx, "Installed ${pack.name}", android.widget.Toast.LENGTH_LONG).show(); onImported(true) }
            } catch (_: InterruptedException) {
                act.runOnUiThread { dlg.dismiss(); android.widget.Toast.makeText(ctx, "Paused — download again to resume", android.widget.Toast.LENGTH_LONG).show(); onImported(false) }
            } catch (e: Throwable) {
                act.runOnUiThread { dlg.dismiss(); AlertDialog.Builder(ctx).setTitle("Download failed").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show(); onImported(false) }
            }
        }
    }
}
