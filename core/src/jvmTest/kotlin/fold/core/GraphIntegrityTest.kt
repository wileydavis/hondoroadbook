package fold.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SEC-01: a damaged graph must be rejected at open, promptly, never looped on. */
class GraphIntegrityTest {
    private fun good(): ByteArray {
        val w = GraphWriter()
        val a = w.addNode(458000000, -1113000000, 1); val b = w.addNode(458100000, -1113000000, 2)
        w.addEdge(a, b, 1_111_00, 10 or GraphFormat.CAR, w.nameId("Road"), IntArray(0), IntArray(0), 0)
        val o = ByteArraySink(); w.write(o); return o.toByteArray()
    }

    private fun rejectsQuickly(bytes: ByteArray, label: String) {
        val t0 = System.nanoTime()
        assertFailsWith<Exception>(label) { Graph.fromBytes(bytes) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "$label: rejected in under two seconds")
    }

    @Test fun validGraphOpens() { Graph.fromBytes(good()) }
    @Test fun emptyFile() = rejectsQuickly(ByteArray(0), "empty")
    @Test fun shorterThanMagic() = rejectsQuickly(ByteArray(5) { 'F'.code.toByte() }, "5 bytes")
    @Test fun headerOnly() = rejectsQuickly(good().copyOf(64), "header only")
    @Test fun truncatedBody() { val g = good(); rejectsQuickly(g.copyOf(g.size - 16), "truncated") }
    @Test fun wrongMagic() { val g = good(); g[0] = 'X'.code.toByte(); rejectsQuickly(g, "magic") }
    @Test fun absurdCounts() { val g = good(); java.nio.ByteBuffer.wrap(g).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE); rejectsQuickly(g, "node count") }
    @Test fun negativeCounts() { val g = good(); java.nio.ByteBuffer.wrap(g).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16, -5); rejectsQuickly(g, "edge count") }
    @Test fun garbageBody() { val g = good(); for (i in 64 until g.size) g[i] = (i * 31).toByte(); rejectsQuickly(g, "garbage body") }
}
