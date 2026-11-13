import AVFoundation
import Foundation
import FoldCore

/// Spoken cues. Everything is best-effort: with the voice silent the pips and chimes carry the cue alone.
///
/// The voice is Kokoro (Heart, bundled: NaturalVoice). It is slower to make than the iPhone's own voice, so a cue's
/// phrase is prepared as soon as the cue is next; a phrase it has not made gets `wait` seconds, and the iPhone's best
/// installed English voice says it if that is not enough (and while Kokoro loads at launch).
final class Speech {
    static let shared = Speech()
    private let synth = AVSpeechSynthesizer()
    private var voice: AVSpeechSynthesisVoice?
    var enabled = false
    /// the system voice is always ready
    var ready: Bool { true }
    var speaking: Bool { synth.isSpeaking || (player?.isPlaying ?? false) }

    private let naturalSid = 0
    private var natural: NaturalVoice?
    private let natQueue = DispatchQueue(label: "roadbook.kokoro", qos: .userInitiated)
    private var cache: [String: Data] = [:]      // "sid|text" → WAV; read and written on main
    private var cacheOrder: [String] = []
    private var player: AVAudioPlayer?
    private var generation = 0
    static let wait = 1.2

    private init() {
        // the ride owns the audio session (Tone.configureSession); the synthesizer must not reconfigure it
        synth.usesApplicationAudioSession = true
        pickSystemVoice()
        loadNatural()
    }

    private func pickSystemVoice() {
        // prefer a premium, then enhanced, English voice in the phone's own accent
        let lang = AVSpeechSynthesisVoice.currentLanguageCode()
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix(lang.prefix(2)) }
        voice = voices.first { $0.quality == .premium && $0.language == lang } ?? voices.first { $0.quality == .premium }
            ?? voices.first { $0.quality == .enhanced && $0.language == lang } ?? voices.first { $0.quality == .enhanced } ?? AVSpeechSynthesisVoice(language: lang)
    }
    private func loadNatural() {
        natQueue.async { [weak self] in
            let n = NaturalVoice()
            DispatchQueue.main.async { self?.natural = n }
        }
    }
    var usingNatural: Bool { natural != nil }

    /// the written cue as it should be heard: "US-287" → "U S two eighty-seven", a roll chart's capitals as words
    private func spoken(_ text: String) -> String { SpeechText.shared.forVoice(text: text) }

    private func key(_ text: String) -> String { "\(naturalSid)|\(text)" }
    private func store(_ k: String, _ w: Data) {
        if cache[k] == nil { cacheOrder.append(k) }
        cache[k] = w
        while cacheOrder.count > 16 { cache.removeValue(forKey: cacheOrder.removeFirst()) }
    }
    /// makes the natural voice's WAV off the main thread, caches it, and hands it back on main
    private func make(_ text: String, done: ((Data?) -> Void)? = nil) {
        guard let n = natural else { done?(nil); return }
        let k = key(text), sid = naturalSid
        if let w = cache[k] { done?(w); return }
        natQueue.async { [weak self] in
            let w = n.wav(text, sid: sid)
            DispatchQueue.main.async { if let w, let self { self.store(k, w) }; done?(w) }
        }
    }

    /// Synthesize ahead of time so say() starts instantly (the natural voice only; the system voice needs no warm-up).
    func prepare(_ text: String) {
        guard enabled, usingNatural else { return }
        let t = spoken(text); if t.isEmpty { return }
        onMain { self.make(t) }
    }

    /// Speak now, replacing whatever is being said.
    func say(_ text: String) {
        guard enabled else { return }
        let t = spoken(text)
        guard !t.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        onMain { self.speak(t, waitForNatural: Speech.wait) }
    }

    private func onMain(_ f: @escaping () -> Void) { if Thread.isMainThread { f() } else { DispatchQueue.main.async(execute: f) } }

    private func speak(_ t: String, waitForNatural: Double) {
        stopNow()
        generation += 1; let gen = generation
        guard usingNatural else { sayWithSystem(t); return }
        if let w = cache[key(t)] { play(w); return }
        var started = false
        make(t) { [weak self] w in
            guard let self, gen == self.generation, !started else { return }
            started = true
            if let w { self.play(w) } else { self.sayWithSystem(t) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + waitForNatural) { [weak self] in
            guard let self, gen == self.generation, !started else { return }
            started = true; self.sayWithSystem(t)
        }
    }

    private func sayWithSystem(_ t: String) {
        // the session stays active about as long as the phrase takes; the release checks isSpeaking before letting go
        Tone.hold(1.0 + Double(t.count) * 0.07)
        let u = AVSpeechUtterance(string: t)
        u.voice = voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95
        u.prefersAssistiveTechnologySettings = false
        synth.speak(u)
    }

    private func play(_ w: Data) {
        do {
            let p = try AVAudioPlayer(data: w)
            Tone.hold(p.duration + 0.5)
            p.prepareToPlay(); p.play(); player = p
        } catch { NSLog("Roadbook: natural voice playback: %@", error.localizedDescription) }
    }

    private func stopNow() { synth.stopSpeaking(at: .immediate); player?.stop(); player = nil }
    func stop() { onMain { self.generation += 1; self.stopNow() } }
}
