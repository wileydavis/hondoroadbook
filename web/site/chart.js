// Roadbook roll charts: the engine. Pure functions, no DOM and no planner state, so the planner's preview, the PDF
// writer (chart-pdf.js) and the tests all share one layout. Two layouts from the same cues:
//   A — the stage-rally scroll chart (NASA Rally Sport RallyMoto): a 2.25 in roll, miles to hundredths.
//   B — the rally-raid roadbook (FIA/FIM): 148 mm wide, total | tulip | notes, kilometres to hundredths.
// Everything is laid out in millimetres, y downward, as a list of drawing primitives per row; the preview draws
// them as SVG and the PDF writer as PDF operators. Nothing is laid out twice.
(function (root) {
"use strict";

// ---- the numbers a test print will tune ----------------------------------------------------------------------------
const LAYOUTS = {
  A: {
    name: "Stage-rally scroll (2.25 in)", units: "mi", colour: false,
    stripW: 57.15, printW: 48, rowH: 25.4,
    band: 6.4, tulipBox: 18, infoW: 30, boxH: 18, square: 18,
    mainPt: 13, secondPt: 9, textPt: 8, textFloor: 6.5, numPt: 6, smallPt: 6,
    path: 0.7, road: 0.5, track: 0.3, dot: 1.6, frame: 0.2, glyph: 5.2,
    ruler: 40, textCodes: "A",
  },
  B: {
    name: "Rally-raid roadbook (148 mm)", units: "km", colour: true,
    stripW: 148, printW: 138, rowH: 38,
    distW: 32, tulipW: 53, notesW: 53, boxH: 38, square: 36,
    mainPt: 24, secondPt: 14, textPt: 11, textFloor: 9, numPt: 7, smallPt: 7,
    path: 1.3, road: 0.95, track: 0.6, dot: 3.2, frame: 0.3, glyph: 10,
    ruler: 100, textCodes: "B",
  },
};
// paper: page size in mm, strips across, rows per strip (roll: per page, chosen in the dialog)
const PRESETS = {
  "A-roll":   { layout: "A", label: "Roll, 2.25 in (receipt or roll printer)", roll: true, finish: "None: print on 2¼ in roll paper" },
  "A-letter": { layout: "A", label: "US Letter, 3 strips", pageW: 215.9, pageH: 279.4, strips: 3, perStrip: 10, top: 10, finish: "Cut the three strips apart and tape them end to end" },
  "A-a4":     { layout: "A", label: "A4, 3 strips", pageW: 210, pageH: 297, strips: 3, perStrip: 11, top: 6.5, finish: "Cut the three strips apart and tape them end to end" },
  "B-roll":   { layout: "B", label: "Roll, 148 mm (roll printer or print shop)", roll: true, finish: "None: roll-fed printer or print shop" },
  "B-a5":     { layout: "B", label: "A5 sheets", pageW: 148, pageH: 210, strips: 1, perStrip: 5, top: 6, finish: "Tape the sheets end to end" },
  "B-a4":     { layout: "B", label: "A4 landscape, two up", pageW: 297, pageH: 210, strips: 2, perStrip: 5, top: 6, finish: "Cut down the centre and tape end to end" },
  "B-letter": { layout: "B", label: "US Letter", pageW: 215.9, pageH: 279.4, strips: 1, perStrip: 6, top: 8, finish: "Trim the sides and tape end to end" },
};
const ROLL_ROWS = { A: [36, 3, 180], B: [36, 5, 130] };
const LEADER = 100;           // mm of blank paper at each end of a roll, for taping to the spools
const MAX_PAGE_MM = 5080;     // 14,400 pt: the page-size ceiling in common PDF readers
// FIA palette, and what each element becomes in black and white
const COL = { ink: "#000000", path: "#0055a4", red: "#e30613", orange: "#f39200", green: "#3aaa35", yellow: "#ffed00", pink: "#f6a8c8", grey: "#777777", rule: "#999999", white: "#ffffff" };
const PT = 25.4 / 72;         // one point in mm

const DEFAULTS = { layout: "A", units: null, decimal: null, colour: null, preset: null, title: "", scale: 1, decline: true, rollRows: 36 };
function settings(s) {
  const o = Object.assign({}, DEFAULTS, s || {});
  if (!LAYOUTS[o.layout]) o.layout = "A";
  const L = LAYOUTS[o.layout];
  if (o.units !== "mi" && o.units !== "km") o.units = L.units;
  if (o.decimal !== "point" && o.decimal !== "comma") o.decimal = o.layout === "B" ? "comma" : "point";
  if (typeof o.colour !== "boolean") o.colour = L.colour;
  if (!PRESETS[o.preset] || PRESETS[o.preset].layout !== o.layout) o.preset = o.layout + "-roll";
  o.scale = Number.isFinite(+o.scale) && +o.scale > 0.5 && +o.scale < 2 ? +o.scale : 1;
  const rr = ROLL_ROWS[o.layout]; o.rollRows = Math.max(rr[1], Math.min(rr[2], Math.round(+o.rollRows) || rr[0]));
  o.title = String(o.title || "").replace(/[\u0000-\u001f]/g, " ").slice(0, 80);
  o.decline = o.decline !== false;
  return o;
}
/** Chart settings as stored in the GPX: only what differs from what the layout would choose. */
function settingsForFile(s) {
  const o = settings(s), L = LAYOUTS[o.layout], out = { layout: o.layout, preset: o.preset };
  if (o.units !== L.units) out.units = o.units;
  if (o.decimal !== (o.layout === "B" ? "comma" : "point")) out.decimal = o.decimal;
  if (o.colour !== L.colour) out.colour = o.colour;
  if (o.title) out.title = o.title;
  if (o.scale !== 1) out.scale = o.scale;
  if (!o.decline) out.decline = false;
  if (o.rollRows !== ROLL_ROWS[o.layout][0]) out.rollRows = o.rollRows;
  return out;
}

// ---- the rally fields on a cue: everything read from a file goes through here ---------------------------------------
const PATHS = ["road", "track", "offtrack"];
const KINDS = ["road", "track", "faint"];
const SLOTS = ["lb", "rb", "la", "ra"];       // left/right of the stem, before/after the junction
const WP_TYPES = { V: "visible", E: "eclipse", M: "masked", N: "navigation", S: "safety", C: "control", P: "precise" };
function cleanText(s, n) { return String(s ?? "").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, n); }
function num(v, lo, hi, step) { v = typeof v === "string" && v.trim() !== "" ? Number(v) : v; if (typeof v !== "number" || !Number.isFinite(v)) return undefined; v = Math.min(hi, Math.max(lo, v)); return step ? Math.round(v / step) * step : v; }
function cleanRally(r) {
  if (!r || typeof r !== "object" || Array.isArray(r)) return null;
  const o = {};
  const at = num(r.at, 0, 1e8); if (at !== undefined) o.at = Math.round(at * 10) / 10;
  const ex = num(r.exit, -180, 180, 1); if (ex !== undefined) o.exit = ex;
  if (Array.isArray(r.branches)) { const b = r.branches.slice(0, 8).map(x => x && typeof x === "object" ? { deg: num(x.deg, -180, 180, 1), kind: KINDS.includes(x.kind) ? x.kind : "track" } : null).filter(x => x && x.deg !== undefined); if (b.length || r.branches.length === 0) o.branches = b; }
  if (PATHS.includes(r.path)) o.path = r.path;
  const ca = num(r.caution, 0, 3, 1); if (ca) o.caution = ca;
  if (typeof r.info === "string") { const t = cleanText(r.info, 120); if (t) o.info = t; }
  if (Array.isArray(r.symbols)) { const s = r.symbols.slice(0, 6).filter(x => x && typeof x.id === "string" && /^[a-z0-9_]{1,32}$/.test(x.id)).map(x => ({ id: x.id, slot: SLOTS.includes(x.slot) ? x.slot : "ra" })); if (s.length) o.symbols = s; }
  const sp = num(r.speed, 1, 300, 1); if (sp) o.speed = sp;
  if (r.section && typeof r.section === "object") o.section = { name: cleanText(r.section.name, 40), kind: r.section.kind === "stage" ? "stage" : "transit" };
  if (r.reset === true) o.reset = true;
  const cap = num(r.cap, 0, 359, 1); if (cap !== undefined) o.cap = cap;
  if (r.capShow === true || r.capShow === false) o.capShow = r.capShow;
  if (r.zone === "DZ" || r.zone === "FZ") o.zone = r.zone;
  if (r.wp && typeof r.wp === "object" && WP_TYPES[r.wp.type]) { o.wp = { type: r.wp.type }; const n = num(r.wp.n, 1, 999, 1); if (n) o.wp.n = n; }
  if (r.edited === true) o.edited = true;
  return Object.keys(o).length ? o : null;
}
/** The rally object from its <rally> text in a GPX: a bad block drops the rally data, never the cue. */
function parseRally(text) { try { return cleanRally(JSON.parse(text)); } catch { return null; } }
function parseSettings(text) { try { const o = JSON.parse(text); return o && typeof o === "object" ? settings(o) : null; } catch { return null; } }

// ---- along the line ----------------------------------------------------------------------------------------------
const R_EARTH = 6371000, RAD = Math.PI / 180;
function dist(a, b) { const dLat = (b[1] - a[1]) * RAD, dLon = (b[0] - a[0]) * RAD; const x = dLon * Math.cos((a[1] + b[1]) / 2 * RAD); return R_EARTH * Math.hypot(dLat, x); }
function cumulative(line) { const c = new Float64Array(line.length); for (let i = 1; i < line.length; i++) c[i] = c[i - 1] + dist(line[i - 1], line[i]); return c; }
function bearing(a, b) { const y = Math.sin((b[0] - a[0]) * RAD) * Math.cos(b[1] * RAD); const x = Math.cos(a[1] * RAD) * Math.sin(b[1] * RAD) - Math.sin(a[1] * RAD) * Math.cos(b[1] * RAD) * Math.cos((b[0] - a[0]) * RAD); return (Math.atan2(y, x) / RAD + 360) % 360; }
/** Signed change of heading from b1 to b2, -180..180, right positive. */
function turnAngle(b1, b2) { let d = ((b2 - b1) % 360 + 540) % 360 - 180; if (d === -180) d = 180; return d; }
function pointAt(line, cum, at) {
  const n = line.length; if (at <= 0) return line[0]; if (at >= cum[n - 1]) return line[n - 1];
  let lo = 0, hi = n - 1; while (hi - lo > 1) { const m = (lo + hi) >> 1; if (cum[m] <= at) lo = m; else hi = m; }
  const seg = cum[hi] - cum[lo]; const t = seg > 0 ? (at - cum[lo]) / seg : 0;
  return [line[lo][0] + (line[hi][0] - line[lo][0]) * t, line[lo][1] + (line[hi][1] - line[lo][1]) * t];
}
/**
 * Headings at a point on the line, as the phone works them out (Route.bearingInto / bearingOutOf): arriving from 20 m
 * back, leaving to 20 m ahead. A roundabout is judged from 20 m before it to the road 50–90 m after, past the ring.
 */
function headings(line, cum, at, roundabout) {
  const total = cum[cum.length - 1], p = pointAt(line, cum, at);
  const back = pointAt(line, cum, Math.max(0, at - 20)), ahead = pointAt(line, cum, Math.min(total, at + 20));
  const arr = at > 1 ? bearing(back, p) : bearing(p, ahead);
  let dep = at < total - 1 ? bearing(p, ahead) : arr;
  if (roundabout && at + 60 < total) dep = bearing(pointAt(line, cum, at + 50), pointAt(line, cum, Math.min(total, at + 90)));
  return { arr, dep, exit: Math.round(turnAngle(arr, dep)) };
}
/**
 * Every place the line passes within [radius] m of a point, one per pass (passes 200 m or more apart along the line),
 * as {at, d}. A cue on a loop or an out-and-back has two; the stored distance picks between them.
 */
function passes(line, cum, p, radius, segs) {
  const k = Math.cos(p[1] * RAD), hits = [];
  const n = segs ? segs.length : line.length - 1;
  for (let j = 0; j < n; j++) {
    const i = segs ? segs[j] : j + 1;
    const a = line[i - 1], b = line[i];
    const ax = (a[0] - p[0]) * k * 111320, ay = (a[1] - p[1]) * 111320, bx = (b[0] - p[0]) * k * 111320, by = (b[1] - p[1]) * 111320;
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
    if (Math.min(ax, bx) > radius || Math.max(ax, bx) < -radius || Math.min(ay, by) > radius || Math.max(ay, by) < -radius) continue;
    const t = l2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
    const d = Math.hypot(ax + t * dx, ay + t * dy);
    if (d <= radius) hits.push({ at: cum[i - 1] + t * (cum[i] - cum[i - 1]), d });
  }
  hits.sort((x, y) => x.at - y.at);
  const out = [];
  for (const h of hits) { const last = out[out.length - 1]; if (last && h.at - last.at < 200) { if (h.d < last.d) out[out.length - 1] = h; } else out.push(h); }
  return out;
}
/** Where a cue sits along the line: the nearest point, unless it is passed twice and a stored distance says which pass. */
function placeAt(line, cum, p, nearest, stored, segs) {
  if (stored === undefined || stored === null) return nearest;
  const ps = passes(line, cum, p, 30, segs);
  if (ps.length < 2) return nearest;
  let best = ps[0]; for (const h of ps) if (Math.abs(h.at - stored) < Math.abs(best.at - stored)) best = h;
  return best.at;
}

// ---- mileage rows ----------------------------------------------------------------------------------------------------
/** hundredths of a chart unit, the one rounding everything is built on: deltas are differences of rounded figures */
function hundredths(m, st) { return Math.round(m * st.scale / (st.units === "km" ? 1000 : 1609.344) * 100); }
function fmtH(h, st) { const s = (Math.abs(h) / 100).toFixed(2); return (h < 0 ? "-" : "") + (st.decimal === "comma" ? s.replace(".", ",") : s); }
const SHORT = { STRAIGHT: "STRAIGHT", SLIGHT_LEFT: "BEAR L", SLIGHT_RIGHT: "BEAR R", LEFT: "L", RIGHT: "R", SHARP_LEFT: "SHARP L", SHARP_RIGHT: "SHARP R", KEEP_LEFT: "KEEP L", KEEP_RIGHT: "KEEP R", UTURN: "U-TURN", ROUNDABOUT: "ROUNDABOUT" };
/** The road class and surface at a distance, from the router's runs, as the ridden line's style. */
function pathFromSurface(surface, line, at, cum) {
  if (!surface || !surface.length) return null;
  for (const r of surface) if (at >= cum[r.i] - 1 && at <= cum[r.j] + 1) return r.cls === "path" || r.cls === "footway" || r.cls === "bridleway" ? "offtrack" : r.cls === "track" || r.unpaved ? "track" : "road";
  return null;
}
/** A router's junction arms (absolute bearings) as branches seen by a rider arriving on [arr] and leaving on [dep]. */
function armsToBranches(arms, arr, dep) {
  if (!arms || !arms.length) return [];
  const rel = arms.map(a => ({ deg: Math.round(turnAngle(arr, a.bearing)), kind: a.cls === "path" || a.cls === "footway" || a.cls === "bridleway" || a.cls === "steps" || a.cls === "cycleway" || a.cls === "pedestrian" ? "faint" : a.cls === "track" || a.unpaved ? "track" : "road" }));
  const exit = turnAngle(arr, dep);
  const drop = (target, within) => { let bi = -1, bd = within; rel.forEach((r, i) => { if (!r) return; const d = Math.abs(turnAngle(target, r.deg)); if (d < bd) { bd = d; bi = i; } }); if (bi >= 0) rel[bi] = null; };
  drop(180, 60); drop(exit, 60);
  return rel.filter(Boolean).slice(0, 8);
}

/**
 * The chart's rows from a line and its cues. Each cue already carries .at (metres along the line, placed by the
 * planner with placeAt). Sections restart the mileage; a reset restarts it without a new section; a START row and a
 * FINISH row are added where no cue sits within 20 m. Returns {rows, total, sections, warnings}.
 */
function buildRows(line, cues, st, opts) {
  st = settings(st); opts = opts || {};
  const cum = opts.cum || cumulative(line), total = cum[cum.length - 1];
  const sorted = cues.filter(c => Number.isFinite(c.at)).slice().sort((a, b) => a.at - b.at);
  // section starts: the line's start, then every cue that starts one
  const starts = [{ at: 0, name: st.title || "Route", kind: "transit", cue: null }];
  for (const c of sorted) if (c.rally && c.rally.section) {
    if (c.at < 20 && !starts[0].cue) { starts[0] = { at: c.at, name: c.rally.section.name || st.title || "Section 1", kind: c.rally.section.kind, cue: c }; continue; }
    starts.push({ at: c.at, name: c.rally.section.name || "Section " + (starts.length + 1), kind: c.rally.section.kind, cue: c });
  }
  const explicit = starts.some(s => s.cue);
  const rows = [], warnings = [];
  let n = 0, inZone = false;
  starts.forEach((sec, si) => {
    const end = si + 1 < starts.length ? starts[si + 1].at : total;
    const mine = sorted.filter(c => c !== sec.cue && c.at >= sec.at - 0.01 && (si + 1 < starts.length ? c.at < end - 0.01 && c !== starts[si + 1].cue : c.at <= end + 0.01));
    const secRow = { kind: "banner", name: sec.name, sectionKind: sec.kind, lengthH: hundredths(end - sec.at, st), index: si + 1 };
    if (explicit) rows.push(secRow);
    let origin = sec.at, prevH = 0, prevAt = sec.at;
    let endH = hundredths(end - origin, st);
    const push = (row) => {
      row.overall = hundredths(row.at - origin, st);
      row.delta = row.overall - prevH; row.partialM = row.at - prevAt;
      row.decline = endH - row.overall;
      row.section = si + 1; row.n = ++n;
      prevH = row.overall; prevAt = row.at; rows.push(row);
    };
    // the 0.00 row: the cue that starts the section, or a START row unless a cue sits within 20 m
    const first = mine[0];
    if (sec.cue) push(cueRow(sec.cue, true));
    else if (!first || first.at - sec.at > 20) push({ kind: "start", at: sec.at, info: (si === 0 ? "START" : "START " + cleanText(sec.name, 30).toUpperCase()) + (st.layout === "A" ? " · ZERO ODO" : ""), zeroOdo: true });
    for (const c of mine) {
      const r = cueRow(c, false);
      if (c.rally && c.rally.reset && !(sec.cue === c)) {
        // the row where the odometer is reset reads 0.00, its delta still the way from the row before
        const leg = hundredths(c.at - prevAt, st);
        origin = c.at; endH = hundredths(end - origin, st); prevH = 0;
        r.resetHere = true; push(r); r.delta = leg;
      } else push(r);
    }
    const last = rows[rows.length - 1];
    if (!last || last.kind === "banner" || end - last.at > 20) push({ kind: "finish", at: end, info: si + 1 < starts.length ? "END " + cleanText(sec.name, 30).toUpperCase() : "FINISH" });
  });
  function cueRow(c, zero) {
    const r = c.rally || {};
    const isRb = c.turn === "ROUNDABOUT";
    const h = headings(line, cum, c.at, isRb);
    // the router's own angle measures the whole junction (a divided road's two crossings as one); the line's 20 m
    // look-ahead would call that straight on
    const exit0 = Number.isFinite(c.angle) && !isRb && c.turn !== "NOTE" && c.turn !== "DANGER" ? Math.round(c.angle) : h.exit;
    const exitNow = r.exit != null ? r.exit : exit0;
    // a roundabout's junction answer is its entry node, not the ring: its exits are drawn from the exit number instead
    const branches = r.branches ? r.branches : c.junc && !isRb ? armsToBranches(c.junc.arms, h.arr, (h.arr + exitNow + 360) % 360) : [];
    const path = r.path || pathFromSurface(opts.surface, line, c.at, cum) || "track";
    const caution = r.caution != null ? r.caution : c.turn === "DANGER" ? (c.level || 2) : 0;
    let info = r.info != null ? r.info : (c.text || "");
    info = cleanText(info, 120).toUpperCase();
    if (zero && st.layout === "A") info = info ? "ZERO ODO · " + info : "ZERO ODO";
    if (r.zone === "DZ") inZone = true;
    const zoned = inZone || r.zone === "FZ";
    if (r.zone === "FZ") inZone = false;
    return {
      kind: "cue", cue: c, id: c.id, at: c.at, turn: c.turn, info, caution, zeroOdo: zero,
      tulip: { exit: (r.exit != null ? r.exit : exit0) || 0, computedExit: exit0 || 0, branches, path, roundabout: isRb ? Math.max(1, c.exit || 1) : 0, junction: !!(branches.length || (c.turn !== "NOTE" && c.turn !== "DANGER")) },
      symbols: r.symbols || [], speed: r.speed || 0, cap: r.cap != null ? r.cap : Math.round(h.dep) % 360, capShow: r.capShow != null ? r.capShow : path === "offtrack",
      zone: r.zone || "", inZone: zoned, wp: r.wp || null, auto: !!c.auto && !r.edited, off: c.off || 0,
    };
  }
  // what a rider would want flagged before printing
  const cueRows = rows.filter(r => r.kind === "cue");
  for (let i = 1; i < cueRows.length; i++) if (cueRows[i].at - cueRows[i - 1].at < 30 && cueRows[i].section === cueRows[i - 1].section) warnings.push({ row: cueRows[i].n, id: cueRows[i].id, kind: "close", text: `Rows ${cueRows[i - 1].n} and ${cueRows[i].n} are ${Math.round(cueRows[i].at - cueRows[i - 1].at)} m apart: merge them into one tulip?` });
  for (const r of cueRows) if (r.off > 25) warnings.push({ row: r.n, id: r.id, kind: "off", text: `Row ${r.n} is ${Math.round(r.off)} m off the line` });
  if (st.layout === "B") for (const r of rows) if ((r.kind === "cue" || r.kind === "finish") && r.delta > 0 && r.partialM < 400 && !r.zeroOdo) { r.shortPartial = true; warnings.push({ row: r.n, id: r.id, kind: "short", text: `Row ${r.n}: a short partial (${fmtH(r.delta, st)} ${st.units}), marked as FIA asks` }); }
  return { rows, total, totalH: hundredths(total, st), sections: starts.length, warnings, settings: st };
}

// ---- text: the PDF standard fonts, WinAnsi only ------------------------------------------------------------------------
// glyph widths (per 1000 em) of Helvetica and Helvetica-Bold for WinAnsi codes 32–255, from the Adobe font metrics
const W_REG = [278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584,761,556,0,222,556,333,1000,556,556,333,1000,667,333,1000,0,611,0,0,222,222,333,333,350,556,1000,333,1000,500,333,944,0,500,667,278,333,556,556,556,556,260,556,333,737,370,556,584,333,737,333,400,584,333,333,333,556,537,278,333,333,365,556,834,834,834,611,667,667,667,667,667,667,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,556,556,556,556,556,556,556,584,611,556,556,556,556,500,556,500];
const W_BOLD = [278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584,761,556,0,278,556,500,1000,556,556,333,1000,667,333,1000,0,611,0,0,278,278,500,500,350,556,1000,333,1000,556,333,944,0,500,667,278,333,556,556,556,556,280,556,333,737,370,556,584,333,737,333,400,584,333,333,333,611,556,278,333,333,365,556,834,834,834,611,722,722,722,722,722,722,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,611,611,611,611,611,611,611,584,611,611,611,611,611,556,611,556];
const WIN_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f\u0090‘’“”•–—˜™š›œ\u009džŸ";
/** A string as WinAnsi codes; anything else becomes "?" and is reported. */
function winAnsi(s) {
  const codes = []; let bad = "";
  for (const ch of String(s)) {
    const u = ch.codePointAt(0);
    let c = -1;
    if (u >= 32 && u < 127) c = u; else if (u >= 160 && u <= 255) c = u; else { const i = WIN_HIGH.indexOf(ch); if (i >= 0 && ![1, 13, 15, 16, 29].includes(i)) c = 128 + i; }
    if (c < 0) { bad += ch; c = 63; }
    codes.push(c);
  }
  return { codes, bad };
}
/** Width of a string in mm at a size in points. */
function textWidth(s, pt, bold) {
  const W = bold ? W_BOLD : W_REG; let w = 0;
  for (const c of winAnsi(s).codes) w += W[c - 32] || 556;
  return w / 1000 * pt * PT;
}
/** Wraps words into lines no wider than w mm; a single word too wide is broken by characters. */
function wrap(text, w, pt, bold) {
  const words = String(text).split(/\s+/).filter(Boolean), lines = [];
  let cur = "";
  for (let word of words) {
    while (textWidth(word, pt, bold) > w && word.length > 1) {
      let k = word.length - 1; while (k > 1 && textWidth(word.slice(0, k), pt, bold) > w) k--;
      if (cur) { lines.push(cur); cur = ""; }
      lines.push(word.slice(0, k)); word = word.slice(k);
    }
    const next = cur ? cur + " " + word : word;
    if (textWidth(next, pt, bold) <= w) cur = next; else { if (cur) lines.push(cur); cur = word; }
  }
  if (cur) lines.push(cur);
  return lines;
}
/** Fits text into a box, shrinking by half points to a floor; reports overflow rather than clipping it silently. */
function fitText(text, w, h, pt, floor, bold) {
  for (let size = pt; size >= floor - 1e-9; size -= 0.5) {
    const lines = wrap(text, w, size, bold), lh = size * PT * 1.15;
    if (lines.length * lh <= h + 0.01) return { lines, size, lh, overflow: false };
  }
  const lines = wrap(text, w, floor, bold), lh = floor * PT * 1.15, fit = Math.max(1, Math.floor((h + 0.01) / lh));
  return { lines: lines.slice(0, fit), size: floor, lh, overflow: lines.length > fit };
}

// ---- symbols: original drawings in a 10 × 10 box, stored by id ---------------------------------------------------------
// p = stroked path, f = filled path, c = stroked circle, C = filled circle, t = bold text (x, y baseline, size, words).
// Paths use absolute M, L, C, Z only, so the SVG preview and the PDF draw them identically.
const GLYPHS = [
  // on the track
  ["bump", "Bump", "track", ["p:M0 7 L2 7 C3.5 7 3.5 3 5 3 C6.5 3 6.5 7 8 7 L10 7"]],
  ["dip", "Dip", "track", ["p:M0 3 L2 3 C3.5 3 3.5 7 5 7 C6.5 7 6.5 3 8 3 L10 3"]],
  ["compression", "Compression", "track", ["p:M0 3 L5 7.5 L10 3", "p:M3.5 9.5 L6.5 9.5"]],
  ["ditch", "Ditch", "track", ["p:M0 4 L3 4 L4 7.5 L6 7.5 L7 4 L10 4"]],
  ["summit", "Summit", "track", ["p:M0 8 L5 2.5 L10 8", "C:5 2.5 0.9"]],
  ["crest", "Crest", "track", ["p:M0 8 C2 2.5 8 2.5 10 8", "p:M5 1 L5 3"]],
  ["bridge", "Bridge", "track", ["p:M1.5 3.5 L8.5 3.5 M1.5 6.5 L8.5 6.5 M0 2 L1.5 3.5 M10 2 L8.5 3.5 M0 8 L1.5 6.5 M10 8 L8.5 6.5"]],
  ["steps", "Steps", "track", ["p:M0 9 L3 9 L3 6.5 L5.5 6.5 L5.5 4 L8 4 L8 1.5 L10 1.5"]],
  ["uphill", "Steep up", "track", ["p:M1 8.5 L9 2", "f:M9.6 1.4 L5.4 2.4 L8.6 5.6 Z", "p:M1 8.5 L9 8.5"]],
  ["downhill", "Steep down", "track", ["p:M1 2 L9 8.5", "f:M9.6 9.1 L8.6 4.9 L5.4 8.1 Z", "p:M1 9.3 L9.6 9.3"]],
  ["gate", "Gate", "track", ["p:M1.5 1.5 L1.5 9 M8.5 1.5 L8.5 9 M1.5 3.5 L8.5 3.5 M1.5 7 L8.5 7 M1.5 7 L8.5 3.5"]],
  ["cattle_guard", "Cattle guard", "track", ["p:M1 2.5 L9 2.5 L9 7.5 L1 7.5 Z M3 2.5 L3 7.5 M5 2.5 L5 7.5 M7 2.5 L7 7.5"]],
  ["water", "Water crossing", "track", ["p:M0 4 C1.5 2.5 3.5 2.5 5 4 C6.5 5.5 8.5 5.5 10 4 M0 7.5 C1.5 6 3.5 6 5 7.5 C6.5 9 8.5 9 10 7.5"]],
  ["concrete_ford", "Concrete ford", "track", ["p:M1 5.5 L9 5.5 L9 8.5 L1 8.5 Z", "p:M1 3 C2.5 1.5 3.5 4 5 2.8 C6.5 1.5 7.5 4 9 2.8"]],
  // safety
  ["stop", "Stop", "safety", ["f:M3 0.5 L7 0.5 L9.5 3 L9.5 7 L7 9.5 L3 9.5 L0.5 7 L0.5 3 Z", "t:5 6.6 3.4 STOP:#ffffff"]],
  ["important", "Important", "safety", ["p:M5 0.8 L9.6 9 L0.4 9 Z", "t:5 8.2 6 !"]],
  ["speed_end", "Speed limit ends", "safety", ["c:5 5 4.3", "p:M2 8 L8 2"]],
  // places and things
  ["fence", "Fence", "symbols", ["p:M0 3.5 L10 3.5 M0 6.5 L10 6.5 M2 2 L2 8.5 M5 2 L5 8.5 M8 2 L8 8.5"]],
  ["railway", "Railway", "symbols", ["p:M0 4 L10 4 M0 6.5 L10 6.5 M1.5 2.8 L1.5 7.7 M4 2.8 L4 7.7 M6.5 2.8 L6.5 7.7 M9 2.8 L9 7.7"]],
  ["hole", "Hole", "symbols", ["C:5 5.5 3", "p:M1 5.5 L1.8 5.5 M8.2 5.5 L9 5.5"]],
  ["washout", "Washout", "symbols", ["p:M0 3.5 L3 3.5 L4 7 L5 4.5 L6 8 L7 3.5 L10 3.5"]],
  ["ruts", "Ruts", "symbols", ["p:M2.5 0.5 C3.8 3.5 1.5 6.5 2.8 9.5 M7.5 0.5 C8.8 3.5 6.5 6.5 7.8 9.5"]],
  ["twisty", "Twisty", "symbols", ["p:M5 9.5 C0.5 8 9.5 6 5 4.5 C0.5 3 9.5 1.5 5 0.2"]],
  ["bumpy", "Bumpy", "symbols", ["p:M0 6 C0.8 3.5 1.7 3.5 2.5 6 C3.3 3.5 4.2 3.5 5 6 C5.8 3.5 6.7 3.5 7.5 6 C8.3 3.5 9.2 3.5 10 6"]],
  ["rocks", "Rocks", "symbols", ["f:M0.5 8.5 L1.5 5.5 L3.5 4.5 L5 6.5 L4.5 8.5 Z", "f:M5 8.5 L6 4 L8.2 3 L9.8 6 L9.3 8.5 Z", "p:M0 9 L10 9"]],
  ["tree", "Tree", "symbols", ["c:5 4 3.2", "p:M5 7.2 L5 10"]],
  ["bushes", "Bushes", "symbols", ["p:M1 8.5 L2 4 L3 8.5 M4 8.5 L5 3 L6 8.5 M7 8.5 L8 4 L9 8.5", "p:M0 9 L10 9"]],
  ["building", "Building", "symbols", ["p:M1.5 9 L1.5 4.5 L5 1.2 L8.5 4.5 L8.5 9 Z"]],
  ["village", "Village", "symbols", ["p:M0.5 9 L0.5 5.5 L3 3.2 L5.5 5.5 L5.5 9 Z M5.5 9 L5.5 4 L7.5 2 L9.5 4 L9.5 9 Z"]],
  ["sign", "Signpost", "symbols", ["p:M4 9.5 L4 1.5", "f:M4 1.5 L8.5 1.5 L9.8 2.8 L8.5 4.1 L4 4.1 Z"]],
  ["power_line", "Power line", "symbols", ["p:M3 9.5 L5 0.8 L7 9.5 M2 3 L8 3 M3.7 6.5 L6.3 6.5 M0 3 L2 3 M8 3 L10 3"]],
  ["antenna", "Antenna", "symbols", ["p:M5 9.5 L5 3.5 M3.2 1.2 C1.4 2.3 1.4 4.5 3.2 5.6 M6.8 1.2 C8.6 2.3 8.6 4.5 6.8 5.6", "C:5 3.4 0.8"]],
  ["tank", "Tank", "symbols", ["p:M1.5 3 L1.5 8.5 L8.5 8.5 L8.5 3 M1.5 3 C1.5 1.3 8.5 1.3 8.5 3 C8.5 4.7 1.5 4.7 1.5 3"]],
  ["cairn", "Cairn", "symbols", ["C:5 8 2", "C:5 4.9 1.5", "C:5 2.4 1"]],
  ["mountain", "Mountain", "symbols", ["f:M0 9.5 L4 2 L6 5.5 L7.2 4 L10 9.5 Z"]],
  ["river", "River", "symbols", ["p:M0 2.5 C3 5.5 7 -0.5 10 2.5 M0 5.2 C3 8.2 7 2.2 10 5.2"]],
  ["lake", "Lake", "symbols", ["p:M5 2 C9 2 10 4.5 9.2 6.8 C8.2 9 1.8 9 0.8 6.8 C0 4.5 1 2 5 2 Z", "p:M3 5.5 L7 5.5"]],
  ["wash", "Dry wash", "symbols", ["p:M0 3 C3 5 7 1 10 3 M0 7 C3 9 7 5 10 7", "C:2.5 5 0.5", "C:5 5 0.5", "C:7.5 5 0.5"]],
  ["tunnel", "Tunnel", "symbols", ["p:M1 9.5 L1 5 C1 0.8 9 0.8 9 5 L9 9.5", "p:M3.2 9.5 L3.2 5.8 C3.2 3.4 6.8 3.4 6.8 5.8 L6.8 9.5"]],
  ["fuel", "Fuel", "symbols", ["p:M1.5 9.5 L1.5 1.5 L6.2 1.5 L6.2 9.5 Z M2.6 3 L5.1 3 L5.1 5 L2.6 5 Z M6.2 4 L8.3 5 L8.3 8.3 C8.3 9.3 9.6 9.3 9.6 8.3 L9.6 3.4"]],
  ["roadworks", "Road works", "symbols", ["p:M5 0.8 L9.6 9 L0.4 9 Z", "p:M3 7.8 L5.5 4.6 M4.6 7.8 L6.6 5.6"]],
  ["reset_trip", "Reset trip", "symbols", ["p:M8.4 6.2 C7.6 8.8 3.6 9.6 1.9 7.2 C0.3 4.9 1.8 1.6 4.6 1.3 C6 1.2 7.2 1.8 8 2.8", "f:M9.2 0.8 L8.9 4.4 L5.7 3 Z", "t:5 6.7 3.2 0"]],
  // controls
  ["ss_start", "Start of selective section", "controls", ["p:M2 9.8 L2 0.6", "f:M2 0.6 L9.4 2.6 L2 4.8 Z"]],
  ["ss_finish", "Finish of selective section", "controls", ["p:M1.5 9.8 L1.5 0.6 M1.5 0.6 L9.5 0.6 L9.5 5.4 L1.5 5.4", "f:M1.5 0.6 L4.2 0.6 L4.2 3 L1.5 3 Z M6.8 0.6 L9.5 0.6 L9.5 3 L6.8 3 Z M4.2 3 L6.8 3 L6.8 5.4 L4.2 5.4 Z"]],
  ["checkpoint", "Check point", "controls", ["p:M0.6 1.6 L9.4 1.6 L9.4 8.4 L0.6 8.4 Z", "t:5 6.4 4.4 CP"]],
  ["time_control", "Time control", "controls", ["c:5 5 4.3", "p:M5 5 L5 2 M5 5 L7.2 6.3"]],
  ["fuel_zone", "Fuel zone", "controls", ["p:M0.4 0.4 L9.6 0.4 L9.6 9.6 L0.4 9.6 Z", "p:M2.6 8.4 L2.6 2.2 L5.9 2.2 L5.9 8.4 Z M5.9 4 L7.4 4.8 L7.4 7.6"]],
  ["service", "Service / assistance", "controls", ["p:M2 8.6 L6.2 4.4", "p:M8.9 2.3 C8 0.9 5.9 1 5.3 2.6 C4.9 3.7 5.4 4.7 6.2 5.2 C7.4 5.9 9 5.3 9.3 4 L7.6 3.9 L7.4 2.6 Z"]],
  // dunes and sand
  ["dune", "Dune", "dunes", ["p:M0 8.5 C3 2 5 2 7 5 L10 8.5"]],
  ["dunes", "Dunes", "dunes", ["p:M0 8.5 C1.8 4 3 4 4.5 6 L5.5 8.5 M4 8.5 C6 3 7.5 3 8.7 5.5 L10 8.5"]],
  ["small_dune", "Small dune", "dunes", ["p:M2 8.5 C3.8 5.5 5 5.5 6.2 7 L8 8.5"]],
  ["dune_crossing", "Dune crossing", "dunes", ["p:M0 8.5 C3 3 5 3 7 5.5 L10 8.5", "p:M1 4 L9 4", "f:M9.8 4 L7.6 2.8 L7.6 5.2 Z"]],
  ["soft_sand", "Soft sand", "dunes", ["C:2 3 0.6", "C:5 2 0.6", "C:8 3 0.6", "C:3.5 5.5 0.6", "C:6.5 5.5 0.6", "C:2 8 0.6", "C:5 8.5 0.6", "C:8 8 0.6"]],
  ["camel_grass", "Camel grass", "dunes", ["p:M1 8.5 L2.5 5 M2 8.5 L2.5 4 M3 8.5 L2.6 5 M6.5 8.5 L8 5 M7.5 8.5 L8 4 M8.5 8.5 L8.1 5", "p:M0 9 L10 9"]],
  ["dune_l1", "Dune difficulty 1", "dunes", ["p:M0 9.5 C3 5 5 5 7 7 L10 9.5", "t:5 4.6 4.4 L1"]],
  ["dune_l2", "Dune difficulty 2", "dunes", ["p:M0 9.5 C3 5 5 5 7 7 L10 9.5", "t:5 4.6 4.4 L2"]],
  ["dune_l3", "Dune difficulty 3", "dunes", ["p:M0 9.5 C3 5 5 5 7 7 L10 9.5", "t:5 4.6 4.4 L3"]],
];
const GLYPH = {}; for (const [id, name, group, els] of GLYPHS) GLYPH[id] = { id, name, group, els };
const GROUPS = [["track", "On the track"], ["safety", "Safety"], ["symbols", "Places and things"], ["controls", "Controls"], ["dunes", "Dunes and sand"]];
function parsePath(d, k, x, y) {
  const tok = d.match(/[MLCZ]|-?\d*\.?\d+/g) || [], ops = []; let i = 0;
  while (i < tok.length) {
    const c = tok[i++];
    if (c === "Z") { ops.push(["Z"]); continue; }
    const n = c === "C" ? 6 : 2, a = [c];
    for (let j = 0; j < n; j++) { const v = +tok[i++]; a.push(j % 2 ? y + v * k : x + v * k); }
    ops.push(a);
  }
  return ops;
}
/** A symbol drawn into a square of [size] mm with its top left at (x, y). */
function glyphPrims(id, x, y, size, color) {
  const g = GLYPH[id]; if (!g) return [];
  const k = size / 10, w = Math.max(0.18, 0.8 * k), out = [];
  for (const el of g.els) {
    const kind = el[0], body = el.slice(2);
    if (kind === "p") out.push({ t: "p", d: parsePath(body, k, x, y), w, c: color, f: null });
    else if (kind === "f") out.push({ t: "p", d: parsePath(body, k, x, y), w: 0, c: null, f: color });
    else if (kind === "c" || kind === "C") { const [cx, cy, r] = body.split(/\s+/).map(Number); out.push({ t: "c", x: x + cx * k, y: y + cy * k, r: r * k, w: kind === "c" ? w : 0, c: kind === "c" ? color : null, f: kind === "C" ? color : null }); }
    else if (kind === "t") { const m = /^(\S+) (\S+) (\S+) ([^:]+)(?::(#[0-9a-f]{6}))?$/i.exec(body); if (m) out.push({ t: "x", x: x + +m[1] * k, y: y + +m[2] * k, s: m[4], size: +m[3] * k / PT, b: true, c: m[5] || color, a: "c" }); }
  }
  return out;
}

// ---- tulips -----------------------------------------------------------------------------------------------------------
const dirv = deg => [Math.sin(deg * RAD), -Math.cos(deg * RAD)];
/** How far from p along v before leaving the box [x0,y0,x1,y1]. */
function reach(p, v, box) {
  let t = Infinity;
  if (v[0] > 1e-9) t = Math.min(t, (box[2] - p[0]) / v[0]); if (v[0] < -1e-9) t = Math.min(t, (box[0] - p[0]) / v[0]);
  if (v[1] > 1e-9) t = Math.min(t, (box[3] - p[1]) / v[1]); if (v[1] < -1e-9) t = Math.min(t, (box[1] - p[1]) / v[1]);
  return Math.max(0, t);
}
function arrowHead(tip, v, w) {
  const hl = w * 3.4, hw = w * 1.7, base = [tip[0] - v[0] * hl, tip[1] - v[1] * hl], n = [-v[1], v[0]];
  return { t: "p", d: [["M", tip[0], tip[1]], ["L", base[0] + n[0] * hw, base[1] + n[1] * hw], ["L", base[0] - n[0] * hw, base[1] - n[1] * hw], ["Z"]], w: 0, c: null, f: null, _len: hl };
}
/**
 * One tulip into a square of side s at (x, y): entered from the bottom at a dot, left along the arrow at the true exit
 * angle (right positive); other ways out as thinner lines by road size; a roundabout as a ring; a reversal as a hook.
 */
function tulipPrims(t, x, y, s, L, colour) {
  const out = [], col = colour ? COL.path : COL.ink, pw = L.path;
  const J = [x + s / 2, y + s * 0.55], foot = [x + s / 2, y + s * 0.92];
  const box = [x + s * 0.05, y + s * 0.05, x + s * 0.95, y + s * 0.95];
  const dash = t.path === "offtrack" ? [pw * 2.4, pw * 1.6] : null;
  const rr = t.roundabout ? s * 0.12 : 0;
  const branchW = k => k === "road" ? L.road : L.track;
  // the other ways out, under the ridden line
  let branches = t.branches || [];
  if (t.roundabout && !branches.length) {
    // no map data: the exits passed on the way round (anticlockwise, as right-hand traffic drives) as stubs
    const e = t.exit > -170 ? t.exit : 180, n = t.roundabout, sweep = 180 - e;
    branches = []; for (let j = 1; j < n; j++) branches.push({ deg: 180 - sweep * j / n, kind: "road" });
  }
  for (const b of branches) {
    const v = dirv(b.deg), start = [J[0] + v[0] * rr, J[1] + v[1] * rr];
    const len = Math.min(s * 0.34, reach(start, v, box)), w = branchW(b.kind);
    out.push({ t: "l", pts: [start, [start[0] + v[0] * len, start[1] + v[1] * len]], w, c: COL.ink, dash: b.kind === "faint" ? [w * 3, w * 2.2] : null, cap: "butt" });
  }
  if (rr) out.push({ t: "c", x: J[0], y: J[1], r: rr, w: L.road, c: COL.ink, f: null });
  // the stem and its dot
  out.push({ t: "l", pts: [foot, [J[0], J[1] + rr]], w: pw, c: col, dash, cap: "butt" });
  out.push({ t: "c", x: foot[0], y: foot[1], r: L.dot / 2, w: 0, c: null, f: col });
  // the way out (round a roundabout, never back down the stem)
  const e = rr ? Math.max(-165, Math.min(165, t.exit)) : t.exit, side = e < 0 ? -1 : 1;
  if (Math.abs(e) > 150 && !rr) {
    const r = s * 0.11, p1 = [J[0], J[1] - s * 0.12], p2 = [p1[0] + side * 2 * r, p1[1]], k = 4 * r / 3;   // one cubic for the half circle
    const end = [p2[0], Math.min(p2[1] + s * 0.32, box[3])];
    const head = arrowHead(end, [0, 1], pw); head.f = col;
    out.push({ t: "p", d: [["M", J[0], J[1]], ["L", p1[0], p1[1]], ["C", p1[0], p1[1] - k, p2[0], p2[1] - k, p2[0], p2[1]], ["L", end[0], end[1] - head._len * 0.8]], w: pw, c: col, f: null, dash, cap: "butt" });
    out.push(head);
  } else {
    const v = dirv(e), start = [J[0] + v[0] * rr, J[1] + v[1] * rr];
    const len = Math.min(s * 0.38, reach(start, v, box)), tip = [start[0] + v[0] * len, start[1] + v[1] * len];
    const head = arrowHead(tip, v, pw); head.f = col;
    out.push({ t: "l", pts: [rr ? start : J, [tip[0] - v[0] * head._len * 0.8, tip[1] - v[1] * head._len * 0.8]], w: pw, c: col, dash, cap: "butt" });
    out.push(head);
  }
  return out;
}

// ---- vocabulary --------------------------------------------------------------------------------------------------------
const CAUTION = { 1: "Take care when approaching", 2: "Slow down for a hazard", 3: "Danger: slow significantly" };
// quick-insert terms. Layout A: plain English as stage-rally route books print it. Layout B: the codes printed in the
// FIA lexicon, with the meanings matched by list order and common usage (check against the lexicon before relying on them)
const CODES_A = [
  ["Directions", ["L", "R", "KEEP L", "KEEP R", "STOP", "YIELD"]],
  ["Odometer", ["ZERO ODO", "RESET ODO", "ODO CHECK"]],
  ["Controls", ["ATC", "FTC", "MTC", "START SS", "FINISH SS"]],
  ["Road", ["CREST", "DIP", "JUMP", "BRIDGE", "CATTLE GUARD", "GATE", "WASH", "NC"]],
];
const CODE_MEANING_A = { ATC: "Arrival time control", FTC: "Finish time control", MTC: "Main time control", NC: "Do not cut", "START SS": "Start of special stage", "FINISH SS": "Finish of special stage" };
const CODES_B = [
  ["Track", [["P", "Track or piste"], ["PP", "Principal track"], ["P//", "Parallel track"], ["RO", "Road"], ["HP", "Off track"], ["SA", "Sandy"], ["GV", "Gravel"]]],
  ["Instructions", [["FPP", "Follow principal track"], ["FRO", "Follow road"], ["kpL", "Keep left"], ["kpR", "Keep right"], ["kpS", "Keep straight"], ["QT", "Leave"], ["RP", "Rejoin"]]],
  ["Qualifiers", [["CX", "Stony"], ["E3", "Narrow"], ["ORN", "Ruts"], ["EFF", "Collapsed"], ["BAD", "Bad"], ["NBX", "Many"], ["TJS", "Always"], ["IMP", "Imperative"], ["BTW", "Between"], ["IN", "In"], ["ET", "And, next"], ["A", "At"]]],
  ["Dunes", [["DN", "Dune"], ["DNX", "Dunes"], ["DNT", "Small dune"], ["L1", "Dune difficulty 1"], ["L2", "Dune difficulty 2"], ["L3", "Dune difficulty 3"]]],
];
const CODE_MEANING_B = {}; for (const [, list] of CODES_B) for (const [c, m] of list) CODE_MEANING_B[c.toUpperCase()] = [c, m];
/** The codes a set of rows uses, for the legend. Layout B codes are matched case-insensitively as whole words. */
function codesUsed(rows, layout) {
  const found = new Map();
  for (const r of rows) {
    const words = String(r.info || "").toUpperCase().split(/[\s,.;·]+/);
    if (layout === "A") { const t = " " + words.join(" ") + " "; for (const k of Object.keys(CODE_MEANING_A)) if (t.includes(" " + k + " ")) found.set(k, CODE_MEANING_A[k]); }
    else for (const w of words) if (CODE_MEANING_B[w] && w.length > 1) found.set(CODE_MEANING_B[w][0], CODE_MEANING_B[w][1]);
  }
  return [...found.entries()];
}

// ---- one row ---------------------------------------------------------------------------------------------------------------
function speedUnit(st) { return st.units === "km" ? "KM/H" : "MPH"; }
function rect(x, y, w, h, lw, c, f, dash) { return { t: "r", x, y, w, h, lw, c, f, dash }; }
function text(x, y, s, pt, bold, c, a) { return { t: "x", x, y, s: String(s), size: pt, b: !!bold, c: c || COL.ink, a: a || "l" }; }
function hline(x1, x2, y, w, c, dash) { return { t: "l", pts: [[x1, y], [x2, y]], w, c, dash: dash || null, cap: "butt" }; }
function bannerPrims(row, st, L) {
  const x0 = (L.stripW - L.printW) / 2, h = L.rowH, out = [];
  // framed, not filled: a thermal roll or an ink cartridge should not spend a black block per section
  const big = st.layout === "A" ? 10 : 18, small = st.layout === "A" ? 6.5 : 10, tab = small * PT + 2.4;
  out.push(rect(x0 + 0.4, 1.2, L.printW - 0.8, h - 2.4, st.layout === "A" ? 0.8 : 1.4, COL.ink, null));
  out.push(rect(x0 + 0.4, 1.2, L.printW - 0.8, tab, 0, null, COL.ink));
  const kind = (row.sectionKind === "stage" ? "STAGE" : "TRANSIT") + " · " + fmtH(row.lengthH, st) + " " + st.units.toUpperCase();
  out.push(text(x0 + 2, 1.2 + tab - 1.2, "SECTION " + row.index + " · " + kind, small, true, COL.white));
  const name = fitText(String(row.name || "").toUpperCase(), L.printW - 4, h - tab - 5, big, small, true);
  name.lines.forEach((ln, i) => out.push(text(x0 + 2, 1.2 + tab + 1.6 + name.size * PT * 0.8 + i * name.lh, ln, name.size, true)));
  return { prims: out, overflow: name.overflow, bad: winAnsi(row.name).bad };
}
function rowPrimsA(row, st, L) {
  if (row.kind === "banner") return bannerPrims(row, st, L);
  const x0 = (L.stripW - L.printW) / 2, out = [], bandY = 0.6, boxY = bandY + L.band;
  const ink = COL.ink, red = st.colour ? COL.red : ink;
  // the mileage band: overall left, delta right, what is left to the section's end between them
  const base = bandY + L.band - 1.3;
  out.push(text(x0 + 0.4, base, fmtH(row.overall, st), L.mainPt, true));
  const dTxt = fmtH(row.delta, st); out.push(text(x0 + L.printW - 0.4, base, dTxt, L.secondPt, true, ink, "r"));
  if (st.decline) out.push(text(x0 + L.printW - 0.4 - textWidth(dTxt, L.secondPt, true) - 2.2, base, "TO GO " + fmtH(row.decline, st), L.smallPt, false, ink, "r"));
  // boxes
  out.push(rect(x0, boxY, L.tulipBox, L.boxH, L.frame, ink, null));
  out.push(rect(x0 + L.tulipBox, boxY, L.infoW, L.boxH, L.frame, ink, null));
  out.push(text(x0 + 0.7, boxY + L.numPt * PT + 0.5, row.n, L.numPt, false, ink));
  if (row.kind === "cue") out.push(...tulipPrims(row.tulip, x0, boxY, L.square, L, st.colour));
  else out.push(...startFinishPrims(row, x0, boxY, L.square, L, st));
  out.push(...symbolPrims(row, x0, boxY, L.square, L, ink));
  // info: caution marks first, as NASA's route books print them
  let info = row.info || "";
  if (row.speed && !/SPEED/.test(info)) info = (info ? info + " · " : "") + "SPEED " + row.speed + " " + speedUnit(st);
  const marks = row.caution ? "!".repeat(row.caution) + " " : "";
  const pad = 1, fit = fitText(marks + info, L.infoW - 2 * pad, L.boxH - 2 * pad, L.textPt, L.textFloor, true);
  fit.lines.forEach((ln, i) => {
    const y = boxY + pad + fit.size * PT * 0.8 + i * fit.lh;
    if (i === 0 && marks && red !== ink) { out.push(text(x0 + L.tulipBox + pad, y, marks, fit.size, true, red)); out.push(text(x0 + L.tulipBox + pad + textWidth(marks, fit.size, true), y, ln.slice(marks.length), fit.size, true)); }
    else out.push(text(x0 + L.tulipBox + pad, y, ln, fit.size, true));
  });
  // a danger 3 row is framed: red, or doubled in black and white
  if (row.caution === 3) { out.push(rect(x0 - 0.9, 0.3, L.printW + 1.8, L.rowH - 0.6, 0.45, red, null)); if (red === ink) out.push(rect(x0 - 0.3, 0.9, L.printW + 0.6, L.rowH - 1.8, 0.25, ink, null)); }
  out.push(hline(x0, x0 + L.printW, L.rowH - 0.15, 0.15, COL.rule));
  return { prims: out, overflow: fit.overflow, bad: winAnsi(marks + info).bad };
}
function rowPrimsB(row, st, L) {
  if (row.kind === "banner") return bannerPrims(row, st, L);
  const x0 = (L.stripW - L.printW) / 2, out = [], h = L.rowH, ink = COL.ink;
  const red = st.colour ? COL.red : ink, xt = x0 + L.distW, xn = xt + L.tulipW;
  // row frames first, so the boxes draw over them: a speed zone in orange (dashed in black and white)
  if (row.inZone) out.push(rect(x0 + 0.4, 0.4, L.printW - 0.8, h - 0.8, 0.9, st.colour ? COL.orange : ink, null, st.colour ? null : [2.4, 1.6]));
  out.push(rect(x0, 0, L.distW, h, L.frame, ink, null), rect(xt, 0, L.tulipW, h, L.frame, ink, null), rect(xn, 0, L.notesW, h, L.frame, ink, null));
  // distance box: total, partial (boxed; a short one marked), row number, waypoint and zone markers
  out.push(text(x0 + 1.5, 1.2 + L.numPt * PT, row.n, L.numPt, false, ink));
  out.push(text(x0 + L.distW - 2, 11.5, fmtH(row.overall, st), L.mainPt, true, ink, "r"));
  const pTxt = fmtH(row.delta, st), pw = textWidth(pTxt, L.secondPt, true), px = x0 + L.distW - 2.5, py = 22.5, ph = L.secondPt * PT * 1.05;
  if (row.shortPartial) {
    out.push(rect(px - pw - 1.4, py - ph + 0.4, pw + 2.8, ph + 1.6, 0.3, ink, st.colour ? COL.pink : ink));
    out.push(text(px, py, pTxt, L.secondPt, true, st.colour ? ink : COL.white, "r"));
    const ax = px - pw - 4.6; out.push({ t: "l", pts: [[ax, py + 1.5], [ax, py - ph + 1.8]], w: 0.6, c: ink, dash: null, cap: "butt" }, { t: "p", d: [["M", ax, py - ph], ["L", ax - 1.2, py - ph + 2.2], ["L", ax + 1.2, py - ph + 2.2], ["Z"]], w: 0, c: null, f: ink });
  } else { out.push(rect(px - pw - 1.4, py - ph + 0.4, pw + 2.8, ph + 1.6, 0.3, ink, null)); out.push(text(px, py, pTxt, L.secondPt, true, ink, "r")); }
  let mx = x0 + 5.5;
  const marker = (label, fill, dashed) => { out.push({ t: "c", x: mx, y: h - 6, r: 3.6, w: 0.45, c: ink, f: fill, dash: dashed ? [1, 0.7] : null }); out.push(text(mx, h - 6 + (label.length > 1 ? 1.1 : 1.6), label, label.length > 1 ? 7 : 10, true, fill && fill !== COL.white && st.colour && fill !== COL.green ? COL.white : ink, "c")); mx += 8.4; };
  if (row.wp) marker(row.wp.type, null, false);
  if (row.zone === "DZ") marker("DZ", st.colour ? COL.orange : null, !st.colour);
  if (row.zone === "FZ") marker("FZ", st.colour ? COL.green : null, false);
  // tulip box: the drawing, cautions in red, the CAP, symbols
  const sx = xt + (L.tulipW - L.square) / 2, sy = (h - L.square) / 2;
  if (row.kind === "cue") out.push(...tulipPrims(row.tulip, sx, sy, L.square, L, st.colour));
  else out.push(...startFinishPrims(row, sx, sy, L.square, L, st));
  out.push(...symbolPrims(row, sx, sy, L.square, L, ink));
  if (row.caution) out.push(text(xt + 1.8, 7.2, "!".repeat(row.caution), 16, true, red));
  if (row.capShow && row.kind === "cue") {
    const c = String(row.cap), cw = textWidth(c, 11, true), cx = xt + L.tulipW - 2 - cw, cy = h - 2.6;
    out.push(rect(cx - 1, cy - 4.4, cw + 2, 5.6, st.colour ? 0 : 0.35, st.colour ? null : ink, st.colour ? COL.yellow : null));
    out.push(text(cx, cy, c, 11, true, ink));
  }
  // notes, with a speed limit sign in the corner when there is one
  let notes = row.info || "", rightPad = 0;
  if (row.speed) {
    const r = 5.2, cx = xn + L.notesW - r - 1.8, cy = r + 1.8; rightPad = 2 * r + 2.5;
    out.push({ t: "c", x: cx, y: cy, r, w: st.colour ? 1.3 : 1.6, c: st.colour ? COL.red : ink, f: COL.white });
    out.push(text(cx, cy + 1.6, row.speed, row.speed >= 100 ? 9 : 11, true, ink, "c"));
  }
  const pad = 1.6, fit = fitText(notes, L.notesW - 2 * pad - rightPad, h - 2 * pad, L.textPt, L.textFloor, true);
  fit.lines.forEach((ln, i) => out.push(text(xn + pad, pad + fit.size * PT * 0.8 + i * fit.lh, ln, fit.size, true)));
  // danger 3: the whole row framed in red, or doubled in black and white
  if (row.caution === 3) { out.push(rect(x0 - 1.2, -0.2, L.printW + 2.4, h + 0.4, 0.9, red, null)); if (red === ink) out.push(rect(x0 + 0.8, 0.8, L.printW - 1.6, h - 1.6, 0.4, ink, null)); }
  return { prims: out, overflow: fit.overflow, bad: winAnsi(notes).bad };
}
/** START and FINISH rows: a flag, since there is no junction to draw. */
function startFinishPrims(row, x, y, s, L, st) {
  const col = st.colour ? COL.path : COL.ink, cx = x + s / 2;
  const out = [{ t: "l", pts: [[cx, y + s * 0.92], [cx, y + s * 0.22]], w: L.path, c: col, dash: null, cap: "butt" }, { t: "c", x: cx, y: y + s * 0.92, r: L.dot / 2, w: 0, c: null, f: col }];
  out.push(...glyphPrims(row.kind === "finish" ? "ss_finish" : "ss_start", cx + s * 0.02, y + s * 0.12, s * 0.4, COL.ink));
  return out;
}
/** Symbols in the tulip square's corners: left or right of the stem, before (low) or after (high) the junction. */
function symbolPrims(row, x, y, s, L, color) {
  const out = [], g = Math.min(L.glyph, s * 0.3), m = s * 0.04;
  const pos = { la: [x + m, y + s * 0.1], ra: [x + s - m - g, y + s * 0.1], lb: [x + m, y + s * 0.66], rb: [x + s - m - g, y + s * 0.66] };
  const used = {};
  for (const sym of row.symbols || []) { let p = pos[sym.slot] || pos.ra; const k = used[sym.slot] = (used[sym.slot] || 0) + 1; if (k > 1) p = [p[0] + (sym.slot[0] === "l" ? 1 : -1) * (g + m) * (k - 1), p[1]]; out.push(...glyphPrims(sym.id, p[0], p[1], g, color)); }
  return out;
}
function rowPrims(row, st, L) { return st.layout === "B" ? rowPrimsB(row, st, L) : rowPrimsA(row, st, L); }

// ---- the head of the chart: title, totals, ruler, legend -------------------------------------------------------------------
function headPrims(chart, st, L, date) {
  const x0 = (L.stripW - L.printW) / 2, out = [], A = st.layout === "A";
  const big = A ? 9 : 16, small = A ? 6 : 9, lh = small * PT * 1.3;
  let y = 1.5 + big * PT;
  const title = fitText((st.title || "Roadbook").toUpperCase(), L.printW, big * PT * 2.4, big, small, true);
  title.lines.slice(0, 2).forEach((ln, i) => out.push(text(x0, y + i * title.lh, ln, title.size, true)));
  y += (Math.min(2, title.lines.length) - 1) * title.lh + lh * 1.1;
  const cueRows = chart.rows.filter(r => r.kind !== "banner").length;
  const lines = [
    (A ? "STAGE-RALLY SCROLL CHART" : "ROADBOOK") + " · " + (st.units === "km" ? "KILOMETRES" : "MILES"),
    fmtH(chart.totalH, st) + " " + st.units.toUpperCase() + " · " + cueRows + " ROWS" + (chart.sections > 1 ? " · " + chart.sections + " SECTIONS" : ""),
    "MADE " + date + (st.scale !== 1 ? " · SCALE " + st.scale.toFixed(3) : ""),
  ];
  for (const ln of lines) { out.push(text(x0, y, ln, small, false)); y += lh; }
  // the ruler, to check the print is at 100 %
  y += 1.5;
  const rx = x0, rl = L.ruler;
  out.push(hline(rx, rx + rl, y, 0.25, COL.ink));
  for (let mm = 0; mm <= rl; mm += 5) out.push({ t: "l", pts: [[rx + mm, y], [rx + mm, y - (mm % 10 === 0 ? 2.2 : 1.2)]], w: 0.2, c: COL.ink, dash: null, cap: "butt" });
  y += small * PT + 1.4;
  out.push(text(rx, y, `PRINT AT 100%: THIS LINE IS ${rl} MM`, small, true));
  y += lh * 1.2;
  // the legend: every mark, symbol and code this chart uses
  const items = [];
  const rows = chart.rows;
  const cautions = [...new Set(rows.map(r => r.caution).filter(Boolean))].sort();
  for (const c of cautions) items.push({ mark: "!".repeat(c), label: CAUTION[c], red: true });
  const syms = [...new Set(rows.flatMap(r => (r.symbols || []).map(s => s.id)))].filter(id => GLYPH[id]);
  for (const id of syms) items.push({ glyph: id, label: GLYPH[id].name });
  const wps = [...new Set(rows.filter(r => r.wp).map(r => r.wp.type))];
  for (const w of wps) items.push({ circle: w, label: "Waypoint, " + WP_TYPES[w] });
  if (rows.some(r => r.zone)) { items.push({ circle: "DZ", label: "Speed zone starts" }, { circle: "FZ", label: "Speed zone ends" }); }
  if (!A && rows.some(r => r.capShow && r.kind === "cue")) items.push({ mark: "CAP", label: "Heading in degrees, true north", cap: true });
  if (!A && rows.some(r => r.shortPartial)) items.push({ mark: "0,00", label: "Short partial, under 400 m", short: true });
  for (const [code, meaning] of codesUsed(rows, st.layout)) items.push({ mark: code, label: meaning });
  if (items.length) {
    out.push(text(x0, y, "LEGEND", small, true)); y += lh * 0.6;
    const cols = A ? 1 : 2, colW = L.printW / cols, ih = A ? 4.6 : 8.6, gs = A ? 3.6 : 6.4;
    items.forEach((it, i) => {
      const cx = x0 + (i % cols) * colW, cy = y + Math.floor(i / cols) * ih;
      const mid = cy + ih / 2 + small * PT * 0.35;
      if (it.glyph) out.push(...glyphPrims(it.glyph, cx, cy + (ih - gs) / 2, gs, COL.ink));
      else if (it.circle) { out.push({ t: "c", x: cx + gs / 2, y: cy + ih / 2, r: gs / 2, w: 0.3, c: COL.ink, f: null }); out.push(text(cx + gs / 2, cy + ih / 2 + small * PT * 0.35, it.circle, it.circle.length > 1 ? small * 0.7 : small, true, COL.ink, "c")); }
      else {
        const col = it.red && st.colour ? COL.red : COL.ink;
        if (it.cap) out.push(rect(cx - 0.3, cy + 0.8, textWidth(it.mark, small, true) + 0.6, ih - 1.6, st.colour ? 0 : 0.3, st.colour ? null : COL.ink, st.colour ? COL.yellow : null));
        if (it.short) out.push(rect(cx - 0.3, cy + 0.8, textWidth(it.mark, small, true) + 0.6, ih - 1.6, 0, null, st.colour ? COL.pink : COL.ink));
        out.push(text(cx, mid, it.mark, small, true, it.short && !st.colour ? COL.white : col));
      }
      const lx = cx + Math.max(gs, it.mark ? textWidth(it.mark, small, true) : 0) + 1.6;
      const fit = fitText(it.label, colW - (lx - cx) - 1, ih, small, small * 0.8, false);
      out.push(text(lx, mid, fit.lines[0] || "", fit.size, false));
    });
    y += Math.ceil(items.length / cols) * ih;
  }
  y += 1.5;
  const rowsTall = Math.max(1, Math.ceil(y / L.rowH));
  out.push(hline(x0, x0 + L.printW, rowsTall * L.rowH - 0.15, 0.15, COL.rule));
  return { prims: out, rows: rowsTall };
}

// ---- pages ------------------------------------------------------------------------------------------------------------------
function shift(prims, dx, dy) {
  return prims.map(p => {
    const q = Object.assign({}, p);
    if (p.t === "l") q.pts = p.pts.map(([x, y]) => [x + dx, y + dy]);
    else if (p.t === "p") q.d = p.d.map(op => op.length === 1 ? op : op.map((v, i) => i === 0 ? v : v + (i % 2 ? dx : dy)));
    else { q.x = p.x + dx; q.y = p.y + dy; }
    return q;
  });
}
/**
 * The chart as pages of primitives. A roll is one strip per page with no margins, so pages butt into one unbroken
 * strip, with a leader at each end. A sheet carries one to three strips with trim marks and a page tab outside them.
 */
function layout(chart, st, opts) {
  st = settings(st); opts = opts || {};
  const L = LAYOUTS[st.layout], P = PRESETS[st.preset];
  const date = opts.date || new Date().toISOString().slice(0, 10);
  const head = headPrims(chart, st, L, date);
  // every slot is one row tall; the head block takes several
  const slots = [{ head: true, prims: head.prims, h: head.rows }];
  const flags = [], badChars = new Set();
  for (const row of chart.rows) {
    const r = rowPrims(row, st, L);
    if (r.overflow) flags.push({ row: row.n, id: row.id, kind: "overflow", text: row.kind === "banner" ? "A section name is too long for its banner" : `Row ${row.n}: the words do not fit even at the smallest size; shorten them` });
    for (const ch of r.bad || "") badChars.add(ch);
    slots.push({ row, prims: r.prims, h: 1 });
  }
  const totalRows = slots.reduce((a, s) => a + s.h, 0);
  const pages = [];
  if (P.roll) {
    const per = st.rollRows; let i = 0;
    while (i < slots.length) {
      const page = []; let used = 0;
      while (i < slots.length && (used + slots[i].h <= per || used === 0)) { page.push(slots[i]); used += slots[i].h; i++; }
      pages.push(page);
    }
    return { pages: pages.map((pg, k) => {
      const lead = (k === 0 ? LEADER : 0), tail = (k === pages.length - 1 ? LEADER : 0);
      const prims = []; let y = lead;
      for (const s of pg) { prims.push(...shift(s.prims, 0, y)); y += s.h * L.rowH; }
      if (k === 0) prims.push(text(L.stripW / 2, LEADER - 6, "LEADER · TAPE TO THE SPOOL", 6, false, COL.grey, "c"));
      if (tail) prims.push(text(L.stripW / 2, y + 8, "END OF CHART", 6, false, COL.grey, "c"));
      return { w: L.stripW, h: y + tail, prims };
    }), stats: stats(), warnings: flags.concat(capWarn()), badChars: [...badChars] };
  }
  // sheets: fill strip after strip, page after page
  const per = P.perStrip, strips = P.strips;
  const stripRows = []; let cur = [], used = 0;
  for (const s of slots) { if (used + s.h > per && cur.length) { stripRows.push(cur); cur = []; used = 0; } cur.push(s); used += s.h; }
  if (cur.length) stripRows.push(cur);
  const sheetCount = Math.ceil(stripRows.length / strips), gap = (P.pageW - strips * L.stripW) / (strips + 1);
  let rowFrom = 1;
  for (let k = 0; k < sheetCount; k++) {
    const prims = [], first = rowFrom;
    for (let j = 0; j < strips; j++) {
      const strip = stripRows[k * strips + j]; if (!strip) break;
      const sx = gap + j * (L.stripW + gap); let y = P.top;
      for (const s of strip) { prims.push(...shift(s.prims, sx, y)); y += s.h * L.rowH; if (s.row) rowFrom = s.row.n + 1; }
      // trim marks: the strip's edges, just outside the rows
      const top = P.top, bot = P.top + per * L.rowH, t = 3.5, w = 0.1;
      for (const x of [sx, sx + L.stripW]) prims.push({ t: "l", pts: [[x, top - 1 - t], [x, top - 1]], w, c: COL.ink, dash: null, cap: "butt" }, { t: "l", pts: [[x, bot + 1], [x, bot + 1 + t]], w, c: COL.ink, dash: null, cap: "butt" });
      prims.push(text(sx + L.stripW / 2, top - 1.6, "STRIP " + (k * strips + j + 1), 5.5, false, COL.grey, "c"));
    }
    const last = rowFrom - 1;
    prims.push(text(P.pageW / 2, P.pageH - 3.5, `${(st.title || "Roadbook").toUpperCase()} · ROWS ${first}–${Math.max(first, last)} · SHEET ${k + 1} OF ${sheetCount} · PRINT AT 100%`, 6.5, false, COL.grey, "c"));
    pages.push({ w: P.pageW, h: P.pageH, prims });
  }
  return { pages, stats: stats(), warnings: flags.concat(capWarn()), badChars: [...badChars] };

  function stats() {
    const strip = totalRows * L.rowH + (P.roll ? 2 * LEADER : 0);
    return { rows: chart.rows.filter(r => r.kind !== "banner").length, slots: totalRows, stripMM: strip, sheets: P.roll ? 0 : Math.ceil(Math.ceil(totalRows / P.perStrip) / P.strips), pages: P.roll ? Math.ceil(totalRows / st.rollRows) : 0, finish: P.finish };
  }
  function capWarn() {
    if (st.layout !== "B") return [];
    const m = totalRows * L.rowH / 1000;
    if (m > 18) return [{ kind: "length", text: `The strip is ${m.toFixed(1)} m long: more than the 18 m an F2R RB850 holds` }];
    if (m > 12) return [{ kind: "length", text: `The strip is ${m.toFixed(1)} m long: more than the 12 m a smaller holder (F2R RB730) takes; an RB850 holds 18 m` }];
    return [];
  }
}

// ---- SVG, for the preview ------------------------------------------------------------------------------------------------
const f2 = v => (Math.round(v * 100) / 100).toString();
function svgDash(d) { return d ? ` stroke-dasharray="${d.map(f2).join(" ")}"` : ""; }
function primsToSvg(prims, w, h, attrs) {
  const out = [];
  for (const p of prims) {
    if (p.t === "l") out.push(`<polyline points="${p.pts.map(q => f2(q[0]) + "," + f2(q[1])).join(" ")}" fill="none" stroke="${p.c}" stroke-width="${f2(p.w)}"${svgDash(p.dash)} stroke-linecap="${p.cap === "round" ? "round" : "butt"}"/>`);
    else if (p.t === "p") out.push(`<path d="${p.d.map(op => op[0] + op.slice(1).map(f2).join(" ")).join("")}" fill="${p.f || "none"}"${p.c && p.w ? ` stroke="${p.c}" stroke-width="${f2(p.w)}" stroke-linejoin="round"` : ""}${svgDash(p.dash)}/>`);
    else if (p.t === "c") out.push(`<circle cx="${f2(p.x)}" cy="${f2(p.y)}" r="${f2(p.r)}" fill="${p.f || "none"}"${p.c && p.w ? ` stroke="${p.c}" stroke-width="${f2(p.w)}"` : ""}${svgDash(p.dash)}/>`);
    else if (p.t === "r") out.push(`<rect x="${f2(p.x)}" y="${f2(p.y)}" width="${f2(p.w)}" height="${f2(p.h)}" fill="${p.f || "none"}"${p.c && p.lw ? ` stroke="${p.c}" stroke-width="${f2(p.lw)}"` : ""}${svgDash(p.dash)}/>`);
    else if (p.t === "x") { const s = winAnsi(p.s).codes.map(c => String.fromCharCode(c === 63 && false ? 63 : c)).join(""); out.push(`<text x="${f2(p.x)}" y="${f2(p.y)}" font-family="Helvetica, Arial, sans-serif" font-size="${f2(p.size * PT)}" font-weight="${p.b ? 700 : 400}" fill="${p.c}"${p.a === "c" ? ' text-anchor="middle"' : p.a === "r" ? ' text-anchor="end"' : ""}>${escXml(decodeWin(s))}</text>`); }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${f2(w)} ${f2(h)}" width="${f2(w)}mm" height="${f2(h)}mm"${attrs || ""}>${out.join("")}</svg>`;
}
function decodeWin(s) { let o = ""; for (const ch of s) { const c = ch.charCodeAt(0); o += c >= 128 && c < 160 ? WIN_HIGH[c - 128] : ch; } return o; }
function escXml(s) { return s.replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
/** One row as SVG, in its own strip-wide box (the preview list). */
function rowSvg(row, st, attrs) { st = settings(st); const L = LAYOUTS[st.layout], r = rowPrims(row, st, L); return { svg: primsToSvg(r.prims, L.stripW, L.rowH, attrs), overflow: r.overflow }; }
function headSvg(chart, st, attrs, date) { st = settings(st); const L = LAYOUTS[st.layout], h = headPrims(chart, st, L, date || new Date().toISOString().slice(0, 10)); return primsToSvg(h.prims, L.stripW, h.rows * L.rowH, attrs); }
/** A tulip alone, in a square of side 10 (the editor draws its own handles over it). */
function tulipSvg(t, layout, colour, attrs) { const L = LAYOUTS[layout || "B"]; const k = 100 / L.square; const scaled = Object.assign({}, L, { path: L.path * k, road: L.road * k, track: L.track * k, dot: L.dot * k }); return primsToSvg(tulipPrims(t, 0, 0, 100, scaled, colour), 100, 100, attrs); }

root.RoadbookChart = {
  LAYOUTS, PRESETS, ROLL_ROWS, LEADER, MAX_PAGE_MM, COL, GLYPHS, GLYPH, GROUPS, CODES_A, CODES_B, CAUTION, WP_TYPES, PATHS, KINDS, SLOTS,
  settings, settingsForFile, cleanRally, parseRally, parseSettings,
  cumulative, bearing, turnAngle, pointAt, headings, passes, placeAt, armsToBranches,
  buildRows, layout, rowPrims: (row, st) => { st = settings(st); return rowPrims(row, st, LAYOUTS[st.layout]); }, rowSvg, headSvg, tulipSvg, primsToSvg, tulipPrims, glyphPrims,
  winAnsi, textWidth, fitText, fmtH, hundredths,
};
})(typeof window !== "undefined" ? window : globalThis);
