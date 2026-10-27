import Foundation
import UIKit

/// Where things live. Documents is the folder the rider sees in the Files app (tracks, saved routes, exported
/// marks, map packs dropped in by hand); Application Support holds what the app keeps for itself (packs, marks,
/// the checkpoint, the recording journal).
enum Files {
    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var support: URL {
        let u = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
        return u
    }
    static var crashFile: URL { support.appendingPathComponent("last-crash.txt") }
    static var share: URL { let d = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true); try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true); return d }

    struct Entry: Identifiable, Hashable { let url: URL; let name: String; let size: Int64; let modified: Date; var id: URL { url } }

    /// Files in Documents with one of the extensions, newest first.
    static func list(_ exts: [String]) -> [Entry] {
        let fm = FileManager.default
        let urls = (try? fm.contentsOfDirectory(at: documents, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey], options: [.skipsHiddenFiles])) ?? []
        return urls.filter { u in exts.contains { u.lastPathComponent.lowercased().hasSuffix($0) } }
            .map { u in let rv = try? u.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]); return Entry(url: u, name: u.lastPathComponent, size: Int64(rv?.fileSize ?? 0), modified: rv?.contentModificationDate ?? .distantPast) }
            .sorted { $0.modified > $1.modified }
    }

    /// A name that does not collide with a file already in Documents.
    static func unique(_ name: String) -> URL {
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var u = documents.appendingPathComponent(name); var n = 2
        while FileManager.default.fileExists(atPath: u.path) { u = documents.appendingPathComponent("\(base) \(n).\(ext)"); n += 1 }
        return u
    }

    /// Writes a new file into Documents and reads it back to be sure: a full disk must not pass as saved.
    @discardableResult
    static func write(_ name: String, _ text: String) throws -> URL {
        let u = unique(name)
        let bytes = Data(text.utf8)
        try bytes.write(to: u, options: .atomic)
        guard let back = try? Data(contentsOf: u), back == bytes else { try? FileManager.default.removeItem(at: u); throw Fail("File is incomplete (read back failed)") }
        return u
    }

    /// Reads a GPX chosen in the Files picker (a security-scoped URL) with a size cap, without loading a huge file first.
    static func readText(_ url: URL, cap: Int = 64 << 20) throws -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var data = Data()
        let fh = try FileHandle(forReadingFrom: url)
        defer { try? fh.close() }
        while let chunk = try fh.read(upToCount: 1 << 16), !chunk.isEmpty {
            data.append(chunk)
            if data.count > cap { throw Fail("GPX is larger than 64 MB") }
        }
        return String(decoding: data, as: UTF8.self)
    }

    /// Copies a picked file into a private spot so it can be opened at leisure (packs are large; no cap).
    static func stage(_ url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent("import-" + url.lastPathComponent)
        try? FileManager.default.removeItem(at: dst)
        try FileManager.default.copyItem(at: url, to: dst)
        return dst
    }

    /// The share sheet for a text file (AirDrop, Mail, Files…).
    static func shareText(_ fileName: String, _ text: String) {
        let f = share.appendingPathComponent(fileName)
        try? text.write(to: f, atomically: true, encoding: .utf8)
        shareURL(f)
    }
    static func shareURL(_ f: URL) {
        let vc = UIActivityViewController(activityItems: [f], applicationActivities: nil)
        UIApplication.top?.present(vc, animated: true)
    }

    /// Opens the rider's mail app with a feedback message started: version, phone, and the last crash if there
    /// was one. Nothing is sent until they press send.
    static func feedback(extra: String = "") {
        let crash = try? String(contentsOf: crashFile, encoding: .utf8)
        var body = "What happened:\n\n\n— Roadbook \(Device.version) · \(Device.model) · iOS \(Device.osVersion)\n"
        if !extra.isEmpty { body += extra + "\n" }
        if let c = crash { body += "\nThe app's last crash (delete this if you would rather not send it):\n" + c }
        let q = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+"))
        let s = "mailto:wiley@machinenusa.com?subject=" + ("Roadbook feedback".addingPercentEncoding(withAllowedCharacters: q) ?? "") + "&body=" + (body.addingPercentEncoding(withAllowedCharacters: q) ?? "")
        if let u = URL(string: s) { UIApplication.shared.open(u) }
    }
}

extension UIApplication {
    /// the view controller to present sheets from
    static var top: UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard var vc = scenes.flatMap({ $0.windows }).first(where: { $0.isKeyWindow })?.rootViewController else { return nil }
        while let p = vc.presentedViewController { vc = p }
        return vc
    }
}
