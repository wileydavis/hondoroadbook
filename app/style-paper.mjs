// Generates Fold's "paper" map style on top of the Protomaps basemap layers.
import { layers, namedFlavor } from '@protomaps/basemaps';
import fs from 'fs';

const OUT = '/home/claude/fold/app/src/main/assets/style/';

function palette(night) {
  return night ? {
    paper: '#1c1c1c', earth: '#1c1c1c', water: '#2b3d4f', waterLine: '#3a5068',
    text: '#ececec', halo: '#1c1c1c', townDot: '#f0f0f0', townDotStroke: '#1c1c1c',
    motorway: '#e0683a', motorwayCase: '#6b2a10',
    primary: '#d9a23a', primaryCase: '#5c4010',
    secondary: '#c9b85a', secondaryCase: '#5a5020',
    tertiary: '#9a9a9a', tertiaryCase: '#3a3a3a',
    minor: '#7c7c7c', minorCase: '#2a2a2a',
    service: '#505050', serviceCase: '#2a2a2a',
    dirt: '#c48a52', trail: '#c48a52', cycleway: '#4fb37a', rail: '#8a8a8a',
    boundary: '#9a8fb0',
  } : {
    paper: '#f4efe4', earth: '#f4efe4', water: '#a9cbe8', waterLine: '#7fa9cf',
    text: '#1d1d1d', halo: '#f4efe4', townDot: '#222222', townDotStroke: '#ffffff',
    motorway: '#e4603a', motorwayCase: '#8f2f12',
    primary: '#f3b43b', primaryCase: '#7d5a16',
    secondary: '#f8e27a', secondaryCase: '#8a7a2e',
    tertiary: '#ffffff', tertiaryCase: '#7a7a7a',
    minor: '#ffffff', minorCase: '#9a9a9a',
    service: '#f7f4ee', serviceCase: '#b8b4ac',
    dirt: '#8b5a2b', trail: '#8b5a2b', cycleway: '#1f8a4c', rail: '#7a7a7a',
    boundary: '#8a6fae',
  };
}

// widths: [zoom, px] pairs, exponential 1.6
const W = (pairs) => ['interpolate', ['exponential', 1.6], ['zoom'], ...pairs.flat()];

function roadLayers(P) {
  const kd = (...v) => ['in', ['get', 'kind_detail'], ['literal', v]];
  const notLink = ['!', ['has', 'is_link']];
  const link = ['has', 'is_link'];
  const L = (id, filter, paint, extra = {}) => ({ id, type: 'line', source: 'protomaps', 'source-layer': 'roads', filter, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint, ...extra });

  // fills share the casing underneath (gap-width keeps the casing outside the fill)
  const motorwayW = W([[4, 0.9], [8, 1.8], [11, 3.2], [14, 7], [18, 22]]);
  const primaryW  = W([[5, 0.6], [8, 1.3], [11, 2.4], [14, 5.5], [18, 18]]);
  const secondaryW = W([[7, 0.5], [10, 1.2], [13, 3], [18, 15]]);
  const tertiaryW = W([[9, 0.5], [12, 1.6], [14, 3], [18, 13]]);
  const minorW = W([[11, 0.4], [13, 1.2], [15, 3], [18, 12]]);
  const serviceW = W([[13, 0.3], [15, 1.2], [18, 7]]);
  const linkW = W([[9, 0.5], [13, 1.5], [18, 9]]);
  const casing = (w, dz = 0) => ['interpolate', ['exponential', 1.6], ['zoom'], 6, 0.6, 12, 1.0, 18, 2.2];

  const isMotorway = ['all', ['==', ['get', 'kind'], 'highway'], notLink];
  const isPrimary = ['all', ['any', kd('trunk', 'primary')], notLink];
  const isSecondary = ['all', kd('secondary'), notLink];
  const isTertiary = ['all', kd('tertiary'), notLink];
  const isMinor = ['all', ['==', ['get', 'kind'], 'minor_road'], kd('residential', 'unclassified', 'living_street', 'road')];
  const isService = ['all', ['==', ['get', 'kind'], 'minor_road'], kd('service')];
  const isTrack = kd('track');
  const isCycleway = kd('cycleway');
  const isTrail = ['all', ['==', ['get', 'kind'], 'path'], ['!', kd('track', 'sidewalk', 'crossing', 'steps', 'footway', 'cycleway')]];
  const isSidewalk = kd('sidewalk', 'crossing', 'steps', 'footway');
  const isLink = ['all', link, ['in', ['get', 'kind'], ['literal', ['highway', 'major_road']]]];

  const cas = (id, f, color, gap, minzoom) => L(id, f, { 'line-color': color, 'line-gap-width': gap, 'line-width': casing() }, minzoom ? { minzoom } : {});
  const fill = (id, f, color, width, minzoom, extra) => L(id, f, { 'line-color': color, 'line-width': width, ...(extra || {}) }, minzoom ? { minzoom } : {});

  return [
    // trails and dirt first, under everything else
    fill('fold_sidewalk', isSidewalk, P.serviceCase, W([[14, 0.3], [18, 1.4]]), 14, { 'line-dasharray': [1, 2] }),
    fill('fold_cycleway', isCycleway, P.cycleway, W([[11, 0.6], [14, 1.4], [18, 3.4]]), 11, { 'line-dasharray': [3, 1.5] }),
    fill('fold_trail', isTrail, P.trail, W([[11, 0.4], [14, 1.0], [18, 2.6]]), 11, { 'line-dasharray': [1.5, 2.5] }),
    fill('fold_track', isTrack, P.dirt, W([[10, 0.5], [13, 1.2], [15, 2.2], [18, 5]]), 10, { 'line-dasharray': [3, 1.6] }),
    // service
    cas('fold_service_casing', isService, P.serviceCase, serviceW, 14),
    fill('fold_service', isService, P.service, serviceW, 13),
    // minor streets
    cas('fold_minor_casing', isMinor, P.minorCase, minorW, 12),
    fill('fold_minor', isMinor, P.minor, minorW, 11),
    // links (ramps)
    cas('fold_link_casing', isLink, P.primaryCase, linkW, 11),
    fill('fold_link', isLink, P.primary, linkW, 9),
    // tertiary
    cas('fold_tertiary_casing', isTertiary, P.tertiaryCase, tertiaryW, 10),
    fill('fold_tertiary', isTertiary, P.tertiary, tertiaryW, 9),
    // secondary
    cas('fold_secondary_casing', isSecondary, P.secondaryCase, secondaryW, 8),
    fill('fold_secondary', isSecondary, P.secondary, secondaryW, 7),
    // primary / trunk
    cas('fold_primary_casing', isPrimary, P.primaryCase, primaryW, 7),
    fill('fold_primary', isPrimary, P.primary, primaryW, 5),
    // motorway
    cas('fold_motorway_casing', isMotorway, P.motorwayCase, motorwayW, 6),
    fill('fold_motorway', isMotorway, P.motorway, motorwayW, 4),
    // rail on top of roads, as a thin hatched line
    L('fold_rail', ['==', ['get', 'kind'], 'rail'], { 'line-color': P.rail, 'line-width': W([[6, 0.4], [12, 1.0], [18, 3]]), 'line-dasharray': [4, 2], 'line-opacity': 0.7 }, { minzoom: 7, layout: { 'line-cap': 'butt', 'line-join': 'miter' } }),
  ];
}

function placeLayers(P) {
  const name = ['coalesce', ['get', 'name:en'], ['get', 'name']];
  const rank = ['coalesce', ['get', 'population_rank'], 0];
  const big = ['>=', rank, 8];     // ~ >100k
  const mid = ['>=', rank, 6];     // ~ >10k
  return [
    // dots when zoomed out: the paper-map convention
    {
      id: 'fold_town_dot', type: 'circle', source: 'protomaps', 'source-layer': 'places', maxzoom: 12,
      filter: ['==', ['get', 'kind'], 'locality'],
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 4, ['case', big, 3.5, mid, 2.6, 1.8], 10, ['case', big, 6, mid, 4.5, 3]],
        'circle-color': P.townDot, 'circle-stroke-color': P.townDotStroke, 'circle-stroke-width': 1.2,
      },
    },
    {
      id: 'fold_town_label', type: 'symbol', source: 'protomaps', 'source-layer': 'places',
      filter: ['==', ['get', 'kind'], 'locality'],
      layout: {
        'symbol-sort-key': ['-', 0, rank],
        'text-field': name,
        'text-font': ['case', mid, ['literal', ['Noto Sans Medium']], ['literal', ['Noto Sans Regular']]],
        'text-size': ['interpolate', ['linear'], ['zoom'], 4, ['case', big, 22, mid, 19, 16], 9, ['case', big, 28, mid, 23, 19], 14, ['case', big, 34, mid, 28, 23]],
        'text-transform': ['case', big, 'uppercase', 'none'],
        'text-letter-spacing': ['case', big, 0.08, 0.02],
        'text-anchor': ['step', ['zoom'], 'left', 12, 'center'],
        'text-offset': ['step', ['zoom'], ['literal', [0.7, 0]], 12, ['literal', [0, 0]]],
        'text-justify': 'left',
        'text-max-width': 8,
        'text-padding': 4,
      },
      paint: { 'text-color': P.text, 'text-halo-color': P.halo, 'text-halo-width': 2.2, 'text-halo-blur': 0.4 },
    },
    {
      id: 'fold_subplace_label', type: 'symbol', source: 'protomaps', 'source-layer': 'places', minzoom: 12,
      filter: ['in', ['get', 'kind'], ['literal', ['neighbourhood', 'macrohood']]],
      layout: { 'text-field': name, 'text-font': ['Noto Sans Italic'], 'text-size': ['interpolate', ['linear'], ['zoom'], 12, 18, 16, 22], 'text-max-width': 8 },
      paint: { 'text-color': P.text, 'text-opacity': 0.75, 'text-halo-color': P.halo, 'text-halo-width': 2 },
    },
    {
      id: 'fold_region_label', type: 'symbol', source: 'protomaps', 'source-layer': 'places', maxzoom: 9,
      filter: ['==', ['get', 'kind'], 'region'],
      layout: { 'text-field': ['step', ['zoom'], ['coalesce', ['get', 'ref:en'], ['get', 'ref'], name], 6, name], 'text-font': ['Noto Sans Medium'], 'text-transform': 'uppercase', 'text-letter-spacing': 0.15,
        'text-size': ['interpolate', ['linear'], ['zoom'], 3, 15, 6, 21, 8, 26] },
      paint: { 'text-color': P.text, 'text-opacity': 0.55, 'text-halo-color': P.halo, 'text-halo-width': 1.5 },
    },
  ];
}

for (const night of [false, true]) {
  const P = palette(night);
  const base = layers('protomaps', namedFlavor(night ? 'dark' : 'light'), { lang: 'en' });
  const out = [];
  let roadsInserted = false;
  for (const l of base) {
    if (l.id === 'background') { l.paint['background-color'] = P.paper; out.push(l); continue; }
    if (l.id === 'earth') { l.paint['fill-color'] = P.earth; out.push(l); continue; }
    if (l.id === 'water') { l.paint['fill-color'] = P.water; out.push(l); continue; }
    if (l.id === 'water_stream' || l.id === 'water_river') { l.paint['line-color'] = P.waterLine; out.push(l); continue; }
    if (l.id === 'boundaries' || l.id === 'boundaries_country') {
      // only national and state lines; county and city limits just clutter a road map
      l.filter = ['<=', ['get', 'kind_detail'], 4];
      l.paint['line-color'] = P.boundary; l.paint['line-width'] = W([[3, 0.6], [8, 1.2], [14, 2.0]]); l.paint['line-dasharray'] = [4, 2]; l.paint['line-opacity'] = 0.8; out.push(l); continue;
    }
    if (l.id.startsWith('places_')) continue;                       // replaced
    if (l.id.startsWith('roads_')) {
      const keep = ['roads_runway', 'roads_taxiway', 'roads_pier', 'roads_shields', 'roads_labels_minor', 'roads_labels_major', 'roads_oneway'];
      if (!keep.includes(l.id)) { if (!roadsInserted && !l.id.startsWith('roads_runway') && !l.id.startsWith('roads_taxiway')) { out.push(...roadLayers(P)); roadsInserted = true; } continue; }
      if (l.id.startsWith('roads_labels')) {
        l.paint = { ...(l.paint || {}), 'text-color': P.text, 'text-halo-color': P.halo, 'text-halo-width': 2 };
        l.layout['text-size'] = l.id === 'roads_labels_major' ? ['interpolate', ['linear'], ['zoom'], 11, 17, 15, 22, 18, 27] : ['interpolate', ['linear'], ['zoom'], 15, 17, 18, 23];
      }
      if (l.id === 'roads_shields') { l.minzoom = 7; l.layout['text-size'] = 13; l.layout['icon-size'] = 1.3; }
      out.push(l); continue;
    }
    if (l.id === 'water_label_lakes' || l.id === 'water_waterway_label' || l.id === 'water_label_ocean' || l.id === 'earth_label_islands' || l.id === 'address_label' || l.id === 'pois') {
      if (l.paint) { l.paint['text-halo-color'] = P.halo; l.paint['text-halo-width'] = 2; }
      const ts = l.layout && l.layout['text-size'];
      if (typeof ts === 'number') l.layout['text-size'] = ts * 1.7; else if (ts) l.layout['text-size'] = ['*', ts, 1.7];
    }
    out.push(l);
  }
  out.push(...placeLayers(P));
  // sanity: every layer has a unique id
  const ids = new Set(); for (const l of out) { if (ids.has(l.id)) throw new Error('dup ' + l.id); ids.add(l.id); }
  fs.writeFileSync(OUT + `layers_${night ? 'dark' : 'light'}.json`, JSON.stringify(out));
  console.log((night ? 'dark' : 'light'), out.length, 'layers');
}
