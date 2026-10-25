package fold.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import fold.core.Turn
import kotlin.math.min

/**
 * The turn glyph and the approach bar are one thing: an arrow whose shaft is the bar. The grey track is the
 * whole turn shape; the red fill grows along it, in the direction of the turn, and meets the red arrowhead
 * at the moment of the turn.
 */
class ArrowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var turn: Turn = Turn.STRAIGHT
        set(v) { if (field != v) { field = v; rebuild(); invalidate() } }
    var exit: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var progress = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; invalidate() } }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.bar_bg); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent); style = Paint.Style.FILL }
    private val headOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.card); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink); style = Paint.Style.FILL }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.card); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink); style = Paint.Style.FILL }

    private val unit = Path()          // the turn shape in a 100x100 box
    private val scaled = Path()
    private val partial = Path()
    private val measure = PathMeasure()
    private val mtx = Matrix()
    private val headPath = Path()
    private var endPos = FloatArray(2); private var endTan = FloatArray(2)

    init { rebuild() }

    private fun rebuild() {
        unit.reset()
        when (turn) {
            Turn.STRAIGHT, Turn.DEPART -> { unit.moveTo(50f, 92f); unit.lineTo(50f, 24f) }
            Turn.RIGHT -> { unit.moveTo(32f, 92f); unit.lineTo(32f, 48f); unit.quadTo(32f, 32f, 48f, 32f); unit.lineTo(74f, 32f) }
            Turn.LEFT -> { unit.moveTo(68f, 92f); unit.lineTo(68f, 48f); unit.quadTo(68f, 32f, 52f, 32f); unit.lineTo(26f, 32f) }
            Turn.SLIGHT_RIGHT -> { unit.moveTo(42f, 92f); unit.lineTo(42f, 56f); unit.quadTo(42f, 44f, 50f, 36f); unit.lineTo(70f, 18f) }
            Turn.SLIGHT_LEFT -> { unit.moveTo(58f, 92f); unit.lineTo(58f, 56f); unit.quadTo(58f, 44f, 50f, 36f); unit.lineTo(30f, 18f) }
            Turn.KEEP_RIGHT -> { unit.moveTo(46f, 92f); unit.lineTo(46f, 60f); unit.quadTo(46f, 48f, 52f, 40f); unit.lineTo(62f, 22f) }
            Turn.KEEP_LEFT -> { unit.moveTo(54f, 92f); unit.lineTo(54f, 60f); unit.quadTo(54f, 48f, 48f, 40f); unit.lineTo(38f, 22f) }
            Turn.SHARP_RIGHT -> { unit.moveTo(36f, 92f); unit.lineTo(36f, 40f); unit.quadTo(36f, 22f, 50f, 30f); unit.lineTo(72f, 52f) }
            Turn.SHARP_LEFT -> { unit.moveTo(64f, 92f); unit.lineTo(64f, 40f); unit.quadTo(64f, 22f, 50f, 30f); unit.lineTo(28f, 52f) }
            Turn.UTURN -> { unit.moveTo(66f, 92f); unit.lineTo(66f, 46f); unit.arcTo(RectF(34f, 24f, 66f, 68f), 0f, -180f, false); unit.lineTo(34f, 70f) }
            Turn.ROUNDABOUT -> { unit.moveTo(50f, 92f); unit.lineTo(50f, 72f); unit.arcTo(RectF(32f, 34f, 68f, 70f), 90f, -180f, false); unit.lineTo(50f, 14f) }
            Turn.ARRIVE -> { unit.moveTo(50f, 92f); unit.lineTo(50f, 30f) }
            Turn.NOTE, Turn.DANGER -> { unit.moveTo(50f, 92f); unit.lineTo(50f, 46f) }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        val sw = s * 0.13f
        track.strokeWidth = sw; fillP.strokeWidth = sw; headOutline.strokeWidth = sw * 0.25f
        mtx.reset(); mtx.setScale(s / 100f, s / 100f); mtx.postTranslate((w - s) / 2f, (h - s) / 2f)
        unit.transform(mtx, scaled)
        measure.setPath(scaled, false)
        val len = measure.length
        c.drawPath(scaled, track)
        if (progress > 0f) { partial.reset(); measure.getSegment(0f, len * progress, partial, true); c.drawPath(partial, fillP) }
        measure.getPosTan(len, endPos, endTan)
        if (turn == Turn.NOTE || turn == Turn.DANGER) {
            // a sign at the end of the shaft: triangle with ! for danger, round sign with i for a note
            val x = endPos[0]; val y = endPos[1] - s * 0.17f; val r = s * 0.19f
            val signPaint = if (turn == Turn.DANGER) head else badge
            if (turn == Turn.DANGER) { val tri = Path(); tri.moveTo(x, y - r); tri.lineTo(x + r * 1.1f, y + r * 0.8f); tri.lineTo(x - r * 1.1f, y + r * 0.8f); tri.close(); c.drawPath(tri, headOutline); c.drawPath(tri, signPaint) }
            else { c.drawCircle(x, y, r, headOutline); c.drawCircle(x, y, r, signPaint) }
            badgeText.textSize = r * 1.3f
            c.drawText(if (turn == Turn.DANGER) "!" else "i", x, y + (if (turn == Turn.DANGER) r * 0.75f else r * 0.45f), badgeText)
            if (turn == Turn.DANGER && exit > 1) { badgeText.textSize = r * 0.5f; c.drawText("!".repeat(exit.coerceAtMost(3)), x + r * 1.4f, y + r * 0.7f, badgeText.also { it.color = badge.color }); badgeText.color = context.getColor(R.color.card) }
            return
        }
        if (turn == Turn.ARRIVE) {
            // a flag at the end of the shaft instead of a head
            val x = endPos[0]; val y = endPos[1]
            c.drawRect(x - sw * 0.3f, y - s * 0.22f, x + sw * 0.3f, y, flag)
            val cell = s * 0.06f
            for (r in 0 until 3) for (col in 0 until 4) if ((r + col) % 2 == 0) c.drawRect(x + sw * 0.3f + col * cell, y - s * 0.22f + r * cell, x + sw * 0.3f + (col + 1) * cell, y - s * 0.22f + (r + 1) * cell, flag)
            return
        }
        // arrowhead at the end of the shaft, pointing along it, protruding beyond the bar
        val hl = s * 0.2f; val hw = s * 0.15f
        val tx = endTan[0]; val ty = endTan[1]; val nx = -ty; val ny = tx
        val tipX = endPos[0] + tx * hl * 0.85f; val tipY = endPos[1] + ty * hl * 0.85f
        val baseX = endPos[0] - tx * hl * 0.15f; val baseY = endPos[1] - ty * hl * 0.15f
        headPath.reset(); headPath.moveTo(tipX, tipY); headPath.lineTo(baseX + nx * hw, baseY + ny * hw); headPath.lineTo(baseX - nx * hw, baseY - ny * hw); headPath.close()
        c.drawPath(headPath, headOutline); c.drawPath(headPath, head)
        if (turn == Turn.ROUNDABOUT && exit > 0) {
            val bx = (w + s) / 2f - s * 0.16f; val by = (h + s) / 2f - s * 0.16f
            c.drawCircle(bx, by, s * 0.14f, badge); badgeText.textSize = s * 0.18f
            c.drawText(exit.toString(), bx, by + badgeText.textSize * 0.35f, badgeText)
        }
    }
}

/** The approach bar. Fills left to right; the right edge is the turn. */
class BarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var progress = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; invalidate() } }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.bar_bg) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        c.drawRoundRect(0f, 0f, w, h, r, r, bg)
        if (progress > 0f) c.drawRoundRect(0f, 0f, w * progress, h, r, r, fg)
        c.drawRect(w - h * 0.35f, 0f, w, h, tick)
    }
}

/** Scale bar: picks a round distance that fits ~100 dp and draws it with end ticks and a label. */
class ScaleView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var metersPerPx = 1.0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink); strokeWidth = 2f * ctx.resources.displayMetrics.density; style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.card); strokeWidth = 6f * ctx.resources.displayMetrics.density; style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink); textSize = 12f * ctx.resources.displayMetrics.scaledDensity; isFakeBoldText = true }
    private val textHalo = Paint(text).apply { color = ctx.getColor(R.color.card); style = Paint.Style.STROKE; strokeWidth = 4f * ctx.resources.displayMetrics.density }
    private var label = ""; private var px = 0f
    fun update(mpp: Double) {
        if (mpp <= 0 || mpp.isNaN()) return
        metersPerPx = mpp
        val d = context.resources.displayMetrics.density
        val target = 110f * d * metersPerPx.toFloat()         // meters we'd like the bar to span
        val nice: Float; val lbl: String
        if (Units.metric) {
            nice = niceNumber(target); lbl = if (nice >= 1000) "${(nice / 1000).toInt()} km" else "${nice.toInt()} m"
        } else {
            val ft = target * 3.28084f
            if (ft < 2640) { nice = niceNumber(ft) / 3.28084f; lbl = "${(nice * 3.28084f).toInt()} ft" }
            else { val mi = niceNumber(ft / 5280f); nice = mi * 1609.344f; lbl = "${mi.toInt()} mi" }
        }
        px = (nice / metersPerPx).toFloat()
        label = lbl
        requestLayout(); invalidate()
    }
    private fun niceNumber(v: Float): Float { val exp = Math.floor(Math.log10(v.toDouble())).toInt(); val base = Math.pow(10.0, exp.toDouble()).toFloat(); val f = v / base; return base * when { f >= 5 -> 5f; f >= 2 -> 2f; else -> 1f } }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((px + text.measureText(label) + 16 * resources.displayMetrics.density).toInt().coerceAtLeast(40), MeasureSpec.getSize(h)) }
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val y = h - 4f; val tick = h * 0.45f
        c.drawLine(0f, y, px, y, halo); c.drawLine(0f, y, 0f, y - tick, halo); c.drawLine(px, y, px, y - tick, halo)
        c.drawLine(0f, y, px, y, line); c.drawLine(0f, y, 0f, y - tick, line); c.drawLine(px, y, px, y - tick, line)
        val tx = px + 6 * resources.displayMetrics.density; val ty = y - 2f
        c.drawText(label, tx, ty, textHalo); c.drawText(label, tx, ty, text)
    }
}


/** A small compass rose: the needle points to true north for the current map rotation. */
class CompassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    /** map bearing in degrees (0 = north up) */
    var bearing = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink_soft); style = Paint.Style.STROKE; strokeWidth = 1.5f * ctx.resources.displayMetrics.density }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent); style = Paint.Style.FILL }
    private val south = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink_soft); style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.ink); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val p = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val r = min(w, h) / 2 - ring.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        c.save(); c.rotate(-bearing, cx, cy)
        p.reset(); p.moveTo(cx, cy - r * 0.85f); p.lineTo(cx - r * 0.22f, cy); p.lineTo(cx + r * 0.22f, cy); p.close(); c.drawPath(p, north)
        p.reset(); p.moveTo(cx, cy + r * 0.85f); p.lineTo(cx - r * 0.22f, cy); p.lineTo(cx + r * 0.22f, cy); p.close(); c.drawPath(p, south)
        text.textSize = r * 0.55f
        c.drawText("N", cx, cy - r * 0.95f - text.textSize * 0.1f, text)
        c.restore()
    }
}
