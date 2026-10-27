import CoreLocation
import Foundation
import FoldCore
import UIKit

extension AppModel {
    // ---------------------------------------------------------------- packs / gpx

    func importPacks(_ urls: [URL]) {
        progress = ProgressState(title: urls.count == 1 ? "Importing map pack" : "Importing \(urls.count) map packs", message: "Starting…", cancel: nil)
        worker.async { [self] in
            var ok = 0; var errors: [String] = []; var last: Pack?
            for (i, url) in urls.enumerated() {
                let prefix = urls.count > 1 ? "\(i + 1)/\(urls.count)  " : ""
                do {
                    // a picked file is copied into a private spot first (the picker's grant is short-lived); a file of our own is used in place
                    let own = url.path.hasPrefix(FileManager.default.temporaryDirectory.path) || url.path.hasPrefix(Files.documents.path)
                    let local = own ? url : try Files.stage(url)
                    last = try packs.importPack(local) { msg in DispatchQueue.main.async { self.progress?.message = prefix + msg } }
                    if local != url { try? FileManager.default.removeItem(at: local) }
                    ok += 1
                } catch { errors.append(error.text) }
            }
            DispatchQueue.main.async {
                self.progress = nil
                if ok > 0 {
                    self.showToast(urls.count == 1 ? "Imported \(last?.name ?? "pack")" : "Imported \(ok) packs" + (errors.isEmpty ? "" : ", \(errors.count) failed"))
                    self.onPacksChanged()
                }
                if !errors.isEmpty && ok == 0 { self.showToast("Import failed: \(errors[0])", 6) } else if let e = errors.first { self.showToast(e, 6) }
            }
        }
    }

    func deletePack(_ p: Pack) {
        worker.async { [self] in packs.delete(p); DispatchQueue.main.async { self.showToast("Removed \(p.name)"); self.onPacksChanged() } }
    }

    func loadGpx(_ url: URL) {
        worker.async { [self] in
            let result: Result<GpxTrack, Error> = Result {
                let text = try Files.readText(url)
                guard let tr = try Gpx.shared.parse(text: text) else { throw Fail("No track or route points found") }
                if tr.lats.size > 2_000_000 { throw Fail("Track has too many points (\(tr.lats.size))") }
                if tr.cues.count > 20_000 { throw Fail("Track has too many cues (\(tr.cues.count))") }
                return tr
            }
            DispatchQueue.main.async {
                switch result {
                case .success(let tr): self.onGpxLoaded(tr, url)
                case .failure(let e): self.showToast("Track failed: \(e.text)")
                }
            }
        }
    }

    /// A GPX with route points is re-routed on this phone's maps; a bare track is followed as a line.
    func onGpxLoaded(_ tr: GpxTrack, _ url: URL? = nil) {
        if tr.hasTrack { sheet = .trackLoaded(tr, url); return }
        if tr.hasRoute { rerouteGpx(tr) } else { followTrack(tr, url) }
    }

    func followTrack(_ tr: GpxTrack, _ url: URL?) {
        trackLats = tr.lats.swift; trackLons = tr.lons.swift; trackName = tr.name; trackURL = url; trackCues = tr.cueList
        do {
            let r = try Route.companion.fromTrack(lats: tr.lats, lons: tr.lons, name: tr.name, cues: tr.cues)
            destination = nil; startNavigation(r, track: true); drawCues()
            showToast("Following track (\(Units.distance(r.length)))" + (tr.cues.count > 0 ? " · \(tr.cues.count) cues" : ""))
        } catch { showToast("Track failed: \(error.text)") }
    }

    /// EDIT ROUTE: a file planned again on this phone's maps so its vias can be changed. A saved route brings its
    /// own start, vias and end; a bare track (a library roadbook, anyone's GPX) gets vias placed where its line
    /// bends, so the plan follows the same roads. The file's authored cues are laid back onto the plan. The compare
    /// is limited to the file's own mode, so the line comes back the same; MODES opens it up again.
    func rerouteGpx(_ tr: GpxTrack) {
        let pts: [[Double]]
        if tr.hasRoute { pts = (0..<tr.rteLats.size).map { [tr.rteLats.get(index: $0), tr.rteLons.get(index: $0)] } }
        else { pts = Route.companion.viasForTrack(lats: tr.lats, lons: tr.lons, maxVias: 40).map { $0.swift } }
        guard pts.count >= 2 else { followTrack(tr, nil); return }
        let start = CLLocationCoordinate2D(latitude: pts[0][0], longitude: pts[0][1]), dest = CLLocationCoordinate2D(latitude: pts[pts.count - 1][0], longitude: pts[pts.count - 1][1])
        if packs.packFor(start.latitude, start.longitude) == nil || packs.packFor(dest.latitude, dest.longitude) == nil {
            if tr.hasTrack { showToast("Route is outside your maps — following its line instead", 5); followTrack(tr, nil) } else { showToast("Route is outside your maps", 6) }
            return
        }
        if let m = tr.mode, let p = Profiles.byName(m) { profile = p; prefs.profile = p }
        compareModes = [profile]
        let endName = tr.hasRoute ? tr.rteName(i: Int32(pts.count - 1)) : ""
        destination = dest; destName = endName.isEmpty ? tr.name : endName
        vias = pts.count > 2 ? pts[1..<(pts.count - 1)].map { CLLocationCoordinate2D(latitude: $0[0], longitude: $0[1]) } : []
        viaNames = vias.indices.map { i in let n = tr.hasRoute ? tr.rteName(i: Int32(i + 1)) : ""; return (n.hasPrefix("Via ") || n.hasPrefix("Stop ")) ? "" : n }
        startName = tr.hasRoute ? (tr.rteName(i: 0) == "Start" ? "" : tr.rteName(i: 0)) : ""
        drawVias()
        editCues = tr.cueList
        if mode == .nav { endNavigationKeepingPlan() }
        // start from the GPS fix if you are near the route's start, otherwise plan from the route's own start
        let nearStart = lastFix.map { Geo.shared.fastDistance(lat1: $0.lat, lon1: $0.lon, lat2: start.latitude, lon2: start.longitude) < 2000 } ?? false
        if !nearStart { planStart = start; drawStart() } else { planStart = nil }
        showPinMarker(dest)
        showToast("Planning \"\(tr.name)\" on your maps (\(profile.label))… add a via with a long press, tap a via to remove it", 6)
        startCompare(startFix(), dest)
    }

    // ---------------------------------------------------------------- Roadbook: cues, the editor, saving

    func snapToTrack(_ lat: Double, _ lon: Double) -> (Double, Double, Double)? {
        guard let la = trackLats, let lo = trackLons, la.count >= 2 else { return nil }
        let out = KotlinDoubleArray(size: 4); var best = Double.greatestFiniteMagnitude; var bLat = lat, bLon = lon
        for i in 0..<(la.count - 1) {
            if abs(la[i] - lat) > 0.02 || abs(lo[i] - lon) > 0.02 { continue }
            Geo.shared.projectOnSegment(pLat: lat, pLon: lon, aLat: la[i], aLon: lo[i], bLat: la[i + 1], bLon: lo[i + 1], out: out)
            if out.get(index: 1) < best { best = out.get(index: 1); bLat = out.get(index: 2); bLon = out.get(index: 3) }
        }
        return best <= 150 ? (bLat, bLon, best) : nil
    }

    /// New cue at a point: on the loaded track (snapped) and/or in the recording.
    func addCue(at lat: Double, _ lon: Double, snap: Bool) {
        var cLat = lat, cLon = lon
        if trackLats != nil && snap { guard let s = snapToTrack(lat, lon) else { showToast("That is more than 150 m from the track"); return }; cLat = s.0; cLon = s.1 }
        sheet = .cue(lat: cLat, lon: cLon, existing: nil)
    }

    /// The cue sheet saved a new cue.
    func cueSaved(_ c: Cue, replacing old: Cue?) {
        if mode == .compare { planCueSaved(c, replacing: old); return }
        if let o = old {
            if let i = trackCues.firstIndex(where: { $0 === o }) { trackCues[i] = c }
            drawCues(); if mode == .nav { rebuildTrackRoute() }
            return
        }
        if trackLats != nil { trackCues.append(c); trackCues.sort { trackOffset($0) < trackOffset($1) }; drawCues(); if mode == .nav { rebuildTrackRoute() } }
        if recorder.running { recorder.addCue(c) }
        haptic(1); showToast(c.turn.spoken + (c.note.isEmpty ? "" : ": " + c.note))
    }

    func cueDeleted(_ c: Cue) {
        if mode == .compare { planCueDeleted(c); return }
        trackCues.removeAll { $0 === c }; drawCues(); if mode == .nav { rebuildTrackRoute() }; showToast("Cue deleted")
    }

    /// CUE button: where you are now. While following a track the cue snaps to it; while recording it goes on the recording.
    func addCueHere() {
        let lat: Double, lon: Double
        if let f = lastFix { lat = f.lat; lon = f.lon } else if !recorder.lastLat.isNaN { lat = recorder.lastLat; lon = recorder.lastLon } else { showToast("Waiting for GPS…"); return }
        addCue(at: lat, lon, snap: trackLats != nil)
    }

    func editCue(_ c: Cue) { sheet = .cue(lat: c.lat, lon: c.lon, existing: c) }

    func trackOffset(_ c: Cue) -> Double {
        guard let la = trackLats, let lo = trackLons else { return 0 }
        var best = Double.greatestFiniteMagnitude; var bi = 0
        for i in la.indices { let d = Geo.shared.fastDistance(lat1: la[i], lon1: lo[i], lat2: c.lat, lon2: c.lon); if d < best { best = d; bi = i } }
        return Double(bi)
    }

    /// Cues changed while following: rebuild the route in place, keeping position.
    func rebuildTrackRoute() {
        guard let la = trackLats, let lo = trackLons else { return }
        guard let r = try? Route.companion.fromTrack(lats: KotlinDoubleArray.of(la), lons: KotlinDoubleArray.of(lo), name: trackName, cues: trackCues) else { return }
        route = r
        let old = ride.controller
        let c = NavController(route: r, isTrack: true, destName: trackName, profileName: profile.name, sink: ride)
        if let o = old, let f = o.lastFix { c.update(fix: f); let v = o.follower.view(nowMs: nowMs()); if v.onRoute { c.follower.resumeAt(alongMeters: v.along) } }
        map.drawRoute(r, color: Theme.track)
        ride.start(c)
    }

    func openEditor(_ tr: GpxTrack, _ url: URL?) {
        if mode == .nav { endNavigation() }
        trackLats = tr.lats.swift; trackLons = tr.lons.swift; trackName = tr.name; trackURL = url; trackCues = tr.cueList
        guard let r = try? Route.companion.fromTrack(lats: tr.lats, lons: tr.lons, name: tr.name, cues: tr.cues) else { showToast("Track failed"); return }
        route = r; isTrack = true; map.drawRoute(r, color: Theme.track); drawCues()
        follow = false
        switchMode(.edit)
        updateEditLabel()
        let coords = r.coords; let step = max(1, coords.count / 500)
        map.fit(stride(from: 0, to: coords.count, by: step).map { coords[$0] } + [coords[coords.count - 1]], insets: UIEdgeInsets(top: 120, left: 40, bottom: 220, right: 40), animated: true)
    }

    func updateEditLabel() {
        let len = route.map { Units.distance($0.length) } ?? ""
        editLabel = "\(trackName) · \(len) · \(trackCues.count) cues\nLong-press on the line to add a cue. Tap a cue to edit it."
    }

    func leaveEdit() {
        route = nil; isTrack = false
        map.clear("route-src")
        trackLats = nil; trackLons = nil; trackURL = nil; trackCues = []; drawCues()
        switchMode(.idle)
    }

    func followEditedTrack() {
        guard let la = trackLats, let lo = trackLons else { return }
        let tr = GpxTrack(name: trackName, lats: KotlinDoubleArray.of(la), lons: KotlinDoubleArray.of(lo), rteLats: KotlinDoubleArray(size: 0), rteLons: KotlinDoubleArray(size: 0), mode: nil, cues: trackCues, rteNames: [])
        let url = trackURL
        switchMode(.idle)
        followTrack(tr, url)
    }

    func cueListLines() -> [(String, String)] {
        guard let la = trackLats, let lo = trackLons, let r = try? Route.companion.fromTrack(lats: KotlinDoubleArray.of(la), lons: KotlinDoubleArray.of(lo), name: trackName, cues: trackCues) else { return [] }
        return r.maneuverList.filter { $0.turn != .depart }.map { (Units.distance($0.at), $0.phrase()) }
    }

    func trackGpx() -> String {
        guard let la = trackLats, let lo = trackLons else { return "" }
        return Gpx.shared.writeTrack(name: trackName, lats: KotlinDoubleArray.of(la), lons: KotlinDoubleArray.of(lo), times: nil, cues: trackCues.sorted { trackOffset($0) < trackOffset($1) })
    }

    /// Writes the track with its cues back over the file it came from (when it is one of ours in Documents), or as a new file.
    func saveTrack() {
        guard trackLats != nil else { return }
        let own = trackURL.map { $0.path.hasPrefix(Files.documents.path) } ?? false
        var btns: [Dialog.Btn] = []
        if own, let u = trackURL { btns.append(Dialog.Btn(label: "SAVE OVER FILE") { [self] in overwriteTrack(u) }) }
        btns.append(Dialog.Btn(label: own ? "SAVE AS…" : "SAVE TO FILES") { [self] in askNameAndWrite(trackName, { [self] in trackGpx() }) })
        btns.append(Dialog.Btn(label: "Cancel", role: .cancel) {})
        ask("Save \"\(trackName)\"", "\(trackCues.count) cues. The GPX keeps the line and adds the cues as waypoints, so any app still opens it.", btns)
    }

    /// Overwrites a roadbook in place. The original is kept in a backup before the file is touched, and that backup
    /// is never replaced by anything that has not been verified: a failed or torn write restores the original.
    func overwriteTrack(_ url: URL) {
        let fm = FileManager.default
        let key = String(url.path.hashValue, radix: 16)
        let bdir = Files.support.appendingPathComponent("backup", isDirectory: true); try? fm.createDirectory(at: bdir, withIntermediateDirectories: true)
        let backup = bdir.appendingPathComponent("\(key).gpx")
        do {
            let bytes = Data(trackGpx().utf8)
            guard let current = try? Data(contentsOf: url) else { throw Fail("Could not read the file") }
            // a backup already there is a verified original from an earlier attempt; the current file is backed up only if it parses
            let parsed: GpxTrack? = (try? Gpx.shared.parse(text: String(decoding: current, as: UTF8.self))) ?? nil
            let currentValid = (parsed?.lats.size ?? 0) >= 2
            if currentValid || !fm.fileExists(atPath: backup.path) { try current.write(to: backup, options: .atomic) }
            let old = try Data(contentsOf: backup)
            do {
                try bytes.write(to: url, options: .atomic)
                guard let back = try? Data(contentsOf: url), back == bytes else { throw Fail("File is incomplete (read back failed)") }
            } catch {
                var restored = false
                if (try? old.write(to: url, options: .atomic)) != nil, let back = try? Data(contentsOf: url), back == old { restored = true }
                if restored { throw Fail("\(error.text); the previous version was restored") }
                ask("Save failed", "\(error.text); the previous version could not be put back. The original is kept safe; save it as a new file?",
                    [Dialog.Btn(label: "SAVE ORIGINAL AS…") { [self] in askNameAndWrite((trackName.isEmpty ? "Roadbook" : trackName) + " (original)", { String(decoding: (try? Data(contentsOf: backup)) ?? Data(), as: UTF8.self) }) },
                     Dialog.Btn(label: "LATER", role: .cancel) {}])
                return
            }
            try? fm.removeItem(at: backup)
            showToast("Saved")
        } catch { showToast("Save failed: \(error.text)", 9) }
    }

    func askNameAndWrite(_ suggest: String, _ body: @escaping () -> String, onDone: @escaping (Bool) -> Void = { _ in }) {
        sheet = .fileName(suggest: suggest, body: body, done: onDone)
    }

    /// Writes a new file into Documents. Returns true only when the whole file was written.
    @discardableResult
    func writeToFolder(_ fileName: String, _ text: String) -> Bool {
        do { let u = try Files.write(fileName, text); lastWritten = u; showToast("Saved \(u.lastPathComponent) — it is under TRACK", 5); return true }
        catch { showToast("Save failed: \(error.text)", 6); return false }
    }

    // ---------------------------------------------------------------- pre-run recording

    func startRecording() {
        if recorder.running { return }
        // an earlier recording that was never saved is offered again, not thrown away
        if recorder.hasData { offerSaveRecording("Unsaved recording"); return }
        if lastFix == nil { showToast("Recording starts with the first GPS fix") }
        recorder.start(); haptic(2); showToast("Recording. Stop at a turn or a hazard and tap CUE.", 5)
        updateRecStrip()
    }

    func stopRecording() {
        recorder.stop()
        updateRecStrip()
        if !recorder.hasData { recorder.discard(); showToast("Nothing recorded"); return }
        offerSaveRecording("Recording stopped")
    }

    func offerRecovery() { offerSaveRecording("An unfinished recording was found") }

    func offerSaveRecording(_ title: String) {
        ask(title, Units.distance(recorder.distance) + " · \(recorder.cues.count) cues",
            [Dialog.Btn(label: "SAVE") { [self] in
                // the recovery file lives until the save is on disk; cancel or a failed write keeps it
                askNameAndWrite(recorder.defaultName(), { [self] in recorder.gpx(recorder.defaultName()) }) { [self] ok in if ok { recorder.discard(); updateRecStrip() } else { showToast("Recording kept — tap ● to save it later", 5) } }
            },
             Dialog.Btn(label: "SHARE…") { [self] in Files.shareText(recorder.defaultName().fileSafe + ".gpx", recorder.gpx(recorder.defaultName())); showToast("Recording kept until you SAVE or DISCARD it", 5) },
             Dialog.Btn(label: "DISCARD", role: .destructive) { [self] in ask("Discard this recording?", "There is no undo.", [Dialog.Btn(label: "Discard", role: .destructive) { [self] in recorder.discard(); updateRecStrip() }, Dialog.Btn(label: "Cancel", role: .cancel) {}]) }],
            cancelable: false)
    }

    // ---------------------------------------------------------------- the library

    /// A roadbook fetched by code: saved into Documents, then offered like any track.
    func onFetchedGpx(_ name: String, _ text: String) {
        guard let tr = try? Gpx.shared.parse(text: text), tr.lats.size >= 2 else { showToast("The fetched file has no track"); return }
        let file = (name.fileSafe.isEmpty ? "Roadbook" : name.fileSafe) + ".gpx"
        let ok = writeToFolder(file, text)
        onGpxLoaded(tr, ok ? lastWritten : nil)
    }

    func showLibrary(_ on: Bool) {
        libraryOn = on
        if !on { libraryItems = []; drawLibrary(); showToast("Roadbooks hidden"); return }
        fetchLibrary(announce: true)
    }

    func fetchLibrary(announce: Bool) {
        let b = map.visibleBounds
        libraryFetchedAt = Date()
        Task {
            do {
                let list = try await Library.browse(minLon: b.sw.longitude, minLat: b.sw.latitude, maxLon: b.ne.longitude, maxLat: b.ne.latitude)
                await MainActor.run {
                    guard self.libraryOn else { return }
                    self.libraryItems = list; self.drawLibrary()
                    if announce { self.showToast(list.isEmpty ? "No roadbooks published for this part of the map yet. Zoom out, or be the first: TRACK → PUBLISH." : "\(list.count) roadbooks here — tap one. Hide them from the menu.", 5) }
                }
            } catch { await MainActor.run { if announce { self.showToast("Library not reachable: \(error.text)", 6) } } }
        }
    }

    func drawLibrary() {
        map.set("lib-src", MapController.collection(libraryItems.map { it in MapController.lineFeature(it.line.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) }, ["id": it.id, "name": it.name]) }))
    }

    func withLibraryGpx(_ item: LibraryItem, _ then: @escaping (GpxTrack, String) -> Void) {
        progress = ProgressState(title: "Fetching \(item.name)…", message: "", cancel: nil)
        Task {
            do {
                let text = try await Library.download(item)
                await MainActor.run {
                    self.progress = nil
                    if let tr = try? Gpx.shared.parse(text: text), tr.lats.size >= 2 { then(tr, text) } else { self.showToast("That roadbook has no track") }
                }
            } catch { await MainActor.run { self.progress = nil; self.showToast("Could not fetch it: \(error.text)", 6) } }
        }
    }
}
