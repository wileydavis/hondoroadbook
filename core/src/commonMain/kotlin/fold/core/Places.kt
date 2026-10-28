package fold.core

/**
 * A place from a pack's pois.tsv: fuel, camping, lodging, water, repair, food, town — the things a rider looks for,
 * with a prominence (3 = worth showing from far out, 1 = only close up). The packer writes them; the planner's nearby
 * lookup and the apps' places layer read them.
 */
class Place(val lat: Double, val lon: Double, val prom: Int, val cat: String, val kind: String, val name: String, val extra: String) {
    /** the sprite icon for this place (the same sheet the map's own points of interest use) */
    val icon: String get() = when (cat) {
        "fuel" -> "fuel"
        "camping" -> if (kind == "RV park") "caravan_site" else "camp_site"
        "lodging" -> "lodging"
        "water" -> "drinking_water"
        "repair" -> if (kind.startsWith("motorcycle")) "motorcycle" else "car_repair"
        "food" -> when (kind) { "supermarket", "general" -> "supermarket"; "convenience" -> "convenience"; "cafe", "bakery" -> "cafe"; "fast food" -> "fast_food"; "bar", "pub" -> "bar"; else -> "restaurant" }
        else -> "townspot"
    }
    /** the zoom from which it is drawn: the prominent ones from far out, a cafe only close up */
    val minZoom: Double get() = when {
        cat == "food" -> if (prom >= 2) 13.0 else 14.5
        prom >= 3 -> 11.0
        prom == 2 -> 12.5
        else -> 14.0
    }
    /** one line for a card or a pin label: "Town Pump · fuel · 24/7" */
    val label: String get() = listOf(name.ifEmpty { kind.replaceFirstChar { it.uppercase() } }, if (name.isEmpty()) "" else kind, extra).filter { it.isNotEmpty() }.joinToString(" · ")
}

object Places {
    /** pois.tsv: lat_e7, lon_e7, prominence, category, kind, name, extra. Towns are skipped: the map labels those itself. */
    fun parse(text: String, skipTowns: Boolean = true): List<Place> {
        val out = ArrayList<Place>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val lat = f[0].toLongOrNull() ?: continue; val lon = f[1].toLongOrNull() ?: continue
            val cat = f[3]
            if (skipTowns && cat == "town") continue
            out.add(Place(lat / 1e7, lon / 1e7, f[2].toIntOrNull() ?: 1, cat, f[4], f.getOrElse(5) { "" }, f.getOrElse(6) { "" }))
        }
        return out
    }
}
