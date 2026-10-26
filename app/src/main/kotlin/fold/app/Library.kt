package fold.app

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Roadbook's other uses of the network, every one of them a button the rider presses:
 *   FETCH CODE   — a roadbook parked by the planner on hondoroadbook.com, under a six-letter code
 *   PUBLISH      — this roadbook into the site's library, trimmed at the ends if asked
 *   BROWSE       — the library's roadbooks in the part of the map on screen
 * Nothing identifies the phone: no account, no id, and a published file keeps only the line and cues.
 */
class LibraryItem(val id: String, val name: String, val lengthM: Double, val cues: Int, val mode: String, val unpavedShare: Double, val created: String, val url: String, val line: List<DoubleArray> /* lon,lat */)

object Library {
    private val worker = Executors.newSingleThreadExecutor()
    /** https://hondoroadbook.com, derived from the catalog URL so one setting names the site. */
    val site: String get() = BuildConfig.CATALOG_URL.substringBefore("/packs/").ifEmpty { "https://hondoroadbook.com" }

    private fun open(url: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.requestMethod = method; c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/json, application/gpx+xml")
        if (body != null) { c.doOutput = true; contentType?.let { c.setRequestProperty("Content-Type", it) }; c.outputStream.use { it.write(body) } }
        return c
    }
    private fun readAll(c: HttpURLConnection): String = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
    private fun errorOf(text: String, code: Int) = try { JSONObject(text).optString("error", "HTTP $code") } catch (_: Throwable) { "HTTP $code" }

    // ---- fetch by code ---------------------------------------------------------------------------------------------

    fun fetchCode(act: Activity, onGpx: (name: String, gpx: String) -> Unit) {
        val ctx = act
        val input = EditText(ctx).apply { hint = "ABC 123"; isSingleLine = true; textSize = 28f; letterSpacing = 0.25f; gravity = android.view.Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16)) }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(TextView(ctx).apply { textSize = 15f; text = "The code the planner showed under SEND TO PHONE. It works for 24 hours." }); addView(input) }
        AlertDialog.Builder(ctx).setTitle("Fetch a roadbook").setView(root)
            .setPositiveButton("FETCH") { _, _ ->
                val code = input.text.toString().uppercase().replace(Regex("[^A-Z0-9]"), "")
                if (code.length != 6) { android.widget.Toast.makeText(ctx, "A code is six letters and digits", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val dlg = ProgressDialogLite(ctx, "Fetching $code…"); dlg.show()
                worker.execute {
                    val r = try {
                        val c = open("$site/api/parked/$code")
                        try { val text = readAll(c); if (c.responseCode != 200) throw IllegalStateException(errorOf(text, c.responseCode))
                            val name = c.getHeaderField("X-Name")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Roadbook"
                            Result.success(name to text) } finally { c.disconnect() }
                    } catch (e: Throwable) { Result.failure(e) }
                    act.runOnUiThread { dlg.dismiss(); r.onSuccess { (n, t) -> onGpx(n, t) }.onFailure { e -> AlertDialog.Builder(ctx).setTitle("Nothing fetched").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show() } }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---- publish ---------------------------------------------------------------------------------------------------

    fun publish(act: Activity, name: String, gpx: String, recorded: Boolean) {
        val ctx = act
        val trims = listOf(0 to "Keep the whole track", 250 to "Trim 250 m off each end", 500 to "Trim 500 m off each end", 1000 to "Trim 1 km off each end")
        val group = RadioGroup(ctx)
        trims.forEachIndexed { i, (m, label) -> group.addView(RadioButton(ctx).apply { text = label; id = 100 + i; textSize = 15f; isChecked = if (recorded) m == 500 else m == 0 }) }
        val nameBox = EditText(ctx).apply { setText(name); isSingleLine = true; textSize = 17f; hint = "Name" }
        val root = android.widget.ScrollView(ctx).apply { addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(TextView(ctx).apply { textSize = 15f; text = "Anyone can then find this roadbook on hondoroadbook.com and in BROWSE ROADBOOKS, and ride it. The published file keeps only the line and the cues: no times, no device, nothing about you. Trimming the ends keeps your start point private." })
            addView(nameBox); addView(group) }) }
        AlertDialog.Builder(ctx).setTitle("Publish to the library").setView(root)
            .setPositiveButton("PUBLISH") { _, _ ->
                val trim = trims[(group.checkedRadioButtonId - 100).coerceIn(0, trims.size - 1)].first
                val n = nameBox.text.toString().trim().ifEmpty { name }
                val dlg = ProgressDialogLite(ctx, "Publishing…"); dlg.show()
                worker.execute {
                    val r = try {
                        val c = open("$site/api/roadbooks?name=${URLEncoder.encode(n, "UTF-8")}&trim=$trim&source=app", "POST", gpx.toByteArray(), "application/gpx+xml")
                        try { val text = readAll(c); if (c.responseCode != 200) throw IllegalStateException(errorOf(text, c.responseCode)); Result.success(JSONObject(text)) } finally { c.disconnect() }
                    } catch (e: Throwable) { Result.failure(e) }
                    act.runOnUiThread {
                        dlg.dismiss()
                        r.onSuccess { j ->
                            val page = j.optString("page")
                            AlertDialog.Builder(ctx).setTitle("Published").setMessage("${j.optString("name")} · ${Units.distance(j.optDouble("length", 0.0))} · ${j.optInt("cues")} cues\n\n$page\n\nIf it should come down, email wiley@machinenusa.com with that link.")
                                .setPositiveButton("COPY LINK") { _, _ -> (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Roadbook", page)) }
                                .setNegativeButton("CLOSE", null).show()
                        }.onFailure { e -> AlertDialog.Builder(ctx).setTitle("Not published").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---- browse ----------------------------------------------------------------------------------------------------

    fun browse(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, onResult: (Result<List<LibraryItem>>) -> Unit) {
        worker.execute {
            val r = try {
                val c = open("$site/api/roadbooks?bbox=$minLon,$minLat,$maxLon,$maxLat&limit=300")
                try {
                    val text = readAll(c); if (c.responseCode != 200) throw IllegalStateException(errorOf(text, c.responseCode))
                    val arr = JSONObject(text).getJSONArray("roadbooks")
                    val out = ArrayList<LibraryItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val ln = o.getJSONArray("line"); val line = ArrayList<DoubleArray>(ln.length())
                        for (k in 0 until ln.length()) { val p = ln.getJSONArray(k); line.add(doubleArrayOf(p.getDouble(0), p.getDouble(1))) }
                        val paved = o.optDouble("paved", Double.NaN); val unpaved = o.optDouble("unpaved", Double.NaN)
                        val share = if (paved.isNaN() || unpaved.isNaN() || paved + unpaved <= 0) Double.NaN else unpaved / (paved + unpaved)
                        val created = o.optLong("created", 0L).let { if (it > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it)) else "" }
                        out.add(LibraryItem(o.getString("id"), o.optString("name", "Roadbook"), o.optDouble("length", 0.0), o.optInt("cues", 0), o.optString("mode", ""), share, created, o.getString("url"), line))
                    }
                    Result.success<List<LibraryItem>>(out)
                } finally { c.disconnect() }
            } catch (e: Throwable) { Result.failure(e) }
            onResult(r)
        }
    }

    fun download(item: LibraryItem, onResult: (Result<String>) -> Unit) {
        worker.execute {
            val r = try { val c = open(item.url); try { val text = readAll(c); if (c.responseCode != 200) throw IllegalStateException(errorOf(text, c.responseCode)); Result.success(text) } finally { c.disconnect() } } catch (e: Throwable) { Result.failure(e) }
            onResult(r)
        }
    }
}
