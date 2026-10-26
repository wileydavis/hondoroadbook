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
  elastic: null, lastGood: null, places: false, pendingNotes: [], cueSeq: 0, routeReq: 0,
  night: matchMedia("(prefers-color-scheme: dark)").matches, terrain: true, land: false, imagery: false, library: false,
  catalog: null, activePacks: new Set(), overview: new Map(), checking: new Set(), hasOverview: false, markers: [], cueMarkers: [], libraryData: null,
  inflight: null,
};

// ---- map ---------------------------------------------------------------------------------------------------
maplibregl.setWorkerUrl("/map/vendor/maplibre-gl-csp-worker.js");   // the CSP build: no blob workers, no eval
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);
let layersLight = null, layersDark = null;
// a browser without WebGL (hardware acceleration off, a locked-down machine, a headless review) gets a sentence, not a blank map
(() => { try { const c = document.createElement("canvas"); if (c.getContext("webgl2") || c.getContext("webgl")) return; } catch {} const d = document.createElement("div"); d.className = "nogl"; d.innerHTML = `<b>This browser can't draw the map.</b><br>It needs WebGL, which is off or unsupported here. Turn on hardware acceleration in the browser's settings, or open the planner in a current Chrome, Firefox, Safari or Edge. Nothing else about the page is wrong.`; document.body.appendChild(d); })();
const map = new maplibregl.Map({ container: "map", style: { version: 8, sources: {}, layers: [{ id: "background", type: "background", paint: { "background-color": "#f4efe6" } }] }, center: [-110.5, 45.5], zoom: 6, hash: false, attributionControl: false, maxPitch: 0, dragRotate: false, pitchWithRotate: false });
map.touchZoomRotate.disableRotation();
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
map.addControl(new maplibregl.ScaleControl({ unit: units === "mi" ? "imperial" : "metric" }), "bottom-right");
map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: "© OpenStreetMap contributors · Protomaps · AWS Terrain Tiles · BLM SMA · USGS NAIP · EOX Sentinel-2" }), "bottom-right");

async function init() {
  const [cat, ll, ld] = await Promise.all([fetch("/packs/catalog.json").then(r => r.json()), fetch("/map/layers_light.json").then(r => r.json()), fetch("/map/layers_dark.json").then(r => r.json())]);
  S.catalog = cat.packs || []; layersLight = ll; layersDark = ld;
  try { S.hasOverview = (await fetch("/tiles/overview/tiles.pmtiles", { method: "HEAD" })).ok; } catch {}
  buildModes();
  restoreHash();
  status("Loading the map…", false, true);
  const slow = setTimeout(() => status("Still loading — the first visit to a region is the slow one; after that it is cached", false, true), 8000);
  map.once("idle", () => { clearTimeout(slow); $("#status").hidden = true; });
  updatePacks(true);
  map.on("moveend", () => updatePacks(false));
  map.on("moveend", () => warm(false));
  warm(true);
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
const PACK_ZOOM = 7.5;   // below this the continent overview draws the map; from here the packs in view do
let packGen = 0;
async function updatePacks(force) {
  const b = map.getBounds(); const z = map.getZoom();
  const want = new Set();
  if (z >= PACK_ZOOM || !S.hasOverview) {
    for (const p of roadsPacks()) if (inter(p.bbox, b)) want.add(p.id);
    for (const p of terrainPacks()) if (inter(p.bbox, b)) want.add(p.id);
  }
  for (const id of [...want]) if (S.overview.get(id) === null) want.delete(id);   // known to be missing from the shelf
  // unchanged set — unless a wanted pack is still unknown (a transient failure last time, or a negative entry that expired): then ask again
  const unknownWanted = [...want].some(id => !S.overview.has(id) && !S.checking.has(id));
  const changed = force || want.size !== S.activePacks.size || [...want].some(id => !S.activePacks.has(id)) || unknownWanted;
  if (!changed) return;
  // the style goes up at once with every pack assumed present; the checks below run behind it and only
  // ever remove a pack (its file is not on the shelf yet) or add its overview roads file
  S.activePacks = want;
  applyStyle();
  const gen = ++packGen;
  const unknown = [...want].filter(id => !S.overview.has(id) && !S.checking.has(id));
  if (!unknown.length) return;
  await Promise.all(unknown.map(async id => {
    S.checking.add(id);
    const roads = roadsPacks().some(p => p.id === id);
    try {
      const r = await fetch(`/tiles/${id}/${roads ? "tiles" : "terrain"}.pmtiles`, { method: "HEAD" });
      if (r.status === 404 || r.status === 409) { S.overview.set(id, null); setTimeout(() => { S.overview.delete(id); updatePacks(false); }, 120000); return; }   // retried in two minutes
      if (!r.ok) return;                                                      // a transient failure: ask again next time
      if (!roads) { S.overview.set(id, false); return; }
      const o = await fetch(`/tiles/${id}/roads.pmtiles`, { method: "HEAD" }); S.overview.set(id, o.ok);
    } catch {} finally { S.checking.delete(id); }
  }));
  if (gen !== packGen) return;                                                 // a newer viewport took over
  const now = new Set([...S.activePacks].filter(id => S.overview.get(id) !== null));
  const grew = [...now].some(id => S.overview.get(id) === true && !map.getSource("o_" + id));
  if (now.size !== S.activePacks.size || grew) { S.activePacks = now; applyStyle(); }
}

function applyStyle() {
  const style = buildStyle();
  map.setStyle(style, { diff: true });
  roadLayerIds = null;
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
  if (S.hasOverview) sources.overview = { type: "vector", url: `pmtiles://${ORIGIN}/tiles/overview/tiles.pmtiles` };
  if (S.land) sources.land = { type: "raster", tiles: [LAND_TILES], tileSize: 256, maxzoom: 15, attribution: "BLM Surface Management Agency" };
  if (S.imagery) { sources.naip = { type: "raster", tiles: [NAIP], tileSize: 256, minzoom: 8, bounds: [-170, 17, -64, 72] }; sources.s2 = { type: "raster", tiles: [S2], tileSize: 256, maxzoom: 14 }; }
  sources.coverage = { type: "geojson", data: coverageGeo() };
  sources.library = { type: "geojson", data: S.library && S.libraryData ? S.libraryData : { type: "FeatureCollection", features: [] } };
  sources.route = { type: "geojson", data: routeGeo() };
  sources.unpaved = { type: "geojson", data: unpavedGeo() };
  sources.elastic = { type: "geojson", data: S.elastic || { type: "FeatureCollection", features: [] } };
  sources.insp = { type: "geojson", data: S.inspPlaces || { type: "FeatureCollection", features: [] } };

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
    // the continent overview carries the basemap below PACK_ZOOM; the packs carry it from there
    if (S.hasOverview && !fromOverview) {
      const clone = webSafe(JSON.parse(JSON.stringify(layer)));
      clone.id = layer.id + "_ov"; clone.source = "overview"; clone.maxzoom = Math.min(clone.maxzoom ?? 24, PACK_ZOOM);
      if (!(clone.minzoom >= PACK_ZOOM)) layers.push(clone);
    }
    for (const p of roads) {
      if (fromOverview && !S.overview.get(p.id)) continue;
      const clone = webSafe(JSON.parse(JSON.stringify(layer)));
      clone.id = layer.id + "_" + p.id; clone.source = (fromOverview ? "o_" : "p_") + p.id;
      if (S.hasOverview) { clone.minzoom = Math.max(clone.minzoom ?? 0, PACK_ZOOM); if (clone.maxzoom != null && clone.maxzoom <= PACK_ZOOM) continue; }
      if (S.imagery && clone.type === "fill" && layer.id === "buildings") clone.paint["fill-opacity"] = 0.25;
      layers.push(clone);
    }
  }
  // the first label layer: everything we draw goes under the labels
  let at = layers.findIndex(l => l.type === "symbol"); if (at < 0) at = layers.length;
  const mode = MODES.find(m => m[0] === S.mode); const color = S.track ? "#7c3aed" : mode[2];
  const ours = [
    { id: "coverage_line", type: "line", source: "coverage", maxzoom: 6, paint: { "line-color": night ? "#777" : "#555", "line-width": 1, "line-dasharray": [2, 2], "line-opacity": 0.35 } },
    { id: "library_line", type: "line", source: "library", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#7c3aed", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1.5, 10, 3, 14, 5], "line-opacity": 0.75 } },
    { id: "route_casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": night ? "#000" : "#fff", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 5, 12, 9, 16, 13], "line-opacity": 0.9 } },
    { id: "route_line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": color, "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 6, 16, 9] } },
    { id: "route_unpaved", type: "line", source: "unpaved", layout: { "line-cap": "butt", "line-join": "round" }, paint: { "line-color": ["case", ["get", "private"], "#dc2626", "#fff"], "line-width": ["interpolate", ["linear"], ["zoom"], 5, ["case", ["get", "private"], 3, 1], 12, ["case", ["get", "private"], 5, 2], 16, ["case", ["get", "private"], 8, 3]], "line-dasharray": [2, 2.5], "line-opacity": 0.9 } },
    { id: "route_hit", type: "line", source: "route", paint: { "line-color": "#000", "line-width": 22, "line-opacity": 0.001 } },
    // what Inspect found, at any zoom: the places around the inspected spot, coloured by what they are
    { id: "insp_c", type: "circle", source: "insp", paint: { "circle-radius": ["interpolate", ["linear"], ["zoom"], 6, 4, 12, 6, 16, 9], "circle-color": ["get", "color"], "circle-stroke-color": night ? "#000" : "#fff", "circle-stroke-width": 2, "circle-opacity": 0.95 } },
    { id: "insp_t", type: "symbol", source: "insp", layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Medium"], "text-size": 11, "text-offset": [0, 1.1], "text-anchor": "top", "text-optional": true, "text-max-width": 8 }, paint: { "text-color": night ? "#eee" : "#222", "text-halo-color": night ? "#000" : "#fff", "text-halo-width": 1.2 } },
    // the rubber band while dragging: straight lines from the neighbouring stops to the cursor
    { id: "elastic_casing", type: "line", source: "elastic", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": night ? "#000" : "#fff", "line-width": 7, "line-opacity": 0.8 } },
    { id: "elastic_line", type: "line", source: "elastic", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": color, "line-width": 4, "line-dasharray": [1.5, 1.5] } },
  ];
  if (S.places) {
    const kinds = Object.keys(POI_KIND).filter(k => POI_KIND[k] !== "town");
    const color = ["match", ["get", "kind"], ["fuel"], "#dc2626", ["camp_site", "camp_pitch", "caravan_site"], "#15803d", ["drinking_water", "spring", "water_well"], "#2563eb", ["hotel", "motel", "hostel", "guest_house", "chalet", "alpine_hut"], "#7c3aed", ["car_repair", "motorcycle", "motorcycle_repair"], "#525252", "#d97706"];
    for (const p of roads) {
      ours.push({ id: "poi_c_" + p.id, type: "circle", source: "p_" + p.id, "source-layer": "pois", minzoom: 12, filter: ["in", ["get", "kind"], ["literal", kinds]], paint: { "circle-radius": ["interpolate", ["linear"], ["zoom"], 12, 4, 16, 8], "circle-color": color, "circle-stroke-color": night ? "#000" : "#fff", "circle-stroke-width": 1.5, "circle-opacity": 0.85 } });
      layers.push({ id: "poi_t_" + p.id, type: "symbol", source: "p_" + p.id, "source-layer": "pois", minzoom: 13, filter: ["in", ["get", "kind"], ["literal", kinds]], layout: { "text-field": ["coalesce", ["get", "name"], ["get", "kind"]], "text-font": ["Noto Sans Medium"], "text-size": 11, "text-offset": [0, 1.1], "text-anchor": "top", "text-optional": true, "text-max-width": 8 }, paint: { "text-color": color, "text-halo-color": night ? "#000" : "#fff", "text-halo-width": 1.2 } });
    }
  }
  layers.splice(at, 0, ...ours);
  layers.push({ id: "coverage_label", type: "symbol", source: "coverage", maxzoom: 5, layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Medium"], "text-size": 11, "text-transform": "uppercase", "text-letter-spacing": 0.1 }, paint: { "text-color": night ? "#aaa" : "#666", "text-halo-color": night ? "#000" : "#fff", "text-halo-width": 1 } });
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
  return { type: "FeatureCollection", features: S.route.surface.filter(r => r.private || r.unpaved || r.cls === "track" || r.cls === "path").map(r => ({ type: "Feature", properties: { private: !!r.private }, geometry: { type: "LineString", coordinates: S.route.coords.slice(r.i, r.j + 1) } })) };
}
function refreshRouteSources() {
  const r = map.getSource("route"); if (r) r.setData(routeGeo());
  const u = map.getSource("unpaved"); if (u) u.setData(unpavedGeo());
  const n = map.getSource("insp"); if (n) n.setData(S.inspPlaces || { type: "FeatureCollection", features: [] });
}
const CAT_COLOR = { fuel: "#dc2626", food: "#d97706", supplies: "#b45309", camping: "#15803d", water: "#2563eb", lodging: "#7c3aed", repair: "#525252", town: "#111" };
const CAT_LABEL = { fuel: "Fuel", food: "Food", supplies: "Supplies", camping: "Camp", water: "Water", lodging: "Lodging", repair: "Repair", town: "Town" };
/** Put what Inspect found on the main map (any zoom) so the rider sees where it all sits; cleared when the sheet closes. */
function showInspPlaces(near) {
  const features = [];
  for (const cat of Object.keys(CAT_COLOR).filter(c => c !== "town")) for (const i of near[cat] || []) features.push({ type: "Feature", properties: { name: i.name || i.kind || "", kind: i.kind || cat, cat, color: CAT_COLOR[cat], label: CAT_LABEL[cat] }, geometry: { type: "Point", coordinates: [i.lon, i.lat] } });
  S.inspPlaces = { type: "FeatureCollection", features };
  const src = map.getSource("insp"); if (src) src.setData(S.inspPlaces);
}
function clearInspPlaces() { S.inspPlaces = null; const src = map.getSource("insp"); if (src) src.setData({ type: "FeatureCollection", features: [] }); }

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
$("#lyPlaces").onchange = e => { S.places = e.target.checked; applyStyle(); };
$("#lyLibrary").onchange = e => { S.library = e.target.checked; if (S.library) loadLibrary(); else { S.libraryData = null; applyStyle(); } };
$("#btnSide").onclick = () => { $("#side").classList.add("hidden"); $("#btnSideShow").hidden = false; };
$("#btnSideShow").onclick = () => { $("#side").classList.remove("hidden"); $("#btnSideShow").hidden = true; };
document.querySelectorAll("[data-close]").forEach(b => b.onclick = () => { const sh = b.closest(".sheet"); sh.hidden = true; if (sh.id === "inspect") { inspGen++; clearInspPlaces(); } });

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
let warnAt = 0;
function status(msg, err, sticky) { const s = $("#status"); s.textContent = msg; s.className = "status" + (err ? " err" : ""); s.hidden = false; clearTimeout(statusT); if (err) warnAt = Date.now(); if (!sticky) statusT = setTimeout(() => s.hidden = true, err ? 8000 : 3000); }
/** Hides "Routing…" but never a warning that was just raised (dropped cues, private roads). */
function clearRoutingStatus() { if (Date.now() - warnAt > 500) $("#status").hidden = true; }
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
    el.textContent = i === 0 ? "START" : last ? "END" : viaLetter(i - 1);
    el.title = i === 0 ? "Start — drag to move, click to delete" : last ? "End — drag to move, click to delete" : `Stop ${viaLetter(i - 1)} — drag to move, click to delete`;
    const mk = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([p.lon, p.lat]).addTo(map);
    let moved = false, snapped = null;
    mk.on("dragstart", () => { moved = true; abortInflight(); fadeRoute(true); el.classList.add("dragging"); });
    mk.on("drag", () => {
      const ll = mk.getLngLat(); const sn = snapOnScreen(map.project(ll)); snapped = sn;
      const at = sn ? [sn.lon, sn.lat] : [ll.lng, ll.lat];
      el.classList.toggle("snapped", !!sn); el.title = sn ? snapLabel(sn) : "";
      const prev = S.pts[i - 1], next = S.pts[i + 1];
      setElastic([prev && [prev.lon, prev.lat], at, next && [next.lon, next.lat]].filter(Boolean));
    });
    mk.on("dragend", () => {
      const ll = mk.getLngLat(); let at = snapped || { lon: ll.lng, lat: ll.lat };
      // the finish dropped onto the start closes a loop: the same point exactly, so the router ends where it began
      if (last && i > 1) { const a = S.pts[0]; const d = map.project([a.lon, a.lat]); const b = map.project([at.lon, at.lat]); if (Math.hypot(d.x - b.x, d.y - b.y) < 18) { at = { lon: a.lon, lat: a.lat }; status("Loop closed at the start"); } }
      S.pts[i] = { lon: at.lon, lat: at.lat }; mk.setLngLat([at.lon, at.lat]); el.classList.remove("dragging", "snapped");
      reroute();
    });
    el.addEventListener("click", e => { e.stopPropagation(); if (moved) { moved = false; return; } S.pts.splice(i, 1); afterPointsChange(); });
    S.markers.push(mk);
  });
}

/** Stops are lettered A, B, C… (then AA, AB) so they read apart from the cue markers; the ends are words. */
function viaLetter(n) { let s = ""; n = Math.max(0, n); do { s = String.fromCharCode(65 + n % 26) + s; n = Math.floor(n / 26) - 1; } while (n >= 0); return s; }
function afterPointsChange() {
  S.routeReq++;
  S.track = null;
  renderPoints();
  if (S.pts.length >= 2) reroute();
  else { S.route = null; abortInflight(); refreshRouteSources(); setElastic(null); renderCues(); renderStats(); }
  saveHash();
}

// ---- routing -----------------------------------------------------------------------------------------------------
function abortInflight() { if (S.inflight) { S.inflight.abort(); S.inflight = null; } progress(false); }
async function callRoute(pts, signal) {
  const q = pts.map(p => `${p.lon.toFixed(6)},${p.lat.toFixed(6)}`).join(";");
  const r = await fetch(`/api/route?pts=${encodeURIComponent(q)}&mode=${S.mode}`, { signal });
  const j = await r.json();
  if (!r.ok) throw Object.assign(new Error(j.error || `router ${r.status}`), { leg: j.leg });
  return j;
}

// ---- dragging: a rubber band drawn locally, a snap to the roads on screen, one route on release ----------------------
function setElastic(coords) {
  S.elastic = coords ? { type: "FeatureCollection", features: [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } }] } : null;
  const src = map.getSource("elastic"); if (src) src.setData(S.elastic || { type: "FeatureCollection", features: [] });
}
function fadeRoute(on) {
  for (const [id, v] of [["route_line", 1], ["route_casing", 0.9], ["route_unpaved", 0.9]]) if (map.getLayer(id)) map.setPaintProperty(id, "line-opacity", on ? v * 0.3 : v);
}
let roadLayerIds = null;
/** The nearest road or trail drawn on screen within `px` pixels of a point: no round trip, the same data the router uses. */
function snapOnScreen(point, px = 14) {
  if (!roadLayerIds) roadLayerIds = map.getStyle().layers.filter(l => l.type === "line" && (l["source-layer"] === "roads") && /^fold_/.test(l.id)).map(l => l.id);
  const ids = roadLayerIds.filter(id => map.getLayer(id)); if (!ids.length) return null;
  let feats;
  try { feats = map.queryRenderedFeatures([[point.x - px, point.y - px], [point.x + px, point.y + px]], { layers: ids }); } catch { return null; }
  let best = null, bd = px + 1;
  for (const f of feats) {
    const g = f.geometry; const lines = g.type === "LineString" ? [g.coordinates] : g.type === "MultiLineString" ? g.coordinates : [];
    for (const line of lines) {
      let prev = map.project(line[0]);
      for (let i = 1; i < line.length; i++) {
        const cur = map.project(line[i]);
        const dx = cur.x - prev.x, dy = cur.y - prev.y; const l2 = dx * dx + dy * dy;
        let t = l2 ? ((point.x - prev.x) * dx + (point.y - prev.y) * dy) / l2 : 0; t = Math.max(0, Math.min(1, t));
        const x = prev.x + t * dx, y = prev.y + t * dy; const d = Math.hypot(point.x - x, point.y - y);
        if (d < bd) { bd = d; best = { x, y, name: f.properties && (f.properties.name || f.properties.ref) || "", kind: f.properties && f.properties.kind || "" }; }
        prev = cur;
      }
    }
  }
  if (!best) return null;
  const ll = map.unproject([best.x, best.y]);
  return { lon: ll.lng, lat: ll.lat, name: best.name, kind: best.kind };
}
const KIND_WORDS = { path: "trail", track: "two-track", minor: "road", service: "road", highway: "highway", major: "road", medium: "road", rail: "rail" };
function snapLabel(sn) { return sn.name || KIND_WORDS[sn.kind] || sn.kind || "road"; }
function ghostMarker(lngLat) {
  const el = document.createElement("div"); el.className = "marker-pt dot ghost"; el.style.pointerEvents = "none";
  const tag = document.createElement("div"); tag.className = "ghost-tag"; el.appendChild(tag);
  const mk = new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map);
  mk.tag = (t) => { tag.textContent = t; tag.hidden = !t; el.classList.toggle("snapped", !!t); };
  return mk;
}

// ---- waking the router -------------------------------------------------------------------------------------------
// The first route of the day waits for the container to wake and pull a state's graph. Two things make that
// bearable: the page warms the router for the map in view as soon as it opens (and as the map moves), and a
// slow route shows what it is waiting on — waking, loading a named state, routing — with a bar and a clock.
let warmT = 0, warmTimer = null, warmState = null;
async function warm(immediate) {
  clearTimeout(warmTimer);
  const go = async () => {
    warmT = Date.now();
    const b = map.getBounds();
    try { const r = await fetch(`/api/warm?bbox=${b.getWest()},${b.getSouth()},${b.getEast()},${b.getNorth()}`); warmState = r.ok ? await r.json() : null; } catch { warmState = null; }
  };
  if (immediate || Date.now() - warmT > 60000) go(); else warmTimer = setTimeout(go, 2500);
}
function progress(show, text, pct) {
  const el = $("#progress"); if (!show) { el.hidden = true; return; }
  el.hidden = false; $("#progressText").textContent = text; const bar = $("#progressBar");
  if (pct == null) bar.classList.add("indeterminate"); else { bar.classList.remove("indeterminate"); bar.style.width = Math.round(pct * 100) + "%"; }
}
/** While a route request is in flight, poll the router's state and narrate it. */
function narrate(ac, pts) {
  const t0 = Date.now(); let stage = "";
  const b = pts.reduce((bb, p) => [Math.min(bb[0], p.lon), Math.min(bb[1], p.lat), Math.max(bb[2], p.lon), Math.max(bb[3], p.lat)], [180, 90, -180, -90]);
  const bbox = `${b[0] - 0.3},${b[1] - 0.3},${b[2] + 0.3},${b[3] + 0.3}`;
  const tick = async () => {
    if (ac.signal.aborted || S.inflight !== ac) { progress(false); return; }
    const secs = Math.round((Date.now() - t0) / 1000);
    if (secs < 2) { setTimeout(tick, 500); return; }
    let text;
    try {
      const r = await fetch(`/api/warm?bbox=${bbox}`, { signal: AbortSignal.timeout(4000) });
      const j = r.ok ? await r.json() : null;
      if (!j) text = "Waking the router — the first route after a quiet spell takes up to half a minute";
      else if (j.loading && j.loading.length) text = `Loading ${j.loading.join(", ")} roads — once per state, then instant`;
      else text = "Routing…";
    } catch { text = "Waking the router — the first route after a quiet spell takes up to half a minute"; }
    if (S.inflight !== ac) { progress(false); return; }
    stage = text; progress(true, `${text} · ${secs}s`, null);
    setTimeout(tick, 1500);
  };
  setTimeout(tick, 1500);
}

async function reroute() {
  if (S.pts.length < 2) return;
  abortInflight(); const ac = new AbortController(); S.inflight = ac;
  status("Routing…"); narrate(ac, S.pts);
  try {
    const j = await callRoute(S.pts, ac.signal);
    if (S.inflight !== ac) return; S.inflight = null;
    setRoute(j, false);
    clearRoutingStatus(); progress(false);
  } catch (e) {
    if (e.name === "AbortError") return;
    S.inflight = null; setElastic(null); fadeRoute(false); progress(false);
    // the points go back to the last route that worked, so what is shown and what is exported always agree
    if (S.pendingNotes.length) { S.pendingNotes = []; status("The stop could not be routed, so its note was not added", true); }
    if (S.lastGood) { S.pts = S.lastGood.pts.map(p => ({ ...p })); S.mode = S.lastGood.mode; buildModes(); renderPoints(); saveHash(); status(e.message + " — kept the previous route", true); }
    else { S.pts = []; S.route = null; refreshRouteSources(); renderAll(); status(e.message, true); }
  }
}

/** Takes a router answer. Auto cues are replaced; the user's own cues stay if they still touch the line. */
function setRoute(j, preview) {
  S.route = j; S.track = null;
  if (!preview && editing && editing.id != null) { $("#cueEd").hidden = true; editing = null; }
  refreshRouteSources(); setElastic(null); fadeRoute(false);
  if (preview) return;
  // the points sit where the router put them: on the road or trail it found, not in the sagebrush beside it
  if (j.snapped && j.snapped.length === S.pts.length) S.pts = j.snapped.map(p => ({ lon: p.lon, lat: p.lat }));
  S.lastGood = { pts: S.pts.map(p => ({ ...p })), mode: S.mode };
  // notes waiting for their stop to be snapped onto a road
  // notes waiting for their stop: only from the request that added the stop, and only onto the stop that landed where it was meant to
  for (const n of S.pendingNotes.splice(0)) {
    if (n.req !== S.routeReq) continue;
    const p = S.pts[n.at]; if (p && dist([p.lon, p.lat], [n.lon, n.lat]) < 1000) S.cues.push({ lon: p.lon, lat: p.lat, turn: "NOTE", level: 0, text: n.text, auto: false });
    else status(`The note “${n.text}” was not added: its stop did not land where it was meant to`, true);
  }
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
  $("#btnLoop").textContent = isLoop() ? "open loop" : "loop";
  $("#btnLoop").title = isLoop() ? "Make the last stop the finish again" : "Finish where you started";
  $("#empty").hidden = have; $("#stats").hidden = !have; $("#cuesSec").hidden = !have; $("#actions").hidden = !have;
  if (!have) return;
  const len = S.route ? S.route.length : lineLength(c);
  $("#dist").textContent = fmtDist(len);
  $("#time").textContent = S.route ? `about ${fmtTime(S.route.time)} · ${MODES.find(m => m[0] === S.route.mode)[1]}` : (S.track ? "loaded track" : "");
  const bar = $("#surface"), key = $("#surfaceKey"); bar.innerHTML = ""; key.innerHTML = "";
  if (S.route) {
    const groups = new Map();
    for (const r of S.route.surface) { const k = SURF[r.cls] || ["Other", "#999"]; const label = r.private ? "Posted private" : r.unpaved && k[0] !== "Two-track" && k[0] !== "Trail" ? "Unpaved road" : k[0]; const color = label === "Posted private" ? "#dc2626" : label === "Unpaved road" ? "#b45309" : k[1]; const g = groups.get(label) || { m: 0, color }; g.m += r.m; groups.set(label, g); }
    const order = ["Posted private", "Highway", "Paved", "Minor", "Unpaved road", "Two-track", "Trail", "Path", "Other"];
    const prv = groups.get("Posted private"); if (prv) status(`${fmtDist(prv.m)} of this route is on roads OpenStreetMap marks private — ask, or drag the line off them`, true);
    for (const label of order) { const g = groups.get(label); if (!g) continue; const sp = document.createElement("span"); sp.style.width = (100 * g.m / len) + "%"; sp.style.background = g.color; sp.title = `${label}: ${fmtDist(g.m)}`; bar.appendChild(sp); const k = document.createElement("span"); k.innerHTML = `<i style="background:${g.color}"></i>${label} ${fmtDist(g.m)}`; key.appendChild(k); }
    const legs = $("#legs"); legs.innerHTML = S.route.legs.length > 1 ? S.route.legs.map((l, i) => `${i === 0 ? "Start" : viaLetter(i - 1)} → ${i === S.route.legs.length - 1 ? "End" : viaLetter(i)}: ${fmtDist(l.length)}, ${fmtTime(l.time)}`).join(" · ") : "";
  } else $("#legs").innerHTML = "";
}
function lineLength(c) { let m = 0; for (let i = 1; i < c.length; i++) m += dist(c[i - 1], c[i]); return m; }
function dist(a, b) { const R = 6371000, r = Math.PI / 180; const dLat = (b[1] - a[1]) * r, dLon = (b[0] - a[0]) * r; const x = dLon * Math.cos((a[1] + b[1]) / 2 * r); return R * Math.hypot(dLat, x); }
function nearLine(c, line) { let best = Infinity; for (let i = 1; i < line.length; i++) { const d = segDist([c.lon, c.lat], line[i - 1], line[i]); if (d < best) best = d; } return best; }
function segDist(p, a, b) { const k = Math.cos(p[1] * Math.PI / 180); const P = [p[0] * k, p[1]], A = [a[0] * k, a[1]], B = [b[0] * k, b[1]]; const dx = B[0] - A[0], dy = B[1] - A[1]; const l2 = dx * dx + dy * dy; let t = l2 ? ((P[0] - A[0]) * dx + (P[1] - A[1]) * dy) / l2 : 0; t = Math.max(0, Math.min(1, t)); return Math.hypot(P[0] - (A[0] + t * dx), P[1] - (A[1] + t * dy)) * 111320; }
/** Nearest vertex index and distance along the line for a point. */
/** Nearest point on the line: segment index, distance along, distance away, and the projected coordinate. A cell index per line keeps 5,000 cues on 100,000 points cheap. */
function alongLine(line, p) {
  const idx = lineIndex(line);
  let best = Infinity, bi = 1;
  const cell = idx.cell; const cx = Math.floor(p[0] / cell), cy = Math.floor(p[1] / cell);
  const test = i => { const d = segDist(p, line[i - 1], line[i]); if (d < best) { best = d; bi = i; } };
  // every segment in ring R+1 or beyond is at least (R cells + the distance to this cell's nearest edge) away;
  // only once the best found is within that bound can the search stop (a point near a cell edge needs the neighbour)
  const mPerDeg = 111320 * Math.cos(p[1] * Math.PI / 180);
  const edge = Math.min(p[0] - cx * cell, (cx + 1) * cell - p[0], p[1] - cy * cell, (cy + 1) * cell - p[1]) * mPerDeg;
  const bound = R => R * cell * mPerDeg + edge;
  let R = 0, settled = false;
  for (; R <= 3; R++) { for (let x = cx - R; x <= cx + R; x++) for (let y = cy - R; y <= cy + R; y++) { if (R && Math.abs(x - cx) !== R && Math.abs(y - cy) !== R) continue; const l = idx.cells.get(x * 100003 + y); if (l) for (const i of l) test(i); }
  if (best <= bound(R)) { settled = true; break; } }     // rings beyond R start a full R cells past this cell's edge
  for (const i of idx.longs) test(i);
  if (!settled) for (let i = 1; i < line.length; i++) test(i);      // nothing proven nearby: the exact answer, however far
  const cum = cumulative(line);
  const a = line[bi - 1], b = line[bi]; const segLen = dist(a, b); let bt = 0;
  if (segLen > 0) { const k = Math.cos(p[1] * Math.PI / 180); const dx = (b[0] - a[0]) * k, dy = b[1] - a[1]; bt = Math.max(0, Math.min(1, (((p[0] - a[0]) * k) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy))); }
  return { index: bi, at: cum[bi - 1] + segLen * bt, d: best, point: [a[0] + (b[0] - a[0]) * bt, a[1] + (b[1] - a[1]) * bt] };
}
let idxCache = { line: null, idx: null };
function lineIndex(line) {
  if (idxCache.line === line) return idxCache.idx;
  const cell = 0.01, cells = new Map(), longs = [];
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1], b = line[i];
    const x0 = Math.floor(Math.min(a[0], b[0]) / cell), x1 = Math.floor(Math.max(a[0], b[0]) / cell), y0 = Math.floor(Math.min(a[1], b[1]) / cell), y1 = Math.floor(Math.max(a[1], b[1]) / cell);
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) { longs.push(i); continue; }     // a long straight stretch is checked for every point instead
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) { const k = x * 100003 + y; let l = cells.get(k); if (!l) cells.set(k, l = []); l.push(i); }
  }
  idxCache = { line, idx: { cell, cells, longs } }; return idxCache.idx;
}
let cumCache = { line: null, cum: null };
function cumulative(line) { if (cumCache.line === line) return cumCache.cum; const cum = new Float64Array(line.length); for (let i = 1; i < line.length; i++) cum[i] = cum[i - 1] + dist(line[i - 1], line[i]); cumCache = { line, cum }; return cum; }

// ---- cues --------------------------------------------------------------------------------------------------------
function renderCues() {
  for (const m of S.cueMarkers) m.remove(); S.cueMarkers = [];
  const ol = $("#cues"); ol.innerHTML = "";
  const line = lineCoords(); if (!line) { $("#cueCount").textContent = ""; return; }
  for (const c of S.cues) { if (!c.id) c.id = ++S.cueSeq; c.at = alongLine(line, [c.lon, c.lat]).at; }
  S.cues.sort((a, b) => a.at - b.at);
  $("#cueCount").textContent = S.cues.length ? `(${S.cues.length})` : "";
  S.cues.forEach((c, idx) => {
    const li = document.createElement("li"); li.className = c.auto ? "auto" : "user";
    const g = arrowSvg(c.turn, c.level, c.exit);
    li.innerHTML = `<span class="g">${g}</span><span><b>${esc(WORDS[c.turn] || c.turn)}</b>${c.text ? `<span class="words">${esc(c.text)}${c.link ? ` <a href="${esc(c.link)}" target="_blank" rel="noopener" title="${esc(c.link)}">↗</a>` : ""}</span>` : ""}</span><span class="at">${fmtDistShort(c.at)}</span>`;
    li.onclick = () => { map.flyTo({ center: [c.lon, c.lat], zoom: Math.max(map.getZoom(), 14) }); editCue(c.id); };
    ol.appendChild(li);
    const el = document.createElement("div"); el.className = "marker-cue" + (c.turn === "DANGER" ? " danger" : c.turn === "NOTE" ? " note" : "") + (c.auto ? "" : " user"); el.innerHTML = g; el.title = (WORDS[c.turn] || c.turn) + (c.text ? ": " + c.text : "");
    el.addEventListener("click", e => { e.stopPropagation(); editCue(c.id); });
    S.cueMarkers.push(new maplibregl.Marker({ element: el, anchor: "center" }).setLngLat([c.lon, c.lat]).addTo(map));
  });
}

let editing = null;   // {id} for an existing cue (looked up at save time: the list can be re-sorted or regenerated meanwhile) or {lon,lat} for a new one
const cueById = id => S.cues.find(c => c.id === id);
const grid = $("#turnGrid");
for (const t of TURNS) { const b = document.createElement("button"); b.dataset.turn = t; b.innerHTML = arrowSvg(t); b.title = WORDS[t]; grid.appendChild(b); }
document.querySelectorAll("#cueEd .dangerRow [data-turn]").forEach(b => { const t = b.dataset.turn; b.innerHTML = arrowSvg(t, Number(b.dataset.level || 0)) + (t === "NOTE" ? "<span>Note</span>" : ""); b.title = t === "NOTE" ? "A note, read in your words" : `Danger ${b.dataset.level}`; });
document.querySelectorAll("#cueEd [data-turn]").forEach(b => b.onclick = () => { document.querySelectorAll("#cueEd [data-turn]").forEach(x => x.classList.remove("on")); b.classList.add("on"); if (b.dataset.turn === "NOTE") $("#cueText").focus(); });
function editCue(id) {
  const c = cueById(id); if (!c) return;
  editing = { id };
  openCueEditor(c.turn, c.level, c.text, "Edit cue");
}
function newCue(lon, lat) { editing = { lon, lat }; openCueEditor("STRAIGHT", 0, "", "New cue"); }
function openCueEditor(turn, level, text, title) {
  $("#cueEdTitle").textContent = title; $("#cueText").value = text || "";
  document.querySelectorAll("#cueEd [data-turn]").forEach(x => x.classList.toggle("on", x.dataset.turn === turn && (turn !== "DANGER" || Number(x.dataset.level) === (level || 2))));
  $("#cueDelete").hidden = editing.id == null;
  for (const s of document.querySelectorAll(".sheet")) s.hidden = true;
  $("#cueEd").hidden = false;
}
$("#cueSave").onclick = () => {
  const on = $("#cueEd [data-turn].on"); if (!on) return;
  const turn = on.dataset.turn, level = turn === "DANGER" ? Number(on.dataset.level) : 0, text = $("#cueText").value.trim();
  if (turn === "NOTE" && !text) { $("#cueText").focus(); return; }
  if (editing.id != null) {
    const c = cueById(editing.id); if (!c) { $("#cueEd").hidden = true; status("That cue is no longer on the route", true); return; }
    if (c.auto) S.deletedAuto.push({ lon: c.lon, lat: c.lat });
    Object.assign(c, { turn, level, text, auto: false, climb: false });   // yours now: no generator may replace it; c.link stays
  } else S.cues.push({ lon: editing.lon, lat: editing.lat, turn, level, text, auto: false });
  $("#cueEd").hidden = true; renderCues();
};
$("#cueDelete").onclick = () => { if (editing.id == null) return; const i = S.cues.findIndex(c => c.id === editing.id); $("#cueEd").hidden = true; if (i < 0) { status("That cue is no longer on the route", true); return; } const c = S.cues[i]; if (c.auto) S.deletedAuto.push({ lon: c.lon, lat: c.lat }); S.cues.splice(i, 1); renderCues(); };
$("#btnAutoCues").onclick = () => { S.deletedAuto = []; S.cues = S.cues.filter(c => false); if (S.route) setRoute(S.route, false); else renderCues(); };

// ---- map interaction: click to add, drag the line for a stop, click the line for the menu ------------------------------
function bindMap() {
  let drag = null;
  map.on("click", e => {
    if ($("#ctx").hidden === false) { $("#ctx").hidden = true; return; }
    if (e.originalEvent.target.closest && e.originalEvent.target.closest(".marker-pt, .marker-cue")) return;
    if (S.inspPlaces && map.getLayer("insp_c")) {
      const f = map.queryRenderedFeatures([[e.point.x - 10, e.point.y - 10], [e.point.x + 10, e.point.y + 10]], { layers: ["insp_c"] })[0];
      if (f) { pointPopup(f.geometry.coordinates.slice(), f.properties.name || f.properties.kind, f.properties.kind, f.properties.label); return; }
    }
    if (S.places) {
      const poiLayers = map.getStyle().layers.filter(l => l.id.startsWith("poi_c_")).map(l => l.id);
      const poi = poiLayers.length ? map.queryRenderedFeatures([[e.point.x - 10, e.point.y - 10], [e.point.x + 10, e.point.y + 10]], { layers: poiLayers })[0] : null;
      if (poi) { placePopup(poi, e.lngLat); return; }
    }
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
    drag = { leg, insertAt: leg + 1, start: point, ghost: ghostMarker(lngLat), moved: false, pts: S.pts.slice(), snapped: null };
    map.dragPan.disable(); map.getCanvas().style.cursor = "grabbing";
    return true;
  };
  const move = (point, lngLat) => {
    if (!drag) return;
    if (!drag.moved && Math.hypot(point.x - drag.start.x, point.y - drag.start.y) < 4) return;
    if (!drag.moved) { abortInflight(); fadeRoute(true); }
    drag.moved = true;
    const sn = snapOnScreen(point); drag.snapped = sn;
    const at = sn ? [sn.lon, sn.lat] : [lngLat.lng, lngLat.lat];
    drag.ghost.setLngLat(at); drag.ghost.tag(sn ? snapLabel(sn) : "");
    const prev = drag.pts[drag.insertAt - 1], next = drag.pts[drag.insertAt];
    setElastic([prev && [prev.lon, prev.lat], at, next && [next.lon, next.lat]].filter(Boolean));
  };
  const end = (lngLat) => {
    if (!drag) return;
    map.dragPan.enable(); map.getCanvas().style.cursor = ""; drag.ghost.remove();
    if (drag.moved) { const at = drag.snapped || { lon: lngLat.lng, lat: lngLat.lat }; S.pts = drag.pts.slice(); S.pts.splice(drag.insertAt, 0, { lon: at.lon, lat: at.lat }); afterPointsChange(); }
    else { S.pts = drag.pts; }
    drag = null;
  };
  // a press on a marker is the marker's drag (MapLibre handles it), never a grab of the line beneath it
  const onMarker = ev => ev.target && ev.target.closest && ev.target.closest(".marker-pt, .marker-cue");
  map.on("mousedown", e => { if (e.originalEvent.button === 0 && !onMarker(e.originalEvent)) start(e.point, e.lngLat); });
  map.on("mousemove", e => move(e.point, e.lngLat));
  map.on("mouseup", e => end(e.lngLat));
  map.on("touchstart", e => { if (e.points.length === 1 && !onMarker(e.originalEvent) && start(e.point, e.lngLat)) e.preventDefault(); });
  map.on("touchmove", e => { if (drag) { e.preventDefault(); move(e.point, e.lngLat); } });
  map.on("touchend", e => end(e.lngLat));
  map.on("touchcancel", e => end(e.lngLat));
  map.on("contextmenu", e => { if (lineCoords()) showCtx(e); });
}

/** Route through a place and leave a note there: the stop goes in at the right leg, the note is placed once the router has snapped the stop onto a road. */
function addStopAt(lon, lat, noteText) {
  if (S.track) { status("This is a loaded track; clear it to route through places", true); return; }
  let at;
  if (S.route) { const { index } = alongLine(S.route.coords, [lon, lat]); let leg = S.route.legs.findIndex(l => index > l.start && index <= l.end); if (leg < 0) leg = S.route.legs.length - 1; at = leg + 1; S.pts.splice(at, 0, { lon, lat }); }
  else { S.pts.push({ lon, lat }); at = S.pts.length - 1; }
  if (noteText) S.pendingNotes.push({ at, lon, lat, text: noteText, req: S.routeReq + 1 });   // the request afterPointsChange is about to make
  afterPointsChange();
  if (S.pts.length < 2) status("Added as the start; click the map for the finish");
}
let placePop = null;
function parseCoords(str) {
  const t = String(str).trim().replace(/[()\[\]]/g, "");
  let m = /^(-?\d{1,2}(?:\.\d+)?)\s*°?\s*([NS])?\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([EW])?$/i.exec(t);
  if (m) { let lat = Number(m[1]), lon = Number(m[3]); if (/s/i.test(m[2] || "")) lat = -Math.abs(lat); if (/w/i.test(m[4] || "")) lon = -Math.abs(lon); if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return [lon, lat]; }
  // 48°04'24.0"N 123°39'44.1"W
  m = /^(\d{1,2})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)?["″]?\s*([NS])\s*,?\s*(\d{1,3})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)?["″]?\s*([EW])$/i.exec(t);
  if (m) { const lat = (Number(m[1]) + Number(m[2]) / 60 + Number(m[3] || 0) / 3600) * (/s/i.test(m[4]) ? -1 : 1); const lon = (Number(m[5]) + Number(m[6]) / 60 + Number(m[7] || 0) / 3600) * (/w/i.test(m[8]) ? -1 : 1); return [lon, lat]; }
  return null;
}
function goToCoords(c) {
  map.flyTo({ center: c, zoom: Math.max(map.getZoom(), 14) });
  map.once("moveend", () => pointPopup(c, `${c[1].toFixed(5)}, ${c[0].toFixed(5)}`, "pasted coordinate", "Point"));
}
// paste anywhere that is not a text box: coordinates from Google Maps or a forum post land on the map
document.addEventListener("paste", e => {
  const tag = (e.target && e.target.tagName || "").toLowerCase(); if (tag === "input" || tag === "textarea" || e.target.isContentEditable) return;
  const c = parseCoords((e.clipboardData || window.clipboardData).getData("text")); if (!c) return;
  e.preventDefault(); goToCoords(c);
});
/** A place on the map, clicked: add it as a note on the route (at the nearest point, with distance and direction) or as a stop. */
function placePopup(f, lngLat) {
  const p = f.properties || {}; let c = f.geometry.coordinates; while (Array.isArray(c[0])) c = c[0];
  const name = p.name || p.kind.replace(/_/g, " "); const cat = POI_KIND[p.kind] || "place"; const label = { fuel: "Fuel", food: "Food", camping: "Camp", water: "Water", lodging: "Lodging", repair: "Repair" }[cat] || "Place";
  pointPopup(c, name, p.kind.replace(/_/g, " "), label);
}
/** A point with a name: a note on the route pointing at it, a stop through it, or a look at it. */
function pointPopup(c, name, kind, label) {
  if (placePop) placePop.remove();
  const el = document.createElement("div"); el.className = "placepop";
  el.innerHTML = `<b>${esc(name)}</b><div class="muted small">${esc(kind)}</div><div class="placepop-actions"><button data-act="note">+ note on route</button><button data-act="stop">+ stop here</button><button data-act="inspect">inspect</button></div>`;
  el.querySelector('[data-act="inspect"]').onclick = () => { placePop.remove(); inspect(c[0], c[1]); };
  el.querySelector('[data-act="note"]').onclick = () => {
    const line = lineCoords(); if (!line) { status("Plan a route first; then places can go on it as notes", true); return; }
    const a = alongLine(line, c); const d = dist(a.point, c); const b = Math.round(bearingDeg(a.point[1], a.point[0], c[1], c[0]));
    S.cues.push({ lon: a.point[0], lat: a.point[1], turn: "NOTE", level: 0, text: d < 60 ? `${label}: ${name}` : `${label}: ${name}, ${fmtDistShort(d)} ${compass(b)}`, auto: false });
    renderCues(); placePop.remove(); status("Added as a note at the nearest point of the route");
  };
  el.querySelector('[data-act="stop"]').onclick = () => { placePop.remove(); addStopAt(c[0], c[1], `${label}: ${name}`); };
  placePop = new maplibregl.Popup({ closeButton: true, closeOnClick: true, offset: 10 }).setLngLat(c).setDOMContent(el).addTo(map);
}

let ctxAt = null;
function showCtx(e) {
  const c = $("#ctx"); ctxAt = { lon: e.lngLat.lng, lat: e.lngLat.lat };
  const line = lineCoords(); const snap = line ? alongLine(line, [ctxAt.lon, ctxAt.lat]) : null;
  if (snap && snap.d < 400) ctxAt = { lon: snap.point[0], lat: snap.point[1], index: snap.index };
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
    S.profile = { samples, el, total };
    // gain with a 5 m hysteresis so GPS-scale noise does not count as climbing
    let gain = 0, loss = 0, ref = el[0];
    for (const e of el) { if (e - ref > 5) { gain += e - ref; ref = e; } else if (ref - e > 5) { loss += ref - e; ref = e; } }
    const min = Math.min(...el), max = Math.max(...el);
    txt.textContent = `↑ ${fmtElev(gain)} · ↓ ${fmtElev(loss)} · ${fmtElev(min)} – ${fmtElev(max)}`;
    if (S.route) climbCues(samples, el, total);
    cv.hidden = false; const ctx = cv.getContext("2d"); const W = cv.width, H = cv.height; ctx.clearRect(0, 0, W, H);
    const ink = getComputedStyle(document.body).getPropertyValue("--soft") || "#777";
    const span = Math.max(50, max - min); const pad = 4;
    ctx.beginPath(); ctx.moveTo(0, H);
    el.forEach((e, k) => { const x = samples[k][2] / total * W; const y = H - pad - (e - min) / span * (H - 2 * pad); ctx.lineTo(x, y); });
    ctx.lineTo(W, H); ctx.closePath(); ctx.fillStyle = MODES.find(m => m[0] === S.mode)[2] + "55"; ctx.fill();
    ctx.beginPath(); el.forEach((e, k) => { const x = samples[k][2] / total * W; const y = H - pad - (e - min) / span * (H - 2 * pad); k ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }); ctx.strokeStyle = ink.trim() || "#777"; ctx.lineWidth = 1.5; ctx.stroke();
  } catch { cv.hidden = true; txt.textContent = ""; }
}

/**
 * The big climbs, as cues: one at the base (length, gain, average and steepest grade) and one at the summit
 * (height, and how far the descent runs), so the phone's "next cue in …" reads as distance to the top.
 * A climb is a run that keeps gaining without ever dropping more than the tolerance below its high point;
 * bikes get every climb from 60 m of gain, motors only the passes (250 m).
 */
function climbCues(samples, raw, total) {
  const bike = /^(RIDE|QUIET|GRAVEL)$/.test(S.mode);
  const minGain = bike ? 60 : 250, tol = bike ? 15 : 25, minGrade = 0.02;   // a long canyon road at 2.7% is still the climb to the pass
  // smoothing over five samples: the DEM is 30 m cells and the road is not always in the one you think
  const el = raw.map((_, i) => { let s = 0, n = 0; for (let k = -2; k <= 2; k++) if (raw[i + k] != null) { s += raw[i + k]; n++; } return s / n; });
  const at = i => samples[i][2];
  const climbs = [];
  let i = 0;
  while (i < el.length - 1) {
    // the base: walk down to a local low
    while (i < el.length - 1 && el[i + 1] <= el[i]) i++;
    let top = i, j = i;
    while (j < el.length - 1) { j++; if (el[j] > el[top]) top = j; else if (el[top] - el[j] > tol) break; }
    // the base is where the flat ends: the last point still within a few metres of the low before the rise
    let m = i; for (let k = i; k <= top; k++) if (el[k] < el[m]) m = k;
    let base = m; while (base < top - 1 && el[base + 1] - el[m] < 3) base++;
    const peak = top; while (top > base + 1 && el[peak] - el[top - 1] < 3) top--;   // and the top is where the flat begins
    const gain = el[top] - el[base], len = at(top) - at(base);
    i = base;
    if (gain >= minGain && len > 0 && gain / len >= minGrade) {
      let maxG = 0;                                        // steepest stretch of at least 300 m
      for (let a = i; a < top; a++) { let b = a + 1; while (b < top && at(b) - at(a) < 300) b++; if (at(b) - at(a) >= 300) maxG = Math.max(maxG, (el[b] - el[a]) / (at(b) - at(a))); }
      // the descent after the top: to the next low (where the next climb would begin) or the end
      let low = top; for (let d = top; d < el.length; d++) { if (el[d] < el[low]) low = d; else if (el[d] - el[low] > tol) break; }
      climbs.push({ i, top, gain, len, avg: gain / len, maxG: Math.max(maxG, gain / len), elev: raw[top], descent: at(low) - at(top), drop: el[top] - el[low] });
    }
    i = Math.max(j, peak + 1);
  }
  S.cues = S.cues.filter(c => !(c.climb && c.auto));
  const removed = (lon, lat) => S.deletedAuto.some(d => dist([d.lon, d.lat], [lon, lat]) < 40);
  for (const c of climbs) {
    const pct = x => (x * 100 < 10 ? (x * 100).toFixed(1) : Math.round(x * 100)) + "%";
    const [lon1, lat1] = samples[c.i], [lon2, lat2] = samples[c.top];
    if (!removed(lon1, lat1)) S.cues.push({ lon: lon1, lat: lat1, turn: "NOTE", level: 0, text: `Climb ${fmtDist(c.len)}, ↑${fmtElev(c.gain)}, avg ${pct(c.avg)}, max ${pct(c.maxG)}`, auto: true, climb: true });
    if (!removed(lon2, lat2)) S.cues.push({ lon: lon2, lat: lat2, turn: "NOTE", level: 0, text: `Summit ${fmtElev(c.elev)}` + (c.descent > 400 && c.drop > tol ? ` · ↓${fmtDist(c.descent)} ahead` : ""), auto: true, climb: true });
  }
  renderCues();
}

// ---- inspect ---------------------------------------------------------------------------------------------------------
let inspMap = null;
let inspGen = 0;
async function inspect(lon, lat) {
  const gen = ++inspGen; const live = () => gen === inspGen && !$("#inspect").hidden;   // a slower, older Inspect must not write over a newer one
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
  const el = document.createElement("div"); el.className = "marker-pt dot"; inspMap._mk = new maplibregl.Marker({ element: el }).setLngLat([lon, lat]).addTo(inspMap);
  $("#inspS2").onclick = () => { inspMap.setStyle(style("s2")); inspMap.jumpTo({ zoom: Math.min(inspMap.getZoom(), 14) }); };
  // what the graph knows
  $("#inspFacts").innerHTML = `<span class="muted">Looking up…</span>`; $("#inspNear").innerHTML = `<span class="muted">Asking OpenStreetMap what is nearby…</span>`; $("#inspSections").innerHTML = ""; $("#inspWiki").innerHTML = ""; $("#inspWeb").innerHTML = "";
  const here = { lon, lat };
  let name = "", region = "", town = "", found = null;
  clearInspPlaces();
  const facts = (async () => {
    try {
      const r = await fetch(`/api/snap?lon=${lon}&lat=${lat}&max=400`); const j = await r.json();
      if (!live()) return;
      if (r.ok) { name = j.name; region = j.region; $("#inspFacts").innerHTML = `<b>${esc(j.name || "Unnamed " + j.cls.replace("_", " "))}</b><div class="muted">${esc(j.cls.replace("_", " "))} · ${j.unpaved ? "unpaved" : "paved or unknown surface"}${j.oneway ? " · one way" : ""} · ${Math.round(j.distance)} m from the point · ${esc(j.region)}</div>${j.private || j.privateBike ? `<div style="color:var(--accent);font-weight:600">Posted private${j.private && j.privateBike ? "" : j.private ? " to motor vehicles (bicycles allowed, by the tag)" : " to bicycles"} — OpenStreetMap tags it access=private (or a locked gate sits on it). That is what the map says, not a ruling on who may pass; ask.</div>` : ""}`; }
      else $("#inspFacts").innerHTML = `<span class="muted">${esc(j.error)}</span>`;
    } catch { if (live()) $("#inspFacts").innerHTML = `<span class="muted">The router is not reachable right now.</span>`; }
  })();
  // what is around: first from the map's own tiles (instant, the same data the phone carries), then
  // OpenStreetMap's query service widens the ring — fuel to 25 km, camping to 20 — when it answers
  const nearby = (async () => {
    const render = (near, note) => {
      if (!live()) return;
      town = town || (near.town && near.town[0] && near.town[0].name) || "";
      // a store is supplies, not a meal
      if (!near.supplies) { near.supplies = (near.food || []).filter(i => /supermarket|convenience|general|grocery|hardware|bakery/.test(i.kind || "")); near.food = (near.food || []).filter(i => !near.supplies.includes(i)); }
      found = near; showInspPlaces(near);
      const sec = (title, items, fmt) => items && items.length ? `<h4>${title}</h4>` + items.map(i => `<div class="row"><span><b>${esc(i.name || fmt.anon || "unnamed")}</b> ${i.kind ? `<span class="k">${esc(i.kind)}</span>` : ""}${i.fee === "free" ? ` <span class="free">free</span>` : i.fee === "fee" ? ` <span class="k">fee</span>` : ""}${i.operator ? ` <span class="k">· ${esc(i.operator)}</span>` : ""}${i.hours ? ` <span class="k">· ${esc(i.hours)}</span>` : ""}${i.phone ? ` <span class="k">· ${esc(i.phone)}</span>` : ""}${i.website ? ` <a target="_blank" rel="noopener" href="${esc(i.website)}">site</a>` : ""}</span><span class="d">${fmtDistShort(i.d)} ${compass(i.bearing)}</span><button data-stop="${esc(`${fmt.label}: ${i.name || fmt.anon || "unnamed"}`)}" data-lon="${i.lon}" data-lat="${i.lat}" title="Route through it and leave a note there">+ stop</button><button data-note="${esc(`${fmt.label}: ${i.name || fmt.anon || "unnamed"}, ${fmtDistShort(i.d)} ${compass(i.bearing)}`)}" title="A note on the route at this spot, pointing to it">+ note</button></div>`).join("") : "";
      $("#inspNear").innerHTML =
        (near.town && near.town.length ? `<div class="muted small">Nearest: ${near.town.map(t => esc(t.name) + " (" + fmtDistShort(t.d) + " " + compass(t.bearing) + ")").join(", ")}</div>` : "") +
        sec("Fuel", near.fuel, { label: "Fuel" }) + sec("Food", near.food, { label: "Food" }) + sec("Supplies", near.supplies, { label: "Supplies" }) + sec("Camping", near.camping, { label: "Camp", anon: "campsite" }) +
        sec("Water", near.water, { label: "Water", anon: "water" }) + sec("Beds", near.lodging, { label: "Lodging" }) + sec("Repair", near.repair, { label: "Repair" }) +
        (["fuel", "food", "supplies", "camping", "water", "lodging", "repair"].every(k => !near[k] || !near[k].length) ? `<span class="muted">Nothing tagged near this spot.</span>` : "") + (note ? `<div class="muted small">${note}</div>` : "");
      $("#inspNear").querySelectorAll("button[data-note]").forEach(b => b.onclick = () => addNoteHere(here, b.dataset.note, ""));
      $("#inspNear").querySelectorAll("button[data-stop]").forEach(b => b.onclick = () => { $("#inspect").hidden = true; addStopAt(Number(b.dataset.lon), Number(b.dataset.lat), b.dataset.stop); });
    };
    try {
      // the pack's place index (any distance, a rider's prominence) when the pack carries one; the tiles otherwise
      let near = null;
      try {
        const r = await fetch(`/api/near?lon=${lon}&lat=${lat}`); const j = await r.json();
        if (r.ok && j.reach > 0 && !j.partial) { near = { fuel: j.fuel, food: j.food, camping: j.camping, water: j.water, lodging: j.lodging, repair: j.repair, town: j.town, reachKm: Math.round(j.reach / 1000), index: true };
          for (const k of Object.keys(near)) if (Array.isArray(near[k])) for (const i of near[k]) { if (i.extra) { if (/^free\b/.test(i.extra)) i.fee = "free"; else if (/^fee\b/.test(i.extra)) i.fee = "fee"; i.hours = i.extra.replace(/^(free|fee)( · )?/, ""); } } }
      } catch {}
      if (near) { $("#inspAt").textContent = "from the map pack's place index"; render(near, `From the pack's place index: everything close, the prominent places (fuel, campgrounds, towns, repair) out to about ${fmtDist(60000)}. Tick <b>Places</b> in the layers box to see them on the map and click any to add it.`); return; }
      const say = (n, done) => render(n, `${done ? "Searched" : "Searching…"} about ${fmtDist(n.reachKm * 1000)} out, from the map tiles${done && n.reachKm > 8 ? " — it kept widening because fuel, camping or beds were not close" : ""}. Tick <b>Places</b> in the layers box to see them on the map and click any to add it.`);
      near = await peekPois(lon, lat, (partial) => say(partial, false));
      if (!live()) return;
      if (!near) { $("#inspNear").innerHTML = `<span class="muted">No map pack covers this spot.</span>`; return; }
      $("#inspAt").textContent = "from the map pack";
      say(near, true);
    } catch { if (live()) $("#inspNear").innerHTML = `<span class="muted">Nearby lookup failed.</span>`; }
  })();
  // nearby Wikipedia
  (async () => {
    try {
      const r = await fetch(`https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=${lat}|${lon}&gsradius=10000&gslimit=6&format=json&origin=*`);
      const j = await r.json(); const hits = (j.query && j.query.geosearch) || [];
      if (live() && hits.length) $("#inspWiki").innerHTML = `<h4>Nearby on Wikipedia</h4>` + hits.map(h => `<a target="_blank" rel="noopener" href="https://en.wikipedia.org/?curid=${h.pageid}">${esc(h.title)} <span class="muted">· ${fmtDistShort(h.dist)}</span></a>`).join("");
    } catch {}
  })();
  // the curated web, sorted the way a rider thinks — food, fuel, beds and camping, supplies, then the roads themselves —
  // each section searching for the named places the map index found rather than for a coordinate
  await Promise.all([facts, nearby]);
  if (!live()) return;
  const place = [name, town || region].filter(Boolean).join(" ") || [town, region].filter(Boolean).join(" ") || `${lat.toFixed(4)}, ${lon.toFixed(4)}`;
  const anchor = [town, region].filter(Boolean).join(" ");
  const names = (cat, n = 3) => ((found && found[cat]) || []).map(i => i.name).filter(x => x && x.length > 2).filter((x, i, a) => a.indexOf(x) === i).slice(0, n);
  const quoted = (list) => list.map(x => `"${x.replace(/"/g, "")}"`).join(" OR ");
  const src = sources();
  const secs = [];
  const add = (key, title, list, q, tail) => { if (!q) return; secs.push({ key, title, sites: src[key] || [], q: q + (tail ? " " + tail : ""), names: list }); };
  add("food", "Food", names("food"), names("food").length ? `(${quoted(names("food"))}) ${anchor}` : anchor ? `${anchor} restaurant OR cafe OR diner` : "");
  add("fuel", "Fuel", names("fuel", 2), names("fuel", 2).length ? `(${quoted(names("fuel", 2))}) ${anchor} gas` : anchor ? `${anchor} gas station` : "");
  const beds = [...names("camping", 3), ...names("lodging", 2)];
  add("lodging", "Camping & beds", beds, beds.length ? `(${quoted(beds)}) ${region}` : anchor ? `${anchor} campground OR motel OR cabins` : "");
  add("camping", "Free & dispersed camping", names("camping", 2), [names("camping", 1)[0] || town || name, region, "camping"].filter(Boolean).join(" "));
  add("supplies", "Supplies", names("supplies"), names("supplies").length ? `(${quoted(names("supplies"))}) ${anchor}` : anchor ? `${anchor} grocery OR hardware OR general store` : "");
  add("forums", "Trip reports & forums", [name].filter(Boolean), name ? `"${name}" ${region}` : `${town} ${region} dual sport OR adventure ride`);
  add("guides", "Trail & road guides", [name].filter(Boolean), name ? `${name} ${region}` : `${town} ${region} trail OR road`);
  const box = $("#inspSections");
  // nothing on the web is searched until asked: a section runs when its button is pressed (a query per named
  // place, each cached a week on the site, so a popular campground is looked up once for everyone); the plain
  // link under each is the same search in the rider's own browser and costs the site nothing
  const ddg = (q, sites) => `https://duckduckgo.com/?q=${encodeURIComponent(q + (sites && sites.length ? " (" + sites.map(s => "site:" + s).join(" OR ") + ")" : ""))}`;
  box.innerHTML = `<div class="ask"><input id="inspAsk" placeholder="Search this area for… (hot springs, bike shop, swimming hole)" maxlength="80"><button id="inspAskGo" class="mini">search</button></div><div id="secAsk"></div>`
    + secs.map((x, i) => `<div id="sec${i}" class="sec"><h4>${x.title} <button class="mini" data-run="${i}">search</button> <a class="muted" target="_blank" rel="noopener" href="${ddg(x.q, x.sites)}">in your browser</a></h4></div>`).join("")
    + `<div class="muted small"><button class="mini" id="secAll">search every section</button></div>`;
  const rowHtml = (w, via) => `<div class="row"><span class="body"><a target="_blank" rel="noopener" href="${esc(w.url)}">${esc(w.title)}</a><small>${esc(w.site || "")}${via ? ` · <i>via ${esc(via)}</i>` : ""} · ${esc((w.description || "").slice(0, 140))}</small></span><button data-note="${esc(w.title.slice(0, 120))}" data-link="${esc(w.url)}" title="Add this finding as a note on the route here">+ note</button></div>`;
  const ask = async (q, sites) => { const r = await fetch(`/api/web?q=${encodeURIComponent(q)}${sites.length ? "&sites=" + encodeURIComponent(sites.join(",")) : ""}`); const j = await r.json(); return { ok: r.ok, status: r.status, j }; };
  const runSection = async (el, title, sites, q, list, queries) => {
    if (!live()) return;
    const ext = `<a target="_blank" rel="noopener" href="${ddg(q, sites)}">open this search in your browser</a>`;
    el.innerHTML = `<h4>${title}</h4><span class="muted">Searching…</span>`;
    try {
      // one query per named place (two at most), then the section's own wording if the names found nothing
      const qs = queries && queries.length ? queries : [q];
      const answers = await Promise.all(qs.map(x => ask(x, sites).catch(() => null)));
      const budgetOut = answers.find(a => a && a.status === 429); const unset = answers.find(a => a && a.status === 501);
      let results = []; const seen = new Set();
      for (const a of answers) if (a && a.ok && a.j.results) for (const w of a.j.results) { const k = w.url.replace(/[#?].*$/, ""); if (!seen.has(k)) { seen.add(k); results.push(w); } }
      if (!results.length && queries && queries.length && !budgetOut && !unset) { const a = await ask(q, sites).catch(() => null); if (a && a.ok) results = a.j.results || []; }
      if (!live()) return;
      if (results.length) {
        // a result that names a place the map knows ranks by how close that place is; the rest keep the engine's order
        const hits = results.map((w, i) => { const text = (w.title + " " + (w.description || "")).toLowerCase(); const k = list.findIndex(n => text.includes(n.toLowerCase())); return { w, i, k: k < 0 ? 99 : k, via: k < 0 ? "" : list[k] }; }).sort((a, b) => a.k - b.k || a.i - b.i);
        el.innerHTML = `<h4>${title}</h4>` + hits.slice(0, 6).map(h => rowHtml(h.w, h.via)).join("") + `<div class="muted small">${ext}</div>`;
      } else el.innerHTML = `<h4>${title}</h4><span class="muted">${budgetOut ? "this site's search budget for today is spent · " : unset ? "web search is not set up on this site · " : "nothing found · "}</span>${ext}`;
    } catch { if (live()) el.innerHTML = `<h4>${title}</h4>${ext}`; }
    if (!live()) return;
    el.querySelectorAll("button[data-note]").forEach(b => b.onclick = () => addNoteHere(here, b.dataset.note, b.dataset.link));
  };
  const perName = (x) => x.names.slice(0, 2).map(n => `"${n.replace(/"/g, "")}" ${x.key === "forums" || x.key === "guides" || x.key === "lodging" || x.key === "camping" ? region : anchor}`);
  const run = (i) => { const x = secs[i]; runSection($("#sec" + i), x.title, x.sites, x.q, x.names, perName(x)); };
  box.querySelectorAll("button[data-run]").forEach(b => b.onclick = () => run(Number(b.dataset.run)));
  $("#secAll").onclick = () => secs.forEach((_, i) => run(i));
  $("#inspAskGo").onclick = () => { const q = $("#inspAsk").value.trim(); if (!q) return; runSection($("#secAsk"), esc(q), [], `${q} ${anchor || place}`, [...names("food"), ...names("camping"), ...names("lodging")], []); };
  $("#inspAsk").onkeydown = e => { if (e.key === "Enter") $("#inspAskGo").onclick(); };
  // the open web, also on request
  $("#inspWeb").innerHTML = `<h4>Elsewhere on the web <button class="mini" id="webGo">search</button> <a class="muted" target="_blank" rel="noopener" href="${ddg(place)}">in your browser</a></h4>`;
  $("#webGo").onclick = () => runSection($("#inspWeb"), "Elsewhere on the web", [], place + (name ? " road" : ""), [name].filter(Boolean), []);
}
// ---- POIs out of the pack tiles -------------------------------------------------------------------------------------
const POI_KIND = { fuel: "fuel", restaurant: "food", cafe: "food", fast_food: "food", bar: "food", pub: "food", food_court: "food", supermarket: "food", convenience: "food", general: "food", bakery: "food", grocery: "food",
  camp_site: "camping", camp_pitch: "camping", caravan_site: "camping", drinking_water: "water", spring: "water", water_well: "water", hotel: "lodging", motel: "lodging", hostel: "lodging", guest_house: "lodging", chalet: "lodging", alpine_hut: "lodging",
  car_repair: "repair", motorcycle: "repair", motorcycle_repair: "repair", city: "town", town: "town", village: "town", hamlet: "town" };
const pmCache = new Map();
/**
 * Points of interest around a spot, from the pack tiles. The search walks outward ring by ring until every
 * essential kind (fuel, camping, beds, repair, water) has a couple of answers or the budget is spent, and
 * reports how far it went. Zoom 15 carries every place; the outer rings use zoom 14, which carries the
 * prominent ones; towns come from a zoom-10 tile. A place index in the packs will replace this with a
 * rider's idea of prominence (fuel first) at any distance.
 */
async function peekPois(lon, lat, onUpdate) {
  if (!window.MVT) return null;
  const pack = roadsPacks().find(p => lon >= p.bbox[0] && lon <= p.bbox[2] && lat >= p.bbox[1] && lat <= p.bbox[3]); if (!pack) return null;
  let pm = pmCache.get(pack.id); if (!pm) { pm = new pmtiles.PMTiles(`${ORIGIN}/tiles/${pack.id}/tiles.pmtiles`); pmCache.set(pack.id, pm); }
  const txy = (z) => { const n = 2 ** z; return { x: Math.floor((lon + 180) / 360 * n), y: Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * n) }; };
  const out = { fuel: [], food: [], camping: [], water: [], lodging: [], repair: [], town: [] }; const seen = new Set();
  const tileKm = 40075 * Math.cos(lat * Math.PI / 180) / 2 ** 14;   // one zoom-14 tile, in km
  const ingest = (tiles) => {
    for (const t of tiles) {
      const vt = new MVT.VectorTile(new MVT.Pbf(new Uint8Array(t.data)));
      const layerName = t.z <= 10 ? "places" : "pois"; const L = vt.layers[layerName]; if (!L) continue;
      for (let i = 0; i < L.length; i++) {
        const f = L.feature(i); const p = f.properties || {};
        const cat = layerName === "places" ? (p.kind === "locality" && /^(city|town|village|hamlet)$/.test(p.kind_detail || "") ? "town" : null) : POI_KIND[p.kind];
        if (!cat || cat === "town" && layerName !== "places") continue;
        const g = f.toGeoJSON(t.x, t.y, t.z); let c = g.geometry.coordinates; while (Array.isArray(c[0])) c = c[0];
        const key = `${cat}|${(p.name || "").toLowerCase()}|${c[0].toFixed(3)},${c[1].toFixed(3)}`; if (seen.has(key)) continue; seen.add(key);
        const d = Math.round(dist([lon, lat], c)); if (cat === "town" && d > 80000) continue;
        const b = Math.round(bearingDeg(lat, lon, c[1], c[0]));
        out[cat].push({ name: p.name || "", kind: cat === "town" ? p.kind_detail : p.kind === "camp_site" ? "campground" : p.kind.replace(/_/g, " "), lon: c[0], lat: c[1], d, bearing: b, population: p.population });
      }
    }
  };
  const fetchRing = async (z, c, r) => {        // only the cells at Chebyshev distance r (the inner ones are already in)
    const jobs = [];
    for (let dx = -r; dx <= r; dx++) for (let dy = -r; dy <= r; dy++) { if (r > 0 && Math.abs(dx) !== r && Math.abs(dy) !== r) continue; jobs.push(pm.getZxy(z, c.x + dx, c.y + dy).then(t => t ? { z, x: c.x + dx, y: c.y + dy, data: t.data } : null).catch(() => null)); }
    return (await Promise.all(jobs)).filter(Boolean);
  };
  const finish = (reach, tilesRead) => { const o = {}; for (const k of Object.keys(out)) o[k] = out[k].slice().sort((a, b) => a.d - b.d).slice(0, k === "town" ? 3 : 6); o.reachKm = Math.round(reach); o.tiles = tilesRead; return o; };
  // the close rings first: everything within about 2½ miles (zoom 15), the prominent places to 5 (zoom 14), the towns (zoom 8)
  const c15 = txy(15), c14 = txy(14), c8 = txy(8);
  const first = await Promise.all([fetchRing(15, c15, 0), fetchRing(15, c15, 1), fetchRing(15, c15, 2), fetchRing(14, c14, 0), fetchRing(14, c14, 1), fetchRing(14, c14, 2), fetchRing(8, c8, 0), fetchRing(8, c8, 1)]);
  first.forEach(ingest);
  let tilesRead = 59, r = 2, reach = 2.5 * tileKm;
  if (onUpdate) onUpdate(finish(reach, tilesRead), false);
  // then outward two rings at a time until fuel, camping and beds each have an answer, or the budget is spent
  const essentials = ["fuel", "camping", "lodging"];
  while (essentials.some(k => out[k].length < 1) && r < 10 && tilesRead < 260) {
    const rings = await Promise.all([fetchRing(14, c14, r + 1), fetchRing(14, c14, r + 2)]); r += 2;
    for (const ring of rings) { tilesRead += ring.length; ingest(ring); }
    reach = (r + 0.5) * tileKm;
    if (onUpdate) onUpdate(finish(reach, tilesRead), false);
  }
  return finish(reach, tilesRead);
}
function bearingDeg(lat1, lon1, lat2, lon2) { const r = Math.PI / 180; const y = Math.sin((lon2 - lon1) * r) * Math.cos(lat2 * r); const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos((lon2 - lon1) * r); return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360; }
function compass(b) { return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Math.round(((b % 360) + 360) % 360 / 45) % 8]; }
/** A finding becomes a cue at the inspected spot: a note with the words, and the link kept for the GPX. */
function addNoteHere(at, text, link) {
  const line = lineCoords(); if (!line) { status("Plan a route first; then findings can go on it", true); return; }
  // the note sits on the route (the phone drops cues more than 150 m off it); if the spot is off the line, the words say how far and which way
  const a = alongLine(line, [at.lon, at.lat]); const d = dist(a.point, [at.lon, at.lat]);
  const words = d < 60 ? String(text) : `${String(text).slice(0, 96)} — ${fmtDistShort(d)} ${compass(Math.round(bearingDeg(a.point[1], a.point[0], at.lat, at.lon)))}`;
  S.cues.push({ lon: a.point[0], lat: a.point[1], turn: "NOTE", level: 0, text: words.slice(0, 120), link: link || "", auto: false });
  renderCues(); status(d < 60 ? "Added as a note on the route" + (link ? " — the link travels with the GPX" : "") : `Added as a note at the nearest point of the route, ${fmtDistShort(d)} from the spot`);
}

// ---- the rider's own source list ------------------------------------------------------------------------------------
const SOURCE_GROUPS = [["food", "Food"], ["fuel", "Fuel"], ["lodging", "Camping & beds"], ["camping", "Free & dispersed camping"], ["supplies", "Supplies"], ["forums", "Trip reports & forums"], ["guides", "Trail & road guides"]];
const DEFAULT_SOURCES = {
  food: [], fuel: [], lodging: [], supplies: [],   // the open web: a named café or campground finds its own pages
  camping: ["freecampsites.net", "campendium.com", "ioverlander.com", "thedyrt.com", "fs.usda.gov", "blm.gov"],
  forums: ["advrider.com", "reddit.com", "expeditionportal.com", "thumpertalk.com", "adventurerider.com"],
  guides: ["trailsoffroad.com", "onxmaps.com", "alltrails.com", "fs.usda.gov", "blm.gov", "stateparks.com"],
};
function sources() {
  try { const s = JSON.parse(localStorage.getItem("rb.sources") || "null"); if (s && typeof s === "object") return { ...DEFAULT_SOURCES, ...s }; } catch {}
  return DEFAULT_SOURCES;
}
$("#btnSources").onclick = () => {
  const cur = sources(); for (const s of document.querySelectorAll(".sheet")) s.hidden = true;
  $("#sourceGroups").innerHTML = SOURCE_GROUPS.map(([k, t]) => `<label>${t}</label><textarea data-group="${k}" placeholder="the open web">${esc((cur[k] || []).join("\n"))}</textarea>`).join("");
  $("#sources").hidden = false;
};
$("#srcSave").onclick = () => {
  const out = {}; for (const ta of document.querySelectorAll("#sourceGroups textarea")) out[ta.dataset.group] = ta.value.split(/\n/).map(s => s.trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/.*$/, "")).filter(s => /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s)).slice(0, 8);
  try { localStorage.setItem("rb.sources", JSON.stringify(out)); } catch {}
  $("#sources").hidden = true; status("Sources saved in this browser");
};
$("#srcReset").onclick = () => { try { localStorage.removeItem("rb.sources"); } catch {} $("#btnSources").onclick(); };

// ---- search ---------------------------------------------------------------------------------------------------------
let searchT;
$("#q").addEventListener("input", () => { clearTimeout(searchT); searchT = setTimeout(search, 250); });
$("#q").addEventListener("keydown", e => { if (e.key === "Enter") { const f = $("#hits div"); if (f) f.click(); } if (e.key === "Escape") $("#hits").hidden = true; });
document.addEventListener("click", e => { if (!e.target.closest(".search")) $("#hits").hidden = true; });
async function search() {
  const q = $("#q").value.trim(); const box = $("#hits");
  if (q.length < 2) { box.hidden = true; return; }
  if ($("#q") !== document.activeElement && box.hidden) return;
  const cc = parseCoords(q);
  if (cc) { box.innerHTML = `<div data-lon="${cc[0]}" data-lat="${cc[1]}">Go to ${cc[1].toFixed(5)}, ${cc[0].toFixed(5)}<small>a coordinate — then add it as a stop, a note, or inspect it</small></div>`; box.hidden = false; box.querySelector("div").onclick = () => { box.hidden = true; goToCoords(cc); }; return; }
  const c = map.getCenter(); const b = map.getBounds();
  // the view, but never wider than the router's 12° (zoomed out to the continent, the search still works around the centre)
  const hw = Math.min(6, (b.getEast() - b.getWest()) / 2), hh = Math.min(6, (b.getNorth() - b.getSouth()) / 2);
  const bb = [Math.max(-180, c.lng - hw), Math.max(-85, c.lat - hh), Math.min(180, c.lng + hw), Math.min(85, c.lat + hh)].map(v => v.toFixed(4));
  box.innerHTML = `<div class="muted">Searching…</div>`; box.hidden = false;
  try {
    const ac = new AbortController(); const giveUp = setTimeout(() => ac.abort(), 25000);
    let r, j;
    try { r = await fetch(`/api/search?q=${encodeURIComponent(q)}&lon=${c.lng.toFixed(5)}&lat=${c.lat.toFixed(5)}&bbox=${bb.join(",")}`, { signal: ac.signal }); j = await r.json(); }
    catch (e) { if (e.name === "AbortError") { if ($("#q").value.trim() === q) box.innerHTML = `<div class="muted">The maps here are still loading on the server — try again in a moment, or zoom in</div>`; return; } throw e; }
    finally { clearTimeout(giveUp); }
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
  for (const c of cues) { const type = c.turn === "DANGER" ? `danger:${c.level || 2}` : c.turn === "NOTE" ? "note" : `cue:${c.turn}`; out.push(`  <wpt lat="${c.lat.toFixed(6)}" lon="${c.lon.toFixed(6)}"><name>${esc(c.text || WORDS[c.turn])}</name>${c.text ? `<desc>${esc(c.text)}</desc>` : ""}${c.link && /^https?:\/\//.test(c.link) ? `<link href="${esc(c.link)}"><text>source</text></link>` : ""}<type>${type}</type></wpt>`); }
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
$("#btnClear").onclick = () => { S.pts = []; S.cues = []; S.deletedAuto = []; S.pendingNotes = []; editing = null; $("#cueEd").hidden = true; S.track = null; S.route = null; S.lastGood = null; abortInflight(); refreshRouteSources(); renderAll(); applyStyle(); };
const MIRROR = { LEFT: "RIGHT", RIGHT: "LEFT", SLIGHT_LEFT: "SLIGHT_RIGHT", SLIGHT_RIGHT: "SLIGHT_LEFT", SHARP_LEFT: "SHARP_RIGHT", SHARP_RIGHT: "SHARP_LEFT", KEEP_LEFT: "KEEP_RIGHT", KEEP_RIGHT: "KEEP_LEFT" };
/** A loop: the finish is the start. With only two points that is an out-and-back; drag the line to make it a circuit. */
function isLoop() { return S.pts.length >= 2 && Math.abs(S.pts[0].lon - S.pts[S.pts.length - 1].lon) < 1e-9 && Math.abs(S.pts[0].lat - S.pts[S.pts.length - 1].lat) < 1e-9; }
$("#btnLoop").onclick = () => {
  if (S.track) { status("This is a loaded track; clear it to plan a loop", true); return; }
  if (S.pts.length < 2) { status("Click a start and at least one more point first", true); return; }
  if (isLoop()) { S.pts.pop(); status("The loop is open again: the last stop is the finish"); }
  else { S.pts.push({ ...S.pts[0] }); status(S.pts.length === 3 ? "Out and back for now — drag the line to make it a circuit" : "Looped back to the start"); }
  afterPointsChange();
};
$("#btnReverse").onclick = () => {
  if (S.track) {
    // a new array, so every cached distance is rebuilt; turns mirror, the rest is the author's to check
    S.track = { ...S.track, coords: S.track.coords.slice().reverse() };
    let flagged = 0;
    S.cues = S.cues.map(c => { if (MIRROR[c.turn]) { if (c.text) flagged++; return { ...c, turn: MIRROR[c.turn] }; } if (c.turn === "ROUNDABOUT" || c.text) flagged++; return { ...c }; });
    refreshRouteSources(); renderAll();
    status(`Reversed: turns mirrored${flagged ? `; ${flagged} cue${flagged > 1 ? "s" : ""} with words or a roundabout exit need a look` : ""}`, flagged > 0);
    return;
  }
  S.pts.reverse(); afterPointsChange();
};
$("#file").onchange = async e => {
  const f = e.target.files[0]; e.target.value = ""; if (!f) return;
  if (f.size > 64 << 20) { status("That file is over 64 MB", true); return; }
  try { loadGpxText(await f.text(), f.name.replace(/\.gpx$/i, "")); } catch (err) { status("Could not read that file: " + err.message, true); }
};

const IMPORT_LIMITS = { points: 200000, cues: 5000 };
function parseGpx(text) {
  if (!/<gpx[\s>]/.test(text)) throw new Error("That is not a GPX file");
  const coord = (attrs, k, max) => { const v = Number(new RegExp(`${k}\\s*=\\s*"([^"]*)"`).exec(attrs)?.[1]); return Number.isFinite(v) && Math.abs(v) <= max ? v : null; };
  const pts = [], rte = [];
  for (const m of text.matchAll(/<trkpt\s+([^>]*?)(?:\/>|>[\s\S]*?<\/trkpt>)/g)) { const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A track point has a bad coordinate"); if (pts.length >= IMPORT_LIMITS.points) throw new Error(`More than ${IMPORT_LIMITS.points.toLocaleString()} points; simplify the track first`); pts.push([lon, lat]); }
  for (const m of text.matchAll(/<rtept\s+([^>]*?)(?:\/>|>[\s\S]*?<\/rtept>)/g)) { const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A route point has a bad coordinate"); rte.push({ lon, lat }); }
  const cues = []; for (const m of text.matchAll(/<wpt\s+([^>]*)>([\s\S]*?)<\/wpt>/g)) {
    const type = (/<type>([\s\S]*?)<\/type>/.exec(m[2])?.[1] || "").trim().toLowerCase();
    if (!(type.startsWith("cue:") || type.startsWith("danger") || type === "note")) continue;
    const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A cue has a bad coordinate");
    if (cues.length >= IMPORT_LIMITS.cues) throw new Error(`More than ${IMPORT_LIMITS.cues.toLocaleString()} cues`);
    const words = unesc((/<desc>([\s\S]*?)<\/desc>/.exec(m[2])?.[1] || /<name>([\s\S]*?)<\/name>/.exec(m[2])?.[1] || "").trim()).slice(0, 200);
    const link = unesc(/<link\s+href="([^"]*)"/.exec(m[2])?.[1] || ""); const safeLink = /^https?:\/\/[^\s"'<>]+$/.test(link) ? link : "";
    if (type.startsWith("cue:") && TURNS.includes(type.slice(4).toUpperCase())) cues.push({ lon, lat, turn: type.slice(4).toUpperCase(), level: 0, text: words, link: safeLink, auto: false });
    else if (type.startsWith("danger")) cues.push({ lon, lat, turn: "DANGER", level: Math.min(3, Math.max(1, Number(type.split(":")[1] || 2) || 2)), text: words, link: safeLink, auto: false });
    else if (type === "note" && words) cues.push({ lon, lat, turn: "NOTE", level: 0, text: words, link: safeLink, auto: false });
  }
  const name = unesc((/<metadata>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || /<trk>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || [, ""])[1].trim()).slice(0, 80);
  const mode = (/<roadbook>[\s\S]*?<mode>([A-Z_]+)<\/mode>/.exec(text) || [, ""])[1];
  return { pts, rte, cues, name, mode };
}
function unesc(s) { return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&"); }
function loadGpxText(text, fallbackName) {
  let g;
  try { g = parseGpx(text); } catch (e) { status(e.message, true); return; }   // the current plan is untouched by a bad file
  if (g.pts.length < 2 && g.rte.length >= 2) {
    // a route file with no track: its points are routing controls, so plan it here
    S.track = null; S.route = null; S.lastGood = null; abortInflight(); S.deletedAuto = []; S.pendingNotes = []; S.cues = g.cues;
    if (MODES.some(m => m[0] === g.mode)) { S.mode = g.mode; buildModes(); }
    let rte = g.rte, note = "";
    if (rte.length > 60) {
      // the router takes 60 stops: keep the start and the finish and the most evenly spread of the rest, and say so
      const keep = [rte[0]]; for (let k = 1; k < 59; k++) keep.push(rte[Math.round(k * (rte.length - 1) / 59)]); keep.push(rte[rte.length - 1]);
      note = ` — it had ${rte.length} stops, more than the router's 60; the start, the finish and 58 spread between them were kept, so check the line`; rte = keep;
    }
    S.pts = rte; $("#name").value = g.name || fallbackName || "Roadbook";
    afterPointsChange(); applyStyle();
    status(`Loaded “${g.name || fallbackName}” as ${rte.length} points to route between${note}`, !!note);
    return;
  }
  if (g.pts.length < 2) { status("That file has no track", true); return; }
  S.pts = []; S.route = null; S.lastGood = null; abortInflight(); S.deletedAuto = []; S.pendingNotes = [];
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
    $("#rbBody").innerHTML = `<div class="meta">${fmtDist(j.length)} · ${j.cues} cues${j.mode && MODES.find(m => m[0] === j.mode) ? " · " + MODES.find(m => m[0] === j.mode)[1] : ""}${surf} · ${new Date(j.created).toLocaleDateString()}</div><div class="actions"><button id="rbOpen" class="cta">Open in planner</button><a class="cta alt" href="${esc(j.url)}">Download GPX</a></div><p class="muted small">In the app: MAPS → BROWSE ROADBOOKS shows the same library on the phone.</p>`;
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

window.addEventListener("beforeunload", e => { if (S.cues.some(c => !c.auto) || S.track) { e.preventDefault(); e.returnValue = ""; } });
window.roadbookPlanner = { map, state: S, reroute, peekPois, inspect, climbCues };   // for the console and tests
init().catch(e => { console.error(e); status("The planner could not start: " + e.message, true); });
})();
