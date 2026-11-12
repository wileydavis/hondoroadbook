package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The planner's roll-chart data rides in each cue's <extensions><rally>…</rally></extensions>, after <type>, and the
 * chart settings in <metadata><extensions><roadbook><chart>. The app reads such a file as it reads one without, and
 * keeps the chart data through an edit and a save, byte for byte.
 */
class GpxRallyTest {
    private val r1 = """{"at":2124.6,"exit":-88,"branches":[{"deg":0,"kind":"track"}],"caution":1,"info":"L ON RANCH RD","section":{"name":"SS1","kind":"stage"},"speed":30}"""
    private val r3 = """{"at":2900,"info":"GATE, LEAVE AS FOUND","symbols":[{"id":"gate","slot":"ra"}]}"""
    private val chart = """{"layout":"B","preset":"B-a5","title":"Bob's loop"}"""
    private fun q(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    // written as the planner writes it: quotes and apostrophes escaped too
    private val withRally = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Roadbook Planner" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>Rally test</name><extensions><roadbook><planner>1</planner><chart>${q(chart)}</chart></roadbook></extensions></metadata>
  <wpt lat="45.6772" lon="-111.0480"><name>onto Bob&#39;s ranch road</name><desc>onto Bob&#39;s ranch road</desc><type>cue:LEFT</type><extensions><rally>${q(r1)}</rally></extensions></wpt>
  <wpt lat="45.6790" lon="-111.0480"><name>Washboard</name><desc>Washboard</desc><type>danger:2</type></wpt>
  <wpt lat="45.6800" lon="-111.0490"><name>Note</name><type>note</type><extensions><rally>${q(r3)}</rally></extensions></wpt>
  <trk><name>Rally test</name><trkseg>
    <trkpt lat="45.6700" lon="-111.0480"></trkpt>
    <trkpt lat="45.6772" lon="-111.0480"></trkpt>
    <trkpt lat="45.6772" lon="-111.0500"></trkpt>
    <trkpt lat="45.6800" lon="-111.0500"></trkpt>
  </trkseg></trk>
</gpx>"""

    @Test fun rallyAndChartAreReadAndApostrophesDecoded() {
        val t = Gpx.parse(withRally)!!
        assertEquals(chart, t.chart)
        assertEquals(3, t.cues.size)
        assertEquals("onto Bob's ranch road", t.cues[0].note)
        assertEquals(r1, t.cues[0].rally)
        assertNull(t.cues[1].rally)
        // a chart-only note: no words of its own, still a cue, its chart fields kept
        assertEquals(Turn.NOTE, t.cues[2].turn); assertEquals("", t.cues[2].note); assertEquals(r3, t.cues[2].rally)
    }

    @Test fun anEditAndASaveKeepEveryChartField() {
        val t = Gpx.parse(withRally)!!
        // the rider rewords the first cue (the editor makes a new Cue and carries the chart fields over) and adds one
        val edited = Cue(t.cues[0].lat, t.cues[0].lon, Turn.LEFT, "left at the red gate").also { it.rally = t.cues[0].rally }
        val added = Cue(45.6795, -111.0495, Turn.NOTE, "water here")
        val cues = listOf(edited, t.cues[1], t.cues[2], added)
        val again = Gpx.parse(Gpx.writeTrackChart(t.name, t.lats, t.lons, null, cues, t.chart))!!
        assertEquals(chart, again.chart)
        assertEquals(4, again.cues.size)
        assertEquals("left at the red gate", again.cues[0].note); assertEquals(r1, again.cues[0].rally)
        assertNull(again.cues[1].rally)
        assertEquals("", again.cues[2].note); assertEquals(r3, again.cues[2].rally)
        assertNull(again.cues[3].rally)
        // and once more: a second save changes nothing
        val third = Gpx.parse(Gpx.writeTrackChart(again.name, again.lats, again.lons, null, again.cues, again.chart))!!
        assertEquals(again.cues.map { it.rally }, third.cues.map { it.rally }); assertEquals(again.chart, third.chart)
    }

    @Test fun followingATrackCarriesTheChartFieldsToItsTurns() {
        val t = Gpx.parse(withRally)!!
        val r = Route.fromTrack(t.lats, t.lons, t.name, t.cues)
        assertEquals(r1, r.maneuvers.first { it.turn == Turn.LEFT }.rally)
        assertEquals(r3, r.maneuvers.first { it.turn == Turn.NOTE }.rally)
    }

    @Test fun badChartDataIsDroppedNotTheCue() {
        val bad = withRally.replace(q(r1), "not json at all")
        val t = Gpx.parse(bad)!!
        assertEquals(3, t.cues.size); assertNull(t.cues[0].rally); assertEquals(Turn.LEFT, t.cues[0].turn)
        assertNotNull(t.cues[2].rally)
    }

    @Test fun aFileWithoutChartDataReadsAsBefore() {
        val plain = withRally.replace(Regex("<extensions><rally>.*?</rally></extensions>"), "").replace(Regex("<chart>.*?</chart>"), "")
        val a = Gpx.parse(plain)!!
        assertNull(a.chart)
        assertEquals(3, a.cues.size)   // as before: a note waypoint named "Note" is a note saying "Note"
        assertEquals("Note", a.cues[2].note)
        assertEquals(Gpx.writeTrack(a.name, a.lats, a.lons, null, a.cues), Gpx.writeTrackChart(a.name, a.lats, a.lons, null, a.cues, null))
    }
}
