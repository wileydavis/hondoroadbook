// Roadbook Planner. One file, no build step. The map is the app's own style over the app's own packs
// (read by byte range through /tiles), the router is the app's core behind /api, and what you export is
// the GPX the phone follows. Nothing is stored unless you publish.
(() => {
"use strict";
const $ = s => document.querySelector(s);
const ORIGIN = location.origin;
const MODES = [
  ["FAST", "Fast", "#2563EB"], ["BACKROADS", "Backroads", "#D97706"], ["DIRT", "Dirt", "#8B5A2B"], ["TOWING", "Towing", "#0F766E"],
  ["RIDE", "Ride", "#16A34A"], ["QUIET", "Quiet ride", "#7C3AED"], ["GRAVEL", "Gravel ride", "#65A30D"],
];
const TURNS = ["STRAIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "LEFT", "RIGHT", "SHARP_LEFT", "SHARP_RIGHT", "KEEP_LEFT", "KEEP_RIGHT", "UTURN", "ROUNDABOUT"];
// The app's ArrowView, as SVG: the same shapes in the same 100×100 box, the same accent shaft and head,
// the same signs for a note and a danger, so a cue looks here exactly as it does on the phone.
const SHAPES = {
  STRAIGHT: "M50 92 L50 24", RIGHT: "M32 92 L32 48 Q32 32 48 32 L74 32", LEFT: "M68 92 L68 48 Q68 32 52 32 L26 32",
  SLIGHT_RIGHT: "M42 92 L42 56 Q42 44 50 36 L70 18", SLIGHT_LEFT: "M58 92 L58 56 Q58 44 50 36 L30 18",
  KEEP_RIGHT: "M46 92 L46 60 Q46 48 52 40 L62 22", KEEP_LEFT: "M54 92 L54 60 Q54 48 48 40 L38 22",
  SHARP_RIGHT: "M36 92 L36 40 Q36 22 50 30 L72 52", SHARP_LEFT: "M64 92 L64 40 Q64 22 50 30 L28 52",
  UTURN: "M66 92 L66 46 A16 22 0 0 0 34 46 L34 70", ROUNDABOUT: "M50 92 L50 72 A18 18 0 0 0 50 34 L50 14",
  NOTE: "M50 92 L50 46", DANGER: "M50 92 L50 46",
};
// end point and direction of each shaft, for the head
const ENDS = { STRAIGHT: [50, 24, 0, -1], RIGHT: [74, 32, 1, 0], LEFT: [26, 32, -1, 0], SLIGHT_RIGHT: [70, 18, 20, -18], SLIGHT_LEFT: [30, 18, -20, -18], KEEP_RIGHT: [62, 22, 10, -18], KEEP_LEFT: [38, 22, -10, -18], SHARP_RIGHT: [72, 52, 22, 22], SHARP_LEFT: [28, 52, -22, 22], UTURN: [34, 70, 0, 1], ROUNDABOUT: [50, 14, 0, -1] };
function arrowSvg(turn, level = 0, exit = 0) {
  const d = SHAPES[turn] || SHAPES.STRAIGHT;
  let extra = "";
  if (turn === "NOTE" || turn === "DANGER") {
    const x = 50, y = 46 - 17, r = 19;
    if (turn === "DANGER") { const tri = `M${x} ${y - r} L${x + r * 1.1} ${y + r * 0.8} L${x - r * 1.1} ${y + r * 0.8} Z`; extra = `<path d="${tri}" fill="var(--accent)" stroke="var(--card)" stroke-width="3.25" stroke-linejoin="round"/><text x="${x}" y="${y + r * 0.75}" font-size="${r * 1.3}" font-weight="700" fill="var(--card)" text-anchor="middle" font-family="system-ui,sans-serif">!</text>` + (level > 1 ? `<text x="${x + r * 1.4}" y="${y + r * 0.7}" font-size="${r * 0.5}" font-weight="700" fill="var(--ink)" text-anchor="middle" font-family="system-ui,sans-serif">${"!".repeat(Math.min(3, level))}</text>` : ""); }
    else extra = `<circle cx="${x}" cy="${y}" r="${r}" fill="var(--ink)" stroke="var(--card)" stroke-width="3.25"/><text x="${x}" y="${y + r * 0.45}" font-size="${r * 1.3}" font-weight="700" fill="var(--card)" text-anchor="middle" font-family="system-ui,sans-serif">i</text>`;
  } else {
    const [ex, ey, dx, dy] = ENDS[turn] || ENDS.STRAIGHT; const l = Math.hypot(dx, dy); const tx = dx / l, ty = dy / l, nx = -ty, ny = tx;
    const hl = 20, hw = 15; const tipX = ex + tx * hl * 0.85, tipY = ey + ty * hl * 0.85, bx = ex - tx * hl * 0.15, by = ey - ty * hl * 0.15;
    extra = `<path d="M${tipX} ${tipY} L${bx + nx * hw} ${by + ny * hw} L${bx - nx * hw} ${by - ny * hw} Z" fill="var(--accent)" stroke="var(--card)" stroke-width="3.25" stroke-linejoin="round"/>`;
    if (turn === "ROUNDABOUT" && exit > 0) extra += `<circle cx="84" cy="84" r="14" fill="var(--ink)"/><text x="84" y="90" font-size="18" font-weight="700" fill="var(--card)" text-anchor="middle" font-family="system-ui,sans-serif">${exit}</text>`;
  }
  return `<svg viewBox="0 0 100 100" aria-hidden="true"><path d="${d}" fill="none" stroke="var(--accent)" stroke-width="13" stroke-linecap="round" stroke-linejoin="round"/>${extra}</svg>`;
}
const WORDS = { STRAIGHT: "Straight on", SLIGHT_LEFT: "Slight left", SLIGHT_RIGHT: "Slight right", LEFT: "Left", RIGHT: "Right", SHARP_LEFT: "Sharp left", SHARP_RIGHT: "Sharp right", KEEP_LEFT: "Keep left", KEEP_RIGHT: "Keep right", UTURN: "U-turn", ROUNDABOUT: "Roundabout", DANGER: "Danger", NOTE: "Note" };
const SURF = { motorway: ["Highway", "#2563eb"], trunk: ["Highway", "#2563eb"], primary: ["Paved", "#64748b"], secondary: ["Paved", "#64748b"], tertiary: ["Paved", "#64748b"], unclassified: ["Minor", "#94a3b8"], residential: ["Minor", "#94a3b8"], living_street: ["Minor", "#94a3b8"], service: ["Minor", "#94a3b8"], road: ["Minor", "#94a3b8"], track: ["Two-track", "#8b5a2b"], path: ["Trail", "#a16207"], bridleway: ["Trail", "#a16207"], cycleway: ["Path", "#16a34a"], footway: ["Path", "#16a34a"], pedestrian: ["Path", "#16a34a"], steps: ["Path", "#16a34a"] };
const LAND_TILES = "https://gis.blm.gov/arcgis/rest/services/lands/BLM_Natl_SMA_Cached_with_PriUnk/MapServer/tile/{z}/{y}/{x}";
const LAND_LEGEND = "https://gis.blm.gov/arcgis/rest/services/lands/BLM_Natl_SMA_Cached_with_PriUnk/MapServer/legend?f=pjson";
const NAIP = "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer/exportImage?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&f=image";
const S2 = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg";
const TERRARIUM = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";
const units = (navigator.language || "en-US").endsWith("US") ? "mi" : "km";

// ---- state -------------------------------------------------------------------------------------------------
const S = {
  mode: "DIRT", pts: [], route: null, track: null,
  cues: [],            // {lon,lat,turn,level,text,auto:boolean,at}
  deletedAuto: [],     // positions of auto cues the user removed; the router's cues near them stay hidden
  night: matchMedia("(prefers-color-scheme: dark)").matches, terrain: true, land: false, imagery: false, library: false,
  catalog: null, activePacks: new Set(), overview: new Map(), markers: [], cueMarkers: [], libraryData: null,
  inflight: null,
};

// ---- map ---------------------------------------------------------------------------------------------------
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);
let layersLight = null, layersDark = null;
const map = new maplibregl.Map({ container: "map", style: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#f4efe6" } }] }, center: [-110.5, 45.5], zoom: 6, hash: false, attributionControl: false, maxPitch: 0, dragRotate: false, pitchWithRotate: false });
map.touchZoomRotate.disableRotation();
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
map.addControl(new maplibregl.ScaleControl({ unit: units === "mi" ? "imperial" : "metric" }), "bottom-right");
map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: "© OpenStreetMap contributors · Protomaps · AWS Terrain Tiles · BLM SMA · USGS NAIP · EOX Sentinel-2" }), "bottom-right");

async function init() {
  const [cat, ll, ld] = await Promise.all([fetch("/packs/catalog.json").then(r => r.json()), fetch("/map/layers_light.json").then(r => r.json()), fetch("/map/layers_dark.json").then(r => r.json())]);
  S.catalog = cat.packs || []; layersLight = ll; layersDark = ld;
  buildModes();
  restoreHash();
  updatePacks(true);
  map.on("moveend", () => updatePacks(false));
  map.on("moveend", () => { if (S.library) loadLibrary(); });
  bindMap();
  if (S.pts.length >= 2) reroute();
  const rb = new URLSearchParams(location.hash.slice(1)).get("rb");
  if (rb) openLibraryItem(rb);
}

function roadsPacks() { return S.catalog.filter(p => (p.kind || "roads") === "roads"); }
function terrainPacks() { return S.catalog.filter(p => p.kind === "terrain"); }
function inter(bb, b) { return !(bb[2] < b.getWest() || bb[0] > b.getEast() || bb[3] < b.getSouth() || bb[1] > b.getNorth()); }

/** Packs in view become sources; the style is rebuilt (diffed) when the set changes. */
async function updatePacks(force) {
  const b = map.getBounds(); const z = map.getZoom();
  const want = new Set();
  if (z >= 5.5) for (const p of roadsPacks()) if (inter(p.bbox, b)) want.add(p.id);
  if (z >= 5.5) for (const p of terrainPacks()) if (inter(p.bbox, b)) want.add(p.id);
  let changed = force || want.size !== S.activePacks.size || [...want].some(id => !S.activePacks.has(id));
  if (!changed) return;
  // a roads pack may carry an overview file (small roads at low zoom); ask once
  await Promise.all([...want].filter(id => !S.overview.has(id) && roadsPacks().some(p => p.id === id)).map(async id => {
    try { const r = await fetch(`/tiles/${id}/roads.pmtiles`, { method: "HEAD" }); S.overview.set(id, r.ok); } catch { S.overview.set(id, false); }
  }));
  S.activePacks = want;
  applyStyle();
}

function applyStyle() {
  const style = buildStyle();
  map.setStyle(style, { diff: true });
}

function buildStyle() {
  const night = S.night;
  const template = night ? layersDark : layersLight;
  const sources = {}; const layers = [];
  const roads = roadsPacks().filter(p => S.activePacks.has(p.id));
  const terr = S.terrain ? terrainPacks().filter(p => S.activePacks.has(p.id)) : [];
  for (const p of roads) {
    sources["p_" + p.id] = { type: "vector", url: `pmtiles://${ORIGIN}/tiles/${p.id}/tiles.pmtiles` };
    if (S.overview.get(p.id)) sources["o_" + p.id] = { type: "vector", url: `pmtiles://${ORIGIN}/tiles/${p.id}/roads.pmtiles` };
  }
  for (const p of terr) sources["t_" + p.id] = { type: "raster", url: `pmtiles://${ORIGIN}/tiles/${p.id}/terrain.pmtiles`, tileSize: 256 };
  if (S.land) sources.land = { type: "raster", tiles: [LAND_TILES], tileSize: 256, maxzoom: 15, attribution: "BLM Surface Management Agency" };
  if (S.imagery) { sources.naip = { type: "raster", tiles: [NAIP], tileSize: 256, minzoom: 8, bounds: [-170, 17, -64, 72] }; sources.s2 = { type: "raster", tiles: [S2], tileSize: 256, maxzoom: 14 }; }
  sources.coverage = { type: "geojson", data: coverageGeo() };
  sources.library = { type: "geojson", data: S.library && S.libraryData ? S.libraryData : { type: "FeatureCollection", features: [] } };
  sources.route = { type: "geojson", data: routeGeo() };
  sources.unpaved = { type: "geojson", data: unpavedGeo() };

  let hillDone = false;
  for (const layer of template) {
    if (layer.type === "background") { layers.push(layer); if (S.imagery) { layers.push({ id: "s2", type: "raster", source: "s2", paint: { "raster-opacity": 1 } }); layers.push({ id: "naip", type: "raster", source: "naip", paint: { "raster-opacity": 1 } }); } continue; }
    if (S.imagery && ["earth", "landcover"].includes(layer.id) || S.imagery && layer.id.startsWith("landuse")) continue;   // photos instead of paint
    if (!hillDone && (layer.id === "roads_runway" || layer.id === "water")) {
      hillDone = true;
      if (!S.imagery) for (const p of terr) layers.push({ id: "hill_" + p.id, type: "raster", source: "t_" + p.id, paint: { "raster-opacity": night ? 0.5 : 0.8, "raster-resampling": "linear", "raster-fade-duration": 0 } });
      if (S.land) layers.push({ id: "land", type: "raster", source: "land", paint: { "raster-opacity": S.imagery ? 0.45 : 0.5, "raster-fade-duration": 0 } });
    }
    const fromOverview = layer.source === "overview";
    for (const p of roads) {
      if (fromOverview && !S.overview.get(p.id)) continue;
      const clone = webSafe(JSON.parse(JSON.stringify(layer)));
      clone.id = layer.id + "_" + p.id; clone.source = (fromOverview ? "o_" : "p_") + p.id;
      if (S.imagery && clone.type === "fill" && layer.id === "buildings") clone.paint["fill-opacity"] = 0.25;
      layers.push(clone);
    }
  }
  // the first label layer: everything we draw goes under the labels
  let at = layers.findIndex(l => l.type === "symbol"); if (at < 0) at = layers.length;
  const mode = MODES.find(m => m[0] === S.mode); const color = S.track ? "#7c3aed" : mode[2];
  const ours = [
    { id: "coverage_line", type: "line", source: "coverage", maxzoom: 7, paint: { "line-color": night ? "#777" : "#555", "line-width": 1, "line-dasharray": [2, 2], "line-opacity": 0.6 } },
    { id: "library_line", type: "line", source: "library", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#7c3aed", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1.5, 10, 3, 14, 5], "line-opacity": 0.75 } },
    { id: "route_casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": night ? "#000" : "#fff", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 5, 12, 9, 16, 13], "line-opacity": 0.9 } },
    { id: "route_line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": color, "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 6, 16, 9] } },
    { id: "route_unpaved", type: "line", source: "unpaved", layout: { "line-cap": "butt", "line-join": "round" }, paint: { "line-color": night ? "#fff" : "#fff", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1, 12, 2, 16, 3], "line-dasharray": [2, 2.5], "line-opacity": 0.9 } },
    { id: "route_hit", type: "line", source: "route", paint: { "line-color": "#000", "line-width": 22, "line-opacity": 0.001 } },
  ];
  layers.splice(at, 0, ...ours);
  layers.push({ id: "coverage_label", type: "symbol", source: "coverage", maxzoom: 7, layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Medium"], "text-size": 11, "text-transform": "uppercase", "text-letter-spacing": 0.1 }, paint: { "text-color": night ? "#aaa" : "#666", "text-halo-color": night ? "#000" : "#fff", "text-halo-width": 1 } });
  return { version: 8, glyphs: `${ORIGIN}/map/fonts/{fontstack}/{range}.pbf`, sprite: `${ORIGIN}/map/sprites/${night ? "dark" : "light"}`, sources, layers };
}

/** MapLibre GL JS rejects ["*", interpolate-on-zoom, k]; fold the factor into the stops (the phone's 1.7× is for arm's length anyway). */
function webSafe(layer) {
  const fix = v => { if (Array.isArray(v) && v[0] === "*" && Array.isArray(v[1]) && v[1][0] === "interpolate" && typeof v[2] === "number") { const e = v[1].slice(); for (let i = 4; i < e.length; i += 2) e[i] = +(e[i] * v[2] * 0.7).toFixed(2); return e; } return v; };
  if (layer.layout) for (const k of Object.keys(layer.layout)) layer.layout[k] = fix(layer.layout[k]);
  if (layer.paint) for (const k of Object.keys(layer.paint)) layer.paint[k] = fix(layer.paint[k]);
  return layer;
}
function coverageGeo() {
  const seen = new Set();
  return { type: "FeatureCollection", features: roadsPacks().filter(p => { if (seen.has(p.region)) return false; seen.add(p.region); return true; }).map(p => { const [a, b, c, d] = p.bbox; return { type: "Feature", properties: { name: p.region }, geometry: { type: "Polygon", coordinates: [[[a, b], [c, b], [c, d], [a, d], [a, b]]] } }; }) };
}
function lineCoords() { return S.route ? S.route.coords : S.track ? S.track.coords : null; }
function routeGeo() { const c = lineCoords(); return { type: "FeatureCollection", features: c ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: c } }] : [] }; }
function unpavedGeo() {
  if (!S.route) return { type: "FeatureCollection", features: [] };
  return { type: "FeatureCollection", features: S.route.surface.filter(r => r.unpaved || r.cls === "track" || r.cls === "path").map(r => ({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: S.route.coords.slice(r.i, r.j + 1) } })) };
}
function refreshRouteSources() {
  const r = map.getSource("route"); if (r) r.setData(routeGeo());
  const u = map.getSource("unpaved"); if (u) u.setData(unpavedGeo());
}

// ---- modes, layers, panel --------------------------------------------------------------------------------------
function buildModes() {
  const el = $("#modes"); el.innerHTML = "";
  for (const [id, label, color] of MODES) {
    const b = document.createElement("button"); b.textContent = label; b.style.setProperty("--c", color); b.dataset.mode = id; b.setAttribute("role", "radio");
    b.className = id === S.mode ? "on" : ""; b.title = id;
    b.onclick = () => { S.mode = id; for (const x of el.children) x.className = x.dataset.mode === id ? "on" : ""; if (S.track) { status("Switching from a loaded track to routing: the line will be rebuilt between the points you add", false); } if (S.pts.length >= 2) reroute(); else applyStyle(); saveHash(); };
    el.appendChild(b);
  }
}
$("#lyTerrain").checked = S.terrain; $("#lyNight").checked = S.night; document.body.classList.toggle("night", S.night);
$("#lyTerrain").onchange = e => { S.terrain = e.target.checked; applyStyle(); };
$("#lyNight").onchange = e => { S.night = e.target.checked; document.body.classList.toggle("night", S.night); applyStyle(); };
$("#lyImagery").onchange = e => { S.imagery = e.target.checked; applyStyle(); };
$("#lyLand").onchange = e => { S.land = e.target.checked; applyStyle(); $("#landKey").hidden = !S.land; if (S.land) landLegend(); };
$("#lyLibrary").onchange = e => { S.library = e.target.checked; if (S.library) loadLibrary(); else { S.libraryData = null; applyStyle(); } };
$("#btnSide").onclick = () => { $("#side").classList.add("hidden"); $("#btnSideShow").hidden = false; };
$("#btnSideShow").onclick = () => { $("#side").classList.remove("hidden"); $("#btnSideShow").hidden = true; };
document.querySelectorAll("[data-close]").forEach(b => b.onclick = () => b.closest(".sheet").hidden = true);

async function landLegend() {
  const k = $("#landKey"); if (k.dataset.done) return;
  try {
    const r = await fetch(LAND_LEGEND); const j = await r.json();
    const feat = (j.layers || []).filter(l => l.layerId >= 19 && l.legend && l.legend.length);
    k.innerHTML = feat.map(l => `<div><img src="data:${l.legend[0].contentType};base64,${l.legend[0].imageData}" alt=""> ${esc(l.layerName)}</div>`).join("") || "Legend unavailable";
    k.dataset.done = "1";
  } catch { k.textContent = "Surface management agency (BLM data). Legend unavailable offline."; }
}

let statusT;
function status(msg, err) { const s = $("#status"); s.textContent = msg; s.className = "status" + (err ? " err" : ""); s.hidden = false; clearTimeout(statusT); statusT = setTimeout(() => s.hidden = true, err ? 6000 : 3000); }
function fmtDist(m) { return units === "mi" ? (m / 1609.344 < 10 ? (m / 1609.344).toFixed(1) : Math.round(m / 1609.344)) + " mi" : (m / 1000 < 10 ? (m / 1000).toFixed(1) : Math.round(m / 1000)) + " km"; }
function fmtDistShort(m) { return units === "mi" ? (m < 300 ? Math.round(m * 3.28084) + " ft" : (m / 1609.344).toFixed(m / 1609.344 < 10 ? 1 : 0) + " mi") : (m < 1000 ? Math.round(m) + " m" : (m / 1000).toFixed(m / 1000 < 10 ? 1 : 0) + " km"); }
function fmtTime(s) { const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60); return h ? `${h} h ${m} min` : `${m} min`; }
function fmtElev(m) { return units === "mi" ? Math.round(m * 3.28084).toLocaleString() + " ft" : Math.round(m).toLocaleString() + " m"; }
function esc(s) { return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }

// ---- points and markers ------------------------------------------------------------------------------------------
function renderPoints() {
  for (const m of S.markers) m.remove(); S.markers = [];
  S.pts.forEach((p, i) => {
    const el = document.createElement("div");
    const last = i === S.pts.length - 1;
    el.className = "marker-pt " + (i === 0 ? "a" : last ? "b" : "via");
    el.textContent = i === 0 ? "A" : last ? "B" : "";
    el.title = i === 0 ? "Start — drag to move, click to delete" : last ? "Finish — drag to move, click to delete" : "Stop — drag to move, click to delete";
    const mk = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([p.lon, p.lat]).addTo(map);
    let moved = false;
    mk.on("dragstart", () => { moved = true; });
    mk.on("drag", () => { const ll = mk.getLngLat(); S.pts[i] = { lon: ll.lng, lat: ll.lat }; previewRoute(); });
    mk.on("dragend", () => { const ll = mk.getLngLat(); S.pts[i] = { lon: ll.lng, lat: ll.lat }; reroute(); });
    el.addEventListener("click", e => { e.stopPropagation(); if (moved) { moved = false; return; } S.pts.splice(i, 1); afterPointsChange(); });
    S.markers.push(mk);
  });
}

function afterPointsChange() {
  S.track = null;
  renderPoints();
  if (S.pts.length >= 2) reroute();
  else { S.route = null; abortInflight(); refreshRouteSources(); renderCues(); renderStats(); }
  saveHash();
}

// ---- routing -----------------------------------------------------------------------------------------------------
function abortInflight() { if (S.inflight) { S.inflight.abort(); S.inflight = null; } }
async function callRoute(pts, signal) {
  const q = pts.map(p => `${p.lon.toFixed(6)},${p.lat.toFixed(6)}`).join(";");
  const r = await fetch(`/api/route?pts=${encodeURIComponent(q)}&mode=${S.mode}`, { signal });
  const j = await r.json();
  if (!r.ok) throw Object.assign(new Error(j.error || `router ${r.status}`), { leg: j.leg });
  return j;
}

let previewT = 0;
/** While dragging: a quick re-route at most every 350 ms, never blocking the drag. */
function previewRoute() {
  const now = Date.now(); if (now - previewT < 350) return; previewT = now;
  abortInflight(); const ac = new AbortController(); S.inflight = ac;
  callRoute(S.pts, ac.signal).then(j => { if (S.inflight === ac) { S.inflight = null; setRoute(j, true); } }).catch(() => {});
}

async function reroute() {
  if (S.pts.length < 2) return;
  abortInflight(); const ac = new AbortController(); S.inflight = ac;
  status("Routing…");
  try {
    const j = await callRoute(S.pts, ac.signal);
    if (S.inflight !== ac) return; S.inflight = null;
    setRoute(j, false);
    $("#status").hidden = true;
  } catch (e) {
    if (e.name === "AbortError") return;
    S.inflight = null;
    status(e.message, true);
  }
}

/** Takes a router answer. Auto cues are replaced; the user's own cues stay if they still touch the line. */
function setRoute(j, preview) {
  S.route = j; S.track = null;
  refreshRouteSources();
  if (preview) return;
  // the points sit where the router put them: on the road or trail it found, not in the sagebrush beside it
  if (j.snapped && j.snapped.length === S.pts.length) S.pts = j.snapped.map(p => ({ lon: p.lon, lat: p.lat }));
  const line = j.coords;
  const kept = S.cues.filter(c => !c.auto).filter(c => nearLine(c, line) < 150);
  const dropped = S.cues.filter(c => !c.auto).length - kept.length;
  const auto = j.cues.filter(c => !S.deletedAuto.some(d => dist([d.lon, d.lat], [c.lon, c.lat]) < 40)).map(c => ({ lon: c.lon, lat: c.lat, turn: c.turn, level: 0, exit: c.exit || 0, text: c.turn === "ROUNDABOUT" ? c.name : (c.name || ""), auto: true }));
  S.cues = [...auto, ...kept];
  if (dropped) status(`${dropped} of your cues no longer touch the route and were dropped`, true);
  renderAll();
}

function renderAll() { renderPoints(); renderCues(); renderStats(); saveHash(); if (lineCoords()) elevation(); }

// ---- stats --------------------------------------------------------------------------------------------------------
function renderStats() {
  const c = lineCoords();
  const have = !!c;
  $("#empty").hidden = have; $("#stats").hidden = !have; $("#cuesSec").hidden = !have; $("#actions").hidden = !have;
  if (!have) return;
  const len = S.route ? S.route.length : lineLength(c);
  $("#dist").textContent = fmtDist(len);
  $("#time").textContent = S.route ? `about ${fmtTime(S.route.time)} · ${MODES.find(m => m[0] === S.route.mode)[1]}` : (S.track ? "loaded track" : "");
  const bar = $("#surface"), key = $("#surfaceKey"); bar.innerHTML = ""; key.innerHTML = "";
  if (S.route) {
    const groups = new Map();
    for (const r of S.route.surface) { const k = SURF[r.cls] || ["Other", "#999"]; const label = r.unpaved && k[0] !== "Two-track" && k[0] !== "Trail" ? "Unpaved road" : k[0]; const color = label === "Unpaved road" ? "#b45309" : k[1]; const g = groups.get(label) || { m: 0, color }; g.m += r.m; groups.set(label, g); }
    const order = ["Highway", "Paved", "Minor", "Unpaved road", "Two-track", "Trail", "Path", "Other"];
    for (const label of order) { const g = groups.get(label); if (!g) continue; const sp = document.createElement("span"); sp.style.width = (100 * g.m / len) + "%"; sp.style.background = g.color; sp.title = `${label}: ${fmtDist(g.m)}`; bar.appendChild(sp); const k = document.createElement("span"); k.innerHTML = `<i style="background:${g.color}"></i>${label} ${fmtDist(g.m)}`; key.appendChild(k); }
    const legs = $("#legs"); legs.innerHTML = S.route.legs.length > 1 ? S.route.legs.map((l, i) => `${i === 0 ? "A" : i} → ${i === S.route.legs.length - 1 ? "B" : i + 1}: ${fmtDist(l.length)}, ${fmtTime(l.time)}`).join(" · ") : "";
  } else $("#legs").innerHTML = "";
}
function lineLength(c) { let m = 0; for (let i = 1; i < c.length; i++) m += dist(c[i - 1], c[i]); return m; }
function dist(a, b) { const R = 6371000, r = Math.PI / 180; const dLat = (b[1] - a[1]) * r, dLon = (b[0] - a[0]) * r; const x = dLon * Math.cos((a[1] + b[1]) / 2 * r); return R * Math.hypot(dLat, x); }
function nearLine(c, line) { let best = Infinity; for (let i = 1; i < line.length; i++) { const d = segDist([c.lon, c.lat], line[i - 1], line[i]); if (d < best) best = d; } return best; }
function segDist(p, a, b) { const k = Math.cos(p[1] * Math.PI / 180); const P = [p[0] * k, p[1]], A = [a[0] * k, a[1]], B = [b[0] * k, b[1]]; const dx = B[0] - A[0], dy = B[1] - A[1]; const l2 = dx * dx + dy * dy; let t = l2 ? ((P[0] - A[0]) * dx + (P[1] - A[1]) * dy) / l2 : 0; t = Math.max(0, Math.min(1, t)); return Math.hypot(P[0] - (A[0] + t * dx), P[1] - (A[1] + t * dy)) * 111320; }
/** Nearest vertex index and distance along the line for a point. */
function alongLine(line, p) {
  let best = Infinity, bi = 0, bt = 0;
  for (let i = 1; i < line.length; i++) { const d = segDist(p, line[i - 1], line[i]); if (d < best) { best = d; bi = i; } }
  const cum = cumulative(line);
  const a = line[bi - 1], b = line[bi]; const segLen = dist(a, b);
  if (segLen > 0) { const k = Math.cos(p[1] * Math.PI / 180); const dx = (b[0] - a[0]) * k, dy = b[1] - a[1]; bt = Math.max(0, Math.min(1, (((p[0] - a[0]) * k) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy))); }
  return { index: bi, at: cum[bi - 1] + segLen * bt, d: best };
}
let cumCache = { line: null, cum: null };
function cumulative(line) { if (cumCache.line === line) return cumCache.cum; const cum = new Float64Array(line.length); for (let i = 1; i < line.length; i++) cum[i] = cum[i - 1] + dist(line[i - 1], line[i]); cumCache = { line, cum }; return cum; }

// ---- cues --------------------------------------------------------------------------------------------------------
function renderCues() {
  for (const m of S.cueMarkers) m.remove(); S.cueMarkers = [];
  const ol = $("#cues"); ol.innerHTML = "";
  const line = lineCoords(); if (!line) { $("#cueCount").textContent = ""; return; }
  for (const c of S.cues) c.at = alongLine(line, [c.lon, c.lat]).at;
  S.cues.sort((a, b) => a.at - b.at);
  $("#cueCount").textContent = S.cues.length ? `(${S.cues.length})` : "";
  S.cues.forEach((c, idx) => {
    const li = document.createElement("li"); li.className = c.auto ? "auto" : "user";
    const g = arrowSvg(c.turn, c.level, c.exit);
    li.innerHTML = `<span class="g">${g}</span><span><b>${esc(WORDS[c.turn] || c.turn)}</b>${c.text ? `<span class="words">${esc(c.text)}</span>` : ""}</span><span class="at">${fmtDistShort(c.at)}</span>`;
    li.onclick = () => { map.flyTo({ center: [c.lon, c.lat], zoom: Math.max(map.getZoom(), 14) }); editCue(idx); };
    ol.appendChild(li);
    const el = document.createElement("div"); el.className = "marker-cue" + (c.turn === "DANGER" ? " danger" : c.turn === "NOTE" ? " note" : "") + (c.auto ? "" : " user"); el.innerHTML = g; el.title = (WORDS[c.turn] || c.turn) + (c.text ? ": " + c.text : "");
    el.addEventListener("click", e => { e.stopPropagation(); editCue(idx); });
    S.cueMarkers.push(new maplibregl.Marker({ element: el, anchor: "center" }).setLngLat([c.lon, c.lat]).addTo(map));
  });
}

let editing = null;   // {idx} or {lon,lat} for a new cue
const grid = $("#turnGrid");
for (const t of TURNS) { const b = document.createElement("button"); b.dataset.turn = t; b.innerHTML = arrowSvg(t); b.title = WORDS[t]; grid.appendChild(b); }
document.querySelectorAll("#cueEd .dangerRow [data-turn]").forEach(b => { const t = b.dataset.turn; b.innerHTML = arrowSvg(t, Number(b.dataset.level || 0)) + (t === "NOTE" ? "<span>Note</span>" : ""); b.title = t === "NOTE" ? "A note, read in your words" : `Danger ${b.dataset.level}`; });
document.querySelectorAll("#cueEd [data-turn]").forEach(b => b.onclick = () => { document.querySelectorAll("#cueEd [data-turn]").forEach(x => x.classList.remove("on")); b.classList.add("on"); if (b.dataset.turn === "NOTE") $("#cueText").focus(); });
function editCue(idx) {
  editing = { idx }; const c = S.cues[idx];
  openCueEditor(c.turn, c.level, c.text, "Edit cue");
}
function newCue(lon, lat) { editing = { lon, lat }; openCueEditor("STRAIGHT", 0, "", "New cue"); }
function openCueEditor(turn, level, text, title) {
  $("#cueEdTitle").textContent = title; $("#cueText").value = text || "";
  document.querySelectorAll("#cueEd [data-turn]").forEach(x => x.classList.toggle("on", x.dataset.turn === turn && (turn !== "DANGER" || Number(x.dataset.level) === (level || 2))));
  $("#cueDelete").hidden = editing.idx == null;
  for (const s of document.querySelectorAll(".sheet")) s.hidden = true;
  $("#cueEd").hidden = false;
}
$("#cueSave").onclick = () => {
  const on = $("#cueEd [data-turn].on"); if (!on) return;
  const turn = on.dataset.turn, level = turn === "DANGER" ? Number(on.dataset.level) : 0, text = $("#cueText").value.trim();
  if (turn === "NOTE" && !text) { $("#cueText").focus(); return; }
  if (editing.idx != null) { const c = S.cues[editing.idx]; if (c.auto) S.deletedAuto.push({ lon: c.lon, lat: c.lat }); Object.assign(c, { turn, level, text, auto: false }); }
  else S.cues.push({ lon: editing.lon, lat: editing.lat, turn, level, text, auto: false });
  $("#cueEd").hidden = true; renderCues();
};
$("#cueDelete").onclick = () => { if (editing.idx == null) return; const c = S.cues[editing.idx]; if (c.auto) S.deletedAuto.push({ lon: c.lon, lat: c.lat }); S.cues.splice(editing.idx, 1); $("#cueEd").hidden = true; renderCues(); };
$("#btnAutoCues").onclick = () => { S.deletedAuto = []; S.cues = S.cues.filter(c => false); if (S.route) setRoute(S.route, false); else renderCues(); };

// ---- map interaction: click to add, drag the line for a stop, click the line for the menu ------------------------------
function bindMap() {
  let drag = null;
  map.on("click", e => {
    if ($("#ctx").hidden === false) { $("#ctx").hidden = true; return; }
    if (e.originalEvent.target.closest && e.originalEvent.target.closest(".marker-pt, .marker-cue")) return;
    const onRoute = map.queryRenderedFeatures(e.point, { layers: ["route_hit"] }).length > 0;
    const onLib = S.library && map.queryRenderedFeatures(e.point, { layers: ["library_line"] });
    if (onRoute) { showCtx(e); return; }
    if (onLib && onLib.length) { openLibraryItem(onLib[0].properties.id); return; }
    if (S.track) { status("This is a loaded track. Clear it to plan a new route.", false); return; }
    S.pts.push({ lon: e.lngLat.lng, lat: e.lngLat.lat }); afterPointsChange();
  });
  map.on("mouseenter", "route_hit", () => map.getCanvas().style.cursor = "grab");
  map.on("mouseleave", "route_hit", () => map.getCanvas().style.cursor = "");
  map.on("mouseenter", "library_line", () => map.getCanvas().style.cursor = "pointer");
  map.on("mouseleave", "library_line", () => map.getCanvas().style.cursor = "");
  const start = (point, lngLat) => {
    if (!S.route || S.track) return false;
    const hit = map.queryRenderedFeatures(point, { layers: ["route_hit"] });
    if (!hit.length) return false;
    // which leg was grabbed decides where the new stop goes in the order
    const { index } = alongLine(S.route.coords, [lngLat.lng, lngLat.lat]);
    let leg = S.route.legs.findIndex(l => index > l.start && index <= l.end); if (leg < 0) leg = S.route.legs.length - 1;
    const el = document.createElement("div"); el.className = "marker-pt via ghost"; el.style.pointerEvents = "none";
    drag = { leg, insertAt: leg + 1, start: point, ghost: new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map), moved: false, pts: S.pts.slice() };
    map.dragPan.disable(); map.getCanvas().style.cursor = "grabbing";
    return true;
  };
  const move = (point, lngLat) => {
    if (!drag) return;
    if (!drag.moved && Math.hypot(point.x - drag.start.x, point.y - drag.start.y) < 4) return;
    drag.moved = true; drag.ghost.setLngLat(lngLat);
    S.pts = drag.pts.slice(); S.pts.splice(drag.insertAt, 0, { lon: lngLat.lng, lat: lngLat.lat });
    previewRoute();
  };
  const end = (lngLat) => {
    if (!drag) return;
    map.dragPan.enable(); map.getCanvas().style.cursor = ""; drag.ghost.remove();
    if (drag.moved) { S.pts = drag.pts.slice(); S.pts.splice(drag.insertAt, 0, { lon: lngLat.lng, lat: lngLat.lat }); afterPointsChange(); }
    else { S.pts = drag.pts; }
    drag = null;
  };
  map.on("mousedown", e => { if (e.originalEvent.button === 0) start(e.point, e.lngLat); });
  map.on("mousemove", e => move(e.point, e.lngLat));
  map.on("mouseup", e => end(e.lngLat));
  map.on("touchstart", e => { if (e.points.length === 1 && start(e.point, e.lngLat)) e.preventDefault(); });
  map.on("touchmove", e => { if (drag) { e.preventDefault(); move(e.point, e.lngLat); } });
  map.on("touchend", e => end(e.lngLat));
  map.on("touchcancel", e => end(e.lngLat));
  map.on("contextmenu", e => { if (lineCoords()) showCtx(e); });
}

let ctxAt = null;
function showCtx(e) {
  const c = $("#ctx"); ctxAt = { lon: e.lngLat.lng, lat: e.lngLat.lat };
  const line = lineCoords(); const snap = line ? alongLine(line, [ctxAt.lon, ctxAt.lat]) : null;
  if (snap && snap.d < 400) { const p = line[snap.index]; ctxAt = { lon: p[0], lat: p[1], index: snap.index }; }
  c.style.left = Math.min(e.point.x + 6, innerWidth - 200) + "px"; c.style.top = Math.min(e.point.y + 6, innerHeight - 140) + "px"; c.hidden = false;
  c.querySelector('[data-act="via"]').hidden = !S.route;
}
$("#ctx").addEventListener("click", e => {
  const act = e.target.dataset.act; if (!act) return; $("#ctx").hidden = true;
  if (act === "cue") newCue(ctxAt.lon, ctxAt.lat);
  if (act === "inspect") inspect(ctxAt.lon, ctxAt.lat);
  if (act === "via" && S.route) { let leg = S.route.legs.findIndex(l => ctxAt.index > l.start && ctxAt.index <= l.end); if (leg < 0) leg = S.route.legs.length - 1; S.pts.splice(leg + 1, 0, { lon: ctxAt.lon, lat: ctxAt.lat }); afterPointsChange(); }
});
document.addEventListener("keydown", e => { if (e.key === "Escape") { $("#ctx").hidden = true; for (const s of document.querySelectorAll(".sheet")) s.hidden = true; } });

// ---- elevation from the terrain tiles -----------------------------------------------------------------------------
const tileCache = new Map();
async function terrariumTile(z, x, y) {
  const k = `${z}/${x}/${y}`; if (tileCache.has(k)) return tileCache.get(k);
  const p = (async () => { const r = await fetch(TERRARIUM.replace("{z}", z).replace("{x}", x).replace("{y}", y)); if (!r.ok) throw new Error("tile"); const bmp = await createImageBitmap(await r.blob()); const cv = new OffscreenCanvas(256, 256); const ctx = cv.getContext("2d"); ctx.drawImage(bmp, 0, 0); return ctx.getImageData(0, 0, 256, 256).data; })();
  tileCache.set(k, p); p.catch(() => tileCache.delete(k)); return p;
}
async function elevAt(lon, lat, z = 12) {
  const n = 2 ** z; const x = (lon + 180) / 360 * n; const latR = lat * Math.PI / 180; const y = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
  const tx = Math.floor(x), ty = Math.floor(y); const px = Math.min(255, Math.floor((x - tx) * 256)), py = Math.min(255, Math.floor((y - ty) * 256));
  const d = await terrariumTile(z, tx, ty); const i = (py * 256 + px) * 4;
  return d[i] * 256 + d[i + 1] + d[i + 2] / 256 - 32768;
}
let elevRun = 0;
async function elevation() {
  const line = lineCoords(); const run = ++elevRun; const cv = $("#elev"); const txt = $("#elevTxt");
  if (!line || line.length < 2) { cv.hidden = true; txt.textContent = ""; return; }
  const cum = cumulative(line); const total = cum[cum.length - 1];
  const step = Math.max(60, total / 300); const samples = [];
  let i = 1;
  for (let s = 0; s <= total; s += step) { while (i < line.length - 1 && cum[i] < s) i++; const a = line[i - 1], b = line[i]; const t = cum[i] > cum[i - 1] ? (s - cum[i - 1]) / (cum[i] - cum[i - 1]) : 0; samples.push([a[0] + (b[0] - a[0]) * Math.max(0, Math.min(1, t)), a[1] + (b[1] - a[1]) * Math.max(0, Math.min(1, t)), s]); }
  try {
    const el = await Promise.all(samples.map(p => elevAt(p[0], p[1])));
    if (run !== elevRun) return;
    // gain with a 5 m hysteresis so GPS-scale noise does not count as climbing
    let gain = 0, loss = 0, ref = el[0];
    for (const e of el) { if (e - ref > 5) { gain += e - ref; ref = e; } else if (ref - e > 5) { loss += ref - e; ref = e; } }
    const min = Math.min(...el), max = Math.max(...el);
    txt.textContent = `↑ ${fmtElev(gain)} · ↓ ${fmtElev(loss)} · ${fmtElev(min)} – ${fmtElev(max)}`;
    cv.hidden = false; const ctx = cv.getContext("2d"); const W = cv.width, H = cv.height; ctx.clearRect(0, 0, W, H);
    const ink = getComputedStyle(document.body).getPropertyValue("--soft") || "#777";
    const span = Math.max(50, max - min); const pad = 4;
    ctx.beginPath(); ctx.moveTo(0, H);
    el.forEach((e, k) => { const x = samples[k][2] / total * W; const y = H - pad - (e - min) / span * (H - 2 * pad); ctx.lineTo(x, y); });
    ctx.lineTo(W, H); ctx.closePath(); ctx.fillStyle = MODES.find(m => m[0] === S.mode)[2] + "55"; ctx.fill();
    ctx.beginPath(); el.forEach((e, k) => { const x = samples[k][2] / total * W; const y = H - pad - (e - min) / span * (H - 2 * pad); k ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }); ctx.strokeStyle = ink.trim() || "#777"; ctx.lineWidth = 1.5; ctx.stroke();
  } catch { cv.hidden = true; txt.textContent = ""; }
}

// ---- inspect ---------------------------------------------------------------------------------------------------------
let inspMap = null;
async function inspect(lon, lat) {
  for (const s of document.querySelectorAll(".sheet")) s.hidden = true;
  const sh = $("#inspect"); sh.hidden = false;
  $("#inspCoord").textContent = `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
  const g = `${lat},${lon}`;
  $("#inspLinks").innerHTML = `<a target="_blank" rel="noopener" href="https://www.google.com/maps/@?api=1&map_action=map&center=${g}&zoom=17&basemap=satellite">Google Maps satellite</a><a target="_blank" rel="noopener" href="https://earth.google.com/web/@${lat},${lon},1000a,800d,35y,0h,45t,0r">Google Earth</a><a target="_blank" rel="noopener" href="https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=${g}">Street View</a><a target="_blank" rel="noopener" href="https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=16/${lat}/${lon}">OpenStreetMap</a><button id="inspS2" class="mini">Sentinel view</button>`;
  const inUS = lon > -170 && lon < -64 && lat > 17 && lat < 72;
  const style = src => ({ version: 8, sources: { img: src === "naip" ? { type: "raster", tiles: [NAIP], tileSize: 256 } : { type: "raster", tiles: [S2], tileSize: 256, maxzoom: 14 } }, layers: [{ id: "img", type: "raster", source: "img" }] });
  if (!inspMap) { inspMap = new maplibregl.Map({ container: "inspMap", style: style(inUS ? "naip" : "s2"), center: [lon, lat], zoom: inUS ? 16 : 13, attributionControl: false, dragRotate: false }); inspMap.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right"); inspMap.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: inUS ? "USGS NAIP" : "Sentinel-2 cloudless by EOX" }), "bottom-right"); }
  else { inspMap.setStyle(style(inUS ? "naip" : "s2")); inspMap.jumpTo({ center: [lon, lat], zoom: inUS ? 16 : 13 }); }
  setTimeout(() => inspMap.resize(), 50);
  if (inspMap._mk) inspMap._mk.remove();
  const el = document.createElement("div"); el.className = "marker-pt via"; inspMap._mk = new maplibregl.Marker({ element: el }).setLngLat([lon, lat]).addTo(inspMap);
  $("#inspS2").onclick = () => { inspMap.setStyle(style("s2")); inspMap.jumpTo({ zoom: Math.min(inspMap.getZoom(), 14) }); };
  // what the graph knows
  $("#inspFacts").innerHTML = `<span class="muted">Looking up…</span>`; $("#inspWiki").innerHTML = ""; $("#inspWeb").innerHTML = "";
  let name = "", region = "";
  try {
    const r = await fetch(`/api/snap?lon=${lon}&lat=${lat}&max=400`); const j = await r.json();
    if (r.ok) { name = j.name; region = j.region; const cls = (SURF[j.cls] || ["Road"])[0]; $("#inspFacts").innerHTML = `<b>${esc(j.name || "Unnamed " + j.cls.replace("_", " "))}</b><div class="muted">${esc(j.cls.replace("_", " "))} · ${j.unpaved ? "unpaved" : "paved or unknown surface"}${j.oneway ? " · one way" : ""} · ${Math.round(j.distance)} m from the point · ${esc(j.region)}</div>`; }
    else $("#inspFacts").innerHTML = `<span class="muted">${esc(j.error)}</span>`;
  } catch { $("#inspFacts").innerHTML = `<span class="muted">The router is not reachable right now.</span>`; }
  // nearby Wikipedia
  try {
    const r = await fetch(`https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=${lat}|${lon}&gsradius=10000&gslimit=6&format=json&origin=*`);
    const j = await r.json(); const hits = (j.query && j.query.geosearch) || [];
    if (hits.length) $("#inspWiki").innerHTML = `<h4>Nearby on Wikipedia</h4>` + hits.map(h => `<a target="_blank" rel="noopener" href="https://en.wikipedia.org/?curid=${h.pageid}">${esc(h.title)} <span class="muted">· ${fmtDistShort(h.dist)}</span></a>`).join("");
  } catch {}
  // the web
  const q = [name, region].filter(Boolean).join(" ") || `${lat.toFixed(4)}, ${lon.toFixed(4)}`;
  const ext = `<a target="_blank" rel="noopener" href="https://duckduckgo.com/?q=${encodeURIComponent(q + " trail OR road")}">Search the web for “${esc(q)}”</a>`;
  try {
    const r = await fetch(`/api/web?q=${encodeURIComponent(q + (name ? " road trail" : ""))}`); const j = await r.json();
    if (r.ok && j.results && j.results.length) $("#inspWeb").innerHTML = `<h4>On the web</h4>` + j.results.map(w => `<a target="_blank" rel="noopener" href="${esc(w.url)}">${esc(w.title)}</a><p>${esc(w.description || "")}</p>`).join("") + ext;
    else $("#inspWeb").innerHTML = `<h4>On the web</h4>${ext}`;
  } catch { $("#inspWeb").innerHTML = `<h4>On the web</h4>${ext}`; }
}

// ---- search ---------------------------------------------------------------------------------------------------------
let searchT;
$("#q").addEventListener("input", () => { clearTimeout(searchT); searchT = setTimeout(search, 250); });
$("#q").addEventListener("keydown", e => { if (e.key === "Enter") { const f = $("#hits div"); if (f) f.click(); } if (e.key === "Escape") $("#hits").hidden = true; });
document.addEventListener("click", e => { if (!e.target.closest(".search")) $("#hits").hidden = true; });
async function search() {
  const q = $("#q").value.trim(); const box = $("#hits");
  if (q.length < 2) { box.hidden = true; return; }
  if ($("#q") !== document.activeElement && box.hidden) return;
  const c = map.getCenter(); const b = map.getBounds();
  box.innerHTML = `<div class="muted">Searching…</div>`; box.hidden = false;
  try {
    const r = await fetch(`/api/search?q=${encodeURIComponent(q)}&lon=${c.lng}&lat=${c.lat}&bbox=${b.getWest()},${b.getSouth()},${b.getEast()},${b.getNorth()}`); const j = await r.json();
    if ($("#q").value.trim() !== q) return;
    const hits = j.hits || [];
    box.innerHTML = j.error ? `<div class="muted">${esc(j.error)}</div>` : hits.length ? hits.map(h => `<div data-lon="${h.lon}" data-lat="${h.lat}">${esc(h.name)}<small>${esc([h.context, h.region].filter(Boolean).join(" · "))}${h.distance ? " · " + fmtDist(h.distance) : ""}</small></div>`).join("") : `<div class="muted">Nothing found under the map. Add the state to look elsewhere: “manhattan, mt”.</div>`;
    box.hidden = false;
    box.querySelectorAll("div[data-lon]").forEach(d => d.onclick = () => { map.flyTo({ center: [Number(d.dataset.lon), Number(d.dataset.lat)], zoom: 13 }); box.hidden = true; });
  } catch { box.innerHTML = `<div class="muted">Search is not reachable right now.</div>`; box.hidden = false; }
}

// ---- GPX in and out ------------------------------------------------------------------------------------------------
function gpxOut() {
  const line = lineCoords(); const name = $("#name").value.trim() || "Roadbook";
  const cues = S.cues.slice().sort((a, b) => a.at - b.at);
  let paved = 0, unpaved = 0; if (S.route) for (const r of S.route.surface) { if (r.unpaved || r.cls === "track" || r.cls === "path") unpaved += r.m; else paved += r.m; }
  const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<gpx version="1.1" creator="Roadbook Planner" xmlns="http://www.topografix.com/GPX/1/1">', `  <metadata><name>${esc(name)}</name><extensions><roadbook>${S.route ? `<mode>${S.route.mode}</mode><paved>${Math.round(paved)}</paved><unpaved>${Math.round(unpaved)}</unpaved>` : ""}<planner>1</planner></roadbook></extensions></metadata>`];
  for (const c of cues) { const type = c.turn === "DANGER" ? `danger:${c.level || 2}` : c.turn === "NOTE" ? "note" : `cue:${c.turn}`; out.push(`  <wpt lat="${c.lat.toFixed(6)}" lon="${c.lon.toFixed(6)}"><name>${esc(c.text || WORDS[c.turn])}</name>${c.text ? `<desc>${esc(c.text)}</desc>` : ""}<type>${type}</type></wpt>`); }
  out.push(`  <trk><name>${esc(name)}</name><trkseg>`);
  for (const p of line) out.push(`    <trkpt lat="${p[1].toFixed(6)}" lon="${p[0].toFixed(6)}"></trkpt>`);
  out.push("  </trkseg></trk>", "</gpx>", "");
  return out.join("\n");
}
$("#btnGpx").onclick = () => { const blob = new Blob([gpxOut()], { type: "application/gpx+xml" }); const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = ($("#name").value.trim() || "roadbook").replace(/[^\w .-]+/g, "_") + ".gpx"; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 5000); };
$("#btnPhone").onclick = async () => {
  const sh = $("#codeSheet"); for (const s of document.querySelectorAll(".sheet")) s.hidden = true; sh.hidden = false; $("#codeTitle").textContent = "Send to phone"; $("#codeBody").innerHTML = `<p class="muted">Parking the roadbook…</p>`;
  try {
    const r = await fetch(`/api/park?name=${encodeURIComponent($("#name").value.trim() || "Roadbook")}`, { method: "POST", body: gpxOut(), headers: { "Content-Type": "application/gpx+xml" } });
    const j = await r.json(); if (!r.ok) throw new Error(j.error);
    $("#codeBody").innerHTML = `<div class="code">${j.code.slice(0, 3)} ${j.code.slice(3)}</div><ol><li>In Roadbook on the phone: <b>≡ → TRACK → FETCH CODE</b>.</li><li>Type the code. The roadbook lands in your folder and opens for editing or following.</li></ol><p class="muted small">The code works for 24 hours, then the file is deleted. ${j.cues} cues · ${fmtDist(j.length)}.</p>`;
  } catch (e) { $("#codeBody").innerHTML = `<p class="muted">${esc(e.message)}</p><p class="muted small">You can still download the GPX and copy it to the phone.</p>`; }
};
$("#btnPublish").onclick = async () => {
  const sh = $("#codeSheet"); for (const s of document.querySelectorAll(".sheet")) s.hidden = true; sh.hidden = false; $("#codeTitle").textContent = "Publish to the library";
  $("#codeBody").innerHTML = `<p>Anyone can then find this roadbook on the map here and in the app, and ride it. The file keeps only the line and the cues — no times, no device, no name but the one below.</p><p class="muted small">Name: <b>${esc($("#name").value.trim() || "Roadbook")}</b></p><div class="sheet-actions"><span></span><button id="pubGo" class="cta">Publish</button></div>`;
  $("#pubGo").onclick = async () => {
    $("#codeBody").innerHTML = `<p class="muted">Publishing…</p>`;
    try {
      const r = await fetch(`/api/roadbooks?name=${encodeURIComponent($("#name").value.trim() || "Roadbook")}&source=planner`, { method: "POST", body: gpxOut(), headers: { "Content-Type": "application/gpx+xml" } });
      const j = await r.json(); if (!r.ok) throw new Error(j.error);
      $("#codeBody").innerHTML = `<p>Published. <a href="${esc(j.page)}">Link to it</a> · <a href="${esc(j.url)}">GPX</a></p><p class="muted small">If it should come down, email hello@hondogarage.com with the link.</p>`;
      if (S.library) loadLibrary();
    } catch (e) { $("#codeBody").innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
  };
};
$("#btnClear").onclick = () => { S.pts = []; S.cues = []; S.deletedAuto = []; S.track = null; S.route = null; abortInflight(); refreshRouteSources(); renderAll(); applyStyle(); };
$("#btnReverse").onclick = () => { if (S.track) { S.track.coords.reverse(); refreshRouteSources(); renderAll(); return; } S.pts.reverse(); afterPointsChange(); };
$("#file").onchange = async e => { const f = e.target.files[0]; if (!f) return; loadGpxText(await f.text(), f.name.replace(/\.gpx$/i, "")); e.target.value = ""; };

function parseGpx(text) {
  const pts = []; for (const m of text.matchAll(/<(?:trkpt|rtept)\s+([^>]*?)\/?>/g)) { const lat = Number(/lat="([^"]+)"/.exec(m[1])?.[1]), lon = Number(/lon="([^"]+)"/.exec(m[1])?.[1]); if (Number.isFinite(lat) && Number.isFinite(lon)) pts.push([lon, lat]); }
  const cues = []; for (const m of text.matchAll(/<wpt\s+([^>]*)>([\s\S]*?)<\/wpt>/g)) {
    const lat = Number(/lat="([^"]+)"/.exec(m[1])?.[1]), lon = Number(/lon="([^"]+)"/.exec(m[1])?.[1]); const type = (/<type>([\s\S]*?)<\/type>/.exec(m[2])?.[1] || "").trim().toLowerCase(); const words = unesc((/<desc>([\s\S]*?)<\/desc>/.exec(m[2])?.[1] || /<name>([\s\S]*?)<\/name>/.exec(m[2])?.[1] || "").trim());
    if (type.startsWith("cue:") && TURNS.includes(type.slice(4).toUpperCase())) cues.push({ lon, lat, turn: type.slice(4).toUpperCase(), level: 0, text: words, auto: false });
    else if (type.startsWith("danger")) cues.push({ lon, lat, turn: "DANGER", level: Math.min(3, Math.max(1, Number(type.split(":")[1] || 2) || 2)), text: words, auto: false });
    else if (type === "note" && words) cues.push({ lon, lat, turn: "NOTE", level: 0, text: words, auto: false });
  }
  const name = unesc((/<metadata>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || /<trk>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || [, ""])[1].trim());
  return { pts, cues, name };
}
function unesc(s) { return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&"); }
function loadGpxText(text, fallbackName) {
  const g = parseGpx(text);
  if (g.pts.length < 2) { status("That file has no track", true); return; }
  S.pts = []; S.route = null; abortInflight(); S.deletedAuto = [];
  S.track = { coords: g.pts, name: g.name || fallbackName }; S.cues = g.cues;
  $("#name").value = S.track.name || "Roadbook";
  refreshRouteSources(); renderAll(); applyStyle();
  const b = g.pts.reduce((bb, p) => [Math.min(bb[0], p[0]), Math.min(bb[1], p[1]), Math.max(bb[2], p[0]), Math.max(bb[3], p[1])], [180, 90, -180, -90]);
  map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 60, duration: 600 });
  status(`Loaded “${S.track.name}”: ${fmtDist(lineLength(g.pts))}, ${g.cues.length} cues. Click the line to add cues.`);
}

// ---- the library ---------------------------------------------------------------------------------------------------------
async function loadLibrary() {
  const b = map.getBounds();
  try {
    const r = await fetch(`/api/roadbooks?bbox=${b.getWest()},${b.getSouth()},${b.getEast()},${b.getNorth()}`); const j = await r.json();
    S.libraryData = { type: "FeatureCollection", features: (j.roadbooks || []).map(rb => ({ type: "Feature", properties: { id: rb.id, name: rb.name, length: rb.length }, geometry: { type: "LineString", coordinates: rb.line } })) };
    const src = map.getSource("library"); if (src) src.setData(S.libraryData); else applyStyle();
    if (j.note) status(j.note, false);
  } catch { status("The library is not reachable right now", true); }
}
async function openLibraryItem(id) {
  for (const s of document.querySelectorAll(".sheet")) s.hidden = true;
  const sh = $("#rbSheet"); sh.hidden = false; $("#rbName").textContent = "Roadbook"; $("#rbBody").innerHTML = `<p class="muted">Loading…</p>`;
  try {
    const r = await fetch(`/api/roadbooks/${id}`); const j = await r.json(); if (!r.ok) throw new Error(j.error);
    $("#rbName").textContent = j.name;
    const surf = j.paved != null && j.unpaved != null ? ` · ${Math.round(100 * j.unpaved / Math.max(1, j.paved + j.unpaved))}% unpaved` : "";
    $("#rbBody").innerHTML = `<div class="meta">${fmtDist(j.length)} · ${j.cues} cues${j.mode ? " · " + (MODES.find(m => m[0] === j.mode) || [, j.mode])[1] : ""}${surf} · ${new Date(j.created).toLocaleDateString()}</div><div class="actions"><button id="rbOpen" class="cta">Open in planner</button><a class="cta alt" href="${esc(j.url)}">Download GPX</a></div><p class="muted small">In the app: MAPS → BROWSE ROADBOOKS shows the same library on the phone.</p>`;
    $("#rbOpen").onclick = async () => { const t = await (await fetch(j.url)).text(); sh.hidden = true; loadGpxText(t, j.name); };
    const bb = j.bbox; if (!S.pts.length && !S.track) map.fitBounds([[bb[0], bb[1]], [bb[2], bb[3]]], { padding: 80, duration: 600 });
  } catch (e) { $("#rbBody").innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
}

// ---- the URL carries the plan ------------------------------------------------------------------------------------------
function saveHash() {
  const p = new URLSearchParams();
  if (S.pts.length) { p.set("m", S.mode); p.set("p", S.pts.map(x => `${x.lon.toFixed(5)},${x.lat.toFixed(5)}`).join(";")); }
  const rb = new URLSearchParams(location.hash.slice(1)).get("rb"); if (rb && !S.pts.length) p.set("rb", rb);
  const h = p.toString(); history.replaceState(null, "", h ? "#" + h : location.pathname);
}
function restoreHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  const m = p.get("m"); if (m && MODES.some(x => x[0] === m)) { S.mode = m; buildModes(); }
  const pts = (p.get("p") || "").split(";").filter(Boolean).map(s => s.split(",").map(Number)).filter(a => a.length === 2 && a.every(Number.isFinite)).map(a => ({ lon: a[0], lat: a[1] }));
  if (pts.length) { S.pts = pts; const b = pts.reduce((bb, q) => [Math.min(bb[0], q.lon), Math.min(bb[1], q.lat), Math.max(bb[2], q.lon), Math.max(bb[3], q.lat)], [180, 90, -180, -90]); map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 80, duration: 0 }); }
}

window.roadbookPlanner = { map, state: S, reroute };   // for the console and tests
init().catch(e => { console.error(e); status("The planner could not start: " + e.message, true); });
})();
