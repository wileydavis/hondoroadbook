// GPX in the Worker: parse what the app and the planner write, strip what should never be published
// (timestamps, device extensions, anything we do not understand) and compute the library's metadata.
// Cues follow the app's convention: a <wpt> whose <type> is cue:LEFT, danger:2 or note, words in <desc>.

const TURNS = new Set(["STRAIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "LEFT", "RIGHT", "SHARP_LEFT", "SHARP_RIGHT", "UTURN", "KEEP_LEFT", "KEEP_RIGHT", "ROUNDABOUT"]);

export function parseGpx(text) {
  if (!/<gpx[\s>]/.test(text)) throw new Error("not a GPX file");
  const name = inner(text, "name", /<metadata>[\s\S]*?<\/metadata>/) || inner(text, "name", /<trk>[\s\S]*?<\/trk>/) || "";
  const pts = [];
  for (const m of text.matchAll(/<trkpt\s+([^>]*)>([\s\S]*?)<\/trkpt>|<trkpt\s+([^>]*)\/>/g)) {
    const attrs = m[1] ?? m[3]; const body = m[2] ?? "";
    const lat = num(attr(attrs, "lat")), lon = num(attr(attrs, "lon"));
    if (lat == null || lon == null) continue;
    const ele = num(inner(body, "ele"));
    pts.push(ele == null ? [lon, lat] : [lon, lat, ele]);
  }
  // a <rte> from a planner that wrote no track still has a line
  if (pts.length === 0) for (const m of text.matchAll(/<rtept\s+([^>]*)>([\s\S]*?)<\/rtept>|<rtept\s+([^>]*)\/>/g)) {
    const attrs = m[1] ?? m[3]; const lat = num(attr(attrs, "lat")), lon = num(attr(attrs, "lon"));
    if (lat != null && lon != null) pts.push([lon, lat]);
  }
  const cues = [];
  for (const m of text.matchAll(/<wpt\s+([^>]*)>([\s\S]*?)<\/wpt>/g)) {
    const lat = num(attr(m[1], "lat")), lon = num(attr(m[1], "lon"));
    if (lat == null || lon == null) continue;
    const type = (inner(m[2], "type") || "").trim().toLowerCase();
    const words = (inner(m[2], "desc") || inner(m[2], "name") || "").trim();
    if (type.startsWith("cue:")) { const t = type.slice(4).toUpperCase(); if (TURNS.has(t)) cues.push({ lon, lat, turn: t, text: words }); }
    else if (type.startsWith("danger")) { const lv = Math.min(3, Math.max(1, Number(type.split(":")[1] || 2) || 2)); cues.push({ lon, lat, turn: "DANGER", level: lv, text: words }); }
    else if (type === "note" && words) cues.push({ lon, lat, turn: "NOTE", text: words });
  }
  const ext = /<roadbook>([\s\S]*?)<\/roadbook>/.exec(text);
  const meta = {};
  if (ext) { for (const k of ["mode", "paved", "unpaved", "planner"]) { const v = inner(ext[1], k); if (v) meta[k] = v; } }
  return { name: unesc(name).trim(), pts, cues, meta };
}

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
export function trimEnds(pts, meters) {
  if (!meters || pts.length < 4) return pts;
  const total = measure(pts).length;
  if (total < meters * 2 + 200) return pts;                 // too short to trim without losing the ride
  const cutFront = cutAt(pts, meters);
  const back = cutAt(pts.slice().reverse(), meters).reverse();
  // the two cuts in terms of the original: keep what both agree on
  const start = pts.length - cutFront.length, end = back.length;
  const out = [cutFront[0], ...pts.slice(start + 1, end - 1), back[back.length - 1]];
  return out.length >= 2 ? out : pts;
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

/** Cues that still sit on the (possibly trimmed) line. */
export function cuesOn(pts, cues, within = 150) {
  return cues.filter(c => { let best = Infinity; for (let i = 1; i < pts.length; i++) { const d = segDist([c.lon, c.lat], pts[i - 1], pts[i]); if (d < best) best = d; if (best < within) return true; } return best < within; });
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
