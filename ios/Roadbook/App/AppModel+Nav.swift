import CoreLocation
import Foundation
import FoldCore
import UIKit

extension AppModel {
    func startNavigation(_ r: Route, track: Bool, resumed: NavController? = nil) {
        route = r; isTrack = track
        // the ride itself — route, follower, what has been said — belongs to the session, so it outlives this screen
        let c = resumed ?? NavController(route: r, isTrack: track, destName: track ? trackName : destName, profileName: profile.name, sink: ride)
        if resumed == nil, let f = lastFix { c.update(fix: f) }
        c.batterySaver = prefs.batterySaver; c.worstSpeed = profile.bike ? 10.0 : 30.0
        Location.shared.setActivity(bike: profile.bike)
        ride.start(c)
        if prefs.batterySaver { applyBatteryFlags(true); batteryHint() }
        map.drawRoute(r, color: track ? Theme.track : Profiles.color(profile))
        drawVias()
        clearPinMarker()
        if !track, let d = destination { showPinMarker(d) }
        follow = true
        offSince = 0
        zoomMode = .turn; manualZoom = -1; overviewDone = false
        switchMode(.nav)
        UIApplication.shared.isIdleTimerDisabled = true
        if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) }
        if !track { trackLats = nil; trackLons = nil; trackURL = nil; trackCues = []; drawCues() }
        if track {
            // show the whole track first so the rider knows where it starts, then settle into the turn view
            zoomMode = .overview; overviewDone = false
            if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [self] in if mode == .nav && isTrack && zoomMode == .overview { zoomMode = .turn; if let f = lastFix { moveCamera(f.lat, f.lon, animate: true) } } }
        }
        startHudTimer()
    }

    /// A ride a dead process left behind: offer to pick it up where it was.
    func offerResume() {
        guard let text = ride.leftover() else { return }
        guard let c = try? NavController.companion.restore(text: text, sink: ride) else { try? FileManager.default.removeItem(at: RideSession.checkpointFile); return }
        ask("Resume navigation?", "\(c.destName.isEmpty ? "A ride" : c.destName) was being followed when the app stopped. Pick it up where it was?",
            [Dialog.Btn(label: "RESUME") { [self] in
                let r = c.route; let n = r.size - 1
                destName = c.destName; destination = c.isTrack ? nil : r.coordinate(ofVertex: n)
                if c.isTrack {
                    trackLats = r.lats.swift; trackLons = r.lons.swift; trackName = c.destName; trackURL = nil
                    trackCues = r.maneuverList.filter { $0.turn != .depart && $0.turn != .arrive }.map { m in Cue(lat: r.lats.get(index: m.vertex), lon: r.lons.get(index: m.vertex), turn: m.turn, note: m.note, level: m.level) }
                    drawCues()
                }
                startNavigation(r, track: c.isTrack, resumed: c)
            },
             Dialog.Btn(label: "DISCARD", role: .destructive) { try? FileManager.default.removeItem(at: RideSession.checkpointFile) }], cancelable: false)
    }

    func endNavigation() {
        Speech.shared.stop()
        dim(false)
        ride.stop()
        routeGeneration += 1
        route = nil; destination = nil; pin = nil; isTrack = false; vias = []; destName = ""; candidates = []; compareGeneration += 1; planStart = nil; drawStart()
        map.clear("route-src"); drawVias()
        clearPinMarker()
        UIApplication.shared.isIdleTimerDisabled = false
        stopHudTimer()
        hud.offVisible = false
        trackLats = nil; trackLons = nil; trackURL = nil; trackCues = []; drawCues()
        switchMode(.idle)
        if let f = lastFix { map.updatePuck(f.lat, f.lon, heading: heading); if follow { moveCamera(f.lat, f.lon, animate: true) } }
    }

    // ---- battery saver: the screen dims to a dark HUD and may lock; the lock screen carries the next cue and lights for it;
    // between cues the GPS eases and the timer slows

    func batteryChanged() {
        let level = UIDevice.current.batteryLevel; let state = UIDevice.current.batteryState
        if level >= 0 && level <= 0.15 && state != .charging && state != .full && mode == .nav && !prefs.batterySaver {
            setBatterySaver(true); showToast("Battery at \(Int(level * 100)) % — battery saver on", 6)
        }
    }

    func setBatterySaver(_ on: Bool) {
        prefs.batterySaver = on
        ride.controller?.batterySaver = on; ride.controller?.worstSpeed = profile.bike ? 10.0 : 30.0
        applyBatteryFlags(on)
        if on { if mode == .nav { dim(true); batteryHint() } else { showToast("Battery saver: while navigating, the screen dims to the next cue and may lock; the lock screen lights for each turn; the GPS eases between cues", 7) } }
        else { dim(false); if mode == .nav { UIApplication.shared.isIdleTimerDisabled = true } }
    }

    func batteryHint() {
        if hinted { return }; hinted = true
        showToast("Battery saver on. No signal out here? Airplane mode with Bluetooth on roughly doubles the battery — the maps and the voice do not need one.", 9)
    }

    func applyBatteryFlags(_ on: Bool) {}

    /// The dark HUD: the map black, the next cue in grey at minimum brightness; the idle timer re-enabled so the
    /// phone's own timeout can lock the screen (the Live Activity then shows the cue on the lock screen).
    func dim(_ on: Bool) {
        if on {
            dimmed = true; hud.visible = false
            UIApplication.shared.isIdleTimerDisabled = false
            savedBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 0.01
        } else {
            if dimmed { UIScreen.main.brightness = savedBrightness > 0.05 ? savedBrightness : 0.5 }
            dimmed = false
        }
    }
    /// The rider tapped the dark screen: show the map for a while, then go dark again if nothing is near.
    func wakeFromDim() {
        dim(false); UIApplication.shared.isIdleTimerDisabled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [self] in
            if prefs.batterySaver && mode == .nav && (ride.controller?.view(nowMs: nowMs()).progress ?? 1) == 0 { dim(true) }
        }
    }

    /// A cue is near (or the line is lost): light the screen for it; let it dim again after.
    func onAttention(_ on: Bool) {
        guard prefs.batterySaver && mode == .nav else { return }
        if on { dim(false); UIApplication.shared.isIdleTimerDisabled = true } else { dim(true) }
    }
}
