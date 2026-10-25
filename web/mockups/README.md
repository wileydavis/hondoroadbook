# App mockups

The marketing images and the manual's screens are built, not photographed: `render2.mjs` renders real
pack tiles with the app's own style (plus route lines from the router, via `fold.packer.SceneKt`), and
`app.js` draws the app's chrome over them 1:1 in dp — the same arrow bar, cards, buttons and layout as
`activity_main.xml`. `features.html` composes the six feature images; `manual.html` the labelled HUD.

```
node render2.mjs s1_nav.json s1_nav.png                      # needs @maplibre/maplibre-gl-native, pmtiles, sharp, and a pack's tiles.pmtiles
python3 -m http.server 8766 &                                # app.js is an ES module
node compose.js http://localhost:8766/mockups/features.html out/ c_hero c_voice c_prerun c_plan c_tablet c_packs
node shot.js "http://localhost:8766/mockups/one.html?s=hero" phone_hero.png 500 1000 2
```
