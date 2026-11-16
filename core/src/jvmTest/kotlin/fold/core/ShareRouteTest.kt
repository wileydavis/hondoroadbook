package fold.core

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A planned route, written as the iPhone writes it (writeRoute, its turns spliced in as cues), shares by code. */
class ShareRouteTest {
    @Test fun aRouteFileSharesByCode() {
        val n = 3000
        val lats = DoubleArray(n) { 45.0 + it * 0.0005 }; val lons = DoubleArray(n) { -111.0 + Math.sin(it / 50.0) * 0.01 }
        var text = Gpx.writeRoute("Bozeman (Moto)", "moto", doubleArrayOf(lats[0], lats[n - 1]), doubleArrayOf(lons[0], lons[n - 1]), listOf("Start", "End"), lats, lons, 4000)
        val cues = (1 until 40).map { Cue(lats[it * 70], lons[it * 70], if (it % 2 == 0) Turn.LEFT else Turn.RIGHT, "Main St & 5th", 0) }
        text = text.replace("  <rte>", cues.joinToString("") { Gpx.cueXml(it) } + "  <rte>")
        val t = Gpx.parse(text); assertNotNull(t, "the route file parses")
        val msg = QrShare.message(t) { b -> val d = Deflater(9, true); d.setInput(b); d.finish(); val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); while (!d.finished()) o.write(buf, 0, d.deflate(buf)); d.end(); o.toByteArray() }
        val e = QrShare.Encoder(msg, QrShare.chunkFor(20))
        assertTrue(e.k in 1..QrShare.MAX_CHUNKS, "k=${e.k}")
        for (i in 0 until e.k + 5) assertNotNull(e.code(i))
        println("route share: ${msg.size} bytes, ${e.k} frames")
    }
}
