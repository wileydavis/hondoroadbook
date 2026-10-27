import SwiftUI
import FoldCore

/// The one screen: the map, the HUD over it, the panel for the current mode at the bottom, the glove menu.
struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Inner(hud: model.hud, prefs: model.prefs)
    }

    /// (the real body, with the model's own observed objects)
    private struct Inner: View {
        @EnvironmentObject var model: AppModel
        @ObservedObject var hud: HudState
        @ObservedObject var prefs: Prefs
        @ObservedObject private var recorder = Recorder.shared

        var body: some View {
            GeometryReader { geo in
                ZStack {
                    MapViewRep(controller: model.map).ignoresSafeArea()
                    // the dark HUD of battery saver: the map black, the next cue in grey
                    if model.dimmed {
                        Color.black.ignoresSafeArea()
                            .overlay(Text(hud.darkText).font(.system(size: 28, weight: .semibold)).multilineTextAlignment(.center).foregroundStyle(Color(white: 0.6)).padding(24))
                            .onTapGesture { model.wakeFromDim() }
                    }
                    VStack(spacing: 0) {
                        topArea
                        Spacer(minLength: 0)
                        bottomArea(geo)
                    }
                    .padding(.horizontal, 12)
                    sideButtons
                    if let t = model.toast {
                        VStack { Spacer(); Text(t).font(.system(size: 15, weight: .medium)).multilineTextAlignment(.center).foregroundStyle(Theme.sInk).padding(.horizontal, 16).padding(.vertical, 10).glass().padding(.bottom, bottomInset).padding(.horizontal, 24) }
                            .transition(.opacity).allowsHitTesting(false)
                    }
                    if model.menuOpen { GloveMenu(hudBottom: hud.visible ? 150 : 20) }
                    if let p = model.progress { ProgressOverlay(state: p) }
                }
                .animation(.easeInOut(duration: 0.2), value: model.toast)
            }
            .alert(model.dialog?.title ?? "", isPresented: Binding(get: { model.dialog != nil }, set: { if !$0 { model.dialog = nil } }), presenting: model.dialog) { d in
                ForEach(d.buttons) { b in Button(b.label, role: b.role, action: b.action) }
            } message: { d in Text(d.message) }
            .sheet(item: $model.sheet) { s in SheetHost(sheet: s) }
        }

        private var bottomInset: CGFloat {
            switch model.mode { case .nav: return 120; case .pin: return 170; case .compare: return 260; case .edit: return 180; default: return 90 }
        }

        @ViewBuilder private var topArea: some View {
            VStack(spacing: 8) {
                if model.mode == .nav {
                    if hud.visible { HudView(hud: hud).padding(.top, 8) }
                    if hud.offVisible {
                        HStack(spacing: 12) {
                            Text(hud.offText).font(.system(size: 22, weight: .heavy)).foregroundStyle(Theme.sAccent).lineLimit(1).minimumScaleFactor(0.6)
                            Spacer()
                            if hud.showReroute { Button(hud.rerouteLabel) { model.rerouteNow() }.buttonStyle(BigButton(accent: true, compact: true)) }
                        }
                        .padding(14).glass().padding(.top, 8)
                    }
                }
                if model.mode == .edit {
                    Text(model.editLabel).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.sInk).multilineTextAlignment(.center).padding(12).glass().padding(.top, 8)
                }
                if recorder.running && model.mode != .compare && model.mode != .pin {
                    HStack(spacing: 10) {
                        Text(model.recStatus).font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.sAccent).lineLimit(1)
                        Spacer()
                        Button("CUE") { model.addCueHere() }.buttonStyle(BigButton(compact: true))
                        Button("STOP") { model.stopRecording() }.buttonStyle(BigButton(accent: true, compact: true))
                    }
                    .padding(10).glass().padding(.top, 8)
                }
            }
        }

        @ViewBuilder private func bottomArea(_ geo: GeometryProxy) -> some View {
            VStack(spacing: 8) {
                HStack { ScaleView(hud: hud).padding(.leading, 4); Spacer() }
                switch model.mode {
                case .pin: PinPanel()
                case .compare: ComparePanel()
                case .nav: NavPanel(hud: hud)
                case .edit: EditPanel()
                case .idle: EmptyView()
                }
                if model.mode == .idle || model.mode == .nav {
                    HStack {
                        Spacer()
                        Button { model.haptic(1); model.menuOpen = true } label: {
                            Image(systemName: "line.3.horizontal").font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.sButtonText)
                                .frame(width: 76, height: 76).background(Theme.sButton, in: Circle()).shadow(color: .black.opacity(0.25), radius: 6, y: 3)
                        }
                        .opacity(model.menuOpen ? 0 : 1)
                        Spacer()
                    }
                }
            }
            .padding(.bottom, 12)
        }

        /// locate / night / lock / sound, stacked at the right edge
        private var sideButtons: some View {
            VStack(spacing: 10) {
                Spacer()
                if model.mode == .nav { RoundButton(system: prefs.sound ? "speaker.wave.2.fill" : "speaker.slash.fill", on: prefs.sound) { model.toggleSound() } }
                RoundButton(system: "location.fill") { model.locate() }
                RoundButton(system: prefs.night ? "sun.max.fill" : "moon.fill", on: prefs.night) { model.toggleNight() }
                RoundButton(system: model.orientationLocked ? "lock.fill" : "lock.open", on: model.orientationLocked) { model.toggleOrientationLock() }
                if model.mode == .idle { RoundButton(system: "record.circle", on: recorder.running) { recorder.running ? model.stopRecording() : model.startRecording() } }
                Spacer().frame(height: bottomInset + 60)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.trailing, 12)
            .opacity(model.menuOpen || model.dimmed ? 0 : 1)
        }
    }
}

/// Long-press result: route here, go by here, start here, save the point.
struct PinPanel: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text(model.pinLabel).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.sInk).lineLimit(2)
                Spacer()
                Button { model.savePin() } label: { Image(systemName: "star").font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.sInk).frame(width: 40, height: 40) }
            }
            HStack(spacing: 8) {
                Button("ROUTE HERE") { if let p = model.pin { model.routeTo(p, model.pinName) } }.buttonStyle(BigButton(accent: true))
                if model.destination != nil && !model.isTrack { Button("VIA") { if let p = model.pin { model.addVia(p, model.pinName) } }.buttonStyle(BigButton(compact: true)) }
                Button("FROM") { if let p = model.pin { model.setStart(p) } }.buttonStyle(BigButton(compact: true))
                Button("✕") { model.clearPin() }.buttonStyle(BigButton(compact: true))
            }
        }
        .padding(14).glass()
    }
}

/// Every enabled mode routed and listed; tap a row to pick, GO to start.
struct ComparePanel: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(spacing: 6) {
            ForEach(model.prefs.modeSet, id: \.name) { p in
                let r = model.candidate(p)
                HStack(spacing: 12) {
                    Rectangle().fill(Color(Profiles.color(p))).frame(width: 18, height: 6)
                    Text(p.label).font(.system(size: 16, weight: p == model.profile ? .bold : .regular)).foregroundStyle(Theme.sInk)
                    Spacer()
                    Text(r.map { Units.distance($0.length) + " · " + Units.time($0.timeSeconds) } ?? (model.compareInProgress && !model.candidates.contains(where: { $0.0.ordinal > p.ordinal }) ? "…" : "no route"))
                        .font(.system(size: 15)).foregroundStyle(Theme.sInkSoft)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(p == model.profile && r != nil ? Theme.sBarBg : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                .opacity(r == nil ? 0.5 : 1)
                .contentShape(Rectangle())
                .onTapGesture { if r != nil { model.selectCandidate(p) } }
            }
            HStack(spacing: 8) {
                Button("GO") { model.goWithSelected() }.buttonStyle(BigButton(accent: true))
                Button("MODES") { model.sheet = .modes }.buttonStyle(BigButton(compact: true))
                Button("SAVE") { if let r = model.candidate(model.profile) { model.saveOrShareRoute(r) } else { model.showToast("Pick a route first") } }.buttonStyle(BigButton(compact: true))
                Button("✕") { model.cancelCompare() }.buttonStyle(BigButton(compact: true))
            }
        }
        .padding(12).glass()
    }
}

/// The bottom card while navigating: what is left, the speed; tap for the turn list.
struct NavPanel: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var hud: HudState
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(hud.remaining).font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.sInk).lineLimit(1)
                Text(hud.speed).font(.system(size: 15)).foregroundStyle(Theme.sInkSoft)
            }
            .contentShape(Rectangle())
            .onTapGesture { if !model.isTrack { model.sheet = .turnList } }
            Spacer()
            if model.isTrack { Button { model.addCueHere() } label: { Image(systemName: "plus.circle").font(.system(size: 22, weight: .semibold)).foregroundStyle(Theme.sInk).frame(width: 44, height: 44) } }
            Button("END") { model.endNavigation() }.buttonStyle(BigButton(accent: true, compact: true))
        }
        .padding(12).glass()
    }
}

/// The cue editor's buttons.
struct EditPanel: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        HStack(spacing: 8) {
            Button("SAVE") { model.saveTrack() }.buttonStyle(BigButton(accent: true))
            Button("LIST") { model.sheet = .cueList }.buttonStyle(BigButton(compact: true))
            Button("FOLLOW") { model.followEditedTrack() }.buttonStyle(BigButton(compact: true))
            Button("✕") { model.leaveEdit() }.buttonStyle(BigButton(compact: true))
        }
        .padding(12).glass()
    }
}

/// A modal progress card (importing, downloading, fetching).
struct ProgressOverlay: View {
    let state: AppModel.ProgressState
    var body: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 12) {
                Text(state.title).font(.headline).foregroundStyle(Theme.sInk)
                ProgressView().tint(Theme.sAccent)
                Text(state.message).font(.system(size: 15)).foregroundStyle(Theme.sInkSoft).multilineTextAlignment(.center)
                if let c = state.cancel { Button("CANCEL", action: c).buttonStyle(BigButton(compact: true)) }
            }
            .padding(24).frame(maxWidth: 320).background(Theme.sCard, in: RoundedRectangle(cornerRadius: 18))
        }
    }
}
