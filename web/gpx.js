// GPX in the Worker: parse what the app and the planner write, strip what should never be published
// (timestamps, device extensions, anything we do not understand) and compute the library's metadata.
// Cues follow the app's convention: a <wpt> whose <type> is cue:LEFT, danger:2 or note, words in <desc>.

export const TURNS = new Set(["STRAIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "LEFT", "RIGHT", "SHARP_LEFT", "SHARP_RIGHT", "UTURN", "KEEP_LEFT", "KEEP_RIGHT", "ROUNDABOUT"]);
export const MODES = new Set(["FAST", "TOWING", "BACKROADS", "DIRT", "RIDE", "QUIET", "GRAVEL"]);
export const LIMITS = { points: 200_000, cues: 5_000, text: 200 };

export class GpxError extends Error { constructor(m, status = 400) { super(m); this.status = status; } }

/**
 * The track (every trkpt, in order), the route points (rtept) separately, the cues, and our metadata.
 * Everything is validated here: finite coordinates in range, bounded counts, bounded words, a known
 * mode — the only values that ever reach storage or a page.
 */
export function parseGpx(text) {
  if (!/<gpx[\s>]/.test(text)) throw new GpxError("not a GPX file");
  const name = inner(text, "name", /<metadata>[\s\S]*?<\/metadata>/) || inner(text, "name", /<trk>[\s\S]*?<\/trk>/) || "";
  const pts = [], rte = [];
  for (const m of text.matchAll(/<trkpt\s+([^>]*?)(\/>|>([\s\S]*?)<\/trkpt>)/g)) {
    const attrs = m[1]; const body = m[3] ?? "";
    const lat = coord(attr(attrs, "lat"), 90), lon = coord(attr(attrs, "lon"), 180);
    if (lat == null || lon == null) throw new GpxError("a track point has a bad coordinate");
    if (pts.length >= LIMITS.points) throw new GpxError(`more than ${LIMITS.points.toLocaleString()} track points`, 413);
    const ele = num(inner(body, "ele"));
    pts.push(ele == null || Math.abs(ele) > 20000 ? [lon, lat] : [lon, lat, ele]);
  }
  for (const m of text.matchAll(/<rtept\s+([^>]*?)(\/>|>[\s\S]*?<\/rtept>)/g)) {
    const lat = coord(attr(m[1], "lat"), 90), lon = coord(attr(m[1], "lon"), 180);
    if (lat == null || lon == null) throw new GpxError("a route point has a bad coordinate");
    if (rte.length >= LIMITS.points) throw new GpxError("too many route points", 413);
    rte.push([lon, lat]);
  }
  const cues = [];
  for (const m of text.matchAll(/<wpt\s+([^>]*)>([\s\S]*?)<\/wpt>/g)) {
    const type = (inner(m[2], "type") || "").trim().toLowerCase();
    const isCue = type.startsWith("cue:") || type.startsWith("danger") || type === "note";
    if (!isCue) continue;                                           // marks and campsites in the same file are not cues
    const lat = coord(attr(m[1], "lat"), 90), lon = coord(attr(m[1], "lon"), 180);
    if (lat == null || lon == null) throw new GpxError("a cue has a bad coordinate");
    if (cues.length >= LIMITS.cues) throw new GpxError(`more than ${LIMITS.cues.toLocaleString()} cues`, 413);
    const words = unesc(inner(m[2], "desc") || inner(m[2], "name") || "").replace(/[\u0000-\u001f]/g, " ").trim().slice(0, LIMITS.text);
    if (type.startsWith("cue:")) { const t = type.slice(4).toUpperCase(); if (TURNS.has(t)) cues.push({ lon, lat, turn: t, text: words }); else throw new GpxError(`unknown cue type ${type.slice(0, 30)}`); }
    else if (type.startsWith("danger")) { const lv = Math.min(3, Math.max(1, Number(type.split(":")[1] || 2) || 2)); cues.push({ lon, lat, turn: "DANGER", level: lv, text: words }); }
    else if (words) cues.push({ lon, lat, turn: "NOTE", text: words });
  }
  const ext = /<roadbook>([\s\S]*?)<\/roadbook>/.exec(text);
  const meta = {};
  if (ext) {
    const mode = (inner(ext[1], "mode") || "").trim().toUpperCase(); if (MODES.has(mode)) meta.mode = mode;
    for (const k of ["paved", "unpaved"]) { const v = Number(inner(ext[1], k)); if (Number.isFinite(v) && v >= 0 && v < 1e8) meta[k] = Math.round(v); }
    if (inner(ext[1], "planner")) meta.planner = "1";
  }
  return { name: unesc(name).replace(/[\u0000-\u001f]/g, " ").trim().slice(0, 80), pts, rte, cues, meta };
}
function coord(s, max) { const v = num(s); return v != null && Math.abs(v) <= max ? v : null; }

/** Length in meters and the bounding box. */
export function measure(pts) {
  let len = 0; let minLon = 180, minLat = 90, maxLon = -180, maxLat = -90;
  for (let i = 0; i < pts.length; i++) {
    const [lon, lat] = pts[i];
    if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon; if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat;
    if (i > 0) len += dist(pts[i - 1], pts[i]);
  }
  return { length: len, bbox: pts.length ? [minLon, minLat, maxLon, maxLat] : null };
}

/** Drops the first and last `meters` of the line so a published track does not start at someone's door. */
/** Drops the first and last `meters` of the line so a published track does not start at someone's door. Returns null when the ride would not survive the trim: the caller must refuse, never publish untrimmed. */
export function trimEnds(pts, meters) {
  if (!meters) return pts;
  if (pts.length < 2) return null;
  const total = measure(pts).length;
  if (total < meters * 2 + 500) return null;               // under half a kilometre would be left
  const cutFront = cutAt(pts, meters);
  const back = cutAt(pts.slice().reverse(), meters).reverse();
  // the two cuts in terms of the original: keep what both agree on
  const start = pts.length - cutFront.length, end = back.length;
  const out = [cutFront[0], ...pts.slice(start + 1, end - 1), back[back.length - 1]];
  return out.length >= 2 ? out : null;
}
/** The line from `meters` along it to the end, starting with an interpolated point exactly there. */
function cutAt(pts, meters) {
  let acc = 0;
  for (let i = 1; i < pts.length; i++) {
    const d = dist(pts[i - 1], pts[i]);
    if (acc + d >= meters) {
      const t = d > 0 ? (meters - acc) / d : 0; const a = pts[i - 1], b = pts[i];
      const p = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]; if (a.length > 2 && b.length > 2) p.push(a[2] + (b[2] - a[2]) * t);
      return [p, ...pts.slice(i)];
    }
    acc += d;
  }
  return pts.slice(-1);
}

/** Cues that still sit on the (possibly trimmed) line: a cell index over the segments, so 5,000 cues on 200,000 points is thousands of comparisons, not a billion. */
export function cuesOn(pts, cues, within = 150) {
  if (!cues.length || pts.length < 2) return [];
  const idx = segmentIndex(pts);
  return cues.filter(c => idx.near([c.lon, c.lat], within));
}
export function segmentIndex(pts, cell = 0.01) {
  const cells = new Map(); const longs = [];
  const key = (cx, cy) => cx * 100003 + cy;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const x0 = Math.floor(Math.min(a[0], b[0]) / cell), x1 = Math.floor(Math.max(a[0], b[0]) / cell), y0 = Math.floor(Math.min(a[1], b[1]) / cell), y1 = Math.floor(Math.max(a[1], b[1]) / cell);
    // a long segment covers many cells; it is still listed in all of them (bounded by a cap, then every cell gets it through `long`)
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) { longs.push(i); continue; }
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) { const k = key(x, y); let l = cells.get(k); if (!l) cells.set(k, l = []); l.push(i); }
  }
  return {
    near(p, within) {
      const r = within / 111320 / cell + 1; const cx = Math.floor(p[0] / cell), cy = Math.floor(p[1] / cell); const R = Math.ceil(r);
      for (let x = cx - R; x <= cx + R; x++) for (let y = cy - R; y <= cy + R; y++) { const l = cells.get(key(x, y)); if (!l) continue; for (const i of l) if (segDist(p, pts[i - 1], pts[i]) < within) return true; }
      for (const i of longs) if (segDist(p, pts[i - 1], pts[i]) < within) return true;
      return false;
    },
  };
}

/** Douglas–Peucker down to at most `max` points, for the library map. */
export function simplify(pts, max = 160) {
  if (pts.length <= max) return pts.map(p => [round(p[0]), round(p[1])]);
  let tol = 0.0005;
  let out = dp(pts, tol);
  while (out.length > max && tol < 0.5) { tol *= 1.6; out = dp(pts, tol); }
  return out.map(p => [round(p[0]), round(p[1])]);
}

function dp(pts, tol) {
  const keep = new Uint8Array(pts.length); keep[0] = 1; keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop(); let far = -1, fd = 0;
    for (let i = a + 1; i < b; i++) { const d = perp(pts[i], pts[a], pts[b]); if (d > fd) { fd = d; far = i; } }
    if (far >= 0 && fd > tol) { keep[far] = 1; stack.push([a, far], [far, b]); }
  }
  const out = []; for (let i = 0; i < pts.length; i++) if (keep[i]) out.push(pts[i]);
  return out;
}
function perp(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1]; const l2 = dx * dx + dy * dy;
  if (l2 === 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
  const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2));
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}
function segDist(p, a, b) {
  // meters, with a local flat projection
  const k = Math.cos(p[1] * Math.PI / 180); const P = [p[0] * k, p[1]], A = [a[0] * k, a[1]], B = [b[0] * k, b[1]];
  return perp(P, A, B) * 111320;
}
export function dist(a, b) {
  const R = 6371000, r = Math.PI / 180; const dLat = (b[1] - a[1]) * r, dLon = (b[0] - a[0]) * r;
  const x = dLon * Math.cos((a[1] + b[1]) / 2 * r); return R * Math.hypot(dLat, x);
}

/** A clean GPX in the app's own format: the line, the cues, nothing else. */
export function writeGpx(name, pts, cues, meta = {}) {
  const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<gpx version="1.1" creator="Roadbook" xmlns="http://www.topografix.com/GPX/1/1">', `  <metadata><name>${esc(name)}</name>`];
  const keys = Object.keys(meta).filter(k => meta[k] != null && meta[k] !== "");
  if (keys.length) out.push("    <extensions><roadbook>" + keys.map(k => `<${k}>${esc(String(meta[k]))}</${k}>`).join("") + "</roadbook></extensions>");
  out.push("  </metadata>");
  for (const c of cues) {
    const type = c.turn === "DANGER" ? `danger:${c.level || 2}` : c.turn === "NOTE" ? "note" : `cue:${c.turn}`;
    out.push(`  <wpt lat="${c.lat}" lon="${c.lon}"><name>${esc(c.text || label(c.turn))}</name>${c.text ? `<desc>${esc(c.text)}</desc>` : ""}<type>${type}</type></wpt>`);
  }
  out.push(`  <trk><name>${esc(name)}</name><trkseg>`);
  for (const p of pts) out.push(`    <trkpt lat="${p[1]}" lon="${p[0]}">${p.length > 2 ? `<ele>${Math.round(p[2])}</ele>` : ""}</trkpt>`);
  out.push("  </trkseg></trk>", "</gpx>", "");
  return out.join("\n");
}

function label(t) { return ({ STRAIGHT: "Straight on", SLIGHT_LEFT: "Slight left", SLIGHT_RIGHT: "Slight right", LEFT: "Left", RIGHT: "Right", SHARP_LEFT: "Sharp left", SHARP_RIGHT: "Sharp right", UTURN: "U-turn", KEEP_LEFT: "Keep left", KEEP_RIGHT: "Keep right", ROUNDABOUT: "Roundabout", DANGER: "Danger", NOTE: "Note" })[t] || t; }
function inner(s, tag, within) { const scope = within ? (within.exec(s) || [""])[0] : s; const m = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`).exec(scope); return m ? m[1] : ""; }
function attr(attrs, k) { const m = new RegExp(`${k}\\s*=\\s*"([^"]*)"`).exec(attrs || ""); return m ? m[1] : null; }
function num(s) { if (s == null || s === "") return null; const v = Number(s); return Number.isFinite(v) ? v : null; }
function round(v) { return Math.round(v * 1e5) / 1e5; }
export function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
function unesc(s) { return String(s).replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&"); }
