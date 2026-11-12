package fold.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import fold.core.Gpx
import fold.core.QrCode
import fold.core.QrShare
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Phone to phone, offline: [ShareCodeActivity] shows a route as a moving QR code, [ScanCodeActivity] films one.
 * The format and the codes are the core's ([QrShare], [QrCode]); here are the screen, the camera and DEFLATE.
 */
object ShareCode {
    val deflate: (ByteArray) -> ByteArray? = { b ->
        val d = java.util.zip.Deflater(9, true); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end(); out.toByteArray()
    }
    val inflate: (ByteArray) -> ByteArray? = { b ->
        val i = java.util.zip.Inflater(true); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n); require(out.size() < 64 shl 20) }
        i.end(); out.toByteArray()
    }

    /** Show [gpx] (any GPX the app reads: a planned route, a track, a roadbook, a roll chart) as a moving code. */
    fun show(ctx: Context, gpx: String) {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "code.gpx"); f.writeText(gpx)
        ctx.startActivity(Intent(ctx, ShareCodeActivity::class.java).putExtra("file", f.path))
    }
}

/** The code on screen: as big as the screen allows, bright, a few frames a second until DONE. */
class ShareCodeActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var enc: QrShare.Encoder
    private lateinit var code: CodeView
    private lateinit var info: TextView
    private var frame = 0
    /** frames a second: a phone camera reads 6 easily; slower helps an old phone, faster a long track */
    private var fps = 6
    private val gen = Executors.newSingleThreadExecutor()
    @Volatile private var next: QrCode? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val gpx = try { File(intent.getStringExtra("file") ?: "").readText() } catch (_: Throwable) { null }
        val tr = gpx?.let { try { Gpx.parse(it) } catch (_: Throwable) { null } }
        if (tr == null) { finish(); return }
        enc = QrShare.Encoder(QrShare.message(tr, ShareCode.deflate))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_HORIZONTAL; setPadding((16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        root.addView(TextView(this).apply { text = tr.name; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK); gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "On the other phone: ≡ → TRACK → SCAN A CODE, and point it here. Works offline, Android or iPhone."; textSize = 14f; setTextColor(0xFF444444.toInt()); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt()) })
        code = CodeView(this)
        root.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        info = TextView(this).apply { textSize = 13f; setTextColor(0xFF444444.toInt()); gravity = Gravity.CENTER; setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt()) }
        root.addView(info)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, on: () -> Unit) = Button(this).apply { text = t; textSize = 15f; setOnClickListener { on() } }
        row.addView(btn("SLOWER") { fps = maxOf(2, fps - 2); showInfo() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("FASTER") { fps = minOf(12, fps + 2); showInfo() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("DONE") { finish() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        setContentView(root)
        showInfo()
        next = enc.code(0)
        ui.post(tick)
    }
    private fun showInfo() {
        val secs = ((enc.k + 3) / fps.toDouble()).let { if (it < 2) "a second or two" else "about ${Math.ceil(it).toInt()} s" }
        info.text = "${enc.k} ${if (enc.k == 1) "frame" else "frames"} · ${String.format(java.util.Locale.US, "%.1f", enc.message.size / 1024.0)} kB · $secs to read · $fps a second"
    }
    private val tick = object : Runnable {
        override fun run() {
            next?.let { code.code = it }
            frame++
            // the next frame is drawn off the main thread while this one shows
            val i = frame; gen.execute { next = enc.code(i) }
            ui.postDelayed(this, 1000L / fps)
        }
    }
    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); gen.shutdownNow() }

    /** a QR symbol, square, centred, with its quiet zone */
    class CodeView(ctx: Context) : View(ctx) {
        var code: QrCode? = null; set(v) { field = v; invalidate() }
        private val p = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        override fun onDraw(c: Canvas) {
            val q = code ?: return
            val n = q.size + 8
            val m = minOf(width, height) / n
            if (m <= 0) return
            val ox = (width - m * n) / 2 + 4 * m; val oy = (height - m * n) / 2 + 4 * m
            for (y in 0 until q.size) for (x in 0 until q.size) if (q.dark(x, y)) c.drawRect((ox + x * m).toFloat(), (oy + y * m).toFloat(), (ox + x * m + m).toFloat(), (oy + y * m + m).toFloat(), p)
        }
    }
}

/** The camera, reading frames until the message is whole; the GPX goes back to the app, which saves and opens it. */
class ScanCodeActivity : AppCompatActivity() {
    private val dec = QrShare.Decoder()
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var done = false
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startCamera() else { setResult(RESULT_CANCELED, Intent().putExtra("error", "Scanning a code needs the camera")); finish() } }
    private lateinit var preview: PreviewView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xCC000000.toInt()); setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt()) }
        status = TextView(this).apply { text = "Point the camera at the code on the other phone"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1; progress = 0 }
        panel.addView(status); panel.addView(bar)
        panel.addView(Button(this).apply { text = "CANCEL"; setOnClickListener { finish() } })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel) { v, ins -> val s = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()); v.setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt() + s.bottom); ins }
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else ask.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Throwable) { fail("The camera did not start: ${e.message}"); return@addListener }
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val sel = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val an = ImageAnalysis.Builder().setResolutionSelector(sel).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val reader = QRCodeReader()
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            an.setAnalyzer(work) { img ->
                try {
                    if (!done) {
                        val pl = img.planes[0]; val buf = pl.buffer; val stride = pl.rowStride
                        val w = img.width; val h = img.height
                        val y = ByteArray(stride * h).also { buf.rewind(); buf.get(it, 0, minOf(it.size, buf.remaining())) }
                        val src = PlanarYUVLuminanceSource(y, stride, h, 0, 0, w, h, false)
                        val text = try { reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text } catch (_: Throwable) { null } finally { reader.reset() }
                        if (text != null && dec.add(text)) ui.post { progress() }
                    }
                } finally { img.close() }
            }
            try { provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an) } catch (e: Throwable) { fail("The camera did not start: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun progress() {
        if (done) return
        bar.max = dec.k; bar.progress = dec.known
        status.text = "Reading… ${dec.known} of ${dec.k}"
        val m = dec.message ?: return
        done = true
        val tr = try { QrShare.track(m, ShareCode.inflate) } catch (e: Throwable) { fail("That code could not be read: ${e.message}"); return }
        val f = File(File(cacheDir, "share").apply { mkdirs() }, "received.gpx"); f.writeText(Gpx.writeShared(tr))
        status.text = "Got it: ${tr.name}"
        setResult(RESULT_OK, Intent().putExtra("file", f.path).putExtra("name", tr.name))
        ui.postDelayed({ finish() }, 400)
    }
    private fun fail(msg: String) { setResult(RESULT_CANCELED, Intent().putExtra("error", msg)); finish() }
    override fun onDestroy() { super.onDestroy(); work.shutdownNow() }
}
