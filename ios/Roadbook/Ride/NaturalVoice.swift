import Foundation

/// The natural voice: Kokoro-82M (Apache-2.0) run on the phone by sherpa-onnx, from the pack the rider downloads under
/// MAPS → VOICE (about 117 MB, kept in Application Support). Synthesis is slower than the system voice, so Speech
/// prepares phrases ahead of the cue and falls back to the system voice when one is not ready in time.
final class NaturalVoice {
    static let pack = "kokoro-en-v1"
    static let sizeMB = 117
    static let bytes: Int64 = 117_011_968   // kokoro-en-v1.zip as built by .github/workflows/voices.yml
    static var url: String { Catalog.site + "/packs/voices/\(pack).zip" }
    /// the speakers offered (Kokoro v1.0 ids)
    static let speakers: [(Int, String)] = [
        (3, "Heart · US, warm"), (2, "Bella · US"), (6, "Nicole · US, soft"), (9, "Sarah · US"),
        (16, "Michael · US"), (14, "Fenrir · US, deep"), (18, "Puck · US, bright"), (11, "Adam · US"),
        (21, "Emma · British"), (24, "Daniel · British"), (26, "George · British"),
    ]
    static var dir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("voices/\(pack)", isDirectory: true)
    }
    static var installed: Bool {
        FileManager.default.fileExists(atPath: dir.appendingPathComponent("installed").path) && FileManager.default.fileExists(atPath: dir.appendingPathComponent("model.int8.onnx").path)
    }

    private let tts: OpaquePointer

    init?() {
        guard NaturalVoice.installed else { return nil }
        let d = NaturalVoice.dir
        func p(_ name: String) -> UnsafeMutablePointer<CChar> { strdup(d.appendingPathComponent(name).path)! }
        let strs = [p("model.int8.onnx"), p("voices.bin"), p("tokens.txt"), p("espeak-ng-data"), p("lexicon-us-en.txt"), strdup("cpu")!]
        defer { strs.forEach { free($0) } }
        var c = SherpaOnnxOfflineTtsConfig()
        c.model.kokoro.model = UnsafePointer(strs[0])
        c.model.kokoro.voices = UnsafePointer(strs[1])
        c.model.kokoro.tokens = UnsafePointer(strs[2])
        c.model.kokoro.data_dir = UnsafePointer(strs[3])
        c.model.kokoro.lexicon = UnsafePointer(strs[4])
        c.model.kokoro.length_scale = 1.0
        c.model.num_threads = 2
        c.model.provider = UnsafePointer(strs[5])
        c.max_num_sentences = 1
        guard let t = SherpaOnnxCreateOfflineTts(&c) else { return nil }
        tts = t
    }
    deinit { SherpaOnnxDestroyOfflineTts(tts) }

    /// 16-bit mono WAV of `text` said by speaker `sid`; nil if the model could not.
    func wav(_ text: String, sid: Int) -> Data? {
        guard let a = SherpaOnnxOfflineTtsGenerate(tts, text, Int32(sid), 1.0) else { return nil }
        defer { SherpaOnnxDestroyOfflineTtsGeneratedAudio(a) }
        let n = Int(a.pointee.n)
        guard n > 0, let s = a.pointee.samples else { return nil }
        var pcm = [Int16](repeating: 0, count: n)
        for i in 0..<n { pcm[i] = Int16(max(-1, min(1, s[i])) * 32767 * 0.9) }
        return NaturalVoice.wav(pcm, rate: Int(a.pointee.sample_rate))
    }

    static func wav(_ pcm: [Int16], rate: Int) -> Data {
        var d = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: UInt16) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 2)) }
        d.append("RIFF".data(using: .ascii)!); u32(UInt32(36 + pcm.count * 2)); d.append("WAVEfmt ".data(using: .ascii)!)
        u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append("data".data(using: .ascii)!); u32(UInt32(pcm.count * 2))
        pcm.withUnsafeBufferPointer { d.append(Data(buffer: $0)) }
        return d
    }
}
