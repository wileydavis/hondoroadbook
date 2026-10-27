package fold.packer

import fold.core.Graph
import fold.core.MultiRouter
import fold.core.Profile
import fold.core.Route
import fold.core.Router
import fold.core.Turn
import java.io.File

/**
 * Dev utility: routes on a graph.bin and writes GeoJSON (line + maneuvers) for renders and docs.
 *   java -cp fold-packer.jar fold.packer.SceneKt graph.bin lat,lon lat,lon[;lat,lon…] FAST,BACKROADS out.json
 */
fun main(args: Array<String>) {
    val g = Graph.open(args[0])
    val mr = MultiRouter(listOf(Router(g)))
    val pts = args[1].split(';').map { it.split(',').map(String::toDouble).toDoubleArray() }
    val profiles = args[2].split(',').map { Profile.valueOf(it) }
    val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (p in profiles) {
        val rr = mr.routeVia(pts.map { doubleArrayOf(it[0], it[1]) }, p) ?: run { System.err.println("no route for $p"); null } ?: continue
        val r = Route.fromGraphRoute(rr, mr, p)
        if (!first) sb.append(','); first = false
        sb.append("{\"type\":\"Feature\",\"properties\":{\"mode\":\"").append(p.name).append("\",\"color\":\"").append(p.color).append("\",\"length\":").append(r.length).append(",\"time\":").append(r.timeSeconds).append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in 0 until r.size) { if (i > 0) sb.append(','); sb.append('[').append(r.lons[i]).append(',').append(r.lats[i]).append(']') }
        sb.append("]}}")
        for (m in r.maneuvers) if (m.turn != Turn.DEPART) {
            sb.append(",{\"type\":\"Feature\",\"properties\":{\"mode\":\"").append(p.name).append("\",\"turn\":\"").append(m.turn.name).append("\",\"name\":\"").append(m.name.replace("\"", "'")).append("\",\"at\":").append(m.at).append(",\"vertex\":").append(m.vertex).append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(r.lons[m.vertex]).append(',').append(r.lats[m.vertex]).append("]}}")
        }
        System.err.println("${p.name}: ${"%.1f".format(r.length / 1000)} km, ${(r.timeSeconds / 60).toInt()} min, ${r.maneuvers.size} maneuvers")
    }
    sb.append("]}")
    File(args[3]).writeText(sb.toString())
}
