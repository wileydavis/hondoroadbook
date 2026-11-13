package fold.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * The natural voice: Kokoro-82M (Apache-2.0), downloaded on request (about 117 MB) from hondoroadbook.com and kept in
 * the app's files. Nothing is fetched unless the rider presses DOWNLOAD on the VOICE screen; the built-in voice stays
 * as the fallback and for anything the natural voice cannot make in time.
 */
object Voices {
    const val PACK = "kokoro-en-v1"
    const val URL_PACK = "https://hondoroadbook.com/packs/voices/$PACK.zip"
    const val SIZE_MB = 117
    /** the speakers offered (Kokoro v1.0 ids) */
    val SPEAKERS = listOf(
        3 to "Heart · US, warm", 2 to "Bella · US", 6 to "Nicole · US, soft", 9 to "Sarah · US",
        16 to "Michael · US", 14 to "Fenrir · US, deep", 18 to "Puck · US, bright", 11 to "Adam · US",
        21 to "Emma · British", 24 to "Daniel · British", 26 to "George · British",
    )
    private const val SAMPLE = "In half a mile, left onto US-287. Then cattle guard, caution."
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false

    fun dir(ctx: Context) = File(ctx.filesDir, "voices/$PACK")
    fun installed(ctx: Context): Boolean { val d = dir(ctx); return File(d, "installed").exists() && File(d, "model.int8.onnx").exists() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fold", Context.MODE_PRIVATE)
    /** the chosen speaker, or -1 for the built-in voice */
    fun chosen(ctx: Context): Int = if (installed(ctx)) prefs(ctx).getInt("voice", -1) else -1
    private fun choose(ctx: Context, sid: Int) { prefs(ctx).edit().putInt("voice", sid).apply(); Speech.useVoice(sid) }

    fun show(act: Activity) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(8)) }
        val have = installed(ctx)
        root.addView(TextView(ctx).apply {
            textSize = 14f; setPadding(0, 0, 0, ctx.dp(8))
            text = if (have) "Pick who reads the cues. The natural voice is prepared ahead of each cue; if a phrase is not ready in time the built-in voice says it."
            else "Roadbook's built-in voice works everywhere. A natural voice (Kokoro, much closer to a person) is a one-time download of about $SIZE_MB MB, kept on this phone and run offline."
        })
        val group = RadioGroup(ctx)
        val current = chosen(ctx)
        fun option(id: Int, label: String) = RadioButton(ctx).apply { this.id = 1000 + id; text = label; textSize = 16f; isChecked = id == current; setPadding(ctx.dp(4), ctx.dp(6), 0, ctx.dp(6)) }
        group.addView(option(-1, "Built-in voice"))
        if (have) for ((sid, label) in SPEAKERS) group.addView(option(sid, label))
        group.setOnCheckedChangeListener { _, id -> choose(ctx, id - 1000) }
        root.addView(group)
        root.addView(TextView(ctx).apply { text = "Cues are read the way people say them: \"US-287\" as \"U S two eighty-seven\", a roll chart's capitals as words."; textSize = 12f; setPadding(0, ctx.dp(8), 0, 0) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, 0) }
        row.addView(Button(ctx).apply { text = "TEST"; textSize = 13f; setOnClickListener { Speech.init(ctx); Speech.test(SAMPLE) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (have) row.addView(Button(ctx).apply { text = "REMOVE NATURAL VOICE"; textSize = 12f; setOnClickListener {
            AlertDialog.Builder(ctx).setMessage("Remove the natural voice ($SIZE_MB MB) from this phone? The built-in voice stays.").setPositiveButton("Remove") { _, _ ->
                choose(ctx, -1); worker.execute { dir(ctx).deleteRecursively() }; act.runOnUiThread { toast(ctx, "Natural voice removed") }
            }.setNegativeButton(R.string.cancel, null).show() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(row)
        root.addView(TextView(ctx).apply { text = "Kokoro-82M · Apache-2.0 · hexgrad. Synthesis runs on the phone; nothing is sent."; textSize = 11f; setPadding(0, ctx.dp(10), 0, 0) })
        val b = AlertDialog.Builder(ctx).setTitle("Voice").setView(android.widget.ScrollView(ctx).apply { addView(root) }).setNegativeButton("CLOSE", null)
        if (!have) b.setPositiveButton("DOWNLOAD ($SIZE_MB MB)") { _, _ -> download(act) }
        b.show()
    }

    private fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()

    private fun download(act: Activity) {
        val ctx = act
        if (ctx.filesDir.usableSpace < (SIZE_MB + 200L) shl 20) { AlertDialog.Builder(ctx).setMessage("Not enough space: the natural voice needs about ${SIZE_MB + 150} MB.").setPositiveButton("OK", null).show(); return }
        cancel = false
        val dlg = ProgressDialogLite(ctx, "Downloading the natural voice"); dlg.setCancel("CANCEL") { cancel = true }; dlg.show()
        worker.execute {
            val part = File(ctx.cacheDir, "downloads/$PACK.zip.part").apply { parentFile?.mkdirs() }
            try {
                var have = if (part.exists()) part.length() else 0L
                val c = URL(URL_PACK).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 416) { /* already whole */ } else {
                    if (code == 200 && have > 0) { have = 0L; part.delete() }
                    else if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                    val total = if (code == 206) have + c.contentLengthLong else c.contentLengthLong
                    RandomAccessFile(part, "rw").use { out ->
                        out.seek(have)
                        c.inputStream.use { ins ->
                            val buf = ByteArray(1 shl 18); var last = -1L
                            while (true) {
                                if (cancel) throw InterruptedException()
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); have += n
                                val pct = if (total > 0) have * 100 / total else -1
                                if (pct != last) { last = pct; val mb = have shr 20; act.runOnUiThread { dlg.setMessage(if (pct >= 0) "$pct% · $mb of ${total shr 20} MB" else "$mb MB") } }
                            }
                        }
                    }
                    if (total > 0 && have < total) throw IllegalStateException("Connection dropped — download again to resume")
                }
                c.disconnect()
                act.runOnUiThread { dlg.setMessage("Unpacking…"); dlg.setCancel(null, null) }
                val d = dir(ctx); val tmp = File(d.parentFile, "$PACK.tmp")
                tmp.deleteRecursively(); tmp.mkdirs()
                val base = tmp.canonicalPath + File.separator
                ZipInputStream(part.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val f = File(tmp, e.name)
                        if (!f.canonicalPath.startsWith(base)) throw IllegalStateException("bad pack")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                if (!File(tmp, "model.int8.onnx").exists() || !File(tmp, "voices.bin").exists()) throw IllegalStateException("the pack is incomplete")
                File(tmp, "installed").writeText(PACK)
                d.deleteRecursively(); tmp.renameTo(d); part.delete()
                act.runOnUiThread { dlg.dismiss(); choose(ctx, SPEAKERS.first().first); toast(ctx, "Natural voice installed"); show(act) }
            } catch (_: InterruptedException) {
                act.runOnUiThread { dlg.dismiss(); toast(ctx, "Paused — download again to resume") }
            } catch (e: Throwable) {
                act.runOnUiThread { dlg.dismiss(); AlertDialog.Builder(ctx).setTitle("Download failed").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }
}
