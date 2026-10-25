package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
