import Foundation
import FoldCore
import ZIPFoundation

/// One installed region: tiles for the map, a graph for routing, an index for search. Same layout as Android:
/// Library/Application Support/packs/<slug>/{pack.txt, tiles.pmtiles, graph.bin, search.tsv[, roads.pmtiles]}.
final class Pack: Identifiable, Equatable {
    let dir: URL
    let manifest: PackManifest
    var id: String { dir.lastPathComponent }
    var name: String { manifest.name }
    var isTerrain: Bool { manifest.isTerrain }
    var tiles: URL { dir.appendingPathComponent(isTerrain ? "terrain.pmtiles" : "tiles.pmtiles") }
    /// roads.pmtiles, when the pack was built with it
    var overview: URL? { let u = dir.appendingPathComponent("roads.pmtiles"); return FileManager.default.fileExists(atPath: u.path) ? u : nil }
    var router: Router?
    private var search: SearchFile?
    private let lock = NSLock()

    init(dir: URL, manifest: PackManifest) { self.dir = dir; self.manifest = manifest }
    static func == (a: Pack, b: Pack) -> Bool { a.dir.standardizedFileURL.path == b.dir.standardizedFileURL.path }

    var sizeBytes: Int64 {
        (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]))?.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) } ?? 0
    }

    func loadRouter() -> Router? {
        if isTerrain { return nil }
        lock.lock(); defer { lock.unlock() }
        if let r = router { return r }
        do { let r = Router(g: try Graph.companion.open(path: dir.appendingPathComponent("graph.bin").path)); router = r; return r }
        catch { NSLog("Roadbook: graph open failed for \(name): \(error)"); return nil }
    }
    func loadSearch() -> SearchFile? {
        if isTerrain { return nil }
        lock.lock(); defer { lock.unlock() }
        if let s = search { return s }
        search = try? SearchFile.companion.open(path: dir.appendingPathComponent("search.tsv").path)
        return search
    }
    func contains(_ lat: Double, _ lon: Double) -> Bool { manifest.contains(lat: lat, lon: lon) }
}

final class PackStore {
    let root: URL
    private(set) var packs: [Pack] = []
    /// packs on disk that failed validation this scan, with the reason; shown under MAPS, never routed
    private(set) var broken: [(String, String)] = []
    private(set) var routerErrors: [String] = []
    private var multi: MultiRouter?
    private var multiFor: [Pack] = []
    private let lock = NSLock()

    init() {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
        root = base.appendingPathComponent("packs", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        // map packs are re-downloadable: keep them out of iCloud backups
        var u = root; var rv = URLResourceValues(); rv.isExcludedFromBackup = true; try? u.setResourceValues(rv)
    }

    var roadPacks: [Pack] { packs.filter { !$0.isTerrain } }
    func packFor(_ lat: Double, _ lon: Double) -> Pack? { packs.first { !$0.isTerrain && $0.contains(lat, lon) } }

    /// One router over every installed pack's graph; packs whose graph fails to open are skipped.
    func multiRouter() -> MultiRouter {
        lock.lock(); defer { lock.unlock() }
        let cur = packs
        if let m = multi, multiFor == cur { return m }
        var errs: [String] = []
        let routers: [Router] = cur.filter { !$0.isTerrain }.compactMap { p in
            if let r = p.loadRouter() { return r }
            errs.append("Routing data for \(p.name) could not be opened — re-download or re-import that pack"); return nil
        }
        routerErrors = errs
        let m = MultiRouter(routers: routers); multi = m; multiFor = cur
        return m
    }

    /// Lists installed packs. Also finishes any replacement a crash interrupted and validates every pack before it is used.
    @discardableResult
    func scan() -> [Pack] {
        recover()
        var found: [Pack] = []; var bad: [(String, String)] = []
        let fm = FileManager.default
        let dirs = ((try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true && !$0.lastPathComponent.hasPrefix(".") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        for d in dirs {
            let mf = d.appendingPathComponent("pack.txt")
            guard let text = try? String(contentsOf: mf, encoding: .utf8) else { bad.append((d.lastPathComponent, "no pack.txt")); continue }
            guard let m = PackManifest.companion.parse(text: text) else { bad.append((d.lastPathComponent, "unreadable pack.txt")); continue }
            let missing = m.required.filter { !fm.fileExists(atPath: d.appendingPathComponent($0).path) }
            if !missing.isEmpty { bad.append((m.name, "missing \(missing.joined(separator: ", "))")); continue }
            found.append(Pack(dir: d, manifest: m))
        }
        lock.lock(); packs = found; broken = bad; lock.unlock()
        return found
    }

    /// A replacement goes old → .old-slug, staging → slug, delete .old-slug. If the process died between the
    /// first two steps the pack is in .old-slug and nothing is at slug: put it back. Leftover staging dirs go.
    private func recover() {
        let fm = FileManager.default
        for d in (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            let n = d.lastPathComponent
            if n.hasPrefix(".old-") {
                let live = root.appendingPathComponent(String(n.dropFirst(5)))
                if !fm.fileExists(atPath: live.appendingPathComponent("pack.txt").path) { try? fm.removeItem(at: live); try? fm.moveItem(at: d, to: live) } else { try? fm.removeItem(at: d) }
            } else if n.hasPrefix(".staging-") || n.hasPrefix(".import") { try? fm.removeItem(at: d) }
        }
    }

    /// Unpacks a .mappack (a stored zip) into place. Returns the new pack or throws with a readable message.
    func importPack(_ file: URL, progress: @escaping (String) -> Void) throws -> Pack {
        let fm = FileManager.default
        let zip: Archive
        do { zip = try Archive(url: file, accessMode: .read) } catch { throw Fail("Could not open that file as a map pack (\(error.text))") }
        guard let mfEntry = zip["pack.txt"] else { throw Fail("Not a .mappack (no pack.txt)") }
        var mfData = Data(); _ = try zip.extract(mfEntry) { mfData.append($0) }
        guard let manifest = PackManifest.companion.parse(text: String(decoding: mfData, as: UTF8.self)) else { throw Fail("Bad pack.txt") }
        // (PackManifest.FORMAT / MIN_FORMAT in Pack.kt)
        let minFormat: Int32 = 2, maxFormat: Int32 = 3
        if manifest.format < minFormat || manifest.format > maxFormat {
            throw Fail(manifest.format < minFormat ? "This pack was made with an older packer — download it again" : "This pack needs a newer Roadbook")
        }
        let required = manifest.required
        for req in required where zip[req] == nil { throw Fail("Pack is missing \(req)") }
        let safe = manifest.name.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let dir = root.appendingPathComponent(safe.isEmpty ? "pack" : safe)
        let replacing = fm.fileExists(atPath: dir.path)
        let staging = root.appendingPathComponent(".staging-\(safe)")
        try? fm.removeItem(at: staging); try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        var wanted = required
        if !manifest.isTerrain, zip["roads.pmtiles"] != nil { wanted.append("roads.pmtiles") }
        let total = wanted.reduce(Int64(0)) { $0 + Int64(zip[$1]?.uncompressedSize ?? 0) }
        let free = (try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
        if free < total + (64 << 20) { throw Fail("Not enough space: pack needs \(total / (1024 * 1024)) MB, phone has \(free / (1024 * 1024)) MB free") }
        var done: Int64 = 0; var lastPct = -1
        for req in wanted {
            guard let e = zip[req] else { continue }
            let out = staging.appendingPathComponent(req)
            guard fm.createFile(atPath: out.path, contents: nil) else { throw Fail("Could not create \(req)") }
            let fh = try FileHandle(forWritingTo: out)
            defer { try? fh.close() }
            // the entries are stored, not deflated: this is a straight copy out of the zip, a megabyte at a time
            _ = try zip.extract(e, bufferSize: 1 << 20, skipCRC32: true) { data in
                try fh.write(contentsOf: data); done += Int64(data.count)
                let pct = total > 0 ? Int(min(100, Double(done) * 100 / Double(total))) : 0
                if pct != lastPct { lastPct = pct; DispatchQueue.main.async { progress("\(manifest.name): \(pct)%") } }
            }
        }
        // the new pack must open before it is allowed to replace anything
        if !manifest.isTerrain {
            do { _ = try Graph.companion.open(path: staging.appendingPathComponent("graph.bin").path) }
            catch { throw Fail("This pack's routing data is damaged (\(error.text)) — it was not installed") }
        }
        if replacing {
            lock.lock(); packs.first { $0.dir.standardizedFileURL.path == dir.standardizedFileURL.path }?.router = nil; multi = nil; multiFor = []; lock.unlock()
            let old = root.appendingPathComponent(".old-\(safe)"); try? fm.removeItem(at: old)
            do { try fm.moveItem(at: dir, to: old) } catch { throw Fail("Could not replace the old pack") }
            do { try fm.moveItem(at: staging, to: dir) } catch { try? fm.moveItem(at: old, to: dir); throw Fail("Could not move pack into place; the previous version was kept") }
            try? fm.removeItem(at: old)
        } else {
            do { try fm.moveItem(at: staging, to: dir) } catch { throw Fail("Could not move pack into place") }
        }
        scan()
        // (directory URLs from a listing carry a trailing slash and may be under /private; compare paths, not URLs)
        guard let p = packs.first(where: { $0.dir.standardizedFileURL.path == dir.standardizedFileURL.path }) else { throw Fail("Pack installed but not found") }
        return p
    }

    func delete(_ p: Pack) {
        lock.lock(); p.router = nil; multi = nil; multiFor = []; lock.unlock()
        try? FileManager.default.removeItem(at: p.dir); scan()
    }
}
