package fold.core

import kotlin.test.Test

/**
 * Writes build/qr-fixture.json: a planner-written GPX, the track the app reads from it, its packed bytes, and frames
 * and QR symbols of an uncompressed message. web/tools copies it to web/test/qr-fixture.json, where the planner's
 * port (web/site/qrshare.js) must reproduce every byte and every module.
 */
class QrFixtureWriter {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test fun write() {
        val r = kotlin.random.Random(5)
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Roadbook Planner\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>Copper Ridge &amp; Bob&#39;s · día 2</name><extensions><roadbook><mode>ride</mode><planner>1</planner><chart>{&quot;layout&quot;:&quot;B&quot;,&quot;scale&quot;:0.97}</chart></roadbook></extensions></metadata>\n")
        var a = 45.123456; var o = -111.654321
        val pts = ArrayList<Pair<Double, Double>>()
        repeat(900) { a += 0.0001 + r.nextDouble(-0.0003, 0.0003); o += 0.00015 + r.nextDouble(-0.0003, 0.0003); pts.add(Math.round(a * 1e6) / 1e6 to Math.round(o * 1e6) / 1e6) }
        fun wpt(i: Int, inner: String) = sb.append("  <wpt lat=\"%.6f\" lon=\"%.6f\">".format(java.util.Locale.US, pts[i].first, pts[i].second)).append(inner).append("</wpt>\n")
        wpt(40, "<name>L ON RANCH RD</name><desc>L ON RANCH RD</desc><type>cue:LEFT</type><extensions><rally>{&quot;at&quot;:1234.5,&quot;exit&quot;:-90,&quot;info&quot;:&quot;L ON RANCH RD&quot;}</rally></extensions>")
        wpt(90, "<name>Washout</name><desc>Washout</desc><type>danger:3</type>")
        wpt(120, "<name>Note</name><type>note</type><extensions><rally>{&quot;symbols&quot;:[{&quot;id&quot;:&quot;fuel&quot;,&quot;slot&quot;:&quot;ra&quot;}],&quot;at&quot;:2600}</rally></extensions>")
        wpt(200, "<name>Right</name><type>cue:RIGHT</type>")
        wpt(300, "<name>a camp</name>")
        wpt(400, "<name>Keep left at the fork</name><desc>Keep left at the fork</desc><link href=\"https://x.org\"><text>source</text></link><type>cue:KEEP_LEFT</type>")
        sb.append("  <trk><name>x</name><trkseg>\n")
        for ((la, lo) in pts) sb.append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"></trkpt>\n".format(java.util.Locale.US, la, lo))
        sb.append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"></trkpt>\n".format(java.util.Locale.US, pts.last().first, pts.last().second))
        sb.append("  </trkseg></trk>\n</gpx>\n")
        val gpx = sb.toString()
        val t = Gpx.parse(gpx)!!
        val packed = QrShare.pack(t)
        val msg = byteArrayOf(0) + packed
        val enc = QrShare.Encoder(msg)
        val frames = (0 until enc.k + 4).map { enc.frame(it) }
        fun rows(q: QrCode) = (0 until q.size).joinToString(",") { y -> js((0 until q.size).joinToString("") { x -> if (q.dark(x, y)) "1" else "0" }) }
        val json = "{\"gpx\":${js(gpx)},\"cues\":${t.cues.size},\"points\":${t.lats.size},\"pack\":${js(hex(packed))},\"k\":${enc.k},\"id\":${enc.id}," +
            "\"frames\":[${frames.joinToString(",") { js(it) }}],\"qr0\":[${rows(enc.code(0))}],\"qrMix\":[${rows(enc.code(enc.k + 2))}]," +
            "\"small\":[${listOf("HELLO", "RB1:AB", "0123456789").joinToString(",") { s -> val q = QrCode.encodeAlphanumeric(s, QrCode.Ecc.M)!!; "{\"text\":${js(s)},\"rows\":[${rows(q)}]}" }}]}"
        java.io.File("build/qr-fixture.json").writeText(json)
    }
}
