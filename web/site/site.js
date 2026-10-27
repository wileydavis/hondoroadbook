// ---- the pack table (coverage page) and the coverage line (home page), from the catalog ---------------
(async () => {
  const tb = document.querySelector('#packTable tbody');
  const cov = document.querySelector('#coverage');
  if (!tb && !cov) return;
  try {
    const r = await fetch('/packs/catalog.json', { cache: 'no-cache' });
    if (!r.ok) throw new Error(r.status);
    const cat = await r.json();
    const packs = (cat.packs || []);
    if (!packs.length) throw new Error('empty');
    const regions = new Map();
    for (const p of packs) { const k = p.region || p.name; if (!regions.has(k)) regions.set(k, { group: p.group || '', kinds: [] }); regions.get(k).kinds.push(p); }
    if (cov) { const n = [...regions.keys()].length; const built = packs.map(p => p.built).sort().pop(); const groups = [...new Set(packs.map(p => p.group).filter(Boolean))]; cov.textContent = `${n} regions are covered — ${groups.join(", ")} (rebuilt ${built})`; }
    if (!tb) return;
    const order = ['roads', 'terrain', 'imagery'];
    tb.innerHTML = [...regions.entries()].sort((a, b) => (a[1].group + a[0]).localeCompare(b[1].group + b[0])).map(([name, r]) => {
      const kinds = r.kinds.sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind));
      const built = kinds.map(p => p.built).sort().pop() || '';
      const size = kinds.map(p => mb(p.bytes)).join(' + ');
      const links = kinds.map(p => `<a href="${esc(p.url)}">${esc(cap(p.kind))}</a>`).join(' · ');
      return `<tr><td>${esc(name)}<br><span class="muted" style="font-size:13px">${esc(r.group)}</span></td><td class="sz">${esc(built)}</td><td class="sz">${size}</td><td>${links}</td></tr>`;
    }).join('');
  } catch (e) {
    if (tb) tb.innerHTML = '<tr><td colspan="4" class="muted">The catalog is being built. Check back soon.</td></tr>';
  }
  function mb(b) { if (!b) return ''; return b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.round(b / 1e6) + ' MB'; }
  function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : ''; }
  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
})();

// ---- one turn, demonstrated: the app's own sounds, pre-rendered as /turn-demo.mp3 by the same arithmetic as
// Tone.kt (pips 520→1040 Hz in 16 steps, 70 ms, 1.6 s apart far out and 0.25 s at the turn; the chime 1040 Hz
// 90 ms then 1320 Hz 160 ms). One clip, played through an <audio> element: on iPhones that plays through the
// mute switch and alongside the system voice, where live Web Audio is silenced. Nothing plays until pressed.
(() => {
  const btn = document.querySelector('#demoPlay'); if (!btn) return;
  const cap = document.querySelector('#demoCap'), hud = document.querySelector('#hudDemo'), dist = document.querySelector('#demoDist'), fill = document.querySelector('#fillRect');
  const snd = new Audio('/turn-demo.mp3'); snd.preload = 'auto';
  let running = false, timers = [], raf = 0;
  const say = (text) => { try { if (!('speechSynthesis' in window)) return; speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(text); u.rate = 0.95; speechSynthesis.speak(u); } catch {} };
  const later = (ms, f) => timers.push(setTimeout(f, ms));
  const reset = () => { dist.textContent = '400 ft'; fill.setAttribute('y', 100); };
  const stop = () => { running = false; cancelAnimationFrame(raf); timers.forEach(clearTimeout); timers = []; try { snd.pause(); snd.currentTime = 0; } catch {} btn.textContent = '▶ Play a turn'; hud.classList.remove('turn'); };
  const ft = (m) => Math.round(m * 3.28084 / 10) * 10;
  btn.onclick = () => {
    if (running) { stop(); cap.textContent = ''; reset(); return; }
    running = true; btn.textContent = '■ Stop';
    // one approach: 120 m out at 12 m/s (27 mph) is ten seconds; the app speaks about fifteen seconds out, so the words come first
    const total = 10, speed = 12;
    const t0 = performance.now();
    cap.textContent = 'Spoken first: "Left. Onto Dry Creek Road."';
    say('Left. Onto Dry Creek Road.');
    snd.currentTime = 0;
    const played = snd.play(); if (played && played.catch) played.catch(() => { cap.textContent = 'The sound could not start. Turn the media volume up and try again.'; });
    let lastPhase = '';
    const tick = () => {
      if (!running) return;
      // the clip's own clock keeps the arrow in step with the pips; fall back to wall time until it starts
      const t = snd.currentTime > 0 ? snd.currentTime : (performance.now() - t0) / 1000;
      const p = Math.min(1, t / total); const m = (1 - p) * speed * total;
      dist.textContent = ft(m) + ' ft';
      fill.setAttribute('y', 100 - 100 * p);
      const phase = t < 1.4 ? 'said' : p < 0.5 ? 'slow' : 'fast';
      if (phase !== lastPhase) { lastPhase = phase; if (phase === 'slow') cap.textContent = 'Pips, slow and low: the turn is still a way off.'; if (phase === 'fast') cap.textContent = 'Rising and quickening as the arrow fills.'; }
      if (p >= 1) { hud.classList.add('turn'); dist.textContent = 'Turn'; cap.textContent = 'The chime: turn now.'; later(1800, () => { stop(); reset(); cap.textContent = 'That was one approach. Play it again, or read on.'; }); return; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
  };
})();

// ---- tap to zoom: the three riding views, live, with the app's buzz pattern -----------------------------
(() => {
  const phone = document.querySelector('#tapPhone'); if (!phone) return;
  const world = document.querySelector('#tapWorld'), label = document.querySelector('#tapLabel'), status = document.querySelector('#tapStatus');
  const tell = (text) => { label.textContent = text; if (status) status.textContent = text; };
  // the screen is 280×560; the puck sits at world (160,520) heading north; the first turn is at (160,300)
  const puck = [160, 520];
  // whole route: fit the route (world 160..470 × 40..760), north up; next turn: puck at 70% down, the turn on screen; close up: tighter
  const views = [
    { name: 'Whole route', scale: 0.52, center: [315, 400], buzz: [220], kind: 'long' },
    { name: 'Next turn', scale: 1.0, center: null, buzz: [40], kind: '' },
    { name: 'Close up', scale: 2.2, center: null, buzz: [40, 90, 40], kind: 'twice' },
  ];
  let i = 0;
  const apply = (v, first) => {
    let tx, ty;
    if (v.center) { tx = 140 - v.center[0] * v.scale; ty = 300 - v.center[1] * v.scale; }
    else { tx = 140 - puck[0] * v.scale; ty = 560 * 0.7 - puck[1] * v.scale; }   // you in the lower third, the turn above
    world.style.transform = `translate(${tx}px, ${ty}px) scale(${v.scale})`;
    tell(first ? 'Tap the map' : v.name);
    if (first && status) status.textContent = 'Try it: tap the map';
  };
  apply(views[1], true);
  i = 1;
  let t;
  const go = () => {
    i = (i + 1) % views.length; const v = views[i];
    apply(v, false);
    phone.classList.remove('buzzing', 'twice', 'long'); void phone.offsetWidth; phone.classList.add('buzzing'); if (v.kind) phone.classList.add(v.kind);
    tell(v.name + (v.kind === 'long' ? ' · one long buzz' : v.kind === 'twice' ? ' · two buzzes' : ' · one buzz'));
    clearTimeout(t); t = setTimeout(() => { tell(v.name); }, 1600);
    try { if (navigator.vibrate) navigator.vibrate(v.buzz); } catch {}
  };
  phone.addEventListener('click', go);
  phone.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
})();
