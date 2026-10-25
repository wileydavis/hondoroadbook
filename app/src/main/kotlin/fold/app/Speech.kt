package fold.app

import android.content.Context

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
    fun prepare(text: String) { if (enabled && text.isNotBlank()) engine?.prepare(text) }
    fun say(text: String) { if (enabled && text.isNotBlank()) engine?.say(text) }
    fun stop() { engine?.stop() }
    fun shutdown() { engine?.shutdown(); engine = null }
}
