// Planning for other tools and AI assistants: the planner's own search, router and library behind
//   POST /api/plan  — stops in, a roadbook out: routed, cued, parked for the phone under a code and a link
//   POST /mcp       — the same as a Model Context Protocol server (Streamable HTTP, stateless, no sign-in),
//                     so an assistant such as Claude can search places, plan a ride and hand the rider a link
// A planned roadbook is parked like the planner's SEND TO PHONE, for a week: hondoroadbook.com/r/<code> opens it in
// the app (or the planner), and the code can be typed into FETCH CODE. Nothing identifies the caller; no accounts.
import { writeGpx, measure, TURNS, MODES, GpxError } from "./gpx.js";

const SITE = "https://hondoroadbook.com";
const KEEP_DAYS = 7;
const LIMITS = { planPerVisitor: 400, planPerDay: 6000 };
const MODE_HELP = {
  BACKROADS: "motorcycle/car: paved two-lane highways and county roads, avoids interstates (the usual choice for a pleasure ride)",
  DIRT: "motorcycle/car: prefers gravel and dirt roads and tracks, pavement only to connect them (adventure / dual-sport)",
  FAST: "motorcycle/car: interstates and major highways (getting somewhere quickly)",
  TOWING: "car with trailer: big roads only, no dirt, no narrow streets",
  RIDE: "bicycle: fastest sensible way",
  QUIET: "bicycle: bike paths and quiet streets",
  GRAVEL: "bicycle: prefers unpaved roads, paths and tracks (gravel / bikepacking)",
};
const WORDS = { STRAIGHT: "Straight on", SLIGHT_LEFT: "Slight left", SLIGHT_RIGHT: "Slight right", LEFT: "Left", RIGHT: "Right", SHARP_LEFT: "Sharp left", SHARP_RIGHT: "Sharp right", KEEP_LEFT: "Keep left", KEEP_RIGHT: "Keep right", UTURN: "U-turn", ROUNDABOUT: "Roundabout" };

/** An in-process call to one of the planner's own endpoints (api.js), so search, the router and the library answer exactly as they do for the planner. */
function caller(request, env, ctx, routerFetch, serveApi) {
  return async (path, params = {}) => {
    const u = new URL(`${SITE}/api/${path}`);
    for (const [k, v] of Object.entries(params)) if (v != null && v !== "") u.searchParams.set(k, String(v));
    const r = await serveApi(new Request(u, { headers: { "CF-Connecting-IP": request.headers.get("CF-Connecting-IP") || "" } }), env, path, ctx, routerFetch);
    let j; try { j = await r.json(); } catch { j = { error: `HTTP ${r.status}` }; }
    return { ok: r.ok, status: r.status, j };
  };
}
function bad(message, status = 400) { return Object.assign(new Error(message), { status }); }
const num = (v, max) => { const n = Number(v); return Number.isFinite(n) && Math.abs(n) <= max ? n : null; };
const clean = (s, max) => String(s ?? "").replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);

// ---- plan ----------------------------------------------------------------------------------------------------------------

/**
 * Stops → a roadbook: routed by the planner's router in the asked mode, every turn a cue, every named stop a note on the
 * line, parked for KEEP_DAYS under a six-letter code. Returns what an assistant needs to judge it and hand it on.
 */
export async function planRoadbook(input, { request, env, call, admit, budget, park }) {
  const stops = Array.isArray(input?.stops) ? input.stops : null;
  if (!stops || stops.length < 2) throw bad("stops: at least two, each {lat, lon} (start, any via points, finish)");
  if (stops.length > 60) throw bad("at most 60 stops; split a long trip into days");
  const pts = stops.map((s, i) => {
    const lat = num(s?.lat, 90), lon = num(s?.lon, 180);
    if (lat == null || lon == null) throw bad(`stop ${i + 1} needs numeric lat and lon`);
    const link = typeof s.link === "string" && /^https:\/\/[^\s"'<>]{1,200}$/.test(s.link) ? s.link : "";
    return { lat, lon, name: clean(s.name, 80), note: clean(s.note, 160), link };
  });
  const mode = String(input.mode || "BACKROADS").toUpperCase();
  if (!MODES.has(mode)) throw bad(`mode is one of ${[...MODES].join(", ")}`);
  const name = clean(input.name, 80) || [pts[0].name, pts[pts.length - 1].name].filter(Boolean).join(" to ") || "Roadbook";

  // one visitor (an assistant's servers are one visitor for many people: the bar is high) and the whole site, per day
  if (!(await admit(request, env, "plan", LIMITS.planPerVisitor))) throw bad("That is plenty of planning from here for one day; try again tomorrow", 429);
  if (!(await budget(env, "plan", LIMITS.planPerDay))) throw bad("The planner has done all the planning it can for today; try again tomorrow", 429);

  const r = await call("route", { pts: pts.map(p => `${p.lon.toFixed(6)},${p.lat.toFixed(6)}`).join(";"), mode });
  if (!r.ok) {
    const why = r.j?.error || `the router answered ${r.status}`;
    if (r.status === 503) throw bad(`${why}. The maps for this area may still be loading on the server: try again in a minute.`, 503);
    throw bad(why, r.status >= 400 && r.status < 500 ? r.status : 502);
  }
  const j = r.j;
  const line = j.coords.map(c => [c[0], c[1]]);

  // turns from the router, as the planner makes them; then each named stop as a note where the route passes it
  const cues = (j.cues || []).filter(c => TURNS.has(c.turn)).map(c => ({ lon: c.lon, lat: c.lat, turn: c.turn, text: c.turn === "ROUNDABOUT" ? clean(c.name, 60) : clean(c.name, 120) }));
  pts.forEach((p, i) => {
    if (!p.name && !p.note) return;
    const at = j.snapped?.[i] || p;
    const text = clean([p.name, p.note].filter(Boolean).join(" — "), 200);
    cues.push({ lon: at.lon, lat: at.lat, turn: "NOTE", text, link: p.link });
  });

  // surface: paved and unpaved metres, the roads ridden longest, and any private stretch
  let paved = 0, unpaved = 0, privateM = 0; const roads = new Map();
  for (const s of j.surface || []) {
    const u = s.unpaved || s.cls === "track" || s.cls === "path";
    if (u) unpaved += s.m; else paved += s.m;
    if (s.private) privateM += s.m;
    const key = `${s.name || `(unnamed ${s.cls})`}|${u ? "unpaved" : "paved"}`;
    roads.set(key, (roads.get(key) || 0) + s.m);
  }
  const mainRoads = [...roads.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12).map(([k, m]) => { const [road, surface] = k.split("|"); return { road, surface, km: round1(m / 1000) }; });
  const legs = (j.legs || []).map((l, i) => ({ from: pts[i].name || `stop ${i + 1}`, to: pts[i + 1].name || `stop ${i + 2}`, km: round1(l.length / 1000), hours: round1(l.time / 3600) }));
  const warnings = [];
  (j.snapped || []).forEach((s, i) => { if (s.distance > 400) warnings.push(`${pts[i].name || `Stop ${i + 1}`} is ${round1(s.distance / 1000)} km from the nearest usable road or trail; the route goes to that road, not the point itself`); });
  if (privateM > 200) warnings.push(`${round1(privateM / 1000)} km is on roads marked private or no-access; check them before riding`);
  if ((mode === "DIRT" || mode === "GRAVEL") && j.length > 20000 && unpaved / j.length < 0.25) warnings.push(`Only ${Math.round(100 * unpaved / j.length)}% is unpaved: the dirt between these stops is scarce or not mapped; via points on known dirt roads would add more`);

  const meta = { mode, paved: Math.round(paved), unpaved: Math.round(unpaved) };
  const gpx = writeGpx(name, line, cues, meta);
  const parked = await park(env, gpx, name, KEEP_DAYS * 24 * 3600 * 1000);
  const plannerUrl = `${SITE}/plan.html#m=${mode}&p=${pts.map(p => `${p.lon.toFixed(5)},${p.lat.toFixed(5)}`).join(";")}`;
  return {
    name, mode, code: parked.code,
    link: `${SITE}/r/${parked.code}`,
    planner: plannerUrl,
    gpx: `${SITE}/api/parked/${parked.code}`,
    expires: new Date(parked.expires).toISOString().slice(0, 10),
    km: round1(j.length / 1000), hours: round1(j.time / 3600),
    paved_km: round1(paved / 1000), unpaved_km: round1(unpaved / 1000), unpaved_share: j.length ? round2(unpaved / j.length) : 0,
    turns: cues.filter(c => c.turn !== "NOTE").length, notes: cues.filter(c => c.turn === "NOTE").length,
    legs, main_roads: mainRoads, warnings,
    bbox: measure(line).bbox,
  };
}
const round1 = x => Math.round(x * 10) / 10, round2 = x => Math.round(x * 100) / 100;

function planText(p) {
  const h = Math.floor(p.hours), m = Math.round((p.hours - h) * 60);
  const lines = [
    `Roadbook "${p.name}" (${p.mode}): ${p.km} km, about ${h} h ${m} min of riding; ${p.unpaved_km} km unpaved (${Math.round(p.unpaved_share * 100)}%). ${p.turns} turn cues, ${p.notes} notes.`,
    `Open on the phone: ${p.link} — opens in the Roadbook app (or type code ${p.code.slice(0, 3)} ${p.code.slice(3)} in Roadbook: ≡ → TRACK → FETCH CODE). Kept until ${p.expires}.`,
    `Adjust it in the planner: ${p.planner}`,
    `GPX for other apps: ${p.gpx}`,
    "", "Legs:", ...p.legs.map(l => `- ${l.from} → ${l.to}: ${l.km} km, ${l.hours} h`),
    "", "Main roads:", ...p.main_roads.map(r => `- ${r.road} (${r.surface}): ${r.km} km`),
  ];
  if (p.warnings.length) lines.push("", "Check:", ...p.warnings.map(w => `- ${w}`));
  return lines.join("\n");
}

// ---- the tools -----------------------------------------------------------------------------------------------------------

const POINT = { type: "object", properties: { lat: { type: "number" }, lon: { type: "number" } }, required: ["lat", "lon"] };
const TOOLS = [
  {
    name: "search_places",
    title: "Find places",
    description: "Find towns, roads, passes, hot springs, peaks, trailheads, campgrounds and other named places on Roadbook's maps (OpenStreetMap, plus USGS GNIS place names in the US). Returns coordinates to use as stops. Always look places up here rather than guessing coordinates. Pass near to rank results close to an area first; add a state or province to the query for common names (\"Hunter Mountain, CA\").",
    inputSchema: { type: "object", properties: { query: { type: "string", description: "A place name, e.g. \"Saline Valley Warm Springs\" or \"Beartooth Pass\"" }, near: { ...POINT, description: "Optional: rank results near this point" } }, required: ["query"] },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "plan_route",
    title: "Plan a roadbook",
    description: "Route through stops in order with Roadbook's own router and make a navigable roadbook: turn-by-turn cues, each named stop as a note on the line. Returns distance, riding time, paved/unpaved split, legs, the main roads used and warnings, plus a link (hondoroadbook.com/r/CODE) that opens the roadbook in the Roadbook phone app for offline navigation, a six-letter code to type in the app, a planner link to adjust it on a computer, and a GPX link. Always give the rider the link and the code. For a multi-day trip, call once per day. Check the result against the rider's intent (the unpaved share for a dirt ride, the hours for a day) and re-plan with different via points if it misses. Coverage: the US, Canada, Baja California and much of Europe.\n\nModes: " + Object.entries(MODE_HELP).map(([k, v]) => `${k} — ${v}`).join("; "),
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string", description: "A short name, e.g. \"Day 2 — Saline Valley to Panamint Springs\"" },
        mode: { type: "string", enum: Object.keys(MODE_HELP), default: "BACKROADS" },
        stops: {
          type: "array", minItems: 2, maxItems: 60,
          description: "Start, via points in order, finish. Via points steer the route: put them on the roads or places the ride should pass. name and note become a note the rider sees and hears when passing (\"Fuel — last for 120 km\").",
          items: { type: "object", properties: { lat: { type: "number" }, lon: { type: "number" }, name: { type: "string" }, note: { type: "string", description: "Optional words for the rider at this stop" }, link: { type: "string", description: "Optional https link about the stop" } }, required: ["lat", "lon"] },
        },
      },
      required: ["stops"],
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "services_near",
    title: "Fuel, food and camping near a point",
    description: "What a rider can find around a point from Roadbook's map data: fuel, food, camping, water, lodging, repair, towns, each with distance and direction. Use it to check fuel range on remote stretches and to suggest overnight stops.",
    inputSchema: { type: "object", properties: { lat: { type: "number" }, lon: { type: "number" } }, required: ["lat", "lon"] },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "find_roadbooks",
    title: "Find published roadbooks",
    description: "Rides other riders have published to Roadbook's public library, in an area: name, length, paved/unpaved, and links. A good source of proven routes; send_roadbook_to_phone gives the rider a phone link for one.",
    inputSchema: { type: "object", properties: { near: { ...POINT, description: "Centre of the area" }, radius_km: { type: "number", default: 60, maximum: 400 } }, required: ["near"] },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "send_roadbook_to_phone",
    title: "Send a published roadbook to the phone",
    description: "A link and code that open a published roadbook (from find_roadbooks) in the Roadbook phone app.",
    inputSchema: { type: "object", properties: { id: { type: "string", description: "The roadbook's id from find_roadbooks" } }, required: ["id"] },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false },
  },
];

const INSTRUCTIONS = `Roadbook plans motorcycle, car and bicycle rides on its own offline maps and hands them to the Roadbook phone app for offline turn-by-turn navigation.
To plan: look up every place with search_places (never guess coordinates), choose the mode that fits the ride, then plan_route with the stops in order. Via points steer the route; put them on roads or places the ride should use. For trips over several days, plan one roadbook per day, ending each where the rider sleeps (services_near finds camping, lodging and fuel). Check each result (hours, unpaved share, warnings) and re-plan if it misses what the rider asked for.
Always give the rider each roadbook's link (hondoroadbook.com/r/…) and its code: the link opens it in the Roadbook app, and the code can be typed there. Links last a week. The app is free at hondoroadbook.com, and the rider downloads the map pack for the area in the app before riding out of signal.`;

export async function toolCall(name, args, deps) {
  const { call } = deps;
  if (name === "search_places") {
    const q = clean(args?.query, 120); if (!q) throw bad("query is required");
    const r = await call("search", { q, lat: num(args?.near?.lat, 90), lon: num(args?.near?.lon, 180) });
    if (!r.ok) throw bad(r.j?.error || "Search is not reachable right now", r.status);
    const hits = (r.j.hits || []).map(h => ({ name: h.name, lat: h.lat, lon: h.lon, kind: h.context || undefined, region: h.region || undefined, km_away: h.distance != null ? round1(h.distance / 1000) : undefined }));
    return { data: { hits }, text: hits.length ? hits.map(h => `${h.name} — ${[h.kind, h.region].filter(Boolean).join(", ")} (${h.lat.toFixed(5)}, ${h.lon.toFixed(5)})${h.km_away != null ? `, ${h.km_away} km away` : ""}`).join("\n") : "Nothing found. Try another spelling, a nearby town, or add the state or province." };
  }
  if (name === "plan_route") { const p = await planRoadbook(args, deps); return { data: p, text: planText(p) }; }
  if (name === "services_near") {
    const lat = num(args?.lat, 90), lon = num(args?.lon, 180); if (lat == null || lon == null) throw bad("lat and lon are required");
    const r = await call("near", { lat, lon });
    if (!r.ok) throw bad(r.j?.error || "Not available here right now", r.status);
    const dir = b => ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Math.round(((Number(b) % 360) + 360) % 360 / 45) % 8];
    const out = {}; const lines = [];
    for (const cat of ["fuel", "food", "camping", "water", "lodging", "repair", "town"]) {
      const list = (r.j[cat] || []).map(x => ({ name: x.name || x.kind, kind: x.kind, lat: x.lat, lon: x.lon, km: round1(Number(x.d) / 1000), direction: dir(x.bearing), extra: x.extra || undefined }));
      out[cat] = list;
      lines.push(`${cat}: ` + (list.length ? list.map(x => `${x.name} ${x.km} km ${x.direction}`).join("; ") : "none found nearby"));
    }
    return { data: { ...out, searched_km: round1(Number(r.j.reach || 0) / 1000), partial: r.j.partial === true }, text: lines.join("\n") + "\n(From OpenStreetMap: hours and fuel types are not known; confirm before relying on a remote station.)" };
  }
  if (name === "find_roadbooks") {
    const lat = num(args?.near?.lat, 90), lon = num(args?.near?.lon, 180); if (lat == null || lon == null) throw bad("near {lat, lon} is required");
    const rk = Math.min(400, Math.max(5, Number(args?.radius_km) || 60));
    const dLat = rk / 110.57, dLon = rk / (111.32 * Math.max(0.1, Math.cos(lat * Math.PI / 180)));
    const r = await call("roadbooks", { bbox: [lon - dLon, lat - dLat, lon + dLon, lat + dLat].map(v => v.toFixed(4)).join(","), limit: 40 });
    if (!r.ok) throw bad(r.j?.error || "The library is not reachable right now", r.status);
    const books = (r.j.roadbooks || []).map(b => ({ id: b.id, name: b.name, km: round1(b.length / 1000), unpaved_share: b.length && b.unpaved != null ? round2(b.unpaved / b.length) : null, mode: b.mode || null, start: b.line?.[0] ? { lon: b.line[0][0], lat: b.line[0][1] } : null, page: `${SITE}/plan.html#rb=${b.id}`, gpx: b.url }));
    return { data: { roadbooks: books }, text: books.length ? books.map(b => `${b.name} — ${b.km} km${b.unpaved_share != null ? `, ${Math.round(b.unpaved_share * 100)}% unpaved` : ""} (id ${b.id}) ${b.page}`).join("\n") : "No published roadbooks in that area yet." };
  }
  if (name === "send_roadbook_to_phone") {
    const id = String(args?.id || "").toLowerCase(); if (!/^[a-z0-9]{8}$/.test(id)) throw bad("id is the eight-letter id from find_roadbooks");
    const { env, request, admit, park } = deps;
    if (!(await admit(request, env, "plan", LIMITS.planPerVisitor))) throw bad("That is plenty for one day; try again tomorrow", 429);
    const row = env.DB && await env.DB.prepare("SELECT name FROM roadbooks WHERE id = ? AND hidden = 0").bind(id).first();
    const obj = row && await env.PACKS.get(`library/${id}.gpx`);
    if (!obj) throw bad("No published roadbook with that id", 404);
    const p = await park(env, await obj.text(), row.name, KEEP_DAYS * 24 * 3600 * 1000);
    const link = `${SITE}/r/${p.code}`;
    return { data: { name: row.name, code: p.code, link, expires: new Date(p.expires).toISOString().slice(0, 10) }, text: `"${row.name}": open on the phone with ${link} (or code ${p.code.slice(0, 3)} ${p.code.slice(3)} in Roadbook: ≡ → TRACK → FETCH CODE). Kept until ${new Date(p.expires).toISOString().slice(0, 10)}.` };
  }
  throw Object.assign(new Error(`unknown tool ${name}`), { rpc: -32602 });
}

// ---- HTTP: POST /api/plan ------------------------------------------------------------------------------------------------

export async function servePlan(request, env, ctx, routerFetch, serveApi, helpers) {
  if (request.method !== "POST") return json({ error: "POST a JSON body: {name, mode, stops: [{lat, lon, name?, note?}, …]}" }, 405);
  let body; try { body = await readJson(request); } catch (e) { return json({ error: e.message }, e.status || 400); }
  const deps = { request, env, call: caller(request, env, ctx, routerFetch, serveApi), ...helpers };
  try { return json(await planRoadbook(body, deps)); }
  catch (e) { return json({ error: e.message }, e.status && e.status < 600 ? e.status : 500); }
}

// ---- HTTP: POST /mcp -------------------------------------------------------------------------------------------------------

const PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];

export async function serveMcp(request, env, ctx, routerFetch, serveApi, helpers) {
  const h = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Content-Type, Accept, Mcp-Session-Id, Mcp-Protocol-Version, Authorization", "Access-Control-Allow-Methods": "POST, GET, DELETE, OPTIONS", "Access-Control-Expose-Headers": "Mcp-Session-Id" };
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: h });
  // stateless: no server-sent stream to open and no session to end
  if (request.method === "GET" || request.method === "DELETE") return new Response(null, { status: 405, headers: { ...h, Allow: "POST" } });
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { ...h, Allow: "POST" } });
  let msg; try { msg = await readJson(request); } catch { return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400, h); }
  const deps = { request, env, call: caller(request, env, ctx, routerFetch, serveApi), ...helpers };
  const batch = Array.isArray(msg);
  const answers = (await Promise.all((batch ? msg : [msg]).slice(0, 20).map(m => rpc(m, deps)))).filter(Boolean);
  if (!answers.length) return new Response(null, { status: 202, headers: h });     // notifications only
  return json(batch ? answers : answers[0], 200, h);
}

async function rpc(m, deps) {
  if (!m || m.jsonrpc !== "2.0" || typeof m.method !== "string") return m && m.id != null ? { jsonrpc: "2.0", id: m.id, error: { code: -32600, message: "Invalid request" } } : null;
  const id = m.id;
  if (id === undefined || id === null) return null;                                        // a notification: nothing to say
  const ok = result => ({ jsonrpc: "2.0", id, result });
  const err = (code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
  switch (m.method) {
    case "initialize": {
      const asked = m.params?.protocolVersion;
      return ok({ protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0], capabilities: { tools: { listChanged: false } }, serverInfo: { name: "roadbook", title: "Roadbook ride planner", version: "1.0.0", websiteUrl: SITE }, instructions: INSTRUCTIONS });
    }
    case "ping": return ok({});
    case "tools/list": return ok({ tools: TOOLS });
    case "resources/list": return ok({ resources: [] });
    case "prompts/list": return ok({ prompts: [] });
    case "tools/call": {
      const name = m.params?.name, args = m.params?.arguments || {};
      if (!TOOLS.some(t => t.name === name)) return err(-32602, `Unknown tool: ${String(name).slice(0, 60)}`);
      try {
        const { data, text } = await toolCall(name, args, deps);
        return ok({ content: [{ type: "text", text }], structuredContent: data, isError: false });
      } catch (e) {
        if (e.rpc) return err(e.rpc, e.message);
        // a problem the assistant can act on (a bad stop, no road near a point, a busy router) is a tool result, not a protocol error
        const message = e.status && e.status < 500 || e.status === 503 ? e.message : "Something failed on the server; try again in a moment";
        if (!(e.status < 500 || e.status === 503)) console.error("mcp", name, e && e.name, e && e.message && e.message.slice(0, 120));
        return ok({ content: [{ type: "text", text: message }], isError: true });
      }
    }
    default: return err(-32601, `Method not found: ${m.method.slice(0, 60)}`);
  }
}

async function readJson(request) {
  const len = Number(request.headers.get("Content-Length") || 0);
  if (len > 256 << 10) throw bad("body over 256 KB", 413);
  const text = await request.text();
  if (text.length > 256 << 10) throw bad("body over 256 KB", 413);
  try { return JSON.parse(text); } catch { throw bad("not JSON"); }
}
function json(o, status = 200, extra = {}) { return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*", ...extra } }); }
export { GpxError };
