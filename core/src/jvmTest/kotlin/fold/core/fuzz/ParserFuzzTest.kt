package fold.core.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fold.core.Graph
import fold.core.Gpx
import fold.core.MiniJson
import fold.core.PackManifest
import fold.core.Profile
import fold.core.QrShare
import fold.core.MultiRouter
import fold.core.Router
import fold.core.SearchFile
import fold.core.SpeechText
import java.util.zip.Inflater

/**
 * Coverage-guided fuzzing of everything that reads input from other people: GPX files, roll-chart JSON, share codes,
 * pack manifests, search indexes, routing graphs, spoken text. A parser may refuse input with an exception (that is
 * its job: the apps catch it); what counts as a bug is anything else: a stack overflow, running out of memory, a hang
 * (Jazzer's timeout), or an error that is not a refusal.
 *
 * In an ordinary test run each target replays its saved inputs (src/jvmTest/resources/fold/core/fuzz/…Inputs/), so a
 * crash once found stays fixed. To fuzz: JAZZER_FUZZ=1 ./gradlew :core:jvmTest --tests '*ParserFuzzTest.gpx'
 * (.github/workflows/fuzz.yml does each for a few minutes a week).
 */
class ParserFuzzTest {
    /** a refusal: what the parsers throw on input they will not read */
    private inline fun refusing(f: () -> Unit) {
        try { f() } catch (e: Exception) { /* IllegalArgumentException, IllegalStateException, MiniJson.Bad, IndexOutOfBounds…: refused */ }
    }
    private fun inflate(b: ByteArray): ByteArray? {
        val i = Inflater(true); i.setInput(b); val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        try { while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n); if (out.size() > QrShare.MAX_UNPACKED) return null } } finally { i.end() }
        return out.toByteArray()
    }

    @FuzzTest(maxDuration = "2m") fun gpx(d: FuzzedDataProvider) { val s = d.consumeRemainingAsString(); refusing { Gpx.parse(s) } }

    @FuzzTest(maxDuration = "2m") fun chartJson(d: FuzzedDataProvider) { val s = d.consumeRemainingAsString(); refusing { MiniJson.parse(s) } }

    @FuzzTest(maxDuration = "2m") fun shareFrames(d: FuzzedDataProvider) {
        val dec = QrShare.Decoder()
        repeat(d.consumeInt(1, 40)) { refusing { dec.add(d.consumeString(600)) } }
        refusing { QrShare.track(d.consumeRemainingAsBytes()) { inflate(it) } }
    }

    @FuzzTest(maxDuration = "2m") fun packManifest(d: FuzzedDataProvider) { val s = d.consumeRemainingAsString(); refusing { PackManifest.parse(s)?.required } }

    @FuzzTest(maxDuration = "2m") fun searchIndex(d: FuzzedDataProvider) {
        val q = d.consumeString(80); val body = d.consumeRemainingAsBytes()
        refusing { SearchFile.fromBytes(body).query(q) }
    }

    @FuzzTest(maxDuration = "2m") fun graph(d: FuzzedDataProvider) {
        val a = DoubleArray(4) { d.consumeRegularDouble(-1.0, 1.0) }
        val body = d.consumeRemainingAsBytes()
        refusing {
            val g = Graph.fromBytes(body); val r = MultiRouter(listOf(Router(g)))
            val s1 = r.snap(g.minLat + (g.maxLat - g.minLat) * (a[0] + 1) / 2, g.minLon + (g.maxLon - g.minLon) * (a[1] + 1) / 2, Profile.values()[0])
            val s2 = r.snap(g.minLat + (g.maxLat - g.minLat) * (a[2] + 1) / 2, g.minLon + (g.maxLon - g.minLon) * (a[3] + 1) / 2, Profile.values()[0])
            if (s1 != null && s2 != null) r.route(s1, s2, Profile.values()[0], maxExplored = 200_000)
        }
    }

    @FuzzTest(maxDuration = "2m") fun spokenText(d: FuzzedDataProvider) { val s = d.consumeRemainingAsString(); refusing { SpeechText.forVoice(s) } }
}
