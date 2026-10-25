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
class CatalogPack(val id: String, val name: String, val group: String, val kind: String, val url: String, val bytes: Long, val built: String, val notes: String)

object Catalog {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false

    fun parse(text: String): List<CatalogPack> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("packs")
        val out = ArrayList<CatalogPack>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(CatalogPack(o.getString("id"), o.getString("name"), o.optString("group", ""), o.optString("kind", "roads"), o.getString("url"), o.optLong("bytes", 0L), o.optString("built", ""), o.optString("notes", "")))
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

    private fun showList(act: Activity, packs: PackStore, list: List<CatalogPack>, onImported: () -> Unit) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8)) }
        val installed = packs.packs.associateBy { it.name }
        var lastGroup: String? = null
        for (p in list.sortedWith(compareBy({ it.group }, { it.name }, { it.kind }))) {
            if (p.group != lastGroup) { lastGroup = p.group; root.addView(TextView(ctx).apply { text = p.group.uppercase(); textSize = 12f; letterSpacing = 0.1f; setPadding(0, ctx.dp(14), 0, ctx.dp(2)) }) }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(6), 0, ctx.dp(6)) }
            val have = installed[p.name]
            val state = when { have == null -> ""; have.manifest.created < p.built -> "update available"; else -> "installed" }
            val mb = p.bytes / (1024 * 1024)
            root.addView(row)
            row.addView(TextView(ctx).apply { text = p.name + (if (p.kind != "roads") " · ${p.kind}" else "") + "\n$mb MB · ${p.built}" + (if (state.isNotEmpty()) " · $state" else ""); textSize = 16f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(ctx).apply {
                text = when (state) { "installed" -> "AGAIN"; "update available" -> "UPDATE"; else -> "GET" }; textSize = 13f
                setOnClickListener { download(act, packs, p, onImported) }
            })
        }
        root.addView(TextView(ctx).apply { text = "Packs download to this phone and are imported automatically. A download you interrupt resumes where it stopped. Nothing is sent but the request for the file."; textSize = 12f; setPadding(0, ctx.dp(14), 0, 0) })
        AlertDialog.Builder(ctx).setTitle("Download map packs").setView(android.widget.ScrollView(ctx).apply { addView(root) }).setNegativeButton("CLOSE", null).show()
    }

    private fun download(act: Activity, packs: PackStore, p: CatalogPack, onImported: () -> Unit) {
        val ctx = act
        val dir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
        val file = File(dir, p.id + ".mappack.part")
        val need = p.bytes + (64L shl 20)
        if (ctx.filesDir.usableSpace < need) { AlertDialog.Builder(ctx).setMessage("Not enough space: ${p.name} needs ${p.bytes / (1024 * 1024)} MB.").setPositiveButton("OK", null).show(); return }
        cancel = false
        val dlg = ProgressDialogLite(ctx, "Downloading ${p.name}"); dlg.setCancel("CANCEL") { cancel = true }; dlg.show()
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
                act.runOnUiThread { dlg.dismiss(); onImported(); android.widget.Toast.makeText(ctx, "Installed ${pack.name}", android.widget.Toast.LENGTH_LONG).show() }
            } catch (_: InterruptedException) {
                act.runOnUiThread { dlg.dismiss(); android.widget.Toast.makeText(ctx, "Paused — GET again to resume", android.widget.Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                act.runOnUiThread { dlg.dismiss(); AlertDialog.Builder(ctx).setTitle("Download failed").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }
}
