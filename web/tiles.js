// /tiles/<pack id>/<file>: a file inside a .mappack, served by byte range without unpacking anything.
// The packs are stored (uncompressed) zips, so an entry is one contiguous run of the archive; the
// planner's map reads tiles.pmtiles, terrain.pmtiles and roads.pmtiles this way, exactly as the phone
// reads them from disk. Central directories and the ranges actually requested are cached at the edge.

const FILES = new Set(["tiles.pmtiles", "terrain.pmtiles", "roads.pmtiles", "pack.txt"]);

export async function serveTile(request, env, path, loadCatalog, fetchPack) {
  const m = /^([a-z0-9-]+)\/([a-z.]+)$/.exec(path);
  if (!m || !FILES.has(m[2])) return json({ error: "not found" }, 404);
  if (request.method !== "GET" && request.method !== "HEAD") return json({ error: "method not allowed" }, 405);
  const [, id, file] = m;
  let pack, key, entry;
  if (id === "overview") {
    // the continent overview is a bare .pmtiles on the release, not a zip: the whole file is the entry
    if (file !== "tiles.pmtiles") return json({ error: "not found" }, 404);
    const cat = await loadCatalog(env);
    pack = { id: "overview", built: cat.updated || "0" }; key = "na-overview.pmtiles";
    const size = await objectSize(env, pack, key, fetchPack);
    if (!size) return json({ error: "no overview yet" }, 404);
    entry = { offset: 0, size };
  } else {
    const cat = await loadCatalog(env);
    pack = cat.packs.find(p => p.id === id);
    if (!pack) return json({ error: "no such pack" }, 404);
    key = packKey(pack);                        // "us/montana-roads.mappack"
    // cache identity is the catalog's build date plus the archive's byte size as it is on the shelf right now
    // (probed, cached a minute): while a rebuild is replacing packs one state at a time, the catalog still lists
    // the old sizes for hours, and the new archive must be served as itself rather than refused or mixed with old ranges
    const live = await liveSize(env, key, fetchPack);
    pack = { ...pack, built: `${pack.built}-${live || pack.bytes || 0}` };
    const index = await zipIndex(env, pack, key, fetchPack);
    entry = index.entries[file];
    if (!entry) return json({ error: `${file} is not in this pack` }, 404);
  }

  const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Expose-Headers": "Content-Range, Content-Length, ETag, Accept-Ranges", "Accept-Ranges": "bytes", "ETag": `"${pack.built}-${entry.size}"`, "Cache-Control": "public, max-age=86400", "Content-Type": file.endsWith(".pmtiles") ? "application/octet-stream" : "text/plain" };
  if (request.method === "HEAD") return new Response(null, { status: 200, headers: { ...cors, "Content-Length": String(entry.size) } });

  const range = parseRange(request.headers.get("Range"), entry.size);
  if (range === "bad") return new Response(null, { status: 416, headers: { ...cors, "Content-Range": `bytes */${entry.size}` } });
  const start = range ? range.start : 0, end = range ? range.end : entry.size - 1;
  if (end - start + 1 > 64 << 20) return json({ error: "range too large; ask for at most 64 MB" }, 416);

  // ranges repeat across visitors (headers, directories, the same tiles), so cache them as 200s under a key of their own
  const cache = caches.default;
  const ckey = new Request(`https://tiles.roadbook.internal/${id}/${pack.built}/${file}?r=${start}-${end}`);
  let hit = await cache.match(ckey);
  let body;
  if (hit) body = hit.body;
  else {
    const up = await fetchPack(env, key, `bytes=${entry.offset + start}-${entry.offset + end}`);
    if (up.status !== 206) return json({ error: `pack storage answered ${up.status}` }, 502);
    const buf = await up.arrayBuffer();
    if (buf.byteLength !== end - start + 1) return json({ error: "short read from pack storage" }, 502);
    await cache.put(ckey, new Response(buf, { status: 200, headers: { "Cache-Control": "public, max-age=604800" } }));
    body = buf;
  }
  if (!range) return new Response(body, { status: 200, headers: { ...cors, "Content-Length": String(entry.size) } });
  return new Response(body, { status: 206, headers: { ...cors, "Content-Length": String(end - start + 1), "Content-Range": `bytes ${start}-${end}/${entry.size}` } });
}

/** The size of a bare object, learned once per catalog update. */
async function objectSize(env, pack, key, fetchPack) {
  const cache = caches.default;
  const ckey = new Request(`https://zipindex.roadbook.internal/${pack.id}/${pack.built}/size`);
  const hit = await cache.match(ckey);
  if (hit) return Number(await hit.text());
  const probe = await fetchPack(env, key, "bytes=0-0");
  if (probe.status !== 206) return 0;
  const total = Number((probe.headers.get("Content-Range") || "").split("/")[1]) || 0;
  await probe.arrayBuffer();
  if (total) await cache.put(ckey, new Response(String(total), { headers: { "Cache-Control": "public, max-age=3600" } }));
  return total;
}

/** The archive's current byte size, remembered a minute: a rebuilt pack is noticed within that long. */
async function liveSize(env, key, fetchPack) {
  const cache = caches.default;
  const ckey = new Request(`https://zipindex.roadbook.internal/live/${key}`);
  const hit = await cache.match(ckey);
  if (hit) return Number(await hit.text());
  const probe = await fetchPack(env, key, "bytes=0-0");
  if (probe.status !== 206) return 0;
  const total = Number((probe.headers.get("Content-Range") || "").split("/")[1]) || 0;
  await probe.arrayBuffer();
  if (total) await cache.put(ckey, new Response(String(total), { headers: { "Cache-Control": "public, max-age=60" } }));
  return total;
}

export function packKey(pack) { const u = new URL(pack.url); return u.pathname.replace(/^\/packs\//, ""); }

function parseRange(h, size) {
  if (!h) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(h.trim());
  if (!m) return "bad";
  let start, end;
  if (m[1] === "" && m[2] === "") return "bad";
  if (m[1] === "") { const n = Number(m[2]); start = Math.max(0, size - n); end = size - 1; }
  else { start = Number(m[1]); end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1); }
  if (start > end || start >= size) return "bad";
  return { start, end };
}

// The central directory of one pack, parsed once per build and kept at the edge.
async function zipIndex(env, pack, key, fetchPack) {
  const cache = caches.default;
  const ckey = new Request(`https://zipindex.roadbook.internal/${pack.id}/${pack.built}`);
  const hit = await cache.match(ckey);
  if (hit) return hit.json();
  // size first (suffix ranges are refused by some hosts), then the tail that holds the directory
  const probe = await fetchPack(env, key, "bytes=0-0");
  if (probe.status !== 206) throw new Error(`pack storage answered ${probe.status}`);
  const total = Number((probe.headers.get("Content-Range") || "").split("/")[1]);
  await probe.arrayBuffer();
  if (!total) throw new Error("no size for pack");
  const tailLen = Math.min(65536, total);
  const tailRes = await fetchPack(env, key, `bytes=${total - tailLen}-${total - 1}`);
  const tail = new DataView(await tailRes.arrayBuffer());
  const tailStart = total - tailLen;
  let eocd = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) if (tail.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error("not a zip");
  let cdSize = tail.getUint32(eocd + 12, true), cdOff = tail.getUint32(eocd + 16, true);
  if (cdSize === 0xffffffff || cdOff === 0xffffffff) {
    const loc = eocd - 20;
    if (loc < 0 || tail.getUint32(loc, true) !== 0x07064b50) throw new Error("zip64 locator missing");
    const e64 = Number(tail.getBigUint64(loc + 8, true));
    let rec;
    if (e64 >= tailStart) rec = new DataView(tail.buffer, e64 - tailStart, 56);
    else rec = new DataView(await (await fetchPack(env, key, `bytes=${e64}-${e64 + 55}`)).arrayBuffer());
    if (rec.getUint32(0, true) !== 0x06064b50) throw new Error("zip64 EOCD missing");
    cdSize = Number(rec.getBigUint64(40, true)); cdOff = Number(rec.getBigUint64(48, true));
  }
  let cd;
  if (cdOff >= tailStart) cd = new DataView(tail.buffer, cdOff - tailStart, cdSize);
  else cd = new DataView(await (await fetchPack(env, key, `bytes=${cdOff}-${cdOff + cdSize - 1}`)).arrayBuffer());
  const entries = {};
  const td = new TextDecoder();
  let p = 0;
  while (p + 46 <= cd.byteLength && cd.getUint32(p, true) === 0x02014b50) {
    const method = cd.getUint16(p + 10, true);
    let csize = cd.getUint32(p + 20, true), usize = cd.getUint32(p + 24, true);
    const nameLen = cd.getUint16(p + 28, true), extraLen = cd.getUint16(p + 30, true), commentLen = cd.getUint16(p + 32, true);
    let lho = cd.getUint32(p + 42, true);
    const name = td.decode(new Uint8Array(cd.buffer, cd.byteOffset + p + 46, nameLen));
    let q = p + 46 + nameLen; const qEnd = q + extraLen;
    while (q + 4 <= qEnd) {
      const id = cd.getUint16(q, true), len = cd.getUint16(q + 2, true);
      if (id === 1) { let r = q + 4;
        if (usize === 0xffffffff) { usize = Number(cd.getBigUint64(r, true)); r += 8; }
        if (csize === 0xffffffff) { csize = Number(cd.getBigUint64(r, true)); r += 8; }
        if (lho === 0xffffffff) { lho = Number(cd.getBigUint64(r, true)); } }
      q += 4 + len;
    }
    if (method === 0) entries[name] = { size: usize, localHeader: lho };
    p += 46 + nameLen + extraLen + commentLen;
  }
  // each local header carries its own name and extra lengths; resolve the data offsets now
  for (const name of Object.keys(entries)) {
    const e = entries[name];
    const lh = new DataView(await (await fetchPack(env, key, `bytes=${e.localHeader}-${e.localHeader + 29}`)).arrayBuffer());
    if (lh.getUint32(0, true) !== 0x04034b50) throw new Error("bad local header for " + name);
    e.offset = e.localHeader + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
  }
  const index = { total, entries };
  await cache.put(ckey, new Response(JSON.stringify(index), { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=2592000" } }));
  return index;
}

function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" } }); }
