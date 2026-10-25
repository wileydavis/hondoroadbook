// Builds Roadbook screens as DOM, 1:1 with the app's layout (dp units), over real map renders.
const LIGHT = { paper:'#F7F4EE', ink:'#1B1B1B', soft:'#5A5A5A', card:'#FFFFFF', accent:'#D8502F', track:'#9333EA', bar:'#E5E1D8', button:'#1B1B1B', btext:'#FFFFFF' };
const DARK  = { paper:'#121212', ink:'#F2F2F2', soft:'#A8A8A8', card:'#1E1E1E', accent:'#FF7A59', track:'#C084FC', bar:'#333333', button:'#F2F2F2', btext:'#111111' };
const UNIT = {
  STRAIGHT:'M50,92 L50,24', RIGHT:'M32,92 L32,48 Q32,32 48,32 L74,32', LEFT:'M68,92 L68,48 Q68,32 52,32 L26,32',
  SLIGHT_RIGHT:'M42,92 L42,56 Q42,44 50,36 L70,18', SLIGHT_LEFT:'M58,92 L58,56 Q58,44 50,36 L30,18',
  KEEP_RIGHT:'M46,92 L46,60 Q46,48 52,40 L62,22', KEEP_LEFT:'M54,92 L54,60 Q54,48 48,40 L38,22',
  SHARP_RIGHT:'M36,92 L36,40 Q36,22 50,30 L72,52', SHARP_LEFT:'M64,92 L64,40 Q64,22 50,30 L28,52',
  UTURN:'M66,92 L66,46 A16,22 0 0 0 34,46 L34,70', ROUNDABOUT:'M50,92 L50,72 A18,18 0 1 1 50,36 L50,14',
  NOTE:'M50,92 L50,46', DANGER:'M50,92 L50,46', ARRIVE:'M50,92 L50,30',
};
const SPOKEN = { STRAIGHT:'Straight on', LEFT:'Left', RIGHT:'Right', SLIGHT_LEFT:'Slight left', SLIGHT_RIGHT:'Slight right', SHARP_LEFT:'Sharp left', SHARP_RIGHT:'Sharp right', KEEP_LEFT:'Keep left', KEEP_RIGHT:'Keep right', UTURN:'U-turn', NOTE:'Note', DANGER:'Danger', ROUNDABOUT:'Roundabout' };
const NS = 'http://www.w3.org/2000/svg';
function svgEl(tag, attrs) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; }

/** The HUD arrow: a bar shaped like the turn that fills toward the arrowhead. Same geometry as ArrowView. */
function arrow(turn, progress, size, T, level = 0) {
  const svg = svgEl('svg', { viewBox: '0 0 100 100', width: size, height: size });
  const d = UNIT[turn] || UNIT.STRAIGHT;
  svg.appendChild(svgEl('path', { d, fill: 'none', stroke: T.bar, 'stroke-width': 13, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }));
  const fill = svgEl('path', { d, fill: 'none', stroke: T.accent, 'stroke-width': 13, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'pathLength': 100, 'stroke-dasharray': `${progress * 100} 1000` });
  if (progress > 0) svg.appendChild(fill);
  // tangent at the end for the head
  const m = svgEl('path', { d }); svg.appendChild(m); const len = m.getTotalLength(); const e = m.getPointAtLength(len); const b = m.getPointAtLength(Math.max(0, len - 1)); m.remove();
  let tx = e.x - b.x, ty = e.y - b.y; const n = Math.hypot(tx, ty) || 1; tx /= n; ty /= n; const nx = -ty, ny = tx;
  if (turn === 'NOTE' || turn === 'DANGER') {
    const x = e.x, y = e.y - 17, r = 19;
    if (turn === 'DANGER') svg.appendChild(svgEl('path', { d: `M${x},${y - r} L${x + r * 1.1},${y + r * 0.8} L${x - r * 1.1},${y + r * 0.8} Z`, fill: T.accent, stroke: T.card, 'stroke-width': 3 }));
    else svg.appendChild(svgEl('circle', { cx: x, cy: y, r, fill: T.ink, stroke: T.card, 'stroke-width': 3 }));
    const t = svgEl('text', { x, y: y + (turn === 'DANGER' ? r * 0.75 : r * 0.45), 'text-anchor': 'middle', 'font-size': r * 1.3, 'font-weight': 700, fill: T.card, 'font-family': 'Inter, sans-serif' }); t.textContent = turn === 'DANGER' ? '!' : 'i'; svg.appendChild(t);
    if (turn === 'DANGER' && level > 1) { const t2 = svgEl('text', { x: x + r * 1.4, y: y + r * 0.7, 'font-size': r * 0.5, 'font-weight': 700, fill: T.ink, 'font-family': 'Inter, sans-serif' }); t2.textContent = '!'.repeat(Math.min(level, 3)); svg.appendChild(t2); }
    return svg;
  }
  if (turn === 'ARRIVE') {
    svg.appendChild(svgEl('rect', { x: e.x - 2, y: e.y - 22, width: 4, height: 22, fill: T.ink }));
    for (let r = 0; r < 3; r++) for (let c = 0; c < 4; c++) if ((r + c) % 2 === 0) svg.appendChild(svgEl('rect', { x: e.x + 2 + c * 6, y: e.y - 22 + r * 6, width: 6, height: 6, fill: T.ink }));
    return svg;
  }
  const hl = 20, hw = 15, tip = [e.x + tx * hl * 0.85, e.y + ty * hl * 0.85], base = [e.x - tx * hl * 0.15, e.y - ty * hl * 0.15];
  svg.appendChild(svgEl('path', { d: `M${tip[0]},${tip[1]} L${base[0] + nx * hw},${base[1] + ny * hw} L${base[0] - nx * hw},${base[1] - ny * hw} Z`, fill: T.accent, stroke: T.card, 'stroke-width': 3, 'stroke-linejoin': 'round' }));
  return svg;
}

function compass(bearing, size, T) {
  const s = svgEl('svg', { viewBox: '0 0 40 40', width: size, height: size }); const r = 18;
  s.appendChild(svgEl('circle', { cx: 20, cy: 20, r, fill: 'none', stroke: T.soft, 'stroke-width': 1.5 }));
  const g = svgEl('g', { transform: `rotate(${-bearing} 20 20)` });
  g.appendChild(svgEl('path', { d: `M20,${20 - r * 0.85} L${20 - r * 0.22},20 L${20 + r * 0.22},20 Z`, fill: T.accent }));
  g.appendChild(svgEl('path', { d: `M20,${20 + r * 0.85} L${20 - r * 0.22},20 L${20 + r * 0.22},20 Z`, fill: T.soft }));
  const t = svgEl('text', { x: 20, y: 20 - r * 0.95 - 1, 'text-anchor': 'middle', 'font-size': r * 0.55, 'font-weight': 700, fill: T.ink, 'font-family': 'Inter, sans-serif' }); t.textContent = 'N'; g.appendChild(t);
  s.appendChild(g); return s;
}

// web mercator in dp: MapLibre world = 512 * 2^zoom
function project(lon, lat, scene, w, h) {
  const world = 512 * Math.pow(2, scene.zoom);
  const px = p => [(p[0] + 180) / 360 * world, (1 - Math.log(Math.tan(p[1] * Math.PI / 180) + 1 / Math.cos(p[1] * Math.PI / 180)) / Math.PI) / 2 * world];
  const c = px(scene.center), q = px([lon, lat]); const dx = q[0] - c[0], dy = q[1] - c[1]; const b = (scene.bearing || 0) * Math.PI / 180;
  return [w / 2 + dx * Math.cos(b) + dy * Math.sin(b), h / 2 - dx * Math.sin(b) + dy * Math.cos(b)];
}

const ICONS = {
  search: 'M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 14 15.5l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z',
  track: 'M4 18l6-12 4 8 2-4 4 8z', maps: 'M3 5l6-2 6 2 6-2v16l-6 2-6-2-6 2zM9 5v14M15 7v14',
  night: 'M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9z', locate: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm9 3h-2.1A7 7 0 0 0 13 5.1V3h-2v2.1A7 7 0 0 0 5.1 11H3v2h2.1A7 7 0 0 0 11 18.9V21h2v-2.1a7 7 0 0 0 5.9-5.9H21v-2z',
  rec: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12zm0 2.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z',
  lock: 'M7 10V7a5 5 0 0 1 10 0v3h1a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM9 10h6V7a3 3 0 0 0-6 0zM6 12v8h12v-8z',
  unlock: 'M7 10V7a5 5 0 0 1 9.6-1.9l-1.9.7A3 3 0 0 0 9 7v3h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM6 12v8h12v-8z',
  sound: 'M3 9v6h4l5 5V4L7 9zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z',
  cue: 'M6 20V4h2v7l5-5 1.4 1.4L11.8 10H20v2h-8.2l2.6 2.6L13 16 8 11v9z',
};
function icon(name, size, color) { const s = svgEl('svg', { viewBox: '0 0 24 24', width: size, height: size }); s.appendChild(svgEl('path', { d: ICONS[name], fill: name === 'maps' || name === 'track' ? 'none' : color, stroke: color, 'stroke-width': name === 'maps' || name === 'track' ? 2 : 0, 'stroke-linejoin': 'round' })); return s; }

function el(tag, style, text) { const e = document.createElement(tag); Object.assign(e.style, style); if (text != null) e.textContent = text; return e; }
function round(name, T, { tint } = {}) { const b = el('div', { width: '46px', height: '46px', borderRadius: '23px', background: T.card, display: 'grid', placeItems: 'center', boxShadow: '0 2px 6px rgba(0,0,0,.18)', flex: 'none' }); b.appendChild(icon(name, 24, tint || T.ink)); return b; }
function bigButton(text, T, { accent, width, flex } = {}) { const b = el('div', { height: '48px', borderRadius: '14px', background: accent ? T.accent : T.button, color: accent ? '#fff' : T.btext, fontWeight: 700, fontSize: '15px', letterSpacing: '.06em', display: 'grid', placeItems: 'center', padding: '0 14px', width: width ? width + 'px' : undefined, flex: flex || 'none' }, text); return b; }
function card(T, style) { return el('div', Object.assign({ background: T.card, borderRadius: '18px', boxShadow: '0 3px 10px rgba(0,0,0,.16)' }, style)); }

/** Renders a scene into container (w×h dp). */
export function screen(container, sc) {
  const T = sc.dark ? DARK : LIGHT; const w = sc.w || 412, h = sc.h || 915;
  Object.assign(container.style, { position: 'relative', width: w + 'px', height: h + 'px', overflow: 'hidden', background: T.paper, fontFamily: 'Inter, Roboto, sans-serif', color: T.ink });
  container.innerHTML = '';
  const map = el('img', { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }); map.src = sc.map; container.appendChild(map);
  const over = el('div', { position: 'absolute', inset: 0 }); container.appendChild(over);
  if (sc.fog) { for (const f of sc.fog) { const d = el('div', { position: 'absolute', left: f[0] + 'px', top: f[1] + 'px', width: f[2] + 'px', height: f[3] + 'px', background: 'rgba(120,110,95,.28)' }); over.appendChild(d); } }
  const P = (ll) => project(ll[0], ll[1], sc.geo, w, h);
  // cue markers
  for (const c of sc.cues || []) { const [x, y] = P(c.ll); const m = el('div', { position: 'absolute', left: (x - 11) + 'px', top: (y - 11) + 'px', width: '22px', height: '22px', borderRadius: '11px', background: c.turn === 'DANGER' ? T.accent : T.ink, border: '2px solid #fff', boxSizing: 'border-box' }); over.appendChild(m);
    if (c.label) { const l = el('div', { position: 'absolute', left: (x - 60) + 'px', top: (y + 12) + 'px', width: '120px', textAlign: 'center', fontSize: '12px', fontWeight: 600, color: T.ink, textShadow: `0 0 3px ${T.paper}, 0 0 3px ${T.paper}, 0 0 3px ${T.paper}` }, c.label); over.appendChild(l); } }
  // pins
  for (const p of sc.pins || []) { const [x, y] = P(p.ll); const d = el('div', { position: 'absolute', left: (x - 12) + 'px', top: (y - 12) + 'px', width: '24px', height: '24px', borderRadius: '12px', background: p.color || T.accent, border: '3px solid #fff', boxSizing: 'border-box', boxShadow: '0 2px 4px rgba(0,0,0,.3)' }); over.appendChild(d); }
  // lead line + puck
  if (sc.puck) { const [x, y] = sc.puck.ll ? P(sc.puck.ll) : [w / 2, h * 0.7];
    if (sc.lead) { const [tx, ty] = P(sc.lead); const s = svgEl('svg', { width: w, height: h, style: 'position:absolute;left:0;top:0' }); s.appendChild(svgEl('line', { x1: x, y1: y, x2: tx, y2: ty, stroke: T.ink, 'stroke-width': 2.5, 'stroke-opacity': .45, 'stroke-dasharray': '5 5', 'stroke-linecap': 'round' })); over.appendChild(s); }
    const s = svgEl('svg', { viewBox: '0 0 44 44', width: 44, height: 44, style: `position:absolute;left:${x - 22}px;top:${y - 22}px;transform:rotate(${sc.puck.rot || 0}deg)` });
    s.appendChild(svgEl('path', { d: 'M22,3.5 L35.2,37 L22,29 L8.8,37 Z', fill: T.accent, stroke: '#fff', 'stroke-width': 3, 'stroke-linejoin': 'round' })); over.appendChild(s); }
  const inset = sc.insetTop ?? 40;
  // HUD
  if (sc.hud) { const hud = card(T, { position: 'absolute', left: '12px', right: w > 700 ? undefined : '12px', width: w > 700 ? '520px' : undefined, top: (inset + 16) + 'px', display: 'flex', alignItems: 'center', padding: '6px 18px 6px 10px', gap: '0' });
    hud.appendChild(arrow(sc.hud.turn, sc.hud.progress, 104, T, sc.hud.level));
    const col = el('div', { flex: 1, paddingLeft: '12px', minWidth: 0 });
    col.appendChild(el('div', { fontSize: '40px', fontWeight: 700, lineHeight: 1, letterSpacing: '-.01em' }, sc.hud.dist));
    col.appendChild(el('div', { fontSize: '18px', color: T.soft, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', marginTop: '4px' }, sc.hud.street));
    hud.appendChild(col); const cp = compass(sc.geo.bearing || 0, 40, T); cp.style.marginLeft = '8px'; cp.style.flex = 'none'; hud.appendChild(cp); over.appendChild(hud); }
  if (sc.off) { const o = el('div', { position: 'absolute', left: '12px', right: '12px', top: (inset + 16) + 'px', background: T.accent, color: '#fff', borderRadius: '14px', display: 'flex', alignItems: 'center', padding: '8px 8px 8px 16px', boxShadow: '0 3px 10px rgba(0,0,0,.16)' });
    o.appendChild(el('div', { flex: 1, fontSize: '20px', fontWeight: 700 }, sc.off)); const b = el('div', { height: '40px', borderRadius: '14px', background: T.button, color: T.btext, fontSize: '13px', fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 14px' }, 'REROUTE'); o.appendChild(b); over.appendChild(o); }
  // scale bar
  if (sc.scale !== false) { const sb = el('div', { position: 'absolute', left: '20px', bottom: (sc.scaleBottom || 84) + 'px', height: '22px', display: 'flex', alignItems: 'flex-end', gap: '6px', fontSize: '12px', fontWeight: 600, color: T.ink, textShadow: `0 0 3px ${T.paper}, 0 0 3px ${T.paper}` });
    const bar = el('div', { width: (sc.scalePx || 70) + 'px', height: '10px', borderLeft: `2px solid ${T.ink}`, borderRight: `2px solid ${T.ink}`, borderBottom: `2px solid ${T.ink}`, boxShadow: `0 0 0 1px ${T.paper}` }); sb.appendChild(bar); sb.appendChild(el('span', {}, sc.scaleLabel || '500 ft')); over.appendChild(sb); }
  // bottom panels
  const bottom = (sc.bottom || 'idle');
  if (bottom === 'idle') { const p = el('div', { position: 'absolute', left: 0, right: 0, bottom: 0, display: 'flex', alignItems: 'center', gap: '10px', padding: '12px 14px 20px' });
    p.appendChild(round('search', T)); p.appendChild(round('track', T)); p.appendChild(round('maps', T)); if (sc.roadbook !== false) p.appendChild(round('rec', T, { tint: sc.recording ? T.accent : T.ink }));
    p.appendChild(el('div', { flex: 1 })); p.appendChild(round(sc.locked ? 'lock' : 'unlock', T, { tint: sc.locked ? T.accent : T.ink })); p.appendChild(round('night', T)); p.appendChild(round('locate', T)); over.appendChild(p); }
  if (bottom === 'nav') { const p = el('div', { position: 'absolute', left: 0, right: 0, bottom: 0, display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px 20px' });
    const nc = card(T, { flex: '1 1 0', minWidth: 0, height: '48px', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 12px 0 14px', overflow: 'hidden' }); nc.appendChild(el('div', { fontSize: '15px', fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', lineHeight: 1.15 }, sc.remaining || '17.4 mi · 31 min')); nc.appendChild(el('div', { fontSize: '13px', color: T.soft, whiteSpace: 'nowrap', lineHeight: 1.15, marginTop: '2px' }, sc.speed || '48 mph')); p.appendChild(nc);
    if (sc.navCue) p.appendChild(round('cue', T)); p.appendChild(round('sound', T)); p.appendChild(bigButton('END', T, { width: 64 })); over.appendChild(p);
    const side = el('div', { position: 'absolute', right: '20px', bottom: (sc.recording ? 152 : 104) + 'px', display: 'flex', flexDirection: 'column', gap: '12px' }); side.appendChild(round(sc.locked ? 'lock' : 'unlock', T, { tint: sc.locked ? T.accent : T.ink })); side.appendChild(round('night', T)); side.appendChild(round('locate', T)); over.appendChild(side); }
  if (bottom === 'compare') { const p = el('div', { position: 'absolute', left: 0, right: 0, bottom: 0, padding: '12px 20px 20px' });
    const rows = card(T, { padding: '4px 0', marginBottom: '8px' });
    for (const r of sc.rows) { const row = el('div', { display: 'flex', alignItems: 'center', padding: '8px 16px', gap: '12px', background: r.sel ? (sc.dark ? '#2a2a2a' : '#F3EFE6') : 'transparent' }); row.appendChild(el('div', { width: '28px', height: '6px', borderRadius: '3px', background: r.color })); row.appendChild(el('div', { flex: 1, fontSize: '16px', fontWeight: r.sel ? 700 : 500 }, r.name)); row.appendChild(el('div', { fontSize: '15px', color: T.soft }, r.info)); rows.appendChild(row); }
    p.appendChild(rows); const b = el('div', { display: 'flex', gap: '8px' }); b.appendChild(bigButton('MODES', T, { flex: 1 })); b.appendChild(bigButton('SAVE', T, { flex: 1 })); b.appendChild(bigButton('GO', T, { accent: true, flex: 2 })); b.appendChild(bigButton('✕', T, { width: 48 })); p.appendChild(b); over.appendChild(p); }
  if (bottom === 'edit') { const p = el('div', { position: 'absolute', left: 0, right: 0, bottom: 0, padding: '12px 14px 20px' });
    p.appendChild(card(T, { padding: '12px', fontSize: '15px', marginBottom: '8px', lineHeight: 1.35 })).textContent = sc.editLabel; const b = el('div', { display: 'flex', gap: '8px' }); b.appendChild(bigButton('LIST', T, { flex: 1 })); b.appendChild(bigButton('SAVE', T, { flex: 1 })); b.appendChild(bigButton('FOLLOW', T, { accent: true, flex: 2 })); b.appendChild(bigButton('✕', T, { width: 48 })); p.appendChild(b); over.appendChild(p); }
  if (bottom === 'pin') { const p = el('div', { position: 'absolute', left: 0, right: 0, bottom: 0, padding: '12px 20px 20px' });
    p.appendChild(card(T, { padding: '12px', fontSize: '16px', marginBottom: '8px' })).textContent = sc.pinLabel; const b = el('div', { display: 'flex', gap: '8px' }); b.appendChild(bigButton('ROUTE HERE', T, { accent: true, flex: 2 })); b.appendChild(bigButton('FROM', T, { flex: 1 })); b.appendChild(bigButton('★', T, { width: 48 })); b.appendChild(bigButton('✕', T, { width: 48 })); p.appendChild(b); over.appendChild(p); }
  // recording strip
  if (sc.recording) { const s = el('div', { position: 'absolute', left: '14px', right: '14px', bottom: '86px', background: T.card, border: `2px solid ${T.accent}`, borderRadius: '12px', display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 8px 6px 14px', boxShadow: '0 2px 8px rgba(0,0,0,.15)' });
    s.appendChild(el('div', { flex: 1, fontSize: '15px', fontWeight: 700 }, sc.recording)); const c = bigButton('CUE', T, { accent: true }); c.style.height = '44px'; c.style.padding = '0 18px'; s.appendChild(c); const st = bigButton('STOP', T); st.style.height = '44px'; s.appendChild(st); over.appendChild(s); }
  // dialogs
  if (sc.dialog) { const scrim = el('div', { position: 'absolute', inset: 0, background: 'rgba(0,0,0,.5)', display: 'grid', placeItems: 'center', padding: '20px' }); const d = el('div', { background: T.card, borderRadius: '6px', width: '100%', maxWidth: '372px', padding: '18px 20px 10px', boxShadow: '0 12px 40px rgba(0,0,0,.4)' });
    d.appendChild(el('div', { fontSize: '20px', fontWeight: 600, marginBottom: '8px' }, sc.dialog.title)); sc.dialog.build(d, T); scrim.appendChild(d); over.appendChild(scrim); }
  // status bar / punch hole
  const sbar = el('div', { position: 'absolute', left: 0, right: 0, top: 0, height: '24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 26px', fontSize: '12px', fontWeight: 600, color: T.ink }); sbar.appendChild(el('span', {}, sc.clock || '9:41')); sbar.appendChild(el('span', {}, sc.dark ? '▲ ▮▮▮ 100%' : '▲ ▮▮▮ 100%')); over.appendChild(sbar);
  const hole = el('div', { position: 'absolute', left: (w / 2 - 7) + 'px', top: '9px', width: '14px', height: '14px', borderRadius: '7px', background: '#000' }); over.appendChild(hole);
  return container;
}

export function cueDialog(d, T, chosen = 'LEFT') {
  const turns = ['SHARP_LEFT', 'LEFT', 'SLIGHT_LEFT', 'STRAIGHT', 'SLIGHT_RIGHT', 'RIGHT', 'SHARP_RIGHT', 'KEEP_LEFT', 'UTURN', 'KEEP_RIGHT', 'NOTE', 'DANGER'];
  const g = el('div', { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '2px' });
  for (const t of turns) { const c = el('div', { display: 'flex', flexDirection: 'column', alignItems: 'center', opacity: t === chosen ? 1 : .35, padding: '4px 2px' }); c.appendChild(arrow(t, 1, 68, T)); c.appendChild(el('div', { fontSize: '10px', fontWeight: 600 }, (SPOKEN[t] || t).toUpperCase())); g.appendChild(c); }
  d.appendChild(g);
  const inp = el('div', { borderBottom: `2px solid ${T.accent}`, padding: '12px 8px', fontSize: '18px', marginTop: '6px', color: T.ink }, 'onto the two-track past the gate'); d.appendChild(inp);
  const btns = el('div', { display: 'flex', justifyContent: 'flex-end', gap: '24px', padding: '14px 4px 4px', fontSize: '14px', fontWeight: 600, color: T.accent }); btns.appendChild(el('span', {}, 'CANCEL')); btns.appendChild(el('span', {}, 'SAVE')); d.appendChild(btns);
}

export function packsDialog(d, T, rows) {
  d.appendChild(el('div', { fontSize: '12px', letterSpacing: '.1em', color: T.soft, padding: '6px 0 2px' }, 'UNITED STATES'));
  for (const r of rows) { const row = el('div', { display: 'flex', alignItems: 'center', padding: '7px 0', gap: '10px' }); const t = el('div', { flex: 1, fontSize: '16px', lineHeight: 1.3 }); t.innerHTML = `${r[0]}<br><span style="color:${T.soft};font-size:14px">${r[1]}</span>`; row.appendChild(t); const b = el('div', { padding: '8px 14px', fontSize: '13px', fontWeight: 700, color: T.accent }, r[2]); row.appendChild(b); d.appendChild(row); }
  d.appendChild(el('div', { fontSize: '12px', color: T.soft, padding: '10px 0 0', lineHeight: 1.4 }, 'Packs download to this phone and are imported automatically. A download you interrupt resumes where it stopped.'));
  const btns = el('div', { display: 'flex', justifyContent: 'flex-end', gap: '24px', padding: '14px 4px 4px', fontSize: '14px', fontWeight: 600, color: T.accent }); btns.appendChild(el('span', {}, 'CLOSE')); d.appendChild(btns);
}

/** A phone frame around a screen. scale = dp → px. */
export function phone(container, sc, scale = 1) {
  const w = sc.w || 412, h = sc.h || 915, bez = 14, r = 52;
  const f = el('div', { position: 'relative', width: (w + bez * 2) * scale + 'px', height: (h + bez * 2) * scale + 'px', borderRadius: (r + bez) * scale + 'px', background: '#101010', boxShadow: '0 30px 60px rgba(0,0,0,.35), 0 0 0 1px rgba(255,255,255,.08) inset', flex: 'none' });
  const s = el('div', { position: 'absolute', left: bez * scale + 'px', top: bez * scale + 'px', width: w * scale + 'px', height: h * scale + 'px', borderRadius: r * scale + 'px', overflow: 'hidden' });
  const inner = el('div', { transformOrigin: '0 0', transform: `scale(${scale})` }); screen(inner, sc); s.appendChild(inner); f.appendChild(s);
  // side buttons
  f.appendChild(el('div', { position: 'absolute', right: -3 * scale + 'px', top: 180 * scale + 'px', width: 3 * scale + 'px', height: 90 * scale + 'px', background: '#2a2a2a', borderRadius: '2px' }));
  f.appendChild(el('div', { position: 'absolute', right: -3 * scale + 'px', top: 300 * scale + 'px', width: 3 * scale + 'px', height: 50 * scale + 'px', background: '#2a2a2a', borderRadius: '2px' }));
  container.appendChild(f); return f;
}
