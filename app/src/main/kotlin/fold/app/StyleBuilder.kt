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

    /**
     * [flavor]: "light" (day), "dark" (night) or "sun" — the noon-sun handlebar style: white ground (the glass mirrors
     * the sky; a bright map out-shines that), wide dark roads, big labels, nothing else drawn (no hillshade either).
     */
    fun build(ctx: Context, all: List<Pack>, flavor: String): String {
        val packs = all.filter { !it.isTerrain }
        val terrain = if (flavor == "sun") emptyList() else all.filter { it.isTerrain }
        val night = flavor == "dark"
        val template = layers(ctx, flavor)
        val style = JSONObject()
        style.put("version", 8)
        style.put("name", "fold-$flavor")
        style.put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
        style.put("sprite", "asset://sprites/" + if (night) "dark" else "light")
        val sources = JSONObject()
        packs.forEachIndexed { i, p ->
            sources.put("pack$i", JSONObject().put("type", "vector").put("url", "pmtiles://file://" + p.tiles.absolutePath))
            p.overview?.let { ov -> sources.put("ov$i", JSONObject().put("type", "vector").put("url", "pmtiles://file://" + ov.absolutePath)) }
        }
        terrain.forEachIndexed { i, p ->
            sources.put("terrain$i", JSONObject().put("type", "raster").put("url", "pmtiles://file://" + p.tiles.absolutePath).put("tileSize", 256))
        }
        style.put("sources", sources)
        val out = JSONArray()
        var hillDone = false
        for (li in 0 until template.length()) {
            val layer = template.getJSONObject(li)
            if (layer.getString("type") == "background") { out.put(layer); continue }
            // hillshade sits over land cover and under water, roads and labels
            if (!hillDone && (layer.getString("id") == "roads_runway" || layer.getString("id") == "water")) {
                hillDone = true
                terrain.forEachIndexed { i, _ ->
                    out.put(JSONObject().put("id", "hill_t$i").put("type", "raster").put("source", "terrain$i")
                        .put("paint", JSONObject().put("raster-opacity", if (night) 0.5 else 0.8).put("raster-resampling", "linear").put("raster-fade-duration", 0)))
                }
            }
            val fromOverview = layer.optString("source") == "overview"
            for (i in packs.indices) {
                if (fromOverview && packs[i].overview == null) continue
                val clone = JSONObject(layer.toString())
                clone.put("id", layer.getString("id") + "_p$i")
                clone.put("source", if (fromOverview) "ov$i" else "pack$i")
                out.put(clone)
            }
        }
        style.put("layers", out)
        return style.toString()
    }
}
