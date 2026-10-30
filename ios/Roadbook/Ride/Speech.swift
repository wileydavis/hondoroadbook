import AVFoundation
import Foundation

/// Spoken cues through the system voice (offline on every iPhone; no download, no model). Everything is
/// best-effort: with the voice silent the pips and chimes carry the cue alone. A Piper voice, the same one
/// Android bundles, can replace this later behind the same four calls.
final class Speech {
    static let shared = Speech()
    private let synth = AVSpeechSynthesizer()
    private var voice: AVSpeechSynthesisVoice?
    var enabled = false
    /// the system voice is always ready
    var ready: Bool { true }
    var speaking: Bool { synth.isSpeaking }

    private init() {
        // the ride owns the audio session (Tone.configureSession); the synthesizer must not reconfigure it
        synth.usesApplicationAudioSession = true
        // prefer an enhanced English voice when the phone has one downloaded
        let lang = AVSpeechSynthesisVoice.currentLanguageCode()
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix(lang.prefix(2)) }
        voice = voices.first { $0.quality == .premium } ?? voices.first { $0.quality == .enhanced } ?? AVSpeechSynthesisVoice(language: lang)
    }

    /// Synthesize ahead of time so say() starts instantly — the system voice needs no warm-up, so this is a no-op.
    func prepare(_ text: String) {}

    /// Speak now, replacing whatever is being said.
    func say(_ text: String) {
        guard enabled, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        // the session stays active about as long as the phrase takes; the release checks isSpeaking before letting go
        Tone.hold(1.0 + Double(text.count) * 0.07)
        synth.stopSpeaking(at: .immediate)
        let u = AVSpeechUtterance(string: text)
        u.voice = voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95
        u.prefersAssistiveTechnologySettings = false
        synth.speak(u)
    }

    func stop() { synth.stopSpeaking(at: .immediate) }
}
