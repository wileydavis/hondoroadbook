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
                    if geo.size.width > geo.size.height && model.mode == .nav {
                        // landscape while riding: the HUD, the nav card and the big button in one column at the left, so the
                        // map keeps its height; the camera is told how wide the column is
                        HStack(spacing: 0) {
                            VStack(spacing: 8) {
                                topArea(compact: true)
                                NavPanel(hud: hud, stacked: true) { EmptyView() }
                                Spacer(minLength: 0)
                                menuButton
                            }
                            .frame(width: 236)
                            .padding(.leading, 12).padding(.bottom, 12)
                            .background(GeometryReader { g in Color.clear.onAppear { model.leftColumnPt = g.frame(in: .global).maxX }.onChange(of: g.size) { _, _ in model.leftColumnPt = g.frame(in: .global).maxX } })
                            Spacer(minLength: 0)
                        }
                        .onDisappear { model.leftColumnPt = 0 }
                    } else {
                        VStack(spacing: 0) {
                            topArea(compact: false)
                            Spacer(minLength: 0)
                            bottomArea(geo)
                        }
                        .padding(.horizontal, 12)
                    }
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

        @ViewBuilder private func topArea(compact: Bool) -> some View {
            VStack(spacing: 8) {
                if model.mode == .nav {
                    if hud.visible { HudView(hud: hud, compact: compact).padding(.top, 8) }
                    if hud.offVisible {
                        HStack(spacing: 12) {
                            Text(hud.offText).font(.system(size: 22, weight: .heavy)).foregroundStyle(Theme.sAccent).lineLimit(1).minimumScaleFactor(0.6)
                            Spacer()
                            if hud.showReroute { Button(hud.rerouteLabel) { model.rerouteNow() }.buttonStyle(BigButton(accent: true, compact: true)) }
                        }
                        .padding(14).glass().padding(.top, 8)
                    }
                    // a zero-height reporter under the HUD and the off-track card: where the map window begins
                    Color.clear.frame(height: 0).background(GeometryReader { g in Color.clear.onAppear { model.hudBottomPt = g.frame(in: .global).minY }.onChange(of: g.frame(in: .global).minY) { _, v in model.hudBottomPt = v } })
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

        private var menuButton: some View {
            Button { model.haptic(1); model.menuOpen = true } label: {
                Image(systemName: "line.3.horizontal").font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.sButtonText)
                    .frame(width: 76, height: 76).background(Theme.sButton, in: Circle()).shadow(color: .black.opacity(0.25), radius: 6, y: 3)
            }
            .opacity(model.menuOpen ? 0 : 1)
        }

        @ViewBuilder private func bottomArea(_ geo: GeometryProxy) -> some View {
            VStack(spacing: 8) {
                if model.mode != .nav { HStack { ScaleView(hud: hud).padding(.leading, 4); Spacer() } }
                switch model.mode {
                case .pin: PinPanel()
                case .compare: ComparePanel()
                case .nav: NavPanel(hud: hud, stacked: false) { menuButton }
                    .background(GeometryReader { g in Color.clear.onAppear { model.cardTopPt = g.frame(in: .global).minY }.onChange(of: g.frame(in: .global).minY) { _, v in model.cardTopPt = v } })
                case .edit: EditPanel()
                case .idle: EmptyView()
                }
                if model.mode == .idle { HStack { Spacer(); menuButton; Spacer() } }
            }
            .padding(.bottom, 12)
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
            if model.hasPlanTitle {
                Text(model.planTitle).font(.system(size: 13)).foregroundStyle(Theme.sInkSoft).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.top, 2)
                    .contentShape(Rectangle()).onTapGesture { model.sheet = .stops }
            }
            ForEach(model.compareModeList, id: \.name) { p in
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
                Button("STOPS") { model.sheet = .stops }.buttonStyle(BigButton(compact: true))
                Button("MODES") { model.sheet = .modes }.buttonStyle(BigButton(compact: true))
                Button("SAVE") { if let r = model.candidate(model.profile) { model.saveOrShareRoute(r) } else { model.showToast("Pick a route first") } }.buttonStyle(BigButton(compact: true))
                Button("✕") { model.cancelCompare() }.buttonStyle(BigButton(compact: true))
            }
        }
        .padding(12).glass()
    }
}

/// The bottom card while navigating: it wraps the big button, with what is left and the ETA on its left, speed and
/// elevation on its right, and the scale bar along its top edge. Tap the numbers for the turn list; END is in the menu.
struct NavPanel<Menu: View>: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var hud: HudState
    /// landscape: the numbers in two rows with nothing between them (the big button sits below the card)
    var stacked = false
    @ViewBuilder let menu: () -> Menu
    var body: some View {
        VStack(spacing: 4) {
            NavScaleBar(hud: hud).frame(height: 18).padding(.horizontal, 2)
            if stacked {
                HStack { Text(hud.remaining).font(.system(size: 24, weight: .bold)); Spacer(); Text(hud.speed).font(.system(size: 24, weight: .bold)) }.foregroundStyle(Theme.sInk).lineLimit(1).minimumScaleFactor(0.7)
                HStack { Text(hud.eta).font(.system(size: 13)); Spacer(); Text(hud.elevation).font(.system(size: 13)) }.foregroundStyle(Theme.sInkSoft).lineLimit(1).minimumScaleFactor(0.7)
            } else {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(hud.remaining).font(.system(size: 27, weight: .bold)).foregroundStyle(Theme.sInk).lineLimit(1).minimumScaleFactor(0.7)
                        Text(hud.eta).font(.system(size: 15)).foregroundStyle(Theme.sInkSoft).lineLimit(1).minimumScaleFactor(0.7)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    menu()
                    VStack(alignment: .trailing, spacing: 3) {
                        Text(hud.speed).font(.system(size: 27, weight: .bold)).foregroundStyle(Theme.sInk).lineLimit(1).minimumScaleFactor(0.7)
                        Text(hud.elevation).font(.system(size: 15)).foregroundStyle(Theme.sInkSoft).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
        }
        .padding(.horizontal, 14).padding(.top, 6).padding(.bottom, 10).glass()
        .contentShape(Rectangle())
        .onTapGesture { if !model.isTrack { model.sheet = .turnList } }
    }
}

/// The nav card's scale: always a fixed distance (a mile, a kilometre), only its length changes with the zoom.
struct NavScaleBar: View {
    @ObservedObject var hud: HudState
    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Path { p in p.move(to: CGPoint(x: 0, y: 9)); p.addLine(to: CGPoint(x: 0, y: 1)); p.addLine(to: CGPoint(x: hud.navScalePx, y: 1)); p.addLine(to: CGPoint(x: hud.navScalePx, y: 9)) }
                .stroke(Theme.sInk, lineWidth: 2)
                .frame(width: max(1, hud.navScalePx), height: 10)
            Text(hud.navScaleLabel).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.sInk).padding(.top, -3)
            Spacer(minLength: 0)
        }
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
