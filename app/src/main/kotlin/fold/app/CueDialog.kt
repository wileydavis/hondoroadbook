package fold.app

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import fold.core.Cue
import fold.core.Turn

/**
 * Pick a cue: the same arrows the HUD draws, plus NOTE and DANGER, and words to say.
 * Built for a gloved thumb at a stop: one tap on an arrow is a complete cue; words are optional
 * except for a note, which is nothing but words.
 */
object CueDialog {
    private val turns = listOf(
        Turn.SHARP_LEFT, Turn.LEFT, Turn.SLIGHT_LEFT, Turn.STRAIGHT, Turn.SLIGHT_RIGHT, Turn.RIGHT, Turn.SHARP_RIGHT,
        Turn.KEEP_LEFT, Turn.UTURN, Turn.KEEP_RIGHT, Turn.NOTE, Turn.DANGER
    )

    /** [existing] = editing that cue (shows DELETE). onSave gets turn, note, level. */
    fun show(act: Activity, lat: Double, lon: Double, existing: Cue?, onSave: (Cue) -> Unit, onDelete: (() -> Unit)? = null) {
        val ctx = act
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        var chosen: Turn = existing?.turn ?: Turn.LEFT
        var level = existing?.level?.takeIf { it > 0 } ?: 2
        val cells = ArrayList<Pair<Turn, View>>()
        val grid = GridLayout(ctx).apply { columnCount = 4; rowCount = 3 }
        val size = minOf((ctx.resources.displayMetrics.widthPixels - ctx.dp(80)) / 4, ctx.dp(92))
        val note = EditText(ctx).apply {
            hint = "Words to say (optional for arrows)"; textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existing?.note ?: ""); setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
        }
        val levelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        val levelButtons = ArrayList<Button>()
        fun paintLevel() { for ((i, b) in levelButtons.withIndex()) b.alpha = if (i + 1 == level) 1f else 0.4f }
        levelRow.addView(TextView(ctx).apply { text = "Severity"; textSize = 14f; setPadding(0, 0, ctx.dp(12), 0) })
        for (l in 1..3) levelRow.addView(Button(ctx).apply { text = "!".repeat(l); textSize = 14f; setOnClickListener { level = l; paintLevel() }; levelButtons.add(this) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        paintLevel()
        fun paint() {
            for ((t, v) in cells) v.alpha = if (t == chosen) 1f else 0.35f
            levelRow.visibility = if (chosen == Turn.DANGER) View.VISIBLE else View.GONE
            note.hint = when (chosen) { Turn.NOTE -> "What to say here"; Turn.DANGER -> "What the danger is (optional)"; else -> "Words to say (optional)" }
        }
        for (t in turns) {
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ctx.dp(2), ctx.dp(4), ctx.dp(2), ctx.dp(4)) }
            val av = ArrowView(ctx).apply { turn = t; progress = 1f; if (t == Turn.DANGER) exit = 0 }
            cell.addView(av, LinearLayout.LayoutParams(size - ctx.dp(8), size - ctx.dp(8)))
            cell.addView(TextView(ctx).apply { text = t.spoken.uppercase(); textSize = 10f; gravity = Gravity.CENTER; maxLines = 1 })
            cell.setOnClickListener { chosen = t; paint() }
            cells.add(t to cell)
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED)).apply { width = size; height = GridLayout.LayoutParams.WRAP_CONTENT })
        }
        paint()
        root.addView(grid)
        root.addView(levelRow)
        root.addView(note)
        val b = AlertDialog.Builder(ctx).setTitle(if (existing == null) "Add cue" else "Edit cue").setView(android.widget.ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("SAVE", null)
            .setNegativeButton(R.string.cancel, null)
        if (onDelete != null) b.setNeutralButton("DELETE") { _, _ -> onDelete() }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val words = note.text.toString().trim()
                if (chosen == Turn.NOTE && words.isEmpty()) { note.error = "A note needs words"; return@setOnClickListener }
                onSave(Cue(lat, lon, chosen, words, if (chosen == Turn.DANGER) level else 0))
                dlg.dismiss()
            }
        }
        dlg.show()
    }
}
