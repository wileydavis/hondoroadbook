import CoreLocation
import MapLibre
import UIKit
import FoldCore

/// What the map reports back to the app model.
protocol MapEvents: AnyObject {
    func mapTapped(_ coord: CLLocationCoordinate2D, at point: CGPoint)
    func mapLongPressed(_ coord: CLLocationCoordinate2D)
    func mapGestureBegan()
    func mapIdle()
    func mapStyleLoaded()
    func mapCameraMoved()
}

/// Owns the MLNMapView: the style built from the installed packs, every overlay source and layer (puck, route,
/// pin, vias, cues, compare candidates, lead line, coverage fog, library lines) and the camera rules. The
/// model tells it what to draw; it tells the model what the rider did. A port of the map half of MainActivity.
final class MapController: NSObject, MLNMapViewDelegate {
    let mapView: MLNMapView
    weak var events: MapEvents?
    private var sources: [String: MLNShapeSource] = [:]
    private(set) var styleReady = false
    private var night = false
    private(set) var styledPacks: [Pack] = []
    var isTrack = false
    var routeColor: UIColor = Theme.route

    override init() {
        mapView = MLNMapView(frame: .zero)
        super.init()
        mapView.delegate = self
        mapView.logoView.isHidden = true
        mapView.attributionButton.isHidden = true
        // the map's compass shows when the map is turned (outside navigation; the HUD has its own while navigating)
        mapView.compassViewPosition = .topRight
        mapView.compassViewMargins = CGPoint(x: 16, y: 170)
        mapView.allowsTilting = false
        mapView.allowsRotating = true
        mapView.showsUserLocation = false            // the puck is ours: it follows the route, not the raw fix
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        for g in mapView.gestureRecognizers ?? [] { if let t = g as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { tap.require(toFail: t) } }
        mapView.addGestureRecognizer(tap)
        let long = UILongPressGestureRecognizer(target: self, action: #selector(onLong(_:))); long.minimumPressDuration = 0.5
        mapView.addGestureRecognizer(long)
    }

    // ---- style ------------------------------------------------------------------------------------------------

    /// Rebuilds the style from the chosen packs. The overlays are re-added when the style finishes loading.
    func loadStyle(_ packs: [Pack], flavor: String) throws {
        self.night = flavor == "dark"; styledPacks = packs; styleReady = false
        mapView.styleURL = try StyleBuilder.write(packs, flavor: flavor)
    }

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        sources.removeAll()
        addOverlays(style)
        styleReady = true
        events?.mapStyleLoaded()
    }

    func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) { NSLog("Roadbook: map failed: \(error)") }

    private func source(_ id: String, _ style: MLNStyle) -> MLNShapeSource {
        let s = MLNShapeSource(identifier: id, shape: MLNShapeCollectionFeature(shapes: []), options: nil)
        style.addSource(s); sources[id] = s; return s
    }

    private static let font = NSExpression(forConstantValue: ["Noto Sans Medium"])
    private static func c(_ v: Any) -> NSExpression { NSExpression(forConstantValue: v) }
    private static func zoomStops(_ stops: [Int: Double]) -> NSExpression {
        NSExpression(forMLNInterpolating: .zoomLevelVariable, curveType: .linear, parameters: nil, stops: NSExpression(forConstantValue: stops))
    }

    private func line(_ id: String, _ src: String, color: UIColor, width: Double, opacity: Double = 1, dash: [Double]? = nil) -> MLNLineStyleLayer {
        let l = MLNLineStyleLayer(identifier: id, source: sources[src]!)
        l.lineColor = Self.c(color); l.lineWidth = Self.c(width); l.lineOpacity = Self.c(opacity)
        l.lineCap = Self.c("round"); l.lineJoin = Self.c("round")
        if let d = dash { l.lineDashPattern = Self.c(d) }
        return l
    }

    private func addOverlays(_ st: MLNStyle) {
        st.setImage(Icons.puck(night: night), forName: "puck")
        st.setImage(Icons.pin(night: night), forName: "pin")
        st.setImage(Icons.mark(night: night), forName: "mark")
        st.setImage(Icons.start(), forName: "start")
        st.setImage(Icons.via(night: night), forName: "via")
        st.setImage(Icons.cue(danger: false, night: night), forName: "cue")
        st.setImage(Icons.cue(danger: true, night: night), forName: "cue-danger")
        st.setImage(Icons.turn(night: night), forName: "turn")
        for id in ["marks-src", "via-src", "cmp-src", "start-src", "lead-src", "route-src", "cues-src", "turns-src", "pin-src", "puck-src", "lib-src", "fog-src", "cover-src", "cover-label-src", "places-src"] { _ = source(id, st) }
        let ink = Theme.ink.resolved(night), paper = Theme.paper.resolved(night), accent = Theme.accent.resolved(night), casing = Theme.routeCasing.resolved(night), track = Theme.track.resolved(night)
        // route lines go under the map's labels (road names, shields, towns), so a route never hides the name of the road it follows
        let firstLabel = st.layers.first { $0 is MLNSymbolStyleLayer }
        func addUnderLabels(_ l: MLNStyleLayer) { if let f = firstLabel { st.insertLayer(l, below: f) } else { st.addLayer(l) } }
        // the library's roadbooks
        addUnderLabels(line("lib-casing", "lib-src", color: casing, width: 7, opacity: 0.8))
        addUnderLabels(line("lib-line", "lib-src", color: track, width: 4, opacity: 0.85))
        let libLabel = MLNSymbolStyleLayer(identifier: "lib-label", source: sources["lib-src"]!)
        libLabel.text = NSExpression(forKeyPath: "name"); libLabel.textFontSize = Self.c(12); libLabel.textFontNames = Self.font
        libLabel.textColor = Self.c(track); libLabel.textHaloColor = Self.c(casing); libLabel.textHaloWidth = Self.c(1.5); libLabel.symbolPlacement = Self.c("line"); libLabel.textAllowsOverlap = Self.c(false)
        st.addLayer(libLabel)
        addUnderLabels(line("route-casing", "route-src", color: casing, width: 10, opacity: 0.9))
        addUnderLabels(line("route-line", "route-src", color: isTrack ? track : routeColor, width: 6))
        // coverage: fog over everything not in an installed pack, outlines and names of what is
        let fog = MLNFillStyleLayer(identifier: "fog-layer", source: sources["fog-src"]!)
        fog.fillColor = Self.c(ink); fog.fillAntialiased = Self.c(false); fog.fillOpacity = Self.zoomStops([7: 0.22, 10: 0]); fog.maximumZoomLevel = 10
        st.addLayer(fog)
        let outline = line("cover-outline", "cover-src", color: accent, width: 1.2, dash: [2, 2]); outline.lineOpacity = Self.zoomStops([7: 0.9, 9: 0]); outline.maximumZoomLevel = 9
        st.addLayer(outline)
        let coverLabel = MLNSymbolStyleLayer(identifier: "cover-label", source: sources["cover-label-src"]!)
        coverLabel.text = NSExpression(forKeyPath: "name"); coverLabel.textFontNames = Self.font; coverLabel.textFontSize = Self.c(13); coverLabel.textTransform = Self.c("uppercase"); coverLabel.textLetterSpacing = Self.c(0.15)
        coverLabel.textColor = Self.c(accent); coverLabel.textHaloColor = Self.c(paper); coverLabel.textHaloWidth = Self.c(1.5); coverLabel.textAllowsOverlap = Self.c(false); coverLabel.maximumZoomLevel = 7
        st.addLayer(coverLabel)
        // the packs' places: fuel, camping, water, beds, repair, food — icons from the map's own sprite, each from its own zoom
        let places = MLNSymbolStyleLayer(identifier: "places-layer", source: sources["places-src"]!)
        places.iconImageName = NSExpression(forKeyPath: "icon"); places.iconAnchor = Self.c("center"); places.iconAllowsOverlap = Self.c(false); places.iconIgnoresPlacement = Self.c(false); places.iconScale = Self.c(1)
        places.text = NSExpression(forKeyPath: "name"); places.textFontNames = Self.font; places.textFontSize = Self.c(11); places.textAnchor = Self.c("top"); places.textOffset = Self.c(NSValue(cgVector: CGVector(dx: 0, dy: 0.9)))
        places.textColor = Self.c(Theme.inkSoft.resolved(night)); places.textHaloColor = Self.c(paper); places.textHaloWidth = Self.c(1.5); places.textOptional = Self.c(true)
        places.predicate = NSPredicate(format: "$zoomLevel >= minzoom"); places.minimumZoomLevel = 11
        st.addLayer(places)
        let marks = MLNSymbolStyleLayer(identifier: "marks-layer", source: sources["marks-src"]!)
        marks.iconImageName = Self.c("mark"); marks.iconAnchor = Self.c("center"); marks.iconAllowsOverlap = Self.c(true); marks.iconIgnoresPlacement = Self.c(true); marks.iconScale = Self.c(1)
        marks.text = NSExpression(forKeyPath: "name"); marks.textFontNames = Self.font; marks.textFontSize = Self.c(13); marks.textAnchor = Self.c("top"); marks.textOffset = Self.c(NSValue(cgVector: CGVector(dx: 0, dy: 0.9)))
        marks.textColor = Self.c(ink); marks.textHaloColor = Self.c(paper); marks.textHaloWidth = Self.c(2); marks.textOptional = Self.c(true)
        st.addLayer(marks)
        let cues = MLNSymbolStyleLayer(identifier: "cues-layer", source: sources["cues-src"]!)
        cues.iconImageName = NSExpression(forKeyPath: "icon"); cues.iconAnchor = Self.c("center"); cues.iconAllowsOverlap = Self.c(true); cues.iconIgnoresPlacement = Self.c(true)
        cues.text = NSExpression(forKeyPath: "label"); cues.textFontNames = Self.font; cues.textFontSize = Self.c(12); cues.textAnchor = Self.c("top"); cues.textOffset = Self.c(NSValue(cgVector: CGVector(dx: 0, dy: 1.0)))
        cues.textColor = Self.c(ink); cues.textHaloColor = Self.c(paper); cues.textHaloWidth = Self.c(2); cues.textOptional = Self.c(true); cues.minimumZoomLevel = 9
        st.addLayer(cues)
        // compare candidates: the unselected routes thin and faded, the selected one bold, drawn last
        let modeColors = Dictionary(uniqueKeysWithValues: Profiles.all.map { (NSExpression(forConstantValue: $0.name), NSExpression(forConstantValue: Profiles.color($0))) })
        let colorByMode = NSExpression(forMLNMatchingKey: NSExpression(forKeyPath: "mode"), in: modeColors, default: NSExpression(forConstantValue: UIColor.gray))
        for sel in [false, true] {
            let suffix = sel ? "-sel" : ""
            let pred = NSPredicate(format: "sel == %@", NSNumber(value: sel))
            let cc = line("cmp-casing\(suffix)", "cmp-src", color: casing, width: sel ? 9 : 5, opacity: sel ? 0.9 : 0.5); cc.predicate = pred; addUnderLabels(cc)
            let cl = line("cmp-line\(suffix)", "cmp-src", color: .gray, width: sel ? 6 : 3, opacity: sel ? 1 : 0.75); cl.lineColor = colorByMode; cl.predicate = pred; addUnderLabels(cl)
        }
        // the "string to the next turn": straight, dashed, half transparent, on top of the route but under labels
        // the stretch of route between the rider and the next turn glows softly in the route's own colour
        let lead = line("lead-line", "lead-src", color: isTrack ? track : routeColor, width: 18, opacity: 0.32); lead.lineBlur = Self.c(5); addUnderLabels(lead)
        // plan mode: the router's turns on the selected route, small hollow rings, tap to reword
        let turns = MLNSymbolStyleLayer(identifier: "turns-layer", source: sources["turns-src"]!); turns.iconImageName = Self.c("turn"); turns.iconAllowsOverlap = Self.c(true); turns.iconIgnoresPlacement = Self.c(true); turns.minimumZoomLevel = 10; st.addLayer(turns)
        let start = MLNSymbolStyleLayer(identifier: "start-layer", source: sources["start-src"]!); start.iconImageName = Self.c("start"); start.iconAllowsOverlap = Self.c(true); start.iconIgnoresPlacement = Self.c(true); st.addLayer(start)
        let via = MLNSymbolStyleLayer(identifier: "via-layer", source: sources["via-src"]!); via.iconImageName = Self.c("via"); via.iconAllowsOverlap = Self.c(true); via.iconIgnoresPlacement = Self.c(true); st.addLayer(via)
        let pin = MLNSymbolStyleLayer(identifier: "pin-layer", source: sources["pin-src"]!); pin.iconImageName = Self.c("pin"); pin.iconAnchor = Self.c("bottom"); pin.iconAllowsOverlap = Self.c(true); pin.iconIgnoresPlacement = Self.c(true); st.addLayer(pin)
        let puck = MLNSymbolStyleLayer(identifier: "puck-layer", source: sources["puck-src"]!)
        puck.iconImageName = Self.c("puck"); puck.iconAllowsOverlap = Self.c(true); puck.iconIgnoresPlacement = Self.c(true); puck.iconRotationAlignment = Self.c("map"); puck.iconRotation = Self.c(0); puck.iconScale = Self.c(1)
        st.addLayer(puck)
    }

    // ---- drawing ----------------------------------------------------------------------------------------------

    func set(_ id: String, _ shape: MLNShape?) { sources[id]?.shape = shape ?? MLNShapeCollectionFeature(shapes: []) }
    func clear(_ id: String) { set(id, nil) }

    static func point(_ c: CLLocationCoordinate2D, _ attrs: [String: Any] = [:]) -> MLNPointFeature { let f = MLNPointFeature(); f.coordinate = c; f.attributes = attrs; return f }
    static func lineFeature(_ coords: [CLLocationCoordinate2D], _ attrs: [String: Any] = [:]) -> MLNPolylineFeature { var c = coords; let f = MLNPolylineFeature(coordinates: &c, count: UInt(c.count)); f.attributes = attrs; return f }
    static func collection(_ shapes: [MLNShape & MLNFeature]) -> MLNShapeCollectionFeature { MLNShapeCollectionFeature(shapes: shapes) }

    func drawRoute(_ r: Route?, color: UIColor) {
        routeColor = color
        guard let r = r else { clear("route-src"); return }
        set("route-src", Self.lineFeature(r.coords))
        (mapView.style?.layer(withIdentifier: "route-line") as? MLNLineStyleLayer)?.lineColor = Self.c(color)
        (mapView.style?.layer(withIdentifier: "lead-line") as? MLNLineStyleLayer)?.lineColor = Self.c(color)
    }

    func updatePuck(_ lat: Double, _ lon: Double, heading: Double) {
        set("puck-src", Self.point(CLLocationCoordinate2D(latitude: lat, longitude: lon)))
        (mapView.style?.layer(withIdentifier: "puck-layer") as? MLNSymbolStyleLayer)?.iconRotation = Self.c(heading)
    }

    func drawCoverage(_ packs: [Pack]) { set("fog-src", Coverage.fog(packs)); set("cover-src", Coverage.outlines(packs)); set("cover-label-src", Coverage.labels(packs)) }

    // ---- camera -----------------------------------------------------------------------------------------------

    var zoom: Double { mapView.zoomLevel }
    var bearing: Double { mapView.direction }
    var center: CLLocationCoordinate2D { mapView.centerCoordinate }
    var visibleBounds: MLNCoordinateBounds { mapView.visibleCoordinateBounds }
    func metersPerPoint(at lat: Double) -> Double { mapView.metersPerPoint(atLatitude: lat) }
    var size: CGSize { mapView.bounds.size }

    /// Centers a point at a zoom and bearing; the inset puts the target lower on the screen while navigating.
    func ease(to c: CLLocationCoordinate2D, zoom: Double, bearing: Double, navInset: Bool, animated: Bool) {
        let h = mapView.bounds.height
        // padding centers the target in the remaining area: top 0.5h, bottom 0.1h puts you at 70% down the screen
        mapView.contentInset = navInset ? UIEdgeInsets(top: h * 0.5, left: 0, bottom: h * 0.1, right: 0) : .zero
        mapView.setCenter(c, zoomLevel: zoom, direction: bearing, animated: animated)
    }

    func fit(_ coords: [CLLocationCoordinate2D], insets: UIEdgeInsets, animated: Bool) {
        guard coords.count >= 2 else { return }
        mapView.contentInset = .zero
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for c in coords { minLat = min(minLat, c.latitude); maxLat = max(maxLat, c.latitude); minLon = min(minLon, c.longitude); maxLon = max(maxLon, c.longitude) }
        let b = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon), ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
        mapView.setVisibleCoordinateBounds(b, edgePadding: insets, animated: animated, completionHandler: nil)
    }

    func resetNorth(animated: Bool) { mapView.setDirection(0, animated: animated) }

    // ---- hit testing ----------------------------------------------------------------------------------------

    func features(at p: CGPoint, layer: String, radius: CGFloat) -> [MLNFeature] {
        guard styleReady else { return [] }
        return mapView.visibleFeatures(in: CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2), styleLayerIdentifiers: [layer])
    }

    @objc private func onTap(_ g: UITapGestureRecognizer) {
        let p = g.location(in: mapView)
        events?.mapTapped(mapView.convert(p, toCoordinateFrom: mapView), at: p)
    }
    @objc private func onLong(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        events?.mapLongPressed(mapView.convert(g.location(in: mapView), toCoordinateFrom: mapView))
    }

    // ---- gestures and idle -----------------------------------------------------------------------------------

    private static let gestures: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom]

    func mapView(_ mapView: MLNMapView, shouldChangeFrom oldCamera: MLNMapCamera, to newCamera: MLNMapCamera, reason: MLNCameraChangeReason) -> Bool {
        if !reason.isDisjoint(with: Self.gestures) && !gesturing { gesturing = true; events?.mapGestureBegan() }
        return true
    }
    private var gesturing = false

    func mapViewRegionIsChanging(_ mapView: MLNMapView) { events?.mapCameraMoved() }

    func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        gesturing = false
        events?.mapCameraMoved()
        events?.mapIdle()
    }
}
