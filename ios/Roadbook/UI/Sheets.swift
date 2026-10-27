import SwiftUI
import UIKit
import FoldCore
import UniformTypeIdentifiers

/// Routes a Sheet value to its view.
struct SheetHost: View {
    @EnvironmentObject var model: AppModel
    let sheet: Sheet
    var body: some View {
        switch sheet {
        case .search: SearchSheet()
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
    @State private var q = ""
    @State private var hits: [Hit] = []
    @State private var shownMarks: [Mark] = []
    @State private var status = ""
    @State private var gen = 0
    @FocusState private var focused: Bool

    var body: some View {
        SheetFrame(title: "Go to") {
            VStack(spacing: 0) {
                TextField("Address, street or place", text: $q).font(.system(size: 20)).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.words).padding().focused($focused)
                if !status.isEmpty { Text(status).font(.system(size: 14)).foregroundStyle(Theme.sInkSoft).padding(.horizontal) }
                List {
                    ForEach(shownMarks) { m in
                        Button { model.sheet = nil; model.goToMark(m) } label: { Text("★ " + m.name + (m.note.isEmpty ? "" : " — " + m.note)).foregroundStyle(Theme.sInk) }
                    }
                    ForEach(Array(hits.enumerated()), id: \.offset) { _, h in
                        Button { model.sheet = nil; model.onSearchPick(h) } label: { Text(h.label).foregroundStyle(Theme.sInk) }
                    }
                }
                .listStyle(.plain)
            }
        }
        .onAppear { focused = true; if model.packs.packs.isEmpty { status = "No maps on this phone yet. Download one under MAPS." }; run("") }
        .onChange(of: q) { _, v in run(v) }
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
                        }
                        let n = marks.marks.count
                        HStack {
                            Text("Marks: \(n)")
                            Spacer()
                            Button("EXPORT GPX") { model.safe { let name = try model.marks.export(); model.showToast("Wrote \(name) to Roadbook's folder in Files") } }.buttonStyle(BigButton(compact: true)).disabled(n == 0)
                            Button("BURN") { confirmBurn = true }.buttonStyle(BigButton(compact: true)).disabled(n == 0)
                        }
                    }
                    Section("Roadbooks others shared") {
                        Text("The library on hondoroadbook.com, drawn over the map. Tap one to follow it or save it.").font(.system(size: 13)).foregroundStyle(Theme.sInkSoft)
                        Button("BROWSE ROADBOOKS") { model.sheet = nil; model.showLibrary(true) }.buttonStyle(BigButton())
                    }
                    Section {
                        Text("Map data © OpenStreetMap contributors · Basemap © Protomaps").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                        Text("Roadbook \(Device.version) · hondoroadbook.com").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                        Button("SEND FEEDBACK") { Files.feedback(extra: "Maps: " + model.packs.packs.map { $0.name }.joined(separator: ", ")) }.buttonStyle(BigButton())
                        Text("Roadbook stores your map packs, these settings, marks you save on purpose, and recordings you keep. The network is used only when you press a button that needs it: downloading packs, fetching or publishing a roadbook, browsing the library. Nothing about where you go leaves the phone.").font(.system(size: 12)).foregroundStyle(Theme.sInkSoft)
                    }
                }
                .id(tick)
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(exportedAs: "com.hondogarage.mappack"), .zip, .data], allowsMultipleSelection: true) { r in
                if case .success(let urls) = r { model.sheet = nil; model.importPacks(urls) }
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
        .onAppear { files = Files.list([".gpx"]) }
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
                Button("EDIT CUES") { model.sheet = nil; model.openEditor(track, url) }.buttonStyle(BigButton())
                if track.hasRoute { Button("EDIT ROUTE") { model.sheet = nil; model.rerouteGpx(track) }.buttonStyle(BigButton()) }
                Button("PUBLISH TO THE LIBRARY") { model.sheet = .publish(name: track.name, gpx: Gpx.shared.writeTrack(name: track.name, lats: track.lats, lons: track.lons, times: nil, cues: track.cues), recorded: true) }.buttonStyle(BigButton(compact: true))
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
        SheetFrame(title: existing == nil ? "Add cue" : "Edit cue") {
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
                        if let e = existing { Button("DELETE") { model.sheet = nil; model.cueDeleted(e) }.buttonStyle(BigButton(compact: true)) }
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
                Button("FOLLOW") { model.sheet = nil; model.withLibraryGpx(item) { tr, _ in model.followTrack(tr, nil) } }.buttonStyle(BigButton(accent: true))
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
