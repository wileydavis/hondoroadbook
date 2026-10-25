package fold.packer

import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.domain.v0_6.Way
import org.openstreetmap.osmosis.core.task.v0_6.Sink
import crosby.binary.osmosis.OsmosisReader
import org.openstreetmap.osmosis.xml.common.CompressionMethod
import org.openstreetmap.osmosis.xml.v0_6.XmlReader
import java.io.File

/** Streams an OSM file (pbf or xml) through a callback for nodes and ways. */
object Osm {
    fun read(file: File, onNode: ((Node) -> Unit)?, onWay: ((Way) -> Unit)?) {
        val sink = object : Sink {
            override fun initialize(metaData: MutableMap<String, Any>?) {}
            override fun process(ec: EntityContainer) {
                when (val e = ec.entity) {
                    is Node -> onNode?.invoke(e)
                    is Way -> onWay?.invoke(e)
                    else -> {}
                }
            }
            override fun complete() {}
            override fun close() {}
        }
        val name = file.name.lowercase()
        if (name.endsWith(".pbf")) {
            val r = OsmosisReader(file)
            r.setSink(sink); r.run()
        } else {
            val cm = when { name.endsWith(".gz") -> CompressionMethod.GZip; name.endsWith(".bz2") -> CompressionMethod.BZip2; else -> CompressionMethod.None }
            val r = XmlReader(file, false, cm)
            r.setSink(sink); r.run()
        }
    }

    fun tags(e: org.openstreetmap.osmosis.core.domain.v0_6.Entity): Map<String, String> {
        val t = e.tags
        if (t.isEmpty()) return emptyMap()
        val m = HashMap<String, String>(t.size * 2)
        for (tag in t) m[tag.key] = tag.value
        return m
    }
}
