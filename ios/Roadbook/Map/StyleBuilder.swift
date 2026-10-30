import Foundation

/// Builds a MapLibre style entirely from bundled assets and local pack files: glyphs and sprites come from the
/// app bundle (asset://), tiles from pmtiles://file:// URLs. No URL in here is remote. Same logic as the Android
/// StyleBuilder, reading the same layers_{light,dark}.json templates.
enum StyleBuilder {
    private static var cache: [String: [[String: Any]]] = [:]

    private static func layers(_ flavor: String) throws -> [[String: Any]] {
        if let c = cache[flavor] { return c }
        guard let url = Bundle.main.url(forResource: "layers_\(flavor)", withExtension: "json", subdirectory: "style") else { throw Fail("style/layers_\(flavor).json is not in the app bundle") }
        guard let arr = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]] else { throw Fail("layers_\(flavor).json is not a layer list") }
        cache[flavor] = arr; return arr
    }

    /// `flavor`: "light" (day), "dark" (night) or "sun" — the noon-sun handlebar style: black ground, wide bright roads,
    /// big labels, nothing else drawn (no hillshade either), so it reads in glare and spends the least power.
    static func build(_ all: [Pack], flavor: String) throws -> Data {
        let packs = all.filter { !$0.isTerrain }
        let terrain = flavor == "sun" ? [] : all.filter { $0.isTerrain }
        let night = flavor != "light"
        let template = try layers(flavor)
        var style: [String: Any] = ["version": 8, "name": "fold-\(flavor)", "glyphs": "asset://fonts/{fontstack}/{range}.pbf", "sprite": "asset://sprites/\(flavor == "light" ? "light" : "dark")"]
        var sources: [String: Any] = [:]
        for (i, p) in packs.enumerated() {
            sources["pack\(i)"] = ["type": "vector", "url": "pmtiles://file://" + p.tiles.path]
            if let ov = p.overview { sources["ov\(i)"] = ["type": "vector", "url": "pmtiles://file://" + ov.path] }
        }
        for (i, p) in terrain.enumerated() { sources["terrain\(i)"] = ["type": "raster", "url": "pmtiles://file://" + p.tiles.path, "tileSize": 256] }
        style["sources"] = sources
        var out: [[String: Any]] = []
        var hillDone = false
        for layer in template {
            let type = layer["type"] as? String ?? ""; let id = layer["id"] as? String ?? ""
            if type == "background" { out.append(layer); continue }
            // hillshade sits over land cover and under water, roads and labels
            if !hillDone && (id == "roads_runway" || id == "water") {
                hillDone = true
                for i in terrain.indices {
                    out.append(["id": "hill_t\(i)", "type": "raster", "source": "terrain\(i)", "paint": ["raster-opacity": night ? 0.5 : 0.8, "raster-resampling": "linear", "raster-fade-duration": 0]])
                }
            }
            let fromOverview = (layer["source"] as? String) == "overview"
            for i in packs.indices {
                if fromOverview && packs[i].overview == nil { continue }
                var clone = layer
                clone["id"] = id + "_p\(i)"
                clone["source"] = fromOverview ? "ov\(i)" : "pack\(i)"
                out.append(clone)
            }
        }
        style["layers"] = out
        return try JSONSerialization.data(withJSONObject: style)
    }

    /// MapLibre loads a style from a URL: the JSON is written to a temporary file for it.
    static func write(_ all: [Pack], flavor: String) throws -> URL {
        let data = try build(all, flavor: flavor)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("style-\(flavor).json")
        try data.write(to: url, options: .atomic)
        return url
    }
}
