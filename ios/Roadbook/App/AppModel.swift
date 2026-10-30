import Combine
import CoreLocation
import Foundation
import FoldCore
import MapLibre
import SwiftUI
import UIKit

/// What the HUD draws, refreshed twenty times a second while navigating. Its own object so the rest of the
/// screen does not redraw with it.
final class HudState: ObservableObject {
    @Published var visible = false
    @Published var turn: Turn = .straight
    @Published var exit: Int32 = 0
    @Published var progress: Double = 0
    @Published var distance = ""
    @Published var street = ""
    @Published var offVisible = false
    @Published var offText = ""
    @Published var showReroute = false
    @Published var rerouteLabel = "REROUTE"
    @Published var remaining = ""
    @Published var speed = ""
    @Published var eta = ""
    @Published var elevation = ""
    /// the fixed-distance scale bar on the nav card: one mile (one kilometre) unless that would not fit
    @Published var navScaleLabel = ""
    @Published var navScalePx: CGFloat = 0
    @Published var compass: Double = 0
    @Published var arrived = false
    @Published var darkText = ""
    @Published var scaleLabel = ""
    @Published var scalePx: CGFloat = 0
}

enum Mode { case idle, pin, compare, nav, edit }
enum ZoomMode { case overview, turn, close }

/// A modal question: title, message, up to three buttons.
struct Dialog: Identifiable {
    struct Btn: Identifiable { let id = UUID(); let label: String; var role: ButtonRole? = nil; let action: () -> Void }
    let id = UUID()
    let title: String
    let message: String
    let buttons: [Btn]
    var cancelable = true
}

/// The sheets the glove menu and the panels open.
/// What a search is for: a destination (the pin panel), or one of plan mode's questions, answered straight into the plan.
struct SearchAsk {
    var title = "Go to"
    /// one more way out: a button with its own action ("FROM WHERE I AM", "BACK TO THE START")
    var extra: (String, () -> Void)? = nil
    /// what a pick does; nil = the pin panel
    var onPick: ((CLLocationCoordinate2D, String) -> Void)? = nil
    static let goTo = SearchAsk()
}

enum Sheet: Identifiable {
    case search(SearchAsk = .goTo)
    case maps, catalog, trackPicker, fetchCode, turnList, cueList, modes, stops
    case cue(lat: Double, lon: Double, existing: Cue?)
    case markEdit(existing: Mark?, suggested: String, lat: Double, lon: Double)
    case markCard(Mark)
    case publish(name: String, gpx: String, recorded: Bool)
    case libraryItem(LibraryItem)
    case fileName(suggest: String, body: () -> String, done: (Bool) -> Void)
    case trackLoaded(GpxTrack, URL?)
    var id: String {
        switch self {
        case .search: return "search"; case .maps: return "maps"; case .catalog: return "catalog"; case .trackPicker: return "track"; case .fetchCode: return "fetch"; case .stops: return "stops"
        case .turnList: return "turns"; case .cueList: return "cues"; case .modes: return "modes"; case .cue: return "cue"; case .markEdit: return "markEdit"; case .markCard: return "markCard"
        case .publish: return "publish"; case .libraryItem: return "lib"; case .fileName: return "fileName"; case .trackLoaded: return "trackLoaded"
        }
    }
}

/// The app: a port of MainActivity. The map is a MapController; the ride is the RideSession; this holds the
/// state in between (the pin, the destination and vias, the candidates on the compare screen, the track being
/// edited) and every action the screens can take.

final class AppModel: ObservableObject, MapEvents {
    let prefs = Prefs()
    let packs = PackStore()
    let marks = MarkStore()
    let map = MapController()
    let hud = HudState()
    let ride = RideSession.shared
    let recorder = Recorder.shared

    @Published var mode: Mode = .idle
    @Published var pin: CLLocationCoordinate2D?
    @Published var pinName = ""
    @Published var destination: CLLocationCoordinate2D?
    @Published var destName = ""
    /// points the route must pass through, in order; "go by Spokane"
    @Published var vias: [CLLocationCoordinate2D] = []
    /// the vias' names ("Lolo Pass", or "" for a point on the map), one per via
    @Published var viaNames: [String] = []
    /// a start chosen on the map for planning, instead of the GPS position
    @Published var planStart: CLLocationCoordinate2D?
    /// the chosen start's name, "" for a point on the map or the GPS position
    @Published var startName = ""
    /// a plan is being set up on the map: the first GPS fix must not yank the camera away from it
    var planningCamera: Bool { planStart != nil || mode == .compare || mode == .edit || mode == .pin }
    @Published var route: Route?
    @Published var isTrack = false
    @Published var profile: Profile
    /// candidate routes by mode, in the order they were computed
    @Published var candidates: [(Profile, Route)] = []
    @Published var compareInProgress = false
    @Published var rerouting = false
    @Published var toast: String?
    @Published var dialog: Dialog?
    @Published var sheet: Sheet?
    @Published var progress: ProgressState?
    @Published var menuOpen = false
    @Published var libraryOn = false
    @Published var recStatus = ""
    @Published var dimmed = false
    @Published var editLabel = ""
    @Published var noMaps = false
    @Published var hasTerrain = false
    /// the packs whose places are on the map right now
    var placesFor: [Pack] = []
    /// the tapped place's full line ("Town Pump · fuel · 24/7") for the pin panel; "" when the pin is not a place
    var placeLabel = ""

    /// the track being followed or edited, with its authored cues (Roadbook)
    var trackLats: [Double]?; var trackLons: [Double]?; var trackName = "Track"; var trackURL: URL?
    @Published var trackCues: [Cue] = []
    /// a file's own cues, carried through EDIT ROUTE and laid back onto whatever gets planned
    var editCues: [Cue] = []
    /// the cue sheet is open on a plan-mode template (a new note on the line, or one of the router's turns to reword), not an existing cue
    var planCueTemplate = false
    /// the modes the compare screen is limited to (a file is re-planned in its own mode); nil = the rider's chosen set
    @Published var compareModes: [Profile]?
    var compareModeList: [Profile] { compareModes ?? prefs.modeSet }
    /// A planned route with the file's authored cues on it (when a file is being edited).
    func planned(_ r: Route) -> Route {
        if editCues.isEmpty { return r }
        return (try? Route.companion.withCues(r: r, cues: Route.companion.authoredCues(fileCues: editCues, planned: r))) ?? r
    }

    var follow = true
    var zoomMode: ZoomMode = .turn
    var manualZoom = -1.0          // pinch override for the current mode, until the next tap
    var overviewDone = false       // overview fits once, then holds still while the puck moves
    private var gestureFrom: CLLocationCoordinate2D?; private var gestureZoom = 0.0
    var lastFix: Fix?
    /// the last fix's height above sea level, metres (nil until known)
    var lastAltitude: Double?
    private var glowAt = Date.distantPast
    var heading = 0.0
    var compareFrom: Fix?
    var compareGeneration = 0
    var routeGeneration = 0
    var offSince: Int64 = 0
    var libraryItems: [LibraryItem] = []
    var libraryFetchedAt = Date.distantPast
    let worker = DispatchQueue(label: "roadbook.worker", qos: .userInitiated)
    private var hudTimer: Timer?
    private var toastTimer: Timer?
    private var recTimer: Timer?
    private var locSub: UUID?
    private var cancellables: Set<AnyCancellable> = []
    private var styleQueued = false
    var uiVisible = true
    var hinted = false
    var savedBrightness: CGFloat = 0.5
    var lastWritten: URL?

    struct ProgressState { var title: String; var message: String; var cancel: (() -> Void)? }

    var follower: Follower? { ride.controller?.follower }

    init() {
        profile = Profile.companion.parse(name: UserDefaults.standard.string(forKey: "profile"))
        Units.metric = prefs.metric
        Tone.shared.enabled = prefs.sound
        Speech.shared.enabled = prefs.sound
        map.events = self
        map.routeColor = Profiles.color(profile)
        ride.onStopRequested = { [weak self] in self?.endNavigation() }
        ride.onAttention = { [weak self] on in self?.onAttention(on) }
        recorder.onChange = { [weak self] in self?.updateRecStrip() }
        if prefs.batterySaver { applyBatteryFlags(true) }
        applyOrientation()
        // under 15 % while navigating, the saver turns itself on rather than let the ride die on a long leg
        UIDevice.current.isBatteryMonitoringEnabled = true
        NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification).sink { [weak self] _ in self?.batteryChanged() }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification).sink { [weak self] _ in
            guard let s = self else { return }
            s.uiVisible = true
            // a pinch the map thought it saw while we were away must not freeze the auto zoom: back to the mode's own zoom
            s.manualZoom = -1; s.gestureFrom = nil; s.overviewDone = false
            if s.follow, let f = s.lastFix { s.moveCamera(f.lat, f.lon, animate: false) }
        }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in self?.uiVisible = false }.store(in: &cancellables)
        // @Published fires before the new value is stored: style from the value it hands over, never from prefs.night here
        prefs.$night.dropFirst().sink { [weak self] night in self?.loadStyle(night: night) }.store(in: &cancellables)
        locSub = Location.shared.subscribe { [weak self] l in self?.onFix(l) }
        Location.shared.start()
        let afterTerms = { [self] in
            if recorder.recover() { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.offerRecovery() } }
            else if ride.leftover() != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.offerResume() } }
        }
        if prefs.accepted { afterTerms() } else { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.showTerms(then: afterTerms) } }
        updateRecStrip()
        worker.async { [self] in
            packs.scan()
            DispatchQueue.main.async {
                self.hasTerrain = self.packs.packs.contains { $0.isTerrain }
                self.loadStyle()
                if let p = self.packs.packs.first, self.lastFix == nil { self.fitPack(p) }
                else if self.packs.packs.isEmpty { self.noMaps = true; self.showToast("No maps yet — tap MAPS → DOWNLOAD") }
            }
            // warm the routers in the background so the first route is instant
            _ = packs.multiRouter()
        }
    }

    // ---------------------------------------------------------------- style / overlays

    /// Packs worth drawing for the current camera: those in view, nearest first, at most six (style size matters).
    func visiblePacks() -> [Pack] {
        let all = packs.packs
        let roads = all.filter { !$0.isTerrain }, terrain = all.filter { $0.isTerrain }
        if all.count <= 6 { return all }
        let b = map.visibleBounds; let c = map.center
        func pick(_ list: [Pack]) -> [Pack] {
            let inView = list.filter { p in let m = p.manifest; return m.maxLat >= b.sw.latitude && m.minLat <= b.ne.latitude && m.maxLon >= b.sw.longitude && m.minLon <= b.ne.longitude }
            return Array(inView.sorted { p, q in dist(c, p) < dist(c, q) }.prefix(6))
        }
        func dist(_ c: CLLocationCoordinate2D, _ p: Pack) -> Double { let m = p.manifest; return Geo.shared.fastDistance(lat1: c.latitude, lon1: c.longitude, lat2: (m.minLat + m.maxLat) / 2, lon2: (m.minLon + m.maxLon) / 2) }
        return pick(roads) + pick(terrain)
    }

    private func refreshVisiblePacks() {
        if packs.packs.count <= 6 { return }
        let v = visiblePacks().filter { prefs.terrain || !$0.isTerrain }
        if v.map({ $0.dir }) != map.styledPacks.map({ $0.dir }) { loadStyle() }
    }

    func loadStyle(night: Bool? = nil) {
        let chosen = visiblePacks().filter { prefs.terrain || !$0.isTerrain }
        map.isTrack = isTrack
        do { try map.loadStyle(chosen, night: night ?? prefs.night) } catch { showToast("Style error: \(error.text)") }
    }

    func mapStyleLoaded() {
        // re-draw anything we had
        map.drawCoverage(packs.roadPacks)
        placesFor = []; drawPlaces()
        if let r = route { map.drawRoute(r, color: isTrack ? Theme.track : Profiles.color(profile)) }
        drawCues(); drawMarks(); drawVias(); drawStart()
        if let p = pin { map.set("pin-src", MapController.point(p)) }
        if let f = lastFix { map.updatePuck(f.lat, f.lon, heading: heading) }
        if mode == .compare { drawCandidates() }
        if libraryOn { drawLibrary() }
        switchMode(mode)
        if mode == .nav && follower != nil { startHudTimer() }
    }

    func onPacksChanged() {
        hasTerrain = packs.packs.contains { $0.isTerrain }; noMaps = packs.packs.isEmpty
        loadStyle()
        if lastFix == nil, let p = packs.packs.last { fitPack(p) }
    }

    func fitPack(_ p: Pack) {
        let m = p.manifest
        map.fit([CLLocationCoordinate2D(latitude: m.minLat, longitude: m.minLon), CLLocationCoordinate2D(latitude: m.maxLat, longitude: m.maxLon)], insets: UIEdgeInsets(top: 40, left: 40, bottom: 40, right: 40), animated: false)
    }

    func drawMarks() {
        map.set("marks-src", MapController.collection(marks.marks.map { MapController.point(CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon), ["name": $0.name, "id": NSNumber(value: $0.id)]) }))
    }
    func drawVias() { map.set("via-src", MapController.collection(vias.enumerated().map { i, v in MapController.point(v, ["idx": NSNumber(value: i)]) })) }

    /// The styled packs' places onto the map (parsed on the worker the first time a pack is seen).
    func drawPlaces() {
        if !prefs.places { map.clear("places-src"); placesFor = []; return }
        let packs = map.styledPacks.filter { !$0.isTerrain }
        if packs == placesFor { return }
        placesFor = packs
        worker.async { [self] in
            var feats: [MLNShape & MLNFeature] = []
            for p in packs { for pl in p.loadPlaces() { feats.append(MapController.point(CLLocationCoordinate2D(latitude: pl.lat, longitude: pl.lon), ["icon": pl.icon, "name": pl.name, "label": pl.label, "minzoom": NSNumber(value: pl.minZoom)])) } }
            let fc = MapController.collection(feats)
            DispatchQueue.main.async { if self.placesFor == packs { self.map.set("places-src", fc) } }
        }
    }
    func togglePlaces() { prefs.places.toggle(); placesFor = []; drawPlaces() }

    /// Tap on a place: it becomes the pin, named, with the usual ROUTE HERE / VIA / FROM / ★.
    func openPlace(_ f: MLNFeature) {
        guard let pt = f as? MLNPointFeature else { return }
        let label = (f.attribute(forKey: "label") as? String) ?? ""
        pin = pt.coordinate; pinName = ((f.attribute(forKey: "name") as? String) ?? "").isEmpty ? String(label.split(separator: "·").first ?? "").trimmingCharacters(in: .whitespaces) : (f.attribute(forKey: "name") as? String) ?? ""
        placeLabel = label
        showPinMarker(pt.coordinate); follow = false
        switchMode(.pin)
    }

    /// Tap on a via: take it out of the route (and route again without it).
    func offerRemoveVia(_ idx: Int) {
        guard vias.indices.contains(idx) else { return }
        let letter = String(UnicodeScalar(UInt8(65 + min(idx, 25))))
        let nm = viaNames.indices.contains(idx) && !viaNames[idx].isEmpty ? " · " + viaNames[idx] : ""
        ask("Stop \(letter)" + nm, fmt(vias[idx]), [Dialog.Btn(label: "REMOVE VIA", role: .destructive) { [self] in removeVia(idx) }, Dialog.Btn(label: "Cancel", role: .cancel) {}])
    }
    func removeVia(_ idx: Int) {
        guard vias.indices.contains(idx) else { return }
        vias.remove(at: idx); if viaNames.indices.contains(idx) { viaNames.remove(at: idx) }; drawVias()
        guard let dest = destination else { return }
        showToast("Via removed — routing…")
        if mode == .nav { computeRoute(lastFix ?? startFix(), dest, initial: true) } else { startCompare(compareFrom ?? startFix(), dest) }
    }
    func drawStart() { map.set("start-src", planStart.map { MapController.collection([MapController.point($0)]) }) }
    func drawCues() {
        let shown = mode == .compare ? editCues : trackCues
        map.set("cues-src", MapController.collection(shown.enumerated().map { i, c in
            MapController.point(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), ["icon": c.turn == .danger ? "cue-danger" : "cue", "idx": NSNumber(value: i), "label": c.note.isEmpty ? c.turn.spoken : String(c.note.prefix(24))])
        }))
    }
    func showPinMarker(_ c: CLLocationCoordinate2D) { map.set("pin-src", MapController.point(c)) }
    func clearPinMarker() { map.clear("pin-src") }

    // ---------------------------------------------------------------- location

    private func onFix(_ l: CLLocation) {
        let fix = Fix(l)
        let first = lastFix == nil
        lastFix = fix
        if l.verticalAccuracy >= 0 { lastAltitude = l.altitude }
        if fix.speed > 1.5 && fix.bearing >= 0 { heading = fix.bearing }
        if let f = follower {
            let v = f.view(nowMs: nowMs())
            // the marker points where the GPS says the bike points, never along the road it is assumed to be on
            if mode == .nav { map.updatePuck(v.lat, v.lon, heading: v.speed > 1.5 ? v.heading : heading); handleOffRoute(v, fix) }
            else { map.updatePuck(fix.lat, fix.lon, heading: heading) }
        } else { map.updatePuck(fix.lat, fix.lon, heading: heading) }
        if (follow || (first && !planningCamera)) && uiVisible { moveCamera(fix.lat, fix.lon, animate: !first) }
    }

    func haptic(_ times: Int, long: Bool = false) {
        for i in 0..<times {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.12) {
                if long { UINotificationFeedbackGenerator().notificationOccurred(.success) } else { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
            }
        }
    }

    // ---------------------------------------------------------------- camera

    func cycleZoom() {
        zoomMode = zoomMode == .overview ? .turn : zoomMode == .turn ? .close : .overview
        // feel which view you landed in without looking: one tap = next turn, two = close up, long = whole route
        switch zoomMode { case .turn: haptic(1); case .close: haptic(2); case .overview: haptic(1, long: true) }
        manualZoom = -1; overviewDone = false
        follow = true
        showToast(zoomMode == .overview ? "Whole route" : zoomMode == .turn ? "Next turn" : "Close up")
        if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) }
    }

    /// Zoom that puts the next maneuver comfortably on screen, bounded to a useful range.
    private func turnZoom() -> Double {
        guard let f = follower else { return 15.5 }
        let v = f.view(nowMs: nowMs())
        let r = f.route
        let d = max(60.0, v.next.map { Geo.shared.fastDistance(lat1: v.lat, lon1: v.lon, lat2: r.lats.get(index: $0.vertex), lon2: r.lons.get(index: $0.vertex)) } ?? v.distToNext)
        let lat = lastFix?.lat ?? 45.0
        // points per meter at zoom z ≈ 2^z / (156543 cos lat). You sit 70% down the screen, so the turn must fit in
        // the smaller of the room above you and half the width, whatever direction it lies.
        let size = map.size
        let hudBottom = hud.visible ? 200.0 : 0.0
        let room = max(200.0, min((size.height * 0.70 - hudBottom) * 0.80, size.width * 0.42))
        let wantMpp = d / room
        let z = log(156543.03 * cos(lat * .pi / 180) / wantMpp) / log(2.0)
        return min(16.5, max(4.0, z))
    }

    func moveCamera(_ lat: Double, _ lon: Double, animate: Bool) {
        let nav = mode == .nav
        if nav && zoomMode == .overview {
            if overviewDone { return }
            overviewDone = true
            guard let r = route else { return }
            var pts = r.coords; pts.append(CLLocationCoordinate2D(latitude: lat, longitude: lon))
            let h = map.size.height
            map.resetNorth(animated: false)
            map.fit(pts, insets: UIEdgeInsets(top: h / 5, left: 24, bottom: h / 4, right: 24), animated: animate)
            return
        }
        let zoom: Double = !nav ? max(map.zoom, 15.0) : manualZoom > 0 ? manualZoom : zoomMode == .close ? 17.3 : turnZoom()
        map.ease(to: CLLocationCoordinate2D(latitude: lat, longitude: lon), zoom: zoom, bearing: nav ? heading : 0, navInset: nav, animated: animate)
    }

    func locate() {
        follow = true
        if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) } else { showToast("Waiting for GPS…") }
    }

    // ---------------------------------------------------------------- map events

    func mapGestureBegan() {
        if mode == .nav { gestureFrom = map.center; gestureZoom = map.zoom } else { follow = false }
    }
    func mapCameraMoved() {
        hud.compass = map.bearing
        updateScale()
    }
    func mapIdle() {
        refreshVisiblePacks()
        if libraryOn && mode == .idle && Date().timeIntervalSince(libraryFetchedAt) > 1.5 { fetchLibrary(announce: false) }
        // navigating: a pinch keeps following at the new zoom; a drag stops following
        if let from = gestureFrom, mode == .nav {
            gestureFrom = nil
            let t = map.center
            let a = map.mapView.convert(from, toPointTo: map.mapView), b = map.mapView.convert(t, toPointTo: map.mapView)
            let moved = hypot(a.x - b.x, a.y - b.y)
            let zoomed = abs(map.zoom - gestureZoom) > 0.1
            if zoomed && moved < 60 { manualZoom = map.zoom; if zoomMode == .overview { zoomMode = .turn; overviewDone = false }; follow = true; if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) } }
            else if moved >= 60 { follow = false }
        }
    }

    func mapLongPressed(_ c: CLLocationCoordinate2D) {
        if mode == .edit { addCue(at: c.latitude, c.longitude, snap: true); return }
        if mode == .compare, snapToPlan(c.latitude, c.longitude) != nil { addPlanCue(at: c.latitude, c.longitude); return }
        if route != nil { follow = false }
        placeLabel = ""
        pin = c; pinName = fmt(c)
        showPinMarker(c)
        switchMode(.pin)
    }

    func mapTapped(_ ll: CLLocationCoordinate2D, at p: CGPoint) {
        if menuOpen { menuOpen = false; return }
        let markHit = map.features(at: p, layer: "marks-layer", radius: 22).first
        let mark = (markHit?.attribute(forKey: "id") as? NSNumber).flatMap { id in marks.marks.first { $0.id == id.int64Value } }
        let cueHit = (mode == .edit || mode == .compare || (mode == .nav && isTrack)) ? map.features(at: p, layer: "cues-layer", radius: 22).first : nil
        let cueList = mode == .compare ? editCues : trackCues
        let cue = (cueHit?.attribute(forKey: "idx") as? NSNumber).flatMap { i in cueList.indices.contains(i.intValue) ? cueList[i.intValue] : nil }
        // plan mode: the router's own turns on the selected route can be reworded
        let turnHit = (mode == .compare && cue == nil) ? map.features(at: p, layer: "turns-layer", radius: 18).first : nil
        let turnIdx = (turnHit?.attribute(forKey: "idx") as? NSNumber).map { $0.intValue }
        let lineHit = mode == .compare ? (map.features(at: p, layer: "cmp-line", radius: 14).first ?? map.features(at: p, layer: "cmp-line-sel", radius: 14).first) : nil
        let picked = (lineHit?.attribute(forKey: "mode") as? String).flatMap { Profiles.byName($0) }
        let viaHit = (!vias.isEmpty && (mode == .compare || mode == .nav || mode == .pin)) ? map.features(at: p, layer: "via-layer", radius: 20).first : nil
        let viaIdx = (viaHit?.attribute(forKey: "idx") as? NSNumber).map { $0.intValue }
        let libHit = (libraryOn && mode == .idle) ? map.features(at: p, layer: "lib-line", radius: 16).first : nil
        let libItem = (libHit?.attribute(forKey: "id") as? String).flatMap { id in libraryItems.first { $0.id == id } }
        // a place from the packs (fuel, camping…): a pin with its name, so ROUTE HERE / VIA / ★ work on it
        let placeHit = (mode != .nav && mode != .edit && prefs.places) ? map.features(at: p, layer: "places-layer", radius: 18).first : nil
        if let c = cue { if mode == .compare { editPlanCue(c) } else { editCue(c) } }
        else if let ti = turnIdx { rewordTurn(ti) }
        else if let vi = viaIdx { offerRemoveVia(vi) }
        else if let it = libItem { sheet = .libraryItem(it) }
        else if let m = mark { sheet = .markCard(m) }
        else if let ph = placeHit { openPlace(ph) }
        else if let pm = picked, candidates.contains(where: { $0.0 == pm }) { profile = pm; prefs.profile = pm; drawCandidates() }
        else if mode == .pin { clearPin() }
        else if mode == .nav { cycleZoom() }
    }

    private func updateScale() {
        let mpp = map.metersPerPoint(at: map.center.latitude)
        guard mpp > 0, !mpp.isNaN else { return }
        let target = 110.0 * mpp
        func nice(_ v: Double) -> Double { let e = floor(log10(v)); let base = pow(10, e); let f = v / base; return base * (f >= 5 ? 5 : f >= 2 ? 2 : 1) }
        let px: Double; let lbl: String
        if Units.metric { let n = nice(target); lbl = n >= 1000 ? "\(Int(n / 1000)) km" : "\(Int(n)) m"; px = n / mpp }
        else {
            let ft = target * 3.28084
            if ft < 2640 { let n = nice(ft); lbl = "\(Int(n)) ft"; px = n / 3.28084 / mpp } else { let mi = nice(ft / 5280); lbl = "\(Int(mi)) mi"; px = mi * 1609.344 / mpp }
        }
        if hud.scaleLabel != lbl { hud.scaleLabel = lbl }
        hud.scalePx = CGFloat(px)
        // the nav card's bar: one mile / one kilometre, stepping down only when it would not fit, up when it would vanish
        let ladder: [(Double, String)] = Units.metric
            ? [(50, "50 m"), (100, "100 m"), (200, "200 m"), (500, "500 m"), (1000, "1 km"), (2000, "2 km"), (5000, "5 km"), (10000, "10 km"), (20000, "20 km"), (50000, "50 km")]
            : [(30.48, "100 ft"), (76.2, "250 ft"), (152.4, "500 ft"), (402.336, "¼ mi"), (804.672, "½ mi"), (1609.344, "1 mi"), (3218.688, "2 mi"), (8046.72, "5 mi"), (16093.44, "10 mi"), (32186.88, "20 mi"), (80467.2, "50 mi")]
        var i = ladder.firstIndex { $0.1 == (Units.metric ? "1 km" : "1 mi") } ?? 5
        let room = Double(map.size.width) - 110
        while i > 0 && ladder[i].0 / mpp > room { i -= 1 }
        while i < ladder.count - 1 && ladder[i].0 / mpp < 36 { i += 1 }
        if hud.navScaleLabel != ladder[i].1 { hud.navScaleLabel = ladder[i].1 }
        hud.navScalePx = CGFloat(ladder[i].0 / mpp)
    }

    // ---------------------------------------------------------------- pin / modes

    func onSearchPick(_ hit: Hit) {
        let c = CLLocationCoordinate2D(latitude: hit.lat, longitude: hit.lon)
        placeLabel = ""
        pin = c; pinName = hit.label
        showPinMarker(c); follow = false
        map.ease(to: c, zoom: 15, bearing: 0, navInset: false, animated: true)
        switchMode(.pin)
    }

    func goToMark(_ m: Mark) {
        let c = CLLocationCoordinate2D(latitude: m.lat, longitude: m.lon)
        placeLabel = ""
        pin = c; pinName = m.name; showPinMarker(c)
        follow = false
        map.ease(to: c, zoom: 15, bearing: 0, navInset: false, animated: true)
        switchMode(.pin)
    }

    var pinLabel: String {
        guard let p = pin else { return "" }
        if !placeLabel.isEmpty { return placeLabel }
        return pinName.isEmpty ? fmt(p) : pinName
    }
    /// a name worth saving (not a coordinate)
    var pinNameIsName: Bool { !pinName.isEmpty && !(pinName.first?.isNumber ?? true) && pinName.first != "-" }

    func clearPin() {
        pin = nil; placeLabel = ""; clearPinMarker()
        if !candidates.isEmpty { if let d = destination { showPinMarker(d) }; switchMode(.compare); drawCandidates() }
        else if route != nil { if let d = destination { showPinMarker(d) }; switchMode(.nav) }
        else { switchMode(.idle) }
    }

    func savePin() {
        guard let p = pin else { return }
        sheet = .markEdit(existing: nil, suggested: pinNameIsName ? pinName : "", lat: p.latitude, lon: p.longitude)
    }

    func switchMode(_ m: Mode) {
        mode = m
        menuOpen = false
        updateRecStrip()
        if m != .nav { hud.visible = false; hud.offVisible = false; map.clear("lead-src") }
        map.mapView.compassView.isHidden = (m == .nav)
        if m != .compare { map.clear("cmp-src"); map.clear("turns-src") }
    }

    // ---------------------------------------------------------------- terms, once

    static let termsShort = "Roadbook is a riding aid, not a substitute for your eyes, your judgment or the law.\n\nThe map is made by volunteers and can be wrong: roads close, gates lock, a track that is public on the map may be private on the ground, and a legal road may be unsafe for you today. A route is a suggestion; a cue is a reminder of what the data says. If a cue and the road disagree, the road wins.\n\nYou are responsible for riding safely and legally, for watching the road rather than the screen, and for carrying what you need where there is no help. Roadbook is free and provided as is, with no warranty, and Hondo Garage is not liable for what happens on the road.\n\nFull terms: hondoroadbook.com/terms.html"

    /// Once, on first launch: what Roadbook is and is not. The full terms are on the site.
    func showTerms(then: @escaping () -> Void) {
        ask("Before you ride", AppModel.termsShort,
            [Dialog.Btn(label: "I understand") { [self] in prefs.accepted = true; then() },
             Dialog.Btn(label: "Read the terms") { [self] in openURL("https://hondoroadbook.com/terms.html"); DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.showTerms(then: then) } }],
            cancelable: false)
    }
    func openURL(_ s: String) { if let u = URL(string: s) { UIApplication.shared.open(u) } }

    // ---------------------------------------------------------------- toasts, dialogs

    func showToast(_ msg: String, _ seconds: Double = 3.5) {
        toast = msg
        toastTimer?.invalidate()
        toastTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in DispatchQueue.main.async { self?.toast = nil } }
    }
    func ask(_ title: String, _ message: String, _ buttons: [Dialog.Btn], cancelable: Bool = true) { dialog = Dialog(title: title, message: message, buttons: buttons, cancelable: cancelable) }
    func info(_ title: String, _ message: String) { ask(title, message, [Dialog.Btn(label: "OK") {}]) }
    func safe(_ block: () throws -> Void) { do { try block() } catch { showToast(error.text) } }

    // ---------------------------------------------------------------- orientation lock, sound, night

    func applyOrientation() {
        switch prefs.orientation {
        case "portrait": AppDelegate.orientations = .portrait
        case "landscapeLeft": AppDelegate.orientations = .landscapeLeft
        case "landscapeRight": AppDelegate.orientations = .landscapeRight
        case "upsideDown": AppDelegate.orientations = .portraitUpsideDown
        default: AppDelegate.orientations = .all
        }
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: AppDelegate.orientations)) { _ in }
        }
        UIApplication.top?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    var orientationLocked: Bool { !prefs.orientation.isEmpty }

    /// Holds the screen the way it is now, and remembers it.
    func toggleOrientationLock() {
        if orientationLocked { prefs.orientation = ""; showToast("Rotation follows the phone") }
        else {
            let o = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation ?? .portrait
            prefs.orientation = o == .landscapeLeft ? "landscapeLeft" : o == .landscapeRight ? "landscapeRight" : o == .portraitUpsideDown ? "upsideDown" : "portrait"
            showToast("Screen locked this way")
        }
        applyOrientation(); haptic(1)
    }

    func toggleSound() {
        let on = !prefs.sound; prefs.sound = on; Tone.shared.enabled = on; Speech.shared.enabled = on
        if on { Tone.shared.turn(); Speech.shared.say("Sound on"); showToast("Sound on · " + Tone.outputName, 4) } else { Speech.shared.stop(); Tone.releaseIfIdle() }
    }

    func toggleNight() { prefs.night.toggle() }

    func toggleTerrain() { prefs.terrain.toggle(); loadStyle() }

    func setUnits(metric: Bool) { prefs.metric = metric; updateScale() }

    // ---------------------------------------------------------------- recording strip

    func updateRecStrip() {
        let on = recorder.running
        if on {
            let s = Int(recorder.elapsed)
            recStatus = "● \(s / 3600):" + String(format: "%02d", s / 60 % 60) + " · " + Units.distance(recorder.distance) + " · \(recorder.cues.count) cues"
            recTimer?.invalidate(); recTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: false) { [weak self] _ in DispatchQueue.main.async { self?.updateRecStrip() } }
        } else { recStatus = "" }
        if let why = recorder.lastFailure { showToast("Not recording: \(why)", 7) }
    }

    // ---------------------------------------------------------------- HUD timer

    func startHudTimer() {
        hudTimer?.invalidate()
        hudTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.hudTick() }
        hudTimer?.tolerance = 0.01
    }
    func stopHudTimer() { hudTimer?.invalidate(); hudTimer = nil }

    private func hudTick() {
        guard let c = ride.controller else { return }
        let now = nowMs()
        // the ride session ticks the controller (sounds, checkpoints); this screen only reads the view — unless the
        // session could not start, when the screen keeps the ride going itself for as long as it is open
        if ride.running { renderHud(c.view(nowMs: now)) }
        else { c.sound = Tone.shared.enabled; c.speechReady = Speech.shared.enabled; if let f = lastFix, f !== c.lastFix { c.update(fix: f) }; if let v = try? c.tick(nowMs: now) { renderHud(v) } }
    }

    /// Dashed line from you to the next turn, for turns still far out (next-turn view only).
    /// The glow from the rider to the next cue: the route's own points between them, refreshed a few times a second.
    private func updateLead(_ v: NavView) {
        guard let next = v.next, let r = follower?.route, v.onRoute, !v.arrived else { if glowAt != .distantPast { glowAt = .distantPast; map.clear("lead-src") }; return }
        if Date().timeIntervalSince(glowAt) < 0.25 { return }
        glowAt = Date()
        var pts = [CLLocationCoordinate2D(latitude: v.lat, longitude: v.lon)]
        let from = max(0, v.segment + 1), to = next.vertex
        if to >= from {
            let step = max(1, Int(to - from) / 600); var i = from
            while i <= to { pts.append(r.coordinate(ofVertex: i)); i += Int32(step) }
            if Int(to - from) % step != 0 { pts.append(r.coordinate(ofVertex: to)) }
        } else { pts.append(r.coordinate(ofVertex: to)) }
        map.set("lead-src", MapController.lineFeature(pts))
    }

    private func renderHud(_ v: NavView) {
        if dimmed {
            let m = v.next
            hud.darkText = v.arrived ? "ARRIVED" : !v.onRoute ? "OFF TRACK" : m == nil ? Units.distance(v.remaining) : Units.cue(v.distToNext) + "\n" + (m!.turn == .arrive ? "Destination" : m!.phrase())
        }
        if mode != .nav { if v.fixState == .valid || v.fixState == .degraded { map.updatePuck(v.lat, v.lon, heading: v.speed > 1.5 ? v.heading : heading) }; return }
        if v.arrived {
            hud.visible = true; hud.offVisible = false; hud.arrived = true
            hud.turn = .arrive; hud.distance = "ARRIVED"; hud.street = ""; hud.progress = 1
            hud.remaining = Units.distance(0); hud.eta = ""
            return
        }
        hud.arrived = false
        if v.fixState == FixState.none || v.fixState == FixState.stale {
            // no position to trust: say so instead of showing frozen guidance as current
            hud.visible = false; hud.offVisible = true
            hud.offText = v.fixState == FixState.none ? "WAITING FOR GPS" : "NO GPS · " + Units.time(v.fixAge)
            hud.showReroute = false
            hud.remaining = Units.distance(v.remaining); hud.eta = Units.time(v.etaSeconds); hud.speed = "—"
            return
        }
        if !v.onRoute {
            hud.visible = false; hud.offVisible = true
            let rel = Geo.shared.turnAngle(a: heading, b: v.offBearing)
            let glyph = rel > 150 || rel < -150 ? "↓" : rel > 110 ? "↘" : rel > 60 ? "→" : rel > 20 ? "↗" : rel < -110 ? "↙" : rel < -60 ? "←" : rel < -20 ? "↖" : "↑"
            hud.offText = "OFF TRACK  \(glyph) " + Units.cue(v.offDist)
            hud.showReroute = !isTrack
            hud.rerouteLabel = rerouting ? "ROUTING…" : "REROUTE"
        } else {
            hud.offVisible = false; hud.visible = true
            if let m = v.next {
                // a danger at the same place as a turn takes the arrow; the words carry the whole group
                let lead = v.hazard ?? m
                hud.turn = lead.turn; hud.exit = lead.turn == .danger ? lead.level : lead.exit
                hud.distance = Units.cue(v.distToNext) + (v.fixState == .degraded ? " ?" : "")
                let g = v.groupList
                hud.street = m.turn == .arrive ? "Destination"
                    : g.count > 1 ? g.map { x in x.note.isEmpty ? ((x.turn == .danger || x.turn == .note) ? x.turn.spoken : (x.name.isEmpty ? x.turn.spoken : x.name)) : x.note }.joined(separator: " · ")
                    : !m.note.isEmpty ? m.note : (m.name.isEmpty ? (isTrack ? "" : "unnamed road") : m.name)
                hud.progress = v.progress
            }
        }
        hud.remaining = Units.distance(v.remaining); hud.eta = Units.clockFromNow(v.etaSeconds) + " · " + Units.time(v.etaSeconds)
        hud.speed = Units.speed(v.speed); hud.elevation = lastAltitude.map { Units.elevation($0) } ?? ""
        hud.compass = map.bearing
        updateLead(v)
        // smooth puck between fixes
        if v.fixState == .valid || v.fixState == .degraded { map.updatePuck(v.lat, v.lon, heading: v.speed > 1.5 ? v.heading : heading) }
    }

    // ---------------------------------------------------------------- open-with (a .gpx or .mappack from Files, Mail, AirDrop)

    func open(url: URL) {
        let name = url.lastPathComponent.lowercased()
        if name.hasSuffix(".mappack") || name.hasSuffix(".zip") { importPacks([url]) }
        else { loadGpx(url) }
    }
}
