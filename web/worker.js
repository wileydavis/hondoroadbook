// hondoroadbook.com
//   /packs/catalog.json   -> R2 object "catalog.json" (short cache, so new packs show up within minutes)
//   /packs/<path>         -> R2 object <path>, with HTTP range support so the app can resume downloads
//   /download/<apk>       -> the app
//   /tiles/<pack>/<file>  -> a file inside a pack, by byte range (the planner's map tiles)      web/tiles.js
//   /api/*                -> routing, parked roadbooks, the library, web search               web/api.js
//   /mcp                  -> the planner for AI assistants (Model Context Protocol)          web/assistant.js
//   /r/<code>             -> a parked roadbook's page: open it in the app or the planner      web/site/open.html
//   /.well-known/*        -> app links: Android's assetlinks.json, Apple's apple-app-site-association
//   everything else       -> the static site in web/site
import { Container, getContainer } from "@cloudflare/containers";
import { serveTile } from "./tiles.js";
import { serveApi, assistantHelpers } from "./api.js";
import { serveMcp } from "./assistant.js";

/** The routing service (server/Dockerfile). Two instances at most: one holds western graphs, one eastern. */
export class RouterContainer extends Container {
  defaultPort = 8080;
  sleepAfter = "20m";
  envVars = { CATALOG_URL: "https://hondoroadbook.com/packs/catalog.json", PACK_DIR: "/data/packs" };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.hostname.startsWith("www.")) return Response.redirect(url.href.replace("//www.", "//"), 301);
    if (url.pathname.startsWith("/packs/")) return servePackOrRelease(request, env, decodeURIComponent(url.pathname.slice("/packs/".length)));
    if (url.pathname.startsWith("/download/")) return serveApp(request, env, url.pathname.slice("/download/".length));
    if (url.pathname.startsWith("/tiles/")) return serveTile(request, env, url.pathname.slice("/tiles/".length), loadCatalog, fetchPack);
    if (url.pathname.startsWith("/api/")) return serveApi(request, env, url.pathname.slice("/api/".length), ctx, routerFetch(env));
    if (url.pathname === "/mcp" || url.pathname === "/mcp/") return serveMcp(request, env, ctx, routerFetch(env), serveApi, assistantHelpers);
    if (/^\/r\/[A-Za-z0-9]{6}\/?$/.test(url.pathname)) return withSiteHeaders(await env.ASSETS.fetch(new Request(new URL("/open", url), request)));
    if (url.pathname === "/.well-known/apple-app-site-association") return appleAssociation(env);
    if (url.pathname === "/.well-known/assetlinks.json") return new Response(ASSET_LINKS, { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=3600" } });
    return withSiteHeaders(await env.ASSETS.fetch(request));
  },
};

// The pages only ever load their own scripts; map tiles, imagery and the few third-party lookups the
// planner makes are listed explicitly. A library entry that smuggled markup past the API would still
// not be allowed to run anything.
const CSP = [
  "default-src 'self'", "script-src 'self' https://static.cloudflareinsights.com", "worker-src 'self' blob:", "child-src 'self' blob:", "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com", "font-src 'self' https://fonts.gstatic.com",
  "img-src 'self' data: blob: https:", "connect-src 'self' https://cloudflareinsights.com https://en.wikipedia.org https://gis.blm.gov https://s3.amazonaws.com https://imagery.nationalmap.gov https://tiles.maps.eox.at https://overpass-api.de",
  "frame-ancestors 'none'", "base-uri 'self'", "form-action 'self'", "object-src 'none'",
].join("; ");
function withSiteHeaders(res) {
  const ct = res.headers.get("Content-Type") || "";
  if (!ct.includes("text/html")) return res;
  const h = new Headers(res.headers);
  h.set("Content-Security-Policy", CSP); h.set("X-Content-Type-Options", "nosniff"); h.set("Referrer-Policy", "strict-origin-when-cross-origin"); h.set("X-Frame-Options", "DENY");
  return new Response(res.body, { status: res.status, headers: h });
}

/** Android app links: hondoroadbook.com/r/<code> opens in Roadbook, the app signed with this certificate (keys/roadbook-release.jks). */
const ASSET_LINKS = JSON.stringify([{ relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "com.hondogarage.roadbook",
  sha256_cert_fingerprints: ["8C:2E:87:93:0B:C2:A5:16:4B:6C:22:E2:3E:47:32:22:BC:54:AE:75:43:60:7B:CE:E1:2F:E2:32:2A:FB:48:F3"] } }]);

/** Apple's app-site association, which names the app by team and bundle id: the TestFlight workflow writes it to the
 *  bucket (the team id is a repository secret there), so it is served from R2 rather than the site's files. */
async function appleAssociation(env) {
  const o = env.PACKS && await env.PACKS.get("well-known/apple-app-site-association");
  if (!o) return new Response("{}", { status: 404, headers: { "Content-Type": "application/json" } });
  return new Response(o.body, { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=3600" } });
}

/** A request into the routing container that holds the shard's graphs; the container wakes if asleep. */
function routerFetch(env) {
  return (shard, target) => getContainer(env.ROUTER, shard).fetch(new Request("http://router" + target, { headers: { "Accept-Encoding": "gzip" } }));
}

/** The catalog as the site publishes it, parsed, cached a few minutes in this isolate. */
let catalogCache = { at: 0, value: null };
async function loadCatalog(env) {
  if (catalogCache.value && Date.now() - catalogCache.at < 120000) return catalogCache.value;
  const r = await servePackOrRelease(new Request("https://hondoroadbook.com/packs/catalog.json"), env, "catalog.json");
  const value = r.ok ? await r.json() : { packs: [] };
  catalogCache = { at: Date.now(), value };
  return value;
}

/** One byte range of a pack, from R2 when mirrored there, else from the GitHub release. */
async function fetchPack(env, key, range) {
  return servePackOrRelease(new Request("https://hondoroadbook.com/packs/" + key, { headers: { Range: range } }), env, key);
}

// /packs/<key>: the R2 object if the workflow has mirrored one there, otherwise the same file from the
// rolling GitHub release "packs" (catalog.json, us/<state>-roads.mappack → asset <state>-roads.mappack).
const RELEASE = "https://github.com/wileydavis/hondoroadbook/releases/download/packs/";
// Only the catalog and pack files are reachable here: parked and library objects share the bucket but are
// served solely by /api, which knows about expiry and hiding.
const PACK_KEY = /^(catalog\.json(\.sig)?|na-overview\.pmtiles|[a-z]{2}\/[a-z0-9-]+-(roads|terrain|imagery|names)\.(mappack|json))$/;
async function servePackOrRelease(request, env, key) {
  if (!key || !PACK_KEY.test(key)) return new Response("not found", { status: 404 });
  if (env.PACKS) { const r = await servePack(request, env, key); if (r.status !== 404) return r; }
  if (request.method !== "GET" && request.method !== "HEAD") return new Response("method not allowed", { status: 405 });
  const asset = key.split("/").pop();
  const isCatalog = asset === "catalog.json";
  const short = isCatalog || asset === "catalog.json.sig";      // the signature changes with the catalog
  const fwd = new Headers(); const rng = request.headers.get("Range"); if (rng) fwd.set("Range", rng);
  const upstream = await fetch(RELEASE + asset, { method: request.method, headers: fwd, redirect: "follow", cf: rng ? undefined : { cacheTtl: short ? 120 : 86400, cacheEverything: true } });
  if (!upstream.ok) return new Response(isCatalog ? '{"version":1,"packs":[]}' : "not found", { status: isCatalog ? 200 : 404, headers: { "Content-Type": isCatalog ? "application/json" : "text/plain", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" } });
  const headers = new Headers({ "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff" });
  if (isCatalog) { headers.set("Content-Type", "application/json; charset=utf-8"); headers.set("Cache-Control", "public, max-age=120"); }
  else { headers.set("Content-Type", "application/octet-stream"); headers.set("Cache-Control", short ? "public, max-age=120" : "public, max-age=86400"); headers.set("Content-Disposition", `attachment; filename="${asset}"`); }
  for (const h of ["Content-Length", "Content-Range", "ETag", "Last-Modified"]) { const v = upstream.headers.get(h); if (v) headers.set(h, v); }
  return new Response(request.method === "HEAD" ? null : upstream.body, { status: upstream.status, headers });
}

// /download/roadbook.apk and /download/fold.apk: the R2 copy if the release workflow has uploaded
// one (key app/<name>), otherwise the APK committed in the repo's dist/ folder, proxied and cached.
const APK_TYPE = "application/vnd.android.package-archive";
async function serveApp(request, env, name) {
  if (!/^[a-z-]+\.apk$/.test(name)) return new Response("not found", { status: 404 });
  const fromR2 = env.PACKS ? await servePack(request, env, "app/" + name) : new Response(null, { status: 404 });
  if (fromR2.status !== 404) { const h = new Headers(fromR2.headers); h.set("Content-Type", APK_TYPE); return new Response(fromR2.body, { status: fromR2.status, headers: h }); }
  // pass Range through so Android's download manager can resume; raw.githubusercontent and the release both honour it
  const fwd = new Headers(); const rng = request.headers.get("Range"); if (rng) fwd.set("Range", rng);
  // the beta is over git's 100 MB file limit (the voice is bundled): .github/workflows/beta.yml puts it on the release
  const src = name === "roadbook-beta.apk" ? RELEASE + name : "https://raw.githubusercontent.com/wileydavis/hondoroadbook/main/dist/" + name;
  const upstream = await fetch(src, { method: request.method === "HEAD" ? "HEAD" : "GET", headers: fwd, redirect: "follow", cf: rng ? undefined : { cacheTtl: 300, cacheEverything: true } });
  if (!upstream.ok) return new Response("no build published yet", { status: 404, headers: { "Cache-Control": "no-store" } });
  const headers = new Headers({ "Content-Type": APK_TYPE, "Content-Disposition": `attachment; filename="${name}"`, "Cache-Control": "public, max-age=300", "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff" });
  for (const h of ["Content-Length", "Content-Range", "ETag", "Last-Modified"]) { const v = upstream.headers.get(h); if (v) headers.set(h, v); }
  return new Response(request.method === "HEAD" ? null : upstream.body, { status: upstream.status, headers });
}

async function servePack(request, env, key) {
  if (!key || key.endsWith("/")) return new Response("not found", { status: 404 });
  if (request.method !== "GET" && request.method !== "HEAD") return new Response("method not allowed", { status: 405 });
  const isCatalog = key === "catalog.json";
  const range = parseRange(request.headers.get("Range"));
  const object = request.method === "HEAD"
    ? await env.PACKS.head(key)
    : await env.PACKS.get(key, range ? { range } : undefined);
  if (!object) return new Response("not found", { status: 404 });

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("ETag", object.httpEtag);
  headers.set("Accept-Ranges", "bytes");
  headers.set("Access-Control-Allow-Origin", "*");
  if (isCatalog) {
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", "public, max-age=300");
  } else if (key === "catalog.json.sig") {
    // the catalog's signature changes with it, so it is cached no longer than the catalog
    headers.set("Content-Type", "application/octet-stream");
    headers.set("Cache-Control", "public, max-age=300");
  } else {
    if (!headers.get("Content-Type")) headers.set("Content-Type", "application/octet-stream");
    headers.set("Cache-Control", "public, max-age=86400");
    headers.set("Content-Disposition", `attachment; filename="${key.split("/").pop()}"`);
  }
  if (request.method === "HEAD") { headers.set("Content-Length", String(object.size)); return new Response(null, { status: 200, headers }); }
  if (range && object.range) {
    const start = object.range.offset ?? 0;
    const length = object.range.length ?? (object.size - start);
    headers.set("Content-Range", `bytes ${start}-${start + length - 1}/${object.size}`);
    headers.set("Content-Length", String(length));
    return new Response(object.body, { status: 206, headers });
  }
  headers.set("Content-Length", String(object.size));
  return new Response(object.body, { status: 200, headers });
}

function parseRange(h) {
  if (!h) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(h.trim());
  if (!m) return null;
  if (m[1] === "" && m[2] !== "") return { suffix: Number(m[2]) };
  const offset = Number(m[1]);
  if (m[2] === "") return { offset };
  return { offset, length: Number(m[2]) - offset + 1 };
}
