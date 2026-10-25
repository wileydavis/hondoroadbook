// render2.mjs spec.json out.png — renders the real pack tiles with the app's style, plus route lines under labels.
import mbgl from '@maplibre/maplibre-gl-native';
import fs from 'fs'; import sharp from 'sharp';
import { PMTiles } from 'pmtiles';
const [,, specFile, out] = process.argv;
const spec = JSON.parse(fs.readFileSync(specFile));
class NodeFile{constructor(p){this.p=p;this.fd=fs.openSync(p,'r');}getKey(){return this.p}async getBytes(o,l){const b=Buffer.alloc(l);fs.readSync(this.fd,b,0,l,o);return {data:b.buffer.slice(b.byteOffset,b.byteOffset+l)};}}
const archive = new PMTiles(new NodeFile(spec.pm || '/home/claude/data/mt/tiles.pmtiles'));
const ASSETS = '/home/claude/fold/app/src/main/assets/';
const flavor = spec.flavor || 'light';
const layers = JSON.parse(fs.readFileSync(ASSETS + 'style/layers_' + flavor + '.json'));
const hdr = await archive.getHeader();
const sources = { protomaps: { type: 'vector', tiles: ['fold://t/{z}/{x}/{y}'], minzoom: hdr.minZoom, maxzoom: hdr.maxZoom } };
const extra = [];
(spec.lines || []).forEach((l, i) => {
  sources['line' + i] = { type: 'geojson', data: { type: 'Feature', geometry: { type: 'LineString', coordinates: l.coords } } };
  if (l.casing !== false) extra.push({ id: 'casing' + i, type: 'line', source: 'line' + i, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': l.casingColor || '#ffffff', 'line-width': (l.width || 6) + 3, 'line-opacity': l.casingOpacity ?? 0.9 } });
  extra.push({ id: 'line' + i, type: 'line', source: 'line' + i, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': l.color || '#2563EB', 'line-width': l.width || 6, 'line-opacity': l.opacity ?? 1, ...(l.dash ? { 'line-dasharray': l.dash } : {}) } });
});
const firstSymbol = layers.findIndex(l => l.type === 'symbol');
const all = firstSymbol < 0 ? [...layers, ...extra] : [...layers.slice(0, firstSymbol), ...extra, ...layers.slice(firstSymbol)];
const style = { version: 8, glyphs: 'asset://fonts/{fontstack}/{range}.pbf', sprite: 'asset://sprites/' + flavor, sources, layers: all };
const map = new mbgl.Map({ request: (req, cb) => { (async () => { try {
  const u = req.url;
  if (u.startsWith('fold://t/')) { const [z,x,y] = u.slice(9).split('/').map(Number); const t = await archive.getZxy(z,x,y); cb(null, { data: t ? Buffer.from(t.data) : Buffer.alloc(0) }); return; }
  if (u.startsWith('asset://')) { cb(null, { data: fs.readFileSync(ASSETS + decodeURIComponent(u.slice(8))) }); return; }
  cb(new Error('unhandled ' + u)); } catch (e) { cb(e); } })(); }, ratio: spec.ratio || 2 });
map.load(style);
const w = spec.width || 540, h = spec.height || 1200, ratio = spec.ratio || 2;
map.render({ zoom: spec.zoom, center: spec.center, bearing: spec.bearing || 0, pitch: spec.pitch || 0, width: w, height: h }, (err, buf) => {
  if (err) { console.error(err); process.exit(1); }
  sharp(buf, { raw: { width: w * ratio, height: h * ratio, channels: 4 } }).png().toFile(out).then(() => { console.log('wrote', out); process.exit(0); });
});
