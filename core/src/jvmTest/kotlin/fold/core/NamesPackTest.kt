package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** tools/names_pack.py writes the names packs' keys with its own copy of TextNorm.normalize: they must agree, or the
 *  binary search misses lines. names-parity.tsv and names-sample.tsv are that script's output. */
class NamesPackTest {
    private fun res(n: String) = this::class.java.classLoader.getResource(n)!!.readText()
    @Test fun keysMatchThePython() {
        for (ln in res("names-parity.tsv").lines().filter { it.isNotBlank() }) {
            val (raw, key) = ln.split('\t'); assertEquals(key, TextNorm.normalize(raw), raw)
        }
    }
    @Test fun findsNamedPlaces() {
        val s = SearchFile.fromBytes(res("names-sample.tsv").encodeToByteArray())
        val hot = s.query("saline valley hot springs")
        assertTrue(hot.isNotEmpty() && hot[0].name == "Saline Valley Hot Springs", hot.toString())
        assertEquals("Hunter Mountain", s.query("hunter mountain")[0].name)
        assertTrue(s.query("hunter").any { it.name == "Hunter Mountain Saddle" })
        assertEquals('N', s.query("lower warm")[0].kind)
    }
    @Test fun aNamesPackNeedsOnlyItsIndex() {
        val m = PackManifest.parse("name=California\nformat=3\nbbox=1,2,3,4\nkind=names\n")!!
        assertTrue(m.isNames && !m.isRoads && !m.isTerrain)
        assertEquals(listOf(PackManifest.FILE, PackManifest.SEARCH), m.required)
        assertTrue(PackManifest.parse("name=x\nformat=3\nbbox=1,2,3,4\n")!!.isRoads)
    }
}
