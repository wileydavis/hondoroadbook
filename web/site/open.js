// hondoroadbook.com/r/<code>: a roadbook parked for the phone (by the planner's Send to phone, or planned by an assistant
// through /mcp). With the app installed, the phone opens such links in Roadbook directly; this page is for everywhere else.
(function () {
  "use strict";
  const code = (location.pathname.match(/\/r\/([A-Za-z0-9]{6})/) || [])[1]?.toUpperCase();
  const box = document.getElementById("rb");
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const metric = !/^en-US$|^en-LR$|^my/.test(navigator.language || "");
  const fmt = m => metric ? `${(m / 1000).toFixed(m < 100000 ? 1 : 0)} km` : `${(m / 1609.344).toFixed(m < 160000 ? 1 : 0)} mi`;
  const fail = msg => { box.innerHTML = `<h1>Nothing here</h1><p>${esc(msg)}</p><p class="muted">Roadbooks sent this way are kept for a limited time. Ask whoever sent the link for a new one, or plan it again in the <a href="/plan.html">planner</a>.</p>`; };
  if (!code) { fail("That link has no roadbook code."); return; }

  fetch(`/api/parked/${code}`).then(async r => {
    if (!r.ok) { let e = ""; try { e = (await r.json()).error; } catch {} throw new Error(e || `The server answered ${r.status}`); }
    const name = decodeURIComponent(r.headers.get("X-Name") || "Roadbook");
    const expires = Number(r.headers.get("X-Expires") || 0);
    show(name, expires, await r.text());
  }).catch(e => fail(e.message));

  function show(name, expires, gpx) {
    const pts = [...gpx.matchAll(/<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"/g)].map(m => [Number(m[2]), Number(m[1])]);
    const notes = [...gpx.matchAll(/<wpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)">[\s\S]*?<type>note<\/type>/g)].map(m => [Number(m[2]), Number(m[1])]);
    const turns = (gpx.match(/<type>cue:/g) || []).length;
    let len = 0; for (let i = 1; i < pts.length; i++) len += dist(pts[i - 1], pts[i]);
    const unpaved = Number((/<unpaved>(\d+)<\/unpaved>/.exec(gpx) || [])[1]);
    const mode = (/<mode>([A-Z]+)<\/mode>/.exec(gpx) || [])[1];
    const until = expires ? new Date(expires).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" }) : "";
    const spaced = `${code.slice(0, 3)} ${code.slice(3)}`;
    document.title = `${name} — Roadbook`;
    box.innerHTML = `
      <p class="muted">A roadbook for the Roadbook app</p>
      <h1>${esc(name)}</h1>
      <p class="stats">${fmt(len)}${Number.isFinite(unpaved) && len ? ` · ${Math.round(100 * unpaved / len)}% unpaved` : ""}${mode ? ` · ${esc(mode.charAt(0) + mode.slice(1).toLowerCase())}` : ""} · ${turns} turns${notes.length ? ` · ${notes.length} notes` : ""}</p>
      ${sketch(pts, notes)}
      <div class="ctas">
        <a class="cta" href="roadbook://r/${code}">Open in Roadbook</a>
        <a class="cta alt" href="/plan.html#code=${code}">Open in the planner</a>
        <a class="cta alt" href="/api/parked/${code}" download>Download GPX</a>
      </div>
      <div class="card">
        <p><b>On the phone:</b> with Roadbook installed, the link opens in the app. Or in the app: <b>≡ → TRACK → FETCH CODE</b> and type</p>
        <p class="code">${spaced}</p>
        <p>${until ? `The roadbook is kept until ${esc(until)}; once it is in the app it is yours, offline.` : ""} Download the map pack for the area in the app (<b>MAPS</b>) before riding out of signal.</p>
        <p>No Roadbook yet? It is free, with no account: <a href="/#get">get the app</a>.</p>
      </div>`;
  }
  function dist(a, b) { const r = Math.PI / 180, x = (b[0] - a[0]) * r * Math.cos((a[1] + b[1]) / 2 * r), y = (b[1] - a[1]) * r; return 6371000 * Math.hypot(x, y); }
  /** the line, drawn plain: enough to recognise the ride before opening it */
  function sketch(pts, notes) {
    if (pts.length < 2) return "";
    let x0 = 180, y0 = 90, x1 = -180, y1 = -90; for (const [x, y] of pts) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    const k = Math.cos((y0 + y1) / 2 * Math.PI / 180), W = 600, H = 300, pad = 18;
    const s = Math.min((W - 2 * pad) / Math.max(1e-6, (x1 - x0) * k), (H - 2 * pad) / Math.max(1e-6, y1 - y0));
    const ox = (W - (x1 - x0) * k * s) / 2, oy = (H - (y1 - y0) * s) / 2;
    const P = ([x, y]) => [(ox + (x - x0) * k * s).toFixed(1), (H - oy - (y - y0) * s).toFixed(1)];
    const step = Math.max(1, Math.floor(pts.length / 1500));
    const d = pts.filter((_, i) => i % step === 0 || i === pts.length - 1).map((p, i) => (i ? "L" : "M") + P(p).join(",")).join("");
    const [sx, sy] = P(pts[0]), [ex, ey] = P(pts[pts.length - 1]);
    return `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="The route's shape"><path d="${d}" fill="none" stroke="var(--track)" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>${notes.map(n => { const [nx, ny] = P(n); return `<circle cx="${nx}" cy="${ny}" r="4" fill="var(--card)" stroke="var(--ink)" stroke-width="1.5"/>`; }).join("")}<circle cx="${sx}" cy="${sy}" r="6" fill="#16a34a"/><circle cx="${ex}" cy="${ey}" r="6" fill="var(--accent)"/></svg>`;
  }
})();
