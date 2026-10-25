package fold.server

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTest {
    @Test fun numbersNeverComeOutEmpty() {
        assertEquals("0", Json.num(0.0, 0))
        assertEquals("0", Json.num(0.0))
        assertEquals("0", Json.num(-0.0, 1))
        assertEquals("12", Json.num(12.0, 3))
        assertEquals("-110.76235", Json.num(-110.76235))
        assertEquals("1.5", Json.num(1.5, 1))
        assertEquals("null", Json.num(Double.NaN))
    }

    @Test fun stringsEscape() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.str("a\"b\\c\n"))
    }

    @Test fun parsesTheCatalogShape() {
        val doc = """{"version":1,"updated":"2026-10-25","packs":[{"id":"x","bytes":1234567,"bbox":[-1.5,2,3,4.25],"ok":true,"n":null,"s":"a\"b"}]}"""
        val root = Json.parse(doc) as Map<*, *>
        assertEquals(1.0, root["version"])
        val p = (root["packs"] as List<*>)[0] as Map<*, *>
        assertEquals("x", p["id"]); assertEquals(1234567.0, p["bytes"]); assertEquals(listOf(-1.5, 2.0, 3.0, 4.25), p["bbox"]); assertEquals(true, p["ok"]); assertEquals(null, p["n"]); assertEquals("a\"b", p["s"])
    }
}
