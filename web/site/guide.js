// The animated guide. A fake phone (and, later, a fake planner window) is driven step by step by a visible finger;
// every step has a caption. Nothing here touches the real app or router: the scenes are drawn by hand so the guide
// is tiny, works offline, and changes when the app changes by editing a label here.
//
// A section is a list of steps; each step is an async function on the stage `s`. Jumping to step N resets the
// stage and replays steps 0..N-1 in fast mode (no waits, no transitions), so Prev/Next and the rail are exact.
(() => {
  'use strict';
  const $ = (q, el = document) => el.querySelector(q);
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  class Abort extends Error {}
  // ?fast=1 runs every wait at zero length (the test harness steps through every scene that way)
  const SPEED = new URLSearchParams(location.search).get('fast') ? 0 : 1;

  // ---- the map drawn on every phone: a few roads in a 280×560 world ----------------------------------------------
  const ROADS = `
    <g class="roads">
      <path d="M-20 470 H300"/><path d="M-20 300 H300"/><path d="M60 -20 V580"/><path d="M220 -20 V580"/>
      <path d="M-20 140 Q140 110 300 60"/><path d="M160 300 V470"/><path d="M60 200 H220"/>
      <path class="dirt" d="M220 300 Q250 240 300 230"/><path class="dirt" d="M60 300 Q30 200 80 130"/>
    </g>
    <text class="town" x="66" y="490">Bozeman</text><text class="town" x="226" y="318">Norris</text><text class="town" x="232" y="66">Ennis</text>
    <path class="hill" d="M-20 380 Q60 330 130 390 T300 370 V580 H-20 Z" fill="#8a7a5a"/>
    <path class="fog" d="M-20 -20 H300 V580 H-20 Z"/><path class="fog edge" d="M-20 -20 H300 V580 H-20 Z M30 30 H250 V530 H30 Z" fill-rule="evenodd"/>
    <rect class="cover" x="30" y="30" width="220" height="500" rx="6"/>
    <g class="routes"></g><g class="markers"></g>`;
  const PLANNER_ROADS = `
    <g class="roads">
      <path d="M200 330 H640"/><path d="M200 200 H640"/><path d="M300 -10 V410"/><path d="M520 -10 V410"/><path d="M200 110 Q420 70 640 60"/><path d="M300 265 H520"/>
      <path class="dirt" d="M520 200 Q560 140 640 130"/><path class="dirt" d="M300 200 Q260 150 320 100"/>
    </g>
    <text class="town" x="306" y="348">Bozeman</text><text class="town" x="526" y="216">Norris</text><text class="town" x="602" y="80">Ennis</text>
    <path class="hill" d="M200 300 Q320 250 420 310 T640 290 V400 H200 Z" fill="#8a7a5a"/>
    <path class="land" d="M400 0 H640 V200 H480 V120 H400 Z" fill="#e8c36a" opacity="0"/>
    <g class="routes"></g><g class="markers"></g>`;
  const ARROW = {
    left: 'M68 92 L68 48 Q68 32 52 32 L26 32 M34 20 L22 32 L34 44',
    right: 'M32 92 L32 48 Q32 32 48 32 L74 32 M66 20 L78 32 L66 44',
    straight: 'M50 92 V20 M32 38 L50 20 L68 38',
    arrive: 'M50 80 V30 M30 50 L50 30 L70 50',
  };
  const arrowSvg = (t, color = '#d8502f') => `<svg viewBox="0 0 100 100"><path d="${ARROW[t] || ARROW.straight}" fill="none" stroke="${color}" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  const MODES = { fast: ['Fast', '#2b6cb0'], backroads: ['Backroads', '#2f855a'], ride: ['Ride', '#7c3aed'] };
  const MENU_IDLE = [['GO TO', '⌕'], ['PLAN', '▤'], ['TRACK', '⤳'], ['MAPS', '▦'], ['RECORD', '●'], ['BATTERY SAVER', '▭'], ['NIGHT MAP', '☾'], ['LOCATE', '◎'], ['LOCK SCREEN', '⊡']];
  const MENU_NAV = [['END', '✕', 'acc'], ['SOUND ON', '♪', 'onn'], ['BATTERY SAVER', '▭'], ['TURN LIST', '≡'], ['RECORD', '●'], ['NIGHT MAP', '☾'], ['SUN MAP', '☀'], ['LOCATE', '◎'], ['LOCK SCREEN', '⊡']];

  // ---- the stage ---------------------------------------------------------------------------------------------------
  class Stage {
    constructor(root) { this.root = root; this.token = 0; this.fast = false; }
    reset(device) {
      this.planner = device === 'planner';
      this.W = this.planner ? 640 : 280; this.H = this.planner ? 400 : 560;
      this.root.innerHTML = this.planner ? '<div class="gplanner"><div class="gscreen"></div></div>' : '<div class="gphone"><div class="gscreen"></div></div>';
      const sc = $('.gscreen', this.root);
      sc.innerHTML = this.planner
        ? `<svg class="gmap" viewBox="0 0 640 400" preserveAspectRatio="none">${PLANNER_ROADS}</svg>
           <div class="pside"></div><div class="players"></div><div class="pctx"></div><div class="gtoast low"></div><div class="gsheet modal"></div><div class="gfinger cursor"></div>`
        : `<svg class="gmap" viewBox="0 0 280 560" preserveAspectRatio="none">${ROADS}</svg>
           <div class="gtoast"></div><div class="gtop"></div><div class="gpanel"></div><div class="gfab">≡</div>
           <div class="gscrim"></div><div class="gmenu"></div><div class="gsheet"></div><div class="gfinger"></div>`;
      this.sc = sc;
      this.routes = $('g.routes', sc); this.markers = $('g.markers', sc);
      this.finger = $('.gfinger', sc);
      this.puckAt = null;
    }
    check() { if (this.token !== this.myToken) throw new Abort(); }
    wait(ms) { this.check(); if (this.fast) return Promise.resolve(); return new Promise((res, rej) => setTimeout(() => this.token !== this.myToken ? rej(new Abort()) : res(), ms * SPEED)); }
    // ---- the finger
    async moveTo(x, y) { this.finger.style.left = (x / this.W * 100) + '%'; this.finger.style.top = (y / this.H * 100) + '%'; this.finger.classList.add('on'); await this.wait(550); }
    posOf(el) { const a = el.getBoundingClientRect(), b = this.sc.getBoundingClientRect(); return [(a.left + a.width / 2 - b.left) / b.width * this.W, (a.top + a.height / 2 - b.top) / b.height * this.H]; }
    /// press at one point, move to another, release (the planner's drag-the-line)
    async drag(from, to) { await this.moveTo(...from); this.finger.classList.add('press'); await this.wait(250); await this.moveTo(...to); await this.wait(150); this.finger.classList.remove('press'); await this.wait(250); }
    async tap(t) {
      const [x, y] = t instanceof Element ? this.posOf(t) : t;
      await this.moveTo(x, y);
      if (t instanceof Element) t.classList.add('hot');
      this.finger.classList.add('press'); await this.wait(180); this.finger.classList.remove('press');
      if (t instanceof Element) setTimeout(() => t.classList.remove('hot'), 400);
      await this.wait(250);
    }
    async longPress(t) {
      const [x, y] = t instanceof Element ? this.posOf(t) : t;
      await this.moveTo(x, y);
      this.finger.classList.add('press', 'hold'); await this.wait(850); this.finger.classList.remove('press', 'hold');
      await this.wait(150);
    }
    hideFinger() { this.finger.classList.remove('on'); }
    // ---- map
    route(id, d, color, { casing = true, draw = true, dim = false } = {}) {
      let g = $('#r-' + id, this.routes); if (g) g.remove();
      g = document.createElementNS('http://www.w3.org/2000/svg', 'g'); g.id = 'r-' + id;
      g.innerHTML = (casing ? `<path class="casing${draw && !this.fast ? ' draw' : ''}" d="${d}"/>` : '') + `<path class="line${dim ? ' dim' : ''}${draw && !this.fast ? ' draw' : ''}" d="${d}" stroke="${color}"/>`;
      this.routes.appendChild(g); return g;
    }
    selectRoute(id) { for (const g of this.routes.children) { const l = $('.line', g); l.classList.toggle('dim', g.id !== 'r-' + id); const c = $('.casing', g); if (c) c.style.opacity = g.id === 'r-' + id ? '' : '0'; } }
    clearRoutes() { this.routes.innerHTML = ''; }
    marker(id, kind, x, y, label = '') {
      $('#m-' + id, this.markers)?.remove();
      const g = document.createElementNS('http://www.w3.org/2000/svg', 'g'); g.id = 'm-' + id;
      const pop = this.fast ? '' : ' pop';
      const body = kind === 'pin' ? `<path class="pin${pop}" d="M${x} ${y} l-9 -16 a10 10 0 1 1 18 0 z"/>`
        : kind === 'puck' ? `<path class="puck" d="M0 -14 L10 10 L0 4 L-10 10 Z" transform="translate(${x} ${y})"/>`
        : `<circle class="${kind}${pop}" cx="${x}" cy="${y}" r="${kind === 'ring' ? 5 : kind === 'start' ? 8 : kind === 'place' ? 9 : 7}"/>`;
      g.innerHTML = body + (label ? (kind === 'place' ? `<text class="emoji" x="${x}" y="${y + 4}" text-anchor="middle">${esc(label)}</text>` : `<text x="${x + 11}" y="${y + 4}">${esc(label)}</text>`) : '');
      this.markers.appendChild(g);
      if (kind === 'puck') this.puckAt = [x, y];
      return g;
    }
    unmark(id) { $('#m-' + id, this.markers)?.remove(); }
    clearMarkers() { this.markers.innerHTML = ''; this.puckAt = null; }
    movePuck(x, y, rot = 0) { const p = $('#m-puck .puck', this.markers); if (p) p.setAttribute('transform', `translate(${x} ${y}) rotate(${rot})`); this.puckAt = [x, y]; }
    layer(cls, on) { $('.' + cls, this.sc).classList.toggle('on', on); }
    // ---- chrome
    fab(on) { $('.gfab', this.sc).style.display = on ? '' : 'none'; }
    panel(html) { const p = $('.gpanel', this.sc); p.innerHTML = html; p.classList.toggle('on', !!html); this.fab(!html); return p; }
    top(html) { const p = $('.gtop', this.sc); p.innerHTML = html; p.classList.toggle('on', !!html); return p; }
    async toast(text, ms = 1600, low = false) { const t = $('.gtoast', this.sc); t.textContent = text; t.classList.toggle('low', low); t.classList.add('on'); await this.wait(ms); t.classList.remove('on'); }
    async menu(items, title = 'ROADBOOK') {
      const m = $('.gmenu', this.sc);
      m.innerHTML = `<div class="t">${esc(title)}</div><div class="grid">${items.map(([l, ic, c]) => `<div class="it ${c || ''}" data-l="${esc(l)}"><em>${ic}</em>${esc(l)}</div>`).join('')}</div>`;
      $('.gscrim', this.sc).classList.add('on'); m.classList.add('on');
      await this.wait(350);
      return l => $(`.it[data-l="${l}"]`, m);
    }
    closeMenu() { $('.gscrim', this.sc).classList.remove('on'); $('.gmenu', this.sc).classList.remove('on'); }
    async sheet(title, html) {
      const s = $('.gsheet', this.sc);
      s.innerHTML = `<div class="bar"><span>${esc(title)}</span><span class="x">Close</span></div><div class="body">${html}</div>`;
      s.classList.add('on'); this.closeMenu();
      await this.wait(380);
      return s;
    }
    async closeSheet() { $('.gsheet', this.sc).classList.remove('on'); await this.wait(350); }
    async type(el, text) {
      el.classList.remove('ph'); el.innerHTML = '<span class="caret"></span>';
      for (let i = 1; i <= text.length; i++) { el.innerHTML = esc(text.slice(0, i)) + '<span class="caret"></span>'; await this.wait(70 + Math.random() * 60); }
      await this.wait(300);
    }
    // ---- composite screens
    pinPanel(label, { via = false } = {}) {
      return this.panel(`<div class="gcard"><div class="label">${esc(label)}</div><div class="grow"><div class="gbtn accent" data-b="route" style="flex:2">ROUTE HERE</div>${via ? '<div class="gbtn" data-b="via">VIA</div>' : ''}<div class="gbtn" data-b="from">FROM</div><div class="gbtn sq" data-b="star">★</div><div class="gbtn sq" data-b="x">✕</div></div></div>`);
    }
    comparePanel(rows, sel, { title = '', stops = false } = {}) {
      const r = rows.map(([k, info]) => { const [name, col] = MODES[k]; return `<div class="r${k === sel ? ' sel' : ''}" data-m="${k}"><i style="background:${col}"></i><b>${name}</b><span>${esc(info)}</span></div>`; }).join('');
      return this.panel(`<div class="gcard cmp">${title ? `<div class="title">${esc(title)}</div>` : ''}${r}<div class="grow"><div class="gbtn accent" data-b="go" style="flex:1.4">GO</div>${stops ? '<div class="gbtn" data-b="stops">STOPS</div>' : ''}<div class="gbtn" data-b="modes">MODES</div><div class="gbtn" data-b="save">SAVE</div><div class="gbtn sq" data-b="x">✕</div></div></div>`);
    }
    hud(turn, dist, street, progress = 0) {
      const t = this.top(`<div class="gcard ghud"><div class="arrow">${arrowSvg(turn)}</div><div><div class="d">${esc(dist)}</div><div class="s">${esc(street)}</div></div></div><div class="gcard" style="padding:6px 12px;margin-top:6px"><div class="gbar"><i style="width:${progress}%"></i></div></div>`);
      return t;
    }
    navPanel(remaining, speed, eta = '3:42 PM · 1 h 20', elev = '4,830 ft') { const p = this.panel(`<div class="gcard navcard"><div class="sc"><i></i><b>1 mi</b></div><div class="row"><div><div class="big">${esc(remaining)}</div><div class="small">${esc(eta)}</div></div><div class="hole"></div><div style="text-align:right"><div class="big">${esc(speed)}</div><div class="small">${esc(elev)}</div></div></div></div>`); this.fab(true); return p; }
    btn(name) { return $(`[data-b="${name}"]`, this.sc); }
    row(name) { return $(`.r[data-m="${name}"]`, this.sc); }
    // ---- the planner
    /** the sidebar: a search box, the modes, then either the hint or stats + cues + actions */
    pside({ mode = 'ride', hint = true, dist = '', time = '', cues = [], name = 'Roadbook', profile = false, legs = '' } = {}) {
      const modes = [['fast', 'Fast'], ['backroads', 'Backroads'], ['ride', 'Ride'], ['dirt', 'Dirt'], ['gravel', 'Gravel']].map(([k, l]) => `<span class="chip${k === mode ? ' on' : ''}" data-mode="${k}" style="--c:${(MODES[k] || ['', '#8a7a5a'])[1]}">${l}</span>`).join('');
      const body = hint ? `<div class="hint"><p><b>Click the map twice</b>: a start and an end. The route follows the mode above.</p><p><b>Drag the line</b> onto any road or trail to pull the route through it.</p><p><b>Click the line</b> to add a cue, inspect the spot, or add a stop.</p></div>`
        : `<div class="stats"><div class="big">${esc(dist)} <span>${esc(time)}</span></div><div class="surf"><i style="width:72%;background:#2b6cb0"></i><i style="width:18%;background:#e7b400"></i><i style="width:10%;background:#8a7a5a"></i></div>${profile ? '<svg class="prof" viewBox="0 0 160 40" preserveAspectRatio="none"><path d="M0 34 L30 30 L60 14 L80 10 L100 22 L130 18 L160 32 V40 H0 Z" fill="#cfc9bb"/><path d="M0 34 L30 30 L60 14 L80 10 L100 22 L130 18 L160 32" fill="none" stroke="#5a5a5a" stroke-width="1.2"/></svg><div class="muted">↑ 2,140 ft · ↓ 1,880 ft · high point 6,320 ft</div>' : ''}${legs ? `<div class="muted">${esc(legs)}</div>` : ''}</div>
           <div class="cues"><h4>Cues <span>(${cues.length})</span> <em>reset to turns</em></h4>${cues.map(c => `<div class="cue${c.user ? ' user' : ''}" data-cue="${esc(c.id || '')}"><b>${esc(c.g)}</b><span>${esc(c.text)}</span><i>${esc(c.at)}</i></div>`).join('')}</div>
           <div class="actions"><span class="cta" data-b="gpx">Download GPX</span><span class="cta alt" data-b="phone">Send to phone</span><span class="cta alt" data-b="publish">Publish</span><div class="mini"><span data-b="clear">clear</span><span data-b="reverse">reverse</span><span data-b="loop">loop</span><span data-b="open">open GPX</span></div><div class="muted">Name: <u data-b="name">${esc(name)}</u></div></div>`;
      const side = $('.pside', this.sc);
      side.innerHTML = `<div class="ptitle">Roadbook <small>planner</small></div><div class="in ph" data-in>Find a town, road or trail</div><div class="modes">${modes}</div>${body}`;
      return side;
    }
    players(on = {}) {
      const L = [['terrain', 'Terrain'], ['land', 'Land ownership'], ['aerial', 'Aerial'], ['places', 'Places'], ['library', 'Roadbooks'], ['night', 'Night']];
      $('.players', this.sc).innerHTML = L.map(([k, l]) => `<label data-ly="${k}"><i class="${on[k] ? 'on' : ''}"></i>${l}</label>`).join('');
    }
    async ctx(x, y, items = ['Add a cue here', 'Add a stop here', 'Inspect this spot']) {
      const c = $('.pctx', this.sc);
      c.innerHTML = items.map(i => `<div data-c="${esc(i)}">${esc(i)}</div>`).join('');
      c.style.left = (x / this.W * 100) + '%'; c.style.top = (y / this.H * 100) + '%'; c.classList.add('on');
      await this.wait(300);
      return l => $(`[data-c="${l}"]`, c);
    }
    closeCtx() { $('.pctx', this.sc).classList.remove('on'); }
    async modal(title, html) { const m = $('.gsheet', this.sc); m.innerHTML = `<div class="bar"><span>${esc(title)}</span><span class="x">×</span></div><div class="body">${html}</div>`; m.classList.add('on'); this.closeCtx(); await this.wait(350); return m; }
    cursorOver(sel) { return $(sel, this.sc); }
  }

  // ---- shared bits of scenes --------------------------------------------------------------------------------------
  const K = s => `<span class="key">${s}</span>`;
  const searchSheet = (s, title, extra) => s.sheet(title, `<div class="in ph" data-in>Address, street or place</div>${extra ? `<div class="grow" style="margin-top:8px"><div class="gbtn" data-b="extra">${esc(extra)}</div></div>` : ''}<div data-hits style="margin-top:8px"></div>`);
  const hits = (sh, list) => { $('[data-hits]', sh).innerHTML = list.map((h, i) => `<div class="li" data-h="${i}">${esc(h)}</div>`).join(''); return i => $(`[data-h="${i}"]`, sh); };
  const stopsSheet = (s, title, stops, loop) => s.sheet('Stops', `<div class="sub">${esc(title)}</div>${stops.map(([tag, name, ctl]) => `<div class="li"><span class="tag">${tag}</span>${esc(name)}${ctl ? '<span class="ctl"><span>▲</span><span>▼</span><span>✕</span></span>' : ''}</div>`).join('')}<div class="foot"><div class="grow"><div class="gbtn" data-b="add">ADD STOP</div><div class="gbtn" data-b="loop">${loop ? 'OPEN LOOP' : 'LOOP'}</div><div class="gbtn" data-b="rev">REVERSE</div></div><div class="grow"><div class="gbtn accent" data-b="done">DONE</div></div></div>`);
  const cueSheet = async (s, title, sel) => {
    const arrows = [['left', 'LEFT'], ['straight', 'STRAIGHT'], ['right', 'RIGHT'], ['uturn', 'U-TURN'], ['note', 'NOTE'], ['danger', 'DANGER']];
    const glyph = k => k === 'note' ? '<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="30" fill="none" stroke="#d8502f" stroke-width="10"/><path d="M50 36 v4 M50 48 v18" stroke="#d8502f" stroke-width="10" stroke-linecap="round"/></svg>'
      : k === 'danger' ? '<svg viewBox="0 0 100 100"><path d="M50 18 L84 78 L16 78 Z" fill="#d8502f"/><path d="M50 40 v18 M50 66 v4" stroke="#fff" stroke-width="9" stroke-linecap="round"/></svg>'
      : k === 'uturn' ? arrowSvg('straight').replace('M50 92 V20 M32 38 L50 20 L68 38', 'M36 92 V40 Q36 20 54 20 Q72 20 72 40 V62 M60 50 L72 64 L84 50') : arrowSvg(k);
    return s.sheet(title, `<div class="arrows">${arrows.map(([k, l]) => `<div data-a="${k}" class="${k === sel ? 'sel' : ''}">${glyph(k)}${l}</div>`).join('')}</div><div class="in ph" data-in>Words to say (optional)</div><div class="grow" style="margin-top:10px"><div class="gbtn accent" data-b="save">SAVE</div></div>`);
  };
  const FAST = 'M160 470 V300 H220 V84 Q236 79 252 74', BACK = 'M160 470 H60 V124 Q156 103 252 74', RIDE = 'M160 470 V300 H60 V124 Q156 103 252 74';
  const idleMap = s => { s.layer('cover', true); s.layer('edge', true); s.marker('puck', 'puck', 160, 470); };

  // ---- the sections ------------------------------------------------------------------------------------------------
  const SECTIONS = [
    { group: 'Getting started', id: 'download-maps', title: 'Download maps', short: true, device: 'phone',
      blurb: 'Roadbook works with no signal at all, so the first thing is to put a map on the phone: roads, places and the router\'s graph for a state or province, in one file.',
      steps: [
        { c: `A fresh install: the map is fogged because nothing is installed yet. Everything starts from the one big button, ${K('≡')}.`,
          run: async s => { s.layer('fog', true); await s.wait(800); } },
        { c: `Tap ${K('≡')}, then ${K('MAPS')}. The menu is the same whichever way the phone is turned, and the buttons are sized for gloves.`,
          run: async s => { await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('MAPS')); } },
        { c: `${K('DOWNLOAD')} opens the catalog: every state and province, roads and terrain listed separately, with sizes.`,
          run: async s => {
            const sh = await s.sheet('Maps', `<div class="sub">No maps on this phone yet.</div><div class="grow"><div class="gbtn accent" data-b="dl">DOWNLOAD</div><div class="gbtn" data-b="imp">IMPORT</div></div><div class="sub" style="margin-top:14px">Units · marks · terms · privacy</div>`);
            await s.tap(s.btn('dl'));
            await s.sheet('Download maps', `<div class="sub">Montana</div><div class="li" data-h="0">Montana roads<span class="r">540 MB</span></div><div class="li" data-h="1">Montana terrain<span class="r">310 MB</span></div><div class="sub" style="margin-top:8px">Nearby</div><div class="li dimmed">Idaho roads<span class="r">380 MB</span></div><div class="li dimmed">Wyoming roads<span class="r">290 MB</span></div>`);
          } },
        { c: `Tap the roads pack for where you ride. It downloads in the background and resumes if the connection drops. Do this at home on wifi — a state is a few hundred megabytes.`,
          run: async s => {
            await s.tap($('[data-h="0"]', s.sc));
            const li = $('[data-h="0"]', s.sc); li.innerHTML = 'Montana roads<span class="r">downloading…</span>';
            const bar = document.createElement('div'); bar.className = 'prog'; bar.innerHTML = '<i></i>'; li.after(bar);
            await s.wait(100); $('i', bar).style.width = '100%'; await s.wait(1800);
            li.innerHTML = 'Montana roads<span class="r">installed ✓</span>'; bar.remove(); await s.wait(500);
          } },
        { c: `Terrain is optional: hillshade under the roads, so a route reads like a paper map. Same tap.`,
          run: async s => {
            await s.tap($('[data-h="1"]', s.sc));
            const li = $('[data-h="1"]', s.sc); li.innerHTML = 'Montana terrain<span class="r">installed ✓</span>'; await s.wait(500);
          } },
        { c: `Close the sheet. The fog lifts over the new pack; the dashed outline is its edge. Routing and search work inside it, with no signal, from now on.`,
          run: async s => {
            await s.tap($('.gsheet .x', s.sc)); await s.closeSheet();
            s.hideFinger(); s.layer('fog', false); s.layer('edge', true); s.layer('cover', true); s.layer('hill', true); await s.wait(300);
            s.marker('puck', 'puck', 160, 470); await s.toast('Montana roads + terrain installed', 1800); await s.wait(400);
          } },
        { c: `Zoom in a little and the pack's places appear: fuel, camping, water, beds, repair, food — the important ones first. Tap one and it becomes a pin with its name and hours, ready for ${K('ROUTE HERE')}. ${K('MAPS')} → ${K('PLACES')} turns them off.`,
          run: async s => { for (const [i, [x, y, l]] of [[232, 318, '⛽'], [66, 320, '▲'], [150, 150, '🍴'], [200, 470, '🛏'], [100, 460, '🔧']].entries()) { s.marker('pl' + i, 'place', x, y, l); await s.wait(220); } await s.wait(500); await s.tap([232, 318]); s.marker('pin', 'pin', 232, 318); s.pinPanel('Norris Pump & Go · fuel · 6am–10pm'); await s.wait(1200); } },
        { c: `Other ways in: ${K('IMPORT')} opens a .mappack you were sent (AirDrop, a USB stick, the Files app), and the same packs are listed at hondoroadbook.com/packs. Next: go somewhere.`,
          run: async s => { await s.wait(600); } },
      ] },

    { group: 'Routing on the phone', id: 'go-somewhere', title: 'Go somewhere', short: true, device: 'phone',
      blurb: 'Pick a destination, see every riding mode drawn at once, choose one, go. The fastest way to the first ride.',
      steps: [
        { c: `${K('≡')} → ${K('GO TO')} searches every installed map: towns, streets, passes, trailheads, and your own saved marks (★) first.`,
          run: async s => { idleMap(s); s.layer('hill', true); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('GO TO')); await searchSheet(s, 'Go to'); } },
        { c: `Type a few letters. Results are ranked across every pack, so a town in the next state beats a street nearby.`,
          run: async s => { const sh = $('.gsheet', s.sc); await s.type($('[data-in]', sh), 'Ennis'); const h = hits(sh, ['Ennis, Madison County', 'Ennis Lake Rd, Ennis', 'Ennis Fish Hatchery']); await s.wait(400); await s.tap(h(0)); await s.closeSheet(); } },
        { c: `The pin lands and the map goes there. ${K('ROUTE HERE')} plans from where you are. (${K('FROM')} plans from somewhere else; ${K('★')} saves the place.)`,
          run: async s => { s.marker('pin', 'pin', 252, 74); s.pinPanel('Ennis, Madison County'); await s.wait(500); await s.tap(s.btn('route')); } },
        { c: `Every mode you have ticked under ${K('MODES')} is routed and drawn as it finishes: Fast, Backroads, Ride, Dirt, Gravel, Quiet, Towing — each its own colour, each with distance and time.`,
          run: async s => {
            s.panel(''); s.hideFinger(); await s.toast('Routing…', 900);
            s.comparePanel([['fast', '…'], ['backroads', ''], ['ride', '']], 'fast');
            s.route('fast', FAST, MODES.fast[1]); await s.wait(900); s.comparePanel([['fast', '48 mi · 58 min'], ['backroads', '…'], ['ride', '']], 'fast');
            s.route('backroads', BACK, MODES.backroads[1], { dim: true }); await s.wait(900); s.comparePanel([['fast', '48 mi · 58 min'], ['backroads', '61 mi · 1 h 25'], ['ride', '…']], 'fast');
            s.route('ride', RIDE, MODES.ride[1], { dim: true }); await s.wait(900); s.comparePanel([['fast', '48 mi · 58 min'], ['backroads', '61 mi · 1 h 25'], ['ride', '57 mi · 1 h 20']], 'fast'); s.selectRoute('fast');
          } },
        { c: `Tap a line or a row to choose it. Ride is the twisty one: paved, as little straight road as it can manage.`,
          run: async s => { await s.tap(s.row('ride')); s.selectRoute('ride'); s.comparePanel([['fast', '48 mi · 58 min'], ['backroads', '61 mi · 1 h 25'], ['ride', '57 mi · 1 h 20']], 'ride'); await s.wait(500); } },
        { c: `${K('GO')}. The screen becomes the riding view: the next cue, the distance to it, the road. The route stays on the phone — no signal needed from here to Ennis.`,
          run: async s => {
            await s.tap(s.btn('go')); s.hideFinger(); s.panel(''); s.clearRoutes(); s.route('nav', RIDE, MODES.ride[1], { draw: false });
            s.hud('left', '0.4 mi', 'MT-84'); s.navPanel('57 mi', '41 mph'); s.fab(true);
            await s.wait(300); s.movePuck(160, 400); await s.wait(1000); s.hud('left', '0.2 mi', 'MT-84', 40); s.movePuck(160, 330); await s.wait(1000); s.hud('left', '350 ft', 'MT-84', 85); await s.wait(600);
          } },
        { c: `The quick way, without typing: ${K('long-press')} anywhere on the map and ${K('ROUTE HERE')}. Next: pulling a route through places on the way.`,
          run: async s => { await s.wait(300); await s.longPress([220, 300]); s.marker('pin', 'pin', 220, 300); s.pinPanel('45.57521, -111.69130', { via: true }); await s.wait(1200); } },
      ] },

    { group: 'Routing on the phone', id: 'vias', title: 'Vias and FROM', device: 'phone',
      blurb: 'Pull every route through a point on the way, take it out again, and plan from somewhere other than where you are.',
      steps: [
        { c: `With a destination set, a long press on the map offers ${K('VIA')}: every mode is routed again through that point.`,
          run: async s => { idleMap(s); s.marker('pin', 'pin', 252, 74); s.route('fast', FAST, MODES.fast[1], { draw: false }); s.comparePanel([['fast', '48 mi · 58 min']], 'fast'); await s.wait(500); await s.longPress([220, 300]); s.marker('pin2', 'pin', 220, 300); s.pinPanel('Norris', { via: true }); await s.wait(300); await s.tap(s.btn('via')); } },
        { c: `The via is slotted in by where it falls along the ride, so you can add them in any order. Vias are the purple dots.`,
          run: async s => { s.unmark('pin2'); s.marker('viaA', 'via', 220, 300); s.panel(''); s.hideFinger(); await s.toast('Via Norris — routing…', 900); s.clearRoutes(); s.route('fast', 'M160 470 V300 H220 V84 Q236 79 252 74', MODES.fast[1]); s.comparePanel([['fast', '49 mi · 1 h 02']], 'fast'); await s.wait(1200); } },
        { c: `Tap a via to take it out again. ${K('REMOVE VIA')} routes once more without it.`,
          run: async s => { await s.tap([220, 300]); s.panel(`<div class="gcard"><div class="label">Stop A · Norris</div><div style="font-size:11px;color:#5a5a5a">45.57521, -111.69130</div><div class="grow"><div class="gbtn accent" data-b="rm">REMOVE VIA</div><div class="gbtn" data-b="x">CANCEL</div></div></div>`); await s.wait(600); await s.tap(s.btn('rm')); s.unmark('viaA'); s.clearRoutes(); s.route('fast', FAST, MODES.fast[1]); s.comparePanel([['fast', '48 mi · 58 min']], 'fast'); s.hideFinger(); await s.wait(900); } },
        { c: `${K('FROM')} on any pin makes that the start instead of the GPS position: planning tomorrow's ride from the motel, or a friend's ride from their door.`,
          run: async s => { await s.longPress([60, 470]); s.marker('pin3', 'pin', 60, 470); s.pinPanel('Bozeman'); await s.wait(300); await s.tap(s.btn('from')); s.unmark('pin3'); s.marker('start', 'start', 60, 470); s.clearRoutes(); s.route('fast', 'M60 470 H160 V300 H220 V84 Q236 79 252 74', MODES.fast[1]); s.comparePanel([['fast', '52 mi · 1 h 04']], 'fast'); s.hideFinger(); await s.toast('Routed from your chosen start', 1400); } },
        { c: `${K('✕')} drops the whole plan; ${K('END')} in the menu does the same during a ride. For a ride with several named stops, a loop, or notes along the way, use ${K('PLAN')} — next.`,
          run: async s => { await s.wait(700); } },
      ] },

    { group: 'Routing on the phone', id: 'plan', title: 'Plan a whole ride', short: true, device: 'phone',
      blurb: 'The planner\'s job on the phone, offline: a start and an end by name, stops in order, a loop or a reverse, and your own notes, hazards and turn wording on the route.',
      steps: [
        { c: `${K('≡')} → ${K('PLAN')} asks where the ride starts. Search, or ${K('FROM WHERE I AM')}.`,
          run: async s => { idleMap(s); s.layer('hill', true); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('PLAN')); await searchSheet(s, 'Plan a ride · where does it start?', 'FROM WHERE I AM'); } },
        { c: `Bozeman, say. The map goes there and the green dot marks the start.`,
          run: async s => { const sh = $('.gsheet', s.sc); await s.type($('[data-in]', sh), 'Boze'); const h = hits(sh, ['Bozeman, Gallatin County', 'Bozeman Pass', 'Bozeman Trail Rd']); await s.wait(300); await s.tap(h(0)); await s.closeSheet(); s.marker('start', 'start', 60, 470); await s.wait(300); } },
        { c: `…and where it ends. ${K('BACK TO THE START')} makes a loop straight away; here the end is Ennis.`,
          run: async s => { await searchSheet(s, '…and where does it end?', 'BACK TO THE START (LOOP)'); const sh = $('.gsheet', s.sc); await s.type($('[data-in]', sh), 'Ennis'); const h = hits(sh, ['Ennis, Madison County', 'Ennis Lake Rd, Ennis']); await s.wait(300); await s.tap(h(0)); await s.closeSheet(); } },
        { c: `The compare screen, now headed by the plan: Bozeman → Ennis. One mode is shown here; tick more under ${K('MODES')}.`,
          run: async s => { s.hideFinger(); s.marker('pin', 'pin', 252, 74); await s.toast('Routing…', 700); s.route('ride', 'M60 470 V124 Q156 103 252 74', MODES.ride[1]); s.comparePanel([['ride', '54 mi · 1 h 15']], 'ride', { title: 'Bozeman → Ennis', stops: true }); await s.wait(1300); } },
        { c: `${K('STOPS')} lists the ride in order. ${K('ADD STOP')} searches for one; stops can also be added with a long press on the map and ${K('VIA')}.`,
          run: async s => { await s.tap(s.btn('stops')); await stopsSheet(s, 'Bozeman → Ennis', [['START', 'Bozeman'], ['END', 'Ennis']]); await s.wait(500); await s.tap(s.btn('add')); await searchSheet(s, 'Add a stop'); const sh = $('.gsheet', s.sc); await s.type($('[data-in]', sh), 'Norris'); const h = hits(sh, ['Norris, Madison County', 'Norris Hot Springs']); await s.wait(300); await s.tap(h(1)); await s.closeSheet(); } },
        { c: `Norris Hot Springs is stop A and the route bends through it. Stops move up or down or come out in the list; the letters match the dots on the map.`,
          run: async s => { s.hideFinger(); s.marker('viaA', 'via', 220, 300, 'A'); s.clearRoutes(); s.route('ride', 'M60 470 H220 V84 Q236 79 252 74', MODES.ride[1]); s.comparePanel([['ride', '58 mi · 1 h 22']], 'ride', { title: 'Bozeman → Norris Hot Springs → Ennis', stops: true }); await s.wait(1300); } },
        { c: `${K('LOOP')} in STOPS: Ennis becomes stop B and the ride ends where it began. ${K('REVERSE')} rides it the other way round.`,
          run: async s => { await s.tap(s.btn('stops')); await stopsSheet(s, 'Bozeman → Norris Hot Springs → Ennis', [['START', 'Bozeman'], ['A', 'Norris Hot Springs', true], ['END', 'Ennis']]); await s.wait(500); await s.tap(s.btn('loop')); await s.closeSheet(); s.hideFinger(); s.unmark('pin'); s.marker('viaB', 'via', 252, 74, 'B'); s.marker('pin', 'pin', 60, 470); s.clearRoutes(); s.route('ride', 'M60 470 H220 V84 Q236 79 252 74 Q156 103 60 124 V470', MODES.ride[1]); s.comparePanel([['ride', '112 mi · 2 h 40']], 'ride', { title: 'Bozeman → Norris Hot Springs → Ennis → back to start', stops: true }); await s.wait(1300); } },
        { c: `Now the roadbook part. ${K('Long-press the line')} to put a note or a danger there, with the words the phone will say as you approach it.`,
          run: async s => { await s.longPress([140, 470]); await cueSheet(s, 'Add cue', 'note'); await s.wait(300); await s.tap($('[data-a="danger"]', s.sc)); $('[data-a="note"]', s.sc).classList.remove('sel'); $('[data-a="danger"]', s.sc).classList.add('sel'); await s.type($('[data-in]', s.sc), 'Cattle guard, loose gravel'); await s.tap(s.btn('save')); await s.closeSheet(); s.marker('cue1', 'cue danger', 140, 470, 'Cattle guard'); s.hideFinger(); await s.toast('Danger: Cattle guard, loose gravel', 1400, true); } },
        { c: `The small rings are the router's own turns. ${K('Tap one')} to say it in your own words — "left at the red barn" beats "left onto Road 283".`,
          run: async s => { s.marker('ringA', 'ring', 220, 470); s.marker('ringB', 'ring', 220, 84); s.marker('ringC', 'ring', 60, 124); await s.wait(500); await s.tap([220, 470]); await cueSheet(s, 'Reword this turn', 'left'); await s.wait(300); await s.type($('[data-in]', s.sc), 'Left at the red barn'); await s.tap(s.btn('save')); await s.closeSheet(); s.unmark('ringA'); s.marker('cue2', 'cue', 220, 470, 'Left at the red barn'); s.hideFinger(); await s.wait(700); } },
        { c: `Tap any cue to change or delete it. ${K('GO')} rides it now; ${K('SAVE')} keeps the whole plan — stops by name, your words, the line — as a GPX roadbook.`,
          run: async s => { await s.tap(s.btn('save')); s.panel(`<div class="gcard"><div class="label">Route: Bozeman</div><div style="font-size:11px;color:#5a5a5a;margin:4px 0 2px">112 mi · 2 h 40 · Ride<br>Saved as a GPX file: any map app opens it; Roadbook re-routes it on the recipient's own maps.</div><div class="grow"><div class="gbtn accent" data-b="sv">SAVE TO FILES</div><div class="gbtn" data-b="sh">SHARE…</div></div></div>`); await s.wait(700); await s.tap(s.btn('sv')); s.hideFinger(); s.comparePanel([['ride', '112 mi · 2 h 40']], 'ride', { title: 'Bozeman → Norris Hot Springs → Ennis → back to start', stops: true }); await s.toast('Saved roadbook-route-bozeman-ride.gpx — it will show under TRACK', 2200); } },
        { c: `Later, ${K('TRACK')} → the file → ${K('EDIT ROUTE')} brings this exact plan back to change, and ${K('SHARE…')} sends it to a friend, whose phone re-plans it on their own maps. None of this needed a signal.`,
          run: async s => { await s.wait(800); } },
      ] },
  ];

  // ---- phone: the rest ------------------------------------------------------------------------------------------------
  const navScene = (s, { dist = '0.4 mi', street = 'MT-84', progress = 0 } = {}) => { idleMap(s); s.layer('hill', true); s.route('nav', RIDE, MODES.ride[1], { draw: false }); s.marker('pin', 'pin', 252, 74); s.hud('left', dist, street, progress); s.navPanel('57 mi', '41 mph'); s.movePuck(160, 380); };
  const trackScene = s => { idleMap(s); s.layer('hill', true); s.route('trk', 'M160 470 V300 H60 V200 H220 V84 Q236 79 252 74', '#7c3aed', { draw: false }); s.marker('c1', 'cue', 60, 300, 'Left'); s.marker('c2', 'cue danger', 60, 250, 'Washout'); s.marker('c3', 'cue', 140, 200, 'Note'); s.marker('c4', 'cue', 220, 84, 'Right'); };
  const trackSheet = (s, name, meta) => s.sheet(name, `<div class="sub">${esc(meta)}</div><div class="grow"><div class="gbtn accent" data-b="follow">FOLLOW</div></div><div class="grow"><div class="gbtn" data-b="editc">EDIT CUES</div><div class="gbtn" data-b="editr">EDIT ROUTE</div></div><div class="grow"><div class="gbtn" data-b="pub">PUBLISH TO THE LIBRARY</div></div>`);
  const trackList = (s, files) => s.sheet('Follow a track', `<div class="grow" style="margin:0 0 8px"><div class="gbtn" data-b="code">FETCH CODE FROM THE PLANNER</div></div>${files.map((f, i) => `<div class="li" data-h="${i}"><div>${esc(f[0])}<div style="font-size:10px;color:#5a5a5a">${esc(f[1])}</div></div></div>`).join('')}<div class="grow" style="margin-top:8px"><div class="gbtn" data-b="browse">BROWSE FILES…</div></div>`);
  const recStrip = (s, text) => { let r = $('.grec', s.sc); if (!r) { r = document.createElement('div'); r.className = 'grec'; s.sc.appendChild(r); } r.textContent = text; r.style.display = text ? '' : 'none'; };
  const night = (s, on) => s.sc.classList.toggle('night', on);

  SECTIONS.splice(1, 0,
    { group: 'Getting started', id: 'riding-screen', title: 'The riding screen', device: 'phone',
      blurb: 'What is on the screen while you ride, and the one button that holds everything else.',
      steps: [
        { c: `While you ride: the next cue, the distance to it, the road it is on. The map is heading-up, you sit low on the screen so the road ahead gets the room, and the stretch of route up to the next cue glows. That is all.`,
          run: async s => { navScene(s); s.route('glow', 'M160 380 V300 H60', MODES.ride[1], { casing: false, draw: false }); $('#r-glow .line', s.sc).style.strokeWidth = '16'; $('#r-glow .line', s.sc).style.opacity = '.3'; await s.wait(600); s.hud('left', '0.3 mi', 'MT-84', 30); await s.wait(800); } },
        { c: `The bar under the arrow fills as the turn comes up. If sound is on, the pips rise with it and a chime says "turn in now" (section: Sound).`,
          run: async s => { s.hud('left', '600 ft', 'MT-84', 60); s.movePuck(160, 340); await s.wait(900); s.hud('left', '200 ft', 'MT-84', 90); s.movePuck(160, 315); await s.wait(900); } },
        { c: `${K('Tap the map')} to change the view: next turn → close up → whole route, each with its own buzz so you know where you landed without looking. A pinch keeps following at that zoom.`,
          run: async s => { await s.tap([140, 250]); s.hideFinger(); await s.toast('Close up', 900, true); await s.wait(300); await s.tap([140, 250]); s.hideFinger(); await s.toast('Whole route', 900, true); await s.wait(300); } },
        { c: `The card at the bottom wraps the big button: what is left and the ETA on the left, your speed and the elevation on the right, a one-mile scale bar along its top. Tap the numbers for the written turn list.`,
          run: async s => { await s.tap([140, 470]); s.hideFinger(); await s.sheet('Ennis · 57 mi · 1 h 20', ['0.3 mi', 'Left onto MT-84', '12 mi', 'Right onto US-287', '31 mi', 'Keep left', '57 mi', 'Arrive'].reduce((h, v, i) => h + (i % 2 ? `${esc(v)}</div>` : `<div class="li"><span class="r" style="margin:0;width:44px">${esc(v)}</span>`), '')); await s.wait(1200); await s.closeSheet(); } },
        { c: `${K('≡')} holds the rest, extra large: END, sound, battery saver, the turn list, record, night map, locate, lock screen. Nothing to hunt for with a glove.`,
          run: async s => { await s.tap($('.gfab', s.sc)); await s.menu(MENU_NAV, 'NAVIGATING'); await s.wait(1200); } },
        { c: `${K('NIGHT MAP')} flips the map dark; ${K('SUN MAP')} is the third map, for noon on the bars: white ground, wide dark roads, big labels, nothing else — the glass mirrors the sky, and a bright map out-shines that. The app remembers either. ${K('LOCK SCREEN')} holds the orientation the phone is in right now.`,
          run: async s => { const it = $('.gmenu [data-l="NIGHT MAP"]', s.sc); if (it) await s.tap(it); s.closeMenu(); night(s, true); s.hideFinger(); await s.wait(1200); } },
        { c: `${K('LOCATE')} snaps the map back to you after you have dragged it. ${K('END')} ends the ride; the plan is dropped and the map is just a map again.`,
          run: async s => { night(s, false); await s.wait(700); } },
      ] });

  SECTIONS.push(
    { group: 'Routing on the phone', id: 'follow-track', title: 'Follow a track or a saved route', short: true, device: 'phone',
      blurb: 'A GPX you were sent, a recording, or a route you saved: open it and ride it, cues and all.',
      steps: [
        { c: `${K('≡')} → ${K('TRACK')} lists every GPX in Roadbook's folder: saved routes, recordings, files you dropped in, roadbooks fetched from the planner.`,
          run: async s => { idleMap(s); s.layer('hill', true); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('TRACK')); await trackList(s, [['Bozeman loop (Ride).gpx', '48 KB · Oct 27'], ['Gravelly Range pre-run.gpx', '212 KB · Oct 19'], ['Madison divide.gpx', '96 KB · Sep 30']]); } },
        { c: `Tap a file. Roadbook reads the line and its cues and says what it found.`,
          run: async s => { await s.tap($('[data-h="0"]', s.sc)); await trackSheet(s, 'Bozeman loop (Ride)', '112 mi · 14 cues · a saved route'); await s.wait(600); } },
        { c: `${K('FOLLOW')} rides the line exactly as it is, cues and all — no router involved, so a track off every road still works. ${K('EDIT CUES')} opens it to add or change cues; ${K('EDIT ROUTE')} brings a saved route back to the plan screen with its stops.`,
          run: async s => { await s.tap(s.btn('follow')); await s.closeSheet(); s.hideFinger(); trackScene(s); s.hud('left', '0.4 mi', 'Bozeman loop', 10); s.navPanel('112 mi', '38 mph', '5:10 PM · 2 h 40'); await s.toast('Following "Bozeman loop"', 1200); } },
        { c: `Following a track, a cue reads whatever its author wrote — "Washout after the cattle guard" — and a danger buzzes harder than a turn. Off the line, the screen says so and points back (section: Off the line).`,
          run: async s => { s.movePuck(160, 380); await s.wait(900); s.hud('left', '0.2 mi', 'onto the ridge road', 45); s.movePuck(160, 330); await s.wait(1000); } },
        { c: `Files get in several ways: saved from a plan, recorded, fetched by code, AirDropped or shared to Roadbook, or dropped into its folder (section: Get a GPX in and out).`,
          run: async s => { await s.wait(600); } },
      ] },

    { group: 'Routing on the phone', id: 'off-line', title: 'Off the line', device: 'phone',
      blurb: 'Nothing happens by itself when you leave the route: the screen tells you, and you decide.',
      steps: [
        { c: `Riding along: a planned route, the next cue ahead.`,
          run: async s => { navScene(s); await s.wait(700); } },
        { c: `You miss the turn. One low buzz; the HUD becomes ${K('OFF TRACK')} with an arrow and the distance back to the line. The route stays where it was — Roadbook never redraws it under you.`,
          run: async s => { s.movePuck(160, 290, 0); await s.wait(800); s.movePuck(160, 250, 0); s.top(`<div class="gcard" style="text-align:center"><div class="d" style="font-size:20px;font-weight:800">OFF TRACK  ↙ 350 ft</div><div class="grow"><div class="gbtn accent" data-b="rr">REROUTE</div></div></div>`); await s.toast('Off the line', 900, true); await s.wait(600); } },
        { c: `Ride back to the line and the cues pick up where they were. Or ${K('REROUTE')}: a fresh route from here to the same destination, through any stops you have not reached yet.`,
          run: async s => { await s.tap(s.btn('rr')); s.hideFinger(); s.top(''); await s.toast('Routing…', 800); s.clearRoutes(); s.route('nav', 'M160 250 V200 H60 V124 Q156 103 252 74', MODES.ride[1]); s.hud('left', '0.1 mi', 'onto Road 283', 20); await s.wait(1200); } },
        { c: `A followed track has no REROUTE (there is nothing to route to): the arrow and the distance point you back to the line. ${K('END')} in the menu ends either kind of ride.`,
          run: async s => { await s.wait(600); } },
      ] },

    { group: 'Routing on the phone', id: 'sound', title: 'Sound and battery', short: true, device: 'phone',
      blurb: 'The voice and the pips, where they go, and keeping the phone alive on a long day.',
      steps: [
        { c: `${K('≡')} → ${K('SOUND ON')}. The toast says where the audio is going: the phone's speaker, wired headphones, or a helmet comm by name. If you hear nothing, that line is the first thing to check.`,
          run: async s => { navScene(s); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_NAV.map(x => x[0] === 'SOUND ON' ? ['SOUND OFF', '♪'] : x), 'NAVIGATING'); await s.tap(it('SOUND OFF')); s.closeMenu(); s.hideFinger(); await s.toast('Sound on · Cardo PACKTALK', 1800); } },
        { c: `A cue is spoken once as it comes into range ("Left onto MT-84"). Then pips, rising in pitch and coming faster as the bar fills, and a two-note chime at the moment to turn in — a couple of seconds before the junction at your speed. Stopped at a sign, the pips go quiet until you move. Music ducks under it all and comes back.`,
          run: async s => { s.hud('left', '0.4 mi', 'MT-84', 10); await s.wait(500); for (const [d, p, y] of [['900 ft', 40, 350], ['500 ft', 65, 330], ['200 ft', 88, 312], ['Now', 100, 300]]) { s.hud('left', d, 'MT-84', p); s.movePuck(160, y); await s.wait(700); } } },
        { c: `A note is read in its author's words; a danger gets a low triple buzz before the words; arrival is three rising notes. Sound stays on with the screen off and the phone in a pocket.`,
          run: async s => { s.hud('straight', '0.6 mi', 'Washout after the cattle guard', 0); await s.wait(1200); } },
        { c: `${K('BATTERY SAVER')} dims the screen and lets the phone lock; the lock screen carries the next cue and lights up for it. Under 15 % it turns itself on. A long day on one charge.`,
          run: async s => { await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_NAV, 'NAVIGATING'); await s.tap(it('BATTERY SAVER')); s.closeMenu(); s.hideFinger(); const d = document.createElement('div'); d.className = 'gdim'; d.innerHTML = '<div>0.4 mi<br><small>Left onto MT-84</small></div>'; s.sc.appendChild(d); await s.wait(200); d.classList.add('on'); await s.wait(1400); } },
      ] },

    { group: 'Routing on the phone', id: 'record', title: 'Record a pre-run and add cues from the bars', device: 'phone',
      blurb: 'Ride it once, mark the hazards as you pass them, and the file is a roadbook for next time.',
      steps: [
        { c: `${K('≡')} → ${K('RECORD')}. The strip shows time, distance and cues; recording starts with the first GPS fix and keeps going with the screen off.`,
          run: async s => { idleMap(s); s.layer('hill', true); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('RECORD')); s.closeMenu(); s.hideFinger(); recStrip(s, '● 0:00 · 0 mi · 0 cues'); await s.toast('Recording starts with the first GPS fix', 1200); s.route('rec', 'M160 470 V400', '#d8502f', { casing: false }); recStrip(s, '● 0:04 · 0.3 mi · 0 cues'); s.movePuck(160, 400); await s.wait(600); } },
        { c: `At a hazard, stop and ${K('≡')} → ${K('ADD CUE HERE')}: the arrow or the danger, a few words, SAVE. It is pinned where you are standing.`,
          run: async s => { await s.tap($('.gfab', s.sc)); const it = await s.menu([['STOP RECORDING', '■', 'acc'], ['ADD CUE HERE', '+'], ['SOUND OFF', '♪'], ['NIGHT MAP', '☾'], ['LOCATE', '◎'], ['LOCK SCREEN', '⊡']], 'RECORDING'); await s.tap(it('ADD CUE HERE')); await cueSheet(s, 'Add cue', 'danger'); await s.type($('[data-in]', s.sc), 'Washout, stay right'); await s.tap(s.btn('save')); await s.closeSheet(); s.hideFinger(); s.marker('r1', 'cue danger', 160, 400, 'Washout'); recStrip(s, '● 0:12 · 0.3 mi · 1 cue'); await s.toast('Danger: Washout, stay right', 1200, true); } },
        { c: `Ride on. Turns need no cue of your own — the router writes those when the file is planned again — so mark only what the map cannot know: washouts, gates, water, the fuel that closes early.`,
          run: async s => { s.route('rec', 'M160 470 V300 H60 V200', '#d8502f', { casing: false }); s.movePuck(60, 200, -90); recStrip(s, '● 0:41 · 2.1 mi · 1 cue'); await s.wait(900); await s.tap($('.gfab', s.sc)); const it = await s.menu([['ADD CUE HERE', '+'], ['STOP RECORDING', '■', 'acc']], 'RECORDING'); await s.tap(it('ADD CUE HERE')); await cueSheet(s, 'Add cue', 'note'); await s.type($('[data-in]', s.sc), 'Gate — close it behind you'); await s.tap(s.btn('save')); await s.closeSheet(); s.hideFinger(); s.marker('r2', 'cue', 60, 200, 'Gate'); recStrip(s, '● 0:52 · 2.1 mi · 2 cues'); } },
        { c: `${K('STOP RECORDING')}, then ${K('SAVE')}. The GPX has the line you rode and your cues; it shows under ${K('TRACK')} and can be shared, published or edited like any other.`,
          run: async s => { await s.tap($('.gfab', s.sc)); const it = await s.menu([['ADD CUE HERE', '+'], ['STOP RECORDING', '■', 'acc']], 'RECORDING'); await s.tap(it('STOP RECORDING')); s.closeMenu(); recStrip(s, ''); s.panel(`<div class="gcard"><div class="label">Recording stopped</div><div style="font-size:11px;color:#5a5a5a;margin:2px 0 4px">2.1 mi · 2 cues</div><div class="grow"><div class="gbtn accent" data-b="sv">SAVE</div><div class="gbtn" data-b="sh">SHARE…</div><div class="gbtn" data-b="ds">DISCARD</div></div></div>`); await s.wait(500); await s.tap(s.btn('sv')); s.panel(''); s.hideFinger(); await s.toast('Saved roadbook-rec-2026-10-27.gpx — it will show under TRACK', 2000); } },
      ] },

    { group: 'Routing on the phone', id: 'edit-cues', title: 'Edit cues on any track', device: 'phone',
      blurb: 'Open any GPX — yours, a friend\'s, one from the library — and add, change or delete the cues on it.',
      steps: [
        { c: `${K('TRACK')} → the file → ${K('EDIT CUES')}. The whole line is shown with its cues; the label counts them.`,
          run: async s => { idleMap(s); s.layer('hill', true); await trackList(s, [['Gravelly Range pre-run.gpx', '212 KB · Oct 19'], ['Madison divide.gpx', '96 KB · Sep 30']]); await s.tap($('[data-h="0"]', s.sc)); await trackSheet(s, 'Gravelly Range pre-run', '23 mi · 4 cues · a recording'); await s.tap(s.btn('editc')); await s.closeSheet(); s.hideFinger(); s.unmark('puck'); trackScene(s); s.panel(`<div class="gcard"><div class="label">Gravelly Range pre-run · 23 mi · 4 cues</div><div style="font-size:11px;color:#5a5a5a;margin:2px 0 4px">Long-press on the line to add a cue. Tap a cue to edit it.</div><div class="grow"><div class="gbtn accent" data-b="sv">SAVE</div><div class="gbtn" data-b="list">CUE LIST</div><div class="gbtn" data-b="fl">FOLLOW</div><div class="gbtn sq" data-b="x">✕</div></div></div>`); await s.wait(600); } },
        { c: `${K('Long-press the line')} where a cue belongs. It snaps to the track (within 150 m), then the usual sheet: arrow, note or danger, words.`,
          run: async s => { await s.longPress([160, 420]); await cueSheet(s, 'Add cue', 'left'); await s.tap($('[data-a="note"]', s.sc)); $('[data-a="left"]', s.sc).classList.remove('sel'); $('[data-a="note"]', s.sc).classList.add('sel'); await s.type($('[data-in]', s.sc), 'Last fuel for 90 miles'); await s.tap(s.btn('save')); await s.closeSheet(); s.hideFinger(); s.marker('c5', 'cue', 160, 420, 'Last fuel'); await s.toast('Note: Last fuel for 90 miles', 1200, true); } },
        { c: `${K('Tap a cue')} to change its words or arrow, or ${K('DELETE')} it. ${K('CUE LIST')} shows them all in order with distances.`,
          run: async s => { await s.tap([60, 250]); await cueSheet(s, 'Edit cue', 'danger'); $('[data-in]', s.sc).classList.remove('ph'); $('[data-in]', s.sc).textContent = 'Washout'; $('.gsheet .grow', s.sc).innerHTML = '<div class="gbtn accent" data-b="save">SAVE</div><div class="gbtn" data-b="del">DELETE</div>'; await s.wait(900); await s.tap($('.gsheet .x', s.sc)); await s.closeSheet(); s.hideFinger(); } },
        { c: `${K('SAVE')} writes the file back (the original is kept as a backup) or as a new one. ${K('FOLLOW')} rides it straight from the editor.`,
          run: async s => { await s.tap(s.btn('sv')); s.hideFinger(); s.panel(`<div class="gcard"><div class="label">Save "Gravelly Range pre-run"</div><div class="grow"><div class="gbtn accent" data-b="ow">OVERWRITE</div><div class="gbtn" data-b="nw">SAVE AS NEW</div></div></div>`); await s.wait(600); await s.tap(s.btn('ow')); s.hideFinger(); s.panel(''); await s.toast('Saved — the old file is kept as .bak', 1600); } },
      ] },

    { group: 'Files', id: 'files', title: 'Get a GPX in and out', device: 'phone',
      blurb: 'Where Roadbook\'s files live on each phone, and the ways a file gets there and leaves.',
      steps: [
        { c: `Roadbook keeps GPX files in its own folder. iPhone: the ${K('Files')} app → On My iPhone → Roadbook. Android: a folder you pick the first time TRACK asks (Downloads, or a Roadbook folder you make).`,
          run: async s => { await s.sheet('Files · On My iPhone · Roadbook', `${[['Bozeman loop (Ride).gpx', '48 KB'], ['Gravelly Range pre-run.gpx', '212 KB'], ['Madison divide.gpx', '96 KB'], ['montana-roads.mappack', '540 MB'], ['marks.gpx', '3 KB']].map(f => `<div class="li">${esc(f[0])}<span class="r">${esc(f[1])}</span></div>`).join('')}`); await s.wait(1200); } },
        { c: `In: AirDrop, Mail, Messages, a download — any .gpx (or .mappack) opened with Roadbook lands in that folder and opens. Or copy files into the folder yourself from a computer.`,
          run: async s => { await s.sheet('Share "Madison divide.gpx"', `<div class="sub">Open with</div>${['Roadbook', 'Maps', 'Mail', 'Files'].map((a, i) => `<div class="li" data-h="${i}">${a}</div>`).join('')}`); await s.wait(500); await s.tap($('[data-h="0"]', s.sc)); await s.closeSheet(); s.hideFinger(); idleMap(s); await trackSheet(s, 'Madison divide', '64 mi · 9 cues · a planner roadbook'); await s.wait(900); } },
        { c: `Out: every ${K('SAVE')} dialog has ${K('SHARE…')} — AirDrop it, mail it, send it to a friend, who opens it with Roadbook on their own maps. ${K('MAPS')} → ${K('EXPORT GPX')} writes your saved marks as one file.`,
          run: async s => { await s.closeSheet(); s.panel(`<div class="gcard"><div class="label">Route: Ennis</div><div style="font-size:11px;color:#5a5a5a;margin:2px 0 4px">57 mi · 1 h 20 · Ride</div><div class="grow"><div class="gbtn accent" data-b="sv">SAVE TO FILES</div><div class="gbtn" data-b="sh">SHARE…</div></div></div>`); await s.wait(500); await s.tap(s.btn('sh')); await s.sheet('Share "roadbook-route-ennis-ride.gpx"', `${['AirDrop', 'Messages', 'Mail', 'Save to Files'].map((a, i) => `<div class="li" data-h="${i}">${a}</div>`).join('')}`); await s.wait(1200); } },
        { c: `The file is plain GPX: any map app opens it, and a roadbook's cues are ordinary waypoints with a type. Nothing is locked in.`,
          run: async s => { await s.wait(500); } },
      ] },

    { group: 'Files', id: 'fetch-code', title: 'Fetch a code from the planner', short: true, device: 'phone',
      blurb: 'The planner parks a roadbook under six letters for a day; the phone fetches it with the code.',
      steps: [
        { c: `On the planner, ${K('Send to phone')} shows a code. On the phone: ${K('≡')} → ${K('TRACK')} → ${K('FETCH CODE FROM THE PLANNER')}.`,
          run: async s => { idleMap(s); s.layer('hill', true); await s.tap($('.gfab', s.sc)); const it = await s.menu(MENU_IDLE); await s.tap(it('TRACK')); await trackList(s, [['Bozeman loop (Ride).gpx', '48 KB · Oct 27']]); await s.tap(s.btn('code')); await s.sheet('Fetch a roadbook', `<div class="sub">The code the planner showed under SEND TO PHONE. It works for 24 hours.</div><div class="in ph" data-in>ABC 123</div><div class="grow" style="margin-top:10px"><div class="gbtn accent" data-b="fetch">FETCH</div></div>`); } },
        { c: `Type the code. This is one of the few moments the app uses the network, and it sends nothing but the code.`,
          run: async s => { await s.type($('[data-in]', s.sc), 'KQ7 M2P'); await s.tap(s.btn('fetch')); await s.closeSheet(); s.hideFinger(); await s.toast('Fetching…', 800); } },
        { c: `The roadbook lands in your folder and opens: ${K('FOLLOW')} it, ${K('EDIT CUES')}, or ${K('EDIT ROUTE')} to re-plan it on the phone's own maps. The code dies after 24 hours; the file is yours for good.`,
          run: async s => { await trackSheet(s, 'Madison divide', '64 mi · 9 cues · from the planner'); await s.wait(1400); } },
      ] });

  // ---- the planner --------------------------------------------------------------------------------------------------
  const P_BOZ = [300, 330], P_ENN = [596, 62], P_NOR = [520, 200];
  const P_DIRECT = 'M300 330 V93 Q448 71 596 62', P_VIA = 'M300 330 H520 V68 Q558 65 596 62';
  const CUES0 = [{ g: '↑', text: 'Depart Bozeman', at: '0' }, { g: '←', text: 'Left onto MT-84', at: '2.1 mi' }, { g: '→', text: 'Right onto US-287', at: '14 mi' }, { g: '↑', text: 'Arrive Ennis', at: '54 mi' }];
  const plannerIdle = (s, mode = 'ride') => { s.pside({ mode }); s.players({ terrain: true }); s.layer('hill', true); };
  const plannerRoute = (s, { cues = CUES0, dist = '54 mi', time = '1 h 15', path = P_DIRECT, name = 'Roadbook', profile = false, mode = 'ride' } = {}) => { s.pside({ mode, hint: false, dist, time, cues, name, profile }); s.clearRoutes(); s.route('p', path, MODES[mode][1], { draw: !s.fast }); };

  SECTIONS.push(
    { group: 'The planner', id: 'planner-route', title: 'Plan a route on the planner', short: true, device: 'planner',
      blurb: 'hondoroadbook.com/plan: the same router and the same maps as the phone, on a desk, with a mouse. Nothing you do there is stored unless you publish it.',
      steps: [
        { c: `${K('Click the map twice')}: a start and an end. Or type a place in the box. The mode chips above the stats pick the kind of road: Ride here.`,
          run: async s => { plannerIdle(s); await s.wait(300); await s.tap(P_BOZ); s.marker('start', 'start', ...P_BOZ); await s.tap(P_ENN); s.marker('pin', 'pin', ...P_ENN); s.hideFinger(); await s.toast('Routing…', 600); plannerRoute(s); await s.wait(1200); } },
        { c: `The sidebar fills: distance and time, the surface bar (paved, gravel, dirt), the legs, and the cues the router wrote. Change the mode and the route is drawn again.`,
          run: async s => { await s.tap(s.cursorOver('[data-mode="fast"]')); s.hideFinger(); plannerRoute(s, { mode: 'fast', dist: '48 mi', time: '58 min', path: 'M300 330 H520 V68 Q558 65 596 62' }); await s.wait(1000); await s.tap(s.cursorOver('[data-mode="ride"]')); s.hideFinger(); plannerRoute(s); await s.wait(800); } },
        { c: `${K('Drag the line')} onto any road or trail to pull the route through it. The drop becomes a lettered stop; drag a stop to move it, click it to delete it.`,
          run: async s => { await s.drag([300, 200], [520, 200]); s.hideFinger(); s.marker('viaA', 'via', ...P_NOR, 'A'); plannerRoute(s, { path: P_VIA, dist: '58 mi', time: '1 h 22', cues: [CUES0[0], { g: '→', text: 'Right onto MT-84', at: '2.1 mi' }, { g: '←', text: 'Left onto US-287 — stop A', at: '21 mi' }, CUES0[3]] }); await s.wait(1200); } },
        { c: `${K('loop')} makes the finish the start (an out-and-back until you drag the line into a circuit); ${K('reverse')} rides it the other way. Dropping END onto START does the same as loop.`,
          run: async s => { await s.tap(s.cursorOver('[data-b="loop"]')); s.hideFinger(); s.unmark('pin'); s.marker('viaB', 'via', ...P_ENN, 'B'); plannerRoute(s, { path: 'M300 330 H520 V68 Q558 65 596 62 Q448 71 300 93 V330', dist: '112 mi', time: '2 h 40', cues: [CUES0[0], { g: '→', text: 'Right onto MT-84', at: '2.1 mi' }, { g: '←', text: 'Left onto US-287 — stop A', at: '21 mi' }, { g: '↶', text: 'Stop B · Ennis', at: '58 mi' }, { g: '↑', text: 'Arrive Bozeman', at: '112 mi' }] }); await s.wait(1200); } },
        { c: `Roads the map marks private are avoided while anything public connects; if the route must use one it is drawn red-dashed and counted as "Posted private" — a heads-up to ask, not a ruling. The link in the address bar carries the whole plan, so it can be shared before it is saved.`,
          run: async s => { await s.wait(800); } },
      ] },

    { group: 'The planner', id: 'planner-cues', title: 'Cues on the planner', device: 'planner',
      blurb: 'The router writes the turns; you add the notes and hazards that make it a roadbook, and reword any turn.',
      steps: [
        { c: `${K('Click the line')} for the menu: Add a cue here, Add a stop here, Inspect this spot.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s); s.fast || await s.wait(300); await s.tap([300, 230]); await s.ctx(300, 230); await s.wait(500); } },
        { c: `${K('Add a cue here')}: the same arrows, note and danger as the phone, with the words to say. A note is read in your words; a danger buzzes before them.`,
          run: async s => { await s.tap(s.cursorOver('[data-c="Add a cue here"]')); await s.modal('Add a cue', `<div class="arrows wide">${['←', '↑', '→', '↶', 'Note', '!'].map((g, i) => `<div data-a="${i}" class="${i === 5 ? 'sel' : ''}"><span>${g}</span></div>`).join('')}</div><div class="in ph" data-in>Words to say</div><div class="grow" style="margin-top:10px"><div class="gbtn accent" data-b="save">Save</div></div>`); await s.type($('[data-in]', s.sc), 'Cattle guard, loose gravel'); await s.tap(s.btn('save')); $('.gsheet', s.sc).classList.remove('on'); s.hideFinger(); s.marker('cue1', 'cue danger', 300, 230); plannerRoute(s, { cues: [CUES0[0], { g: '!', text: 'Cattle guard, loose gravel', at: '1.4 mi', user: true }, ...CUES0.slice(1)] }); await s.wait(800); } },
        { c: `The cue list on the left is the roadbook in order. Click any cue — the router's turns too — to reword it ("left at the red barn"), move the arrow, or delete it. ${K('reset to turns')} starts over from the router's own.`,
          run: async s => { await s.tap(s.cursorOver('.cue:nth-of-type(3)')); await s.modal('Edit cue', `<div class="arrows wide">${['←', '↑', '→', '↶', 'Note', '!'].map((g, i) => `<div data-a="${i}" class="${i === 0 ? 'sel' : ''}"><span>${g}</span></div>`).join('')}</div><div class="in" data-in>Left at the red barn</div><div class="grow" style="margin-top:10px"><div class="gbtn accent" data-b="save">Save</div><div class="gbtn" data-b="del">delete</div></div>`); await s.wait(900); await s.tap(s.btn('save')); $('.gsheet', s.sc).classList.remove('on'); s.hideFinger(); plannerRoute(s, { cues: [CUES0[0], { g: '!', text: 'Cattle guard, loose gravel', at: '1.4 mi', user: true }, { g: '←', text: 'Left at the red barn', at: '2.1 mi', user: true }, ...CUES0.slice(2)] }); await s.wait(600); } },
        { c: `Bike modes get a cue at the foot of every real climb (length, gain, average and steepest grade) and one at the summit; motor modes only the passes. Delete any you do not want. The phone reads them like any other cue.`,
          run: async s => { plannerRoute(s, { profile: true, cues: [CUES0[0], { g: '!', text: 'Cattle guard, loose gravel', at: '1.4 mi', user: true }, { g: '←', text: 'Left at the red barn', at: '2.1 mi', user: true }, { g: '△', text: 'Climb 4.2 mi, ↑1,180 ft, avg 5 %, max 9 %', at: '9 mi' }, { g: '▲', text: 'Summit 6,320 ft · ↓6 mi ahead', at: '13 mi' }, ...CUES0.slice(2)] }); await s.wait(1200); } },
      ] },

    { group: 'The planner', id: 'planner-inspect', title: 'Inspect a spot', short: true, device: 'planner',
      blurb: 'The research desk for any point on the route: what the map knows, what is nearby, and what the web says — each finding one click from becoming a cue.',
      steps: [
        { c: `${K('Click the line')} → ${K('Inspect this spot')}.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s); s.fast || await s.wait(300); await s.tap([520, 150]); await s.ctx(520, 150); await s.tap(s.cursorOver('[data-c="Inspect this spot"]')); } },
        { c: `The sheet opens on aerial imagery of the spot and what the map knows about the road: name, surface, whether it is posted private. Then the nearest fuel, food, supplies, camping (free ones marked), water, beds and repair — with distance and direction, and dropped onto the main map while the sheet is open.`,
          run: async s => { await s.modal('Inspect · 45.5752, −111.6913', `<div class="insp"><div class="aerial"></div><div><div class="sub">US-287 · paved · public</div><div class="li">⛽ Norris Pump &amp; Go <span class="r">0.4 mi N</span></div><div class="li">♨ Norris Hot Springs <span class="r">0.3 mi S · beds, food</span></div><div class="li">▲ Red Mountain campground <span class="r">6 mi SE · free</span></div><div class="li">💧 Hot Springs Creek <span class="r">0.5 mi</span></div></div></div><div class="sub" style="margin-top:8px">Web: <u>food</u> · <u>fuel</u> · <u>camping and beds</u> · <u>free camping</u> · <u>supplies</u> · <u>trip reports</u> · <u>trail guides</u> · <b>all</b></div>`); await s.wait(1400); } },
        { c: `Below that, the web, section by section — food, fuel, camping and beds, free camping, supplies, trip reports and forums, trail and road guides — each searched when you press it, for the named places the map found rather than a coordinate. A box at the top searches the area for anything you type.`,
          run: async s => { const b = $('.gsheet .body', s.sc); const w = document.createElement('div'); w.innerHTML = `<div class="li">Norris Hot Springs — "the best soak on the 287"… <span class="r">via Norris Hot Springs</span> <span class="gbtn mini" data-b="note">+ note</span></div><div class="li">Red Mountain BLM campground — free, 8 sites, river… <span class="gbtn mini" data-b="note2">+ note</span></div>`; b.appendChild(w); await s.wait(1000); } },
        { c: `${K('+ note')} on any finding puts it on the route as a cue at that spot, link included — so the research rides with you and the phone reads it as you get there. ${K('sources')} edits the list of sites each section is confined to.`,
          run: async s => { await s.tap(s.btn('note')); $('.gsheet', s.sc).classList.remove('on'); s.hideFinger(); s.marker('cue1', 'cue', 520, 150, 'Norris Hot Springs'); plannerRoute(s, { cues: [CUES0[0], CUES0[1], { g: '●', text: 'Norris Hot Springs: "the best soak on the 287" ↗', at: '19 mi', user: true }, ...CUES0.slice(2)] }); await s.wait(1200); } },
      ] },

    { group: 'The planner', id: 'planner-layers', title: 'Layers, places and the profile', device: 'planner',
      blurb: 'What the planner can show under and around the route: terrain, land ownership, aerial, places, the library, night — and the elevation profile under the distance.',
      steps: [
        { c: `Top right, the layers. ${K('Terrain')} is on by default; ${K('Land ownership')} draws BLM surface management — public in colour, private clear — with a legend. Good for knowing whose gate that is.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s, { profile: true }); s.fast || await s.wait(300); await s.tap(s.cursorOver('[data-ly="land"]')); s.players({ terrain: true, land: true }); $('.land', s.sc).style.opacity = '.45'; s.hideFinger(); await s.wait(1200); } },
        { c: `${K('Aerial')} swaps the map for imagery (USGS and EOX) to check a surface or a ford. ${K('Places')} drops fuel, food, camping, water, beds and repair from the map itself — click one for ${K('+ stop')} (routes through it, leaves a note) or ${K('+ note')} on the route pointing to it.`,
          run: async s => { await s.tap(s.cursorOver('[data-ly="places"]')); s.players({ terrain: true, land: true, places: true }); s.hideFinger(); for (const [i, [x, y, l]] of [[560, 240, '⛽'], [470, 120, '▲'], [600, 110, '🍴'], [330, 150, '♨']].entries()) s.marker('pl' + i, 'place', x, y, l); await s.wait(1200); } },
        { c: `${K('Roadbooks')} shows the public library on the map (section: Publish and browse). ${K('Night')} is the dark map, kept until you change it.`,
          run: async s => { await s.tap(s.cursorOver('[data-ly="night"]')); s.players({ terrain: true, land: true, places: true, night: true }); night(s, true); s.hideFinger(); await s.wait(1000); night(s, false); s.players({ terrain: true, land: true, places: true }); } },
        { c: `Under the distance: the surface bar (paved, gravel, dirt, posted private) and the elevation profile with gain, loss and the high point. Hover the profile to see where on the map you are.`,
          run: async s => { await s.tap(s.cursorOver('.prof')); await s.wait(1200); } },
      ] },

    { group: 'The planner', id: 'send-to-phone', title: 'Send to phone', short: true, device: 'planner',
      blurb: 'From the desk to the handlebars in six letters.',
      steps: [
        { c: `With the route planned and the cues the way you want them, ${K('Send to phone')}.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s, { name: 'Madison divide' }); s.fast || await s.wait(300); await s.tap(s.cursorOver('[data-b="phone"]')); } },
        { c: `The roadbook is parked under a code for 24 hours. Nothing else about you goes with it: the line, the cues, the name.`,
          run: async s => { await s.modal('Send to phone', `<div class="code">KQ7 M2P</div><ol><li>In Roadbook on the phone: <b>≡ → TRACK → FETCH CODE</b>.</li><li>Type the code. The roadbook lands in your folder and opens for editing or following.</li></ol><div class="sub">The code works for 24 hours, then the file is deleted. 9 cues · 64 mi.</div>`); s.hideFinger(); await s.wait(1600); } },
        { c: `On the phone: ${K('≡')} → ${K('TRACK')} → ${K('FETCH CODE FROM THE PLANNER')}, type it, and the roadbook opens (section: Fetch a code). ${K('Download GPX')} is the same file by hand, for a cable or a cloud folder.`,
          run: async s => { await s.wait(700); } },
      ] },

    { group: 'The planner', id: 'publish', title: 'Publish and browse the library', device: 'planner',
      blurb: 'A roadbook worth sharing goes in the public library, where anyone can find it on the planner map or on the phone.',
      steps: [
        { c: `${K('Publish')} on the planner. Give it a name. The published file keeps only the line and the cues — no times, no device, nothing about you.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s, { name: 'Madison divide' }); s.fast || await s.wait(300); await s.tap(s.cursorOver('[data-b="publish"]')); await s.modal('Publish to the library', `<p>Anyone can then find this roadbook on the map here and in the app, and ride it. The file keeps only the line and the cues — no times, no device, no name but the one below.</p><div class="sub">Name: <b>Madison divide</b></div><div class="grow" style="margin-top:10px"><div class="gbtn accent" data-b="go">Publish</div></div>`); await s.wait(600); await s.tap(s.btn('go')); $('.gsheet', s.sc).classList.remove('on'); s.hideFinger(); await s.toast('Published: Madison divide', 1400); } },
        { c: `From the phone, any opened track has ${K('PUBLISH TO THE LIBRARY')}, with a choice to trim the ends so a ride never starts at your front door.`,
          run: async s => { await s.wait(700); } },
        { c: `Finding them: ${K('Roadbooks')} in the planner's layers draws the library over the map; click one to load it. On the phone, ${K('MAPS')} → ${K('BROWSE ROADBOOKS')} does the same — tap a line, then ${K('OPEN')} to follow or edit it, or ${K('SAVE')} to keep the file.`,
          run: async s => { await s.tap(s.cursorOver('[data-ly="library"]')); s.players({ terrain: true, library: true }); s.hideFinger(); s.route('lib1', 'M200 265 H300 V200 Q260 150 320 100', '#7c3aed', { casing: false, dim: true }); s.route('lib2', 'M520 330 V200 Q560 140 640 130', '#7c3aed', { casing: false, dim: true }); s.route('lib3', 'M640 200 H520 V68', '#7c3aed', { casing: false, dim: true }); await s.wait(1200); } },
        { c: `Published routes come from other riders and nobody checks them: a line may be impassable, private or beyond you. Everything in the terms applies doubly. To take one down, email wiley@machinenusa.com.`,
          run: async s => { await s.wait(700); } },
      ] },

    { group: 'The planner', id: 'download-share', title: 'Download GPX and share a plan as a link', device: 'planner',
      blurb: 'Two other ways out of the planner: the file itself, and a link that carries the whole plan.',
      steps: [
        { c: `${K('Download GPX')} saves the roadbook as a file: the line, the stops, the cues. Any map app opens it; Roadbook on a phone re-plans it on that phone's own maps.`,
          run: async s => { plannerIdle(s); s.marker('start', 'start', ...P_BOZ); s.marker('pin', 'pin', ...P_ENN); plannerRoute(s, { name: 'Madison divide' }); s.fast || await s.wait(300); await s.tap(s.cursorOver('[data-b="gpx"]')); s.hideFinger(); await s.toast('Downloaded madison-divide.gpx', 1400); } },
        { c: `The address bar carries your points and mode the whole time, so copying the URL shares the plan before it is ever saved — paste it to a friend and they see the same route, and can change it without touching yours.`,
          run: async s => { const u = document.createElement('div'); u.className = 'purl'; u.textContent = 'hondoroadbook.com/plan.html#m=ride&p=45.68,-111.04;45.58,-111.69;45.35,-111.73'; s.sc.appendChild(u); await s.wait(200); u.classList.add('on'); await s.wait(1600); } },
        { c: `${K('open GPX')} goes the other way: open any GPX in the planner to edit its cues, then send it to the phone or publish it.`,
          run: async s => { await s.tap(s.cursorOver('[data-b="open"]')); s.hideFinger(); await s.wait(800); } },
      ] });

  // ---- narration: the caption read aloud by the browser's own voice (the way the app speaks on the phone) ---------
  const Narrator = {
    on: false, ok: 'speechSynthesis' in window,
    // the caption as words: the key chips read as their labels, the glyphs as what they are
    text(html) {
      const d = document.createElement('div'); d.innerHTML = html;
      for (const k of d.querySelectorAll('.key')) k.textContent = ' ' + k.textContent + ' ';
      return d.textContent.replace(/≡/g, 'the menu button').replace(/→/g, ', then').replace(/✕/g, 'the X').replace(/★/g, 'the star').replace(/▶/g, 'play').replace(/[·—]/g, ',').replace(/\s+/g, ' ').trim();
    },
    voice() {
      const vs = speechSynthesis.getVoices().filter(v => /^en/i.test(v.lang));
      return vs.find(v => /Samantha|Daniel|Karen|Google US English|Google UK English Female|Microsoft Aria|Microsoft Zira/.test(v.name)) || vs.find(v => v.default) || vs[0] || null;
    },
    /** speaks, resolving when the sentence ends (or at once when narration is off) */
    say(html, s) {
      if (!this.on || !this.ok) return Promise.resolve();
      return new Promise(res => {
        try {
          speechSynthesis.cancel();
          const u = new SpeechSynthesisUtterance(this.text(html)); u.rate = 1; u.lang = 'en-US';
          const v = this.voice(); if (v) u.voice = v;
          let done = false; const fin = () => { if (!done) { done = true; res(); } };
          u.onend = fin; u.onerror = fin;
          // a voice that never fires onend (it happens), or never starts, must not hang the step
          setTimeout(fin, 1500 + u.text.length * 75);
          setTimeout(() => { if (!speechSynthesis.speaking && !speechSynthesis.pending) fin(); }, 700);
          this.u = u; speechSynthesis.speak(u);
        } catch { res(); }
      });
    },
    stop() { try { speechSynthesis.cancel(); } catch {} },
  };
  try { Narrator.on = localStorage.getItem('rb.narrate') !== 'off' && Narrator.ok; } catch { Narrator.on = Narrator.ok; }
  if (Narrator.ok) speechSynthesis.getVoices();   // warms the list on browsers that load it lazily

  // ---- the player ---------------------------------------------------------------------------------------------------
  const stage = new Stage($('#gscene'));
  const nav = $('#gnav'), cap = $('#gcap'), rail = $('#grail'), head = $('#ghead'), next = $('#gnext');
  const bPrev = $('#gprev'), bPlay = $('#gplay'), bNext = $('#gnextb'), bReplay = $('#greplay'), bVoice = $('#gvoice');
  let sec = 0, step = 0, playing = false, running = null;

  // the list of sections, grouped
  {
    let g = ''; const parts = [];
    for (const [i, x] of SECTIONS.entries()) { if (x.group !== g) { g = x.group; parts.push(`<h3>${esc(g)}</h3>`); } parts.push(`<a href="#${x.id}" data-i="${i}" class="${x.short ? 'short' : ''}">${esc(x.title)}</a>`); }
    nav.innerHTML = parts.join('');
    $('#gshort').innerHTML = SECTIONS.filter(x => x.short).map(x => `<a href="#${x.id}">${esc(x.title)}</a>`).join('');
  }

  function paint() {
    const S = SECTIONS[sec];
    for (const a of nav.querySelectorAll('a')) a.classList.toggle('on', +a.dataset.i === sec);
    head.innerHTML = `<h2>${esc(S.title)}</h2><p>${esc(S.blurb)}</p>`;
    cap.innerHTML = `<div class="k">Step ${step + 1} of ${S.steps.length}</div><div class="c">${S.steps[step].c}</div>`;
    rail.innerHTML = S.steps.map((_, i) => `<button data-s="${i}" class="${i < step ? 'done' : i === step ? 'on' : ''}" aria-label="Step ${i + 1}"></button>`).join('');
    bPrev.disabled = step === 0 && sec === 0; bNext.disabled = step === S.steps.length - 1 && sec === SECTIONS.length - 1;
    bPlay.textContent = playing ? '❚❚ Pause' : '▶ Play';
    bVoice.textContent = Narrator.on ? '🔊 Voice on' : '🔇 Voice off'; bVoice.disabled = !Narrator.ok; bVoice.classList.toggle('on', Narrator.on);
    const last = step === S.steps.length - 1, N = SECTIONS[sec + 1];
    next.innerHTML = last && N ? `Next: <a href="#${N.id}">${esc(N.title)}</a>` : '';
  }

  // run the current section from step 0 to `step` quickly, then play `step`
  async function show(autoAdvance) {
    const S = SECTIONS[sec];
    // each run gets its own view of the stage, holding the token it was started with: a run abandoned mid-step
    // (Next pressed, section changed) finds the stage's token moved on and stops at its next wait
    const s = Object.create(stage); s.myToken = ++stage.token;
    stage.reset(S.device);
    paint();
    try {
      s.fast = true; $('#gscene').classList.add('nofx');
      for (let i = 0; i < step; i++) await S.steps[i].run(s);
      // let the fast-forwarded layout settle before transitions come back
      s.fast = false; await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); s.check(); $('#gscene').classList.remove('nofx');
      // the sentence and the taps run together; the step is over when both are
      const spoken = playing ? Narrator.say(S.steps[step].c, s) : Promise.resolve();
      await S.steps[step].run(s);
      await spoken; s.check();
      if (autoAdvance && playing) {
        await s.wait(Narrator.on ? 700 : 1800);
        if (step < S.steps.length - 1) { step++; setHash(S.id); show(true); }
        else { playing = false; paint(); }
      }
    } catch (e) { if (!(e instanceof Abort)) console.error(e); }
  }
  function setHash(id) { if (location.hash.slice(1) !== id) history.replaceState(null, '', '#' + id); }

  function goto(i, st = 0, play = true) { sec = Math.max(0, Math.min(SECTIONS.length - 1, i)); step = st; playing = play; Narrator.stop(); show(true); }
  bPrev.onclick = () => { if (step > 0) step--; else if (sec > 0) { sec--; step = SECTIONS[sec].steps.length - 1; } setHash(SECTIONS[sec].id); show(true); };
  bNext.onclick = () => { if (step < SECTIONS[sec].steps.length - 1) step++; else if (sec < SECTIONS.length - 1) { sec++; step = 0; } setHash(SECTIONS[sec].id); show(true); };
  bPlay.onclick = () => { playing = !playing; if (playing) show(true); else { stage.token++; Narrator.stop(); paint(); } };
  bVoice.onclick = () => { Narrator.on = !Narrator.on && Narrator.ok; try { localStorage.setItem('rb.narrate', Narrator.on ? 'on' : 'off'); } catch {} if (!Narrator.on) Narrator.stop(); paint(); if (Narrator.on && playing) show(true); };
  bReplay.onclick = () => { step = 0; playing = true; show(true); };
  rail.onclick = e => { const b = e.target.closest('button'); if (!b) return; step = +b.dataset.s; Narrator.stop(); show(true); };
  document.addEventListener('keydown', e => { if (e.target.matches('input, textarea')) return; if (e.key === 'ArrowRight') bNext.click(); else if (e.key === 'ArrowLeft') bPrev.click(); else if (e.key === ' ') { e.preventDefault(); bPlay.click(); } });
  function fromHash() { const id = location.hash.slice(1); const i = SECTIONS.findIndex(x => x.id === id); return i < 0 ? 0 : i; }
  window.addEventListener('hashchange', () => { const i = fromHash(); if (i !== sec) goto(i); });
  nav.addEventListener('click', e => { const a = e.target.closest('a'); if (!a) return; e.preventDefault(); const i = +a.dataset.i; setHash(SECTIONS[i].id); goto(i); });
  $('#gshort').addEventListener('click', e => { const a = e.target.closest('a'); if (!a) return; e.preventDefault(); setHash(a.getAttribute('href').slice(1)); goto(fromHash()); $('#gscene').scrollIntoView({ behavior: 'smooth', block: 'start' }); });
  document.addEventListener('visibilitychange', () => { if (document.hidden && playing) { playing = false; stage.token++; Narrator.stop(); paint(); } });
  window.addEventListener('pagehide', () => Narrator.stop());
  // play only while the stage is on screen
  if ('IntersectionObserver' in window) new IntersectionObserver(es => { for (const en of es) if (!en.isIntersecting && playing) { playing = false; stage.token++; Narrator.stop(); paint(); } }, { threshold: 0.2 }).observe($('#gscene'));
  // browsers only speak after a tap or click, so the guide waits for Play rather than starting on its own
  goto(fromHash(), 0, false);
})();
