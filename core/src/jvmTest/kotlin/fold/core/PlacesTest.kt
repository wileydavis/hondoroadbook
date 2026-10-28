package fold.core
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacesTest { @Test fun parseAndIcons() {
    val tsv = "457000000\t-1110000000\t3\tfuel\tfuel\tTown Pump\t24/7\n" +
        "457100000\t-1110100000\t2\tcamping\tRV park\tRiverside RV\tfree\n" +
        "457200000\t-1110200000\t1\twater\ttap\t\t\n" +
        "457300000\t-1110300000\t3\ttown\ttown\tEnnis\t838\n" +
        "bad line\n" +
        "457400000\t-1110400000\t1\tfood\tcafe\tThe Grind\tMo-Fr 7-14\n"
    val p = Places.parse(tsv)
    assertEquals(4, p.size)
    assertEquals(listOf("fuel", "caravan_site", "drinking_water", "cafe"), p.map { it.icon })
    assertEquals(listOf(11.0, 12.5, 14.0, 14.5), p.map { it.minZoom })
    assertEquals("Town Pump · fuel · 24/7", p[0].label); assertEquals("Tap", p[2].label); assertEquals("The Grind · cafe · Mo-Fr 7-14", p[3].label)
    assertEquals(45.7, p[0].lat, 1e-9); assertEquals(-111.0, p[0].lon, 1e-9)
    assertEquals(5, Places.parse(tsv, skipTowns = false).size)
} }
