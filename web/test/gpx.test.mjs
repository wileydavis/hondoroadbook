// The server's GPX handling keeps a roll chart through Send to phone and Publish, and nothing else besides.
// Run: npm test (node --test web/test/*.test.mjs)
import test from "node:test";
import assert from "node:assert/strict";
import { parseGpx, writeGpx, shiftRally, cleanJson, cuesOn } from "../gpx.js";

const rally = { at: 1500.5, exit: 90, info: "R ON RANCH RD", caution: 2, symbols: [{ id: "gate", slot: "la" }] };
const file = `<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>Copper</name><extensions><roadbook><mode>MOTO</mode><planner>1</planner><chart>${JSON.stringify({ layout: "B", scale: 0.97 }).replace(/"/g, "&quot;")}</chart></roadbook></extensions></metadata>
<wpt lat="45.0100" lon="-111.0000"><name>R ON RANCH RD</name><desc>R ON RANCH RD</desc><type>cue:RIGHT</type><extensions><rally>${JSON.stringify(rally).replace(/"/g, "&quot;")}</rally></extensions></wpt>
<wpt lat="45.0200" lon="-111.0000"><name>Note</name><type>note</type><extensions><rally>{&quot;symbols&quot;:[{&quot;id&quot;:&quot;fuel&quot;,&quot;slot&quot;:&quot;ra&quot;}],&quot;at&quot;:2600}</rally></extensions></wpt>
<wpt lat="45.0300" lon="-111.0000"><name>bad</name><type>note</type><desc>bad rally</desc><extensions><rally>[1,2,3]</rally></extensions></wpt>
<trk><trkseg>${Array.from({ length: 50 }, (_, i) => `<trkpt lat="${(45 + i * 0.001).toFixed(4)}" lon="-111.0000"/>`).join("")}</trkseg></trk></gpx>`;

test("a cue's chart fields and the file's chart settings survive parse and write", () => {
  const g = parseGpx(file);
  assert.equal(g.cues.length, 3);
  assert.deepEqual(JSON.parse(g.cues[0].rally), rally);
  assert.equal(JSON.parse(g.meta.chart).layout, "B");
  assert.equal(g.cues[1].turn, "NOTE", "a chart-only note (a symbol, no words) is kept");
  assert.equal(g.cues[2].rally, "", "an array is not a chart field");
  const again = parseGpx(writeGpx(g.name, g.pts, g.cues, g.meta));
  assert.deepEqual(JSON.parse(again.cues[0].rally), rally);
  assert.equal(again.meta.chart, g.meta.chart);
  assert.match(writeGpx(g.name, g.pts, g.cues, g.meta), /<type>cue:RIGHT<\/type><extensions><rally>\{&quot;at&quot;/);
});

test("chart fields are re-serialised plain JSON, never markup", () => {
  assert.equal(cleanJson('{"info":"<script>x</script>"}', 100), '{"info":"<script>x</script>"}');
  const g = parseGpx(file.replace("R ON RANCH RD</desc>", "R ON RANCH RD</desc>").replace(/<rally>\{&quot;at&quot;:1500.5/, "<rally>{&quot;at&quot;:1500.5,&quot;info2&quot;:&quot;&lt;b&gt;&quot;"));
  assert.match(writeGpx(g.name, g.pts, g.cues, g.meta), /&lt;b&gt;/, "escaped on the way out");
  assert.equal(cleanJson("x".repeat(5000), 2000), "");
  assert.equal(cleanJson("not json", 2000), "");
});

test("trimming a published ride's start moves each cue's chart position with it", () => {
  const g = parseGpx(file);
  const c = shiftRally(g.cues[0], 1000);
  assert.equal(JSON.parse(c.rally).at, 500.5);
  assert.equal(JSON.parse(shiftRally(g.cues[0], 2000).rally).at, undefined, "a position before the new start is dropped");
  assert.equal(shiftRally(g.cues[2], 1000).rally, "");
  assert.equal(cuesOn(g.pts, g.cues).length, 3);
});
