import Foundation

/// A pencil mark on the map: a point with a name and a note.
struct Mark: Identifiable, Hashable {
    let id: Int64
    var name: String
    var note: String
    let lat: Double, lon: Double
    let created: Int64
}

/// The only thing Roadbook writes besides packs and settings, and only when you tap SAVE. Kept as a plain GPX
/// file (marks.gpx) in the app's private storage: export it to your Documents folder or burn it, from Maps.
final class MarkStore: ObservableObject {
    private let file = Files.support.appendingPathComponent("marks.gpx")
    @Published private(set) var marks: [Mark] = []

    init() { load() }

    func load() { marks = (try? String(contentsOf: file, encoding: .utf8)).map(MarkStore.parse) ?? [] }

    /// Each change is written before it becomes visible; a failed write leaves memory and file as they were.
    @discardableResult
    func add(_ name: String, _ note: String, _ lat: Double, _ lon: Double) throws -> Mark {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let m = Mark(id: now, name: name, note: note, lat: lat, lon: lon, created: now)
        let next = marks + [m]; try save(next); marks = next; return m
    }
    func update(_ m: Mark, name: String, note: String) throws {
        var next = marks; guard let i = next.firstIndex(where: { $0.id == m.id }) else { return }
        next[i].name = name; next[i].note = note; try save(next); marks = next
    }
    func remove(_ m: Mark) throws { let next = marks.filter { $0.id != m.id }; try save(next); marks = next }
    func burn() { try? FileManager.default.removeItem(at: file); marks = [] }

    func gpx(_ list: [Mark]? = nil) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Roadbook\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        let iso = ISO8601DateFormatter()
        for m in list ?? marks {
            s += "  <wpt lat=\"\(m.lat)\" lon=\"\(m.lon)\">\n    <name>\(MarkStore.esc(m.name))</name>\n"
            if !m.note.isEmpty { s += "    <desc>\(MarkStore.esc(m.note))</desc>\n" }
            s += "    <time>\(iso.string(from: Date(timeIntervalSince1970: Double(m.created) / 1000)))</time>\n  </wpt>\n"
        }
        return s + "</gpx>\n"
    }

    /// Atomic: the new file is written beside the old one and renamed over it, so a crash never leaves a torn marks.gpx.
    private func save(_ list: [Mark]) throws {
        if list.isEmpty { try? FileManager.default.removeItem(at: file); return }
        try gpx(list).write(to: file, atomically: true, encoding: .utf8)
    }

    /// Writes marks into the Documents folder (visible in the Files app). Returns the file name written.
    func export() throws -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        let name = "roadbook-marks-\(f.string(from: Date())).gpx"
        try gpx().write(to: Files.documents.appendingPathComponent(name), atomically: true, encoding: .utf8)
        return name
    }

    private static func esc(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
    private static func unesc(_ s: String) -> String { s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&amp;", with: "&") }

    static func parse(_ text: String) -> [Mark] {
        guard let re = try? NSRegularExpression(pattern: "<wpt\\s+lat=\"([^\"]+)\"\\s+lon=\"([^\"]+)\">(.*?)</wpt>", options: [.dotMatchesLineSeparators]) else { return [] }
        let ns = text as NSString
        var out: [Mark] = []; var i: Int64 = 0
        let iso = ISO8601DateFormatter()
        func inner(_ body: String, _ tag: String) -> String? {
            guard let a = body.range(of: "<\(tag)>"), let b = body.range(of: "</\(tag)>", range: a.upperBound..<body.endIndex) else { return nil }
            return String(body[a.upperBound..<b.lowerBound])
        }
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let lat = Double(ns.substring(with: m.range(at: 1))), let lon = Double(ns.substring(with: m.range(at: 2))) else { continue }
            let body = ns.substring(with: m.range(at: 3))
            let name = inner(body, "name").map(unesc) ?? "Mark"
            let note = inner(body, "desc").map(unesc) ?? ""
            let t = inner(body, "time").flatMap { iso.date(from: $0) }.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
            out.append(Mark(id: t + i, name: name, note: note, lat: lat, lon: lon, created: t)); i += 1
        }
        return out
    }
}
