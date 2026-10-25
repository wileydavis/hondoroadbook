// hondoroadbook.com
//   /packs/catalog.json   -> R2 object "catalog.json" (short cache, so new packs show up within minutes)
//   /packs/<path>         -> R2 object <path>, with HTTP range support so the app can resume downloads
//   everything else       -> the static site in web/site
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname.startsWith("www.")) return Response.redirect(url.href.replace("//www.", "//"), 301);
    if (url.pathname.startsWith("/packs/")) return servePack(request, env, decodeURIComponent(url.pathname.slice("/packs/".length)));
    if (url.pathname.startsWith("/download/")) return serveApp(request, env, url.pathname.slice("/download/".length));
    return env.ASSETS.fetch(request);
  },
};

// /download/roadbook.apk and /download/fold.apk: the R2 copy if the release workflow has uploaded
// one (key app/<name>), otherwise the APK committed in the repo's dist/ folder, proxied and cached.
const APK_TYPE = "application/vnd.android.package-archive";
async function serveApp(request, env, name) {
  if (!/^[a-z-]+\.apk$/.test(name)) return new Response("not found", { status: 404 });
  const fromR2 = await servePack(request, env, "app/" + name);
  if (fromR2.status !== 404) { const h = new Headers(fromR2.headers); h.set("Content-Type", APK_TYPE); return new Response(fromR2.body, { status: fromR2.status, headers: h }); }
  // pass Range through so Android's download manager can resume; raw.githubusercontent honours it
  const fwd = new Headers(); const rng = request.headers.get("Range"); if (rng) fwd.set("Range", rng);
  const upstream = await fetch("https://raw.githubusercontent.com/wileydavis/hondoroadbook/main/dist/" + name, { headers: fwd, cf: rng ? undefined : { cacheTtl: 3600, cacheEverything: true } });
  if (!upstream.ok) return new Response("no build published yet", { status: 404, headers: { "Cache-Control": "no-store" } });
  const headers = new Headers({ "Content-Type": APK_TYPE, "Content-Disposition": `attachment; filename="${name}"`, "Cache-Control": "public, max-age=3600", "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff" });
  for (const h of ["Content-Length", "Content-Range", "ETag", "Last-Modified"]) { const v = upstream.headers.get(h); if (v) headers.set(h, v); }
  return new Response(upstream.body, { status: upstream.status, headers });
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
