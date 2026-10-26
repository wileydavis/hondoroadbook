package fold.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Bad input is refused before any pack is touched: a catalog that cannot be reached proves no pack was asked for. */
class InputTest {
    private val api = Api(PackSet("http://127.0.0.1:9/never", File(System.getProperty("java.io.tmpdir"), "fold-input-test")))

    private fun status(f: () -> Any) = assertFailsWith<ApiError> { f() }.status

    @Test fun nonFiniteAndOutOfRangeCoordinatesAre400() {
        for (pts in listOf("NaN,NaN;NaN,NaN", "Infinity,1;2,3", "-111,45;1e400,2", "-111,45;-110,91", "-111,45;-181,40", "a,b;1,2", "-111,45"))
            assertEquals(400, status { api.route(mapOf("pts" to pts, "mode" to "FAST")) }, pts)
        assertEquals(400, status { api.snap(mapOf("lon" to "NaN", "lat" to "45")) })
        assertEquals(400, status { api.snap(mapOf("lon" to "-111", "lat" to "95")) })
        for (bb in listOf("NaN,NaN,NaN,NaN", "-111,45,-112,46", "1,2,3", "-200,0,-100,1"))   // a merely wide box is shrunk, not refused
            assertEquals(400, status { api.search(mapOf("q" to "bozeman", "bbox" to bb)) }, bb)
    }

    @Test fun tooManyPointsOrTooFarIs400() {
        val many = (0..60).joinToString(";") { "-111,${45 + it * 0.01}" }
        assertEquals(400, status { api.route(mapOf("pts" to many, "mode" to "FAST")) })
        assertEquals(400, status { api.route(mapOf("pts" to "-111,45;-70,45;-111,45", "mode" to "FAST")) })   // 6,400 km as the crow flies
        assertEquals(400, status { api.route(mapOf("pts" to "-111,45;-110,45", "mode" to "WARP")) })
    }

    @Test fun aStateInTheQueryIsRecognised() {
        assertEquals("Montana", Api.STATES["mt"]); assertEquals("Montana", Api.STATES["montana"]); assertEquals("Baja California", Api.STATES["baja"]); assertEquals(null, Api.STATES["xx"])
    }
}
