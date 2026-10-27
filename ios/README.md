# Roadbook for iOS

The same app as Android, on the same engine. Everything that decides a route, reads a GPX, follows the line and
times a cue is the Kotlin in `../core`, compiled into `FoldCore.framework` for iOS. This folder is the native
shell around it: SwiftUI screens, MapLibre for the map (the same packs, the same style, the same fonts and
sprites), Core Location, the system voice, ActivityKit for the lock screen.

What it does, feature for feature with Android:

- Maps from `.mappack` files: download from the catalog (resumable), import from Files/AirDrop/Mail, remove; up to
  six packs drawn at once, nearest first; terrain (hillshade) packs under the roads; day and night styles; coverage
  fog and pack outlines when zoomed out.
- GO TO search over every installed pack (ranked across packs) with saved marks first; long-press for a pin: ROUTE
  HERE, VIA, FROM (plan from a point), save as a mark.
- Compare: every enabled mode routed in turn (Fast, Towing, Backroads, Dirt, Ride, Quiet ride, Gravel ride), rows
  and lines, tap to pick, GO; MODES to choose which; SAVE/SHARE as GPX (route points + line + the turns as cues).
- Navigation: the HUD (arrow-as-bar, distance, road), the bottom card (left, ETA, speed), tap-to-cycle camera
  (whole route → next turn → close up, with the haptic pattern), pinch keeps following, drag stops; off-route
  banner with REROUTE (vias already passed are dropped); turn list.
- Pips, chimes and the voice: the same Tone.kt arithmetic, the NavController's own cue rules (nothing is decided on
  the phone side), spoken through the system voice. Sound goes out as navigation guidance (ducks music, Bluetooth).
- With the screen off: the ride runs in the background (location + audio background modes); the next cue is on the
  lock screen and in the Dynamic Island (Live Activity); every 30 s a checkpoint is written and offered after a crash.
- Battery saver (one button, or automatic under 15 %): the screen goes to a dark HUD at minimum brightness and may
  lock; the GPS eases between cues (distance filter from the controller's Demand); for a cue the Live Activity
  alerts, which lights the lock screen; tap the dark screen to see the map for 20 s.
- TRACK: GPX from Roadbook's folder in the Files app, anywhere in Files, or by six-letter code from the planner;
  follow, edit cues (the cue sheet with the twelve arrows, NOTE, DANGER with severity), add a cue here while
  following, save (over the file or as new, with a verified write and a kept backup), re-route a file's route
  points on your own maps, publish to the library.
- RECORD a pre-run (background, journaled every minute, recovered after a crash), with cues; save or share.
- Marks (★): save, edit, delete, export as GPX, burn; the library (browse the roadbooks others published in the
  part of the map on screen, follow or save one).
- Units, orientation lock, night map, terrain on/off, feedback email with version/phone/last crash.

Nothing leaves the phone except on a button press: catalog and packs, fetch by code, publish, browse. No analytics,
no accounts, no crash reporting service.

## Build it (on a Mac)

You need: macOS with **Xcode 16 or newer** (App Store), a **JDK 17+** (`brew install --cask temurin@17`), and
**XcodeGen** (`brew install xcodegen`). The iPhone needs iOS 17 or newer.

```sh
cd ios
xcodegen generate          # writes Roadbook.xcodeproj from project.yml
open Roadbook.xcodeproj
```

In Xcode: select the **Roadbook** target → Signing & Capabilities → tick *Automatically manage signing* and pick
your team (see the Apple account section below). Do the same for the **RoadbookWidget** target. Plug in the iPhone,
choose it as the run destination, press Run. The first build takes a few minutes: the run-script phase builds the
Kotlin engine (`./gradlew :core:embedAndSignAppleFrameworkForXcode`) and Swift Package Manager fetches MapLibre and
ZIPFoundation.

If the script phase cannot find Java, set `JAVA_HOME` in the scheme's environment or in `project.yml`
(`export JAVA_HOME=$(/usr/libexec/java_home -v 17)` is what it tries).

Without a developer account you can still run on your own iPhone with a free Apple ID (the app expires after 7
days and must be re-installed; Live Activities and background location still work). TestFlight needs the paid
account.

### Where things are

- `project.yml` — the XcodeGen project: two targets (app + widget extension), the FoldCore script phase, the
  packages, and the Android assets (`fonts`, `sprites`, `style`) bundled as folder references.
- `Roadbook/App` — `AppModel` (the port of MainActivity: state and every action), `Prefs`, `Theme`.
- `Roadbook/Map` — `StyleBuilder` (the same JSON the Android app builds), `MapController` (MLNMapView, overlays,
  camera, hit testing), `Coverage`, `Icons`.
- `Roadbook/Ride` — `RideSession` (the port of NavService: owns the `NavController`, GPS, the 4 Hz tick,
  checkpoints, the Demand ladder, the Live Activity), `Location`, `Sound` (Tone), `Speech`, `Recorder`.
- `Roadbook/Packs` — `PackStore` (the pack directory, import with staging and swap, the MultiRouter), `Catalog`
  and the resumable `Downloader`.
- `Roadbook/Data` — `MarkStore`, `Library`, `Files`, `Units`.
- `Roadbook/UI` — `ContentView` (map + HUD + panels), `HUD` (ArrowView, CompassView, ScaleView), `GloveMenu`,
  `Sheets` (search, maps, catalog, track picker, fetch code, cue editor, marks, publish, library item, lists).
- `Shared/RideActivity.swift` — the Live Activity attributes, compiled into both targets.
- `RoadbookWidget` — the lock-screen card and Dynamic Island.

### Known differences from Android, and what to watch in testing

- **GPS interval**: iOS does not take an interval; on a quiet leg the app widens the distance filter instead
  (`Location.tune`). The GPS chip stays on either way, so the saving is CPU, not radio. Compare battery life with
  the Android phone on the same ride.
- **Waking the screen** for a cue is not possible on iOS. In battery saver the Live Activity *alert* lights the
  lock screen with the cue instead (`RideSession.attention`). If the lock screen does not light up, check
  Settings → Roadbook → Live Activities is on.
- **Voice**: the system voice (`AVSpeechSynthesizer`), not Piper. Download an *Enhanced* or *Premium* English voice
  under Settings → Accessibility → Spoken Content → Voices for a better one; the app picks the best installed.
- **Files**: there is no "choose a folder". Roadbook's own folder in the Files app (On My iPhone → Roadbook) is
  where saved routes, recordings and exported marks go, and where it looks for tracks; any other GPX opens through
  the Files browser or by sharing the file to Roadbook. SAVE OVER FILE is offered only for files in that folder.
- **Orientation lock** holds the current orientation through `supportedInterfaceOrientations`; the iPhone's own
  rotation lock in Control Center also works.
- `pmtiles://` tiles need MapLibre Native iOS **6.10 or newer** (set in `project.yml`); if the map comes up empty
  with a "source" error in the console, bump the package version.

## Apple developer account

1. **An Apple ID with two-factor authentication on.** Your existing one is fine. Enrollment refuses an Apple ID
   without 2FA.
2. **Decide Individual vs Organization** — this is the one choice that is hard to undo:
   - *Organization* (Hondo Garage / Machinen USA): the company name shows as the seller on the App Store and in
     TestFlight. Needs a **D-U-N-S number** for the legal entity (free; look it up or request one at
     https://developer.apple.com/enroll/duns-lookup/ — a new one takes about 5 business days), a website, and
     you must be an owner/officer or have signed authority. Apple's review of the enrollment takes a few days.
   - *Individual*: usually approved the same day, but the seller name is your personal name, and switching to an
     Organization later goes through Apple support and takes weeks. If the company name matters for the public
     release, start the D-U-N-S lookup first and enroll as an Organization; you can run on your own phone with a
     free Apple ID while that is pending.
3. **Enroll** at https://developer.apple.com/programs/enroll/ (or in the *Apple Developer* app on the iPhone,
   which is faster for Individuals because it verifies identity with the phone). **$99/year.**
4. **Once approved**: Xcode → Settings → Accounts → add the Apple ID; the team appears. Tick *Automatically manage
   signing* on both targets and run on the phone.
5. **Beta testers (TestFlight)**: App Store Connect → My Apps → + New App (bundle id `com.hondogarage.roadbook`,
   create it first under Certificates, Identifiers & Profiles, or let Xcode register it). In Xcode: Product →
   Archive → Distribute App → TestFlight. Internal testers (up to 100 people on your team) get builds at once; a
   public TestFlight link (up to 10,000 external testers) needs one short Beta App Review for the first build.
   Each build is good for 90 days.
6. **What the app needs declared** (already in `Info.plist`): location when-in-use and the background *location*
   and *audio* modes, Live Activities, file sharing. App Review will ask why background location: "turn-by-turn
   navigation with the screen off" is the accepted answer; the blue indicator shows while a ride runs.

## Not done yet

- A Piper voice (sherpa-onnx) in place of the system voice, so both phones sound the same.
- The pre-rendered demo and the website's iOS install link once TestFlight is live.
