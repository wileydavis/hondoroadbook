package fold.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The one sound Fold makes: short synthesized pips, no audio files. Played as navigation guidance,
 * so they go to a helmet headset or car stereo over Bluetooth like any nav app's voice would.
 *
 * Approach cue: as the bar fills, pips rise in pitch (one octave over the approach) and come faster.
 * At the turn, a two-note chime. Off route: one low buzz. Arrival: three rising notes.
 */
object Tone {
    private const val RATE = 22050
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val cache = HashMap<String, ShortArray>()
    @Volatile var enabled = false

    /** One or more notes back to back. freq in Hz, each ms long, with a soft attack/release so there is no click. */
    private fun render(notes: List<Pair<Double, Int>>, gap: Int = 0): ShortArray {
        val total = notes.sumOf { it.second } + gap * (notes.size - 1)
        val out = ShortArray(total * RATE / 1000)
        var pos = 0
        for ((i, n) in notes.withIndex()) {
            val (f, ms) = n
            val len = ms * RATE / 1000
            val ramp = min(len / 4, RATE / 100)   // ≤10 ms
            for (k in 0 until len) {
                val env = when { k < ramp -> k / ramp.toDouble(); k > len - ramp -> (len - k) / ramp.toDouble(); else -> 1.0 }
                // a little second harmonic so it cuts through road noise
                val v = sin(2 * PI * f * k / RATE) * 0.8 + sin(4 * PI * f * k / RATE) * 0.2
                out[pos + k] = (v * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len + if (i < notes.size - 1) gap * RATE / 1000 else 0
        }
        return out
    }

    private fun play(key: String, build: () -> ShortArray) {
        if (!enabled) return
        val pcm = synchronized(cache) { cache.getOrPut(key, build) }
        try {
            val track = AudioTrack(attrs, AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                pcm.size * 2, AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { try { t.release() } catch (_: Throwable) {} }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (_: Throwable) {}
    }

    /** Approach pip: progress 0..1 → pitch 520..1040 Hz. Quantized to 16 steps so the cache stays small. */
    fun pip(progress: Double) {
        val step = (progress.coerceIn(0.0, 1.0) * 16).toInt()
        val f = 520.0 * Math.pow(2.0, step / 16.0)
        play("pip$step") { render(listOf(f to 70)) }
    }
    /** Interval between pips for a given progress: 1.6 s far out, 0.25 s at the end. */
    fun pipIntervalMs(progress: Double): Long = (1600 - 1350 * progress.coerceIn(0.0, 1.0)).toLong()

    fun turn() = play("turn") { render(listOf(1040.0 to 90, 1320.0 to 160), 20) }
    fun offRoute() = play("off") { render(listOf(220.0 to 350)) }
    fun arrive() = play("arrive") { render(listOf(660.0 to 120, 880.0 to 120, 1320.0 to 260), 30) }
    fun back() = play("back") { render(listOf(880.0 to 80, 660.0 to 120), 20) }
    /** Hazard: low triple buzz, one more at level 3. */
    fun danger(level: Int) = play("danger$level") { render(List(if (level >= 3) 4 else 3) { 260.0 to 110 }, 70) }
    /** A note point: two soft mid notes. */
    fun note() = play("note") { render(listOf(740.0 to 90, 740.0 to 90), 60) }
    /** Spoken-word lead-in so a phrase never starts from silence on a headset. */
    fun attention() = play("attn") { render(listOf(880.0 to 60), 0) }
}
