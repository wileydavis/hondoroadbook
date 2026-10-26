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
    if (cov) { const n = [...regions.keys()].length; const built = packs.map(p => p.built).sort().pop(); cov.textContent = `${n} regions are covered (rebuilt ${built})`; }
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

// ---- one turn, demonstrated: the app's own sounds, rendered here by the same arithmetic as Tone.kt ------
// (22,050 Hz, a sine with a touch of second harmonic, 10 ms ramps; pips 520→1040 Hz in 16 steps, 1.6 s apart
// far out and 0.25 s at the turn; the chime 1040 Hz 90 ms then 1320 Hz 160 ms). Nothing plays until pressed.
(() => {
  const btn = document.querySelector('#demoPlay'); if (!btn) return;
  const cap = document.querySelector('#demoCap'), hud = document.querySelector('#hudDemo'), dist = document.querySelector('#demoDist'), fill = document.querySelector('#fillRect');
  const RATE = 22050;
  let ac = null, running = false, timers = [];
  const render = (notes, gap = 0) => {
    const total = notes.reduce((a, n) => a + n[1], 0) + gap * (notes.length - 1);
    const buf = ac.createBuffer(1, Math.ceil(total * RATE / 1000), RATE); const out = buf.getChannelData(0);
    let pos = 0;
    notes.forEach(([f, ms], i) => {
      const len = Math.floor(ms * RATE / 1000); const ramp = Math.min(len / 4, RATE / 100);
      for (let k = 0; k < len; k++) {
        const env = k < ramp ? k / ramp : k > len - ramp ? (len - k) / ramp : 1;
        const v = Math.sin(2 * Math.PI * f * k / RATE) * 0.8 + Math.sin(4 * Math.PI * f * k / RATE) * 0.2;
        out[pos + k] = v * env * 0.6;
      }
      pos += len + (i < notes.length - 1 ? Math.floor(gap * RATE / 1000) : 0);
    });
    return buf;
  };
  const play = (buf) => { const s = ac.createBufferSource(); s.buffer = buf; s.connect(ac.destination); s.start(); };
  const pip = (progress) => { const step = Math.floor(Math.max(0, Math.min(1, progress)) * 16); play(render([[520 * Math.pow(2, step / 16), 70]])); };
  const chime = () => play(render([[1040, 90], [1320, 160]], 20));
  const pipInterval = (p) => 1600 - 1350 * Math.max(0, Math.min(1, p));
  const say = (text) => { try { if (!('speechSynthesis' in window)) return; speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(text); u.rate = 0.95; speechSynthesis.speak(u); } catch {} };
  const later = (ms, f) => timers.push(setTimeout(f, ms));
  const stop = () => { running = false; timers.forEach(clearTimeout); timers = []; btn.textContent = '▶ Play a turn'; hud.classList.remove('turn'); };
  const ft = (m) => Math.round(m * 3.28084 / 10) * 10;
  btn.onclick = () => {
    if (running) { stop(); cap.textContent = ''; dist.textContent = '400 ft'; fill.setAttribute('y', 100); return; }
    if (!ac) ac = new (window.AudioContext || window.webkitAudioContext)();
    if (ac.state === 'suspended') ac.resume();
    running = true; btn.textContent = '■ Stop';
    // one approach: 120 m out at 12 m/s (27 mph) is ten seconds; the app speaks about fifteen seconds out, so the words come first
    const total = 10000, speed = 12;
    const t0 = performance.now();
    cap.textContent = 'Spoken first: "Left. Onto Dry Creek Road."';
    say('Left. Onto Dry Creek Road.');
    let nextPip = 1400;
    const tick = () => {
      if (!running) return;
      const t = performance.now() - t0; const p = Math.min(1, t / total); const m = (1 - p) * speed * total / 1000;
      dist.textContent = ft(m) + ' ft';
      fill.setAttribute('y', 100 - 100 * p);
      if (t >= nextPip && p < 1) { pip(p); nextPip = t + pipInterval(p); cap.textContent = p < 0.5 ? 'Pips, slow and low: the turn is still a way off.' : 'Rising and quickening as the arrow fills.'; }
      if (p >= 1) { chime(); hud.classList.add('turn'); dist.textContent = 'Turn'; cap.textContent = 'The chime: turn now.'; later(1800, () => { stop(); dist.textContent = '400 ft'; fill.setAttribute('y', 100); cap.textContent = 'That was one approach. Play it again, or read on.'; }); return; }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  };
})();
