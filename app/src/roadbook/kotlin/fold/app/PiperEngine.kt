package fold.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * The voice that ships inside Roadbook: a Piper VITS voice (en_US lessac, int8) run by sherpa-onnx,
 * entirely on the phone. No speech engine needs to be installed, which matters on GrapheneOS.
 *
 * Phrases are synthesized ahead of time ([prepare], called as soon as a cue becomes the next one)
 * and cached as PCM, so [say] at the window opening starts in milliseconds. Audio goes out as
 * navigation guidance, the same stream as the pips, so a helmet headset gets it.
 */
class PiperEngine(private val ctx: Context) : SpeechEngine {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "piper").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile override var ready = false; private set
    private var tts: OfflineTts? = null
    private var rate = 16000
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 12 }
    @Volatile private var playing: AudioTrack? = null
    @Volatile private var generation = 0   // bumped by say() and stop(); queued synthesis checks it before playing

    init { worker.execute { load() } }

    private fun load() {
        try {
            // espeak-ng wants its data on disk; the model and tokens are read straight from assets
            val dir = File(ctx.filesDir, "tts/espeak-ng-data")
            val stamp = File(ctx.filesDir, "tts/espeak.version")
            if (!dir.exists() || stamp.takeIf { it.exists() }?.readText() != VERSION) {
                dir.deleteRecursively(); copyAssets("tts/espeak-ng-data", dir); stamp.writeText(VERSION)
            }
            val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = "tts/en.onnx", tokens = "tts/tokens.txt", dataDir = dir.absolutePath, lengthScale = 0.95f), numThreads = 2, provider = "cpu"))
            val t = OfflineTts(ctx.assets, cfg)
            rate = t.sampleRate(); tts = t; ready = true
        } catch (e: Throwable) { android.util.Log.w("Roadbook", "voice unavailable: $e"); ready = false }
    }

    private fun copyAssets(path: String, to: File) {
        val am = ctx.assets
        val kids = am.list(path) ?: emptyArray()
        if (kids.isEmpty()) { to.parentFile?.mkdirs(); am.open(path).use { ins -> to.outputStream().use { ins.copyTo(it) } }; return }
        to.mkdirs(); for (k in kids) copyAssets("$path/$k", File(to, k))
    }

    private fun synth(text: String): FloatArray? {
        synchronized(cache) { cache[text]?.let { return it } }
        val t = tts ?: return null
        val a = try { t.generate(text, 0, 1.0f) } catch (_: Throwable) { return null }
        val s = a.samples
        synchronized(cache) { cache[text] = s }
        return s
    }

    override fun prepare(text: String) { if (ready) worker.execute { synth(text) } }

    override fun say(text: String) {
        if (!ready) return
        stopPlayback()
        val gen = ++generation
        worker.execute {
            val pcm = synth(text) ?: return@execute
            // a stop() or a newer say() since this was queued: drop it rather than talk over the rider
            if (gen != generation) return@execute
            synchronized(this) { if (gen != generation) return@execute; play(pcm) }
        }
    }

    private fun play(samples: FloatArray) {
        try {
            val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f * 0.9f).toInt().toShort() }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val fmt = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val track = AudioTrack(attrs, fmt, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { try { t.release() } catch (_: Throwable) {}; if (playing === t) playing = null }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            playing = track
            track.play()
        } catch (_: Throwable) {}
    }

    /** Silences now and invalidates anything queued, so nothing speaks after END or mute. */
    override fun stop() { generation++; stopPlayback() }
    private fun stopPlayback() { playing?.let { t -> playing = null; try { t.stop(); t.release() } catch (_: Throwable) {} } }

    override fun shutdown() { stop(); worker.execute { try { tts?.release() } catch (_: Throwable) {}; tts = null; ready = false } }

    companion object { const val VERSION = "lessac-low-int8-1" }
}
