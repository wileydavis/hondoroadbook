import AVFoundation
import Foundation
import FoldCore

/// Spoken cues. Everything is best-effort: with the voice silent the pips and chimes carry the cue alone.
///
/// Two voices: the iPhone's own (offline on every phone; the rider can pick any English voice installed, including
/// the Premium ones from Settings → Accessibility → Spoken Content → Voices), and the natural voice (Kokoro, a
/// download: NaturalVoice). The natural voice is slower to make, so a cue's phrase is prepared as soon as the cue is
/// next; a phrase it has not made gets `wait` seconds, and the system voice says it if that is not enough.
final class Speech {
    static let shared = Speech()
    private let synth = AVSpeechSynthesizer()
    private var voice: AVSpeechSynthesisVoice?
    var enabled = false
    /// the system voice is always ready
    var ready: Bool { true }
    var speaking: Bool { synth.isSpeaking || (player?.isPlaying ?? false) }

    private let d = UserDefaults.standard
    /// the natural voice's speaker id, or -1 for the system voice
    private(set) var naturalSid: Int
    /// the chosen system voice's identifier, or nil for the best installed English one
    private(set) var systemVoiceId: String?
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
        naturalSid = d.object(forKey: "voiceNatural") as? Int ?? -1
        systemVoiceId = d.string(forKey: "voiceSystem")
        pickSystemVoice()
        loadNatural()
    }

    /// English voices installed on this phone, best first.
    static var systemVoices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("en") && !$0.voiceTraits.contains(.isNoveltyVoice) }
            .sorted { ($0.quality.rawValue, $0.language == AVSpeechSynthesisVoice.currentLanguageCode() ? 1 : 0, $0.name) > ($1.quality.rawValue, $1.language == AVSpeechSynthesisVoice.currentLanguageCode() ? 1 : 0, $1.name) }
    }
    private func pickSystemVoice() {
        if let id = systemVoiceId, let v = AVSpeechSynthesisVoice(identifier: id) { voice = v; return }
        // prefer a premium, then enhanced, English voice in the phone's own accent
        let lang = AVSpeechSynthesisVoice.currentLanguageCode()
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix(lang.prefix(2)) }
        voice = voices.first { $0.quality == .premium && $0.language == lang } ?? voices.first { $0.quality == .premium }
            ?? voices.first { $0.quality == .enhanced && $0.language == lang } ?? voices.first { $0.quality == .enhanced } ?? AVSpeechSynthesisVoice(language: lang)
    }
    private func loadNatural() {
        let sid = naturalSid
        guard sid >= 0, NaturalVoice.installed else { natural = nil; return }
        if natural != nil { return }
        natQueue.async { [weak self] in
            let n = NaturalVoice()
            DispatchQueue.main.async { if self?.naturalSid ?? -1 >= 0 { self?.natural = n } }
        }
    }
    var usingNatural: Bool { naturalSid >= 0 && natural != nil }

    /// MAPS → VOICE: the system voice by identifier (nil for automatic), with the natural voice off.
    func useSystem(_ id: String?) {
        systemVoiceId = id; d.set(id, forKey: "voiceSystem"); pickSystemVoice()
        naturalSid = -1; d.set(-1, forKey: "voiceNatural"); natural = nil; cache.removeAll(); cacheOrder.removeAll()
    }
    /// MAPS → VOICE: the natural voice's speaker.
    func useNatural(_ sid: Int) {
        naturalSid = sid; d.set(sid, forKey: "voiceNatural"); cache.removeAll(); cacheOrder.removeAll(); loadNatural()
    }

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
            DispatchQueue.main.async { if let w, let self, self.naturalSid == sid { self.store(k, w) }; done?(w) }
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

    /// MAPS → VOICE → TEST: the chosen voice, whether or not cues are being spoken, waiting as long as the natural voice takes.
    func test(_ text: String) { onMain { self.speak(self.spoken(text), waitForNatural: 30) } }

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
