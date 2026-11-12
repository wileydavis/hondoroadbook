// Writes core/src/jvmTest/resources/chart-fixtures.json: test routes run through the planner's chart engine
// (web/site/chart.js), every row recorded. The apps' Kotlin port (core Chart.kt) must reproduce them
// (ChartFixturesTest). Run after changing either engine: node web/tools/chart-fixtures.mjs
import { readFileSync, writeFileSync } from "node:fs";
import vm from "node:vm";
const root = new URL("../../", import.meta.url);
const ctx = {}; vm.createContext(ctx); ctx.globalThis = ctx;
vm.runInContext(readFileSync(new URL("web/site/chart.js", root), "utf8"), ctx);
const C = ctx.RoadbookChart;

const R = 6371000, RAD = Math.PI / 180;
function dest([lon, lat], brg, m) { const d = m / R, b = brg * RAD, p1 = lat * RAD, l1 = lon * RAD; const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b)); const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2)); return [l2 / RAD, p2 / RAD]; }
function track(legs, start = [-111, 45], step = 5) { const out = [start]; let p = start; for (const [b, m] of legs) for (let s = step; s <= m + 1e-9; s += step) { p = dest(p, b, step); out.push(p); } return out; }
// the nearest point along the line, computed exactly as the Kotlin port computes it (Chart.nearest)
function nearest(line, cum, lat, lon) { const k = Math.cos(lat * RAD) * 111320; let best = Infinity, at = 0; for (let i = 1; i < line.length; i++) { const ax = (line[i - 1][0] - lon) * k, ay = (line[i - 1][1] - lat) * 111320, bx = (line[i][0] - lon) * k, by = (line[i][1] - lat) * 111320; const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy; const t = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0; const d = Math.hypot(ax + t * dx, ay + t * dy); if (d < best) { best = d; at = cum[i - 1] + t * (cum[i] - cum[i - 1]); } } return at; }

const fixtures = [];
function fixture(name, line, cues, settings) {
  const cum = C.cumulative(line);
  const placed = cues.map((c, i) => { const rally = c.rally ? C.cleanRally(c.rally) : null; const near = nearest(line, cum, c.lat, c.lon); return Object.assign({}, c, { id: i, rally, at: C.placeAt(line, cum, [c.lon, c.lat], near, rally ? rally.at : undefined) }); });
  const st = C.settings(settings);
  const { rows } = C.buildRows(line, placed, st, { cum });
  fixtures.push({
    name, lats: line.map(p => p[1]), lons: line.map(p => p[0]), settings: JSON.stringify(C.settingsForFile(st)),
    cues: cues.map(c => ({ lat: c.lat, lon: c.lon, turn: c.turn, level: c.level || 0, text: c.text || "", rally: c.rally ? JSON.stringify(c.rally) : null, angle: c.angle ?? null, exit: c.exit || 0, auto: !!c.auto })),
    rows: rows.map(r => r.kind === "banner" ? { kind: "BANNER", name: r.name, sectionKind: r.sectionKind, lengthH: r.lengthH }
      : { kind: r.kind.toUpperCase(), n: r.n, overall: r.overall, delta: r.delta, decline: r.decline, info: r.info, caution: r.caution || 0, zeroOdo: !!r.zeroOdo, resetHere: !!r.resetHere, shortPartial: !!r.shortPartial, inZone: !!r.inZone, auto: !!r.auto,
          ...(r.kind === "cue" ? { exit: r.tulip.exit, branches: r.tulip.branches.map(b => [b.deg, b.kind]), path: r.tulip.path, roundabout: r.tulip.roundabout, cap: r.cap, capShow: !!r.capShow, speed: r.speed, symbols: r.symbols.map(s => [s.id, s.slot]), zone: r.zone, wp: r.wp ? r.wp.type + (r.wp.n || "") : "" } : {}) }),
  });
}
const at = (line, m, extra) => { const cum = C.cumulative(line), p = C.pointAt(line, cum, m); return Object.assign({ lat: p[1], lon: p[0], turn: "NOTE", text: "note " + Math.round(m) }, extra); };

// 1 · sections, a reset, START and FINISH rows (layout A, miles)
{ const l = track([[0, 10000]]); fixture("sections and a reset", l, [at(l, 1000), at(l, 2500, { rally: { reset: true } }), at(l, 3333), at(l, 4000, { rally: { section: { name: "Stage 1", kind: "stage" } } }), at(l, 7000), at(l, 8888)], { layout: "A", title: "Straight" }); }
// 2 · an out-and-back: the same spot on each pass, stored distances keep ride order
{ const l = track([[0, 3000], [180, 3000]]); const cum = C.cumulative(l); const p = C.pointAt(l, cum, 1000); fixture("out and back", l, [{ lat: p[1], lon: p[0], turn: "NOTE", text: "gate", rally: { at: 1000 } }, { lat: p[1], lon: p[0], turn: "NOTE", text: "gate again", rally: { at: 5000 } }], { layout: "B" }); }
// 3 · turns of every size, a reversal, a roundabout
{ const l = track([[0, 600], [90, 600], [45, 600], [180, 600], [315, 600], [130, 600], [310, 600], [310, 400]]); fixture("turns", l, [at(l, 600, { turn: "RIGHT", text: "" }), at(l, 1200, { turn: "SLIGHT_LEFT" }), at(l, 1800, { turn: "SHARP_RIGHT" }), at(l, 2400, { turn: "LEFT" }), at(l, 3000, { turn: "SHARP_RIGHT" }), at(l, 3600, { turn: "UTURN" }), at(l, 4200, { turn: "ROUNDABOUT", exit: 3, text: "exit 3" })], { layout: "A" }); }
// 4 · layout B: zones, CAPs, waypoints, short partials, cautions, drawn tulips and symbols, the decimal comma
{ const l = track([[0, 900], [90, 900], [30, 900], [300, 900]]); fixture("roadbook fields", l, [
  at(l, 300, { turn: "RIGHT", rally: { branches: [{ deg: 0, kind: "track" }, { deg: -90, kind: "road" }], exit: 88, path: "road", caution: 1, info: "R ON RANCH RD", symbols: [{ id: "cattle_guard", slot: "ra" }], edited: true } }),
  at(l, 560, { turn: "DANGER", level: 2, text: "Wash crossing, soft sand", rally: { symbols: [{ id: "wash", slot: "la" }], speed: 25 } }),
  at(l, 900, { turn: "LEFT", rally: { zone: "DZ", speed: 30, cap: 315, capShow: true, wp: { type: "V", n: 1 } } }),
  at(l, 1200, { turn: "NOTE", text: "", rally: { path: "offtrack", info: "HP CAP 300 BTW BUSHES" } }),
  at(l, 1800, { turn: "RIGHT", rally: { zone: "FZ", caution: 3, info: "DROP OFF" } }),
  at(l, 1830, { turn: "NOTE", text: "close behind", auto: true }),
], { layout: "B", title: "Roadbook" }); }
// 5 · a real routed line (Charlottetown, PEI) with the router's own angles
{ const r = JSON.parse(readFileSync(new URL("web/test/pei-route.json", root), "utf8")); fixture("routed, PEI", r.coords, r.cues.map(c => ({ lat: c.lat, lon: c.lon, turn: c.turn, text: c.name || "", angle: c.angle, exit: Number(c.exit) || 0, auto: true })), { layout: "B" }); }
// 6 · scale factor and miles on layout B
{ const l = track([[0, 5000]]); fixture("scale and units", l, [at(l, 1234), at(l, 3210)], { layout: "B", units: "mi", decimal: "point", scale: 1.025 }); }
// 7 · 500 rows
{ const l = track([[0, 100000]], [-111, 45], 20); const cues = []; for (let i = 0; i < 500; i++) cues.push(at(l, 100 + i * 199, { turn: i % 3 ? "RIGHT" : "NOTE" })); fixture("500 rows", l, cues, { layout: "A" }); }

writeFileSync(new URL("core/src/jvmTest/resources/chart-fixtures.json", root), JSON.stringify(fixtures));
console.log(fixtures.map(f => `${f.name}: ${f.rows.length} rows`).join("\n"));
