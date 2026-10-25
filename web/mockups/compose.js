const { chromium } = require('playwright');
(async () => { const [,, url, outPrefix, ...ids] = process.argv; const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1700, height: 1100 }, deviceScaleFactor: 2 }); p.on('pageerror', e => console.error('PAGEERROR', e.message)); await p.goto(url); await p.waitForTimeout(1200);
  for (const id of ids) { await p.locator('#' + id).screenshot({ path: `${outPrefix}${id.replace(/^c_/, '')}.png` }); console.log('wrote', id); }
  await b.close(); })();
