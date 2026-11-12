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
                    // the chart reader covers the map while it is up; the menu button stands on it
                    if model.mode == .nav && model.chartOn && model.chart != nil { ChartScreen(state: model.chartState, menu: menuButton) }
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
        .padding(.horizontal, 14).padding(.top, 14).padding(.bottom, 10).glass()
        // the scale sits on the card's top edge, centred, drawn over it
        .overlay(alignment: .top) { NavScaleBar(hud: hud).frame(height: 24).offset(y: -12).allowsHitTesting(false) }
        .contentShape(Rectangle())
        .onTapGesture { if !model.isTrack { model.sheet = .turnList } }
    }
}

/// The nav card's scale: always a fixed distance (a mile, a kilometre), only its length changes with the zoom. It sits
/// on the card's top edge, centred, so it grows and shrinks from the middle: a heavy bar, ticks standing up at the ends,
/// the distance in a card-coloured box across its centre.
struct NavScaleBar: View {
    @ObservedObject var hud: HudState
    var body: some View {
        GeometryReader { g in
            let w = max(1, min(hud.navScalePx, g.size.width - 44)), cx = g.size.width / 2, y: CGFloat = 12
            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: cx - w / 2, y: y - 9)); p.addLine(to: CGPoint(x: cx - w / 2, y: y))
                    p.addLine(to: CGPoint(x: cx + w / 2, y: y)); p.addLine(to: CGPoint(x: cx + w / 2, y: y - 9))
                }
                .stroke(Theme.sInk, style: StrokeStyle(lineWidth: 3.5, lineCap: .butt, lineJoin: .miter))
                Text(hud.navScaleLabel).font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.sInk).lineLimit(1).fixedSize()
                    .padding(.horizontal, 5).padding(.vertical, 1)
                    .background(Theme.sCard, in: RoundedRectangle(cornerRadius: 3))
                    .position(x: cx, y: y)
            }
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

/// The chart reader: the roll chart's rows on the phone, drawn from the shared core (the same mileage and tulips the
/// planner printed), the trip meter above them (beside them, held sideways). The list moves itself as the meter passes
/// each row; a swipe moves it by hand and it settles back on the rider's place a few seconds later.
/// Touch: − and + nudge the meter by a hundredth; a long press on the meter sets it to the row at the top.
struct ChartScreen<Menu: View>: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var state: ChartState
    let menu: Menu
    /// a swipe's offset, in rows
    @State private var manual: CGFloat = 0
    @State private var dragStart: CGFloat? = nil
    @State private var lastTouch = Date.distantPast
    /** a handlebar remote pairs as a Bluetooth keyboard: the chart takes its keys while it is up */
    @FocusState private var keys: Bool
    /// the inset folds away (a tap) when it covers a row's corner
    @State private var insetOn = true
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            let land = geo.size.width > geo.size.height
            ZStack(alignment: land ? .bottomLeading : .bottom) {
                Theme.sPaper.ignoresSafeArea()
                if land {
                    HStack(spacing: 0) {
                        meter(land: true).frame(width: geo.size.width * 0.3).background(Theme.sCard)
                        Rectangle().fill(Theme.sInkSoft).frame(width: 1)
                        rows(land: true)
                    }
                } else {
                    VStack(spacing: 0) {
                        meter(land: false).frame(height: 118).background(Theme.sCard)
                        Rectangle().fill(Theme.sInkSoft).frame(height: 1)
                        rows(land: false)
                    }
                }
                menu.padding(.bottom, 12).padding(.leading, land ? max(0, (geo.size.width * 0.3 - 76) / 2) : 0)
            }
        }
        .onReceive(tick) { _ in if manual != 0 && Date().timeIntervalSince(lastTouch) > 4 { manual = 0 } }
        .focusable()
        .focused($keys)
        .focusEffectDisabled()
        .onAppear { keys = true }
        .onKeyPress(phases: [.down, .repeat]) { k in remote(k) }
    }

    /// The remote: up and down look a row back or ahead, left and right (or − and +) nudge the meter, the centre
    /// button (Return or Space) sets it to the row at the top, once per press.
    private func remote(_ k: KeyPress) -> KeyPress.Result {
        if k.key == .upArrow || k.key == .pageUp { step(-1) }
        else if k.key == .downArrow || k.key == .pageDown { step(1) }
        else if k.key == .leftArrow || k.characters == "-" { model.nudgeMeter(-1) }
        else if k.key == .rightArrow || k.characters == "+" || k.characters == "=" { model.nudgeMeter(1) }
        else if k.key == .return || k.key == .space {
            if k.phase == .down { model.setMeterToRow(max(0, min(rowCount - 1, state.shown + Int(manual.rounded())))); manual = 0 }
        } else { return .ignored }
        return .handled
    }
    /// the line around the rider, the way ahead up, the chart's rows as dots
    @ViewBuilder private var inset: some View {
        if let h = state.here, let la = state.lats, let lo = state.lons, let cum = state.cum {
            if insetOn {
                Canvas { ctx, size in
                    guard let sk = Chart.shared.sketch(lats: la, lons: lo, cum: cum, lat: h.lat, lon: h.lon, along: h.along, rowsAt: state.rowsAt, back: 250, ahead: 750) else { return }
                    // 1 pt to 8 m: 700 m ahead fits above the rider, who sits two thirds down
                    let k = size.width / 1000, o = CGPoint(x: size.width / 2, y: size.height * 0.72)
                    func pt(_ a: KotlinDoubleArray, _ i: Int32) -> CGPoint { CGPoint(x: o.x + a.get(index: 2 * i) * k, y: o.y + a.get(index: 2 * i + 1) * k) }
                    var p = Path(); for i in 0..<(sk.line.size / 2) { if i == 0 { p.move(to: pt(sk.line, i)) } else { p.addLine(to: pt(sk.line, i)) } }
                    ctx.stroke(p, with: .color(Theme.sInk), lineWidth: 4)
                    for i in 0..<(sk.marks.size / 2) { let c = pt(sk.marks, i); ctx.fill(Path(ellipseIn: CGRect(x: c.x - 4.5, y: c.y - 4.5, width: 9, height: 9)), with: .color(Theme.sInk)) }
                    let r = CGPoint(x: o.x + sk.riderX * k, y: o.y + sk.riderY * k)
                    var a = Path(); a.move(to: CGPoint(x: r.x, y: r.y - 10)); a.addLine(to: CGPoint(x: r.x + 7, y: r.y + 7)); a.addLine(to: CGPoint(x: r.x, y: r.y + 3)); a.addLine(to: CGPoint(x: r.x - 7, y: r.y + 7)); a.closeSubpath()
                    ctx.fill(a, with: .color(Theme.sAccent)); ctx.stroke(a, with: .color(Theme.sCard), lineWidth: 1.5)
                }
                .frame(width: 128, height: 128)
                .background(Theme.sPaper)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.sInkSoft, lineWidth: 1))
                .onTapGesture { insetOn = false }
            } else {
                Text("MAP").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.sCard).padding(.horizontal, 10).padding(.vertical, 7).background(Theme.sInk, in: Capsule())
                    .onTapGesture { insetOn = true }
            }
        }
    }

    private func step(_ rows: Int) {
        manual = min(max(manual.rounded() + CGFloat(rows), -CGFloat(state.shown)), CGFloat(max(0, rowCount - 1 - state.shown)))
        lastTouch = Date()
    }

    private var rowCount: Int { model.chart?.rows.count ?? 1 }

    @ViewBuilder private func meter(land: Bool) -> some View {
        let st = model.chart?.settings
        let scale = st?.scale ?? 1
        let unit = (st?.units == "km" ? "KM" : "MI") + " · " + (state.free ? "ODOMETER" : "ALONG THE ROUTE") + (scale != 1 ? " · ×\(scale)" : "")
        let reading = VStack(spacing: 4) {
            Text(state.reading).font(.system(size: land ? 46 : 54, weight: .bold)).monospacedDigit().foregroundStyle(Theme.sInk).lineLimit(1).minimumScaleFactor(0.4)
            if let s = state.status { Text(s).font(.system(size: 13, weight: .bold)).foregroundStyle(.white).padding(.horizontal, 8).padding(.vertical, 3).background(Theme.sAccent, in: Capsule()) }
            else { Text(unit).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.sInkSoft).lineLimit(1).minimumScaleFactor(0.6) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        // "I am at this row": the row at the top of the list (or the one swiped to)
        .onLongPressGesture(minimumDuration: 0.6) { model.setMeterToRow(max(0, min(rowCount - 1, state.shown + Int(manual.rounded())))); manual = 0 }
        if land {
            // (the menu button sits at the column's foot, so the meter keeps to its upper part)
            VStack(spacing: 10) {
                reading.frame(maxHeight: 140)
                HStack(spacing: 10) { nudge("−", -1); nudge("+", 1) }.frame(height: 76)
                Spacer(minLength: 100)
            }
            .padding(10)
        } else {
            HStack(spacing: 10) { nudge("−", -1).frame(width: 76); reading; nudge("+", 1).frame(width: 76) }.padding(10)
        }
    }

    private func nudge(_ s: String, _ h: Int32) -> some View {
        Button { model.nudgeMeter(h) } label: {
            Text(s).font(.system(size: 40, weight: .bold)).foregroundStyle(Theme.sCard).frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.sInk, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }

    private func rows(land: Bool) -> some View {
        GeometryReader { g in
            let rowH: CGFloat = land ? g.size.height / 2 : max(196, g.size.height / 3.1)
            let shown = state.shown, man = manual, ver = state.version
            Canvas { ctx, size in
                guard let ch = model.chart else { return }
                let rows = ch.rows
                // the row the meter is at sits near the top; a strip of the one just passed shows above (a whole banner, it being short)
                let base = CGFloat(shown) + man
                let first = Int(floor(base)) - 1
                let prevH: CGFloat = (first >= 0 && first < rows.count && rows[first].kind == .banner) ? rowH * 0.42 : rowH
                var y = -(base - floor(base)) * rowH - (prevH < rowH ? 0 : rowH * 0.7)
                var k = first
                while y < size.height && k < rows.count {
                    if k >= 0 {
                        let r = rows[k]; let hh = r.kind == .banner ? rowH * 0.42 : rowH
                        ChartDraw.row(ctx, r, x0: 0, y0: y, w: size.width, h: hh, st: ch.settings, current: k == shown, passed: k < shown)
                        y += hh
                    } else { y += rowH }
                    k += 1
                }
            }
            .id(ver)
            .overlay(alignment: .bottomTrailing) { inset.padding(.trailing, 12).padding(.bottom, 16) }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 8)
                .onChanged { v in
                    if dragStart == nil { dragStart = manual }
                    manual = min(max(dragStart! - v.translation.height / rowH, -CGFloat(shown)), CGFloat(max(0, rowCount - 1 - shown)))
                    lastTouch = Date()
                }
                .onEnded { _ in dragStart = nil; lastTouch = Date() })
        }
    }
}

/// Draws one chart row into a canvas: the figures, the tulip from the core's shapes, the words, the marks.
enum ChartDraw {
    static let red = Color(red: 0xE3 / 255, green: 0x06 / 255, blue: 0x13 / 255)
    static let orange = Color(red: 0xF3 / 255, green: 0x92 / 255, blue: 0)
    static let yellow = Color(red: 1, green: 0xED / 255, blue: 0)
    static let pink = Color(red: 0xF6 / 255, green: 0xA8 / 255, blue: 0xC8 / 255)
    static let green = Color(red: 0x3A / 255, green: 0xAA / 255, blue: 0x35 / 255)

    private static func text(_ ctx: GraphicsContext, _ s: String, _ size: CGFloat, _ color: Color, at p: CGPoint, anchor: UnitPoint) {
        ctx.draw(Text(s).font(.system(size: size, weight: .bold)).foregroundColor(color), at: p, anchor: anchor)
    }
    private static func width(_ ctx: GraphicsContext, _ s: String, _ size: CGFloat) -> CGFloat {
        ctx.resolve(Text(s).font(.system(size: size, weight: .bold))).measure(in: CGSize(width: 10_000, height: 1_000)).width
    }

    static func row(_ ctx0: GraphicsContext, _ r: ChartRow, x0: CGFloat, y0: CGFloat, w: CGFloat, h: CGFloat, st: ChartSettings, current: Bool, passed: Bool) {
        var ctx = ctx0; ctx.opacity = passed ? 0.43 : 1
        let ink = Theme.sInk, soft = Theme.sInkSoft, card = Theme.sCard, paper = Theme.sPaper
        let pad: CGFloat = 12
        ctx.fill(Path(CGRect(x: x0, y: y0, width: w, height: h)), with: .color(card))
        ctx.fill(Path(CGRect(x: x0, y: y0 + h - 1, width: w, height: 1)), with: .color(soft.opacity(0.35)))
        if r.kind == .banner {
            ctx.fill(Path(CGRect(x: x0, y: y0 + 4, width: w, height: h - 8)), with: .color(ink))
            text(ctx, "SECTION \(r.section) · \(r.sectionKind == "stage" ? "STAGE" : "TRANSIT") · \(Chart.shared.fmt(h: r.lengthH, st: st)) \(st.units.uppercased())", 13, card, at: CGPoint(x: x0 + pad, y: y0 + h * 0.3), anchor: .leading)
            text(ctx, r.sectionName.uppercased(), 22, card, at: CGPoint(x: x0 + pad, y: y0 + h * 0.66), anchor: .leading)
            return
        }
        if current { ctx.fill(Path(CGRect(x: x0, y: y0, width: 6, height: h)), with: .color(Theme.sAccent)) }
        // the figures: overall left, the leg right, what is left to the section's end under it (layout A)
        let lineH: CGFloat = 60
        text(ctx, "\(r.n)", 12, soft, at: CGPoint(x: x0 + pad, y: y0 + 12), anchor: .leading)
        let overall = Chart.shared.fmt(h: r.overall, st: st)
        text(ctx, overall, 36, ink, at: CGPoint(x: x0 + pad + 28, y: y0 + 29), anchor: .leading)
        if r.resetHere || r.zeroOdo {
            // the odometer is zeroed here: the figure boxed, as on paper
            let tw = width(ctx, overall, 36)
            ctx.stroke(Path(CGRect(x: x0 + pad + 22, y: y0 + 8, width: tw + 12, height: 42)), with: .color(ink), lineWidth: 2.5)
            text(ctx, "ZERO", 11, soft, at: CGPoint(x: x0 + pad + 40 + tw, y: y0 + 38), anchor: .leading)
        }
        let delta = Chart.shared.fmt(h: r.delta, st: st)
        if r.shortPartial { let tw = width(ctx, delta, 22); ctx.fill(Path(roundedRect: CGRect(x: x0 + w - pad - tw - 8, y: y0 + 12, width: tw + 12, height: 30), cornerRadius: 4), with: .color(pink)) }
        text(ctx, delta, 22, ink, at: CGPoint(x: x0 + w - pad, y: y0 + 27), anchor: .trailing)
        if st.layout == "A" && st.decline { text(ctx, "TO GO " + Chart.shared.fmt(h: r.decline, st: st), 11, soft, at: CGPoint(x: x0 + w - pad, y: y0 + 49), anchor: .trailing) }
        // the tulip, big, left
        let side = min(h - lineH - pad, w * 0.46)
        let tx = x0 + pad + 4, ty = y0 + lineH + 2
        ctx.fill(Path(roundedRect: CGRect(x: tx, y: ty, width: side, height: side), cornerRadius: 8), with: .color(paper))
        let widths = Chart.Widths(path: side * 0.05, road: side * 0.036, track: side * 0.022, dot: side * 0.1)
        let shapes: [Chart.Shape] = r.kind == .cue ? Chart.shared.tulip(t: r.tulip!, s: side, w: widths) : Chart.shared.flag(finish: r.kind == .finish, s: side, w: widths)
        draw(ctx, shapes, dx: tx, dy: ty, pathColor: r.kind == .cue && r.tulip?.path == "offtrack" ? Theme.sAccent : ink)
        // symbols in the tulip's corners, as on paper
        let g = side * 0.26, m = side * 0.04
        var used: [String: Int] = [:]
        for s in r.symbols {
            let n = used[s.slot, default: 0]; used[s.slot] = n + 1
            let slot = Array(s.slot)
            let gx = slot.first == "l" ? tx + m + CGFloat(n) * (g + m) : tx + side - m - g - CGFloat(n) * (g + m)
            let gy = slot.count > 1 && slot[1] == "a" ? ty + side * 0.08 : ty + side * 0.66
            draw(ctx, ChartGlyphs.shared.shapes(id: s.id, x: gx, y: gy, size: g), dx: 0, dy: 0, pathColor: ink)
            for wd in ChartGlyphs.shared.words(id: s.id, x: gx, y: gy, size: g) {
                ctx.draw(Text(wd.text).font(.system(size: wd.size, weight: .bold)).foregroundColor(wd.light ? card : ink), at: CGPoint(x: wd.x, y: wd.y), anchor: .bottom)
            }
        }
        // the words beside it: cautions first in red, then the instruction
        let wx = tx + side + pad, ww = (x0 + w - pad) - wx
        var wy = ty
        if r.caution > 0 { text(ctx, String(repeating: "!", count: Int(r.caution)), 26, red, at: CGPoint(x: wx, y: wy + 14), anchor: .leading); wy += 30 }
        var sign: CGFloat = 0
        if r.speed > 0 {
            sign = 46; let c = CGPoint(x: x0 + w - pad - sign / 2, y: wy + sign / 2)
            let disc = Path(ellipseIn: CGRect(x: c.x - sign / 2, y: c.y - sign / 2, width: sign, height: sign))
            ctx.fill(disc, with: .color(card))
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - sign / 2 + 2.5, y: c.y - sign / 2 + 2.5, width: sign - 5, height: sign - 5)), with: .color(red), lineWidth: 5)
            text(ctx, "\(r.speed)", 18, ink, at: c, anchor: .center)
        }
        let pills = !r.zone_.isEmpty || !r.wpType.isEmpty
        if !r.info.isEmpty && ww > 40 {
            let avail = ty + side - wy - (r.capShow && st.layout == "B" ? 34 : 0) - (pills ? 30 : 0)
            let tw = ww - (sign > 0 ? sign + 6 : 0)
            var size: CGFloat = 21
            var t = ctx.resolve(Text(r.info).font(.system(size: size, weight: .bold)).foregroundColor(ink))
            while t.measure(in: CGSize(width: tw, height: 10_000)).height > avail && size > 14 { size -= 1; t = ctx.resolve(Text(r.info).font(.system(size: size, weight: .bold)).foregroundColor(ink)) }
            var clip = ctx; clip.clip(to: Path(CGRect(x: wx, y: wy, width: ww, height: max(0, avail))))
            clip.draw(t, in: CGRect(x: wx, y: wy, width: tw, height: max(0, avail)))
        }
        // layout B: the CAP in yellow
        if st.layout == "B" && r.capShow && r.kind == .cue {
            let cap = "CAP \(r.cap)"; let tw = width(ctx, cap, 18)
            ctx.fill(Path(CGRect(x: wx, y: ty + side - 28, width: tw + 12, height: 28)), with: .color(yellow))
            text(ctx, cap, 18, .black, at: CGPoint(x: wx + 6, y: ty + side - 14), anchor: .leading)
        }
        // the zone and the waypoint, as pills at the foot
        var mx = x0 + w - pad
        let labels: [String] = [r.zone_, r.wpType.isEmpty ? "" : r.wpType + (r.wpN > 0 ? "\(r.wpN)" : "")].filter { !$0.isEmpty }
        for label in labels {
            let tw = width(ctx, label, 13) + 14
            ctx.fill(Path(roundedRect: CGRect(x: mx - tw, y: y0 + h - 30, width: tw, height: 22), cornerRadius: 11), with: .color(label == "DZ" ? orange : label == "FZ" ? green : ink))
            text(ctx, label, 13, card, at: CGPoint(x: mx - tw / 2, y: y0 + h - 19), anchor: .center)
            mx -= tw + 6
        }
        // a speed zone frames the row in orange; danger 3 in red
        if r.inZone { ctx.stroke(Path(CGRect(x: x0 + 2, y: y0 + 2, width: w - 4, height: h - 4)), with: .color(orange), lineWidth: 3) }
        if r.caution == 3 { ctx.stroke(Path(CGRect(x: x0 + 5, y: y0 + 5, width: w - 10, height: h - 10)), with: .color(red), lineWidth: 4) }
    }

    /// the core's shapes (lines, paths, circles) into the canvas
    static func draw(_ ctx: GraphicsContext, _ shapes: [Chart.Shape], dx: CGFloat, dy: CGFloat, pathColor: Color) {
        for s in shapes {
            let col = s.role == "path" ? pathColor : Theme.sInk
            if let l = s as? Chart.Line {
                guard l.width > 0 else { continue }
                var p = Path()
                for (i, pt) in l.pts.enumerated() { let q = CGPoint(x: dx + pt.get(index: 0), y: dy + pt.get(index: 1)); if i == 0 { p.move(to: q) } else { p.addLine(to: q) } }
                ctx.stroke(p, with: .color(col), style: StrokeStyle(lineWidth: l.width, lineCap: .butt, lineJoin: .round, dash: dash(l.dash)))
            } else if let ps = s as? Chart.PathShape {
                var p = Path()
                for op in ps.ops {
                    func pt(_ i: Int32) -> CGPoint { CGPoint(x: dx + op.get(index: i), y: dy + op.get(index: i + 1)) }
                    switch Int(op.get(index: 0)) {
                    case 0: p.move(to: pt(1))
                    case 1: p.addLine(to: pt(1))
                    case 2: p.addCurve(to: pt(5), control1: pt(1), control2: pt(3))
                    default: p.closeSubpath()
                    }
                }
                if ps.fill { ctx.fill(p, with: .color(col)) }
                else if ps.width > 0 { ctx.stroke(p, with: .color(col), style: StrokeStyle(lineWidth: ps.width, lineCap: .butt, lineJoin: .round, dash: dash(ps.dash))) }
            } else if let c = s as? Chart.Circle {
                let p = Path(ellipseIn: CGRect(x: dx + c.x - c.r, y: dy + c.y - c.r, width: c.r * 2, height: c.r * 2))
                if c.fill { ctx.fill(p, with: .color(col)) } else { ctx.stroke(p, with: .color(col), lineWidth: c.width) }
            }
        }
    }
    private static func dash(_ d: KotlinDoubleArray?) -> [CGFloat] { guard let d = d, d.size >= 2 else { return [] }; return [CGFloat(d.get(index: 0)), CGFloat(d.get(index: 1))] }
}
