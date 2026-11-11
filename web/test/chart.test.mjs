// The roll-chart engine's acceptance checks that need no printer (spec checks 3–6, 9, 13).
// Run: npm test (node --test web/test/*.test.mjs)
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const ctx = { console }; vm.createContext(ctx); ctx.globalThis = ctx;
vm.runInContext(readFileSync(new URL("../site/chart.js", import.meta.url), "utf8"), ctx);
const C = ctx.RoadbookChart;

const R = 6371000, RAD = Math.PI / 180;
function dest([lon, lat], brg, m) { const d = m / R, b = brg * RAD, p1 = lat * RAD, l1 = lon * RAD; const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b)); const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2)); return [l2 / RAD, p2 / RAD]; }
/** a line from legs of [bearing, metres], a point every 5 m */
function track(legs, start = [-111, 45]) { const out = [start]; let p = start; for (const [b, m] of legs) for (let s = 5; s <= m + 1e-9; s += 5) { p = dest(p, b, 5); out.push(p); } return out; }
function cueAt(line, at, extra = {}) { const cum = C.cumulative(line), p = C.pointAt(line, cum, at); return Object.assign({ lon: p[0], lat: p[1], turn: "NOTE", level: 0, text: "x", auto: false, at, id: Math.round(at) }, extra); }

test("3. mileage: rounded overall figures, deltas that add up, declines to 0.00, restarts at 0.00", () => {
  const line = track([[0, 10000]]);
  const cues = [cueAt(line, 1000), cueAt(line, 2500, { rally: { reset: true } }), cueAt(line, 3333), cueAt(line, 4000, { rally: { section: { name: "Stage 1", kind: "stage" } } }), cueAt(line, 7000), cueAt(line, 8888)];
  const st = C.settings({ layout: "A" });
  const { rows } = C.buildRows(line, cues, st);
  const U = 1609.344, h = m => Math.round(m / U * 100);
  const r = rows.filter(x => x.kind !== "banner");
  const byId = id => r.find(x => x.id === id);
  // overall figures right to 0.01 from each origin
  assert.equal(byId(1000).overall, h(1000));
  assert.equal(byId(2500).overall, 0, "the reset row reads 0.00");
  assert.equal(byId(3333).overall, h(833));
  assert.equal(byId(4000).overall, 0, "the section's first row reads 0.00");
  assert.equal(byId(7000).overall, h(3000));
  // a START row at 0.00, FINISH rows at each section's end with decline 0.00
  assert.equal(r[0].kind, "start"); assert.equal(r[0].overall, 0);
  const fins = r.filter(x => x.kind === "finish"); assert.equal(fins.length, 2);
  for (const f of fins) assert.equal(f.decline, 0, "decline reaches 0.00 at the section end");
  assert.equal(fins[1].overall, h(6000));
  // per run from an origin, the deltas after it add up to the last overall
  let run = []; const runs = [run];
  for (const x of r) { if (x.overall === 0 && run.length) { run = []; runs.push(run); } run.push(x); }
  for (const rn of runs) { const sum = rn.slice(1).reduce((a, x) => a + x.delta, 0); assert.equal(sum, rn[rn.length - 1].overall); }
  // two banners, numbered rows in order
  assert.equal(rows.filter(x => x.kind === "banner").length, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(r.map(x => x.n))), JSON.parse(JSON.stringify(r.map((_, i) => i + 1))));
});

test("4. loop: the same spot on each pass of an out-and-back is two rows in ride order", () => {
  const line = track([[0, 3000], [180, 3000]]);
  const cum = C.cumulative(line);
  const spot = C.pointAt(line, cum, 1000);
  const a = { lon: spot[0], lat: spot[1], turn: "NOTE", text: "gate", rally: { at: 1000 }, id: 1 }, b = { lon: spot[0], lat: spot[1], turn: "NOTE", text: "gate again", rally: { at: 5000 }, id: 2 };
  for (const c of [a, b]) c.at = C.placeAt(line, cum, [c.lon, c.lat], 1000, c.rally.at);
  const { rows } = C.buildRows(line, [b, a], C.settings({ layout: "B" }), { cum });
  const r = rows.filter(x => x.kind === "cue");
  assert.deepEqual(JSON.parse(JSON.stringify(r.map(x => x.id))), [1, 2]);
  assert.ok(Math.abs(r[0].at - 1000) < 2 && Math.abs(r[1].at - 5000) < 2);
  assert.notEqual(r[0].overall, r[1].overall);
});

test("5. tulip angles: the drawn exit is the true one; the stem enters from the bottom; a reversal is a hook", () => {
  for (const [turn, want] of [[90, 90], [-90, -90], [45, 45], [135, 135], [-135, -135]]) {
    const line = track([[0, 500], [turn, 500]]);
    const { rows } = C.buildRows(line, [cueAt(line, 500, { turn: "LEFT", text: "" })], C.settings({ layout: "B" }));
    const r = rows.find(x => x.kind === "cue");
    assert.ok(Math.abs(C.turnAngle(want, r.tulip.exit)) <= 5, `exit ${r.tulip.exit} for ${want}`);
    const prims = C.tulipPrims(r.tulip, 0, 0, 36, C.LAYOUTS.B, false);
    const stem = prims.find(p => p.t === "l" && p.w === C.LAYOUTS.B.path);
    assert.ok(stem.pts[0][1] > stem.pts[1][1] && Math.abs(stem.pts[0][0] - stem.pts[1][0]) < 1e-9, "stem rises from the bottom centre");
    // the arrow's tip lies along the exit angle from the junction
    const head = prims.filter(p => p.t === "p").pop(), tip = head.d[0];
    const ang = Math.atan2(tip[1] - 36 * 0.55, tip[2] === undefined ? 0 : 0);
    const drawn = Math.atan2(tip[1] - 18, -(tip[2] - 36 * 0.55)) / RAD;
    assert.ok(Math.abs(C.turnAngle(r.tulip.exit, drawn)) <= 5, `drawn ${drawn} vs ${r.tulip.exit}`);
  }
  const back = track([[0, 500], [180, 500]].map(([b, m], i) => [b + (i ? 3 : 0), m]));
  const { rows } = C.buildRows(back, [cueAt(back, 500, { turn: "UTURN" })], C.settings({ layout: "A" }));
  const t = rows.find(x => x.kind === "cue").tulip;
  assert.ok(Math.abs(t.exit) > 150);
  assert.ok(C.tulipPrims(t, 0, 0, 18, C.LAYOUTS.A, false).some(p => p.t === "p" && p.d.some(op => op[0] === "C")), "drawn as a hook");
});

test("6. page geometry: every preset at its exact size, no page over 14,400 pt", () => {
  const line = track([[0, 20000]]);
  const cues = []; for (let m = 150; m < 20000; m += 150) cues.push(cueAt(line, m, { turn: "RIGHT", text: "turn " + m }));
  const PT = 72 / 25.4, close = (a, b) => Math.abs(a - b) < 0.01;
  for (const [preset, w, h] of [["A-letter", 612, 792], ["A-a4", 595.28, 841.89], ["B-a5", 419.53, 595.28], ["B-a4", 841.89, 595.28], ["B-letter", 612, 792]]) {
    const st = C.settings({ layout: preset[0], preset });
    const out = C.layout(C.buildRows(line, cues, st), st);
    for (const p of out.pages) assert.ok(close(p.w * PT, w) && close(p.h * PT, h), `${preset}: ${p.w * PT} x ${p.h * PT}`);
  }
  for (const [layout, w, rowPt] of [["A", 162, 72], ["B", 419.53, 38 * 72 / 25.4]]) {   // B: 107.72 pt, as the spec rounds it
    for (const rollRows of [3, 36, layout === "A" ? 180 : 130]) {
      const st = C.settings({ layout, preset: layout + "-roll", rollRows });
      const out = C.layout(C.buildRows(line, cues, st), st);
      out.pages.forEach((p, i) => {
        assert.ok(close(p.w * PT, w), `${layout} roll width ${p.w * PT}`);
        const lead = (i === 0 ? 100 : 0) + (i === out.pages.length - 1 ? 100 : 0);
        const rowsTall = Math.round((p.h - lead) / C.LAYOUTS[layout].rowH);
        assert.ok(close((p.h - lead) * PT, rowsTall * rowPt), `${layout} roll page is whole rows`);
        assert.ok(p.h * PT <= 14400, "under the PDF page ceiling");
        if (i > 0 && i < out.pages.length - 1) assert.equal(rowsTall, st.rollRows);
      });
    }
  }
});

test("9. legibility: 500 rows in both layouts; no text leaves its box, and text cut at the size floor is flagged", () => {
  const line = track([[0, 100000]]);
  const cues = []; for (let i = 0; i < 500; i++) cues.push(cueAt(line, 100 + i * 199, { turn: "NOTE", text: i % 50 === 0 ? "WWWWWWWW MMMMMMMM ".repeat(8) : "GATE ON THE LEFT " + i }));
  for (const layout of ["A", "B"]) {
    const st = C.settings({ layout }), L = C.LAYOUTS[layout];
    const chart = C.buildRows(line, cues, st), out = C.layout(chart, st);
    const flagged = new Set(out.warnings.filter(w => w.kind === "overflow").map(w => w.row));
    // the info / notes box this layout writes into
    const x0 = (L.stripW - L.printW) / 2, box = layout === "A" ? [x0 + L.tulipBox, x0 + L.printW, L.band + 0.6, L.band + 0.6 + L.boxH] : [x0 + L.distW + L.tulipW, x0 + L.printW, 0, L.rowH];
    for (const row of chart.rows.filter(r => r.kind === "cue")) {
      const { prims, overflow } = C.rowPrims(row, st);
      for (const p of prims.filter(p => p.t === "x" && p.x >= box[0] - 0.01 && p.x < box[1] && p.a === "l" && p.c === "#000000" && p.y > box[2] + 1)) {
        assert.ok(p.x + C.textWidth(p.s, p.size, p.b) <= box[1] + 0.01, `${layout} row ${row.n}: "${p.s}" runs past the box`);
        assert.ok(p.y <= box[3] + 0.01, `${layout} row ${row.n}: a line below the box`);
      }
      assert.equal(flagged.has(row.n), overflow, `${layout} row ${row.n}: flagged exactly when cut`);
    }
    if (layout === "A") assert.ok(flagged.size > 0, "A: the long rows were cut and flagged");   // B fits the longest 120-character note
  }
});

test("round trip of the rally fields through their text form", () => {
  const r = { at: 2124.6, exit: -88, branches: [{ deg: 0, kind: "track" }, { deg: 95, kind: "faint" }], path: "offtrack", caution: 2, info: "L ON RANCH RD", symbols: [{ id: "gate", slot: "ra" }], speed: 30, section: { name: "SS1", kind: "stage" }, reset: true, cap: 245, capShow: true, zone: "DZ", wp: { type: "V", n: 3 }, edited: true };
  assert.deepEqual(JSON.parse(JSON.stringify(C.parseRally(JSON.stringify(r)))), r);
  assert.equal(C.parseRally("{not json"), null);
  assert.deepEqual(JSON.parse(JSON.stringify(C.cleanRally({ caution: 9, exit: 999, branches: new Array(20).fill({ deg: 10, kind: "x" }), info: "a".repeat(500), symbols: [{ id: "../x" }] }))), { exit: 180, branches: new Array(8).fill({ deg: 10, kind: "track" }), caution: 3, info: "a".repeat(120) });
});
