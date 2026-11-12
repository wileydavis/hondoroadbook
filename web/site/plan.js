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
  // day map by default; the choice is kept in this browser once changed (the apps do the same)
  night: (() => { try { return localStorage.getItem("rb.night") === "1"; } catch { return false; } })(), terrain: true, land: false, imagery: false, library: false,
  catalog: null, activePacks: new Set(), overview: new Map(), checking: new Set(), hasOverview: false, markers: [], cueMarkers: [], libraryData: null,
  inflight: null,
};
const RC = window.RoadbookChart;          // the roll-chart engine (chart.js)
S.chart = RC.settings({ layout: "A" }); S.chartOn = false;

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
  if (!S.pts.length && !new URLSearchParams(location.hash.slice(1)).get("rb")) offerRestore();
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
    b.onclick = () => { S.mode = id; for (const x of el.children) x.className = x.dataset.mode === id ? "on" : ""; if (S.track) { status(S.imported ? `Kept as recorded — press “route it on roads” to rebuild it as ${label}` : "This is a loaded track", false); } if (S.pts.length >= 2) reroute(); else applyStyle(); saveHash(); };
    el.appendChild(b);
  }
}
$("#lyTerrain").checked = S.terrain; $("#lyNight").checked = S.night; document.body.classList.toggle("night", S.night);
$("#lyTerrain").onchange = e => { S.terrain = e.target.checked; applyStyle(); };
$("#lyNight").onchange = e => { S.night = e.target.checked; try { localStorage.setItem("rb.night", S.night ? "1" : "0"); } catch {} document.body.classList.toggle("night", S.night); applyStyle(); };
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
    else if (S.imported && S.imported.routed) { S.imported.merging = false; keepTrack(); status(e.message + " — kept the track as recorded", true); }
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
  // the opened file's own cues that fall off the routed line come back with "back to the track"
  if (S.imported && S.imported.routed) for (const c of S.cues) if (!c.auto && c.imp && !kept.includes(c)) S.imported.merged.add(c.imp);
  const auto = j.cues.filter(c => !S.deletedAuto.some(d => dist([d.lon, d.lat], [c.lon, c.lat]) < 40)).map(c => ({ lon: c.lon, lat: c.lat, turn: c.turn, level: 0, exit: c.exit || 0, angle: Number.isFinite(c.angle) ? c.angle : undefined, text: c.turn === "ROUNDABOUT" ? c.name : (c.name || ""), auto: true }));
  S.cues = [...auto, ...kept];
  mergeImported();
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
  $("#empty").hidden = have; $("#stats").hidden = !have; $("#cuesSec").hidden = !have || S.chartOn; $("#chartSec").hidden = !have || !S.chartOn; $("#actions").hidden = !have;
  if (!have) { $("#trackSwap").hidden = true; return; }
  const len = S.route ? S.route.length : lineLength(c);
  $("#dist").textContent = fmtDist(len);
  $("#time").textContent = S.route ? `about ${fmtTime(S.route.time)} · ${MODES.find(m => m[0] === S.route.mode)[1]}` : (S.track ? "loaded track" : "");
  renderSwap();
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
  for (const c of S.cues) { if (!c.id) c.id = ++S.cueSeq; placeCue(c, line); }
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
  if (S.chartOn) scheduleChart();
  scheduleAutosave();
}
/** Where a cue sits along the line: its nearest point, or, where the line passes it twice (a loop, an out-and-back),
 *  the pass nearest the distance it was stored at, so its row keeps its place in the chart. */
function placeCue(c, line) {
  const near = alongLine(line, [c.lon, c.lat]); c.off = near.d;
  const stored = c.rally && c.rally.at != null ? c.rally.at : c.atHint;
  if (stored == null) { c.at = near.at; return; }
  c.at = RC.placeAt(line, cumulative(line), [c.lon, c.lat], near.at, stored, segmentsNear(line, [c.lon, c.lat]));
}
/** The line's segments within a cell of a point, from the same index alongLine uses. */
function segmentsNear(line, p) {
  const idx = lineIndex(line), cx = Math.floor(p[0] / idx.cell), cy = Math.floor(p[1] / idx.cell), out = new Set(idx.longs);
  for (let x = cx - 1; x <= cx + 1; x++) for (let y = cy - 1; y <= cy + 1; y++) { const l = idx.cells.get(x * 100003 + y); if (l) for (const i of l) out.add(i); }
  return [...out].sort((a, b) => a - b);
}

let editing = null;   // {id} for an existing cue (looked up at save time: the list can be re-sorted or regenerated meanwhile) or {lon,lat} for a new one
const cueById = id => S.cues.find(c => c.id === id);
const grid = $("#turnGrid");
for (const t of TURNS) { const b = document.createElement("button"); b.dataset.turn = t; b.innerHTML = arrowSvg(t); b.title = WORDS[t]; grid.appendChild(b); }
document.querySelectorAll("#cueEd .dangerRow [data-turn]").forEach(b => { const t = b.dataset.turn; b.innerHTML = arrowSvg(t, Number(b.dataset.level || 0)) + (t === "NOTE" ? "<span>Note</span>" : ""); b.title = t === "NOTE" ? "A note, read in your words" : `Danger ${b.dataset.level}`; });
document.querySelectorAll("#cueEd [data-turn]").forEach(b => b.onclick = () => { document.querySelectorAll("#cueEd [data-turn]").forEach(x => x.classList.remove("on")); b.classList.add("on"); if (b.dataset.turn === "NOTE") $("#cueText").focus(); });
function editCue(id, tab) {
  const c = cueById(id); if (!c) return;
  editing = { id };
  openCueEditor(c.turn, c.level, c.text, "Edit cue");
  rallyOpen(c); showCueTab(tab || (S.chartOn ? "rally" : "cue"));
  showSelRing();
}
function newCue(lon, lat) {
  // where along the line it was clicked: on a loop that pins the pass, not just the place
  const line = lineCoords(); let at = null;
  if (line && ctxAt && ctxAt.index) { const cum = cumulative(line); at = cum[ctxAt.index - 1] + dist(line[ctxAt.index - 1], [lon, lat]); }
  editing = { lon, lat, at };
  openCueEditor("STRAIGHT", 0, "", "New cue");
  rallyOpen(null); showCueTab(S.chartOn ? "rally" : "cue");
  showSelRing();
}
/** A ring on the map round the cue the editor has open (a new one before it is saved, too), gone when it closes. */
let selRing = null;
function showSelRing() {
  const open = !$("#cueEd").hidden && editing;
  const c = open && editing.id != null ? cueById(editing.id) : null;
  const at = c ? [c.lon, c.lat] : open && editing.lon != null ? [editing.lon, editing.lat] : null;
  if (!at) { if (selRing) { selRing.remove(); selRing = null; } return; }
  if (!selRing) { const el = document.createElement("div"); el.className = "cue-ring"; el.innerHTML = "<i></i>"; selRing = new maplibregl.Marker({ element: el, anchor: "center" }); }
  selRing.setLngLat(at).addTo(map);
}
new MutationObserver(showSelRing).observe($("#cueEd"), { attributes: true, attributeFilter: ["hidden"] });
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
  if (turn === "NOTE" && !text && !($("#rInfo").value.trim())) { showCueTab("cue"); $("#cueText").focus(); return; }
  if (editing.id != null) {
    const c = cueById(editing.id); if (!c) { $("#cueEd").hidden = true; status("That cue is no longer on the route", true); return; }
    if (c.auto) S.deletedAuto.push({ lon: c.lon, lat: c.lat });
    Object.assign(c, { turn, level, text, auto: false, climb: false });   // yours now: no generator may replace it; c.link stays
    const r = rallyResult(); if (r !== undefined) { if (r) c.rally = r; else delete c.rally; }
  } else { const c = { lon: editing.lon, lat: editing.lat, turn, level, text, auto: false }; if (editing.at != null) c.atHint = editing.at; const r = rallyResult(); if (r) c.rally = r; S.cues.push(c); }
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
/** The GPX. opts.rally adds the roll-chart fields (Download GPX and the autosave); Send to phone and Publish leave
 *  them out in v1. Every <type> stays exactly as the apps know it; the rally object is JSON in one <rally> element,
 *  the last thing in its <wpt>, with no child named name, desc or type, so both parsers skip it. */
function gpxOut(opts) {
  opts = opts || {};
  const line = lineCoords(); const name = $("#name").value.trim() || "Roadbook";
  const cues = S.cues.slice().sort((a, b) => a.at - b.at);
  let paved = 0, unpaved = 0; if (S.route) for (const r of S.route.surface) { if (r.unpaved || r.cls === "track" || r.cls === "path") unpaved += r.m; else paved += r.m; }
  const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<gpx version="1.1" creator="Roadbook Planner" xmlns="http://www.topografix.com/GPX/1/1">', `  <metadata><name>${esc(name)}</name><extensions><roadbook>${S.route ? `<mode>${S.route.mode}</mode><paved>${Math.round(paved)}</paved><unpaved>${Math.round(unpaved)}</unpaved>` : ""}<planner>1</planner>${opts.rally ? `<chart>${esc(JSON.stringify(RC.settingsForFile(S.chart)))}</chart>` : ""}</roadbook></extensions></metadata>`];
  for (const c of cues) { const type = c.turn === "DANGER" ? `danger:${c.level || 2}` : c.turn === "NOTE" ? "note" : `cue:${c.turn}`; out.push(`  <wpt lat="${c.lat.toFixed(6)}" lon="${c.lon.toFixed(6)}"><name>${esc(c.text || WORDS[c.turn])}</name>${c.text ? `<desc>${esc(c.text)}</desc>` : ""}${c.link && /^https?:\/\//.test(c.link) ? `<link href="${esc(c.link)}"><text>source</text></link>` : ""}<type>${type}</type>${opts.rally ? rallyXml(c) : ""}</wpt>`); }
  out.push(`  <trk><name>${esc(name)}</name><trkseg>`);
  // a kept track keeps the heights its file had; a routed line has none to give
  for (const p of line) out.push(`    <trkpt lat="${p[1].toFixed(6)}" lon="${p[0].toFixed(6)}">${S.track && p.length > 2 ? `<ele>${p[2].toFixed(1)}</ele>` : ""}</trkpt>`);
  out.push("  </trkseg></trk>", "</gpx>", "");
  return out.join("\n");
}
/** Does this roadbook have a roll chart worth carrying to the phone and the library? (opened here, came in a file, or a cue has chart fields) */
function chartInUse() { return !!(S.chartOn || S.chartLoaded || S.cues.some(c => c.rally && Object.keys(c.rally).length)); }
function rallyXml(c) { const r = Object.assign({}, c.rally || {}); if (Number.isFinite(c.at)) r.at = Math.round(c.at * 10) / 10; const clean = RC.cleanRally(r); return clean ? `<extensions><rally>${esc(JSON.stringify(clean))}</rally></extensions>` : ""; }
$("#btnGpx").onclick = () => { const blob = new Blob([gpxOut({ rally: true })], { type: "application/gpx+xml" }); const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = ($("#name").value.trim() || "roadbook").replace(/[^\w .-]+/g, "_") + ".gpx"; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 5000); };
$("#btnPhone").onclick = async () => {
  const sh = $("#codeSheet"); for (const s of document.querySelectorAll(".sheet")) s.hidden = true; sh.hidden = false; $("#codeTitle").textContent = "Send to phone"; $("#codeBody").innerHTML = `<p class="muted">Parking the roadbook…</p>`;
  try {
    const r = await fetch(`/api/park?name=${encodeURIComponent($("#name").value.trim() || "Roadbook")}`, { method: "POST", body: gpxOut({ rally: chartInUse() }), headers: { "Content-Type": "application/gpx+xml" } });
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
      const r = await fetch(`/api/roadbooks?name=${encodeURIComponent($("#name").value.trim() || "Roadbook")}&source=planner`, { method: "POST", body: gpxOut({ rally: chartInUse() }), headers: { "Content-Type": "application/gpx+xml" } });
      const j = await r.json(); if (!r.ok) throw new Error(j.error);
      $("#codeBody").innerHTML = `<p>Published. <a href="${esc(j.page)}">Link to it</a> · <a href="${esc(j.url)}">GPX</a></p><p class="muted small">If it should come down, email wiley@machinenusa.com with the link.</p>`;
      if (S.library) loadLibrary();
    } catch (e) { $("#codeBody").innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
  };
};
$("#btnClear").onclick = () => { forgetAutosave(); S.imported = null; $("#openSheet").hidden = true; S.pts = []; S.cues = []; S.deletedAuto = []; S.pendingNotes = []; editing = null; $("#cueEd").hidden = true; S.track = null; S.route = null; S.lastGood = null; abortInflight(); refreshRouteSources(); renderAll(); applyStyle(); };
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
    if (S.imported) S.imported.coords = S.track.coords;   // "back to the track" keeps the new direction
    let flagged = 0;
    S.cues = S.cues.map(c => { c = reversedRally(c); if (MIRROR[c.turn]) { if (c.text) flagged++; return { ...c, turn: MIRROR[c.turn] }; } if (c.turn === "ROUNDABOUT" || c.text) flagged++; return { ...c }; });
    refreshRouteSources(); renderAll();
    status(`Reversed: turns mirrored${flagged ? `; ${flagged} cue${flagged > 1 ? "s" : ""} with words or a roundabout exit need a look` : ""}`, flagged > 0);
    return;
  }
  if (S.imported && S.imported.routed) S.imported.coords = S.imported.coords.slice().reverse();
  S.cues = S.cues.map(reversedRally);
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
  for (const m of text.matchAll(/<trkpt\s+([^>]*?)(?:\/>|>([\s\S]*?)<\/trkpt>)/g)) { const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A track point has a bad coordinate"); if (pts.length >= IMPORT_LIMITS.points) throw new Error(`More than ${IMPORT_LIMITS.points.toLocaleString()} points; simplify the track first`); const ele = Number(/<ele>\s*([-\d.eE+]+)\s*<\/ele>/.exec(m[2] || "")?.[1]); pts.push(Number.isFinite(ele) && Math.abs(ele) < 12000 ? [lon, lat, ele] : [lon, lat]); }
  for (const m of text.matchAll(/<rtept\s+([^>]*?)(?:\/>|>[\s\S]*?<\/rtept>)/g)) { const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A route point has a bad coordinate"); rte.push({ lon, lat }); }
  const cues = [], wpts = []; for (const m of text.matchAll(/<wpt\s+([^>]*)>([\s\S]*?)<\/wpt>/g)) {
    const type = (/<type>([\s\S]*?)<\/type>/.exec(m[2])?.[1] || "").trim().toLowerCase();
    if (!(type.startsWith("cue:") || type.startsWith("danger") || type === "note")) {
      // a plain waypoint (a gas stop, a gate someone marked): offered as a note when it sits by the line
      const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180), nm = unesc((/<name>([\s\S]*?)<\/name>/.exec(m[2])?.[1] || "").trim()).slice(0, 120);
      if (lat != null && lon != null && nm && wpts.length < IMPORT_LIMITS.cues) wpts.push({ lon, lat, name: nm, desc: unesc((/<desc>([\s\S]*?)<\/desc>/.exec(m[2])?.[1] || "").trim()).slice(0, 120) });
      continue;
    }
    const lat = coord(m[1], "lat", 90), lon = coord(m[1], "lon", 180); if (lat == null || lon == null) throw new Error("A cue has a bad coordinate");
    if (cues.length >= IMPORT_LIMITS.cues) throw new Error(`More than ${IMPORT_LIMITS.cues.toLocaleString()} cues`);
    const words = unesc((/<desc>([\s\S]*?)<\/desc>/.exec(m[2])?.[1] || /<name>([\s\S]*?)<\/name>/.exec(m[2])?.[1] || "").trim()).slice(0, 200);
    const link = unesc(/<link\s+href="([^"]*)"/.exec(m[2])?.[1] || ""); const safeLink = /^https?:\/\/[^\s"'<>]+$/.test(link) ? link : "";
    const rx = /<rally>([\s\S]*?)<\/rally>/.exec(m[2]); const rally = rx ? RC.parseRally(unesc(rx[1])) : null;
    const n0 = cues.length;
    if (type.startsWith("cue:") && TURNS.includes(type.slice(4).toUpperCase())) cues.push({ lon, lat, turn: type.slice(4).toUpperCase(), level: 0, text: words, link: safeLink, auto: false });
    else if (type.startsWith("danger")) cues.push({ lon, lat, turn: "DANGER", level: Math.min(3, Math.max(1, Number(type.split(":")[1] || 2) || 2)), text: words, link: safeLink, auto: false });
    else if (type === "note" && (words || rally)) cues.push({ lon, lat, turn: "NOTE", level: 0, text: words, link: safeLink, auto: false });
    if (rally && cues.length > n0) cues[cues.length - 1].rally = rally;
  }
  const name = unesc((/<metadata>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || /<trk>[\s\S]*?<name>([\s\S]*?)<\/name>/.exec(text) || [, ""])[1].trim()).slice(0, 80);
  const mode = (/<roadbook>[\s\S]*?<mode>([A-Z_]+)<\/mode>/.exec(text) || [, ""])[1];
  const cx = /<roadbook>[\s\S]*?<chart>([\s\S]*?)<\/chart>/.exec(text); const chart = cx ? RC.parseSettings(unesc(cx[1])) : null;
  return { pts, rte, cues, name, mode, chart, wpts };
}
function unesc(s) { return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;|&#39;/g, "'").replace(/&amp;/g, "&"); }   // esc() writes &#39;
function loadGpxText(text, fallbackName, opts) {
  opts = opts || {};
  let g;
  try { g = parseGpx(text); } catch (e) { status(e.message, true); return; }   // the current plan is untouched by a bad file
  S.chartLoaded = !!g.chart; if (g.chart) { S.chart = g.chart; if (S.chartOn) syncChartLayout(); }
  if (g.pts.length < 2 && g.rte.length >= 2) {
    // a route file with no track: its points are routing controls, so plan it here
    S.imported = null; $("#openSheet").hidden = true;
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
  // the file as it came, so the person can route it and still come back to it (and its cues) later
  g.cues.forEach((c, i) => c.imp = i + 1);
  hintOrder(g.cues, g.pts);
  S.imported = { coords: g.pts, name: g.name || fallbackName, cues: g.cues.map(c => ({ ...c })), mode: MODES.some(m => m[0] === g.mode) ? g.mode : null, merged: new Set() };
  keepTrack(g.cues);
  $("#name").value = S.imported.name || "Roadbook";
  const b = g.pts.reduce((bb, p) => [Math.min(bb[0], p[0]), Math.min(bb[1], p[1]), Math.max(bb[2], p[0]), Math.max(bb[3], p[1])], [180, 90, -180, -90]);
  map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 60, duration: 600 });
  for (const sh of document.querySelectorAll(".sheet")) sh.hidden = true;
  if (opts.restore) { status(`Restored “${S.imported.name}”: ${g.cues.length} cues`); return; }
  // plain waypoints near the line (a gas stop, a gate someone marked) can come in as notes
  const near = g.wpts.filter(w => nearLine(w, g.pts) < 150 && !g.cues.some(c => dist([c.lon, c.lat], [w.lon, w.lat]) < 10));
  const wb = $("#openWpts"); wb.hidden = !near.length;
  if (near.length) { wb.textContent = `Add ${near.length} waypoint${near.length > 1 ? "s" : ""} from the file as notes`; wb.onclick = () => { for (const w of near) S.cues.push({ lon: w.lon, lat: w.lat, turn: "NOTE", level: 0, text: (w.desc && w.desc !== w.name ? `${w.name}: ${w.desc}` : w.name).slice(0, 120), auto: false }); wb.hidden = true; renderCues(); status(`Added ${near.length} notes`); }; }
  $("#openTitle").textContent = `Opened “${S.imported.name}”`;
  $("#openBody").textContent = `${fmtDist(lineLength(g.pts))} of track, ${g.cues.length} cue${g.cues.length === 1 ? "" : "s"}. Keep the line as it is, or route it on roads (${modeLabel()})?`;
  $("#openSheet").hidden = false;
  status(`Loaded “${S.imported.name}” as recorded. Click the line to add cues.`);
}

// ---- an opened track: kept as recorded, or routed on roads through stops taken from it ----------------------------------
function modeLabel() { return (MODES.find(m => m[0] === S.mode) || MODES[0])[1]; }
/** Show the opened file's own line; cues are the ones given, or the current cues that still sit on it plus any the merge took. */
function keepTrack(cues) {
  const imp = S.imported; if (!imp) return;
  S.routeReq++; S.pts = []; S.route = null; S.lastGood = null; abortInflight(); setElastic(null); S.deletedAuto = []; S.pendingNotes = [];
  if (!cues) {
    const mine = S.cues.filter(c => !c.auto && nearLine(c, imp.coords) < 150);
    const back = imp.cues.filter(c => imp.merged.has(c.imp) && !mine.some(m => m.imp === c.imp)).map(c => ({ ...c }));
    const lost = S.cues.filter(c => !c.auto).length - mine.length;
    cues = [...mine, ...back];
    if (lost) status(`${lost} cue${lost > 1 ? "s" : ""} added on the routed line ${lost > 1 ? "are" : "is"} off the original track and ${lost > 1 ? "were" : "was"} left out`, true);
  }
  imp.merged = new Set(); imp.routed = false;
  S.track = { coords: imp.coords, name: imp.name }; S.cues = cues;
  renderPoints(); refreshRouteSources(); renderAll(); applyStyle();
}
/** Stops spread along the track by distance (the router takes 60), so the route follows the same way. */
function stopsFromTrack(c) {
  const total = lineLength(c), n = Math.max(2, Math.min(60, Math.round(total / 1500) + 1));
  const step = total / (n - 1), out = [{ lon: c[0][0], lat: c[0][1] }];
  let along = 0, next = step;
  for (let i = 1; i < c.length && out.length < n - 1; i++) {
    const d = dist(c[i - 1], c[i]);
    while (d > 0 && along + d >= next && out.length < n - 1) { const t = (next - along) / d; out.push({ lon: c[i - 1][0] + (c[i][0] - c[i - 1][0]) * t, lat: c[i - 1][1] + (c[i][1] - c[i - 1][1]) * t }); next += step; }
    along += d;
  }
  out.push({ lon: c[c.length - 1][0], lat: c[c.length - 1][1] });
  return out;
}
function routeTrack() {
  const imp = S.imported; if (!imp) return;
  const cues = S.cues.filter(c => !c.auto);
  S.track = null; S.deletedAuto = []; S.pendingNotes = []; S.lastGood = null;
  S.cues = cues; imp.routed = true; imp.merging = true;
  S.pts = stopsFromTrack(imp.coords);
  afterPointsChange(); applyStyle();
  status(`Routing “${imp.name}” on roads (${modeLabel()}) through ${S.pts.length} stops from the track`);
}
/** After the first route of an opened track: a plain turn cue from the file on a turn the router also found gives way to the
 *  router's (one cue per turn); one with words keeps its words and takes the router's place. */
function mergeImported() {
  const imp = S.imported; if (!imp || !imp.merging) return; imp.merging = false;
  const auto = S.cues.filter(c => c.auto), mine = S.cues.filter(c => !c.auto);
  const keep = [];
  for (const c of mine) {
    const near = TURNS.includes(c.turn) && c.turn !== "DANGER" && c.turn !== "NOTE" ? auto.find(a => dist([a.lon, a.lat], [c.lon, c.lat]) < 40) : null;
    if (!near) { keep.push(c); continue; }
    const words = c.text && c.text.trim().toUpperCase().replace(/ /g, "_") !== c.turn && c.text.trim().toLowerCase() !== String(WORDS[c.turn] || "").toLowerCase();
    if (words) { auto.splice(auto.indexOf(near), 1); keep.push(c); }
    else if (c.imp) imp.merged.add(c.imp);
  }
  S.cues = [...auto, ...keep];
}
function renderSwap() {
  const el = $("#trackSwap"), imp = S.imported;
  if (!imp || (!S.track && !(imp.routed && S.route))) { el.hidden = true; return; }
  el.hidden = false;
  if (S.track) { $("#swapTxt").textContent = "Kept as recorded."; $("#swapBtn").textContent = `route it on roads (${modeLabel()})`; $("#swapBtn").onclick = routeTrack; }
  else { $("#swapTxt").textContent = `Routed from “${imp.name}”.`; $("#swapBtn").textContent = "back to the track as recorded"; $("#swapBtn").onclick = () => { keepTrack(); status(`Back to “${imp.name}” as recorded`); }; }
}
$("#openKeep").onclick = () => { $("#openSheet").hidden = true; };
$("#openRoute").onclick = () => { $("#openSheet").hidden = true; routeTrack(); };

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

// ---- roll charts: the preview, the rally fields on a cue, the export ----------------------------------------------------
// The engine (chart.js) lays out every row; this part feeds it the line and the cues, shows what it draws, and edits the
// rally object on each cue. The PDF writer (chart-pdf.js) loads only when the export dialog opens.
const juncCache = new Map();
const juncKey = c => c.lon.toFixed(5) + "," + c.lat.toFixed(5);
function chartSettings() { const st = RC.settings(S.chart); if (!st.title) st.title = $("#name").value.trim() || "Roadbook"; return st; }
function chartData() {
  const line = lineCoords(); if (!line) return null;
  const st = chartSettings();
  return { st, line, chart: RC.buildRows(line, S.cues, st, { cum: cumulative(line), surface: S.route ? S.route.surface : null }) };
}
let chartT = 0, selRow = null;
function scheduleChart() { clearTimeout(chartT); chartT = setTimeout(renderChart, 150); }
function syncChartLayout() { for (const b of document.querySelectorAll("#chartLayout button")) b.classList.toggle("on", b.dataset.l === S.chart.layout); $("#chartRows").className = "crows " + S.chart.layout; }
/** The preview: the head block and every row, drawn by the same code as the PDF; the rows that need a look listed above. */
function renderChart() {
  if (!S.chartOn) return;
  syncChartLayout();
  const d = chartData(), box = $("#chartRows"), warn = $("#chartWarn");
  if (!d) { box.innerHTML = ""; warn.innerHTML = ""; return; }
  const parts = [`<div class="crow head">${RC.headSvg(d.chart, d.st)}</div>`], issues = d.chart.warnings.slice(), bad = new Set();
  for (const r of d.chart.rows) {
    const { svg, overflow } = RC.rowSvg(r, d.st);
    if (overflow) issues.push({ row: r.n, id: r.id, text: r.kind === "banner" ? "A section name is too long for its banner" : `Row ${r.n}: the words do not fit even at the smallest size; shorten them` });
    for (const ch of RC.winAnsi((r.info || "") + (r.name || "")).bad) bad.add(ch);
    parts.push(`<div class="crow${overflow ? " over" : ""}${r.id != null && r.id === selRow ? " sel" : ""}"${r.id != null ? ` data-id="${r.id}"` : ""}${Number.isFinite(r.at) ? ` data-at="${r.at}"` : ""}>${svg}${r.auto ? '<span class="tag">auto</span>' : ""}</div>`);
  }
  if (bad.size) issues.push({ text: `These characters cannot be printed and will show as “?”: ${[...bad].join(" ")}` });
  box.innerHTML = parts.join("");
  // a town route can flag a short partial on every row; the first few and a count read better than a wall
  const shorts = issues.filter(w => w.kind === "short"), rest = issues.filter(w => w.kind !== "short");
  const shown = rest.slice(0, 8).concat(shorts.length > 3 ? [{ text: `${shorts.length} rows have a short partial (under 400 m); FIA marks them pink with an arrow, as the chart does` }] : shorts);
  if (rest.length > 8) shown.push({ text: `and ${rest.length - 8} more` });
  warn.innerHTML = shown.map(w => `<li${w.id != null ? ` data-id="${w.id}"` : ""}>${esc(w.text)}</li>`).join("");
  $("#chartCount").textContent = `(${d.chart.rows.filter(r => r.kind !== "banner").length} rows)`;
}
function goToCue(c) { selRow = c.id; map.flyTo({ center: [c.lon, c.lat], zoom: Math.max(map.getZoom(), 15) }); editCue(c.id, "rally"); renderChart(); }
$("#chartRows").addEventListener("click", e => {
  const el = e.target.closest(".crow"); if (!el) return;
  if (el.dataset.id) { const c = cueById(Number(el.dataset.id)); if (c) goToCue(c); }
  else if (el.dataset.at) { const line = lineCoords(); if (line) map.flyTo({ center: RC.pointAt(line, cumulative(line), Number(el.dataset.at)), zoom: Math.max(map.getZoom(), 14) }); }
});
$("#chartWarn").addEventListener("click", e => { const li = e.target.closest("li"); const c = li && li.dataset.id ? cueById(Number(li.dataset.id)) : null; if (c) goToCue(c); });
document.querySelectorAll("#chartLayout button").forEach(b => b.onclick = () => { setLayout(b.dataset.l); renderChart(); });
function setLayout(l) { if (S.chart.layout === l) return; S.chart = RC.settings({ layout: l, title: S.chart.title, scale: S.chart.scale, decline: S.chart.decline }); scheduleAutosave(); }
$("#btnChart").onclick = () => { S.chartOn = true; renderStats(); renderChart(); fetchJunctions(false); $("#chartSec").scrollIntoView({ block: "start", behavior: "smooth" }); };
$("#btnChartClose").onclick = () => { S.chartOn = false; renderStats(); };
$("#btnJunctions").onclick = () => fetchJunctions(true);
$("#btnSuggest").onclick = suggestCues;

/**
 * The other roads at each cue's junction, from the routing graph (/api/junctions): suggestions only. A cue whose tulip
 * the rider has drawn keeps their roads; with no answer the tulip shows stem and exit alone.
 */
let juncBusy = false;
async function fetchJunctions(ask, only) {
  if (juncBusy) return;
  const todo = [];
  for (const c of (only || S.cues)) {
    if (c.rally && c.rally.branches) continue;
    const k = juncKey(c); if (juncCache.has(k)) c.junc = juncCache.get(k); else todo.push([c, k]);
  }
  if (!todo.length) { if (S.chartOn) scheduleChart(); if (ask) status("Every junction is already looked up"); return; }
  juncBusy = true; if (ask) status(`Asking the map about ${todo.length} junction${todo.length > 1 ? "s" : ""}…`, false, true);
  let ok = true;
  try {
    for (let i = 0; i < todo.length; i += 200) {
      const batch = todo.slice(i, i + 200);
      const r = await fetch(`/api/junctions?pts=${encodeURIComponent(batch.map(([c]) => `${c.lon.toFixed(6)},${c.lat.toFixed(6)}`).join(";"))}`);
      if (!r.ok) { ok = false; break; }
      const j = await r.json();
      batch.forEach(([c, k], n) => { const v = { arms: (j.junctions && j.junctions[n] && j.junctions[n].arms) || [] }; juncCache.set(k, v); c.junc = v; });
    }
  } catch { ok = false; }
  juncBusy = false;
  if (ask) status(ok ? "Junctions found. They are suggestions: drag or click a tulip to make it yours" : "The map's junctions are not available here right now; draw the roads by hand", !ok);
  if (S.chartOn) scheduleChart(); drawTulipEd();
}

/** Turns from a kept track's shape, the way the phone finds them in a bare track (Route.fromTrack): marked auto. */
function suggestCues() {
  const line = lineCoords(); if (!line) return;
  if (S.route) { status("A routed line already carries the router's turns (reset to turns brings back any you deleted)"); return; }
  const keep = simplify(line, 6), cum = cumulative(line); let lastAt = -1e9, added = 0;
  for (let k = 1; k < keep.length - 1; k++) {
    const i = keep[k], a = line[keep[k - 1]], p = line[i], b = line[keep[k + 1]];
    const ang = RC.turnAngle(RC.bearing(a, p), RC.bearing(p, b)), x = Math.abs(ang);
    const t = x >= 150 ? "UTURN" : x >= 110 ? (ang < 0 ? "SHARP_LEFT" : "SHARP_RIGHT") : x >= 45 ? (ang < 0 ? "LEFT" : "RIGHT") : null;
    if (!t || cum[i] - lastAt < 25) continue;
    lastAt = cum[i];
    if (S.cues.some(c => Math.abs(c.at - cum[i]) < 30)) continue;
    S.cues.push({ lon: p[0], lat: p[1], turn: t, level: 0, text: "", auto: true, atHint: cum[i] }); added++;
  }
  renderCues();
  status(added ? `Added ${added} turn${added > 1 ? "s" : ""} from the track's shape, marked auto` : "No turns found that are not already cues");
  if (added) fetchJunctions(false);
}
/** Douglas–Peucker over the line in local metres; returns the indices kept. */
function simplify(line, tol) {
  const n = line.length; if (n < 3) return [...line.keys()];
  const k = Math.cos(line[0][1] * Math.PI / 180) * 111320, xy = line.map(p => [p[0] * k, p[1] * 111320]);
  const keep = new Uint8Array(n); keep[0] = keep[n - 1] = 1;
  const stack = [[0, n - 1]];
  while (stack.length) {
    const [a, b] = stack.pop(); let best = -1, bd = tol;
    const [ax, ay] = xy[a], [bx, by] = xy[b], dx = bx - ax, dy = by - ay, l = Math.hypot(dx, dy) || 1e-9;
    for (let i = a + 1; i < b; i++) { const d = Math.abs((xy[i][0] - ax) * dy - (xy[i][1] - ay) * dx) / l; if (d > bd) { bd = d; best = i; } }
    if (best > 0) { keep[best] = 1; stack.push([a, best], [best, b]); }
  }
  const out = []; for (let i = 0; i < n; i++) if (keep[i]) out.push(i); return out;
}
/** Cues from a file without a stored distance: on a loop, taken in the file's order, each at the first pass after the last. */
function hintOrder(cues, line) {
  const cum = RC.cumulative(line); let prev = 0;
  for (const c of cues) {
    if (c.rally && c.rally.at != null) { prev = c.rally.at; continue; }
    const ps = RC.passes(line, cum, [c.lon, c.lat], 30);
    if (ps.length < 2) { if (ps.length) prev = ps[0].at; continue; }
    const next = ps.find(h => h.at >= prev - 50) || ps[ps.length - 1];
    c.atHint = next.at; prev = next.at;
  }
}
/** A reversed line: a stored distance, a drawn angle and roads, a CAP no longer apply; symbols swap sides. */
const SLOT_FLIP = { la: "rb", ra: "lb", lb: "ra", rb: "la" };
function reversedRally(c) {
  c = { ...c }; delete c.atHint; delete c.junc;
  if (!c.rally) return c;
  const r = { ...c.rally }; delete r.at; delete r.exit; delete r.branches; delete r.cap;
  if (r.symbols) r.symbols = r.symbols.map(s => ({ id: s.id, slot: SLOT_FLIP[s.slot] || s.slot }));
  c.rally = RC.cleanRally(r) || undefined; if (!c.rally) delete c.rally;
  return c;
}

// ---- the Roll chart tab of the cue editor -----------------------------------------------------------------------------------
let rDraft = null, rOrig = "", rCtx = null;
function canon(o) { if (Array.isArray(o)) return "[" + o.map(canon).join(",") + "]"; if (o && typeof o === "object") return "{" + Object.keys(o).sort().map(k => JSON.stringify(k) + ":" + canon(o[k])).join(",") + "}"; return JSON.stringify(o); }
function showCueTab(tab) {
  for (const b of document.querySelectorAll("#cueTabs button")) b.classList.toggle("on", b.dataset.tab === tab);
  $("#cuePane").hidden = tab !== "cue"; $("#rallyPane").hidden = tab !== "rally";
  if (tab === "rally") drawTulipEd();
}
document.querySelectorAll("#cueTabs button").forEach(b => b.onclick = () => showCueTab(b.dataset.tab));
function setSeg(sel, v) { for (const b of document.querySelectorAll(sel + " button")) b.classList.toggle("on", Number(b.dataset.v) === v); }
function rallyOpen(c) {
  rDraft = JSON.parse(JSON.stringify((c && c.rally) || {})); delete rDraft.edited; delete rDraft.at;
  rCtx = { cue: c, lon: c ? c.lon : editing.lon, lat: c ? c.lat : editing.lat, at: c ? c.at : editing.at };
  const st = chartSettings();
  $("#rPath").value = rDraft.path || "";
  setSeg("#rCaution", rDraft.caution != null ? rDraft.caution : c && c.turn === "DANGER" ? c.level || 2 : 0);
  $("#rInfo").value = rDraft.info || ""; $("#rInfo").placeholder = c && c.text ? c.text.toUpperCase().slice(0, 60) : "Printed words, in capitals";
  $("#rSpeed").value = rDraft.speed || ""; $("#rSpeedUnit").textContent = st.units === "km" ? "km/h" : "mph";
  $("#rSection").checked = !!rDraft.section; $("#rSecName").value = rDraft.section ? rDraft.section.name : ""; $("#rSecKind").value = rDraft.section ? rDraft.section.kind : "transit";
  $("#rReset").checked = !!rDraft.reset;
  $("#rCap").value = rDraft.cap != null ? rDraft.cap : "";
  $("#rCapShow").checked = rDraft.capShow != null ? rDraft.capShow : rDraft.path === "offtrack";
  $("#rZone").value = rDraft.zone || ""; $("#rWp").value = rDraft.wp ? rDraft.wp.type : ""; $("#rWpN").value = rDraft.wp && rDraft.wp.n ? rDraft.wp.n : "";
  $("#rB").open = st.layout === "B";
  readForm(); rOrig = canon(rDraft);
  buildChips(st.layout); renderSyms();
  if (c && !c.junc && !(c.rally && c.rally.branches)) fetchJunctions(false, [c]);
}
/** The form into the draft. Only what the person set is stored; everything else stays derived from the line. */
function readForm() {
  const d = rDraft; if (!d) return;
  const path = $("#rPath").value; if (path) d.path = path; else delete d.path;
  const info = $("#rInfo").value.replace(/\s+/g, " ").trim(); if (info) d.info = info; else delete d.info;
  const sp = Math.round(Number($("#rSpeed").value)); if (sp > 0) d.speed = Math.min(300, sp); else delete d.speed;
  if ($("#rSection").checked) d.section = { name: $("#rSecName").value.trim().slice(0, 40), kind: $("#rSecKind").value === "stage" ? "stage" : "transit" }; else delete d.section;
  if ($("#rReset").checked) d.reset = true; else delete d.reset;
  const cap = $("#rCap").value; if (cap !== "" && Number.isFinite(Number(cap))) d.cap = ((Math.round(Number(cap)) % 360) + 360) % 360; else delete d.cap;
  const capDef = d.path === "offtrack", capOn = $("#rCapShow").checked;
  if (d.capShow != null || capOn !== capDef) d.capShow = capOn;
  const z = $("#rZone").value; if (z) d.zone = z; else delete d.zone;
  const wp = $("#rWp").value; if (wp) { d.wp = { type: wp }; const n = Math.round(Number($("#rWpN").value)); if (n > 0) d.wp.n = n; } else delete d.wp;
}
/** undefined when the tab was not touched (the cue keeps what it had), else the cleaned rally object (null for none). */
function rallyResult() {
  if (!rDraft) return undefined;
  readForm();
  if (canon(rDraft) === rOrig) return undefined;
  return RC.cleanRally(Object.assign({}, rDraft, { edited: true }));
}
document.querySelectorAll("#rCaution button").forEach(b => b.onclick = () => { rDraft.caution = Number(b.dataset.v); setSeg("#rCaution", rDraft.caution); drawTulipEd(); });
$("#rallyPane").addEventListener("input", () => drawTulipEd());
$("#rallyPane").addEventListener("change", () => drawTulipEd());
function buildChips(layout) {
  const box = $("#rChips"); box.innerHTML = "";
  const groups = layout === "B" ? RC.CODES_B.map(([g, list]) => [g, list.map(([c, m]) => [c, m])]) : RC.CODES_A.map(([g, list]) => [g, list.map(c => [c, ""])]);
  for (const [, list] of groups) for (const [code, meaning] of list) {
    const b = document.createElement("button"); b.type = "button"; b.textContent = code; if (meaning) b.title = meaning;
    b.onclick = () => { const i = $("#rInfo"); i.value = (i.value.trim() ? i.value.trim() + " " : "") + code; i.focus(); drawTulipEd(); };
    box.appendChild(b);
  }
}
const glyphSvg = (id, size) => RC.primsToSvg(RC.glyphPrims(id, 0.5, 0.5, 9, "#000000"), 10, 10);
(function buildGlyphs() {
  const box = $("#rGlyphs");
  for (const [id, name] of RC.GLYPHS) {
    const b = document.createElement("button"); b.type = "button"; b.title = name; b.innerHTML = glyphSvg(id);
    b.onclick = () => { const list = (rDraft.symbols || []).slice(); if (list.length >= 6) { status("Six symbols at most on one row", true); return; } list.push({ id, slot: $("#rSlot").value }); rDraft.symbols = list; renderSyms(); drawTulipEd(); };
    box.appendChild(b);
  }
})();
function renderSyms() {
  const box = $("#rSyms"); box.innerHTML = "";
  (rDraft.symbols || []).forEach((sym, i) => {
    const g = RC.GLYPH[sym.id]; if (!g) return;
    const b = document.createElement("button"); b.type = "button"; b.title = `Remove ${g.name}`;
    b.innerHTML = `<span>${glyphSvg(sym.id)}</span>${esc(g.name)} · ${{ ra: "right after", la: "left after", rb: "right before", lb: "left before" }[sym.slot]} ×`;
    b.onclick = () => { const list = rDraft.symbols.slice(); list.splice(i, 1); if (list.length) rDraft.symbols = list; else delete rDraft.symbols; renderSyms(); drawTulipEd(); };
    box.appendChild(b);
  });
}
// the tulip editor works in the engine's tulip box scaled to 100: junction at (50, 55), stem from the bottom
const TJ = [50, 55];
const dv = deg => [Math.sin(deg * Math.PI / 180), -Math.cos(deg * Math.PI / 180)];
function reach(p, v) { let t = Infinity; const box = [5, 5, 95, 95]; if (v[0] > 1e-9) t = Math.min(t, (box[2] - p[0]) / v[0]); if (v[0] < -1e-9) t = Math.min(t, (box[0] - p[0]) / v[0]); if (v[1] > 1e-9) t = Math.min(t, (box[3] - p[1]) / v[1]); if (v[1] < -1e-9) t = Math.min(t, (box[1] - p[1]) / v[1]); return Math.max(0, t); }
function edTulip() {
  const c = rCtx && rCtx.cue, line = lineCoords();
  let comp = { exit: 0, arr: 0, dep: 0 }, sugg = [];
  if (line && rCtx) {
    const cum = cumulative(line), at = Number.isFinite(rCtx.at) ? rCtx.at : alongLine(line, [rCtx.lon, rCtx.lat]).at;
    comp = RC.headings(line, cum, at, c && c.turn === "ROUNDABOUT");
    const ex = rDraft.exit != null ? rDraft.exit : c && Number.isFinite(c.angle) && c.turn !== "NOTE" && c.turn !== "DANGER" ? Math.round(c.angle) : comp.exit;
    if (c && c.junc && c.turn !== "ROUNDABOUT") sugg = RC.armsToBranches(c.junc.arms, comp.arr, (comp.arr + ex + 360) % 360);
  }
  const rb = c && c.turn === "ROUNDABOUT" ? Math.max(1, c.exit || 1) : 0;
  const base = c && Number.isFinite(c.angle) && !rb && c.turn !== "NOTE" && c.turn !== "DANGER" ? Math.round(c.angle) : comp.exit;
  return { exit: rDraft.exit != null ? rDraft.exit : base, branches: rDraft.branches || sugg, path: rDraft.path || "track", roundabout: rb, computed: comp.exit };
}
function tipOf(t) {
  const e = t.roundabout ? Math.max(-165, Math.min(165, t.exit)) : t.exit;
  if (Math.abs(e) > 150 && !t.roundabout) { const side = e < 0 ? -1 : 1; return [50 + side * 22, Math.min(43 + 32, 95)]; }
  const rr = t.roundabout ? 12 : 0, v = dv(e), s = [TJ[0] + v[0] * rr, TJ[1] + v[1] * rr], len = Math.min(38, reach(s, v));
  return [s[0] + v[0] * len, s[1] + v[1] * len];
}
function branchEnd(b) { const v = dv(b.deg), len = Math.min(34, reach(TJ, v)); return [TJ[0] + v[0] * len, TJ[1] + v[1] * len]; }
function drawTulipEd() {
  if (!rDraft || $("#rallyPane").hidden) return;
  readForm();
  const t = edTulip(), st = chartSettings();
  const inner = RC.tulipSvg(t, "B", st.colour).replace(/^<svg[^>]*>/, "").replace(/<\/svg>$/, "");
  const tip = tipOf(t);
  let h = t.branches.map((b, i) => { const e = branchEnd(b); return `<circle cx="${e[0].toFixed(1)}" cy="${e[1].toFixed(1)}" r="4.5" fill="rgba(0,0,0,.05)" stroke="#94a3b8" stroke-width=".6" data-h="b${i}"/>`; }).join("");
  h += `<circle cx="${tip[0].toFixed(1)}" cy="${tip[1].toFixed(1)}" r="7.5" fill="rgba(37,99,235,.14)" stroke="#2563eb" stroke-width=".8" data-h="exit" style="cursor:grab"/>`;
  const note = rDraft.exit != null || rDraft.branches ? "" : `<text x="100" y="102" font-size="5" text-anchor="end" fill="#94a3b8">${t.branches.length ? "roads from the map" : "from the line"}</text>`;
  $("#tulipEd").innerHTML = `<rect x="-4" y="-4" width="108" height="108" fill="#fff"/>${inner}${h}${note}`;
}
const ed = $("#tulipEd"); let dragExit = false;
function edPoint(e) { const r = ed.getBoundingClientRect(); return [-4 + (e.clientX - r.left) / r.width * 108, -4 + (e.clientY - r.top) / r.height * 108]; }
const edAngle = p => Math.round(Math.atan2(p[0] - TJ[0], -(p[1] - TJ[1])) * 180 / Math.PI);
function segDistXY(p, a, b) { const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy; const t = l2 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) : 0; return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy); }
ed.addEventListener("pointerdown", e => {
  if (!rDraft) return;
  const p = edPoint(e), t = edTulip(), tip = tipOf(t);
  if (Math.hypot(p[0] - tip[0], p[1] - tip[1]) < 10) { dragExit = true; ed.setPointerCapture(e.pointerId); return; }
  // a road: click it to change its kind; past faint it goes
  const bi = t.branches.findIndex(b => segDistXY(p, TJ, branchEnd(b)) < 4.5);
  if (bi >= 0) {
    const list = t.branches.map(b => ({ ...b })), k = list[bi].kind;
    if (k === "road") list[bi].kind = "track"; else if (k === "track") list[bi].kind = "faint"; else list.splice(bi, 1);
    rDraft.branches = list; drawTulipEd(); return;
  }
  // empty space beside the junction: a road at that angle, but not on the stem or the way out
  if (Math.hypot(p[0] - TJ[0], p[1] - TJ[1]) < 9) return;
  const a = edAngle(p);
  if (Math.abs(RC.turnAngle(180, a)) < 20 || Math.abs(RC.turnAngle(t.exit, a)) < 15) return;
  if (t.branches.length >= 8) { status("Eight roads at most at one junction", true); return; }
  rDraft.branches = t.branches.map(b => ({ ...b })).concat([{ deg: a, kind: "track" }]); drawTulipEd();
});
ed.addEventListener("pointermove", e => { if (!dragExit) return; rDraft.exit = Math.max(-180, Math.min(180, edAngle(edPoint(e)))); drawTulipEd(); });
ed.addEventListener("pointerup", () => { dragExit = false; });
ed.addEventListener("pointercancel", () => { dragExit = false; });
$("#tulipReset").onclick = () => { delete rDraft.exit; delete rDraft.branches; drawTulipEd(); };
$("#tulipFind").onclick = () => { const c = rCtx && rCtx.cue; if (!c) { status("Save the cue first, then find its junction"); return; } delete c.junc; juncCache.delete(juncKey(c)); fetchJunctions(true, [c]); };

// ---- export ---------------------------------------------------------------------------------------------------------------------
let pdfLoading = null;
function loadPdfWriter() {
  if (window.RoadbookChartPdf) return Promise.resolve();
  if (!pdfLoading) pdfLoading = new Promise((ok, no) => { const sc = document.createElement("script"); sc.src = "/chart-pdf.js"; sc.onload = ok; sc.onerror = () => { pdfLoading = null; no(new Error("the PDF writer did not load")); }; document.head.appendChild(sc); });
  return pdfLoading;
}
$("#btnExport").onclick = openExport;
function openExport() {
  for (const sh of document.querySelectorAll(".sheet")) sh.hidden = true;
  fillExport();
  $("#exportSheet").hidden = false;
  exportSummary();
  loadPdfWriter().catch(() => {});
}
function fillExport() {
  const st = chartSettings();
  $("#xLayout").value = st.layout;
  $("#xPreset").innerHTML = Object.entries(RC.PRESETS).filter(([, p]) => p.layout === st.layout).map(([k, p]) => `<option value="${k}">${esc(p.label)}</option>`).join("");
  $("#xPreset").value = st.preset;
  const rr = RC.ROLL_ROWS[st.layout];
  $("#xRoll").innerHTML = [rr[1], rr[0], rr[2]].map(v => `<option value="${v}">${v}${v === rr[0] ? " (a manageable page)" : v === rr[2] ? " (longest page)" : " (short pages)"}</option>`).join("");
  $("#xRoll").value = String(st.rollRows); $("#xRollRow").hidden = !RC.PRESETS[st.preset].roll;
  $("#xUnits").value = st.units; $("#xDecimal").value = st.decimal; $("#xColour").checked = st.colour;
  $("#xDecline").checked = st.decline; $("#xDeclineRow").hidden = st.layout !== "A";
  $("#xTitle").value = S.chart.title || ""; $("#xTitle").placeholder = $("#name").value.trim() || "Roadbook";
  $("#xScale").value = st.scale.toFixed(3);
}
$("#exportSheet").addEventListener("change", e => {
  if (e.target.id === "xLayout") { setLayout($("#xLayout").value); fillExport(); }
  else S.chart = RC.settings({ layout: S.chart.layout, preset: $("#xPreset").value, rollRows: Number($("#xRoll").value), units: $("#xUnits").value, decimal: $("#xDecimal").value, colour: $("#xColour").checked, decline: $("#xDecline").checked, title: $("#xTitle").value.trim(), scale: Number($("#xScale").value) || 1 });
  $("#xRollRow").hidden = !RC.PRESETS[S.chart.preset].roll;
  exportSummary(); if (S.chartOn) scheduleChart(); scheduleAutosave();
});
let exportT = 0;
function exportSummary() { clearTimeout(exportT); exportT = setTimeout(() => {
  const d = chartData(); if (!d) return;
  const out = RC.layout(d.chart, d.st), s = out.stats, P = RC.PRESETS[d.st.preset];
  const len = d.st.units === "km" || d.st.layout === "B" ? `${(s.stripMM / 1000).toFixed(2)} m` : `${(s.stripMM / 25.4 / 12).toFixed(1)} ft`;
  $("#xSummary").innerHTML = `<b>${s.rows} rows</b> · a strip ${len} long${P.roll ? ` · ${out.pages.length} PDF page${out.pages.length > 1 ? "s" : ""} that butt into one roll` : ` · ${s.sheets} sheet${s.sheets > 1 ? "s" : ""}`}. ${esc(P.finish)}.`;
  const issues = d.chart.warnings.concat(out.warnings); if (out.badChars.length) issues.push({ text: `Characters the PDF fonts cannot print (shown as “?”): ${out.badChars.join(" ")}` });
  $("#xWarn").innerHTML = issues.map(w => `<li>${esc(w.text)}</li>`).join("");
}, 120); }
$("#xGo").onclick = async () => {
  const btn = $("#xGo"); btn.disabled = true; btn.textContent = "Building…";
  try {
    await loadPdfWriter();
    const d = chartData(); if (!d) throw new Error("there is no line");
    const out = RC.layout(d.chart, d.st);
    const bytes = await window.RoadbookChartPdf.toPdf(out.pages, { title: d.st.title });
    const file = `${d.st.title} ${d.st.layout === "A" ? "scroll" : "roadbook"} ${d.st.preset.slice(2)}`.replace(/[^\w .-]+/g, "_") + ".pdf";
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" })); a.download = file; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    status(`Saved ${file}: ${out.stats.rows} rows. Print it at 100%.`);
  } catch (e) { status("The PDF could not be made: " + e.message, true); }
  btn.disabled = false; btn.textContent = "Download PDF";
};

// ---- a chart in progress survives a reload, in this browser only -------------------------------------------------------------
const AUTOSAVE = "rb.chart.autosave";
let autosaveT = 0;
function scheduleAutosave() { clearTimeout(autosaveT); autosaveT = setTimeout(autosave, 2000); }
function autosave() {
  const line = lineCoords();
  if (!line || !(S.track || S.cues.some(c => !c.auto || c.rally))) return;
  try {
    const gpx = gpxOut({ rally: true }); if (gpx.length > 4e6) return;     // a very long track would not fit the browser's allowance
    localStorage.setItem(AUTOSAVE, JSON.stringify({ v: 1, t: Date.now(), name: $("#name").value.trim() || "Roadbook", cues: S.cues.length, chartOn: S.chartOn, gpx }));
  } catch {}
}
function forgetAutosave() { clearTimeout(autosaveT); try { localStorage.removeItem(AUTOSAVE); } catch {} }
function offerRestore() {
  let saved = null; try { saved = JSON.parse(localStorage.getItem(AUTOSAVE) || "null"); } catch {}
  if (!saved || typeof saved.gpx !== "string") return;
  $("#restoreBody").textContent = `“${saved.name}”: ${saved.cues} cue${saved.cues === 1 ? "" : "s"}, saved in this browser ${new Date(saved.t).toLocaleString()}. It comes back as a track kept as recorded, with every cue and chart field.`;
  $("#restoreSheet").hidden = false;
  $("#restoreGo").onclick = () => { $("#restoreSheet").hidden = true; loadGpxText(saved.gpx, saved.name, { restore: true }); if (saved.chartOn) $("#btnChart").onclick(); };
  $("#restoreNo").onclick = () => { $("#restoreSheet").hidden = true; forgetAutosave(); };
}

// ---- the URL carries the plan ------------------------------------------------------------------------------------------
function saveHash() {
  const fb = document.getElementById("fb"); if (fb) fb.href = "mailto:wiley@machinenusa.com?subject=Planner%20feedback&body=" + encodeURIComponent("What happened:\n\n\nThe route I was looking at: " + location.href + "\n");
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
window.roadbookPlanner = { map, state: S, reroute, peekPois, inspect, climbCues, gpxOut, loadGpxText, chartData, fetchJunctions, renderChart };   // for the console and tests
init().catch(e => { console.error(e); status("The planner could not start: " + e.message, true); });
})();
