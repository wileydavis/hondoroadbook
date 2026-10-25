// the pack table on the home page, from the catalog
(async () => {
  const tb = document.querySelector('#packTable tbody');
  try {
    const r = await fetch('/packs/catalog.json', { cache: 'no-cache' });
    if (!r.ok) throw new Error(r.status);
    const cat = await r.json();
    const packs = (cat.packs || []);
    if (!packs.length) throw new Error('empty');
    const regions = new Map();
    for (const p of packs) { const k = p.region || p.name; if (!regions.has(k)) regions.set(k, { group: p.group || '', kinds: [] }); regions.get(k).kinds.push(p); }
    const order = ['roads', 'terrain', 'imagery'];
    tb.innerHTML = [...regions.entries()].sort((a, b) => (a[1].group + a[0]).localeCompare(b[1].group + b[0])).map(([name, r]) => {
      const kinds = r.kinds.sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind));
      const built = kinds.map(p => p.built).sort().pop() || '';
      const size = kinds.map(p => mb(p.bytes)).join(' + ');
      const links = kinds.map(p => `<a href="${esc(p.url)}">${esc(cap(p.kind))}</a>`).join(' · ');
      return `<tr><td>${esc(name)}<br><span class="muted" style="font-size:13px">${esc(r.group)}</span></td><td class="sz">${esc(built)}</td><td class="sz">${size}</td><td>${links}</td></tr>`;
    }).join('');
  } catch (e) {
    tb.innerHTML = '<tr><td colspan="4" class="muted">The catalog is being built. Check back soon.</td></tr>';
  }
  function mb(b) { if (!b) return ''; return b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.round(b / 1e6) + ' MB'; }
  function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : ''; }
  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
})();
