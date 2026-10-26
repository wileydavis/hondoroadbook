package fold.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BACKEND-04: warming the same pending pack a hundred times schedules it once, and the warmer's queue is bounded. */
class WarmTest {
    @Test fun repeatedWarmSchedulesOnce() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fold-warm-test").apply { mkdirs() }
        val packs = object : PackSet("http://127.0.0.1:9/never", dir) {
            // a catalog of one pack that can never be fetched (the port is closed), so it stays pending
            override fun catalog(): List<CatalogPack> = listOf(CatalogPack("us-nowhere-roads", "Nowhere", "Nowhere", "http://127.0.0.1:9/never.mappack", "2026-01-01", 1000L, doubleArrayOf(-112.0, 45.0, -110.0, 46.0)))
        }
        for (i in 0 until 100) packs.warm(-111.5, 45.2, -110.5, 45.8)
        assertTrue(packs.warmQueued() <= 1, "one job for one pack, not a hundred (queued ${packs.warmQueued()})")
        Thread.sleep(1500)
        assertEquals(0, packs.warmQueued(), "the failed job cleared its reservation")
    }
}
