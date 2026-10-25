import { META, EDIT_CUES, ROWS, DIRT } from './data.js';
import { cueDialog, packsDialog } from './app.js';
const H = META.hero, R = META.rec, E = META.edit, C = META.compare, TB = META.tablet;
const recPos = R.pos;
export const SCENES = {
  hero: { map: './s1_nav.png', geo: H, hud: { turn: 'LEFT', progress: 0.72, dist: '350 ft', street: 'Dry Creek Road' }, puck: { ll: H.pos }, bottom: 'nav', remaining: '11.8 mi · 24 min', speed: '46 mph', navCue: true, scaleLabel: '200 ft', scalePx: 38 },
  night: { map: './s1_nav_dark.png', dark: true, geo: H, hud: { turn: 'LEFT', progress: 0.72, dist: '350 ft', street: 'Dry Creek Road' }, puck: { ll: H.pos }, bottom: 'nav', remaining: '11.8 mi · 24 min', speed: '46 mph', navCue: true, scaleLabel: '200 ft', scalePx: 38, clock: '21:14' },
  record: { map: './s4_rec.png', geo: R, puck: { ll: R.pos }, bottom: 'idle', recording: '● 0:42:13 · 12.4 mi · 3 cues', cues: [{ ll: DIRT[Math.max(0, DIRT.indexOf(R.pos) - 25)], turn: 'LEFT' }], scaleLabel: '200 ft', scalePx: 48, scaleBottom: 150 },
  cuepick: { map: './s4_rec.png', geo: R, puck: { ll: R.pos }, bottom: 'idle', recording: '● 0:42:13 · 12.4 mi · 3 cues', scale: false, dialog: { title: 'Add cue', build: (d, T) => cueDialog(d, T, 'LEFT') } },
  edit: { map: './s2_edit.png', geo: E, bottom: 'edit', cues: EDIT_CUES, editLabel: 'Dry Creek loop · 19.1 mi · 7 cues\nLong-press on the line to add a cue. Tap a cue to edit it.', scaleLabel: '2 mi', scalePx: 64, scaleBottom: 150, pins: [{ ll: DIRT[0], color: '#16A34A' }, { ll: DIRT[DIRT.length - 1] }] },
  compare: { map: './s3_compare.png', geo: C, bottom: 'compare', rows: ROWS, pins: [{ ll: C.start, color: '#16A34A' }, { ll: C.end }], scaleLabel: '5 mi', scalePx: 60, scaleBottom: 230 },
  packs: { map: './s6_idle.png', geo: { center: [-111.6, 45.9], zoom: 6.6, bearing: 0 }, bottom: 'idle', scaleLabel: '50 mi', scalePx: 58, dialog: { title: 'Download map packs', build: (d, T) => packsDialog(d, T, [['Montana', '471 MB · 2026-10-25 · installed', 'AGAIN'], ['Idaho', '520 MB · 2026-10-25', 'GET'], ['Wyoming', '380 MB · 2026-10-25', 'GET'], ['Utah', '610 MB · 2026-10-25', 'GET'], ['Colorado', '812 MB · 2026-10-25', 'GET']]) } },
  tablet: { map: './s5_tablet.png', w: 1280, h: 800, geo: TB, hud: { turn: 'DANGER', level: 3, progress: 0.4, dist: '500 ft', street: 'Washout, then a cattle guard' }, puck: { ll: TB.pos }, bottom: 'nav', remaining: '9.6 mi · 20 min', speed: '38 mph', navCue: true, scaleLabel: '500 ft', scalePx: 60, insetTop: 8 },
  terrain: { map: './s7_terrain.png', geo: { center: [-110.98, 45.47], zoom: 11.6, bearing: 0 }, bottom: 'idle', scaleLabel: '1 mi', scalePx: 52 },
  offroute: { map: './s4_rec.png', geo: R, puck: { ll: R.pos }, bottom: 'nav', off: 'OFF TRACK  ↖ 120 m', remaining: '11.2 mi · 23 min', speed: '12 mph', scaleLabel: '200 ft', scalePx: 48 },
};
