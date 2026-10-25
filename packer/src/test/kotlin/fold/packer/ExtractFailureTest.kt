package fold.packer

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MAP-01: a failed tile fetch must fail the extraction instead of writing an archive with holes. */
class ExtractFailureTest {
    private fun synthetic(dir: File): File {
        // a small gzip-compressed "vector" archive: zooms 0..6 over the whole world
        val tiles = HashMap<Long, ByteArray>()
        for (z in 0..6) { val n = 1L shl z; for (x in 0 until n) for (y in 0 until n) tiles[PmTiles.zxyToTileId(z, x, y)] = PmTiles.gzip("tile $z/$x/$y".toByteArray()) }
        val f = File(dir, "src.pmtiles")
        PmTiles.writeArchive(f, tiles, 0, 6, -180.0, -85.0, 180.0, 85.0, tileType = 1, tileCompression = 2, metadataJson = "{}")
        return f
    }

    @Test fun cleanExtractVerifies() {
        val dir = createTempDirectory()
        val src = synthetic(dir); val out = File(dir, "out.pmtiles")
        PmTiles.extract(PmTiles.FileSource(src), -120.0, 40.0, -100.0, 50.0, out, {}, zoomCap = 6)
        assertTrue(out.length() > 0)
        PmTiles.verify(out, expectedTiles = countWanted(), tileCompression = 2, log = {})
    }

    @Test fun failingRangeFailsTheBuild() {
        val dir = createTempDirectory()
        val src = synthetic(dir); val out = File(dir, "out.pmtiles")
        val inner = PmTiles.FileSource(src)
        var reads = 0
        val flaky = object : PmTiles.Source { override fun read(offset: Long, length: Long): ByteArray { reads++; if (reads == 3) throw IOException("simulated network failure"); return inner.read(offset, length) } }
        val e = assertFailsWith<IOException> { PmTiles.extract(flaky, -120.0, 40.0, -100.0, 50.0, out, {}, zoomCap = 6) }
        assertTrue(e.message!!.contains("tile fetch failed"), e.message)
        assertFalse(out.exists() && out.length() > 0, "no archive is left behind")
    }

    @Test fun shortReadFailsTheBuild() {
        val dir = createTempDirectory()
        val src = synthetic(dir); val out = File(dir, "out.pmtiles")
        val inner = PmTiles.FileSource(src)
        val short = object : PmTiles.Source { override fun read(offset: Long, length: Long): ByteArray { val b = inner.read(offset, length); return if (length > 64) b.copyOf((length / 2).toInt()) else b } }
        assertFailsWith<Exception> { PmTiles.extract(short, -120.0, 40.0, -100.0, 50.0, out, {}, zoomCap = 6) }
    }

    private fun countWanted(): Int { var n = 0; for (z in 0..6) { val k = 1L shl z; val x0 = Math.floor((-120.0 + 180) / 360 * k).toLong(); val x1 = Math.floor((-100.0 + 180) / 360 * k).toLong()
        fun y(lat: Double): Long { val r = Math.toRadians(lat); return Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * k).toLong() }
        n += ((x1 - x0 + 1) * (y(40.0) - y(50.0) + 1)).toInt() }; return n }
    private fun createTempDirectory(): File = java.nio.file.Files.createTempDirectory("fold-extract").toFile().apply { deleteOnExit() }
}
