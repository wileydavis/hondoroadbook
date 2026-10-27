import AVFoundation
import Foundation

/// The one sound Roadbook makes: short synthesized pips, no audio files. The same arithmetic as Tone.kt on
/// Android, rendered to PCM once and played through AVAudioPlayer. Played as navigation guidance, so they go
/// to a helmet headset or a car stereo over Bluetooth, ducking music, and keep playing with the screen off.
///
/// Approach cue: as the bar fills, pips rise in pitch (one octave over the approach) and come faster.
/// At the turn, a two-note chime. Off route: one low buzz. Arrival: three rising notes.
final class Tone {
    static let shared = Tone()
    private static let rate = 22050
    private var cache: [String: AVAudioPlayer] = [:]
    private let lock = NSLock()
    var enabled = false

    private init() {}

    /// Playback that ducks other audio and mixes with it, and keeps the session alive in the background.
    private static var configured = false
    static func configureSession() {
        let s = AVAudioSession.sharedInstance()
        // playback (plays with the silent switch on, through the media volume), ducking music while a cue plays;
        // if the ducking options are refused on some setup, plain playback is still better than silence
        do { try s.setCategory(.playback, mode: .default, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers]) }
        catch {
            NSLog("Roadbook: audio session options refused (%@); plain playback", error.localizedDescription)
            do { try s.setCategory(.playback, mode: .default) } catch { NSLog("Roadbook: audio session category refused: %@", error.localizedDescription) }
        }
        do { try s.setActive(true); configured = true } catch { NSLog("Roadbook: audio session could not activate: %@", error.localizedDescription) }
    }

    /// One or more notes back to back as 16-bit mono WAV bytes. freq in Hz, each ms long, with a soft attack/release.
    private static func render(_ notes: [(Double, Int)], gap: Int = 0) -> Data {
        let rate = Double(Tone.rate)
        let total = notes.reduce(0) { $0 + $1.1 } + gap * (notes.count - 1)
        var pcm = [Int16](repeating: 0, count: total * Tone.rate / 1000)
        var pos = 0
        for (i, n) in notes.enumerated() {
            let (f, ms) = n
            let len = ms * Tone.rate / 1000
            let ramp = min(len / 4, Tone.rate / 100)   // ≤10 ms
            for k in 0..<len {
                let env: Double = k < ramp ? Double(k) / Double(ramp) : (k > len - ramp ? Double(len - k) / Double(ramp) : 1.0)
                // a little second harmonic so it cuts through road noise
                let v = sin(2 * .pi * f * Double(k) / rate) * 0.8 + sin(4 * .pi * f * Double(k) / rate) * 0.2
                pcm[pos + k] = Int16(v * env * 0.6 * Double(Int16.max))
            }
            pos += len + (i < notes.count - 1 ? gap * Tone.rate / 1000 : 0)
        }
        return wav(pcm)
    }

    private static func wav(_ pcm: [Int16]) -> Data {
        var d = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: UInt16) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 2)) }
        let bytes = pcm.count * 2
        d.append("RIFF".data(using: .ascii)!); u32(UInt32(36 + bytes)); d.append("WAVE".data(using: .ascii)!)
        d.append("fmt ".data(using: .ascii)!); u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append("data".data(using: .ascii)!); u32(UInt32(bytes))
        pcm.withUnsafeBufferPointer { d.append(Data(buffer: $0)) }
        return d
    }

    private func play(_ key: String, _ build: () -> Data) {
        guard enabled else { return }
        if !Tone.configured { Tone.configureSession() }
        lock.lock()
        var p = cache[key]
        if p == nil {
            do { let pl = try AVAudioPlayer(data: build()); pl.volume = 1; pl.prepareToPlay(); p = pl; cache[key] = pl }
            catch { NSLog("Roadbook: tone %@ could not be made: %@", key, error.localizedDescription) }
        }
        lock.unlock()
        guard let player = p else { return }
        player.currentTime = 0
        if !player.play() { NSLog("Roadbook: tone %@ did not play", key) }
    }

    /// Where sound is going right now ("iPhone speaker", a helmet comm's name…), so a silent phone explains itself.
    static var outputName: String {
        let outs = AVAudioSession.sharedInstance().currentRoute.outputs
        guard let o = outs.first else { return "no output" }
        switch o.portType {
        case .builtInSpeaker: return "iPhone speaker"
        case .builtInReceiver: return "iPhone earpiece"
        case .headphones: return "wired headphones"
        default: return o.portName
        }
    }

    /// Approach pip: progress 0..1 → pitch 520..1040 Hz. Quantized to 16 steps so the cache stays small.
    func pip(_ progress: Double) {
        let step = Int(min(1, max(0, progress)) * 16)
        let f = 520.0 * pow(2.0, Double(step) / 16.0)
        play("pip\(step)") { Tone.render([(f, 70)]) }
    }
    func turn() { play("turn") { Tone.render([(1040, 90), (1320, 160)], gap: 20) } }
    func offRoute() { play("off") { Tone.render([(220, 350)]) } }
    func arrive() { play("arrive") { Tone.render([(660, 120), (880, 120), (1320, 260)], gap: 30) } }
    func back() { play("back") { Tone.render([(880, 80), (660, 120)], gap: 20) } }
    /// Hazard: low triple buzz, one more at level 3.
    func danger(_ level: Int) { play("danger\(level)") { Tone.render(Array(repeating: (260.0, 110), count: level >= 3 ? 4 : 3), gap: 70) } }
    /// A note point: two soft mid notes.
    func note() { play("note") { Tone.render([(740, 90), (740, 90)], gap: 60) } }
}
