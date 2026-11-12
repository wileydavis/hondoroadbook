package fold.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The apps' chart engine against the planner's: every fixture route (written by web/tools/chart-fixtures.mjs from
 * web/site/chart.js) must give the same rows — mileage to the hundredth, the same words, the same tulips.
 */
class ChartFixturesTest {
    private val fixtures: List<Map<*, *>> by lazy {
        val text = javaClass.classLoader.getResource("chart-fixtures.json")!!.readText()
        @Suppress("UNCHECKED_CAST") (MiniJson.parse(text) as List<Map<*, *>>)
    }
    private fun dbl(v: Any?) = (v as Number).toDouble()
    private fun arr(v: Any?) = (v as List<*>).map { dbl(it) }.toDoubleArray()

    private fun run(f: Map<*, *>): ChartBuild {
        val cues = (f["cues"] as List<*>).mapIndexed { i, x -> val c = x as Map<*, *>
            ChartCue(dbl(c["lat"]), dbl(c["lon"]), Turn.valueOf(c["turn"] as String), dbl(c["level"]).toInt(), c["text"] as String,
                Rally.parse(c["rally"] as String?), (c["angle"] as? Double), dbl(c["exit"]).toInt(), c["auto"] == true, i) }
        return Chart.build(arr(f["lats"]), arr(f["lons"]), cues, ChartSettings.parse(f["settings"] as String))
    }

    @Test fun everyFixtureGivesThePlannersRows() {
        assertTrue(fixtures.size >= 7)
        for (f in fixtures) {
            val name = f["name"]; val want = f["rows"] as List<*>; val got = run(f).rows
            assertEquals(want.size, got.size, "$name: row count")
            for ((i, w0) in want.withIndex()) {
                val w = w0 as Map<*, *>; val g = got[i]; val where = "$name, row ${i + 1}"
                assertEquals(w["kind"], g.kind.name, "$where: kind")
                if (g.kind == ChartRow.Kind.BANNER) { assertEquals(w["name"], g.sectionName, where); assertEquals(dbl(w["lengthH"]).toInt(), g.lengthH, "$where: section length"); continue }
                assertEquals(dbl(w["n"]).toInt(), g.n, "$where: number")
                // the same rounding of the same distances: exact, or a hundredth either way where a cue sits on a rounding edge
                for ((k, v) in listOf("overall" to g.overall, "delta" to g.delta, "decline" to g.decline)) assertTrue(abs(dbl(w[k]).toInt() - v) <= 1, "$where: $k ${w[k]} vs $v")
                assertEquals(w["info"], g.info, "$where: words")
                assertEquals(dbl(w["caution"]).toInt(), g.caution, "$where: caution")
                for ((k, v) in listOf("zeroOdo" to g.zeroOdo, "resetHere" to g.resetHere, "shortPartial" to g.shortPartial, "inZone" to g.inZone, "auto" to g.auto)) assertEquals(w[k], v, "$where: $k")
                if (g.kind == ChartRow.Kind.CUE) {
                    val t = g.tulip!!
                    assertTrue(abs(dbl(w["exit"]).toInt() - t.exit) <= 1, "$where: exit ${w["exit"]} vs ${t.exit}")
                    assertEquals((w["branches"] as List<*>).map { (it as List<*>).let { b -> "${dbl(b[0]).toInt()}/${b[1]}" } }, t.branches.map { "${it.deg}/${it.kind}" }, "$where: branches")
                    assertEquals(w["path"], t.path, "$where: path"); assertEquals(dbl(w["roundabout"]).toInt(), t.roundabout, "$where: roundabout")
                    assertTrue(abs(dbl(w["cap"]).toInt() - g.cap) <= 1 || abs(dbl(w["cap"]).toInt() - g.cap) >= 359, "$where: cap")
                    assertEquals(w["capShow"], g.capShow, "$where: capShow"); assertEquals(dbl(w["speed"]).toInt(), g.speed, "$where: speed")
                    assertEquals((w["symbols"] as List<*>).map { (it as List<*>).joinToString("/") }, g.symbols.map { "${it.id}/${it.slot}" }, "$where: symbols")
                    assertEquals(w["zone"], g.zone, "$where: zone"); assertEquals(w["wp"], g.wpType + (if (g.wpN > 0) g.wpN.toString() else ""), "$where: waypoint")
                }
            }
        }
    }

    @Test fun tulipShapesMatchTheGeometry() {
        val w = Chart.Widths(1.3, 0.95, 0.6, 3.2)
        // a right turn: the stem rises from the bottom centre, the arrow leaves to the right
        val right = Chart.tulip(Tulip(90, listOf(ChartBranch(-90, "road")), "track", 0), 36.0, w)
        val stem = right.filterIsInstance<Chart.Line>().first { it.role == "path" }
        assertTrue(stem.pts[0][1] > stem.pts[1][1] && abs(stem.pts[0][0] - 18.0) < 1e-9)
        val head = right.filterIsInstance<Chart.PathShape>().last()
        assertTrue(head.ops[0][1] > 18 + 10 && abs(head.ops[0][2] - 36 * 0.55) < 0.5, "arrow tip to the right of the junction")
        // a reversal is a hook (a cubic), a roundabout a ring
        assertTrue(Chart.tulip(Tulip(175, emptyList(), "track", 0), 36.0, w).filterIsInstance<Chart.PathShape>().any { p -> p.ops.any { it[0] == 2.0 } })
        assertTrue(Chart.tulip(Tulip(-60, emptyList(), "road", 3), 36.0, w).any { it is Chart.Circle && !it.fill })
    }

    @Test fun everySymbolDraws() {
        assertTrue(ChartGlyphs.all.size >= 57)
        for (g in ChartGlyphs.all) assertTrue(ChartGlyphs.shapes(g.id, 0.0, 0.0, 10.0).isNotEmpty() || ChartGlyphs.words(g.id, 0.0, 0.0, 10.0).isNotEmpty(), g.id)
        assertEquals("CP", ChartGlyphs.words("checkpoint", 0.0, 0.0, 10.0).single().text)
    }

    @Test fun theTripMeterFollowsTheRouteAndTheRidersCorrections() {
        val f = fixtures.first { it["name"] == "sections and a reset" }
        val chart = run(f); val m = TripMeter(chart)
        val reset = chart.rows.first { it.resetHere }; val stage = chart.rows.indexOfFirst { it.kind == ChartRow.Kind.BANNER && it.sectionName == "Stage 1" }
        m.onRoute(1000.0); assertEquals(Chart.hundredths(1000.0, chart.settings), m.readingH)
        m.onRoute(reset.at + 500); assertEquals(Chart.hundredths(500.0, chart.settings), m.readingH, "counts from the reset")
        m.onRoute(chart.rows[stage].at + 100); assertEquals(Chart.hundredths(100.0, chart.settings), m.readingH, "counts from the section start")
        m.nudge(+3); assertEquals(Chart.hundredths(100.0, chart.settings) + 3, m.readingH)
        m.nudge(-5); assertEquals(Chart.hundredths(100.0, chart.settings) - 2, m.readingH)
        // the rider is at a row: the meter reads that row's figure
        val row = chart.rows.indexOfLast { it.kind == ChartRow.Kind.CUE }
        m.setToRow(row); assertEquals(chart.rows[row].overall, m.readingH); assertEquals(row, m.rowIndex)
        // the next row shows a little before it is reached
        m.onRoute(chart.rows[row].at - 300); assertEquals(row - 1, m.rowIndex); assertEquals(row, m.showRow(400.0)); assertEquals(row - 1, m.showRow(100.0))
    }

    @Test fun theFreeOdometerCountsRiddenDistanceAndIgnoresAStandstill() {
        val f = fixtures.first { it["name"] == "scale and units" }
        val chart = run(f); val m = TripMeter(chart); m.switchSource(TripMeter.Source.FREE)
        val lats = arr(f["lats"]); val lons = arr(f["lons"])
        // 2 km of good fixes every 10 m
        for (i in 0..400 step 2) m.onFix(lats[i], lons[i], 5.0, 10.0)
        val two = Chart.cumulative(lats, lons)[400]
        assertTrue(abs(m.chartMeters - two) < 1.0, "counted ${m.chartMeters} of $two")
        // standing still with wandering fixes, and a burst of bad ones: no distance
        val before = m.chartMeters
        for (k in 0 until 60) m.onFix(lats[400] + (k % 3 - 1) * 0.00001, lons[400] + (k % 2) * 0.00001, 6.0, 0.2)
        for (k in 0 until 10) m.onFix(lats[400] + 0.01, lons[400], 80.0, 0.0)
        assertTrue(m.chartMeters - before < 5.0, "crept ${m.chartMeters - before} m")
        // the chart's scale factor applies to the reading
        assertEquals(Chart.hundredths(m.chartMeters, chart.settings), m.readingH)
    }
}
