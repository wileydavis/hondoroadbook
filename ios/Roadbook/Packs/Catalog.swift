import Foundation

/// Roadbook's one use of the network by itself: the pack catalog and the packs it points at, and only when
/// the rider presses DOWNLOAD. catalog.json: { "packs": [ { id, name, region, group, kind, url, bytes, built, bbox, notes } ] }
struct CatalogPack: Identifiable, Hashable {
    let id: String, name: String, region: String, group: String, kind: String, url: String, bytes: Int64, built: String, notes: String
    var kindLabel: String { switch kind { case "roads": return "Roads"; case "terrain": return "Terrain"; case "imagery": return "Imagery"; default: return kind.capitalizedFirst } }
    var blurb: String { switch kind { case "roads": return "roads, trails, routing, address search"; case "terrain": return "hillshade and contours under the roads"; case "imagery": return "aerial photos"; default: return "" } }
}

enum Catalog {
    static var url: String { Bundle.main.object(forInfoDictionaryKey: "CATALOG_URL") as? String ?? "" }
    /// https://hondoroadbook.com, derived from the catalog URL so one setting names the site
    static var site: String { url.components(separatedBy: "/packs/").first.flatMap { $0.isEmpty ? nil : $0 } ?? "https://hondoroadbook.com" }

    static func parse(_ data: Data) throws -> [CatalogPack] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let arr = root["packs"] as? [[String: Any]] else { throw Fail("Catalog is not readable") }
        return arr.compactMap { o in
            guard let id = o["id"] as? String, let name = o["name"] as? String, let url = o["url"] as? String else { return nil }
            return CatalogPack(id: id, name: name, region: o["region"] as? String ?? name, group: o["group"] as? String ?? "", kind: o["kind"] as? String ?? "roads", url: url,
                               bytes: (o["bytes"] as? NSNumber)?.int64Value ?? 0, built: o["built"] as? String ?? "", notes: o["notes"] as? String ?? "")
        }
    }

    static func fetch() async throws -> [CatalogPack] {
        guard let u = URL(string: url), !url.isEmpty else { throw Fail("This build has no pack catalog.") }
        var req = URLRequest(url: u); req.timeoutInterval = 20; req.setValue("application/json", forHTTPHeaderField: "Accept"); req.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let code = (resp as? HTTPURLResponse)?.statusCode, code == 200 else { throw Fail("Catalog: HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)") }
        return try parse(data)
    }

    /// "" / "installed" / "update"
    static func state(_ installed: [String: Pack], _ p: CatalogPack) -> String {
        guard let have = installed[p.name] else { return "" }
        return have.manifest.created < p.built ? "update" : "installed"
    }
}

/// Downloads resume: a partial file in the cache keeps its bytes and the next attempt asks for the rest.
final class Downloader: NSObject, URLSessionDataDelegate {
    private var session: URLSession!
    private var handle: FileHandle?
    private var have: Int64 = 0
    private var total: Int64 = 0
    private var onProgress: ((String) -> Void)?
    private var cont: CheckedContinuation<Void, Error>?
    private var failed: Error?
    private(set) var cancelled = false

    static var dir: URL {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true); return d
    }

    func cancel() { cancelled = true; session?.invalidateAndCancel() }

    /// Fetches the pack into the cache (resuming a partial file) and returns the finished .mappack.
    func download(_ p: CatalogPack, progress: @escaping (String) -> Void) async throws -> URL {
        let dir = Downloader.dir
        // the partial file is named for the build it belongs to: a newer catalog entry never resumes onto old bytes
        let stamp = p.built.replacingOccurrences(of: "[^0-9A-Za-z]", with: "", options: .regularExpression)
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] where f.lastPathComponent.hasPrefix(p.id + "-") && f.pathExtension == "part" && !f.lastPathComponent.contains("-\(stamp).") { try? FileManager.default.removeItem(at: f) }
        let file = dir.appendingPathComponent("\(p.id)-\(stamp).mappack.part")
        let free = (try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
        if free < p.bytes + (64 << 20) { throw Fail("Not enough space: \(p.name) needs \(p.bytes / (1024 * 1024)) MB.") }
        have = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
        if p.bytes > 0 && have > p.bytes { try? FileManager.default.removeItem(at: file); have = 0 }
        if have < p.bytes || p.bytes == 0 {
            if !FileManager.default.fileExists(atPath: file.path) { FileManager.default.createFile(atPath: file.path, contents: nil) }
            handle = try FileHandle(forWritingTo: file); try handle?.seekToEnd()
            onProgress = progress
            let cfg = URLSessionConfiguration.default; cfg.timeoutIntervalForRequest = 30; cfg.timeoutIntervalForResource = 6 * 3600
            session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
            guard let u = URL(string: p.url) else { throw Fail("Bad pack URL") }
            var req = URLRequest(url: u)
            if have > 0 { req.setValue("bytes=\(have)-", forHTTPHeaderField: "Range") }
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                cont = c
                session.dataTask(with: req).resume()
            }
            try? handle?.close(); handle = nil
            if total > 0 && have < total { throw Fail("Connection dropped — download again to resume") }
        }
        let done = dir.appendingPathComponent("\(p.id).mappack")
        try? FileManager.default.removeItem(at: done)
        try FileManager.default.moveItem(at: file, to: done)
        return done
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let r = response as? HTTPURLResponse else { completionHandler(.cancel); return }
        if r.statusCode == 200 && have > 0 {
            // the server ignored the range: start over
            try? handle?.truncate(atOffset: 0); have = 0
        } else if r.statusCode != 200 && r.statusCode != 206 { failed = Fail("HTTP \(r.statusCode)"); completionHandler(.cancel); return }
        total = r.statusCode == 206 ? have + r.expectedContentLength : r.expectedContentLength
        completionHandler(.allow)
    }

    private var lastPct: Int64 = -1
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let h = handle else { return }
        do { try h.write(contentsOf: data) } catch { failed = error; dataTask.cancel(); return }
        have += Int64(data.count)
        let pct = total > 0 ? have * 100 / total : -1
        if pct != lastPct {
            lastPct = pct; let mb = have / (1024 * 1024); let t = total / (1024 * 1024)
            let msg = pct >= 0 ? "\(pct)% · \(mb) of \(t) MB" : "\(mb) MB"
            DispatchQueue.main.async { [onProgress] in onProgress?(msg) }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let c = cont; cont = nil
        session.finishTasksAndInvalidate()
        if cancelled { c?.resume(throwing: CancellationError()); return }
        if let f = failed { c?.resume(throwing: f); return }
        if let e = error { c?.resume(throwing: Fail("Connection dropped (\(e.localizedDescription)) — download again to resume")); return }
        c?.resume()
    }
}
