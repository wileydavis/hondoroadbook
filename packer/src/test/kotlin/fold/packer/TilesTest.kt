package fold.packer

import kotlin.test.Test
import kotlin.test.assertEquals

class TilesTest {
    @Test fun hilbertMatchesReference() {
        val lines = javaClass.getResource("/tileids.txt")!!.readText().lines().filter { it.isNotBlank() }
        for (l in lines) { val p = l.split(' '); assertEquals(p[3].toLong(), PmTiles.zxyToTileId(p[0].toInt(), p[1].toLong(), p[2].toLong()), l) }
    }
    @Test fun directoryRoundTrip() {
        val e = listOf(PmTiles.Entry(5, 0, 100, 1), PmTiles.Entry(6, 100, 50, 3), PmTiles.Entry(20, 0, 100, 1), PmTiles.Entry(21, 500, 7, 0))
        val back = PmTiles.deserialize(PmTiles.serialize(e))
        assertEquals(e.map { listOf(it.tileId, it.offset, it.length.toLong(), it.runLength.toLong()) }, back.map { listOf(it.tileId, it.offset, it.length.toLong(), it.runLength.toLong()) })
    }
}
