import CoreLocation
import Foundation
import FoldCore

/// A pre-run: the rider drives the course, the phone writes the line, and every stop to add a cue or a note
/// lands on it. The result is a GPX track with authored cues, ready to follow at speed. Background location
/// keeps the fixes coming with the screen off; a recovery file is rewritten every minute so a crash or a dead
/// battery loses at most a minute.
final class Recorder: ObservableObject {
    static let shared = Recorder()
    private(set) var lats: [Double] = [], lons: [Double] = [], times: [Int64] = []
    private(set) var cues: [Cue] = []
    @Published private(set) var running = false
    @Published private(set) var distance = 0.0
    @Published private(set) var cueCount = 0
    private(set) var startedAt: Date?
    /// last recorded position, for placing cues
    private(set) var lastLat = Double.nan, lastLon = Double.nan
    private(set) var lastFailure: String?
    private var dirty = false
    private var lastFlush = Date.distantPast
    private var sub: UUID?
    var onChange: (() -> Void)?

    static var recoveryFile: URL {
        let d = Files.support.appendingPathComponent("rec", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("recording.gpx")
    }

    func start() {
        if running { return }
        clear(); lastFailure = nil; running = true; startedAt = Date()
        Location.shared.start()
        if sub == nil { sub = Location.shared.subscribe { [weak self] l in self?.addFix(l) } }
        onChange?()
    }

    func stop() {
        running = false
        if let s = sub { Location.shared.unsubscribe(s); sub = nil }
        flush(force: true)
        onChange?()
    }

    func discard() { clear(); try? FileManager.default.removeItem(at: Recorder.recoveryFile); onChange?() }

    private func clear() { lats = []; lons = []; times = []; cues = []; distance = 0; cueCount = 0; lastLat = .nan; lastLon = .nan; dirty = false }

    private func addFix(_ l: CLLocation) {
        guard running, l.horizontalAccuracy <= 60 else { return }
        let lat = l.coordinate.latitude, lon = l.coordinate.longitude, t = Int64(l.timestamp.timeIntervalSince1970 * 1000)
        if let la = lats.last, let lo = lons.last, let lt = times.last {
            let d = Geo.shared.fastDistance(lat1: la, lon1: lo, lat2: lat, lon2: lon)
            // a point every 5 m or every 10 s, whichever comes first; standing still writes nothing
            if d < 5.0 && t - lt < 10_000 { return }
            if d < 2.0 { return }
            distance += d
        }
        lats.append(lat); lons.append(lon); times.append(t); lastLat = lat; lastLon = lon; dirty = true
        flush()
        onChange?()
    }

    func addCue(_ c: Cue) { cues.append(c); cueCount = cues.count; dirty = true; flush(force: true); onChange?() }

    var elapsed: TimeInterval { startedAt.map { Date().timeIntervalSince($0) } ?? 0 }
    var hasData: Bool { lats.count >= 2 || !cues.isEmpty }

    func gpx(_ name: String) -> String { Gpx.shared.writeTrack(name: name, lats: KotlinDoubleArray.of(lats), lons: KotlinDoubleArray.of(lons), times: KotlinLongArray.of(times), cues: cues) }

    func defaultName() -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd HH.mm"
        return "Pre-run " + f.string(from: startedAt ?? Date())
    }

    /// Rewrites the recovery file if anything changed (at most once a minute unless forced).
    func flush(force: Bool = false) {
        guard dirty, force || Date().timeIntervalSince(lastFlush) > 60 else { return }
        if (try? gpx(defaultName()).write(to: Recorder.recoveryFile, atomically: true, encoding: .utf8)) != nil { dirty = false; lastFlush = Date() }
    }

    /// Loads an unfinished recording left by a crash. Returns true if there was one with data.
    func recover() -> Bool {
        let f = Recorder.recoveryFile
        guard !running, let text = try? String(contentsOf: f, encoding: .utf8) else { return false }
        guard let tr = try? Gpx.shared.parse(text: text) else { try? FileManager.default.removeItem(at: f); return false }
        clear()
        let la = tr.lats.swift, lo = tr.lons.swift
        for i in la.indices { lats.append(la[i]); lons.append(lo[i]); times.append(0); if i > 0 { distance += Geo.shared.fastDistance(lat1: la[i - 1], lon1: lo[i - 1], lat2: la[i], lon2: lo[i]) } }
        cues = tr.cueList; cueCount = cues.count
        startedAt = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
        dirty = true
        return hasData
    }
}
