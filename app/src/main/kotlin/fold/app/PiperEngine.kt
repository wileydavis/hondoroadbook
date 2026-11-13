package fold.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The voice that ships inside Roadbook: a Piper VITS voice (en_US lessac, int8) run by sherpa-onnx,
 * entirely on the phone. No speech engine needs to be installed, which matters on GrapheneOS.
 *
 * Phrases are synthesized ahead of time ([prepare], called as soon as a cue becomes the next one)
 * and cached as PCM, so [say] at the window opening starts in milliseconds. Audio goes out as
 * navigation guidance, the same stream as the pips, so a helmet headset gets it.
 *
 * The natural voice (Kokoro, a download: [Voices]) runs beside it when chosen. It is slower to synthesize, so it
 * works on its own thread: a cue it has prepared plays in the natural voice; one it has not gets [WAIT_MS] to be
 * synthesized, and the built-in voice says it if that is not enough. The ride never waits on the bigger model.
 */
class PiperEngine(private val ctx: Context) : SpeechEngine {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "piper").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile override var ready = false; private set
    private val natWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "kokoro").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var tts: OfflineTts? = null
    private var rate = 16000
    @Volatile private var nat: OfflineTts? = null
    @Volatile private var natSid = -1
    private class Pcm(val samples: FloatArray, val rate: Int)
    private val cache = object : LinkedHashMap<String, Pcm>(16, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>?) = size > 16 }
    @Volatile private var playing: AudioTrack? = null
    @Volatile private var generation = 0   // bumped by say() and stop(); queued synthesis checks it before playing

    init { worker.execute { load() }; useVoice(Voices.chosen(ctx)) }

    /** The natural voice's speaker, or -1 for the built-in voice only. Loads or frees the model off the ride's threads. */
    fun useVoice(sid: Int) {
        natWorker.execute {
            if (sid < 0 || !Voices.installed(ctx)) { nat?.let { n -> nat = null; natSid = -1; try { n.release() } catch (_: Throwable) {} }; return@execute }
            if (nat == null) nat = loadNatural()
            natSid = if (nat != null) sid else -1
            synchronized(cache) { cache.keys.removeAll { it.startsWith("k") } }
        }
    }
    val natural: Boolean get() = nat != null && natSid >= 0

    private fun loadNatural(): OfflineTts? = try {
        val d = Voices.dir(ctx)
        val k = OfflineTtsKokoroModelConfig(model = File(d, "model.int8.onnx").path, voices = File(d, "voices.bin").path, tokens = File(d, "tokens.txt").path,
            dataDir = File(d, "espeak-ng-data").path, lexicon = File(d, "lexicon-us-en.txt").path, lengthScale = 1.0f)
        OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = k, numThreads = 2, provider = "cpu")))
    } catch (e: Throwable) { android.util.Log.w("Roadbook", "natural voice unavailable: $e"); null }

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

    private fun synth(text: String): Pcm? {
        synchronized(cache) { cache["p$text"]?.let { return it } }
        val t = tts ?: return null
        val a = try { t.generate(text, 0, 1.0f) } catch (_: Throwable) { return null }
        val p = Pcm(a.samples, rate)
        synchronized(cache) { cache["p$text"] = p }
        return p
    }
    /** natural voice: runs on [natWorker] only */
    private fun synthNatural(text: String): Pcm? {
        val sid = natSid; val key = "k$sid|$text"
        synchronized(cache) { cache[key]?.let { return it } }
        val t = nat ?: return null
        if (sid < 0) return null
        val a = try { t.generate(text, sid, 1.0f) } catch (_: Throwable) { return null }
        val p = Pcm(a.samples, a.sampleRate)
        synchronized(cache) { cache[key] = p }
        return p
    }
    private fun cachedNatural(text: String): Pcm? = synchronized(cache) { cache["k$natSid|$text"] }

    override fun prepare(text: String) {
        if (natural) natWorker.execute { synthNatural(text) }
        if (ready) worker.execute { synth(text) }
    }

    /** The chosen voice saying [text] for the VOICE screen's TEST: waits for the natural voice however long it takes. */
    fun test(text: String) {
        stopPlayback()
        val gen = ++generation
        (if (natural) natWorker else worker).execute {
            val pcm = (if (natural) synthNatural(text) else null) ?: synth(text) ?: return@execute
            synchronized(playLock) { if (gen != generation) return@execute; play(pcm) }
        }
    }

    override fun say(text: String) {
        if (!ready && !natural) return
        stopPlayback()
        val gen = ++generation
        // the natural voice if it has this phrase, or can make it in time; the built-in voice otherwise
        val natFuture: Future<Pcm?>? = if (natural && cachedNatural(text) == null) natWorker.submit<Pcm?> { synthNatural(text) } else null
        worker.execute {
            if (gen != generation) return@execute                 // stale before it was even synthesised
            val pcm = (if (natural) cachedNatural(text) ?: try { natFuture?.get(WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { null } else null)
                ?: synth(text) ?: return@execute
            // a stop() or a newer say() since this was queued: drop it rather than talk over the rider. The check
            // and the start of playback sit under the same lock stop() takes, so once stop() returns nothing starts.
            synchronized(playLock) { if (gen != generation) return@execute; play(pcm) }
        }
    }
    private val playLock = Any()

    private fun play(p: Pcm) {
        val samples = p.samples; val rate = p.rate
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

    /** Silences now and invalidates anything queued, so nothing speaks after END or mute: it takes the playback lock, so a phrase between its final check and play() finishes that call before this returns, and is then stopped. */
    override fun stop() { generation++; synchronized(playLock) { stopPlayback() } }
    private fun stopPlayback() { playing?.let { t -> playing = null; try { t.stop(); t.release() } catch (_: Throwable) {} } }

    override fun shutdown() {
        stop()
        worker.execute { try { tts?.release() } catch (_: Throwable) {}; tts = null; ready = false }
        natWorker.execute { try { nat?.release() } catch (_: Throwable) {}; nat = null; natSid = -1 }
    }

    companion object { const val VERSION = "lessac-low-int8-1"; const val WAIT_MS = 1200L }
}
