// Roadbook roll charts: the PDF writer. Turns the engine's pages of primitives (chart.js, millimetres, y downward)
// into a vector PDF at exact size: lines, curves, fills, dashes and text in the PDF standard fonts Helvetica and
// Helvetica-Bold, so no font file is embedded and the text stays selectable. Loaded only when the export dialog opens.
(function (root) {
"use strict";
const C = root.RoadbookChart;
const K = 72 / 25.4;   // points per millimetre

const n = v => { const r = Math.round(v * 1000) / 1000; return Object.is(r, -0) ? "0" : String(r); };
function rgb(hex) { const v = parseInt(hex.slice(1), 16); return [(v >> 16) / 255, (v >> 8 & 255) / 255, (v & 255) / 255].map(x => n(Math.round(x * 1000) / 1000)).join(" "); }
function pdfString(codes) {
  let s = "(";
  for (const c of codes) s += c === 40 || c === 41 || c === 92 ? "\\" + String.fromCharCode(c) : c < 32 || c > 126 ? "\\" + c.toString(8).padStart(3, "0") : String.fromCharCode(c);
  return s + ")";
}
const KAPPA = 0.5522847498;
function circlePath(x, y, r) {
  const k = r * KAPPA;
  return `${n(x + r)} ${n(y)} m ${n(x + r)} ${n(y + k)} ${n(x + k)} ${n(y + r)} ${n(x)} ${n(y + r)} c ${n(x - k)} ${n(y + r)} ${n(x - r)} ${n(y + k)} ${n(x - r)} ${n(y)} c ${n(x - r)} ${n(y - k)} ${n(x - k)} ${n(y - r)} ${n(x)} ${n(y - r)} c ${n(x + k)} ${n(y - r)} ${n(x + r)} ${n(y - k)} ${n(x + r)} ${n(y)} c h`;
}
/** One page's content stream. The page is flipped once (mm, y down) so every primitive keeps the engine's coordinates. */
function contentStream(page) {
  const o = [`${n(K)} 0 0 ${n(-K)} 0 ${n(page.h * K)} cm`, "1 j"];
  let dash = "", lw = -1, cap = -1;
  const setDash = d => { const s = d ? `[${d.map(n).join(" ")}] 0 d` : "[] 0 d"; if (s !== dash) { o.push(s); dash = s; } };
  const setW = w => { if (w !== lw) { o.push(`${n(w)} w`); lw = w; } };
  const setCap = c => { if (c !== cap) { o.push(`${c} J`); cap = c; } };
  for (const p of page.prims) {
    if (p.t === "l") {
      if (!p.c || !p.w) continue;
      setW(p.w); setDash(p.dash); setCap(p.cap === "round" ? 1 : 0);
      o.push(`${rgb(p.c)} RG ${p.pts.map((q, i) => `${n(q[0])} ${n(q[1])} ${i ? "l" : "m"}`).join(" ")} S`);
    } else if (p.t === "p") {
      const d = p.d.map(op => op[0] === "M" ? `${n(op[1])} ${n(op[2])} m` : op[0] === "L" ? `${n(op[1])} ${n(op[2])} l` : op[0] === "C" ? `${op.slice(1).map(n).join(" ")} c` : "h").join(" ");
      const stroke = p.c && p.w;
      if (stroke) { setW(p.w); setDash(p.dash); setCap(0); o.push(`${rgb(p.c)} RG`); }
      if (p.f) o.push(`${rgb(p.f)} rg`);
      o.push(`${d} ${p.f && stroke ? "B" : p.f ? "f" : "S"}`);
    } else if (p.t === "c") {
      const stroke = p.c && p.w;
      if (stroke) { setW(p.w); setDash(p.dash); o.push(`${rgb(p.c)} RG`); }
      if (p.f) o.push(`${rgb(p.f)} rg`);
      if (!stroke && !p.f) continue;
      o.push(`${circlePath(p.x, p.y, p.r)} ${p.f && stroke ? "B" : p.f ? "f" : "S"}`);
    } else if (p.t === "r") {
      const stroke = p.c && p.lw;
      if (stroke) { setW(p.lw); setDash(p.dash); setCap(0); o.push(`${rgb(p.c)} RG`); }
      if (p.f) o.push(`${rgb(p.f)} rg`);
      if (!stroke && !p.f) continue;
      o.push(`${n(p.x)} ${n(p.y)} ${n(p.w)} ${n(p.h)} re ${p.f && stroke ? "B" : p.f ? "f" : "S"}`);
    } else if (p.t === "x") {
      const { codes } = C.winAnsi(p.s); if (!codes.length) continue;
      const size = p.size * 25.4 / 72, w = C.textWidth(p.s, p.size, p.b);
      const x = p.a === "c" ? p.x - w / 2 : p.a === "r" ? p.x - w : p.x;
      // the text matrix flips y back so the letters stand upright on the flipped page
      o.push(`${rgb(p.c)} rg BT /${p.b ? "F2" : "F1"} 1 Tf ${n(size)} 0 0 ${n(-size)} ${n(x)} ${n(p.y)} Tm ${pdfString(codes)} Tj ET`);
    }
  }
  return o.join("\n");
}
function bytes(s) { const b = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 255; return b; }
async function deflate(data) {
  if (typeof CompressionStream === "undefined") return null;
  try {
    const cs = new CompressionStream("deflate"), w = cs.writable.getWriter(); w.write(data); w.close();
    const parts = [], r = cs.readable.getReader(); let total = 0;
    for (;;) { const { value, done } = await r.read(); if (done) break; parts.push(value); total += value.length; }
    const out = new Uint8Array(total); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  } catch { return null; }
}
/** The whole document as bytes. meta.title becomes the document title; no author, no personal data. */
async function toPdf(pages, meta) {
  const chunks = [], offsets = []; let pos = 0;
  const push = b => { if (typeof b === "string") b = bytes(b); chunks.push(b); pos += b.length; };
  const obj = (id, body) => { offsets[id] = pos; push(`${id} 0 obj\n`); for (const b of [].concat(body)) push(b); push("\nendobj\n"); };
  push("%PDF-1.4\n%\xe2\xe3\xcf\xd3\n");
  const first = 6, ids = pages.map((_, i) => first + i * 2);
  obj(1, "<< /Type /Catalog /Pages 2 0 R >>");
  obj(2, `<< /Type /Pages /Kids [${ids.map(i => i + " 0 R").join(" ")}] /Count ${pages.length} >>`);
  obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
  obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
  obj(5, `<< /Title ${pdfString(C.winAnsi(meta && meta.title || "Roll chart").codes)} /Producer (Roadbook planner) >>`);
  for (let i = 0; i < pages.length; i++) {
    const p = pages[i], id = ids[i];
    obj(id, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(p.w * K)} ${n(p.h * K)}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${id + 1} 0 R >>`);
    const raw = bytes(contentStream(p)), z = await deflate(raw);
    obj(id + 1, [`<< /Length ${(z || raw).length}${z ? " /Filter /FlateDecode" : ""} >>\nstream\n`, z || raw, "\nendstream"]);
  }
  const count = first + pages.length * 2, xref = pos;
  let x = `xref\n0 ${count}\n0000000000 65535 f \n`;
  for (let i = 1; i < count; i++) x += String(offsets[i]).padStart(10, "0") + " 00000 n \n";
  push(x + `trailer\n<< /Size ${count} /Root 1 0 R /Info 5 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
  const out = new Uint8Array(pos); let o = 0; for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}
root.RoadbookChartPdf = { toPdf, contentStream };
})(typeof window !== "undefined" ? window : globalThis);
