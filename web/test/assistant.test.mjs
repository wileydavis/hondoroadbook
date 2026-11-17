// The planner for assistants (/mcp, /api/plan): the protocol answers, and a planned roadbook is one the apps can ride.
// Run: npm test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { serveMcp, servePlan } from "../assistant.js";
import { parseGpx } from "../gpx.js";

const route = JSON.parse(readFileSync(new URL("./pei-route.json", import.meta.url)));
const parked = new Map();
const asked = [];
const fakeApi = async (req, env, path) => {
  const u = new URL(req.url); asked.push({ path, q: Object.fromEntries(u.searchParams) });
  const j = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });
  if (path === "route") return u.searchParams.get("mode") === "TOWING" ? j({ error: "No Towing route between point 1 and point 2" }, 422) : j(route);
  if (path === "search") return j({ hits: [{ name: "Charlottetown", lat: 46.2382, lon: -63.1311, context: "Town", region: "Prince Edward Island", distance: 1200 }] });
  if (path === "near") return j({ fuel: [{ name: "Irving", kind: "fuel", lon: -63.13, lat: 46.24, d: "850", bearing: "92" }], food: [], camping: [], water: [], lodging: [], repair: [], town: [], reach: "850", partial: false });
  if (path === "roadbooks") return j({ roadbooks: [{ id: "abcd1234", name: "Island loop", length: 120000, unpaved: 30000, mode: "DIRT", line: [[-63.1, 46.2], [-63.2, 46.3]], url: "https://hondoroadbook.com/api/roadbooks/abcd1234.gpx" }] });
  return j({ error: "no" }, 404);
};
const helpers = {
  admit: async () => true, budget: async () => true,
  park: async (env, body, name, keep) => { const code = "ABC234"; parked.set(code, { body, name, keep }); return { code, expires: Date.UTC(2026, 10, 24) }; },
};
const mcp = body => serveMcp(new Request("https://hondoroadbook.com/mcp", { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json" } }), {}, null, null, fakeApi, helpers);

test("initialize, tools/list and notifications", async () => {
  const init = await (await mcp({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } } })).json();
  assert.equal(init.result.protocolVersion, "2025-06-18");
  assert.ok(init.result.capabilities.tools);
  assert.match(init.result.instructions, /search_places/);
  const old = await (await mcp({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "1999-01-01" } })).json();
  assert.equal(old.result.protocolVersion, "2025-06-18", "an unknown version is answered with ours");
  const note = await mcp({ jsonrpc: "2.0", method: "notifications/initialized" });
  assert.equal(note.status, 202);
  const list = await (await mcp({ jsonrpc: "2.0", id: 3, method: "tools/list" })).json();
  assert.deepEqual(list.result.tools.map(t => t.name), ["search_places", "plan_route", "services_near", "find_roadbooks", "send_roadbook_to_phone"]);
  for (const t of list.result.tools) assert.equal(t.inputSchema.type, "object");
  const nope = await (await mcp({ jsonrpc: "2.0", id: 4, method: "nope" })).json();
  assert.equal(nope.error.code, -32601);
  const get = await serveMcp(new Request("https://hondoroadbook.com/mcp"), {}, null, null, fakeApi, helpers);
  assert.equal(get.status, 405);
});

test("plan_route makes a roadbook the apps can read, parked for a week", async () => {
  const r = await (await mcp({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "plan_route", arguments: {
    name: "Charlottetown <loop>", mode: "backroads",
    stops: [{ lat: 46.239, lon: -63.134, name: "Start", note: "Fuel up" }, { lat: 46.268, lon: -63.120, name: "Finish", link: "javascript:alert(1)" }],
  } } })).json();
  assert.equal(r.result.isError, false);
  const p = r.result.structuredContent;
  assert.equal(p.link, "https://hondoroadbook.com/r/ABC234");
  assert.equal(p.mode, "BACKROADS");
  assert.equal(p.km, 4.6);
  assert.match(r.result.content[0].text, /hondoroadbook\.com\/r\/ABC234/);
  assert.match(r.result.content[0].text, /ABC 234/);
  assert.match(p.planner, /plan\.html#m=BACKROADS&p=-63\.13400,46\.23900;-63\.12000,46\.26800$/);
  const saved = parked.get("ABC234");
  assert.equal(saved.keep, 7 * 24 * 3600 * 1000);
  const g = parseGpx(saved.body);
  assert.equal(g.name, "Charlottetown loop", "markup is stripped from the name");
  assert.equal(g.pts.length, route.coords.length);
  assert.equal(g.meta.mode, "BACKROADS");
  const notes = g.cues.filter(c => c.turn === "NOTE");
  assert.deepEqual(notes.map(n => n.text), ["Start — Fuel up", "Finish"]);
  assert.equal(notes[1].link, "", "only https links ride along");
  assert.equal(g.cues.filter(c => c.turn !== "NOTE").length, route.cues.length);
  assert.equal(asked.find(a => a.path === "route").q.mode, "BACKROADS");
});

test("problems come back as tool errors the assistant can act on", async () => {
  const bad = async args => (await (await mcp({ jsonrpc: "2.0", id: 6, method: "tools/call", params: { name: "plan_route", arguments: args } })).json()).result;
  let r = await bad({ stops: [{ lat: 46, lon: -63 }] });
  assert.equal(r.isError, true); assert.match(r.content[0].text, /at least two/);
  r = await bad({ mode: "WARP", stops: [{ lat: 46, lon: -63 }, { lat: 46.1, lon: -63 }] });
  assert.match(r.content[0].text, /mode is one of/);
  r = await bad({ stops: [{ lat: 146, lon: -63 }, { lat: 46.1, lon: -63 }] });
  assert.match(r.content[0].text, /stop 1/);
  r = await bad({ mode: "TOWING", stops: [{ lat: 46, lon: -63 }, { lat: 46.1, lon: -63 }] });
  assert.match(r.content[0].text, /No Towing route/);
  const unknown = await (await mcp({ jsonrpc: "2.0", id: 7, method: "tools/call", params: { name: "rm_rf", arguments: {} } })).json();
  assert.equal(unknown.error.code, -32602);
});

test("search, services and the library", async () => {
  const call = async (name, args) => (await (await mcp({ jsonrpc: "2.0", id: 8, method: "tools/call", params: { name, arguments: args } })).json()).result;
  let r = await call("search_places", { query: "Charlottetown", near: { lat: 46.2, lon: -63.1 } });
  assert.equal(r.structuredContent.hits[0].name, "Charlottetown"); assert.match(r.content[0].text, /46\.23820/);
  r = await call("services_near", { lat: 46.24, lon: -63.13 });
  assert.equal(r.structuredContent.fuel[0].direction, "E"); assert.equal(r.structuredContent.fuel[0].km, 0.9);
  r = await call("find_roadbooks", { near: { lat: 46.2, lon: -63.1 }, radius_km: 50 });
  assert.equal(r.structuredContent.roadbooks[0].unpaved_share, 0.25);
  assert.equal(r.structuredContent.roadbooks[0].page, "https://hondoroadbook.com/plan.html#rb=abcd1234");
});

test("POST /api/plan answers the same plan as JSON", async () => {
  const res = await servePlan(new Request("https://hondoroadbook.com/api/plan", { method: "POST", body: JSON.stringify({ stops: [{ lat: 46.239, lon: -63.134 }, { lat: 46.268, lon: -63.12 }] }) }), {}, null, null, fakeApi, helpers);
  assert.equal(res.status, 200);
  const p = await res.json();
  assert.equal(p.code, "ABC234"); assert.equal(p.mode, "BACKROADS"); assert.equal(p.name, "Roadbook");
  const no = await servePlan(new Request("https://hondoroadbook.com/api/plan", { method: "POST", body: "{nope" }), {}, null, null, fakeApi, helpers);
  assert.equal(no.status, 400);
});
