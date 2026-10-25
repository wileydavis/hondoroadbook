package fold.app

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken cues through the phone's text-to-speech engine, if it has one. GrapheneOS ships none by
 * default, so everything here is best-effort: with no engine the pips and chimes carry the cue alone.
 */
object Speech {
    private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set
    @Volatile var enabled = false
    private var pending: String? = null

    fun init(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale.US
                        tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        tts?.setSpeechRate(1.05f)
                    } catch (_: Throwable) {}
                    ready = true
                    pending?.let { say(it) }; pending = null
                } else ready = false
            }
        } catch (_: Throwable) { ready = false }
    }

    /** Speaks [text] now, interrupting anything earlier. Silently does nothing without an engine. */
    fun say(text: String) {
        if (!enabled || text.isBlank()) return
        val t = tts ?: return
        if (!ready) { pending = text; return }
        try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue") } catch (_: Throwable) {}
    }

    fun shutdown() { try { tts?.shutdown() } catch (_: Throwable) {}; tts = null; ready = false }
}
