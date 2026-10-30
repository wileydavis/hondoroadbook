import CoreLocation
import Foundation
import FoldCore
import MapLibre
import UIKit

extension AppModel {
    /// Plan from a point on the map instead of where the phone is. Cleared by END.
    func setStart(_ c: CLLocationCoordinate2D) {
        planStart = c; startName = placeName(pinName); drawStart()
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
        destination = dest; destName = label; vias = []; viaNames = []
        if !fromWhere.isEmpty { showToast(fromWhere, 6) }
        pin = nil; clearPinMarker(); showPinMarker(dest)
        startCompare(f0, dest)
    }

    /// A search label is a name; a long-press label is coordinates, which is no name at all.
    func placeName(_ label: String) -> String { (!label.isEmpty && !(label.first?.isNumber ?? true) && label.first != "-") ? label : "" }

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
        vias.insert(c, at: idx); viaNames.insert(placeName(label), at: idx)
        pin = nil; clearPinMarker()
        let fix = startFix()
        showToast("Via " + (viaNames[idx].isEmpty ? "point" : viaNames[idx]) + " — routing…")
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
                case .success(let r): self.startNavigation(self.planned(r), track: false)
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
        let modes = compareModeList
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
                    self.drawCandidates(); self.drawCues()
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
        drawTurns()
    }

    /// Plan mode: the router's turns on the selected route (those not already reworded), so a tap can reword them.
    func drawTurns() {
        guard mode == .compare, let r = candidate(profile) else { map.clear("turns-src"); return }
        var feats: [MLNShape & MLNFeature] = []
        for (i, m) in r.maneuverList.enumerated() {
            if m.turn == .depart || m.turn == .arrive { continue }
            let c = r.coordinate(ofVertex: m.vertex)
            if editCues.contains(where: { $0.turn == m.turn && Geo.shared.fastDistance(lat1: $0.lat, lon1: $0.lon, lat2: c.latitude, lon2: c.longitude) < 40 }) { continue }
            feats.append(MapController.point(c, ["idx": NSNumber(value: i)]))
        }
        map.set("turns-src", MapController.collection(feats))
    }

    func fitCandidates() {
        var pts: [CLLocationCoordinate2D] = []
        for (_, r) in candidates { let step = max(1, Int(r.size) / 400); var i: Int32 = 0; while i < r.size { pts.append(r.coordinate(ofVertex: i)); i += Int32(step) } }
        if let f = compareFrom { pts.append(CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon)) }
        if pts.count < 2 { return }
        let h = map.size.height
        map.fit(pts, insets: UIEdgeInsets(top: 40, left: 24, bottom: max(h / 3, 120 + CGFloat(compareModeList.count) * 44), right: 24), animated: true)
    }

    func selectCandidate(_ p: Profile) { profile = p; prefs.profile = p; drawCandidates() }

    func goWithSelected() {
        guard let r = candidate(profile) else { showToast("Pick a route first"); return }
        compareGeneration += 1
        startNavigation(planned(r), track: false)
    }

    func cancelCompare() {
        compareGeneration += 1
        candidates = []; compareInProgress = false
        map.clear("cmp-src")
        if route != nil { switchMode(.nav) } else { destination = nil; destName = ""; vias = []; viaNames = []; drawVias(); clearPinMarker(); planStart = nil; startName = ""; drawStart(); editCues = []; compareModes = nil; drawCues(); switchMode(.idle) }
    }

    func setModes(_ sel: [Profile]) {
        prefs.modeSet = sel.isEmpty ? [.fast] : sel; compareModes = nil
        if let from = compareFrom, let dest = destination { startCompare(from, dest) }
    }

    // ---- saved routes: GPX with the points as a route and the line as a track; anyone can open it

    func routeGpx(_ r0: Route) -> String {
        let r = planned(r0)
        let from = compareFrom.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) } ?? r.coordinate(ofVertex: 0)
        var pts = [from]; pts += vias; if let d = destination { pts.append(d) }
        var names = [startName.isEmpty ? "Start" : startName]
        for i in vias.indices { let n = viaNames.indices.contains(i) ? viaNames[i] : ""; names.append(n.isEmpty ? "Stop " + String(UnicodeScalar(UInt8(65 + min(i, 25)))) : n) }
        names.append(destName.isEmpty ? "End" : destName)
        let title = (destName.isEmpty ? "Route" : destName) + " (" + profile.label + ")"
        let text = Gpx.shared.writeRoute(name: title, mode: profile.name, ptLats: KotlinDoubleArray.of(pts.map { $0.latitude }), ptLons: KotlinDoubleArray.of(pts.map { $0.longitude }), ptNames: names, lineLats: r.lats, lineLons: r.lons, maxPoints: 4000)
        // Roadbook: the router's turns go in as cues (reworded ones with the rider's words, notes and dangers as they are),
        // so the file is a roadbook you can annotate and follow as a track, and EDIT ROUTE brings it back as this plan
        let cues = r.maneuverList.filter { $0.turn != .depart && $0.turn != .arrive }.map { m in
            Cue(lat: r.lats.get(index: m.vertex), lon: r.lons.get(index: m.vertex), turn: m.turn, note: !m.note.isEmpty ? m.note : (m.turn == .roundabout ? "exit \(m.exit)" : m.name), level: m.level) }
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
            for k in vias.indices.reversed() where along(vias[k], r) < here - 500 { vias.remove(at: k); if viaNames.indices.contains(k) { viaNames.remove(at: k) } }
        }
        computeRoute(fix, dest, initial: true)
    }

    /// Off route: nothing happens by itself. The route is fixed until you tap REROUTE or END.
    func handleOffRoute(_ v: NavView, _ fix: Fix) {
        if v.onRoute { offSince = 0; return }
        if offSince == 0 { offSince = fix.timeMs }
    }
}

// MARK: - plan mode (kept in this file so the Xcode project needs no regenerating)

/// Plan mode: the planner's job, on the phone, offline.
///
/// PLAN asks for a start and an end by name; stops are added by search (VIA) or a long press; STOPS reorders them, closes a
/// loop or reverses the ride; a long press on the chosen route adds a note or a danger; a tap on one of the router's turns
/// rewords it. Everything lands in `editCues`, which `planned()` lays onto whichever route is chosen, and SAVE writes the lot
/// as a roadbook GPX that EDIT ROUTE opens again.
extension AppModel {
    /// PLAN from the menu: a plan in progress comes back up; otherwise the start is asked for, then the end.
    func startPlan() {
        if destination != nil && !candidates.isEmpty { switchMode(.compare); drawCandidates(); drawCues(); fitCandidates(); return }
        if route != nil { showToast("End the ride first (menu → END)"); return }
        follow = false
        var ask = SearchAsk(title: "Plan a ride · where does it start?")
        if lastFix != nil { ask.extra = ("FROM WHERE I AM", { [self] in planStart = nil; startName = ""; drawStart(); askEnd() }) }
        ask.onPick = { [self] c, label in setStartNamed(c, label); askEnd() }
        sheet = .search(ask)
    }

    func askEnd() {
        var ask = SearchAsk(title: "…and where does it end?")
        ask.extra = ("BACK TO THE START (LOOP)", { [self] in planLoopFromStart() })
        ask.onPick = { [self] c, label in routeTo(c, label) }
        // the first sheet is still coming down; the next one needs a beat
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { self.sheet = .search(ask) }
    }

    /// A loop needs somewhere to ride out to: the end is the start, and the first stop is asked for next.
    func planLoopFromStart() {
        guard let ps = planStart ?? lastFix.map({ CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }) else { showToast("Waiting for a GPS fix…"); return }
        if packs.packFor(ps.latitude, ps.longitude) == nil { showToast("The start is outside your maps", 6); return }
        if planStart == nil { planStart = ps; drawStart() }
        destination = ps; destName = startName.isEmpty ? "Start" : startName; vias = []; viaNames = []; editCues = []; compareModes = nil
        pin = nil; clearPinMarker(); showPinMarker(ps)
        var ask = SearchAsk(title: "A loop · where does it turn around?")
        ask.extra = ("PICK ON THE MAP", { [self] in showToast("Long-press the map where the ride turns around, then VIA", 6) })
        ask.onPick = { [self] c, label in addVia(c, label) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { self.sheet = .search(ask) }
    }

    func setStartNamed(_ c: CLLocationCoordinate2D, _ label: String) {
        planStart = c; startName = placeName(label); drawStart()
        follow = false
        map.ease(to: c, zoom: 12, bearing: 0, inset: .zero, animated: true)
    }

    /// Where the plan starts: the chosen start, else where the compare was routed from, else the phone.
    var planFrom: CLLocationCoordinate2D? {
        planStart ?? compareFrom.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) } ?? lastFix.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
    }

    /// The plan's points in ride order with their names: start, stops, end.
    func planPoints() -> [(CLLocationCoordinate2D, String)] {
        guard let from = planFrom else { return [] }
        var out: [(CLLocationCoordinate2D, String)] = [(from, startName)]
        for i in vias.indices { out.append((vias[i], viaNames.indices.contains(i) ? viaNames[i] : "")) }
        if let d = destination { out.append((d, destName)) }
        return out
    }

    func stopLetter(_ i: Int) -> String { String(UnicodeScalar(UInt8(65 + min(i, 25)))) }

    var planTitle: String {
        let pts = planPoints(); if pts.count < 2 { return destName }
        let first = pts[0].1.isEmpty ? (planStart == nil ? "Here" : "Start") : pts[0].1
        let mid = vias.indices.map { i -> String in let n = viaNames.indices.contains(i) ? viaNames[i] : ""; return n.isEmpty ? "stop \(stopLetter(i))" : n }
        let last = isLoop ? "back to start" : (pts[pts.count - 1].1.isEmpty ? "End" : pts[pts.count - 1].1)
        return ([first] + mid + [last]).joined(separator: " → ")
    }

    /// the plan shows its stops above the modes: only when it is more than "from here to there"
    var hasPlanTitle: Bool { planStart != nil || !vias.isEmpty || !startName.isEmpty }

    var isLoop: Bool {
        guard let d = destination, let from = planFrom else { return false }
        return Geo.shared.fastDistance(lat1: d.latitude, lon1: d.longitude, lat2: from.latitude, lon2: from.longitude) < 30
    }

    /// LOOP: the end becomes the last stop and the ride ends where it started. OPEN LOOP undoes that.
    func toggleLoop() {
        guard let d = destination, let from = planFrom else { return }
        if isLoop {
            if vias.isEmpty { showToast("An out-and-back needs a stop to turn around at — add one with VIA"); return }
            destination = vias.removeLast(); destName = viaNames.isEmpty ? "" : viaNames.removeLast()
        } else {
            vias.append(d); viaNames.append(destName)
            if planStart == nil { planStart = from; drawStart() }
            destination = from; destName = startName.isEmpty ? "Start" : startName
        }
        drawVias(); clearPinMarker(); if let nd = destination { showPinMarker(nd) }
        if let nd = destination { startCompare(startFix(), nd) }
    }

    /// REVERSE: ride it the other way round. Notes and dangers stay where they are; reworded turns are dropped (they would face the wrong way).
    func reversePlan() {
        guard let d = destination, let from = planFrom else { return }
        if !isLoop {
            planStart = d; let sn = startName; startName = destName
            destination = from; destName = sn.isEmpty ? "Start" : sn
        }
        vias.reverse(); viaNames.reverse()
        let dropped = editCues.filter { $0.turn != .note && $0.turn != .danger }.count
        editCues.removeAll { $0.turn != .note && $0.turn != .danger }
        drawStart(); drawVias(); clearPinMarker(); if let nd = destination { showPinMarker(nd) }
        if dropped > 0 { showToast("Reversed — \(dropped) reworded turn\(dropped > 1 ? "s" : "") dropped (they faced the other way)", 5) }
        if let nd = destination { startCompare(startFix(), nd) }
    }

    /// The stops sheet moved or removed stops: route again.
    func stopsChanged() {
        drawVias()
        if let d = destination { startCompare(startFix(), d) }
    }

    func moveStop(_ i: Int, by d: Int) {
        let j = i + d
        guard vias.indices.contains(i), vias.indices.contains(j) else { return }
        vias.swapAt(i, j); if viaNames.indices.contains(i) && viaNames.indices.contains(j) { viaNames.swapAt(i, j) }
    }

    func removeStop(_ i: Int) {
        guard vias.indices.contains(i) else { return }
        vias.remove(at: i); if viaNames.indices.contains(i) { viaNames.remove(at: i) }
    }

    /// ADD STOP from the stops sheet: a search whose pick becomes a via.
    func addStopBySearch() {
        var ask = SearchAsk(title: "Add a stop")
        ask.onPick = { [self] c, label in addVia(c, label) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { self.sheet = .search(ask) }
    }

    // ---- cues on a planned route

    /// The chosen route's point nearest a map point, if it is within 150 m.
    func snapToPlan(_ lat: Double, _ lon: Double) -> (Double, Double)? {
        guard let r = candidate(profile) else { return nil }
        let out = KotlinDoubleArray(size: 4); var best = Double.greatestFiniteMagnitude; var bLat = lat, bLon = lon
        let n = Int(r.size)
        if n >= 2 {
            for i in 0..<(n - 1) {
                let k = Int32(i)
                if abs(r.lats.get(index: k) - lat) > 0.02 || abs(r.lons.get(index: k) - lon) > 0.02 { continue }
                Geo.shared.projectOnSegment(pLat: lat, pLon: lon, aLat: r.lats.get(index: k), aLon: r.lons.get(index: k), bLat: r.lats.get(index: k + 1), bLon: r.lons.get(index: k + 1), out: out)
                if out.get(index: 1) < best { best = out.get(index: 1); bLat = out.get(index: 2); bLon = out.get(index: 3) }
            }
        }
        return best <= 150 ? (bLat, bLon) : nil
    }

    /// Long press on the chosen route in plan mode: a note or a danger there (an arrow is allowed too).
    func addPlanCue(at lat: Double, _ lon: Double) {
        guard let s = snapToPlan(lat, lon) else { return }
        sheet = .cue(lat: s.0, lon: s.1, existing: Cue(lat: s.0, lon: s.1, turn: .note, note: "", level: 0))
        planCueTemplate = true
    }

    func editPlanCue(_ c: Cue) { planCueTemplate = false; sheet = .cue(lat: c.lat, lon: c.lon, existing: c) }

    /// Tap on one of the router's turns: say it in your own words (the arrow can change too).
    func rewordTurn(_ idx: Int) {
        guard let r = candidate(profile), idx < r.maneuverList.count else { return }
        let m = r.maneuverList[idx]
        let c = r.coordinate(ofVertex: m.vertex)
        sheet = .cue(lat: c.latitude, lon: c.longitude, existing: Cue(lat: c.latitude, lon: c.longitude, turn: m.turn, note: "", level: 0))
        planCueTemplate = true
    }

    /// The cue sheet saved while planning: a new cue (from a template) or a changed one.
    func planCueSaved(_ c: Cue, replacing old: Cue?) {
        if let o = old, !planCueTemplate, let i = editCues.firstIndex(where: { $0 === o }) { editCues[i] = c }
        else {
            // rewording with nothing to say changes nothing
            if let o = old, planCueTemplate, c.turn == o.turn, c.note.isEmpty, c.turn != .note, c.turn != .danger { planCueTemplate = false; return }
            editCues.append(c); haptic(1); showToast(c.turn.spoken + (c.note.isEmpty ? "" : ": " + c.note))
        }
        planCueTemplate = false
        drawCues(); drawTurns()
    }

    func planCueDeleted(_ c: Cue) {
        if planCueTemplate { planCueTemplate = false; return }
        editCues.removeAll { $0 === c }; drawCues(); drawTurns(); showToast("Cue deleted")
    }
}
