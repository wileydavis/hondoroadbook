package fold.packer

import fold.core.Graph
import java.io.File

/** Dev: java -cp fold-packer.jar fold.packer.OverviewToolKt graph.bin roads.pmtiles */
fun main(args: Array<String>) {
    val g = Graph.open(args[0])
    Overview.build(g, File(args[1]), { System.err.println(it) })
}
