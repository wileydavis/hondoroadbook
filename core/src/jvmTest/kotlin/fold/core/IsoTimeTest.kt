package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals

class IsoTimeTest {
    @Test fun matchesJavaTime() {
        for (ms in listOf(0L, 951782400000L, 1761573785000L, 4102444800000L, -86400000L, 1709164800000L, 1735689599999L)) {
            assertEquals(java.time.Instant.ofEpochMilli(ms).toString().replace(Regex("\\.\\d+Z$"), "Z"), isoTime(ms))
        }
    }
}
