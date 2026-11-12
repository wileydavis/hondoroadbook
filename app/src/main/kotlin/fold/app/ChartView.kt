package fold.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import fold.core.Chart
import fold.core.ChartBuild
import fold.core.ChartGlyphs
import fold.core.ChartRow
import fold.core.TripMeter

/**
 * The chart reader: the roll chart's rows on the phone, the trip meter above them. The rows are drawn from the
 * shared core (Chart.kt), so they carry the same mileage and tulips the planner printed, laid out for a handlebar:
 * big figures, a big tulip, the words beside it. The list moves itself as the meter passes each row; a swipe moves
 * it by hand and it settles back on the rider's place a few seconds later.
 *
 * Touch: − and + nudge the meter by a hundredth; a long press on the meter sets it to the row at the top (the rider
 * is standing at that landmark); a swipe scrolls.
 */
class ChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var chart: ChartBuild? = null
        set(v) { field = v; manual = 0f; invalidate() }
    var meter: TripMeter? = null
    /** called after a touch changed the meter (the activity saves nothing, but buzzes) */
    var onMeterTouched: (() -> Unit)? = null
    /** the map HUD's warning, shown under the meter while the chart covers the map ("OFF TRACK", "NO GPS") */
    var status: String? = null

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * d
    private val ink get() = context.getColor(R.color.ink)
    private val soft get() = context.getColor(R.color.ink_soft)
    private val card get() = context.getColor(R.color.card)
    private val paper get() = context.getColor(R.color.paper)
    private val accent get() = context.getColor(R.color.accent)
    private val red = 0xFFE30613.toInt(); private val orange = 0xFFF39200.toInt(); private val yellow = 0xFFFFED00.toInt(); private val pink = 0xFFF6A8C8.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    /** the row index at the top of the list, as the meter wants it; [manual] is a swipe's offset in rows */
    var shown = 0; private set
    private var manual = 0f
    private var lastTouch = 0L
    private var lastKey = ""
    private val minus = RectF(); private val plus = RectF(); private val meterBox = RectF()

    /** Called each second (and on a fix) with how far ahead a row should come up. */
    fun refresh(leadMeters: Double) {
        val m = meter ?: return
        val want = m.showRow(leadMeters)
        if (manual != 0f && System.currentTimeMillis() - lastTouch > 4000) manual = 0f   // settle back on the rider's place
        val key = m.reading + "|" + status + "|" + want + "|" + Math.round(hereLat * 50000) + "," + Math.round(hereLon * 50000) + "|" + manual + "|" + m.source
        if (want != shown) shown = want
        if (key != lastKey) { lastKey = key; invalidate() }
    }

    /** the room inside the system bars (the activity sets the padding) */
    private val cw get() = (width - paddingLeft - paddingRight).toFloat()
    private val chh get() = (height - paddingTop - paddingBottom).toFloat()
    private val landscape get() = cw > chh
    private val meterH get() = if (landscape) chh.toFloat() else dp(118f)
    private val meterW get() = if (landscape) cw * 0.3f else cw.toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(paper)
        val ch = chart ?: return
        c.save(); c.translate(paddingLeft.toFloat(), paddingTop.toFloat()); drawAll(c, ch); c.restore()
    }

    private fun drawAll(c: Canvas, ch: ChartBuild) {
        val st = ch.settings
        // the rows, below (or right of) the meter
        val left = if (landscape) meterW else 0f; val top = if (landscape) 0f else meterH
        val w = cw - left; val h = chh - top
        val rowH = if (landscape) h / 2f else maxOf(dp(196f), h / 3.1f)
        c.save(); c.clipRect(left, top, cw.toFloat(), chh.toFloat())
        // the row the meter is at sits second, the one just passed above it, faded
        val base = shown + manual
        val firstIdx = kotlin.math.floor(base).toInt() - 1
        // a strip of the row just passed shows above (a whole banner, it being short)
        val prevH = ch.rows.getOrNull(firstIdx)?.let { if (it.kind == ChartRow.Kind.BANNER) rowH * 0.42f else rowH } ?: rowH
        var y = top - (base - kotlin.math.floor(base)) * rowH - (if (prevH < rowH) 0f else rowH * 0.7f)
        var k = firstIdx
        while (y < chh && k < ch.rows.size) {
            if (k >= 0) {
                val r = ch.rows[k]
                val hh = if (r.kind == ChartRow.Kind.BANNER) rowH * 0.42f else rowH
                drawRow(c, r, k, left, y, w, hh, st, k == shown, k < shown)
                y += hh
            } else y += rowH
            k++
        }
        c.restore()
        drawInset(c, left, top, w, h)
        drawMeter(c, st)
    }

    // ---- the inset: the line around the rider, the way ahead up, the chart's rows as ticks ----------------------------
    private var tLats: DoubleArray? = null; private var tLons: DoubleArray? = null; private var tCum: DoubleArray? = null; private var rowsAt = DoubleArray(0)
    private var hereLat = Double.NaN; private var hereLon = Double.NaN; private var hereAlong = 0.0
    /** the rider can fold it away (a tap) when it covers a row's corner */
    var insetOn = true
    private val inset = RectF()
    fun setTrack(lats: DoubleArray?, lons: DoubleArray?, cum: DoubleArray?) {
        tLats = lats; tLons = lons; tCum = cum
        rowsAt = chart?.rows?.filter { it.kind != ChartRow.Kind.BANNER }?.map { it.at }?.toDoubleArray() ?: DoubleArray(0)
        invalidate()
    }
    /** where the GPS puts the rider, and how far along the line the follower last had them */
    fun here(lat: Double, lon: Double, along: Double) { hereLat = lat; hereLon = lon; hereAlong = along }
    private fun drawInset(c: Canvas, x0: Float, y0: Float, w: Float, h: Float) {
        val la = tLats ?: return; val lo = tLons ?: return; val cum = tCum ?: return
        if (hereLat.isNaN()) return
        val s = dp(128f)
        if (!insetOn) {
            // folded: a small chip to bring it back
            text.textSize = 12 * sp; val tw = text.measureText("MAP") + dp(20f)
            inset.set(x0 + w - tw - dp(12f), y0 + h - dp(44f), x0 + w - dp(12f), y0 + h - dp(16f))
            fill.color = ink; c.drawRoundRect(inset, dp(14f), dp(14f), fill)
            text.color = card; text.textAlign = Paint.Align.CENTER; c.drawText("MAP", inset.centerX(), inset.centerY() + dp(4f), text); text.textAlign = Paint.Align.LEFT
            return
        }
        inset.set(x0 + w - s - dp(12f), y0 + h - s - dp(16f), x0 + w - dp(12f), y0 + h - dp(16f))
        val sk = Chart.sketch(la, lo, cum, hereLat, hereLon, hereAlong, rowsAt) ?: return
        fill.color = paper; c.drawRoundRect(inset, dp(12f), dp(12f), fill)
        stroke.color = soft; stroke.alpha = 255; stroke.strokeWidth = dp(1f); stroke.pathEffect = null; c.drawRoundRect(inset, dp(12f), dp(12f), stroke)
        c.save(); c.clipRect(inset)
        // 1 dp to 8 m: 700 m ahead fits above the rider, who sits two thirds down
        val k = s / 1000f; val ox = inset.centerX(); val oy = inset.top + s * 0.72f
        path.reset()
        for (i in 0 until sk.line.size / 2) { val x = ox + sk.line[2 * i].toFloat() * k; val y = oy + sk.line[2 * i + 1].toFloat() * k; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        stroke.color = ink; stroke.strokeWidth = dp(4f); c.drawPath(path, stroke)
        fill.color = ink; for (i in 0 until sk.marks.size / 2) c.drawCircle(ox + sk.marks[2 * i].toFloat() * k, oy + sk.marks[2 * i + 1].toFloat() * k, dp(4.5f), fill)
        // the rider: an arrow pointing up the page (the way ahead)
        val rx = ox + sk.riderX.toFloat() * k; val ry = oy + sk.riderY.toFloat() * k
        path.reset(); path.moveTo(rx, ry - dp(10f)); path.lineTo(rx + dp(7f), ry + dp(7f)); path.lineTo(rx, ry + dp(3f)); path.lineTo(rx - dp(7f), ry + dp(7f)); path.close()
        fill.color = accent; c.drawPath(path, fill); stroke.color = card; stroke.strokeWidth = dp(1.5f); c.drawPath(path, stroke)
        c.restore()
    }

    private fun drawMeter(c: Canvas, st: fold.core.ChartSettings) {
        val m = meter
        fill.color = card; c.drawRect(0f, 0f, meterW, meterH, fill)
        fill.color = soft; if (!landscape) c.drawRect(0f, meterH - dp(1f), meterW, meterH, fill) else c.drawRect(meterW - dp(1f), 0f, meterW, meterH, fill)
        val bw = dp(76f); val pad = dp(10f)
        if (landscape) {
            // the meter in a column: the reading, then − and + side by side under it
            // (the menu button sits at the column's foot, so the meter keeps to its upper part)
            meterBox.set(pad, pad, meterW - pad, meterH * 0.36f)
            minus.set(pad, meterH * 0.4f, meterW / 2 - pad / 2, meterH * 0.4f + bw)
            plus.set(meterW / 2 + pad / 2, meterH * 0.4f, meterW - pad, meterH * 0.4f + bw)
        } else {
            minus.set(pad, pad, pad + bw, meterH - pad)
            plus.set(meterW - pad - bw, pad, meterW - pad, meterH - pad)
            meterBox.set(minus.right + pad, pad, plus.left - pad, meterH - pad)
        }
        for ((r, s) in listOf(minus to "−", plus to "+")) {
            fill.color = ink; c.drawRoundRect(r, dp(14f), dp(14f), fill)
            text.color = card; text.textSize = 40 * sp; text.textAlign = Paint.Align.CENTER
            c.drawText(s, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        val reading = m?.reading ?: Chart.fmt(0, st)
        text.color = ink; text.textAlign = Paint.Align.CENTER
        text.textSize = (if (landscape) 46 else 54) * sp
        while (text.measureText(reading) > meterBox.width() && text.textSize > 20 * sp) text.textSize -= 2 * sp
        c.drawText(reading, meterBox.centerX(), meterBox.centerY() + text.textSize * 0.28f, text)
        text.textSize = 12 * sp; text.color = soft
        val unit = (if (st.units == "km") "KM" else "MI") + " · " + (if (m?.source == TripMeter.Source.FREE) "ODOMETER" else "ALONG THE ROUTE") + (if (st.scale != 1.0) " · ×" + st.scale else "")
        val st2 = status
        if (st2 != null) {
            text.textSize = 13 * sp; val tw = text.measureText(st2) + dp(16f)
            fill.color = accent; c.drawRoundRect(meterBox.centerX() - tw / 2, meterBox.bottom - dp(20f), meterBox.centerX() + tw / 2, meterBox.bottom + dp(2f), dp(11f), dp(11f), fill)
            text.color = 0xFFFFFFFF.toInt(); c.drawText(st2, meterBox.centerX(), meterBox.bottom - dp(4f), text)
        } else c.drawText(unit, meterBox.centerX(), meterBox.bottom - dp(2f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawRow(c: Canvas, r: ChartRow, idx: Int, x0: Float, y0: Float, w: Float, h: Float, st: fold.core.ChartSettings, current: Boolean, passed: Boolean) {
        val alpha = if (passed) 110 else 255
        val pad = dp(12f)
        fill.color = card; fill.alpha = 255; c.drawRect(x0, y0, x0 + w, y0 + h, fill)
        fill.color = soft; fill.alpha = 90; c.drawRect(x0, y0 + h - dp(1f), x0 + w, y0 + h, fill); fill.alpha = 255
        if (r.kind == ChartRow.Kind.BANNER) {
            fill.color = ink; c.drawRect(x0, y0 + dp(4f), x0 + w, y0 + h - dp(4f), fill)
            text.color = card; text.textSize = 13 * sp; text.textAlign = Paint.Align.LEFT
            c.drawText("SECTION ${r.section} · ${if (r.sectionKind == "stage") "STAGE" else "TRANSIT"} · ${Chart.fmt(r.lengthH, st)} ${st.units.uppercase()}", x0 + pad, y0 + h * 0.38f, text)
            text.textSize = 22 * sp; c.drawText(r.sectionName.uppercase(), x0 + pad, y0 + h * 0.8f, text)
            return
        }
        if (current) { fill.color = accent; c.drawRect(x0, y0, x0 + dp(6f), y0 + h, fill) }
        // the figures: overall left, the leg right, what is left to the section's end under it (layout A)
        val lineH = dp(60f)
        text.alpha = alpha; text.textAlign = Paint.Align.LEFT
        text.color = soft; text.textSize = 12 * sp; c.drawText("${r.n}", x0 + pad, y0 + dp(16f), text)
        text.color = ink; text.textSize = 36 * sp; c.drawText(Chart.fmt(r.overall, st), x0 + pad + dp(28f), y0 + dp(42f), text)
        // the odometer is zeroed here: the figure boxed, as on paper
        if (r.resetHere || r.zeroOdo) { val tw = text.measureText(Chart.fmt(r.overall, st)); stroke.color = ink; stroke.alpha = alpha; stroke.strokeWidth = dp(2.5f); stroke.pathEffect = null; c.drawRect(x0 + pad + dp(22f), y0 + dp(10f), x0 + pad + dp(34f) + tw, y0 + dp(50f), stroke); stroke.alpha = 255
            text.color = soft; text.textSize = 11 * sp; c.drawText("ZERO", x0 + pad + dp(40f) + tw, y0 + dp(42f), text) }
        text.textAlign = Paint.Align.RIGHT
        val deltaTxt = Chart.fmt(r.delta, st)
        if (r.shortPartial) { val tw = text.apply { textSize = 22 * sp }.measureText(deltaTxt); fill.color = pink; fill.alpha = alpha; c.drawRoundRect(x0 + w - pad - tw - dp(8f), y0 + dp(14f), x0 + w - pad + dp(4f), y0 + dp(42f), dp(4f), dp(4f), fill); fill.alpha = 255 }
        text.textSize = 22 * sp; c.drawText(deltaTxt, x0 + w - pad, y0 + dp(36f), text)
        if (st.layout == "A" && st.decline) { text.color = soft; text.textSize = 11 * sp; c.drawText("TO GO " + Chart.fmt(r.decline, st), x0 + w - pad, y0 + dp(52f), text) }
        text.textAlign = Paint.Align.LEFT
        // the tulip, big, left
        val side = minOf(h - lineH - pad, w * 0.46f)
        val tx = x0 + pad + dp(4f); val ty = y0 + lineH + dp(2f)
        fill.color = paper; fill.alpha = alpha; c.drawRoundRect(tx, ty, tx + side, ty + side, dp(8f), dp(8f), fill); fill.alpha = 255
        val widths = Chart.Widths(side * 0.05, side * 0.036, side * 0.022, side * 0.1)
        val shapes = when (r.kind) { ChartRow.Kind.CUE -> Chart.tulip(r.tulip!!, side.toDouble(), widths); else -> Chart.flag(r.kind == ChartRow.Kind.FINISH, side.toDouble(), widths) }
        drawShapes(c, shapes, tx, ty, alpha, if (r.kind == ChartRow.Kind.CUE && r.tulip?.path == "offtrack") accent else ink)
        // symbols in the tulip's corners, as on paper
        val g = side * 0.26f; val m = side * 0.04f
        val used = HashMap<String, Int>()
        for (s in r.symbols) {
            val n = used.getOrPut(s.slot) { 0 }; used[s.slot] = n + 1
            val gx = if (s.slot[0] == 'l') tx + m + n * (g + m) else tx + side - m - g - n * (g + m)
            val gy = if (s.slot[1] == 'a') ty + side * 0.08f else ty + side * 0.66f
            drawShapes(c, ChartGlyphs.shapes(s.id, gx.toDouble(), gy.toDouble(), g.toDouble()), 0f, 0f, alpha, ink)
            for (wd in ChartGlyphs.words(s.id, gx.toDouble(), gy.toDouble(), g.toDouble())) { text.color = if (wd.light) card else ink; text.textSize = wd.size.toFloat(); text.textAlign = Paint.Align.CENTER; c.drawText(wd.text, wd.x.toFloat(), wd.y.toFloat(), text); text.textAlign = Paint.Align.LEFT }
        }
        // the words beside it: cautions first in red, then the instruction
        val wx = tx + side + pad; val ww = (x0 + w - pad) - wx
        var wy = ty
        if (r.caution > 0) { text.color = red; text.alpha = alpha; text.textSize = 26 * sp; c.drawText("!".repeat(r.caution), wx, wy + dp(24f), text); wy += dp(30f) }
        var sign = 0f
        if (r.speed > 0) {
            sign = dp(46f); val cx = x0 + w - pad - sign / 2; val cy = wy + sign / 2
            fill.color = card; c.drawCircle(cx, cy, sign / 2, fill); stroke.color = red; stroke.strokeWidth = dp(5f); stroke.pathEffect = null; c.drawCircle(cx, cy, sign / 2 - dp(2.5f), stroke)
            text.color = ink; text.textSize = 18 * sp; text.textAlign = Paint.Align.CENTER; c.drawText("${r.speed}", cx, cy + dp(6f), text); text.textAlign = Paint.Align.LEFT
        }
        if (r.info.isNotEmpty() && ww > dp(40f)) {
            words.color = ink; words.alpha = alpha; words.textSize = 21 * sp
            val pills = r.zone.isNotEmpty() || r.wpType.isNotEmpty()
            val avail = ty + side - wy - (if (r.capShow && st.layout == "B") dp(34f) else 0f) - (if (pills) dp(30f) else 0f)
            var lay = StaticLayout.Builder.obtain(r.info, 0, r.info.length, words, (ww - (if (sign > 0) sign + dp(6f) else 0f)).toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            while (lay.height > avail && words.textSize > 14 * sp) { words.textSize -= sp; lay = StaticLayout.Builder.obtain(r.info, 0, r.info.length, words, (ww - (if (sign > 0) sign + dp(6f) else 0f)).toInt().coerceAtLeast(10)).build() }
            c.save(); c.translate(wx, wy); c.clipRect(0f, 0f, ww, avail); lay.draw(c); c.restore()
        }
        // layout B: the CAP in yellow, the zone, the waypoint
        if (st.layout == "B" && r.capShow && r.kind == ChartRow.Kind.CUE) {
            val cap = "CAP ${r.cap}"; text.textSize = 18 * sp; text.color = ink; val tw = text.measureText(cap)
            fill.color = yellow; fill.alpha = alpha; c.drawRect(wx, ty + side - dp(28f), wx + tw + dp(12f), ty + side, fill); fill.alpha = 255
            c.drawText(cap, wx + dp(6f), ty + side - dp(8f), text)
        }
        var mx = x0 + w - pad
        for (label in listOfNotNull(r.zone.takeIf { it.isNotEmpty() }, r.wpType.takeIf { it.isNotEmpty() }?.let { it + (if (r.wpN > 0) "${r.wpN}" else "") })) {
            text.textSize = 13 * sp; val tw = text.measureText(label) + dp(14f)
            fill.color = when (label) { "DZ" -> orange; "FZ" -> 0xFF3AAA35.toInt(); else -> ink }; fill.alpha = alpha
            c.drawRoundRect(mx - tw, y0 + h - dp(30f), mx, y0 + h - dp(8f), dp(11f), dp(11f), fill); fill.alpha = 255
            text.color = card; text.textAlign = Paint.Align.CENTER; c.drawText(label, mx - tw / 2, y0 + h - dp(14f), text); text.textAlign = Paint.Align.LEFT
            mx -= tw + dp(6f)
        }
        // a speed zone frames the row in orange; danger 3 in red
        if (r.inZone) { stroke.color = orange; stroke.alpha = alpha; stroke.strokeWidth = dp(3f); stroke.pathEffect = null; c.drawRect(x0 + dp(2f), y0 + dp(2f), x0 + w - dp(2f), y0 + h - dp(2f), stroke) }
        if (r.caution == 3) { stroke.color = red; stroke.alpha = alpha; stroke.strokeWidth = dp(4f); stroke.pathEffect = null; c.drawRect(x0 + dp(5f), y0 + dp(5f), x0 + w - dp(5f), y0 + h - dp(5f), stroke) }
        stroke.alpha = 255; text.alpha = 255
    }

    private val path = Path()
    private fun drawShapes(c: Canvas, shapes: List<Chart.Shape>, dx: Float, dy: Float, alpha: Int, pathColor: Int) {
        for (s in shapes) {
            val col = if (s.role == "path") pathColor else ink
            when (s) {
                is Chart.Line -> {
                    if (s.width <= 0) continue
                    stroke.color = col; stroke.alpha = alpha; stroke.strokeWidth = s.width.toFloat(); stroke.pathEffect = s.dash?.let { DashPathEffect(floatArrayOf(it[0].toFloat(), it[1].toFloat()), 0f) }
                    path.reset(); s.pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(dx + p[0].toFloat(), dy + p[1].toFloat()) else path.lineTo(dx + p[0].toFloat(), dy + p[1].toFloat()) }
                    c.drawPath(path, stroke)
                }
                is Chart.PathShape -> {
                    path.reset()
                    for (op in s.ops) when (op[0].toInt()) {
                        0 -> path.moveTo(dx + op[1].toFloat(), dy + op[2].toFloat()); 1 -> path.lineTo(dx + op[1].toFloat(), dy + op[2].toFloat())
                        2 -> path.cubicTo(dx + op[1].toFloat(), dy + op[2].toFloat(), dx + op[3].toFloat(), dy + op[4].toFloat(), dx + op[5].toFloat(), dy + op[6].toFloat()); 3 -> path.close()
                    }
                    if (s.fill) { fill.color = col; fill.alpha = alpha; c.drawPath(path, fill); fill.alpha = 255 }
                    else if (s.width > 0) { stroke.color = col; stroke.alpha = alpha; stroke.strokeWidth = s.width.toFloat(); stroke.pathEffect = s.dash?.let { DashPathEffect(floatArrayOf(it[0].toFloat(), it[1].toFloat()), 0f) }; c.drawPath(path, stroke) }
                }
                is Chart.Circle -> {
                    if (s.fill) { fill.color = col; fill.alpha = alpha; c.drawCircle(dx + s.x.toFloat(), dy + s.y.toFloat(), s.r.toFloat(), fill); fill.alpha = 255 }
                    else { stroke.color = col; stroke.alpha = alpha; stroke.strokeWidth = s.width.toFloat(); stroke.pathEffect = null; c.drawCircle(dx + s.x.toFloat(), dy + s.y.toFloat(), s.r.toFloat(), stroke) }
                }
            }
        }
        stroke.pathEffect = null; stroke.alpha = 255
    }

    // ---- a handlebar remote (a Bluetooth keyboard to Android) ---------------------------------------------------------
    /** Look a row ahead (+1) or back (−1), as a swipe would; it settles back on the rider's place like one. */
    fun step(rows: Int) {
        val ch = chart ?: return
        manual = (kotlin.math.round(manual) + rows).coerceIn(-shown.toFloat(), (ch.rows.size - 1 - shown).toFloat().coerceAtLeast(0f))
        lastTouch = System.currentTimeMillis(); invalidate()
    }
    fun nudge(h: Int) { meter?.nudge(h); onMeterTouched?.invoke(); invalidate() }
    /** "I am at this row": the row with the bar, or the one stepped to */
    fun setHere() {
        val m = meter ?: return
        m.setToRow((shown + kotlin.math.round(manual).toInt()).coerceIn(0, (chart?.rows?.size ?: 1) - 1)); manual = 0f; onMeterTouched?.invoke(); invalidate()
    }

    // ---- touch ------------------------------------------------------------------------------------------------------
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (inset.contains(e.x, e.y) && !tLats.isNullOrEmptyArr()) { insetOn = !insetOn; invalidate(); return true }
            val m = meter ?: return false
            when {
                minus.contains(e.x, e.y) -> { m.nudge(-1); onMeterTouched?.invoke(); invalidate(); return true }
                plus.contains(e.x, e.y) -> { m.nudge(+1); onMeterTouched?.invoke(); invalidate(); return true }
            }
            return false
        }
        override fun onLongPress(e: MotionEvent) {
            val m = meter ?: return
            if (meterBox.contains(e.x, e.y)) {
                // "I am at this row": the row at the top of the list (or the one swiped to)
                val target = (shown + kotlin.math.round(manual).toInt()).coerceIn(0, (chart?.rows?.size ?: 1) - 1)
                m.setToRow(target); manual = 0f; onMeterTouched?.invoke(); invalidate()
            }
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val ch = chart ?: return false
            val rowH = if (landscape) chh / 2f else maxOf(dp(196f), (chh - meterH) / 3.1f)
            manual = (manual + dy / rowH).coerceIn(-shown.toFloat(), (ch.rows.size - 1 - shown).toFloat().coerceAtLeast(0f))
            lastTouch = System.currentTimeMillis(); invalidate(); return true
        }
    })
    override fun onTouchEvent(e0: MotionEvent): Boolean {
        val e = MotionEvent.obtain(e0); e.offsetLocation(-paddingLeft.toFloat(), -paddingTop.toFloat())
        try { return handle(e) } finally { e.recycle() }
    }
    private fun handle(e: MotionEvent): Boolean { lastTouch = System.currentTimeMillis(); return gestures.onTouchEvent(e) || super.onTouchEvent(e) }
}

private fun DoubleArray?.isNullOrEmptyArr() = this == null || this.isEmpty()
