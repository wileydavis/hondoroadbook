package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The planner's roll-chart data rides in each cue's <extensions><rally>…</rally></extensions>, after <type>. The app
 * must read such a file exactly as it reads one without: same cues, same turns, same words.
 */
class GpxRallyTest {
    private val withRally = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Roadbook Planner" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>Rally test</name><extensions><roadbook><planner>1</planner><chart>{&quot;layout&quot;:&quot;B&quot;,&quot;preset&quot;:&quot;B-a5&quot;}</chart></roadbook></extensions></metadata>
  <wpt lat="45.6772" lon="-111.0480"><name>onto the ranch road</name><desc>onto the ranch road</desc><type>cue:LEFT</type><extensions><rally>{&quot;at&quot;:2124.6,&quot;exit&quot;:-88,&quot;branches&quot;:[{&quot;deg&quot;:0,&quot;kind&quot;:&quot;track&quot;}],&quot;caution&quot;:1,&quot;info&quot;:&quot;L ON RANCH RD&quot;,&quot;section&quot;:{&quot;name&quot;:&quot;SS1&quot;,&quot;kind&quot;:&quot;stage&quot;},&quot;speed&quot;:30}</rally></extensions></wpt>
  <wpt lat="45.6790" lon="-111.0480"><name>Washboard</name><desc>Washboard</desc><type>danger:2</type><extensions><rally>{&quot;at&quot;:2300}</rally></extensions></wpt>
  <wpt lat="45.6800" lon="-111.0480"><name>Gate</name><desc>Gate</desc><type>note</type></wpt>
  <trk><name>Rally test</name><trkseg>
    <trkpt lat="45.6700" lon="-111.0480"></trkpt>
    <trkpt lat="45.6772" lon="-111.0480"></trkpt>
    <trkpt lat="45.6772" lon="-111.0500"></trkpt>
    <trkpt lat="45.6800" lon="-111.0500"></trkpt>
  </trkseg></trk>
</gpx>"""

    @Test fun rallyExtensionsAreIgnoredSafely() {
        val plain = withRally.replace(Regex("<extensions><rally>.*?</rally></extensions>"), "")
        val a = Gpx.parse(withRally)!!; val b = Gpx.parse(plain)!!
        assertEquals("Rally test", a.name)
        assertEquals(b.cues.size, a.cues.size)
        assertEquals(3, a.cues.size)
        for (i in a.cues.indices) {
            assertEquals(b.cues[i].turn, a.cues[i].turn); assertEquals(b.cues[i].note, a.cues[i].note); assertEquals(b.cues[i].level, a.cues[i].level)
        }
        assertEquals(Turn.LEFT, a.cues[0].turn); assertEquals("onto the ranch road", a.cues[0].note)
        assertEquals(Turn.DANGER, a.cues[1].turn); assertEquals(2, a.cues[1].level)
        assertEquals(4, a.lats.size)
    }
}
