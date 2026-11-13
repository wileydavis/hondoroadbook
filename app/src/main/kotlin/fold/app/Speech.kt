package fold.app

import android.content.Context
import fold.core.SpeechText

/** What a speech engine must do. Roadbook bundles one; Fold has none. */
interface SpeechEngine {
    val ready: Boolean
    /** Synthesize ahead of time so [say] starts instantly. */
    fun prepare(text: String)
    fun say(text: String)
    fun stop()
    fun shutdown()
}

/**
 * Spoken cues. Everything is best-effort: with no engine the pips and chimes carry the cue alone.
 * The engine comes from the flavor ([SpeechFactory]).
 */
object Speech {
    private var engine: SpeechEngine? = null
    val ready: Boolean get() = engine?.ready == true
    @Volatile var enabled = false

    fun init(ctx: Context) { if (engine == null) engine = try { SpeechFactory.create(ctx.applicationContext) } catch (_: Throwable) { null } }
    // the written cue as it should be heard: "US-287" → "U S two eighty-seven", a roll chart's capitals as words
    private fun spoken(text: String) = try { SpeechText.forVoice(text) } catch (_: Throwable) { text }
    fun prepare(text: String) { if (enabled && text.isNotBlank()) engine?.prepare(spoken(text)) }
    fun say(text: String) { if (enabled && text.isNotBlank()) engine?.say(spoken(text)) }
    /** The VOICE screen: hear the chosen voice whether or not cues are being spoken. */
    fun test(text: String) { (engine as? PiperEngine)?.test(spoken(text)) ?: engine?.say(spoken(text)) }
    /** The natural voice's speaker id, or -1 for the built-in voice. */
    fun useVoice(sid: Int) { (engine as? PiperEngine)?.useVoice(sid) }
    fun stop() { engine?.stop() }
    fun shutdown() { engine?.shutdown(); engine = null }
}
