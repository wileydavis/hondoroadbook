import ActivityKit
import CoreLocation
import Foundation
import FoldCore
import UIKit

/// Owns a ride. The NavController (the route, the follower, what has been said and chimed) lives here, fed by
/// the phone's GPS and ticked by a timer, with the audio session and background location keeping the process
/// alive — so the screen can go dark and the voice and the pips carry on. Every half minute the session is
/// checkpointed to a private file; after a crash the app offers to resume from it. The screen only observes.
/// A port of NavService; the Live Activity stands in for Android's notification.
final class RideSession: NSObject, NavSink {
    static let shared = RideSession()

    private(set) var controller: NavController?
    private(set) var running = false
    private(set) var lastFailure: String?
    /// observers, called on the main thread
    var onFix: ((NavController) -> Void)?
    var onStopRequested: (() -> Void)?
    var onAttention: ((Bool) -> Void)?

    private var timer: Timer?
    private var sub: UUID?
    private var lastNotify: Int64 = 0
    private var lastCheckpoint: Int64 = 0
    private var lastDemand: Int64 = 0
    private var awake = true
    private var fixInterval: Int64 = 1000
    private var activity: Activity<RideActivityAttributes>?
    private var title = "Navigating"
    private var lastState: RideActivityAttributes.ContentState?

    static var checkpointFile: URL {
        let d = Files.support.appendingPathComponent("nav", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("active.nav")
    }

    /// Takes ownership of a session and starts guiding it.
    func start(_ c: NavController) {
        if let old = controller, old !== c { old.end() }
        controller = c; lastFailure = nil
        title = c.destName.isEmpty ? "Navigating" : (c.isTrack ? "Following " : "To ") + c.destName
        Tone.configureSession()   // the category only; the session goes active per sound, so music is not held down all ride
        if sub == nil { sub = Location.shared.subscribe { [weak self] l in self?.fix(l) } }
        Location.shared.start()
        running = true
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }
        timer?.tolerance = 0.05
        startActivity()
    }

    /// Ends the ride: the session is closed, the checkpoint deleted (a deliberate END is not something to resume).
    func stop() {
        controller?.end(); controller = nil
        running = false
        timer?.invalidate(); timer = nil
        if let s = sub { Location.shared.unsubscribe(s); sub = nil }
        Location.shared.tuneDefault()
        try? FileManager.default.removeItem(at: RideSession.checkpointFile)
        endActivity()
    }

    /// A ride left by a dead process, if any: the checkpoint's text, for the app to offer.
    func leftover() -> String? {
        guard controller == nil else { return nil }
        return try? String(contentsOf: RideSession.checkpointFile, encoding: .utf8)
    }

    private func fix(_ l: CLLocation) {
        guard let c = controller else { return }
        c.update(fix: Fix(l))
        if !awake { tick() }
        onFix?(c)
    }

    private func tick() {
        guard let c = controller else { return }
        c.sound = Tone.shared.enabled; c.speechReady = Speech.shared.enabled && Speech.shared.ready
        let now = nowMs()
        guard let v = try? c.tick(nowMs: now) else { return }
        if now - lastNotify > 5000 { lastNotify = now; updateActivity(v, c) }
        if now - lastCheckpoint > 30_000 { lastCheckpoint = now; writeCheckpoint(c, now) }
        if now - lastDemand > 2000 { lastDemand = now; follow(c.demand(nowMs: now), c) }
    }

    /// Gives the ride what it asks for and no more: on iOS the GPS accuracy and distance filter, and a slower timer.
    private func follow(_ d: Demand, _ c: NavController) {
        if d.fixIntervalMs != fixInterval || d.awake != awake {
            fixInterval = d.fixIntervalMs; awake = d.awake
            Location.shared.tune(fixIntervalMs: d.fixIntervalMs, awake: d.awake, worstSpeed: c.worstSpeed)
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: awake ? 0.25 : 1.0, repeats: true) { [weak self] _ in self?.tick() }
            timer?.tolerance = awake ? 0.05 : 0.3
        }
    }

    private func writeCheckpoint(_ c: NavController, _ now: Int64) {
        let text = c.checkpoint(nowMs: now)
        try? text.write(to: RideSession.checkpointFile, atomically: true, encoding: .utf8)
    }

    // ---- NavSink: the phone's sounds and voice, driven by the controller's decisions ------------------------

    func tone(kind: ToneKind, level: Int32, progress: Double) {
        let t = Tone.shared
        switch kind {
        case .pip: t.pip(progress)
        case .turn: t.turn()
        case .offRoute: t.offRoute()
        case .back: t.back()
        case .arrive: t.arrive()
        case .danger: t.danger(Int(level))
        case .note: t.note()
        default: break
        }
    }
    func say(phrase: String) { Speech.shared.say(phrase) }
    func prepare(phrase: String) { Speech.shared.prepare(phrase) }
    func changed(c: NavController) {}
    /// a cue is near (or the line is lost): the screen's hook, and a lock-screen alert when the saver has dimmed it
    func attention(on: Bool) {
        onAttention?(on)
        if on, let c = controller { lastNotify = nowMs(); updateActivity(c.view(nowMs: nowMs()), c, alert: true) }
    }

    // ---- the Live Activity: the next cue on the lock screen and in the Dynamic Island --------------------------

    static func state(_ v: NavView, _ c: NavController) -> RideActivityAttributes.ContentState {
        let m = v.next
        let distance: String, instruction: String
        if v.arrived { distance = "ARRIVED"; instruction = c.destName }
        else if v.fixState == FixState.none || v.fixState == FixState.stale { distance = "NO GPS"; instruction = "Waiting for GPS" }
        else if !v.onRoute { distance = Units.cue(v.offDist); instruction = "OFF TRACK" }
        else if let m = m { distance = Units.cue(v.distToNext); instruction = m.turn == .arrive ? "Destination" : v.phrase() }
        else { distance = Units.distance(v.remaining); instruction = "to go" }
        return RideActivityAttributes.ContentState(distance: distance, instruction: instruction, turn: (v.hazard ?? m)?.turn.name ?? "STRAIGHT",
                                                   remaining: Units.distance(v.remaining) + " · " + Units.time(v.etaSeconds), arrived: v.arrived, offRoute: !v.onRoute)
    }

    private func startActivity() {
        guard ActivityAuthorizationInfo().areActivitiesEnabled, let c = controller else { return }
        endActivity()
        let s = RideSession.state(c.view(nowMs: nowMs()), c)
        activity = try? Activity.request(attributes: RideActivityAttributes(title: title), content: .init(state: s, staleDate: nil), pushType: nil)
        lastState = s
    }

    private func updateActivity(_ v: NavView, _ c: NavController, alert: Bool = false) {
        guard let a = activity else { return }
        let s = RideSession.state(v, c)
        if s == lastState && !alert { return }
        lastState = s
        Task {
            if alert {
                // the lock screen lights up and shows the cue: in battery saver this is how the rider is told to look
                await a.update(.init(state: s, staleDate: nil), alertConfiguration: AlertConfiguration(title: LocalizedStringResource(stringLiteral: s.distance), body: LocalizedStringResource(stringLiteral: s.instruction), sound: .default))
            } else {
                await a.update(.init(state: s, staleDate: nil))
            }
        }
    }

    private func endActivity() {
        guard let a = activity else { return }
        activity = nil
        Task { await a.end(nil, dismissalPolicy: .immediate) }
    }
}
