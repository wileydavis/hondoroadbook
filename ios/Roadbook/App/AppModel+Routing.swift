import CoreLocation
import Foundation
import FoldCore
import UIKit

extension AppModel {
    /// Plan from a point on the map instead of where the phone is. Cleared by END.
    func setStart(_ c: CLLocationCoordinate2D) {
        planStart = c; drawStart()
        pin = nil; clearPinMarker()
        showToast("Start set — now pick a destination (GO TO, or long-press and ROUTE HERE)", 5)
        if let d = destination { startCompare(startFix(), d) } else { switchMode(.idle) }
    }

    func startFix() -> Fix {
        if let ps = planStart { return Fix(ps) }
        return lastFix ?? Fix(map.center)
    }

    /// Routes to a point. Starts from the GPS fix; without one (indoors, planning a trip at the kitchen table)
    /// it starts from the last position the phone knew, or failing that the middle of the map, and says so.
    func routeTo(_ dest: CLLocationCoordinate2D, _ label: String) {
        var fix = lastFix
        var fromWhere = ""
        if let ps = planStart { fix = Fix(ps); fromWhere = "Routed from your chosen start" }
        if fix == nil {
            if let last = Location.shared.lastKnown { fix = Fix(last.coordinate); fromWhere = "No GPS fix yet — routed from your last known position" }
            else { fix = Fix(map.center); fromWhere = "No GPS fix yet — routed from the middle of the map" }
        }
        let f0 = fix!
        if packs.packFor(f0.lat, f0.lon) == nil { showToast(lastFix == nil ? "No GPS fix, and the map center is outside your maps. Move the map over an installed pack or wait for GPS." : "You are outside your maps.", 6); return }
        if packs.packFor(dest.latitude, dest.longitude) == nil { showToast("That point is outside your maps.", 6); return }
        destination = dest; destName = label; vias = []
        if !fromWhere.isEmpty { showToast(fromWhere, 6) }
        pin = nil; clearPinMarker(); showPinMarker(dest)
        startCompare(f0, dest)
    }

    private func along(_ p: CLLocationCoordinate2D, _ r: Route) -> Double {
        var best = Double.greatestFiniteMagnitude; var at = 0.0
        for i in 0..<r.size { let d = Geo.shared.fastDistance(lat1: p.latitude, lon1: p.longitude, lat2: r.lats.get(index: i), lon2: r.lons.get(index: i)); if d < best { best = d; at = r.cum.get(index: i) } }
        return at
    }

    /// "Go by here": pulls the route through a point. The via is slotted into the list by where it falls along
    /// the current route, so you can add them in any order.
    func addVia(_ c: CLLocationCoordinate2D, _ label: String) {
        guard let dest = destination else { return }
        let idx: Int
        if let r = route, !vias.isEmpty { let a = along(c, r); idx = vias.firstIndex { along($0, r) > a } ?? vias.count } else { idx = vias.count }
        vias.insert(c, at: idx)
        pin = nil; clearPinMarker()
        let fix = startFix()
        let isName = !label.isEmpty && !(label.first?.isNumber ?? true) && label.first != "-"
        showToast("Via " + (isName ? label : "point") + " — routing…")
        drawVias()
        if mode == .nav { computeRoute(fix, dest, initial: true) } else { startCompare(compareFrom ?? fix, dest) }
    }

    /// Reroute (or single-mode routing): one mode, straight into navigation.
    func computeRoute(_ from: Fix, _ dest: CLLocationCoordinate2D, initial: Bool) {
        routeGeneration += 1; let gen = routeGeneration
        let p = profile
        if initial && toast == nil { showToast("Routing…") }
        rerouting = true
        let viasCopy = vias
        worker.async { [self] in
            let result: Result<Route, Error> = Result { try routeOne(from, dest, viasCopy, p) }
            packs.multiRouter().releaseMemory()
            DispatchQueue.main.async {
                self.rerouting = false
                if gen != self.routeGeneration { return }
                switch result {
                case .success(let r): self.startNavigation(r, track: false)
                case .failure(let e): if initial { self.showToast(e.text, 6) }
                }
            }
        }
    }

    /// Worker-thread routing for one mode through the vias. Throws with a readable message.
    func routeOne(_ from: Fix, _ dest: CLLocationCoordinate2D, _ viasCopy: [CLLocationCoordinate2D], _ p: Profile) throws -> Route {
        let router = packs.multiRouter()
        if router.routers.isEmpty { throw Fail("Could not open routing data") }
        if let e = packs.routerErrors.first { DispatchQueue.main.async { self.showToast(e, 6) } }
        if try router.snap(lat: from.lat, lon: from.lon, profile: p, maxMeters: 1500, roadsOnly: false) == nil { throw Fail("No road near you") }
        if try router.snap(lat: dest.latitude, lon: dest.longitude, profile: p, maxMeters: 6000, roadsOnly: false) == nil { throw Fail("No road within 6 km of the destination") }
        var pts: [KotlinDoubleArray] = [KotlinDoubleArray.of([from.lat, from.lon])]
        for v in viasCopy {
            if try router.snap(lat: v.latitude, lon: v.longitude, profile: p, maxMeters: 6000, roadsOnly: false) == nil { throw Fail("No road within 6 km of a via point") }
            pts.append(KotlinDoubleArray.of([v.latitude, v.longitude]))
        }
        pts.append(KotlinDoubleArray.of([dest.latitude, dest.longitude]))
        let t0 = Date()
        guard let rr = try router.routeVia(points: pts, profile: p) else { throw Fail("No route found inside the loaded maps.") }
        NSLog("Roadbook: route %@ took %.1f s", p.name, Date().timeIntervalSince(t0))
        return try Route.companion.fromGraphRoute(r: rr, mr: router, profile: p)
    }

    /// The compare screen: every enabled mode is routed in turn and drawn as it finishes, whole-route view,
    /// with a row per mode. Tap a row (or a line) to select; GO starts; VIA pulls all of them.
    func startCompare(_ from: Fix, _ dest: CLLocationCoordinate2D) {
        let modes = prefs.modeSet
        candidates = []
        compareFrom = from
        if !modes.contains(profile) { profile = modes[0] }
        switchMode(.compare)
        compareInProgress = true
        compareGeneration += 1; let gen = compareGeneration
        routeGeneration += 1   // cancel any single-mode routing in flight
        let viasCopy = vias
        follow = false
        worker.async { [self] in
            var firstError: String?
            // the search arrays stay allocated across the modes; when the compare is over (or cancelled) they go
            defer { packs.multiRouter().releaseMemory() }
            for p in modes {
                if gen != compareGeneration { return }
                let r: Route?
                do { r = try routeOne(from, dest, viasCopy, p) } catch { if firstError == nil { firstError = error.text }; r = nil }
                DispatchQueue.main.async {
                    if gen != self.compareGeneration { return }
                    if let r = r { self.candidates.append((p, r)) }
                    self.compareInProgress = p != modes.last
                    self.drawCandidates()
                    if self.candidates.count == 1 || p == modes.last { self.fitCandidates() }
                }
            }
            DispatchQueue.main.async {
                if gen != self.compareGeneration { return }
                if self.candidates.isEmpty { self.showToast(firstError ?? "No route found", 6); self.cancelCompare() }
                else if !self.candidates.contains(where: { $0.0 == self.profile }) { self.profile = self.candidates[0].0; self.drawCandidates() }
            }
        }
    }

    func candidate(_ p: Profile) -> Route? { candidates.first { $0.0 == p }?.1 }

    func drawCandidates() {
        map.set("cmp-src", MapController.collection(candidates.map { p, r in MapController.lineFeature(r.coords, ["color": p.color, "sel": NSNumber(value: p == profile), "mode": p.name]) }))
    }

    func fitCandidates() {
        var pts: [CLLocationCoordinate2D] = []
        for (_, r) in candidates { let step = max(1, Int(r.size) / 400); var i: Int32 = 0; while i < r.size { pts.append(r.coordinate(ofVertex: i)); i += Int32(step) } }
        if let f = compareFrom { pts.append(CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon)) }
        if pts.count < 2 { return }
        let h = map.size.height
        map.fit(pts, insets: UIEdgeInsets(top: 40, left: 24, bottom: max(h / 3, 120 + CGFloat(prefs.modeSet.count) * 44), right: 24), animated: true)
    }

    func selectCandidate(_ p: Profile) { profile = p; prefs.profile = p; drawCandidates() }

    func goWithSelected() {
        guard let r = candidate(profile) else { showToast("Pick a route first"); return }
        compareGeneration += 1
        startNavigation(r, track: false)
    }

    func cancelCompare() {
        compareGeneration += 1
        candidates = []; compareInProgress = false
        map.clear("cmp-src")
        if route != nil { switchMode(.nav) } else { destination = nil; vias = []; drawVias(); clearPinMarker(); planStart = nil; drawStart(); switchMode(.idle) }
    }

    func setModes(_ sel: [Profile]) {
        prefs.modeSet = sel.isEmpty ? [.fast] : sel
        if let from = compareFrom, let dest = destination { startCompare(from, dest) }
    }

    // ---- saved routes: GPX with the points as a route and the line as a track; anyone can open it

    func routeGpx(_ r: Route) -> String {
        let from = compareFrom.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) } ?? r.coordinate(ofVertex: 0)
        var pts = [from]; pts += vias; if let d = destination { pts.append(d) }
        var names = ["Start"]; for i in vias.indices { names.append("Via \(i + 1)") }; names.append(destName.isEmpty ? "End" : destName)
        let title = (destName.isEmpty ? "Route" : destName) + " (" + profile.label + ")"
        let text = Gpx.shared.writeRoute(name: title, mode: profile.name, ptLats: KotlinDoubleArray.of(pts.map { $0.latitude }), ptLons: KotlinDoubleArray.of(pts.map { $0.longitude }), ptNames: names, lineLats: r.lats, lineLons: r.lons, maxPoints: 4000)
        // Roadbook: the router's turns go in as cues, so the file is a roadbook you can annotate and follow as a track
        let cues = r.maneuverList.filter { $0.turn != .depart && $0.turn != .arrive }.map { m in Cue(lat: r.lats.get(index: m.vertex), lon: r.lons.get(index: m.vertex), turn: m.turn, note: m.turn == .roundabout ? "exit \(m.exit)" : m.name, level: 0) }
        return text.replacingOccurrences(of: "  <rte>", with: cues.map { Gpx.shared.cueXml(c: $0) }.joined() + "  <rte>")
    }

    func routeFileName() -> String {
        let n = (destName.isEmpty ? "route" : destName).lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return "roadbook-route-" + String(n.prefix(40)) + "-" + profile.name.lowercased() + ".gpx"
    }

    func saveOrShareRoute(_ r: Route) {
        ask("Route: " + (destName.isEmpty ? "route" : destName),
            Units.distance(r.length) + " · " + Units.time(r.timeSeconds) + " · " + profile.label + "\n\nSaved as a GPX file: any map app opens it; Roadbook re-routes it on the recipient's own maps.",
            [Dialog.Btn(label: "SAVE TO FILES") { [self] in safe { let u = try Files.write(routeFileName(), routeGpx(r)); showToast("Saved \(u.lastPathComponent) — it will show under TRACK", 5) } },
             Dialog.Btn(label: "SHARE…") { [self] in Files.shareText(routeFileName(), routeGpx(r)) },
             Dialog.Btn(label: "Cancel", role: .cancel) {}])
    }

    /// The written directions, for the turn-list sheet.
    func turnListLines(_ r: Route) -> [(String, String)] {
        r.maneuverList.filter { $0.turn != .depart }.map { m in
            let what = m.turn == .arrive ? "Arrive" : m.turn == .roundabout ? "Roundabout, exit \(m.exit)" : m.turn.title
            return (Units.distance(m.at), what + (!m.name.isEmpty && m.turn != .arrive ? " onto " + m.name : ""))
        }
    }

    func rerouteNow() {
        guard let fix = lastFix else { showToast("Waiting for a GPS fix…"); return }
        guard let dest = destination else { showToast("Tracks can't be rerouted — ride back to the line or END"); return }
        if packs.packFor(fix.lat, fix.lon) == nil { showToast("You are outside your maps."); return }
        if rerouting { return }
        // vias we have already passed stay behind
        if let r = route, let f = follower, !vias.isEmpty {
            let here = f.view(nowMs: nowMs()).along
            vias.removeAll { along($0, r) < here - 500 }
        }
        computeRoute(fix, dest, initial: true)
    }

    /// Off route: nothing happens by itself. The route is fixed until you tap REROUTE or END.
    func handleOffRoute(_ v: NavView, _ fix: Fix) {
        if v.onRoute { offSince = 0; return }
        if offSince == 0 { offSince = fix.timeMs }
    }
}
