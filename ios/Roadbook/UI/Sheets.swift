import CoreLocation
import SwiftUI
import UIKit
import FoldCore
import UniformTypeIdentifiers
import AVFoundation
import ZIPFoundation

/// Routes a Sheet value to its view.
struct SheetHost: View {
    @EnvironmentObject var model: AppModel
    let sheet: Sheet
    var body: some View {
        switch sheet {
        case .search(let ask): SearchSheet(ask: ask)
        case .stops: StopsSheet()
        case .maps: MapsSheet()
        case .catalog: CatalogSheet()
        case .trackPicker: TrackPickerSheet()
        case .fetchCode: FetchCodeSheet()
        case .turnList: ListSheet(title: (model.destName.isEmpty ? "Route" : model.destName) + (model.route.map { " · " + Units.distance($0.length) + " · " + Units.time($0.timeSeconds) } ?? ""), lines: model.route.map { model.turnListLines($0) } ?? [], action: model.route.map { r -> (String, () -> Void) in ("SAVE / SHARE", { model.sheet = nil; model.saveOrShareRoute(r) }) })
        case .cueList: ListSheet(title: model.trackName + (model.route.map { " · " + Units.distance($0.length) } ?? ""), lines: model.cueListLines(), action: nil)
        case .modes: ModesSheet()
        case .cue(let lat, let lon, let existing): CueSheet(lat: lat, lon: lon, existing: existing)
        case .markEdit(let existing, let suggested, let lat, let lon): MarkEditSheet(existing: existing, suggested: suggested, lat: lat, lon: lon)
        case .markCard(let m): MarkCardSheet(mark: m)
        case .publish(let name, let gpx, let recorded): PublishSheet(name: name, gpx: gpx, recorded: recorded)
        case .libraryItem(let it): LibraryItemSheet(item: it)
        case .fileName(let suggest, let body, let done): FileNameSheet(suggest: suggest, body: body, done: done)
        case .trackLoaded(let tr, let url): TrackLoadedSheet(track: tr, url: url)
        case .shareCode(let gpx): ShareCodeSheet(gpx: gpx)
        case .scanCode: ScanCodeSheet()
        case .voice: VoiceSheet()
        }
    }
}

/// A sheet frame: title, close, content.
struct SheetFrame<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    @EnvironmentObject var model: AppModel
    var body: some View {
        NavigationStack {
            content()
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { model.sheet = nil } } }
        }
    }
}

// ---- GO TO: address / place search over every installed pack, with saved marks first --------------------------

struct SearchSheet: View {
    @EnvironmentObject var model: AppModel
    var ask: SearchAsk = .goTo
    @State private var q = ""
    @State private var hits: [Hit] = []
    @State private var shownMarks: [Mark] = []
    @State private var status = ""
    @State private var gen = 0
    @FocusState private var focused: Bool

    var body: some View {
        SheetFrame(title: ask.title) {
            VStack(spacing: 0) {
                TextField("Address, street or place", text: $q).font(.system(size: 20)).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.words).padding().focused($focused)
                if !status.isEmpty { Text(status).font(.system(size: 14)).foregroundStyle(Theme.sInkSoft).padding(.horizontal) }
                if let extra = ask.extra { Button(extra.0) { model.sheet = nil; extra.1() }.buttonStyle(BigButton(compact: true)).padding(.horizontal).padding(.bottom, 8) }
                List {
                    ForEach(shownMarks) { m in
                        Button { pick(CLLocationCoordinate2D(latitude: m.lat, longitude: m.lon), m.name) { model.goToMark(m) } } label: { Text("★ " + m.name + (m.note.isEmpty ? "" : " — " + m.note)).foregroundStyle(Theme.sInk) }
                    }
                    ForEach(Array(hits.enumerated()), id: \.offset) { _, h in
                        Button { pick(CLLocationCoordinate2D(latitude: h.lat, longitude: h.lon), h.label) { model.onSearchPick(h) } } label: { Text(h.label).foregroundStyle(Theme.sInk) }
                    }
                }
                .listStyle(.plain)
            }
        }
        .onAppear { focused = true; if model.packs.packs.isEmpty { status = "No maps on this phone yet. Download one under MAPS." }; run("") }
        .onChange(of: q) { _, v in run(v) }
    }

    /// a pick answers the plan's question, or goes to the pin panel
    private func pick(_ c: CLLocationCoordinate2D, _ label: String, orElse: () -> Void) {
        model.sheet = nil
        if let f = ask.onPick { f(c, label) } else { orElse() }
    }

    private func run(_ q: String) {
        gen += 1; let g = gen
        let nq = TextNorm.shared.normalize(s: q)
        shownMarks = model.marks.marks.filter { nq.isEmpty || TextNorm.shared.normalize(s: $0.name + " " + $0.note).contains(nq) }.sorted { $0.created > $1.created }
        if q.trimmingCharacters(in: .whitespaces).isEmpty { hits = []; return }
        status = "Searching…"
        let packs = model.packs.roadPacks
        model.worker.async {
            var scored: [(Int32, Hit)] = []
            for p in packs {
                if let s = p.loadSearch(), let res = try? s.queryScored(q: q, limit: 20) {
                    for pair in res { if let sc = pair.first, let h = pair.second { scored.append((sc.int32Value, h)) } }
                }
                if g != gen { return }
            }
            // rank across packs, not pack by pack: a city in Washington must beat a street in Montana
            scored.sort { $0.0 > $1.0 }
            let results = scored.prefix(40).map { $0.1 }
            DispatchQueue.main.async {
                if g != gen { return }
                hits = results; status = results.isEmpty ? "No matches" : ""
            }
        }
    }
}

// ---- MAPS: installed packs, download, import, units, marks, feedback ----------------------------------------------

struct MapsSheet: View {
    @EnvironmentObject var model: AppModel
    var body: some View { MapsInner(marks: model.marks) }

    struct MapsInner: View {
        @EnvironmentObject var model: AppModel
        @ObservedObject var marks: MarkStore
        @State private var importing = false
        @State private var confirmDelete: Pack?
        @State private var confirmBurn = false
        @State private var importingMarks = false
        @State private var tick = 0
        var body: some View {
            SheetFrame(title: "Maps on this phone") {
                List {
                    Section {
                        if model.packs.packs.isEmpty { Text("No maps on this phone yet.\n\nTap DOWNLOAD to fetch a region, or drop a .mappack file into Roadbook's folder in the Files app and IMPORT it.").foregroundStyle(Theme.sInkSoft) }
                        ForEach(model.packs.packs) { p in
                            HStack {
                                VStack(alignment: .leading) { Text(p.name).font(.system(size: 17)); Text("\(Units.mb(p.sizeBytes)) · \(p.manifest.created)" + (p.isTerrain ? " · hillshade" : "")).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }
                                Spacer()
                                Button("REMOVE") { confirmDelete = p }.buttonStyle(BigButton(compact: true))
                            }
                        }
                        ForEach(model.packs.broken, id: \.0) { b in Text("\(b.0): damaged (\(b.1)) — re-download or re-import").foregroundStyle(Theme.sAccent).font(.system(size: 14)) }
                        HStack(spacing: 8) {
                            Button("DOWNLOAD") { model.sheet = .catalog }.buttonStyle(BigButton(accent: true))
                            Button("IMPORT FILE") { importing = true }.buttonStyle(BigButton())
                        }
                    }
                    Section("Settings") {
                        HStack(spacing: 8) {
                            Button(model.prefs.metric ? "UNITS: KM / M" : "UNITS: MILES / FT") { model.setUnits(metric: !model.prefs.metric); tick += 1 }.buttonStyle(BigButton())
                            if model.hasTerrain { Button(model.prefs.terrain ? "TERRAIN: ON" : "TERRAIN: OFF") { model.toggleTerrain(); tick += 1 }.buttonStyle(BigButton()) }
                            Button(model.prefs.places ? "PLACES: ON" : "PLACES: OFF") { model.togglePlaces(); tick += 1 }.buttonStyle(BigButton())
                        }
                        Text("Places: fuel, camping, water, beds, repair and food from the map packs, drawn as you zoom in. Tap one to route to it.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        Button("VOICE…") { model.sheet = .voice }.buttonStyle(BigButton())
                        let n = marks.marks.count
                        HStack {
                            Text("Marks: \(n)")
                            Spacer()
                            Button("EXPORT GPX") { model.safe { let name = try model.marks.export(); model.showToast("Wrote \(name) to Roadbook's folder in Files") } }.buttonStyle(BigButton(compact: true)).disabled(n == 0)
                            Button("BURN") { confirmBurn = true }.buttonStyle(BigButton(compact: true)).disabled(n == 0)
                        }
                        Text("Marks are your own saved places, drawn on the map with their names. A copy, \"\(MarkStore.mirrorName)\", is kept in Roadbook's folder in the Files app (and so in your iCloud backup) and replaced on every change; to restore or add marks, open any GPX of waypoints with Roadbook, or IMPORT MARKS.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        Button("IMPORT MARKS") { importingMarks = true }.buttonStyle(BigButton())
                    }
                    Section("Roadbooks others shared") {
                        Text("The library on hondoroadbook.com, drawn over the map. Tap one to follow it or save it.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        Button("BROWSE ROADBOOKS") { model.sheet = nil; model.showLibrary(true) }.buttonStyle(BigButton())
                    }
                    Section {
                        Text("Map data © OpenStreetMap contributors · Basemap © Protomaps").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                        Text("Roadbook \(Device.version) · hondoroadbook.com").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                        Button("SEND FEEDBACK") { Files.feedback(extra: "Maps: " + model.packs.packs.map { $0.name }.joined(separator: ", ")) }.buttonStyle(BigButton())
                        HStack(spacing: 8) {
                            Button("TERMS") { model.openURL("https://hondoroadbook.com/terms.html") }.buttonStyle(BigButton())
                            Button("PRIVACY") { model.openURL("https://hondoroadbook.com/privacy.html") }.buttonStyle(BigButton())
                        }
                        Text("Roadbook stores your map packs, these settings, marks you save on purpose, and recordings you keep. The network is used only when you press a button that needs it: downloading packs or the natural voice, fetching or publishing a roadbook, browsing the library. Nothing about where you go leaves the phone.").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                    }
                }
                .id(tick)
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(exportedAs: "com.hondogarage.mappack"), .zip, .data], allowsMultipleSelection: true) { r in
                if case .success(let urls) = r { model.sheet = nil; model.importPacks(urls) }
            }
            .fileImporter(isPresented: $importingMarks, allowedContentTypes: [UTType(importedAs: "com.topografix.gpx"), .xml, .data]) { r in
                if case .success(let u) = r { model.sheet = nil; model.loadGpx(u) }
            }
            .alert("Remove \(confirmDelete?.name ?? "") from this phone?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
                Button("Remove", role: .destructive) { if let p = confirmDelete { model.deletePack(p) }; confirmDelete = nil }
                Button("Cancel", role: .cancel) { confirmDelete = nil }
            }
            .alert("Delete all \(model.marks.marks.count) marks from this phone? There is no undo.", isPresented: $confirmBurn) {
                Button("Burn", role: .destructive) { model.marks.burn(); model.drawMarks(); model.showToast("All marks deleted") }
                Button("Cancel", role: .cancel) {}
            }
        }
    }
}

// ---- VOICE: who reads the cues --------------------------------------------------------------------------------------

struct VoiceSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var tick = 0
    @State private var busy: String?
    @State private var downloader: Downloader?
    @State private var confirmRemove = false
    private let sample = "In half a mile, left onto US-287. Then cattle guard, caution."
    private var sp: Speech { Speech.shared }

    var body: some View {
        SheetFrame(title: "Voice") {
            List {
                Section("Natural voice") {
                    if NaturalVoice.installed {
                        Text("Prepared ahead of each cue; if a phrase is not ready in time, the iPhone voice below says it.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        ForEach(NaturalVoice.speakers, id: \.0) { s in
                            row(s.1, on: sp.naturalSid == s.0) { sp.useNatural(s.0) }
                        }
                        Button("REMOVE NATURAL VOICE") { confirmRemove = true }.buttonStyle(BigButton(compact: true))
                    } else if let b = busy {
                        HStack { ProgressView(); Text(b).padding(.leading, 8); Spacer(); Button("CANCEL") { downloader?.cancel() }.buttonStyle(BigButton(compact: true)) }
                    } else {
                        Text("Kokoro, a voice much closer to a person: a one-time download of about \(NaturalVoice.sizeMB) MB, kept on this phone and run offline. Nothing is sent.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        Button("DOWNLOAD (\(NaturalVoice.sizeMB) MB)") { download() }.buttonStyle(BigButton(accent: true))
                    }
                }
                Section("iPhone voice") {
                    row("Automatic (the best installed)", on: sp.naturalSid < 0 && sp.systemVoiceId == nil) { sp.useSystem(nil) }
                    ForEach(Speech.systemVoices, id: \.identifier) { v in
                        row(v.name + " · " + v.language + (v.quality == .premium ? " · Premium" : v.quality == .enhanced ? " · Enhanced" : ""), on: sp.naturalSid < 0 && sp.systemVoiceId == v.identifier) { sp.useSystem(v.identifier) }
                    }
                    Text("Better iPhone voices are free: Settings → Accessibility → Spoken Content → Voices → English, then download a Premium or Enhanced voice (Zoe, Ava, Evan, Nathan…). It appears here once installed.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                }
                Section {
                    Button("TEST") { sp.test(sample) }.buttonStyle(BigButton())
                    Text("Cues are read the way people say them: \"US-287\" as \"U S two eighty-seven\", a roll chart's capitals as words.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                    Text("Kokoro-82M · Apache-2.0 · hexgrad").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                }
            }
            .id(tick)
        }
        .alert("Remove the natural voice (\(NaturalVoice.sizeMB) MB) from this phone?", isPresented: $confirmRemove) {
            Button("Remove", role: .destructive) { sp.useSystem(sp.systemVoiceId); try? FileManager.default.removeItem(at: NaturalVoice.dir); tick += 1 }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ label: String, on: Bool, pick: @escaping () -> Void) -> some View {
        Button { pick(); tick += 1 } label: {
            HStack { Text(label).font(.system(size: 16)).foregroundStyle(Theme.sInk); Spacer(); if on { Image(systemName: "checkmark").foregroundStyle(Theme.sAccent) } }
        }
    }

    private func download() {
        let d = Downloader(); downloader = d; busy = "Starting…"
        let p = CatalogPack(id: NaturalVoice.pack, name: "Natural voice", region: "Voice", group: "", kind: "voice", url: NaturalVoice.url, bytes: NaturalVoice.bytes, built: "v1", notes: "")
        Task {
            do {
                let zip = try await d.download(p) { m in busy = m }
                busy = "Unpacking…"
                let dir = NaturalVoice.dir
                try await Task.detached {
                    let fm = FileManager.default
                    let tmp = dir.deletingLastPathComponent().appendingPathComponent(NaturalVoice.pack + ".tmp", isDirectory: true)
                    try? fm.removeItem(at: tmp); try fm.createDirectory(at: tmp, withIntermediateDirectories: true)
                    try fm.unzipItem(at: zip, to: tmp)
                    guard fm.fileExists(atPath: tmp.appendingPathComponent("model.int8.onnx").path) else { throw Fail("The voice pack is incomplete") }
                    try NaturalVoice.pack.write(to: tmp.appendingPathComponent("installed"), atomically: true, encoding: .utf8)
                    try? fm.removeItem(at: dir); try fm.moveItem(at: tmp, to: dir); try? fm.removeItem(at: zip)
                    var u = dir.deletingLastPathComponent(); var rv = URLResourceValues(); rv.isExcludedFromBackup = true; try? u.setResourceValues(rv)
                }.value
                busy = nil; sp.useNatural(NaturalVoice.speakers[0].0); tick += 1
                model.showToast("Natural voice installed")
            } catch is CancellationError {
                busy = nil; model.showToast("Paused — download again to resume")
            } catch {
                busy = nil; model.showToast("Download failed: " + error.localizedDescription, 6)
            }
        }
    }
}

// ---- DOWNLOAD: the catalog, regions grouped by country, kinds per region -----------------------------------------

struct CatalogSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var list: [CatalogPack]?
    @State private var error: String?
    @State private var region: String?

    var body: some View {
        SheetFrame(title: "Map packs") {
            Group {
                if let e = error {
                    VStack(spacing: 12) { Text("Catalog unavailable").font(.headline); Text(e + "\n\nYou can still copy .mappack files into Roadbook's folder in the Files app and import them under MAPS.").multilineTextAlignment(.center).foregroundStyle(Theme.sInkSoft) }.padding()
                } else if let list = list {
                    if list.isEmpty { Text("The catalog is empty: the first packs are still being built. Try again later, or import a .mappack file.").padding() }
                    else { regions(list) }
                } else { ProgressView("Fetching catalog…") }
            }
        }
        .task { do { list = try await Catalog.fetch() } catch { self.error = error.text } }
        .sheet(item: Binding(get: { region.map { RegionKey(name: $0) } }, set: { region = $0?.name })) { k in
            RegionSheet(region: k.name, kinds: (list ?? []).filter { $0.region == k.name }) { region = nil; model.sheet = nil }
        }
    }
    private struct RegionKey: Identifiable { let name: String; var id: String { name } }

    private func regions(_ list: [CatalogPack]) -> some View {
        let installed = Dictionary(model.packs.packs.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        let groups = Dictionary(grouping: list, by: { $0.region }).sorted { ($0.value[0].group, $0.key) < ($1.value[0].group, $1.key) }
        let byCountry = Dictionary(grouping: groups, by: { $0.value[0].group }).sorted { $0.key < $1.key }
        return List {
            ForEach(byCountry, id: \.key) { country, regs in
                Section(country.uppercased()) {
                    ForEach(regs, id: \.key) { name, kinds in
                        let have = kinds.filter { Catalog.state(installed, $0) == "installed" }.count, upd = kinds.filter { Catalog.state(installed, $0) == "update" }.count
                        let sub = kinds.map { $0.kindLabel + " " + Units.mb($0.bytes) }.joined(separator: " · ") + (upd > 0 ? " · update available" : have == kinds.count ? " · installed" : have > 0 ? " · \(have) of \(kinds.count) installed" : "")
                        Button { region = name } label: {
                            HStack { VStack(alignment: .leading) { Text(name).font(.system(size: 16)).foregroundStyle(Theme.sInk); Text(sub).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }; Spacer(); Image(systemName: "chevron.right").foregroundStyle(Theme.sInkSoft) }
                        }
                    }
                }
            }
            Section { Text("Pick a region, then the maps you want for it. Packs download to this phone and install themselves; an interrupted download resumes.").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft) }
        }
    }
}

/// One region: the kinds of map available for it, checked for download.
struct RegionSheet: View {
    @EnvironmentObject var model: AppModel
    let region: String
    let kinds: [CatalogPack]
    let onDone: () -> Void
    @State private var checked: Set<String> = []
    @State private var downloader: Downloader?

    private var sorted: [CatalogPack] { let order = ["roads", "terrain", "imagery"]; return kinds.sorted { (order.firstIndex(of: $0.kind) ?? 99) < (order.firstIndex(of: $1.kind) ?? 99) } }

    var body: some View {
        let installed = Dictionary(model.packs.packs.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        NavigationStack {
            List {
                ForEach(sorted) { p in
                    let st = Catalog.state(installed, p)
                    Button { if checked.contains(p.id) { checked.remove(p.id) } else { checked.insert(p.id) } } label: {
                        HStack(alignment: .top) {
                            Image(systemName: checked.contains(p.id) ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundStyle(Theme.sAccent)
                            VStack(alignment: .leading) {
                                Text(p.kindLabel + " · " + Units.mb(p.bytes) + (st.isEmpty ? "" : " · " + st)).foregroundStyle(Theme.sInk)
                                if !p.blurb.isEmpty { Text(p.blurb).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }
                            }
                        }
                    }
                }
                let missing = ["roads", "terrain"].filter { k in !kinds.contains { $0.kind == k } }.map { $0.capitalizedFirst }
                if !missing.isEmpty { Text("\(missing.joined(separator: ", ")) for \(region): not built yet").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }
                Button("DOWNLOAD") { download(sorted.filter { checked.contains($0.id) }) }.buttonStyle(BigButton(accent: true)).disabled(checked.isEmpty)
            }
            .navigationTitle(region).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { onDoneLocal() } } }
        }
        .onAppear { checked = Set(sorted.filter { Catalog.state(installed, $0) != "installed" }.map { $0.id }) }
    }
    @Environment(\.dismiss) private var dismiss
    private func onDoneLocal() { dismiss() }

    private func download(_ want: [CatalogPack]) {
        guard let p = want.first else { onDone(); return }
        let dl = Downloader(); downloader = dl
        model.sheet = nil
        model.progress = AppModel.ProgressState(title: "Downloading \(p.region) · \(p.kindLabel)", message: "Starting…", cancel: { dl.cancel() })
        Task {
            do {
                let file = try await dl.download(p) { msg in model.progress?.message = msg }
                await MainActor.run { model.progress = AppModel.ProgressState(title: "Installing \(p.region) · \(p.kindLabel)", message: "Importing…", cancel: nil) }
                let packs = model.packs; let progress: (String) -> Void = { s in DispatchQueue.main.async { model.progress?.message = s } }
                let pack = try await Task.detached { try packs.importPack(file, progress: progress) }.value
                try? FileManager.default.removeItem(at: file)
                await MainActor.run { model.progress = nil; model.showToast("Installed \(pack.name)"); model.onPacksChanged(); download(Array(want.dropFirst())) }
            } catch is CancellationError {
                await MainActor.run { model.progress = nil; model.showToast("Paused — download again to resume", 5); onDone() }
            } catch {
                await MainActor.run { model.progress = nil; model.info("Download failed", error.text); onDone() }
            }
        }
    }
}

// ---- TRACK: the GPX files in Roadbook's folder, or anywhere in Files, or by code from the planner -------------------

struct TrackPickerSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var files: [Files.Entry] = []
    @State private var browsing = false
    var body: some View {
        SheetFrame(title: "Follow a track") {
            List {
                Section {
                    Button("FETCH CODE FROM THE PLANNER") { model.sheet = .fetchCode }.buttonStyle(BigButton())
                    Button("SCAN A CODE FROM ANOTHER PHONE") { model.sheet = .scanCode }.buttonStyle(BigButton())
                    Button("BROWSE FILES…") { browsing = true }.buttonStyle(BigButton())
                }
                Section("GPX tracks in Roadbook's folder") {
                    if files.isEmpty { Text("No GPX files yet. Save a route or a recording here, fetch one by code, or drop files into Roadbook's folder in the Files app (or AirDrop a .gpx to this phone and open it with Roadbook).").font(.system(size: 14)).foregroundStyle(Theme.sInkSoft) }
                    ForEach(files) { f in
                        Button { model.sheet = nil; model.loadGpx(f.url) } label: {
                            VStack(alignment: .leading) { Text(f.name).foregroundStyle(Theme.sInk); Text("\(f.size / 1024) KB · \(f.modified.formatted(date: .abbreviated, time: .shortened))").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }
                        }
                    }
                    .onDelete { idx in for i in idx { try? FileManager.default.removeItem(at: files[i].url) }; files = Files.list([".gpx"]) }
                }
            }
        }
        .onAppear { files = Files.list([".gpx"]).filter { $0.name != MarkStore.mirrorName } }
        .fileImporter(isPresented: $browsing, allowedContentTypes: [UTType(importedAs: "com.topografix.gpx"), .xml, .data]) { r in
            if case .success(let u) = r { model.sheet = nil; model.loadGpx(u) }
        }
    }
}

struct FetchCodeSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var code = ""
    @State private var busy = false
    @FocusState private var focused: Bool
    var body: some View {
        SheetFrame(title: "Fetch a roadbook") {
            VStack(spacing: 16) {
                Text("The code the planner showed under SEND TO PHONE. It works for 24 hours.").foregroundStyle(Theme.sInkSoft)
                TextField("ABC 123", text: $code).font(.system(size: 28, weight: .bold, design: .monospaced)).multilineTextAlignment(.center).textInputAutocapitalization(.characters).autocorrectionDisabled().textFieldStyle(.roundedBorder).focused($focused)
                Button(busy ? "FETCHING…" : "FETCH") { fetch() }.buttonStyle(BigButton(accent: true)).disabled(busy)
                Spacer()
            }
            .padding()
        }
        .onAppear { focused = true }
    }
    private func fetch() {
        let c = code.uppercased().replacingOccurrences(of: "[^A-Z0-9]", with: "", options: .regularExpression)
        if c.count != 6 { model.showToast("A code is six letters and digits"); return }
        busy = true
        Task {
            do { let (name, text) = try await Library.fetchCode(c); await MainActor.run { busy = false; model.sheet = nil; model.onFetchedGpx(name, text) } }
            catch { await MainActor.run { busy = false; model.info("Nothing fetched", error.text) } }
        }
    }
}

/// A loaded GPX with a track: follow it, edit its cues, publish it, or re-route its points.
struct TrackLoadedSheet: View {
    @EnvironmentObject var model: AppModel
    let track: GpxTrack
    let url: URL?
    var body: some View {
        let n = track.cues.count
        SheetFrame(title: track.name) {
            VStack(spacing: 12) {
                Text(Units.distance(track.length) + (n > 0 ? " · \(n) cues" : " · no cues yet") + (track.hasRoute ? "\n\nThis file also carries its start, vias and end: EDIT ROUTE plans it again on your maps, where you can add or remove vias (tap a via to remove it), then GO or SAVE." : "")).multilineTextAlignment(.center)
                Button("FOLLOW") { model.sheet = nil; model.followTrack(track, url) }.buttonStyle(BigButton(accent: true))
                if n > 0 { Button("FOLLOW AS ROLL CHART") { model.sheet = nil; model.followTrack(track, url, chart: true) }.buttonStyle(BigButton()) }
                Button("EDIT CUES") { model.sheet = nil; model.openEditor(track, url) }.buttonStyle(BigButton())
                if track.hasRoute { Button("EDIT ROUTE") { model.sheet = nil; model.rerouteGpx(track) }.buttonStyle(BigButton()) }
                Button("SHARE BY CODE") { model.sheet = .shareCode(Gpx.shared.writeShared(t: track)) }.buttonStyle(BigButton(compact: true))
                Button("PUBLISH TO THE LIBRARY") { model.sheet = .publish(name: track.name, gpx: Gpx.shared.writeTrackChart(name: track.name, lats: track.lats, lons: track.lons, times: nil, cues: track.cues, chart: track.chart), recorded: true) }.buttonStyle(BigButton(compact: true))
                Spacer()
            }
            .padding()
        }
        .presentationDetents([.medium, .large])
    }
}

// ---- lists: the turn list, the cue list -----------------------------------------------------------------------

struct ListSheet: View {
    @EnvironmentObject var model: AppModel
    let title: String
    let lines: [(String, String)]
    let action: (String, () -> Void)?
    var body: some View {
        SheetFrame(title: title) {
            List {
                if lines.isEmpty { Text("No cues yet.").foregroundStyle(Theme.sInkSoft) }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                    HStack(alignment: .top) { Text(l.0).font(.system(size: 14, design: .monospaced)).frame(width: 72, alignment: .leading); Text(l.1).font(.system(size: 15)) }
                }
                if let a = action { Button(a.0, action: a.1).buttonStyle(BigButton()) }
            }
        }
    }
}

struct ModesSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var sel: Set<String> = []
    var body: some View {
        SheetFrame(title: "Modes to compare") {
            List {
                ForEach(Profiles.all, id: \.name) { p in
                    Button { if sel.contains(p.name) { sel.remove(p.name) } else { sel.insert(p.name) } } label: {
                        HStack(alignment: .top) {
                            Image(systemName: sel.contains(p.name) ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundStyle(Theme.sAccent)
                            VStack(alignment: .leading) { Text(p.label).foregroundStyle(Theme.sInk); Text(p.blurb).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft) }
                        }
                    }
                }
                Button("OK") { model.sheet = nil; model.setModes(Profiles.all.filter { sel.contains($0.name) }) }.buttonStyle(BigButton(accent: true))
            }
        }
        .onAppear { sel = Set(model.prefs.modeSet.map { $0.name }) }
    }
}

// ---- STOPS: the plan's points in order; move or remove a stop, add one by search, close the loop, reverse ----------

struct StopsSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var changed = false
    var body: some View {
        SheetFrame(title: "Stops") {
            VStack(spacing: 0) {
                Text(model.planTitle).font(.system(size: 14)).foregroundStyle(Theme.sInkSoft).lineLimit(3).padding(.horizontal).padding(.top, 8)
                List {
                    let pts = model.planPoints()
                    ForEach(Array(pts.enumerated()), id: \.offset) { i, p in
                        let isStart = i == 0, isEnd = i == pts.count - 1, vi = i - 1
                        HStack(spacing: 10) {
                            Text(isStart ? "START" : isEnd ? "END" : model.stopLetter(vi)).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.sInkSoft).frame(width: 48, alignment: .leading)
                            Text(p.1.isEmpty ? (isStart && model.planStart == nil ? "Where you are" : fmt(p.0)) : p.1).font(.system(size: 16)).foregroundStyle(Theme.sInk).lineLimit(2)
                            Spacer()
                            if !isStart && !isEnd {
                                Button { model.moveStop(vi, by: -1); changed = true } label: { Image(systemName: "chevron.up") }.buttonStyle(.borderless).disabled(vi == 0)
                                Button { model.moveStop(vi, by: 1); changed = true } label: { Image(systemName: "chevron.down") }.buttonStyle(.borderless).disabled(vi >= model.vias.count - 1)
                                Button { model.removeStop(vi); changed = true } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                            }
                        }
                        .font(.system(size: 18, weight: .semibold))
                    }
                }
                .listStyle(.plain)
                VStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Button("ADD STOP") { model.sheet = nil; model.addStopBySearch() }.buttonStyle(BigButton(compact: true))
                        Button(model.isLoop ? "OPEN LOOP" : "LOOP") { model.sheet = nil; model.toggleLoop() }.buttonStyle(BigButton(compact: true))
                        Button("REVERSE") { model.sheet = nil; model.reversePlan() }.buttonStyle(BigButton(compact: true))
                    }
                    Button("DONE") { model.sheet = nil; if changed { model.stopsChanged() } }.buttonStyle(BigButton(accent: true))
                }
                .padding()
            }
        }
    }
}

// ---- cues ----------------------------------------------------------------------------------------------------

/// Pick a cue: the same arrows the HUD draws, plus NOTE and DANGER, and words to say. Built for a gloved thumb at a
/// stop: one tap on an arrow is a complete cue; words are optional except for a note, which is nothing but words.
struct CueSheet: View {
    @EnvironmentObject var model: AppModel
    let lat: Double, lon: Double
    let existing: Cue?
    @State private var chosen: Turn = .left
    @State private var level: Int32 = 2
    @State private var words = ""
    @State private var needWords = false
    private static let turns: [Turn] = [.sharpLeft, .left, .slightLeft, .straight, .slightRight, .right, .sharpRight, .keepLeft, .uturn, .keepRight, .note, .danger]

    var body: some View {
        SheetFrame(title: existing == nil || (model.planCueTemplate && existing?.turn == .note) ? "Add cue" : model.planCueTemplate ? "Reword this turn" : "Edit cue") {
            ScrollView {
                VStack(spacing: 12) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                        ForEach(CueSheet.turns, id: \.name) { t in
                            VStack(spacing: 2) {
                                ArrowView(turn: t, exit: 0, progress: 1).frame(height: 70)
                                Text(t.spoken.uppercased()).font(.system(size: 10, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                            }
                            .padding(4).opacity(t == chosen ? 1 : 0.35)
                            .background(t == chosen ? Theme.sBarBg : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                            .onTapGesture { chosen = t }
                        }
                    }
                    if chosen == .danger {
                        HStack { Text("Severity").font(.system(size: 14)); ForEach(1...3, id: \.self) { l in Button(String(repeating: "!", count: l)) { level = Int32(l) }.buttonStyle(BigButton(compact: true)).opacity(level == Int32(l) ? 1 : 0.4) } }
                    }
                    TextField(chosen == .note ? "What to say here" : chosen == .danger ? "What the danger is (optional)" : "Words to say (optional)", text: $words).font(.system(size: 18)).textFieldStyle(.roundedBorder).autocorrectionDisabled()
                    if needWords { Text("A note needs words").foregroundStyle(Theme.sAccent).font(.system(size: 13)) }
                    HStack(spacing: 8) {
                        Button("SAVE") { save() }.buttonStyle(BigButton(accent: true))
                        if let e = existing, !model.planCueTemplate { Button("DELETE") { model.sheet = nil; model.cueDeleted(e) }.buttonStyle(BigButton(compact: true)) }
                    }
                }
                .padding()
            }
        }
        .onAppear { if let e = existing { chosen = e.turn; level = e.level > 0 ? e.level : 2; words = e.note } }
    }
    private func save() {
        let w = words.trimmingCharacters(in: .whitespaces)
        if chosen == .note && w.isEmpty { needWords = true; return }
        let c = Cue(lat: lat, lon: lon, turn: chosen, note: w, level: chosen == .danger ? level : 0)
        model.sheet = nil
        model.cueSaved(c, replacing: existing)
    }
}

// ---- marks ---------------------------------------------------------------------------------------------------

struct MarkEditSheet: View {
    @EnvironmentObject var model: AppModel
    let existing: Mark?
    let suggested: String
    let lat: Double, lon: Double
    @State private var name = ""
    @State private var note = ""
    @FocusState private var focused: Bool
    var body: some View {
        SheetFrame(title: existing == nil ? "Save mark" : "Edit mark") {
            VStack(spacing: 12) {
                TextField("Name", text: $name).font(.system(size: 18)).textFieldStyle(.roundedBorder).focused($focused)
                TextField("Note (water here, gate code, who told you about it…)", text: $note, axis: .vertical).lineLimit(2...6).textFieldStyle(.roundedBorder)
                Button("SAVE") {
                    let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Mark" : name.trimmingCharacters(in: .whitespaces)
                    model.sheet = nil
                    model.safe {
                        if let e = existing { try model.marks.update(e, name: n, note: note.trimmingCharacters(in: .whitespaces)); model.drawMarks() }
                        else { try model.marks.add(n, note.trimmingCharacters(in: .whitespaces), lat, lon); model.drawMarks(); model.clearPin(); model.showToast("Saved: \(n)") }
                    }
                }.buttonStyle(BigButton(accent: true))
                Spacer()
            }
            .padding()
        }
        .onAppear { name = existing?.name ?? suggested; note = existing?.note ?? ""; focused = true }
        .presentationDetents([.medium])
    }
}

struct MarkCardSheet: View {
    @EnvironmentObject var model: AppModel
    let mark: Mark
    @State private var confirm = false
    var body: some View {
        SheetFrame(title: "★ " + mark.name) {
            VStack(alignment: .leading, spacing: 12) {
                if !mark.note.isEmpty { Text(mark.note).font(.system(size: 16)) }
                Text(String(format: "%.5f, %.5f", mark.lat, mark.lon)).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                Button("ROUTE HERE") { model.sheet = nil; model.routeTo(.init(latitude: mark.lat, longitude: mark.lon), mark.name) }.buttonStyle(BigButton(accent: true))
                HStack(spacing: 8) {
                    Button("EDIT") { model.sheet = .markEdit(existing: mark, suggested: mark.name, lat: mark.lat, lon: mark.lon) }.buttonStyle(BigButton())
                    Button("DELETE") { confirm = true }.buttonStyle(BigButton())
                }
                Spacer()
            }
            .padding()
        }
        .presentationDetents([.medium])
        .alert("Delete \(mark.name)?", isPresented: $confirm) {
            Button("Delete", role: .destructive) { model.sheet = nil; model.safe { try model.marks.remove(mark); model.drawMarks(); model.showToast("Deleted \(mark.name)") } }
            Button("Cancel", role: .cancel) {}
        }
    }
}

// ---- the library ---------------------------------------------------------------------------------------------

struct PublishSheet: View {
    @EnvironmentObject var model: AppModel
    let name: String, gpx: String, recorded: Bool
    @State private var n = ""
    @State private var trim = 0
    @State private var busy = false
    private let trims = [(0, "Keep the whole track"), (250, "Trim 250 m off each end"), (500, "Trim 500 m off each end"), (1000, "Trim 1 km off each end")]
    var body: some View {
        SheetFrame(title: "Publish to the library") {
            List {
                Text("Anyone can then find this roadbook on hondoroadbook.com and in BROWSE ROADBOOKS, and ride it. The published file keeps only the line and the cues: no times, no device, nothing about you. Trim the ends if the track starts at your door.").font(.system(size: 15)).foregroundStyle(Theme.sInkSoft)
                TextField("Name", text: $n).font(.system(size: 17))
                ForEach(trims, id: \.0) { t in
                    Button { trim = t.0 } label: { HStack { Image(systemName: trim == t.0 ? "largecircle.fill.circle" : "circle").foregroundStyle(Theme.sAccent); Text(t.1).foregroundStyle(Theme.sInk) } }
                }
                Button(busy ? "PUBLISHING…" : "PUBLISH") { publish() }.buttonStyle(BigButton(accent: true)).disabled(busy)
            }
        }
        .onAppear { n = name; trim = recorded ? 500 : 0 }
    }
    private func publish() {
        busy = true
        let nm = n.trimmingCharacters(in: .whitespaces).isEmpty ? name : n.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let p = try await Library.publish(name: nm, gpx: gpx, trim: trim)
                await MainActor.run {
                    busy = false; model.sheet = nil
                    model.ask("Published", "\(p.name) · \(Units.distance(p.length)) · \(p.cues) cues\n\n\(p.page)\n\nIf it should come down, email wiley@machinenusa.com with the link.",
                              [Dialog.Btn(label: "COPY LINK") { UIPasteboard.general.string = p.page }, Dialog.Btn(label: "CLOSE", role: .cancel) {}])
                }
            } catch { await MainActor.run { busy = false; model.info("Not published", error.text) } }
        }
    }
}

struct LibraryItemSheet: View {
    @EnvironmentObject var model: AppModel
    let item: LibraryItem
    var body: some View {
        let surf = item.unpavedShare.isNaN ? "" : " · \(Int((item.unpavedShare * 100).rounded()))% unpaved"
        let modeLabel = Profiles.byName(item.mode).map { " · " + $0.label } ?? ""
        SheetFrame(title: item.name) {
            VStack(spacing: 12) {
                Text(Units.distance(item.lengthM) + " · \(item.cues) cues" + modeLabel + surf + (item.created.isEmpty ? "" : "\nPublished \(item.created)")).multilineTextAlignment(.center)
                Button("OPEN") { model.sheet = nil; model.withLibraryGpx(item) { tr, _ in model.onGpxLoaded(tr, nil) } }.buttonStyle(BigButton(accent: true))
                Button("SAVE") { model.sheet = nil; model.withLibraryGpx(item) { _, text in model.writeToFolder((item.name.fileSafe.isEmpty ? "Roadbook" : item.name.fileSafe) + ".gpx", text) } }.buttonStyle(BigButton())
                Spacer()
            }
            .padding()
        }
        .presentationDetents([.medium])
    }
}

/// Asks for a file name and writes into Roadbook's folder; done gets true only after the bytes are on disk.
struct FileNameSheet: View {
    @EnvironmentObject var model: AppModel
    let suggest: String
    let body_: () -> String
    let done: (Bool) -> Void
    @State private var name = ""
    @FocusState private var focused: Bool
    init(suggest: String, body: @escaping () -> String, done: @escaping (Bool) -> Void) { self.suggest = suggest; self.body_ = body; self.done = done }
    var body: some View {
        SheetFrame(title: "File name") {
            VStack(spacing: 12) {
                TextField("Name", text: $name).font(.system(size: 18)).textFieldStyle(.roundedBorder).focused($focused)
                Button("SAVE") {
                    let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? suggest : name.trimmingCharacters(in: .whitespaces)
                    model.sheet = nil
                    let base = n.lowercased().hasSuffix(".gpx") ? String(n.dropLast(4)) : n
                    done(model.writeToFolder(base.fileSafe + ".gpx", body_()))
                }.buttonStyle(BigButton(accent: true))
                Button("Cancel") { model.sheet = nil; done(false) }.buttonStyle(BigButton())
                Spacer()
            }
            .padding()
        }
        .onAppear { name = suggest; focused = true }
        .interactiveDismissDisabled()
        .presentationDetents([.medium])
    }
}

// ---- SHARE BY CODE: a route as a moving QR code for a phone beside this one, read by the camera; offline ----------

/// DEFLATE for the share code (raw RFC 1951, the same as Android's Deflater/Inflater with nowrap), and byte arrays across to the core.
enum ShareCodeIO {
    static func bytes(_ a: KotlinByteArray) -> Data { var d = Data(count: Int(a.size)); for i in 0..<a.size { d[Int(i)] = UInt8(bitPattern: a.get(index: i)) }; return d }
    static func kotlin(_ d: Data) -> KotlinByteArray { let a = KotlinByteArray(size: Int32(d.count)); for (i, b) in d.enumerated() { a.set(index: Int32(i), value: Int8(bitPattern: b)) }; return a }
    static let deflate: (KotlinByteArray) -> KotlinByteArray? = { b in (try? (bytes(b) as NSData).compressed(using: .zlib)).map { kotlin($0 as Data) } }
    static let inflate: (KotlinByteArray) -> KotlinByteArray? = { b in (try? (bytes(b) as NSData).decompressed(using: .zlib)).map { kotlin($0 as Data) } }
}

/// The code on screen: big, bright, a few frames a second until DONE.
struct ShareCodeSheet: View {
    @EnvironmentObject var model: AppModel
    let gpx: String
    @State private var enc: QrShare.Encoder? = nil
    @State private var name = ""
    @State private var code: QrCode? = nil
    @State private var frame: Int32 = 0
    /// frames a second: a phone camera reads 6 easily; slower helps an old phone, faster a long track
    @State private var fps = 6
    @State private var savedBrightness: CGFloat = 0.5
    @State private var savedIdle = false

    var body: some View {
        VStack(spacing: 10) {
            Text(name).font(.system(size: 20, weight: .bold)).foregroundStyle(.black)
            Text("On the other phone: ≡ → TRACK → SCAN A CODE, and point it here. Works offline, Android or iPhone.").font(.system(size: 14)).foregroundStyle(Color(white: 0.27)).multilineTextAlignment(.center)
            Canvas { ctx, size in
                guard let q = code else { return }
                let n = CGFloat(q.size + 8), m = floor(min(size.width, size.height) / n)
                guard m > 0 else { return }
                let ox = (size.width - m * n) / 2 + 4 * m, oy = (size.height - m * n) / 2 + 4 * m
                var p = Path()
                for y in 0..<q.size { for x in 0..<q.size where q.dark(x: x, y: y) { p.addRect(CGRect(x: ox + CGFloat(x) * m, y: oy + CGFloat(y) * m, width: m, height: m)) } }
                ctx.fill(p, with: .color(.black))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if let e = enc {
                Text("\(e.k) \(e.k == 1 ? "frame" : "frames") · \(String(format: "%.1f", Double(e.message.size) / 1024)) kB · " + (Double(e.k + 3) / Double(fps) < 2 ? "a second or two" : "about \(Int(ceil(Double(e.k + 3) / Double(fps)))) s") + " to read · \(fps) a second")
                    .font(.system(size: 13)).foregroundStyle(Color(white: 0.27))
            }
            HStack(spacing: 10) {
                Button("SLOWER") { fps = max(2, fps - 2) }.buttonStyle(BigButton(compact: true))
                Button("FASTER") { fps = min(12, fps + 2) }.buttonStyle(BigButton(compact: true))
                Button("DONE") { model.sheet = nil }.buttonStyle(BigButton(accent: true, compact: true))
            }
        }
        .padding()
        .background(Color.white.ignoresSafeArea())
        .presentationDetents([.large])
        .onAppear {
            guard let tr = try? Gpx.shared.parse(text: gpx) else { model.sheet = nil; model.showToast("Nothing to share in that file"); return }
            name = tr.name
            let e = QrShare.Encoder(message: QrShare.shared.message(t: tr, deflate: ShareCodeIO.deflate), chunk: QrShare.shared.chunkFor(version: 20))
            enc = e; code = e.code(i: 0)
            savedBrightness = UIScreen.main.brightness; UIScreen.main.brightness = 1
            savedIdle = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear { UIScreen.main.brightness = savedBrightness; UIApplication.shared.isIdleTimerDisabled = savedIdle }
        .task(id: fps) {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(1_000_000_000 / max(1, fps)))
                guard let e = enc else { continue }
                frame += 1
                code = e.code(i: frame)
            }
        }
    }
}

/// The camera, reading frames until the message is whole; the GPX is then saved and opened like a fetched one.
struct ScanCodeSheet: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var scan = CodeScanner()
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            CameraPreview(session: scan.session).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 8) {
                Text(scan.status).font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                ProgressView(value: Double(scan.known), total: Double(max(1, scan.k))).tint(Theme.sAccent)
                Button("CANCEL") { model.sheet = nil }.buttonStyle(BigButton(compact: true))
            }
            .padding()
            .background(Color.black.opacity(0.8))
        }
        .presentationDetents([.large])
        .onAppear {
            scan.start(done: { name, gpx in model.sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { model.onFetchedGpx(name, gpx) } },
                       fail: { msg in model.sheet = nil; model.showToast(msg, 5) })
        }
        .onDisappear { scan.stop() }
    }
}

final class CodeScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()
    @Published var known = 0
    @Published var k = 0
    @Published var status = "Point the camera at the code on the other phone"
    private let dec = QrShare.Decoder()
    private var done: ((String, String) -> Void)?
    private var fail: ((String) -> Void)?
    private var finished = false
    private var configured = false

    func start(done: @escaping (String, String) -> Void, fail: @escaping (String) -> Void) {
        self.done = done; self.fail = fail
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async { if ok { self.configure() } else { fail("Scanning a code needs the camera: allow it in Settings → Roadbook") } }
        }
    }
    private func configure() {
        guard !configured else { return }
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back), let input = try? AVCaptureDeviceInput(device: dev) else { fail?("The camera did not start"); return }
        session.beginConfiguration()
        if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
        guard session.canAddInput(input) else { session.commitConfiguration(); fail?("The camera did not start"); return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { session.commitConfiguration(); fail?("The camera did not start"); return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        session.commitConfiguration()
        configured = true
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }
    func stop() { let s = session; DispatchQueue.global(qos: .userInitiated).async { if s.isRunning { s.stopRunning() } } }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !finished else { return }
        for case let m as AVMetadataMachineReadableCodeObject in objects {
            if let s = m.stringValue, dec.add(text: s) { known = Int(dec.known); k = Int(dec.k); status = "Reading… \(known) of \(k)" }
        }
        guard let msg = dec.message else { return }
        finished = true
        do {
            let tr = try QrShare.shared.track(m: msg, inflate: ShareCodeIO.inflate)
            status = "Got it: " + tr.name
            stop()
            done?(tr.name, Gpx.shared.writeShared(t: tr))
        } catch { fail?("That code could not be read") }
    }
}

/// the camera's live picture, filling the sheet
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
    func makeUIView(context: Context) -> PreviewView { let v = PreviewView(); v.preview.session = session; v.preview.videoGravity = .resizeAspectFill; return v }
    func updateUIView(_ v: PreviewView, context: Context) {}
}
