package fold.core
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class GpxRouteTest { @Test fun roundTrip() {
  val lats = DoubleArray(500) { 45.0 + it * 0.001 }; val lons = DoubleArray(500) { -111.0 + Math.sin(it / 20.0) * 0.01 }
  val text = Gpx.writeRoute("Manhattan → Walla Walla (Fast)", "FAST", doubleArrayOf(45.0, 45.2, 45.499), doubleArrayOf(-111.0, -111.01, -111.0), listOf("Start", "Via 1", "Walla Walla"), lats, lons)
  val t = Gpx.parse(text)!!
  assertEquals("Manhattan → Walla Walla (Fast)", t.name); assertEquals("FAST", t.mode); assertEquals(3, t.rteLats.size); assertTrue(t.hasTrack && t.lats.size in 50..500)
  println("gpx ${text.length} bytes, track pts ${t.lats.size}, route pts ${t.rteLats.size}, mode ${t.mode}")
  // plain track from elsewhere: no rte, still loads
  val plain = Gpx.parse("<gpx><trk><name>ride</name><trkseg><trkpt lat=\"45\" lon=\"-111\"/><trkpt lat=\"45.1\" lon=\"-111\"/></trkseg></trk></gpx>")!!
  assertTrue(plain.hasTrack && !plain.hasRoute)
} }
