package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files and codes from other people are untrusted: whatever they hold, reading them must stay bounded and fail
 * cleanly. Each test is an attack that once would have hung, crashed or exhausted the phone.
 */
class HostileInputTest {
    private fun timed(ms: Long, f: () -> Unit) { val t = System.nanoTime(); f(); val took = (System.nanoTime() - t) / 1_000_000; assertTrue(took < ms, "took $took ms") }

    @Test fun deeplyNestedChartJsonIsRefusedNotFollowedDownTheStack() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertFailsWith<MiniJson.Bad> { MiniJson.parse(deep) }
        assertNotNull(MiniJson.parse("""{"a":[1,{"b":[2,3]}],"c":"x"}"""))
        val ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH)
        assertNotNull(MiniJson.parse(ok))
    }

    @Test fun manyRoutePointsTimesManyWaypointsDoesNotHang() {
        val sb = StringBuilder("<gpx>")
        for (i in 0 until 60_000) sb.append("<rtept lat=\"${45 + i * 1e-5}\" lon=\"-111\"/>")
        for (i in 0 until 19_000) sb.append("<wpt lat=\"46\" lon=\"${-110 + i * 1e-5}\"><name>w$i</name></wpt>")
        sb.append("</gpx>")
        timed(5_000) { val t = Gpx.parse(sb.toString())!!; assertTrue(t.hasRoute) }
    }

    @Test fun hugeTextIsCut() {
        val big = "x".repeat(2_000_000)
        val t = Gpx.parse("<gpx><name>$big</name><trk><trkseg><trkpt lat=\"45\" lon=\"-111\"/><trkpt lat=\"45.1\" lon=\"-111\"/></trkseg></trk>" +
            "<wpt lat=\"45\" lon=\"-111\"><name>$big</name><type>note</type></wpt></gpx>")!!
        assertEquals(Gpx.MAX_TEXT, t.name.length)
        assertTrue(t.cues.isNotEmpty() && t.cues.all { it.note.length <= Gpx.MAX_TEXT })
    }

    @Test fun outOfRangeWaypointsAreIgnored() {
        val t = Gpx.parse("<gpx><trk><trkseg><trkpt lat=\"45\" lon=\"-111\"/><trkpt lat=\"45.1\" lon=\"-111\"/></trkseg></trk><wpt lat=\"1e308\" lon=\"NaN\"><type>note</type><name>x</name></wpt></gpx>")!!
        assertTrue(t.cues.isEmpty())
    }

    @Test fun aShareFrameClaimingTinyChunksOrAHugeMessageIsIgnored() {
        fun frame(size: Int, chunk: Int, idx: Int = 0): String {
            val h = ByteArray(13 + chunk); h[0] = 1
            fun put(at: Int, v: Long, n: Int) { for (j in 0 until n) h[at + j] = (v shr (8 * (n - 1 - j))).toByte() }
            put(1, 7, 4); put(5, size.toLong(), 3); put(8, chunk.toLong(), 2); put(10, idx.toLong(), 3)
            return "RB1:" + Base45.encode(h)
        }
        val d = QrShare.Decoder()
        timed(2_000) { assertFalse(d.add(frame((1 shl 24) - 1, 1))) }        // 16 million one-byte chunks
        assertFalse(d.add(frame((1 shl 24) - 1, 16)))                          // a million chunks
        assertEquals(0, d.k)
    }

    @Test fun aShareThatUnpacksToTooMuchIsRefused() {
        val m = byteArrayOf(1, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { QrShare.track(m) { ByteArray(QrShare.MAX_UNPACKED + 1) } }
    }

    @Test fun aGraphWithBadOffsetsFailsCleanly() {
        val w = GraphWriter(); val a = w.addNode(450000000, -1110000000); val b = w.addNode(450010000, -1110000000)
        w.addEdge(a, b, 1000, 0, 0, IntArray(0), IntArray(0), 0)
        val sink = ByteArraySink(); w.write(sink); val bytes = sink.toByteArray()
        assertNotNull(Graph.fromBytes(bytes))
        // the header claims more nodes than the file holds
        val bad = bytes.copyOf(); bad[12] = 0x7f; bad[13] = 0x7f
        assertFailsWith<IllegalArgumentException> { Graph.fromBytes(bad) }
        assertFailsWith<IllegalArgumentException> { Graph.fromBytes(bytes.copyOf(40)) }
    }

    @Test fun aPackManifestWithoutTheBasicsIsRefused() {
        assertNull(PackManifest.parse("name=x\nformat=three\nbbox=1,2,3,4"))
        assertNull(PackManifest.parse("format=3\nbbox=1,2,3,4"))
    }
}
