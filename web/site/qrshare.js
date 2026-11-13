/*
 * Roadbook share code for the planner: the roadbook as a moving QR code that the phone's camera reads
 * (≡ → TRACK → SCAN A CODE FROM ANOTHER PHONE). Nothing goes through a server; the phone needs no signal.
 *
 * A port of the apps' own share code (core/…/QrShare.kt and QrCode.kt), byte for byte: the same packing of the track,
 * the same frames (the first K plain, the rest random XOR mixes from the same splitmix64 coins), the same base45 text,
 * the same QR symbols. web/test/qrshare.test.mjs checks it against frames the Kotlin wrote.
 *
 * What travels is what the app reads from the GPX the planner writes (parseGpx below is the app's parser, ported):
 * the name, the line, the cues with their words and roll-chart fields, the chart's settings.
 */
(function (root) {
  "use strict";
  const PREFIX = "RB1:", HEAD = 13;
  const ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const TURNS = new Set(["DEPART", "STRAIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "LEFT", "RIGHT", "SHARP_LEFT", "SHARP_RIGHT", "UTURN", "KEEP_LEFT", "KEEP_RIGHT", "ROUNDABOUT", "NOTE", "DANGER", "ARRIVE"]);

  // ---- the app's GPX reading (Gpx.parse), for what the planner writes ---------------------------------------------
  const unesc = s => s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, "&");
  const cleanRally = s => { if (s == null) return null; const t = s.trim(); return t.length >= 2 && t.length <= 4000 && t.startsWith("{") && t.endsWith("}") ? t : null; };
  function attr(tag, key) {
    const k = tag.indexOf(key + "="); if (k < 0) return null;
    const q = tag[k + key.length + 1]; if (q !== '"' && q !== "'") return null;
    const start = k + key.length + 2, end = tag.indexOf(q, start); if (end < 0) return null;
    const v = tag.substring(start, end).trim(); if (!/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(v)) return null; return parseFloat(v);
  }
  function fromWaypoint(lat, lon, type, name, desc) {
    const t = (type || "").trim().toLowerCase(), text = (desc.trim() ? desc : name).trim();
    if (t.startsWith("cue:")) { const turn = t.substring(4).toUpperCase(); return TURNS.has(turn) ? { lat, lon, turn, note: text, level: 0 } : null; }
    if (t.startsWith("danger")) { const i = t.indexOf(":"), v = i < 0 ? "2" : t.substring(i + 1); const n = /^[-+]?\d+$/.test(v) ? parseInt(v, 10) : NaN; return { lat, lon, turn: "DANGER", note: text, level: Number.isFinite(n) ? Math.min(3, Math.max(1, n)) : 2 }; }
    if (t === "note") return text ? { lat, lon, turn: "NOTE", note: text, level: 0 } : null;
    return null;
  }
  function parseGpx(text) {
    const lats = [], lons = [], cues = []; let name = "", mode = null, chart = null, i = 0, lastLat = NaN, lastLon = NaN;
    const n = text.length;
    while (i < n) {
      const lt = text.indexOf("<", i); if (lt < 0) break;
      const gt = text.indexOf(">", lt); if (gt < 0) break;
      const tag = text.substring(lt + 1, gt); i = gt + 1;
      if (tag.startsWith("trkpt")) {
        const lat = attr(tag, "lat"), lon = attr(tag, "lon");
        if (lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && (lat !== lastLat || lon !== lastLon)) { lats.push(lat); lons.push(lon); lastLat = lat; lastLon = lon; }
      } else if (tag.startsWith("wpt")) {
        const lat = attr(tag, "lat"), lon = attr(tag, "lon"), close = text.indexOf("</wpt>", i);
        if (lat != null && lon != null && close > 0) {
          const body = text.substring(i, close);
          const inner = t => { const a = body.indexOf(`<${t}>`); if (a < 0) return ""; const b = body.indexOf(`</${t}>`, a); return b < 0 ? "" : unesc(body.substring(a + t.length + 2, b).trim()); };
          const rally = cleanRally(inner("rally") || null);
          const chartNote = rally != null && inner("type").trim().toLowerCase() === "note" && inner("desc") === "";
          let cue;
          if (chartNote) { const nm = inner("name"); cue = { lat, lon, turn: "NOTE", note: nm !== "Note" && nm !== "NOTE" ? nm : "", level: 0 }; }
          else cue = fromWaypoint(lat, lon, inner("type"), inner("name"), inner("desc"));
          if (cue) { cue.rally = rally; cues.push(cue); }
          i = close + 6;
        }
      } else if (name === "" && tag === "name") {
        const close = text.indexOf("</name>", i); if (close > 0) { name = unesc(text.substring(i, close).trim()); i = close + 7; }
      } else if (tag === "chart") {
        const close = text.indexOf("</chart>", i); if (close > 0) { chart = cleanRally(unesc(text.substring(i, close))); i = close + 8; }
      } else if (tag === "fold:mode") {
        const close = text.indexOf("</fold:mode>", i); if (close > 0) { mode = text.substring(i, close).trim(); i = close + 12; }
      }
    }
    if (lats.length < 2) return null;
    return { name: name || "Track", lats, lons, rteLats: [], rteLons: [], rteNames: [], mode, chart, cues };
  }

  // ---- packing (QrShare.pack) ------------------------------------------------------------------------------------
  // Kotlin's round() goes half to even
  function e6(v) { const x = v * 1e6, f = Math.floor(x), d = x - f; return d > 0.5 ? f + 1 : d < 0.5 ? f : (f % 2 === 0 ? f : f + 1); }
  class Out {
    constructor() { this.a = []; }
    byte(v) { this.a.push(v & 0xff); }
    uint(v) { while (v >= 0x80) { this.byte((v % 128) | 0x80); v = Math.floor(v / 128); } this.byte(v); }
    sint(v) { this.uint(v >= 0 ? v * 2 : -v * 2 - 1); }
    str(s) { const u = new TextEncoder().encode(s); this.uint(u.length); for (const x of u) this.byte(x); }
    coords(la, lo) { this.uint(la.length); let pa = 0, po = 0; for (let i = 0; i < la.length; i++) { const a = e6(la[i]), o = e6(lo[i]); this.sint(a - pa); this.sint(o - po); pa = a; po = o; } }
    bytes() { return Uint8Array.from(this.a); }
  }
  function pack(t) {
    const w = new Out(); w.byte(1);
    w.str(t.name); w.str(t.mode || ""); w.str(t.chart || "");
    w.coords(t.lats, t.lons); w.coords(t.rteLats, t.rteLons);
    w.uint(t.rteNames.length); for (const s of t.rteNames) w.str(s);
    w.uint(t.cues.length);
    let pa = 0, po = 0;
    for (const c of t.cues) { const a = e6(c.lat), o = e6(c.lon); w.sint(a - pa); w.sint(o - po); pa = a; po = o; w.str(c.turn); w.uint(c.level || 0); w.str(c.note || ""); w.str(c.rally || ""); }
    return w.bytes();
  }
  /** a long line thinned to within a metre (Douglas–Peucker), as the apps do above 6,000 points */
  function thin(t, above = 6000) {
    if (t.lats.length <= above) return t;
    const n = t.lats.length, keep = new Uint8Array(n); keep[0] = keep[n - 1] = 1;
    const k = Math.cos(t.lats[0] * Math.PI / 180) * 111320, m = 110540;
    const stack = [[0, n - 1]];
    while (stack.length) {
      const [a, b] = stack.pop(); if (b <= a + 1) continue;
      const ax = t.lons[a] * k, ay = t.lats[a] * m, bx = t.lons[b] * k, by = t.lats[b] * m, dx = bx - ax, dy = by - ay, L = dx * dx + dy * dy;
      let best = -1, bd = 1.0;
      for (let i = a + 1; i < b; i++) { const px = t.lons[i] * k - ax, py = t.lats[i] * m - ay; let d; if (L === 0) d = Math.hypot(px, py); else { const u = Math.max(0, Math.min(1, (px * dx + py * dy) / L)); d = Math.hypot(px - u * dx, py - u * dy); } if (d > bd) { bd = d; best = i; } }
      if (best >= 0) { keep[best] = 1; stack.push([a, best], [best, b]); }
    }
    const lats = [], lons = []; for (let i = 0; i < n; i++) if (keep[i]) { lats.push(t.lats[i]); lons.push(t.lons[i]); }
    return Object.assign({}, t, { lats, lons });
  }
  async function deflateRaw(b) {
    if (typeof CompressionStream === "undefined") return null;
    try { const s = new Blob([b]).stream().pipeThrough(new CompressionStream("deflate-raw")); return new Uint8Array(await new Response(s).arrayBuffer()); } catch (_) { return null; }
  }
  /** the message: packed, and compressed when that makes it smaller ([1] + deflated, or [0] + packed) */
  async function message(t) {
    const p = pack(thin(t)), z = await deflateRaw(p);
    const out = z && z.length < p.length ? z : p, m = new Uint8Array(out.length + 1);
    m[0] = out === p ? 0 : 1; m.set(out, 1); return m;
  }

  // ---- frames (QrShare.Encoder) ------------------------------------------------------------------------------------
  const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1; t[n] = c; } return t; })();
  function crc32(b) { let c = -1; for (const x of b) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8); return ~c | 0; }
  const M64 = (1n << 64n) - 1n;
  function mixMask(id, i, k) {
    let x = ((BigInt(id) << 32n) ^ BigInt(i)) & M64;
    const m = new Uint8Array(k); let any = false, j = 0;
    while (j < k) {
      x = (x + 0x9E3779B97F4A7C15n) & M64;
      let z = x; z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M64; z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M64; z = z ^ (z >> 31n);
      for (let b = 0n; b < 64n && j < k; b++, j++) { m[j] = Number((z >> b) & 1n); if (m[j]) any = true; }
    }
    if (!any) m[i % k] = 1;
    return m;
  }
  function base45(b) {
    let s = "", i = 0;
    for (; i + 1 < b.length; i += 2) { const n = b[i] * 256 + b[i + 1]; s += ALNUM[n % 45] + ALNUM[Math.floor(n / 45) % 45] + ALNUM[Math.floor(n / 2025)]; }
    if (i < b.length) { const n = b[i]; s += ALNUM[n % 45] + ALNUM[Math.floor(n / 45)]; }
    return s;
  }
  function chunkFor(version = 20) { return Math.floor((QR.capacityChars(version, "M") - PREFIX.length) / 3) * 2 - HEAD; }
  class Encoder {
    constructor(msg, chunk = chunkFor()) {
      this.message = msg; this.chunk = Math.min(65535, Math.max(16, chunk));
      this.k = Math.max(1, Math.ceil(msg.length / this.chunk)); this.id = crc32(msg);
      this.chunks = []; for (let i = 0; i < this.k; i++) { const c = new Uint8Array(this.chunk); c.set(msg.subarray(i * this.chunk, Math.min(msg.length, (i + 1) * this.chunk))); this.chunks.push(c); }
    }
    frame(i) {
      const body = new Uint8Array(this.chunk);
      if (i < this.k) body.set(this.chunks[i]);
      else { const mask = mixMask(this.id, i, this.k); for (let j = 0; j < this.k; j++) if (mask[j]) { const c = this.chunks[j]; for (let x = 0; x < this.chunk; x++) body[x] ^= c[x]; } }
      const h = new Uint8Array(HEAD + this.chunk);
      const put = (at, v, n) => { for (let j = 0; j < n; j++) h[at + j] = Math.floor(v / 2 ** (8 * (n - 1 - j))) & 0xff; };
      h[0] = 1; put(1, this.id >>> 0, 4); put(5, this.message.length, 3); put(8, this.chunk, 2); put(10, i, 3);
      h.set(body, HEAD);
      return PREFIX + base45(h);
    }
    code(i) { return QR.encode(this.frame(i), "M"); }
  }

  // ---- the QR symbol (QrCode.kt, from Project Nayuki's generator, MIT) ---------------------------------------------
  const QR = (() => {
    const ECC = { L: [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
                  M: [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28] };
    const BLK = { L: [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
                  M: [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49] };
    const FMT = { L: 1, M: 0 };
    const raw = v => { let r = (16 * v + 128) * v + 64; if (v >= 2) { const na = Math.floor(v / 7) + 2; r -= (25 * na - 10) * na - 55; if (v >= 7) r -= 36; } return r; };
    const dataCw = (v, e) => Math.floor(raw(v) / 8) - ECC[e][v] * BLK[e][v];
    const countBits = v => v <= 9 ? 9 : v <= 26 ? 11 : 13;
    const capacityChars = (v, e) => { const bits = dataCw(v, e) * 8 - 4 - countBits(v); return Math.floor(bits / 11) * 2 + (bits % 11 >= 6 ? 1 : 0); };
    const mul = (x, y) => { let z = 0; for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; } return z & 0xff; };
    function divisor(d) { const r = new Array(d).fill(0); r[d - 1] = 1; let root = 1; for (let i = 0; i < d; i++) { for (let j = 0; j < d; j++) { r[j] = mul(r[j], root); if (j + 1 < d) r[j] ^= r[j + 1]; } root = mul(root, 2); } return r; }
    function remainder(data, div) { const r = new Array(div.length).fill(0); for (const b of data) { const f = b ^ r[0]; r.shift(); r.push(0); for (let i = 0; i < r.length; i++) r[i] ^= mul(div[i], f); } return r; }
    function alignment(v, size) { if (v === 1) return []; const na = Math.floor(v / 7) + 2, step = Math.floor((v * 8 + na * 3 + 5) / (na * 4 - 4)) * 2; const r = new Array(na); r[0] = 6; let pos = size - 7; for (let i = na - 1; i >= 1; i--) { r[i] = pos; pos -= step; } return r; }
    function encode(text, e = "M") {
      let v = 1; while (v <= 40 && capacityChars(v, e) < text.length) v++;
      if (v > 40) return null;
      const bits = []; const put = (x, n) => { for (let i = n - 1; i >= 0; i--) bits.push((x >>> i) & 1); };
      put(2, 4); put(text.length, countBits(v));
      let i = 0; for (; i + 1 < text.length; i += 2) put(ALNUM.indexOf(text[i]) * 45 + ALNUM.indexOf(text[i + 1]), 11);
      if (i < text.length) put(ALNUM.indexOf(text[i]), 6);
      const cap = dataCw(v, e) * 8;
      put(0, Math.min(4, cap - bits.length)); put(0, (8 - bits.length % 8) % 8);
      for (let pad = 0xEC; bits.length < cap; pad ^= 0xEC ^ 0x11) put(pad, 8);
      const data = []; for (let k = 0; k < bits.length; k += 8) { let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[k + j]; data.push(b); }
      // error correction, interleaved
      const nb = BLK[e][v], eccLen = ECC[e][v], rw = Math.floor(raw(v) / 8), nShort = nb - rw % nb, shortLen = Math.floor(rw / nb), div = divisor(eccLen);
      const blocks = []; let k = 0;
      for (let b = 0; b < nb; b++) { const len = shortLen - eccLen + (b < nShort ? 0 : 1); const dat = data.slice(k, k + len); k += len; const block = dat.slice(); while (block.length < shortLen - eccLen + 1) block.push(0); const ec = remainder(dat, div); for (let j = 0; j < eccLen; j++) block[shortLen + 1 - eccLen + j] = ec[j]; blocks.push(block); }
      const all = []; for (let a = 0; a < blocks[0].length; a++) for (let b = 0; b < blocks.length; b++) if (a !== shortLen - eccLen || b >= nShort) all.push(blocks[b][a]);
      // the symbol
      const size = v * 4 + 17, mod = [], fn = [];
      for (let y = 0; y < size; y++) { mod.push(new Uint8Array(size)); fn.push(new Uint8Array(size)); }
      const set = (x, y, d) => { mod[y][x] = d ? 1 : 0; fn[y][x] = 1; };
      for (let a = 0; a < size; a++) { set(6, a, a % 2 === 0); set(a, 6, a % 2 === 0); }
      for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) { const x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy)); if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4); }
      const al = alignment(v, size), na = al.length;
      for (let a = 0; a < na; a++) for (let b = 0; b < na; b++) { if ((a === 0 && b === 0) || (a === 0 && b === na - 1) || (a === na - 1 && b === 0)) continue; for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(al[a] + dx, al[b] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1); }
      const format = mask => {
        const d = (FMT[e] << 3) | mask; let rem = d; for (let j = 0; j < 10; j++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        const fb = ((d << 10) | rem) ^ 0x5412, bit = j => ((fb >>> j) & 1) !== 0;
        for (let j = 0; j <= 5; j++) set(8, j, bit(j));
        set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
        for (let j = 9; j < 15; j++) set(14 - j, 8, bit(j));
        for (let j = 0; j < 8; j++) set(size - 1 - j, 8, bit(j));
        for (let j = 8; j < 15; j++) set(8, size - 15 + j, bit(j));
        set(8, size - 8, true);
      };
      format(0);
      if (v >= 7) { let rem = v; for (let j = 0; j < 12; j++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25); const vb = (v << 12) | rem; for (let j = 0; j < 18; j++) { const b = ((vb >>> j) & 1) !== 0, a = size - 11 + j % 3, c = Math.floor(j / 3); set(a, c, b); set(c, a, b); } }
      let bi = 0;
      for (let right = size - 1; right >= 1; right -= 2) {
        if (right === 6) right = 5;
        for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) { const x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert; if (!fn[y][x] && bi < all.length * 8) { mod[y][x] = (all[bi >>> 3] >>> (7 - (bi & 7))) & 1; bi++; } }
      }
      const applyMask = m => {
        for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
          let inv;
          switch (m) { case 0: inv = (x + y) % 2 === 0; break; case 1: inv = y % 2 === 0; break; case 2: inv = x % 3 === 0; break; case 3: inv = (x + y) % 3 === 0; break;
            case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break; case 5: inv = x * y % 2 + x * y % 3 === 0; break; case 6: inv = (x * y % 2 + x * y % 3) % 2 === 0; break; default: inv = ((x + y) % 2 + x * y % 3) % 2 === 0; }
          if (inv && !fn[y][x]) mod[y][x] ^= 1;
        }
      };
      let best = 0, bestP = Infinity;
      for (let m = 0; m < 8; m++) { applyMask(m); format(m); const p = penalty(mod, size); if (p < bestP) { bestP = p; best = m; } applyMask(m); }
      applyMask(best); format(best);
      return { version: v, size, dark: (x, y) => mod[y][x] === 1, rows: mod };
    }
    function penalty(m, size) {
      let p = 0;
      const addHistory = (run, h) => { if (h[0] === 0) run += size; for (let i = h.length - 1; i >= 1; i--) h[i] = h[i - 1]; h[0] = run; };
      const count = h => { const n = h[1], core = n > 0 && h[2] === n && h[3] === n * 3 && h[4] === n && h[5] === n; return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0); };
      const terminate = (color, run, h) => { if (color) { addHistory(run, h); run = 0; } run += size; addHistory(run, h); return count(h); };
      for (let pass = 0; pass < 2; pass++) for (let a = 0; a < size; a++) {
        let color = 0, run = 0; const h = [0, 0, 0, 0, 0, 0, 0];
        for (let b = 0; b < size; b++) { const c = pass === 0 ? m[a][b] : m[b][a]; if (c === color) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else { addHistory(run, h); if (!color) p += count(h) * 40; color = c; run = 1; } }
        p += terminate(color, run, h) * 40;
      }
      for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) { const c = m[y][x]; if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) p += 3; }
      let dark = 0; for (const row of m) for (const c of row) dark += c;
      const total = size * size;
      p += (Math.floor((Math.abs(dark * 20 - total * 10) + total - 1) / total) - 1) * 10;
      return p;
    }
    return { encode, capacityChars };
  })();

  const api = { parseGpx, pack, thin, message, Encoder, crc32, base45, chunkFor, qr: QR, PREFIX };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.RoadbookQr = api;
})(typeof window !== "undefined" ? window : globalThis);
