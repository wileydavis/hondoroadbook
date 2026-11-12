package fold.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The moving share code: our QR symbols read by an independent decoder (ZXing), and a track across the frames. */
class QrShareTest {
    private fun read(q: QrCode, scale: Int = 4): String {
        val n = (q.size + 8) * scale
        val px = ByteArray(n * n) { 0xFF.toByte() }
        for (y in 0 until q.size) for (x in 0 until q.size) if (q.dark(x, y)) for (dy in 0 until scale) for (dx in 0 until scale) px[((y + 4) * scale + dy) * n + (x + 4) * scale + dx] = 0
        val src = object : LuminanceSource(n, n) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = (row ?: ByteArray(n)).also { px.copyInto(it, 0, y * n, y * n + n) }
            override fun getMatrix(): ByteArray = px
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test fun everyVersionReadsBack() {
        val rnd = Random(7)
        for (ecc in QrCode.Ecc.values()) for (v in listOf(1, 2, 5, 6, 7, 9, 10, 14, 15, 20, 21, 26, 27, 32, 33, 40)) {
            val n = QrCode.capacityChars(v, ecc)
            val text = String(CharArray(n) { QrCode.ALPHANUMERIC[rnd.nextInt(45)] })
            val q = QrCode.encodeAlphanumeric(text, ecc)!!
            assertEquals(v, q.version, "$ecc capacity at version $v")
            assertEquals(text, read(q), "$ecc version $v")
            assertEquals(v + 1, QrCode.encodeAlphanumeric(text + "A", ecc)?.version ?: 41, "$ecc: one more character needs the next version")
        }
    }

    @Test fun base45RoundTrips() {
        assertEquals("BB8", Base45.encode("AB".encodeToByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".encodeToByteArray()))
        val r = Random(3)
        repeat(50) { val b = r.nextBytes(r.nextInt(0, 300)); assertContentEquals(b, Base45.decode(Base45.encode(b))) }
    }

    private fun sample(n: Int): Gpx.Track {
        // a road's wander: the planner's points are irregular, so they compress like real ones
        val r = Random(n)
        val lats = DoubleArray(n); val lons = DoubleArray(n); var a = 45.0; var o = -111.0
        for (i in 0 until n) { a += 0.0001 + r.nextDouble(-0.0003, 0.0003); o += 0.00015 + r.nextDouble(-0.0003, 0.0003); lats[i] = (a * 1e6).toLong() / 1e6; lons[i] = (o * 1e6).toLong() / 1e6 }
        val c1 = Cue(lats[40], lons[40], Turn.LEFT, "L onto Bob's \"gate\" rd & <go>", 0).also { it.rally = "{\"at\":1234.5,\"exit\":-90,\"info\":\"L ON RANCH RD\",\"symbols\":[{\"id\":\"gate\",\"slot\":\"la\"}]}" }
        val c2 = Cue(lats[90], lons[90], Turn.DANGER, "washout", 3)
        val c3 = Cue(lats[120], lons[120], Turn.NOTE, "").also { it.rally = "{\"symbols\":[{\"id\":\"fuel\",\"slot\":\"ra\"}]}" }
        return Gpx.Track("Copper Ridge · día 2", lats, lons, doubleArrayOf(lats[0], lats[n / 2], lats[n - 1]), doubleArrayOf(lons[0], lons[n / 2], lons[n - 1]), "MOTO",
            listOf(c1, c2, c3), listOf("Start", "Lolo Pass", "End")).also { it.chart = "{\"layout\":\"B\",\"scale\":0.97}" }
    }
    private fun same(a: Gpx.Track, b: Gpx.Track) {
        assertEquals(a.name, b.name); assertEquals(a.mode, b.mode); assertEquals(a.chart, b.chart); assertEquals(a.rteNames, b.rteNames)
        assertEquals(a.lats.size, b.lats.size)
        for (i in a.lats.indices) assertTrue(kotlin.math.abs(a.lats[i] - b.lats[i]) < 6e-7 && kotlin.math.abs(a.lons[i] - b.lons[i]) < 6e-7)
        assertEquals(a.rteLats.size, b.rteLats.size)
        assertEquals(a.cues.map { "${it.turn}/${it.level}/${it.note}/${it.rally}" }, b.cues.map { "${it.turn}/${it.level}/${it.note}/${it.rally}" })
    }

    private val deflate: (ByteArray) -> ByteArray? = { b -> val d = java.util.zip.Deflater(9, true); d.setInput(b); d.finish(); val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); while (!d.finished()) out.write(buf, 0, d.deflate(buf)); out.toByteArray() }
    private val inflate: (ByteArray) -> ByteArray? = { b -> val i = java.util.zip.Inflater(true); i.setInput(b); val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }; out.toByteArray() }

    @Test fun aTrackSurvivesPackingAndTheGpxWriter() {
        val t = sample(500)
        same(t, QrShare.unpack(QrShare.pack(t)))
        same(t, Gpx.parse(Gpx.writeShared(QrShare.unpack(QrShare.pack(t))))!!)
    }

    @Test fun theFramesRebuildTheMessageWhicheverAreMissed() {
        val t = sample(3000)
        val msg = QrShare.message(t, deflate)
        val enc = QrShare.Encoder(msg)
        assertTrue(enc.k in 5..60, "${msg.size} bytes in ${enc.k} frames")
        // the camera misses a third of the frames, at random, and sees some twice
        val r = Random(11)
        val dec = QrShare.Decoder()
        var i = 0; var shown = 0
        while (dec.message == null && i < enc.k * 4) { if (r.nextDouble() > 0.33) { dec.add(enc.frame(i)); if (r.nextBoolean()) dec.add(enc.frame(i)) }; shown++; i++ }
        assertNotNull(dec.message, "whole after $shown frames")
        assertTrue(shown <= (enc.k + 5) * 2, "took $shown frames for ${enc.k} chunks")
        // on average a frame or two more than the chunks, whichever ones arrive
        var extra = 0
        repeat(40) { trial -> val rr = Random(100 + trial); val d = QrShare.Decoder(); var got = 0; var f = 0
            while (d.message == null) { if (rr.nextDouble() > 0.5) { d.add(enc.frame(f)); got++ }; f++ }; extra += got - enc.k }
        assertTrue(extra / 40.0 < 2.5, "average extra frames ${extra / 40.0}")
        same(t, QrShare.track(dec.message!!, inflate))
        // a frame read from a real code
        assertEquals(enc.frame(7), read(enc.code(7), 3))
        assertTrue(enc.code(0).version <= 20)
    }

    @Test fun strangersAndANewMessageAreHandled() {
        val a = QrShare.Encoder(QrShare.message(sample(200), deflate)); val b = QrShare.Encoder(QrShare.message(sample(300), deflate))
        val dec = QrShare.Decoder()
        assertTrue(!dec.add("HTTPS://EXAMPLE.COM")); assertTrue(!dec.add("RB1:!!"))
        assertTrue(a.k > 1); dec.add(a.frame(0)); assertEquals(a.id, dec.id)
        var i = 0; while (dec.message == null) dec.add(b.frame(i++))
        assertEquals(b.id, dec.id)
    }

    @Test fun aLongRecordingIsThinnedToAFewSecondsOfFrames() {
        // 20,000 fixes a second apart along a wandering road
        val n = 20000
        val lats = DoubleArray(n) { 45.0 + it * 0.00012 + kotlin.math.sin(it / 200.0) * 0.004 }
        val lons = DoubleArray(n) { -111.0 + kotlin.math.cos(it / 170.0) * 0.006 + it * 0.00005 }
        val t = Gpx.Track("Long day", lats, lons, DoubleArray(0), DoubleArray(0), null)
        val thin = QrShare.thin(t)
        val len = { a: DoubleArray, o: DoubleArray -> var s = 0.0; for (i in 1 until a.size) s += Geo.fastDistance(a[i - 1], o[i - 1], a[i], o[i]); s }
        assertTrue(kotlin.math.abs(len(thin.lats, thin.lons) / len(lats, lons) - 1) < 0.001, "length kept")
        val k = QrShare.Encoder(QrShare.message(t, deflate)).k
        assertTrue(k < 120, "$k frames")
    }
}
