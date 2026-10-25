// /api/*: the planner's back end.
//   route, snap, search   -> the routing container (the phone's core behind HTTP), sharded west/east
//   park, parked/<code>   -> a roadbook parked for 24 h under a six-letter code, for the phone to fetch
//   roadbooks             -> the public library: D1 for the list, R2 for the GPX files
//   web                   -> Brave web search for the inspect panel, when BRAVE_KEY is set
// Nothing here identifies anyone: no accounts, no cookies, uploads keep only the line and the cues.
import { parseGpx, measure, trimEnds, cuesOn, simplify, writeGpx } from "./gpx.js";

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const MAX_GPX = 6 << 20;

export async function serveApi(request, env, path, ctx, routerFetch) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors({ "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Name", "Access-Control-Max-Age": "86400" }) });
  try {
    if (path === "route" || path === "snap" || path === "search") return routerProxy(request, env, path, url, routerFetch);
    if (path === "park" && request.method === "POST") return park(request, env, url);
    if (path.startsWith("parked/")) return parked(request, env, path.slice(7));
    if (path === "roadbooks") return request.method === "POST" ? publish(request, env, url) : list(request, env, url);
    if (path.startsWith("roadbooks/")) return roadbook(request, env, path.slice(10), url);
    if (path === "web") return webSearch(request, env, url, ctx);
    if (path === "health") return json({ ok: true, router: !!(env.ROUTER || env.ROUTER_URL), library: !!env.DB, storage: !!env.PACKS, web: !!env.BRAVE_KEY });
    return json({ error: "no such endpoint" }, 404);
  } catch (e) {
    return json({ error: String(e && e.message || e) }, 500);
  }
}

// ---- routing -------------------------------------------------------------------------------------

async function routerProxy(request, env, path, url, routerFetch) {
  if (request.method !== "GET") return json({ error: "GET only" }, 405);
  const target = "/" + path + url.search;
  // the container holding the western graphs or the eastern ones, by the first point (or the point)
  const first = (url.searchParams.get("pts") || "").split(/[;|]/)[0] || `${url.searchParams.get("lon")},${url.searchParams.get("lat")}`;
  const lon = Number(first.split(",")[0]);
  const shard = Number.isFinite(lon) && lon > -97 ? "east" : "west";
  if (env.ROUTER && routerFetch) return withCors(await routerFetch(shard, target));
  if (env.ROUTER_URL) return withCors(await fetch(env.ROUTER_URL.replace(/\/$/, "") + target));
  return json({ error: "The router is not deployed yet" }, 503);
}

// ---- park a roadbook for the phone ----------------------------------------------------------------

async function park(request, env, url) {
  if (!env.PACKS) return json({ error: "storage not configured" }, 503);
  const text = await readGpx(request);
  const g = parseGpx(text);
  if (g.pts.length < 2) return json({ error: "the file has no track" }, 400);
  const name = cleanName(url.searchParams.get("name") || g.name || "Roadbook");
  const body = writeGpx(name, g.pts, g.cues, g.meta);
  let code;
  for (let tries = 0; tries < 5; tries++) {
    code = randomCode(6);
    if (!(await env.PACKS.head(`parked/${code}.gpx`))) break;
  }
  const expires = Date.now() + 24 * 3600 * 1000;
  await env.PACKS.put(`parked/${code}.gpx`, body, { httpMetadata: { contentType: "application/gpx+xml" }, customMetadata: { expires: String(expires), name } });
  return json({ code, name, expires, bytes: body.length, cues: g.cues.length, length: Math.round(measure(g.pts).length) });
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
  const text = await readGpx(request);
  const g = parseGpx(text);
  const trim = Math.min(5000, Math.max(0, Number(url.searchParams.get("trim") || 0) || 0));
  let pts = trimEnds(g.pts, trim);
  if (pts.length < 2) return json({ error: "the file has no track" }, 400);
  const { length, bbox } = measure(pts);
  if (length < 500) return json({ error: "too short to publish (under 500 m)" }, 400);
  if (length > 5_000_000) return json({ error: "too long to publish (over 5,000 km)" }, 400);
  const cues = cuesOn(pts, g.cues);
  const name = cleanName(url.searchParams.get("name") || request.headers.get("X-Name") && decodeURIComponent(request.headers.get("X-Name")) || g.name || "Roadbook");
  const source = /^[a-z]+$/.test(url.searchParams.get("source") || "") ? url.searchParams.get("source") : "app";
  // a gentle cap per visitor per day, keyed by a hash that changes daily so no address is kept
  const ip = request.headers.get("CF-Connecting-IP") || "";
  const day = new Date().toISOString().slice(0, 10);
  const key = await sha256(`${day}|${ip}|${env.SALT || "roadbook"}`);
  const row = await env.DB.prepare("SELECT n FROM uploads WHERE k = ?").bind(key).first();
  if (row && row.n >= 40) return json({ error: "That is plenty for one day; try again tomorrow" }, 429);
  const id = randomCode(8).toLowerCase();
  const body = writeGpx(name, pts, cues, { ...g.meta, planner: undefined });
  await env.PACKS.put(`library/${id}.gpx`, body, { httpMetadata: { contentType: "application/gpx+xml" }, customMetadata: { name } });
  const line = JSON.stringify(simplify(pts));
  await env.DB.batch([
    env.DB.prepare("INSERT INTO roadbooks (id, name, length_m, cues, min_lon, min_lat, max_lon, max_lat, line, mode, paved, unpaved, source, created, hidden) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)")
      .bind(id, name, Math.round(length), cues.length, bbox[0], bbox[1], bbox[2], bbox[3], line, g.meta.mode || null, numOrNull(g.meta.paved), numOrNull(g.meta.unpaved), source, Date.now()),
    env.DB.prepare("INSERT INTO uploads (k, n) VALUES (?, 1) ON CONFLICT(k) DO UPDATE SET n = n + 1").bind(key),
  ]);
  return json({ id, name, length: Math.round(length), cues: cues.length, url: `${url.origin}/api/roadbooks/${id}.gpx`, page: `${url.origin}/plan.html#rb=${id}` });
}

async function list(request, env, url) {
  if (!env.DB) return json({ roadbooks: [], note: "The library is not set up on this site yet" });
  const bb = (url.searchParams.get("bbox") || "").split(",").map(Number);
  const limit = Math.min(500, Number(url.searchParams.get("limit") || 300) || 300);
  let stmt;
  if (bb.length === 4 && bb.every(Number.isFinite)) {
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
  const q = (url.searchParams.get("q") || "").trim().slice(0, 200);
  if (!q) return json({ error: "q required" }, 400);
  if (!env.BRAVE_KEY) return json({ error: "Web search is not configured on this site", configured: false }, 501);
  const cache = caches.default;
  const ckey = new Request("https://websearch.roadbook.internal/?q=" + encodeURIComponent(q.toLowerCase()));
  const hit = await cache.match(ckey);
  if (hit) return withCors(hit);
  const r = await fetch("https://api.search.brave.com/res/v1/web/search?count=8&safesearch=moderate&text_decorations=false&q=" + encodeURIComponent(q), { headers: { Accept: "application/json", "X-Subscription-Token": env.BRAVE_KEY } });
  if (!r.ok) return json({ error: `search answered ${r.status}` }, 502);
  const data = await r.json();
  const results = ((data.web && data.web.results) || []).slice(0, 8).map(w => ({ title: w.title, url: w.url, description: w.description, site: w.meta_url && w.meta_url.hostname }));
  const res = json({ q, results }, 200, { "Cache-Control": "public, max-age=86400" });
  ctx && ctx.waitUntil(cache.put(ckey, res.clone()));
  return res;
}

// ---- helpers -----------------------------------------------------------------------------------------

async function readGpx(request) {
  if (request.method !== "POST") throw Object.assign(new Error("POST a GPX file"), { status: 405 });
  const len = Number(request.headers.get("Content-Length") || 0);
  if (len > MAX_GPX) throw new Error("GPX larger than 6 MB");
  const buf = await request.arrayBuffer();
  if (buf.byteLength > MAX_GPX) throw new Error("GPX larger than 6 MB");
  return new TextDecoder().decode(buf);
}
function cleanName(s) { return String(s).replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, 80) || "Roadbook"; }
function safeFile(s) { return s.replace(/[^A-Za-z0-9 _.-]/g, "_").trim().slice(0, 60) || "roadbook"; }
function numOrNull(v) { const n = Number(v); return Number.isFinite(n) ? n : null; }
function randomCode(n) { const b = new Uint8Array(n); crypto.getRandomValues(b); let s = ""; for (const x of b) s += CODE_ALPHABET[x % CODE_ALPHABET.length]; return s; }
async function sha256(s) { const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)); return [...new Uint8Array(h)].map(b => b.toString(16).padStart(2, "0")).join(""); }
function cors(h = {}) { return { "Access-Control-Allow-Origin": "*", "Access-Control-Expose-Headers": "X-Name, Content-Disposition", ...h }; }
function withCors(res) { const h = new Headers(res.headers); h.set("Access-Control-Allow-Origin", "*"); return new Response(res.body, { status: res.status, headers: h }); }
function json(o, status = 200, extra = {}) { return new Response(JSON.stringify(o), { status, headers: cors({ "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...extra }) }); }
