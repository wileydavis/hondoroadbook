import Foundation

/// Roadbook's other uses of the network, every one of them a button the rider presses:
///   FETCH CODE — a roadbook parked by the planner on hondoroadbook.com, under a six-letter code
///   PUBLISH    — this roadbook into the site's library, trimmed at the ends if asked
///   BROWSE     — the library's roadbooks in the part of the map on screen
/// Nothing identifies the phone: no account, no id, and a published file keeps only the line and cues.
struct LibraryItem: Identifiable, Hashable {
    let id: String, name: String, lengthM: Double, cues: Int, mode: String, unpavedShare: Double, created: String, url: String
    let line: [[Double]]   // lon,lat
}

struct PublishedRoadbook { let name: String, length: Double, cues: Int, page: String }

enum Library {
    static var site: String { Catalog.site }

    private static func request(_ url: String, method: String = "GET", body: Data? = nil, contentType: String? = nil) async throws -> (Data, HTTPURLResponse) {
        guard let u = URL(string: url) else { throw Fail("Bad URL") }
        var req = URLRequest(url: u); req.httpMethod = method; req.timeoutInterval = 30
        req.setValue("application/json, application/gpx+xml", forHTTPHeaderField: "Accept")
        if let b = body { req.httpBody = b; if let ct = contentType { req.setValue(ct, forHTTPHeaderField: "Content-Type") } }
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let r = resp as? HTTPURLResponse else { throw Fail("No response") }
        return (data, r)
    }
    private static func errorOf(_ data: Data, _ code: Int) -> String {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String ?? "HTTP \(code)"
    }

    /// A roadbook parked by the planner: (name, gpx text).
    static func fetchCode(_ code: String) async throws -> (String, String) {
        let (data, r) = try await request("\(site)/api/parked/\(code)")
        if r.statusCode != 200 { throw Fail(errorOf(data, r.statusCode)) }
        let name = (r.value(forHTTPHeaderField: "X-Name")?.removingPercentEncoding) ?? "Roadbook"
        return (name, String(decoding: data, as: UTF8.self))
    }

    static func publish(name: String, gpx: String, trim: Int) async throws -> PublishedRoadbook {
        let q = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
        let (data, r) = try await request("\(site)/api/roadbooks?name=\(q)&trim=\(trim)&source=app", method: "POST", body: Data(gpx.utf8), contentType: "application/gpx+xml")
        if r.statusCode != 200 { throw Fail(errorOf(data, r.statusCode)) }
        guard let j = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Fail("Unexpected reply") }
        return PublishedRoadbook(name: j["name"] as? String ?? name, length: (j["length"] as? NSNumber)?.doubleValue ?? 0, cues: (j["cues"] as? NSNumber)?.intValue ?? 0, page: j["page"] as? String ?? "")
    }

    static func browse(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) async throws -> [LibraryItem] {
        let (data, r) = try await request("\(site)/api/roadbooks?bbox=\(minLon),\(minLat),\(maxLon),\(maxLat)&limit=300")
        if r.statusCode != 200 { throw Fail(errorOf(data, r.statusCode)) }
        guard let j = try JSONSerialization.jsonObject(with: data) as? [String: Any], let arr = j["roadbooks"] as? [[String: Any]] else { throw Fail("Unexpected reply") }
        let df = DateFormatter(); df.locale = Locale(identifier: "en_US_POSIX"); df.dateFormat = "yyyy-MM-dd"
        return arr.compactMap { o in
            guard let id = o["id"] as? String, let url = o["url"] as? String else { return nil }
            let line = (o["line"] as? [[NSNumber]] ?? []).map { $0.map { $0.doubleValue } }
            let paved = (o["paved"] as? NSNumber)?.doubleValue, unpaved = (o["unpaved"] as? NSNumber)?.doubleValue
            let share: Double = (paved != nil && unpaved != nil && paved! + unpaved! > 0) ? unpaved! / (paved! + unpaved!) : .nan
            let created = ((o["created"] as? NSNumber)?.doubleValue).flatMap { $0 > 0 ? df.string(from: Date(timeIntervalSince1970: $0 / 1000)) : nil } ?? ""
            return LibraryItem(id: id, name: o["name"] as? String ?? "Roadbook", lengthM: (o["length"] as? NSNumber)?.doubleValue ?? 0, cues: (o["cues"] as? NSNumber)?.intValue ?? 0,
                               mode: o["mode"] as? String ?? "", unpavedShare: share, created: created, url: url, line: line)
        }
    }

    static func download(_ item: LibraryItem) async throws -> String {
        let (data, r) = try await request(item.url)
        if r.statusCode != 200 { throw Fail(errorOf(data, r.statusCode)) }
        return String(decoding: data, as: UTF8.self)
    }
}
