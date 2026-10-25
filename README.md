# Roadbook / Fold

One codebase, two apps:

* **Roadbook** (`com.hondogarage.roadbook`, by Hondo Garage) — offline motorcycle navigation that
  rides a GPX roadbook at speed: record a pre-run, drop cues and notes, then follow it with the arrow,
  the fill-to-the-turn bar, pips in the helmet and the cue read aloud. Map packs download in-app from
  https://hondoroadbook.com. Website and pipeline: `web/`, `.github/workflows/`.
* **Fold** (`fold.app`) — the stripped-down private version. Same maps, routing and HUD; **no network
  permission at all**, no recording, no speech. Packs are copied to the phone by hand.

Both are built from `app/` as product flavors (`assembleRoadbookRelease`, `assembleFoldRelease`).

## Roadbook

Everything under **Fold** below applies; Roadbook adds:

* **Record a pre-run.** The ● button starts recording (a foreground service keeps GPS going with the
  screen off; a notification shows distance and cue count). A strip appears with **CUE** and **STOP**.
  Stop at a turn or a hazard and tap CUE: pick the arrow (the same ones the HUD draws), or NOTE, or
  DANGER with a severity, and type what to say. STOP → SAVE writes one GPX with the line and the cues.
* **Edit cues on any track.** TRACK → pick a file → **EDIT CUES**: the track is drawn, long-press on
  the line to add a cue there, tap a cue to change or delete it, LIST shows the roadbook as text, SAVE
  writes over the file (or SAVE AS). FOLLOW starts riding it. While following, the ↳ button adds a cue
  at your position (snapped to the track) — for the stop-and-note pass.
* **Routes become roadbooks.** SAVE on the compare screen writes the planned route as a track *with the
  router's turns already in as cues*, so you can open it under EDIT CUES and add your own notes before
  the ride.
* **Spoken cues.** With sound on, about fifteen seconds before each cue the phrase is spoken
  ("Left. onto the ranch road", a note's words, "Danger. washout"), then the pips take over; at the point:
  chime for a turn, two soft notes for a note, a low buzz for a danger. Speech uses the phone's
  text-to-speech engine. GrapheneOS has none by default — install one and pick it under Settings →
  Accessibility → Text-to-speech; without one you get pips and chimes only (a toast says so).
* **Orientation lock** (padlock button, idle and while navigating): holds the screen the way it is
  now, remembered across restarts.
* **In-app packs.** MAPS → DOWNLOAD lists the catalog at `hondoroadbook.com/packs/catalog.json`,
  downloads with resume, imports automatically. IMPORT FILE still takes a `.mappack` you copied yourself.
  Packs of different kinds (roads today; terrain, imagery later) for one region install side by side.

### The GPX cue convention

A cue is a `<wpt>` whose `<type>` names it; `<desc>` (or `<name>`) carries the words.

```xml
<wpt lat="46.0201" lon="-112.0000"><name>Left</name><desc>onto the ranch road</desc><type>cue:LEFT</type></wpt>
<wpt lat="46.0302" lon="-112.0001"><name>Danger</name><desc>washout</desc><type>danger:3</type></wpt>
<wpt lat="46.0403" lon="-112.0000"><desc>water crossing, usually a foot deep</desc><type>note</type></wpt>
```

`cue:` + STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, KEEP_LEFT,
KEEP_RIGHT, UTURN, ROUNDABOUT; `danger:1..3`; `note`. Cues more than 150 m from the line are
ignored, so ordinary waypoints in the same file do no harm. A track with authored cues is followed
exactly as authored: no turns are synthesized from its geometry.

### Website, packs and releases

`wrangler.jsonc` + `web/` is the Cloudflare Worker for hondoroadbook.com (static site + `/packs/*`
from an R2 bucket). `.github/workflows/packs.yml` builds state packs on GitHub's runners and uploads
them to that bucket; `release.yml` signs and publishes the APKs on a tag. Setup steps: `web/README.md`.

---

# Fold

A paper-map substitute for a phone. Offline. No account. No network permission at all.

Fold shows a map, follows a GPX track, or routes you to an address or a point you long-press,
with a turn cue (arrow + distance + a bar that fills as the turn approaches and lands exactly at the turn).
That's the whole feature list, on purpose.

```
fold/
  app/      Android app (Kotlin). MapLibre renders Protomaps vector tiles from local files.
  core/     Pure JVM library: road graph, A* router, route follower, turn cues, GPX, search.
  packer/   Desktop tool: OSM extract → .mappack (tiles + routing graph + address index).
  dist/     fold.apk (signed), fold-packer.jar
  keys/     fold-release.jks — the signing key. Keep it; updates must be signed with the same key.
```

## Privacy model

* The merged Android manifest contains **only** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.
  `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` (which the map library asks for) are
  stripped with `tools:node="remove"`. The app cannot open a socket, so there is nothing for a
  firewall to block. GrapheneOS will not even show a Network toggle for it.
* Map data is read from files you put on the phone yourself (`.mappack`). Fonts, sprites and the
  style are inside the APK.
* Nothing is written except your packs and two settings (night mode, drive/ride, units).
  No history, no recent searches, no saved destinations, no track logs. A seized phone reveals
  that you own maps of a region and nothing else.
* GPS is receive-only. Put the phone in airplane mode and Fold works exactly the same.

## Install on GrapheneOS (or any Android 10+)

1. Get `fold.apk` onto the phone — download it from this chat in Vanadium, or copy it over USB.
2. Open **Files → Downloads**, tap `fold.apk`.
3. Android asks to allow installs from that app (Files or Vanadium) — allow it, then **Install**.
4. Open Fold. Allow **Precise location** ("While using the app"). That is the only permission it will ever ask for.
5. Tap **MAPS → IMPORT .MAPPACK**. The first time Fold asks for the folder that holds your packs
   (pick Downloads); then it lists the `.mappack` files there — tap one, or **IMPORT ALL**.
   Import is a straight unzip into the app's private storage: a 450 MB pack takes under a minute.

Updating later: install the new `fold.apk` over the old one; packs and settings stay.

## Using it

* **Long-press** the map → **ROUTE HERE**. That opens the **compare** view: every mode you have ticked under
  MODES is routed and drawn in its own color over the whole trip, with distance and time per row. Tap a row
  or a line to choose, **VIA** (long-press, then VIA) to pull all of them through a point, **GO** to start.
  Modes: Fast (interstates), Towing (big roads only, no dirt, no tight streets), Backroads (paved two-lanes,
  avoids the interstate), Dirt (gravel and tracks wherever they go), Ride, Quiet ride, Gravel ride.
* **GO TO** → type an address, street or town. Results come from the pack's offline index.
* **TRACK** → pick a GPX file from your folder (the first time, Fold asks which folder on the phone
  holds your files — Downloads is fine — and remembers it). Fold follows the track and synthesizes turn cues from its geometry.
  If you leave the track it shows an arrow and distance back to it.
* **Routes you make and keep.** Long-press → **FROM** sets a start anywhere (a green dot), so you can plan at
  the kitchen table; ROUTE HERE / VIA build it; **SAVE** on the compare screen (or tap the bottom card while
  navigating for the written turn list and SAVE / SHARE) writes a GPX to your folder or hands it to any app
  to send. The file holds the points as a route and the line as a track: any map app shows the line, and when
  a Fold user opens it under TRACK it is re-routed through the same points on *their* maps, in the same mode.
* **★ on the pin panel** saves a mark with a name and a note (water here, gate code…). Marks show on the
  map; tap one to route there, edit or delete it; GO TO lists them. MAPS has EXPORT GPX and BURN for all of them.
  This is the only thing Fold writes beyond packs and settings, and only when you ask.
* The profile button cycles DRIVE → RIDE → QUIET RIDE. Drive leans on interstates: a two-lane highway
  hour counts about 1.65× a motorway hour when choosing, so it takes I-90 round rather than a mountain pass
  that is a little shorter. (ETA is still computed from real speeds.) Use VIA to pull a route anywhere else. Quiet ride treats a bike path as half the cost of its
  length and a highway as five to eight times, so it takes the detour a cyclist would.
* **Sound** (speaker button while navigating, off by default): the only audible cue. As the bar fills,
  short pips rise one octave and come faster; a two-note chime at the turn; one low buzz when you
  leave the route and a short falling note when you are back on it; three rising notes on arrival.
  The pips are synthesized, not recordings, and play as navigation guidance so a Bluetooth helmet or car stereo gets them.
* While navigating the screen stays on, the map is heading-up with you in the lower third,
  and the top card shows the next turn. The bar fills over the last ~15 seconds of approach and is
  full exactly at the turn. **END** stops. Leave the route (gas, a detour, a look at something) and the route stays exactly as it was:
  the banner shows the direction and distance back to it, and nothing is recomputed unless you tap
  **REROUTE**. Sound, if on, buzzes once when you leave and gives a short note when you're back.
* The round buttons: **moon** = night map, **crosshair** = re-center / follow me.
* **Tap the map while navigating** to cycle the view: whole route (north up) → next turn in view → close up.
  Pinch to adjust any of them; the view keeps following you at your zoom until the next tap. Drag to look
  around, which stops following; the crosshair resumes.
* Zoomed out, areas with no installed pack are fogged and each pack is outlined and named, so you can see
  at a glance what the phone holds and what it doesn't.
* **MAPS** lists installed packs, lets you remove them, and switches miles/km.

## Packs: one per state, load as many as you like

Fold draws every installed pack and **routes across them**: each pack's road graph carries the
OpenStreetMap node ids, so the same junction in Montana's and Wyoming's packs is one node to the
router and a Manhattan→Cheyenne route (tested: 988 km, 66 ms) just crosses the line. When more than
six packs are installed the map draws the six nearest to the camera and swaps them as you move.

To build every state: put `fold-packer.jar` and `build-states.sh` in a folder on a computer and run

```
./build-states.sh                      # all 50 states + DC: ~2 hours, ~30 GB (Geofabrik downloads + packs)
./build-states.sh montana wyoming idaho # or just some
FOLD_PACKER_ARGS="--max-zoom 14" ./build-states.sh   # ~40% smaller packs, same roads and names
```

Copy the `.mappack` files to the phone's Downloads folder (USB, or a USB-C drive), open Fold,
**MAPS → IMPORT** → the first time, pick the Downloads folder when asked → **IMPORT ALL**.
A 450 MB pack imports in under a minute from the phone's own storage. A 2.5 GB one (Texas,
California) takes a few minutes. Importing straight from a USB-C drive avoids copying the pack
onto the phone first.

## Other regions, other countries

Any OpenStreetMap extract works: Geofabrik has every country and most states and provinces
(https://download.geofabrik.de). For a piece of a bigger extract, `--bbox` cuts roads, places and
tiles to a rectangle:

```
java -Xmx6g -jar fold-packer.jar --pbf mexico-latest.osm.pbf --bbox -118.5,22.8,-109.3,32.8 --out baja.mappack --name "Baja California"
```

Packs overlap and route across each other, so a Baja pack next to California just works.

## Making map packs

You need Java 17+ on a computer (`brew install openjdk@21`, `sudo apt install openjdk-21-jre`, or
adoptium.net on Windows) and an OpenStreetMap extract of the region:

* Geofabrik: https://download.geofabrik.de → e.g. `north-america/us/montana-latest.osm.pbf`
* or any `.osm.pbf` / `.osm` file from another source.

Then:

```
java -Xmx6g -jar fold-packer.jar --pbf montana-latest.osm.pbf --out montana.mappack --name "Montana"
```

The packer builds the routing graph and address index from the PBF, then fetches vector tiles for
the same bounding box from the newest Protomaps daily planet build (https://build.protomaps.com)
using HTTP range requests, and writes one `.mappack`. Montana takes about 70 seconds and 450 MB;
Colorado 133 seconds and 800 MB. Build per state, not the whole country: a single 25 GB pack has no
advantage over 51 state packs, and the per-state build fits in an 8 GB Java heap.
This is the only step that touches the internet, and it happens on your computer, not the phone.

Options: `--bbox minLon,minLat,maxLon,maxLat` to pick the tile area yourself, `--max-zoom 14` for a
smaller pack with slightly less street detail, `--tiles some.pmtiles` to use tiles you already have,
`--tiles-url` to extract from a different PMTiles archive (any URL that supports range requests, or a local file).

Rough sizes (full detail, zoom 15): a big rural Western state 0.4–1 GB, a dense state 2–4 GB,
the whole US ~25 GB, the planet 100+ GB. Packs can be copied phone-to-phone; they contain nothing personal.

## Building from source

```
export ANDROID_HOME=/path/to/android-sdk     # platform 35 + build-tools 35
gradle :core:test :packer:test               # routing/GPX/HUD tests (set FOLD_GRAPH to a graph.bin for the real-data tests)
gradle :packer:fatJar                        # packer/build/libs/fold-packer.jar
gradle :app:assembleFoldRelease :app:assembleRoadbookRelease   # then zipalign + apksigner (keys/ is git-ignored; see release.yml)
```

## Credits

Map data © OpenStreetMap contributors (ODbL). Basemap tiles and style © Protomaps (ODbL / BSD).
Rendering by MapLibre Native. Fonts: Noto Sans.
