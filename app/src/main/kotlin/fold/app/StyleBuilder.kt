package fold.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a MapLibre style entirely from bundled assets and local pack files:
 * glyphs and sprites come from the APK, tiles from pmtiles://file:// URLs. No URL in here is remote.
 */
object StyleBuilder {
    private val cache = HashMap<String, JSONArray>()

    private fun layers(ctx: Context, flavor: String): JSONArray = cache.getOrPut(flavor) {
        JSONArray(ctx.assets.open("style/layers_$flavor.json").bufferedReader().readText())
    }

    fun build(ctx: Context, packs: List<Pack>, night: Boolean): String {
        val flavor = if (night) "dark" else "light"
        val template = layers(ctx, flavor)
        val style = JSONObject()
        style.put("version", 8)
        style.put("name", "fold-$flavor")
        style.put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
        style.put("sprite", "asset://sprites/$flavor")
        val sources = JSONObject()
        packs.forEachIndexed { i, p ->
            sources.put("pack$i", JSONObject().put("type", "vector").put("url", "pmtiles://file://" + p.tiles.absolutePath))
        }
        style.put("sources", sources)
        val out = JSONArray()
        for (li in 0 until template.length()) {
            val layer = template.getJSONObject(li)
            if (layer.getString("type") == "background") { out.put(layer); continue }
            for (i in packs.indices) {
                val clone = JSONObject(layer.toString())
                clone.put("id", layer.getString("id") + "_p$i")
                clone.put("source", "pack$i")
                out.put(clone)
            }
        }
        style.put("layers", out)
        return style.toString()
    }
}
