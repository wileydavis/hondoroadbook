package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CueTest {
    @Test fun gpxCuesRoundTrip() {
        val lats = DoubleArray(50) { 46.0 + it * 0.001 }; val lons = DoubleArray(50) { -112.0 }
        val cues = listOf(Cue(46.0201, -112.0, Turn.LEFT, "onto the ranch road"), Cue(46.0302, -112.0001, Turn.DANGER, "washout", 3), Cue(46.0403, -112.0, Turn.NOTE, "water crossing, usually a foot deep"))
        val text = Gpx.writeTrack("Test", lats, lons, null, cues)
        val tr = Gpx.parse(text)!!
        assertEquals(3, tr.cues.size)
        assertEquals(Turn.DANGER, tr.cues[1].turn); assertEquals(3, tr.cues[1].level); assertEquals("washout", tr.cues[1].note)
        assertEquals("water crossing, usually a foot deep", tr.cues[2].note)
        val r = Route.fromTrack(tr.lats, tr.lons, tr.name, tr.cues)
        val kinds = r.maneuvers.map { it.turn }
        assertTrue(kinds.containsAll(listOf(Turn.LEFT, Turn.DANGER, Turn.NOTE, Turn.ARRIVE)), kinds.toString())
        val note = r.maneuvers.first { it.turn == Turn.NOTE }
        assertEquals("water crossing, usually a foot deep", note.phrase())
        assertTrue(r.maneuvers.first { it.turn == Turn.DANGER }.phrase().startsWith("Danger"), r.maneuvers.first { it.turn == Turn.DANGER }.phrase())
        // cues are ordered along the line
        val ats = r.maneuvers.map { it.at }; assertEquals(ats.sorted(), ats)
    }
}

class CuePerfTest {
    /** REL-01: snapping thousands of cues onto a long track must not be quadratic. */
    @kotlin.test.Test fun manyCuesOnLongTrackIsFast() {
        val n = 100_000
        val lats = DoubleArray(n) { 46.0 + it * 0.00002 }; val lons = DoubleArray(n) { -112.0 + Math.sin(it / 300.0) * 0.001 }
        val cues = (0 until 5000).map { k -> val i = (k.toLong() * (n - 1) / 5000).toInt(); Cue(lats[i], lons[i], Turn.NOTE, "note $k") }
        val t0 = System.nanoTime()
        val r = Route.fromTrack(lats, lons, "long", cues)
        val ms = (System.nanoTime() - t0) / 1_000_000
        kotlin.test.assertTrue(r.maneuvers.size >= 5000, "all cues snapped: ${r.maneuvers.size}")
        kotlin.test.assertTrue(ms < 4000, "100k points + 5k cues took $ms ms")
    }
}

class LongSegmentCueTest {
    /** A sparse file: one 33 km straight segment with a washout halfway. The hazard must survive. */
    @Test fun hazardOnALongSparseSegmentIsKept() {
        val lats = doubleArrayOf(45.0, 45.3); val lons = doubleArrayOf(-111.0, -111.0)      // 33 km due north
        val cues = listOf(Cue(45.15, -111.0, Turn.DANGER, "washout", 3))
        val r = Route.fromTrack(lats, lons, "desert", cues)
        val danger = r.maneuvers.firstOrNull { it.turn == Turn.DANGER }
        assertNotNull(danger, "the washout must be in the roadbook")
        assertEquals(16_680.0, danger!!.at, 300.0)
        // and a cue well off the line is still dropped
        val r2 = Route.fromTrack(lats, lons, "desert", listOf(Cue(45.15, -111.1, Turn.DANGER, "elsewhere", 3)))
        assertNull(r2.maneuvers.firstOrNull { it.turn == Turn.DANGER })
    }
}
