// The planner's share code against the apps': the same track read from the same GPX, the same bytes, the same
// frames, the same QR modules (web/test/qr-fixture.json is written by core's QrFixtureWriter).
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
(0, eval)(readFileSync(new URL("../site/qrshare.js", import.meta.url), "utf8"));   // a browser script: it sets globalThis.RoadbookQr
const Q = globalThis.RoadbookQr;
const fx = JSON.parse(readFileSync(new URL("./qr-fixture.json", import.meta.url)));
const hex = b => Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
const rows = q => q.rows.map(r => Array.from(r).join(""));

test("the planner reads its GPX as the app does, and packs the same bytes", () => {
  const t = Q.parseGpx(fx.gpx);
  assert.equal(t.cues.length, fx.cues); assert.equal(t.lats.length, fx.points);
  assert.equal(hex(Q.pack(t)), fx.pack);
});

test("the same frames, plain and mixed", () => {
  const msg = new Uint8Array(fx.pack.length / 2 + 1); msg[0] = 0;
  for (let i = 0; i < fx.pack.length / 2; i++) msg[i + 1] = parseInt(fx.pack.substr(i * 2, 2), 16);
  const enc = new Q.Encoder(msg);
  assert.equal(enc.k, fx.k); assert.equal(enc.id, fx.id);
  fx.frames.forEach((f, i) => assert.equal(enc.frame(i), f, `frame ${i}`));
  assert.deepEqual(rows(enc.code(0)), fx.qr0);
  assert.deepEqual(rows(enc.code(fx.k + 2)), fx.qrMix);
});

test("the same QR symbols for short texts", () => {
  for (const s of fx.small) assert.deepEqual(rows(Q.qr.encode(s.text, "M")), s.rows, s.text);
});

test("the message compresses and stays readable", async () => {
  const t = Q.parseGpx(fx.gpx);
  const m = await Q.message(t);
  assert.equal(m[0], 1, "deflated");
  const inflated = new Uint8Array(await new Response(new Blob([m.subarray(1)]).stream().pipeThrough(new DecompressionStream("deflate-raw"))).arrayBuffer());
  assert.equal(hex(inflated), fx.pack);
});
