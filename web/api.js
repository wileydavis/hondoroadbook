// /api/*: the planner's back end.
//   route, snap, search   -> the routing container (the phone's core behind HTTP), sharded west/east
//   park, parked/<code>   -> a roadbook parked for 24 h under a six-letter code, for the phone to fetch
//   roadbooks             -> the public library: D1 for the list, R2 for the GPX files
//   web                   -> Brave web search for the inspect panel, when BRAVE_KEY is set
// Nothing here identifies anyone: no accounts, no cookies, uploads keep only the line and the cues.
import { parseGpx, measure, trimEnds, cuesOn, simplify, writeGpx, GpxError, dist, shiftRally } from "./gpx.js";

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const MAX_GPX = 6 << 20;
const DAILY = { publish: 40, park: 60 };

export async function serveApi(request, env, path, ctx, routerFetch) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors({ "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Name", "Access-Control-Max-Age": "86400" }) });
  try {
    if (path === "search") return await search(request, env, url, routerFetch);
    if (path === "route" || path === "snap" || path === "junctions" || path === "warm" || path === "near") return await routerProxy(request, env, path, url, routerFetch);
    if (path === "park" && request.method === "POST") return await park(request, env, url);
    if (path.startsWith("parked/")) return await parked(request, env, path.slice(7));
    if (path === "roadbooks") return request.method === "POST" ? await publish(request, env, url) : await list(request, env, url);
    if (path === "import" && request.method === "POST") return await importStaged(env);
    if (path.startsWith("roadbooks/")) return await roadbook(request, env, path.slice(10), url);
    if (path === "web") return await webSearch(request, env, url, ctx);
    if (path === "health") return json({ ok: true, router: !!(env.ROUTER || env.ROUTER_URL), library: !!env.DB, storage: !!env.PACKS, web: !!env.BRAVE_KEY });
    return json({ error: "no such endpoint" }, 404);
  } catch (e) {
    // a bad request is the caller's; anything else is ours and is reported without its internals
    if (e instanceof GpxError || (e && e.status >= 400 && e.status < 500)) return json({ error: e.message }, e.status || 400);
    // the endpoint's name only: a parked code or a roadbook id is not for the log
    console.error("api", path.split("/")[0], e && e.name, e && e.message && e.message.slice(0, 120));
    return json({ error: "Something failed on the server; try again in a moment" }, 502);
  }
}
function bad(message, status = 400) { return Object.assign(new Error(message), { status }); }

// ---- routing -------------------------------------------------------------------------------------

async function routerProxy(request, env, path, url, routerFetch) {
  if (request.method !== "GET") return json({ error: "GET only" }, 405);
  const target = "/" + path + url.search;
  // the container holding the western graphs or the eastern ones, by the first point (or the point)
  const bb = (url.searchParams.get("bbox") || "").split(",").map(Number);
  const first = (url.searchParams.get("pts") || "").split(/[;|]/)[0] || (url.searchParams.get("lon") ? `${url.searchParams.get("lon")},${url.searchParams.get("lat")}` : bb.length === 4 ? `${(bb[0] + bb[2]) / 2},0` : "");
  const lon = Number(first.split(",")[0]);
  const shard = Number.isFinite(lon) && lon > -97 ? "east" : "west";
  if (env.ROUTER && routerFetch) return withCors(await routerFetch(shard, target));
  if (env.ROUTER_URL) return withCors(await fetch(env.ROUTER_URL.replace(/\/$/, "") + target));
  return json({ error: "The router is not deployed yet" }, 503);
}

// ---- search: the map's own towns, roads and trails (the router), and the country's place names (GNIS) ----------------

// The routing container answers from the map data; GNIS (USGS, public domain) adds the named places a map leaves
// unlabelled: passes, springs, summits, canyons, mines… with their variant names. tools/gnis_index.py builds the index;
// it lives in the bucket under names/v1/<first three letters of a distinctive word>.json.
const NAME_GENERIC = new Set(("the of and de del la el los las le du des a an at in on to mount mountain mountains mt peak peaks summit hill hills butte buttes knob ridge ridges point bluff cliff cliffs rock rocks " +
  "creek creeks river run brook branch fork forks spring springs well wells lake lakes pond ponds reservoir tank tanks dam falls canyon canyons valley valleys gulch draw wash hollow ravine basin flat flats meadow meadows park pass gap saddle notch " +
  "upper lower north south east west middle little big old new great mine mines ranch camp trail road cave arch island bay cove beach lookout tunnel bridge station historical ditch number lateral drain canal").split(" "));
const NAME_CLASS = { Gap: "Pass", Summit: "Summit", Spring: "Spring", Valley: "Valley", Stream: "Creek", Mine: "Mine", Locale: "Place", "Populated Place": "Town", Lake: "Lake", Reservoir: "Reservoir", Flat: "Flat", Basin: "Basin", Range: "Range", Ridge: "Ridge", Arch: "Arch", Falls: "Falls", Trail: "Trail", Park: "Park", Crossing: "Crossing", Airport: "Airstrip", Cemetery: "Cemetery", Dam: "Dam", Well: "Well", Canal: "Canal", Bench: "Bench", Cliff: "Cliff", Crater: "Crater", Pillar: "Pillar", Plain: "Plain", Island: "Island", Bend: "Bend", Cape: "Point", Lava: "Lava flow", Glacier: "Glacier", Bridge: "Bridge", Tunnel: "Tunnel", Woods: "Woods", Slope: "Slope", Gut: "", Rapids: "Rapids", Beach: "Beach", Bar: "Bar", Isthmus: "Isthmus" };
const shardCache = new Map();
function nameWords(s) { return (s.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().match(/[a-z0-9]+/g)) || []; }
function km(aLat, aLon, bLat, bLon) { const x = (bLon - aLon) * 111.32 * Math.cos((aLat + bLat) * Math.PI / 360), y = (bLat - aLat) * 110.57; return Math.hypot(x, y); }

async function placeNames(env, q, lat, lon) {
  if (!env.PACKS) return [];
  const qw = nameWords(q);
  const anchor = qw.find(w => w.length >= 3 && !NAME_GENERIC.has(w)) || qw.find(w => w.length >= 3);
  if (!anchor) return [];
  const key = anchor.slice(0, 3);
  let rows = shardCache.get(key);
  if (!rows) {
    const o = await env.PACKS.get(`names/v1/${key}.json`);
    rows = o ? await o.json() : [];
    if (shardCache.size > 60) shardCache.delete(shardCache.keys().next().value);
    shardCache.set(key, rows);
  }
  const near = Number.isFinite(lat) && Number.isFinite(lon);
  const out = [];
  for (const r of rows) {
    const w = nameWords(r[0]);
    if (!w.some(x => x.startsWith(anchor))) continue;
    let got = 0; for (const t of qw) if (w.some(x => x.startsWith(t))) got++;
    out.push({ r, got, full: got === qw.length, exact: w.join(" ") === qw.join(" "), d: near ? km(lat, lon, r[4], r[5]) : 0 });
  }
  // every word of the query first, then the most of them; within each, near beats far, an exact name counting for some
  const score = o => Math.log1p(o.d) - (o.exact ? 1.5 : 0);
  out.sort((a, b) => (b.full - a.full) || (b.got - a.got) || score(a) - score(b));
  return out.slice(0, 10).map(({ r, full, exact, d }) => ({
    name: r[0], lat: r[4], lon: r[5], full, exact, source: "gnis",
    context: [NAME_CLASS[r[1]] ?? r[1], r[6] ? `officially ${r[6]}` : ""].filter(Boolean).join(" · "),
    region: [r[2] && `${r[2]} County`, r[3]].filter(Boolean).join(", "),
    distance: near ? Math.round(d * 1000) : undefined,
  }));
}

async function search(request, env, url, routerFetch) {
  const q = (url.searchParams.get("q") || "").trim().slice(0, 120);
  const lat = Number(url.searchParams.get("lat")), lon = Number(url.searchParams.get("lon"));
  const names = placeNames(env, q, lat, lon).catch(() => []);
  const routed = routerProxy(request, env, "search", url, routerFetch).then(r => r.json().then(j => ({ ok: r.ok, j }))).catch(() => null);
  const gn = await names;
  // with place names in hand, a slow router (the maps still loading) does not hold them up
  const rt = await (gn.length ? Promise.race([routed, new Promise(res => setTimeout(() => res(null), 6000))]) : routed);
  const hits = rt && rt.ok && Array.isArray(rt.j.hits) ? rt.j.hits : [];
  if (!gn.length) return rt ? json(rt.j, rt.ok ? 200 : 503) : json({ error: "Search is not reachable right now" }, 503);
  const qw = nameWords(q).join(" ");
  const qws = nameWords(q);
  const ranked = hits.map(h => { const w = nameWords(h.name || ""); return { ...h, exact: w.join(" ") === qw, full: qws.every(t => w.some(x => x.startsWith(t))) }; });
  // a town the map already found is not listed twice
  for (const g of gn) if (!ranked.some(h => nameWords(h.name || "").join(" ") === nameWords(g.name).join(" ") && Number.isFinite(h.lat) && km(h.lat, h.lon, g.lat, g.lon) < 5)) ranked.push(g);
  // the whole query first; within that, the nearer, an exact name counting for some (a town the map knows first among equals)
  const score = h => Math.log1p((h.distance ?? 5e6) / 1000) - (h.exact ? 1.5 : 0) + (h.source === "gnis" ? 0.1 : 0);
  ranked.sort((a, b) => (b.full - a.full) || score(a) - score(b));
  return json({ hits: ranked.slice(0, 12).map(({ exact, full, source, ...h }) => h) });
}

// ---- park a roadbook for the phone ----------------------------------------------------------------

async function park(request, env, url) {
  if (!env.PACKS) return json({ error: "storage not configured" }, 503);
  if (!(await admit(request, env, "park", DAILY.park))) return json({ error: "That is plenty for one day; try again tomorrow" }, 429);
  const text = await readGpx(request);
  const g = parseGpx(text);
  const pts = g.pts.length >= 2 ? g.pts : g.rte;
  if (pts.length < 2) return json({ error: "the file has no track" }, 400);
  const { length } = measure(pts);
  if (length > 5_000_000) return json({ error: "too long to park (over 5,000 km)" }, 400);
  const name = cleanName(url.searchParams.get("name") || g.name || "Roadbook");
  const kept = cuesOn(pts, g.cues);
  const body = writeGpx(name, pts, kept, g.meta);
  let code;
  for (let tries = 0; tries < 5; tries++) {
    code = randomCode(6);
    if (!(await env.PACKS.head(`parked/${code}.gpx`))) break;
  }
  const expires = Date.now() + 24 * 3600 * 1000;
  await env.PACKS.put(`parked/${code}.gpx`, body, { httpMetadata: { contentType: "application/gpx+xml" }, customMetadata: { expires: String(expires), name } });
  await sweepParked(env).catch(() => {});
  return json({ code, name, expires, bytes: body.length, cues: kept.length, length: Math.round(length) });
}

// Each park sweeps out the codes that have expired, so a parked roadbook is actually gone after
// 24 hours rather than lingering until someone asks for it. Parked files are few; one list suffices.
async function sweepParked(env) {
  const now = Date.now();
  const list = await env.PACKS.list({ prefix: "parked/", limit: 500, include: ["customMetadata"] });
  const dead = list.objects.filter(o => {
    const exp = Number(o.customMetadata?.expires || 0);
    return exp ? now > exp : now - o.uploaded.getTime() > 24 * 3600 * 1000;
  }).map(o => o.key);
  if (dead.length) await env.PACKS.delete(dead);
}

async function parked(request, env, code) {
  if (!env.PACKS) return json({ error: "storage not configured" }, 503);
  code = code.toUpperCase().replace(/[^A-Z0-9]/g, "").replace(/O/g, "0").replace(/I|L/g, "1");
  if (code.length !== 6) return json({ error: "a code is six letters" }, 400);
  const obj = await env.PACKS.get(`parked/${code}.gpx`);
  if (!obj) return json({ error: "No roadbook under that code. Codes last 24 hours." }, 404);
  const expires = Number(obj.customMetadata?.expires || 0);
  if (expires && Date.now() > expires) { await env.PACKS.delete(`parked/${code}.gpx`); return json({ error: "That code has expired" }, 410); }
  const name = obj.customMetadata?.name || "Roadbook";
  return new Response(obj.body, { headers: cors({ "Content-Type": "application/gpx+xml; charset=utf-8", "Content-Disposition": `attachment; filename="${safeFile(name)}.gpx"`, "X-Name": encodeURIComponent(name), "Cache-Control": "no-store" }) });
}

// ---- the library -------------------------------------------------------------------------------------

async function publish(request, env, url) {
  if (!env.DB || !env.PACKS) return json({ error: "The library is not set up on this site yet" }, 503);
  // the day's allowance is spent before any parsing, so a flood costs one query each
  if (!(await admit(request, env, "publish", DAILY.publish))) return json({ error: "That is plenty for one day; try again tomorrow" }, 429);
  const text = await readGpx(request);
  const name = url.searchParams.get("name") || request.headers.get("X-Name") && decodeURIComponent(request.headers.get("X-Name"));
  const from = /^(app|planner)$/.test(url.searchParams.get("source") || "") ? url.searchParams.get("source") : "app";
  const r = await addToLibrary(env, text, { trim: Number(url.searchParams.get("trim") || 0), name, from });
  if (r.error) return json({ error: r.error }, r.status || 400);
  return json({ ...r, url: `${url.origin}/api/roadbooks/${r.id}.gpx`, page: `${url.origin}/plan.html#rb=${r.id}` });
}

/** The one way into the library: trimmed, measured, its cues judged, written clean (no times, nothing but the line and its cues). */
async function addToLibrary(env, text, { trim: t = 0, name: asked, from = "app" }) {
  const g = parseGpx(text);
  const trim = Math.min(5000, Math.max(0, Math.round(Number(t) || 0)));
  const source = g.pts.length >= 2 ? g.pts : g.rte;
  if (source.length < 2) return { error: "the file has no track" };
  // trimming never fails open: a ride too short for the chosen trim is refused, not published whole
  const pts = trimEnds(source, trim);
  if (!pts) return { error: `Too short to trim ${trim} m off each end and keep a ride. Choose a smaller trim.` };
  if (trim > 0 && (same(pts[0], source[0]) || same(pts[pts.length - 1], source[source.length - 1]))) return { error: "Trimming did not move the ends; not published" };
  const { length, bbox } = measure(pts);
  if (length < 500) return { error: "too short to publish (under 500 m)" };
  if (length > 5_000_000) return { error: "too long to publish (over 5,000 km)" };
  // cues are judged against the untrimmed line and must belong to the kept stretch: a cue at a trimmed-off end never rides along
  const cues = trim > 0 ? cuesOn(source, g.cues, 150, [trim, measure(source).length - trim]).map(c => shiftRally(c, trim)) : cuesOn(pts, g.cues);
  const name = cleanName(asked || g.name || "Roadbook");
  const id = randomCode(8).toLowerCase();
  const body = writeGpx(name, pts, cues, { ...g.meta, planner: undefined });
  await env.PACKS.put(`library/${id}.gpx`, body, { httpMetadata: { contentType: "application/gpx+xml" }, customMetadata: { name } });
  const line = JSON.stringify(simplify(pts));
  await env.DB.batch([
    env.DB.prepare("INSERT INTO roadbooks (id, name, length_m, cues, min_lon, min_lat, max_lon, max_lat, line, mode, paved, unpaved, source, created, hidden) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)")
      .bind(id, name, Math.round(length), cues.length, bbox[0], bbox[1], bbox[2], bbox[3], line, g.meta.mode || null, numOrNull(g.meta.paved), numOrNull(g.meta.unpaved), from, Date.now()),
  ]);
  return { id, name, length: Math.round(length), cues: cues.length };
}

// POST /api/import: publishes roadbooks staged in the bucket under import/ (each a GPX, its name in the object's
// metadata, trimmed by its "trim" metadata), a few per call, deleting each once it is in. Only whoever holds the bucket's
// keys can stage anything, so the call itself needs no secret: it can only publish what they put there.
async function importStaged(env) {
  if (!env.DB || !env.PACKS) return json({ error: "The library is not set up on this site yet" }, 503);
  const list = await env.PACKS.list({ prefix: "import/", limit: 6, include: ["customMetadata"] });
  const done = [];
  for (const o of list.objects) {
    const obj = await env.PACKS.get(o.key);
    if (!obj) continue;
    const m = obj.customMetadata || {};
    let r;
    try { r = await addToLibrary(env, await obj.text(), { trim: Number(m.trim || 500), name: m.name && decodeURIComponent(m.name), from: "import" }); }
    catch (e) { r = { error: String(e && e.message || e) }; }
    await env.PACKS.delete(o.key);
    done.push({ key: o.key, ...r });
  }
  const more = list.truncated || (await env.PACKS.list({ prefix: "import/", limit: 1 })).objects.length > 0;
  return json({ done, more });
}

async function list(request, env, url) {
  if (!env.DB) return json({ roadbooks: [], note: "The library is not set up on this site yet" });
  // the whole library as points (the middle of each roadbook's box): the planner shows them, clustered, when zoomed out
  if (url.searchParams.get("points") === "1") {
    const { results } = await env.DB.prepare("SELECT id, name, length_m, min_lon, min_lat, max_lon, max_lat FROM roadbooks WHERE hidden = 0 ORDER BY created DESC LIMIT 20000").all();
    return json({ points: results.map(r => ({ id: r.id, name: r.name, length: r.length_m, c: [Math.round((r.min_lon + r.max_lon) * 5e4) / 1e5, Math.round((r.min_lat + r.max_lat) * 5e4) / 1e5] })) }, 200, { "Cache-Control": "public, max-age=300" });
  }
  const bb = (url.searchParams.get("bbox") || "").split(",").map(Number);
  const lim = Number(url.searchParams.get("limit") || 300);
  const limit = Number.isInteger(lim) && lim >= 1 && lim <= 500 ? lim : 300;
  let stmt;
  if (bb.length === 4 && bb.every(Number.isFinite) && bb[0] <= bb[2] && bb[1] <= bb[3] && Math.abs(bb[0]) <= 180 && Math.abs(bb[2]) <= 180 && Math.abs(bb[1]) <= 90 && Math.abs(bb[3]) <= 90) {
    stmt = env.DB.prepare("SELECT id, name, length_m, cues, min_lon, min_lat, max_lon, max_lat, line, mode, paved, unpaved, source, created FROM roadbooks WHERE hidden = 0 AND max_lon >= ? AND min_lon <= ? AND max_lat >= ? AND min_lat <= ? ORDER BY created DESC LIMIT ?").bind(bb[0], bb[2], bb[1], bb[3], limit);
  } else {
    stmt = env.DB.prepare("SELECT id, name, length_m, cues, min_lon, min_lat, max_lon, max_lat, line, mode, paved, unpaved, source, created FROM roadbooks WHERE hidden = 0 ORDER BY created DESC LIMIT ?").bind(limit);
  }
  const { results } = await stmt.all();
  const roadbooks = results.map(r => ({ id: r.id, name: r.name, length: r.length_m, cues: r.cues, bbox: [r.min_lon, r.min_lat, r.max_lon, r.max_lat], line: JSON.parse(r.line), mode: r.mode, paved: r.paved, unpaved: r.unpaved, source: r.source, created: r.created, url: `${url.origin}/api/roadbooks/${r.id}.gpx` }));
  return json({ roadbooks }, 200, { "Cache-Control": "public, max-age=60" });
}

async function roadbook(request, env, rest, url) {
  const m = /^([a-z0-9]{8})(\.gpx)?(?:\/(hide|unhide|delete))?$/.exec(rest);
  if (!m) return json({ error: "not found" }, 404);
  const [, id, gpx, action] = m;
  if (action) {
    const auth = request.headers.get("Authorization") || "";
    if (!env.ADMIN_TOKEN || auth !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "forbidden" }, 403);
    if (request.method !== "POST" && request.method !== "DELETE") return json({ error: "POST" }, 405);
    if (action === "delete") { await env.DB.prepare("DELETE FROM roadbooks WHERE id = ?").bind(id).run(); await env.PACKS.delete(`library/${id}.gpx`); return json({ ok: true, deleted: id }); }
    await env.DB.prepare("UPDATE roadbooks SET hidden = ? WHERE id = ?").bind(action === "hide" ? 1 : 0, id).run();
    return json({ ok: true, id, hidden: action === "hide" });
  }
  if (!env.DB || !env.PACKS) return json({ error: "The library is not set up on this site yet" }, 503);
  if (gpx) {
    const row = await env.DB.prepare("SELECT name, hidden FROM roadbooks WHERE id = ?").bind(id).first();
    if (!row || row.hidden) return json({ error: "not found" }, 404);
    const obj = await env.PACKS.get(`library/${id}.gpx`);
    if (!obj) return json({ error: "not found" }, 404);
    return new Response(obj.body, { headers: cors({ "Content-Type": "application/gpx+xml; charset=utf-8", "Content-Disposition": `attachment; filename="${safeFile(row.name)}.gpx"`, "Cache-Control": "public, max-age=3600" }) });
  }
  const r = await env.DB.prepare("SELECT * FROM roadbooks WHERE id = ? AND hidden = 0").bind(id).first();
  if (!r) return json({ error: "not found" }, 404);
  return json({ id: r.id, name: r.name, length: r.length_m, cues: r.cues, bbox: [r.min_lon, r.min_lat, r.max_lon, r.max_lat], line: JSON.parse(r.line), mode: r.mode, paved: r.paved, unpaved: r.unpaved, source: r.source, created: r.created, url: `${url.origin}/api/roadbooks/${r.id}.gpx` }, 200, { "Cache-Control": "public, max-age=300" });
}

// ---- web search for the inspect panel ---------------------------------------------------------------

async function webSearch(request, env, url, ctx) {
  const q0 = (url.searchParams.get("q") || "").trim().slice(0, 200);
  if (!q0) return json({ error: "q required" }, 400);
  if (!env.BRAVE_KEY) return json({ error: "Web search is not configured on this site", configured: false }, 501);
  // the planner may confine a search to the rider's own list of trusted sites (hostnames only, at most eight)
  const sites = (url.searchParams.get("sites") || "").split(",").map(s => s.trim().toLowerCase()).filter(s => /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s)).slice(0, 8);
  const q = sites.length ? `${q0} (${sites.map(s => "site:" + s).join(" OR ")})` : q0;
  const cache = caches.default;
  const ckey = new Request("https://websearch.roadbook.internal/?q=" + encodeURIComponent(q.toLowerCase()));
  const hit = await cache.match(ckey);
  if (hit) return withCors(hit);
  // a cache miss costs money: one counter for the whole site per day (no visitor in the key), and past the
  // budget the planner falls back to links the rider opens in their own browser
  if (!(await budget(env, "web", Number(env.WEB_DAILY) || 2000))) return json({ error: "Today's web-search budget on this site is spent; the links below open the same searches in your browser", budget: true }, 429, { "Cache-Control": "no-store" });
  const r = await fetch("https://api.search.brave.com/res/v1/web/search?count=8&safesearch=moderate&text_decorations=false&q=" + encodeURIComponent(q), { headers: { Accept: "application/json", "X-Subscription-Token": env.BRAVE_KEY } });
  if (!r.ok) return json({ error: `search answered ${r.status}` }, 502);
  const data = await r.json();
  const results = ((data.web && data.web.results) || []).slice(0, 8).map(w => ({ title: w.title, url: w.url, description: w.description, site: w.meta_url && w.meta_url.hostname }));
  const res = json({ q: q0, sites, results }, 200, { "Cache-Control": "public, max-age=604800" });   // a week: a campground's pages do not change by the day
  ctx && ctx.waitUntil(cache.put(ckey, res.clone()));
  return res;
}

// ---- helpers -----------------------------------------------------------------------------------------

/** The body, read in chunks and abandoned the moment it passes the cap: a 7 MB upload costs 6 MB of reading, not 7. */
async function readGpx(request) {
  if (request.method !== "POST") throw bad("POST a GPX file", 405);
  const len = Number(request.headers.get("Content-Length") || 0);
  if (len > MAX_GPX) throw bad("GPX larger than 6 MB", 413);
  if (!request.body) throw bad("no body");
  const reader = request.body.getReader(); const chunks = []; let total = 0;
  while (true) {
    const { done, value } = await reader.read(); if (done) break;
    total += value.byteLength;
    if (total > MAX_GPX) { try { await reader.cancel(); } catch {} throw bad("GPX larger than 6 MB", 413); }
    chunks.push(value);
  }
  const buf = new Uint8Array(total); let o = 0; for (const c of chunks) { buf.set(c, o); o += c.byteLength; }
  return new TextDecoder().decode(buf);
}
/** One row per visitor per kind per day, under a hash that changes daily so no address is kept; the increment is the check, so parallel requests cannot slip past it. */
async function admit(request, env, kind, max) {
  if (!env.DB) return true;
  const ip = request.headers.get("CF-Connecting-IP") || "";
  const day = new Date().toISOString().slice(0, 10);
  const key = await sha256(`${day}|${kind}|${ip}|${env.SALT || "roadbook"}`);
  const row = await env.DB.prepare("INSERT INTO uploads (k, n) VALUES (?, 1) ON CONFLICT(k) DO UPDATE SET n = n + 1 RETURNING n").bind(key).first();
  return !row || row.n <= max;
}
/** A site-wide counter per kind per day — nothing about who asked — so a paid upstream has a ceiling. */
async function budget(env, kind, max) {
  if (!env.DB) return true;
  const day = new Date().toISOString().slice(0, 10);
  const row = await env.DB.prepare("INSERT INTO uploads (k, n) VALUES (?, 1) ON CONFLICT(k) DO UPDATE SET n = n + 1 RETURNING n").bind(`${kind}|${day}`).first();
  return !row || row.n <= max;
}
function same(a, b) { return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9; }
function cleanName(s) { return String(s).replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, 80) || "Roadbook"; }
function safeFile(s) { return s.replace(/[^A-Za-z0-9 _.-]/g, "_").trim().slice(0, 60) || "roadbook"; }
function numOrNull(v) { const n = Number(v); return Number.isFinite(n) ? n : null; }
function randomCode(n) { const b = new Uint8Array(n); crypto.getRandomValues(b); let s = ""; for (const x of b) s += CODE_ALPHABET[x % CODE_ALPHABET.length]; return s; }
async function sha256(s) { const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)); return [...new Uint8Array(h)].map(b => b.toString(16).padStart(2, "0")).join(""); }
function cors(h = {}) { return { "Access-Control-Allow-Origin": "*", "Access-Control-Expose-Headers": "X-Name, Content-Disposition", ...h }; }
function withCors(res) { const h = new Headers(res.headers); h.set("Access-Control-Allow-Origin", "*"); return new Response(res.body, { status: res.status, headers: h }); }
function json(o, status = 200, extra = {}) { return new Response(JSON.stringify(o), { status, headers: cors({ "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...extra }) }); }
