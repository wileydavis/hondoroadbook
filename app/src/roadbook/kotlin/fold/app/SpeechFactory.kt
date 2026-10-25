package fold.app

import android.content.Context

object SpeechFactory { fun create(ctx: Context): SpeechEngine = PiperEngine(ctx) }
