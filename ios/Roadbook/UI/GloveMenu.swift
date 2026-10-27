import SwiftUI

/// The glove menu: giant buttons in two columns, reaching up to the HUD, for a gloved thumb at speed.
struct GloveMenu: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject private var recorder = Recorder.shared
    let hudBottom: CGFloat

    struct Item: Identifiable { let id = UUID(); let label: String; let icon: String; var accent = false; var on = false; let action: () -> Void }

    private var items: [Item] {
        let p = model.prefs
        var items: [Item] = []
        if model.mode == .nav {
            items.append(Item(label: "END", icon: "xmark.circle.fill", accent: true) { model.endNavigation() })
            items.append(Item(label: p.sound ? "SOUND ON" : "SOUND OFF", icon: p.sound ? "speaker.wave.2.fill" : "speaker.slash.fill", on: p.sound) { model.toggleSound() })
            items.append(Item(label: p.batterySaver ? "BATTERY SAVER ON" : "BATTERY SAVER", icon: "battery.25percent", on: p.batterySaver) { model.setBatterySaver(!p.batterySaver) })
            if model.isTrack { items.append(Item(label: "ADD CUE HERE", icon: "plus.circle") { model.addCueHere() }) }
            else { items.append(Item(label: "TURN LIST", icon: "list.bullet") { model.sheet = .turnList }) }
            items.append(Item(label: recorder.running ? "RECORDING…" : "RECORD", icon: "record.circle", on: recorder.running) { recorder.running ? model.stopRecording() : model.startRecording() })
        } else {
            items.append(Item(label: "GO TO", icon: "magnifyingglass") { model.sheet = .search })
            items.append(Item(label: "TRACK", icon: "point.topleft.down.to.point.bottomright.curvepath") { model.sheet = .trackPicker })
            items.append(Item(label: "MAPS", icon: "map") { model.sheet = .maps })
            items.append(Item(label: recorder.running ? "RECORDING…" : "RECORD", icon: "record.circle", on: recorder.running) { recorder.running ? model.stopRecording() : model.startRecording() })
            if model.libraryOn { items.append(Item(label: "HIDE ROADBOOKS", icon: "books.vertical", on: true) { model.showLibrary(false) }) }
            if model.hasTerrain { items.append(Item(label: p.terrain ? "TERRAIN ON" : "TERRAIN OFF", icon: "mountain.2", on: p.terrain) { model.toggleTerrain() }) }
            items.append(Item(label: p.batterySaver ? "BATTERY SAVER ON" : "BATTERY SAVER", icon: "battery.25percent", on: p.batterySaver) { model.setBatterySaver(!p.batterySaver) })
        }
        items.append(Item(label: p.night ? "DAY MAP" : "NIGHT MAP", icon: p.night ? "sun.max.fill" : "moon.fill", on: p.night) { model.toggleNight() })
        items.append(Item(label: "LOCATE", icon: "location.fill") { model.locate() })
        items.append(Item(label: model.orientationLocked ? "UNLOCK SCREEN" : "LOCK SCREEN", icon: model.orientationLocked ? "lock.fill" : "lock.open", on: model.orientationLocked) { model.toggleOrientationLock() })
        return items
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                Color.black.opacity(0.55).ignoresSafeArea().onTapGesture { model.menuOpen = false }
                let list = items
                let rows = (list.count + 1) / 2
                let avail = geo.size.height - hudBottom - 150
                let itemH = min(132, max(84, avail / CGFloat(rows) - 10))
                VStack(spacing: 10) {
                    Text(model.mode == .nav ? (model.isTrack ? "FOLLOWING " + model.trackName.uppercased() : "NAVIGATING") : "ROADBOOK").font(.system(size: 13, weight: .bold)).tracking(2).foregroundStyle(Theme.sInkSoft)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(list) { it in
                            Button { model.haptic(1); model.menuOpen = false; it.action() } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: it.icon).font(.system(size: 30, weight: .semibold)).foregroundStyle(it.accent ? Color.white : (it.on ? Theme.sAccent : Theme.sInk))
                                    Text(it.label).font(.system(size: 19, weight: .bold)).tracking(0.5).multilineTextAlignment(.center).minimumScaleFactor(0.7).foregroundStyle(it.accent ? Color.white : Theme.sInk)
                                }
                                .frame(maxWidth: .infinity).frame(height: itemH)
                                .background(it.accent ? Theme.sAccent : Theme.sBarBg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Button("CLOSE") { model.menuOpen = false }.buttonStyle(BigButton())
                }
                .padding(16)
                .background(Theme.sCard, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .padding(.horizontal, 10).padding(.bottom, 10)
                .transition(.move(edge: .bottom))
            }
        }
        .transition(.opacity)
    }
}
