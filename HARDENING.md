# Hardening pass, 2026-10-25

Response to the independent audit of commit `5a876b1` (findings NAV-01…06, SEC-01, REL-01/02, DATA-01…05,
MAP-01…03, AUDIO-01, ROUTE-01). Each finding was re-checked against the current code before anything
was changed. Status: **verified & fixed**, **verified & mitigated** (reduced, with a note on what remains),
**verified, open** (scheduled, not in this pass), or **rejected**.

| # | Finding | Status | What changed | Test |
|---|---|---|---|---|
| NAV-04 | Planned routes ended short of / beyond the destination | **fixed** | `Router.appendArrival` builds the last piece in travel order, from the node reached to the snapped destination, and ends exactly on it (the old code kept the points on the far side of the snap and never appended the destination). | `RouterEndpointTest` — 7 cases: both directions, with/without interior geometry, same-edge, around a corner, via join. All 6 geometry cases fail on the old router, pass now. |
| NAV-02 | A danger at the same point as a turn was never delivered | **fixed** | Cues within 20 m along the route form a *group*: one instruction. `NavView.group` / `phrase()` / `hazard`; the HUD shows the danger's arrow and both texts; speech says "Left. Danger. washout". Groups crossed in one update without having been the current instruction go to `Follower.drainMissed()`; the app announces missed dangers/notes ("Passed. …") and plays their tone. | `FollowerTest.dangerAtTheTurnIsDeliveredWithIt`, `sparseFixesCrossingTwoCuesReportTheSecond` |
| NAV-03 | Stale GPS shown as live guidance | **fixed** | `FixState` NONE / VALID / DEGRADED (accuracy > 50 m or 5–15 s old) / STALE (> 15 s). STALE: dead reckoning stops, speed reads 0, the HUD shows "NO GPS · age", no pips/speech. NONE: "WAITING FOR GPS" (the old core reported *on route* before any fix). DEGRADED: distance shown with "?". Out-of-order fixes are rejected; a > 120 m/s jump is ignored unless the next fix confirms it (then accepted as a real move; after a 60 s gap, accepted at once). | `FollowerTest.staleGpsIsNotLiveGuidance`, `outOfOrderAndTeleportingFixesAreIgnored`; `RealDataTest.followSimulatedDrive` (Boulder graph) still passes |
| NAV-01 | Loop / out-and-back matched the wrong leg; arrival latched from one bad fix | **mitigated** | Matching now scores candidates by distance *plus* a plausibility penalty: a segment further along than the rider could have travelled since the last fix (1.5 × speed × dt + 2 × accuracy, min 60 m), or well behind, costs extra metres. Arrival requires having been matched within 300 m of the end first (ordered progress). | `FollowerTest.loopStartIsNotArrival` (the audit's case: 2 m from the start of a 3.8 km loop — old code: arrived), `loopRiddenEndsAtArrivalInOrder`, `outAndBackStaysOnTheRightLeg`. **Remaining:** this is a constrained nearest-match, not a full stateful matcher with heading; figure-eights with long shared sections at speed are not yet tested. Tier 2. |
| SEC-01 | A truncated graph could spin a worker forever | **fixed** | `Bytes.get` bounds-checks the whole range and refuses zero-progress reads; `Graph` validates header counts, section extents, the bbox and sample edges on open; a pack's graph is opened in staging *before* it may replace anything; damaged installed packs are listed under MAPS and excluded from routing. | `GraphIntegrityTest` — empty, 5-byte, header-only, truncated, bad magic, absurd/negative counts, garbage body: all rejected in < 2 s |
| REL-01 | GPX limits late; cue snapping quadratic; route built on the UI thread for a dialog | **fixed** | Bytes are capped *while* reading (64 MB), plus 2 M points / 20 k cues caps. Cue snapping uses a 0.01° grid index. The load dialog no longer builds the route to show a length. | `CuePerfTest`: 100 000 points + 5 000 cues, 18.1 s in the audit → 0.6 s |
| DATA-01 | Recording recovery deleted before the save succeeded | **fixed** | The journal is deleted only after the written file is read back at full size. Cancel, a read-only grant, a full disk: the recording stays and ● offers it again ("Unsaved recording"). SHARE keeps it until SAVE or DISCARD. | manual (SAF has no JVM harness); logic is in `offerSaveRecording` / `askNameAndWrite(onDone)` / `writeToFolder(): Boolean` |
| DATA-02 | Sticky service restart announced recording while dropping fixes | **fixed** | On a null-intent restart the service resumes from the journal (`Recorder.resumeAfterRestart`) and keeps recording; with nothing to resume it stops itself instead of posting a false "Recording" notification. | manual (process death) |
| DATA-03 | Night-mode recreation lost the editor's track state | **fixed** | Track arrays, name, URI and cues live in the retained `NavSession`; the editor label and cue markers are redrawn after recreation. | manual |
| DATA-04 | Folder grant persisted read-only → saves failed after reboot | **fixed** | `takePersistableUriPermission` with READ and WRITE; saves use `writableFolder()` and re-ask for the folder when the stored grant is read-only. | manual |
| DATA-05 | Mark writes could crash the UI; overwrites truncated first | **fixed** | `MarkStore` writes `marks.gpx.tmp` and renames; memory changes only after the file is on disk; UI callbacks are wrapped. SAVE OVER FILE keeps a copy of the old GPX in the app cache, verifies the new size, and restores the old bytes if the write came up short. | manual |
| MAP-01 | A failed tile fetch could still produce a publishable pack | **fixed** | Fetches are futures; any failure or short read fails the extraction and deletes the scratch file. Every written archive is reopened and verified (tile count, every entry has bytes, sampled gzip magic). The pack workflow runs `:core:test :packer:test` before building. | `ExtractFailureTest` — clean extract verifies; a failing range and a short read both abort with no archive left |
| MAP-02 | Resume could mix two pack versions | **mitigated** | Partial downloads are named for the catalog entry's build date, so a newer build never resumes onto old bytes. **Remaining:** no end-to-end digest; packs are rebuilt monthly, so the window is a day. A catalog SHA-256 is the clean follow-up. |
| MAP-03 | Replacement removed the working pack before the new one was in place | **fixed** | Order is now old → `.old-slug`, staging → slug, delete `.old-slug`; a failed second step puts the old pack back. `scan()` first runs `recover()`: an `.old-` left by a crash with no live pack is restored; leftover staging is removed; dot-directories are never listed as packs. | manual (interrupt at each step) |
| AUDIO-01 | Speech could play after mute or END | **fixed** | `stop()` bumps the generation so queued synthesis is dropped; playback starts under a lock after a last generation check. | manual (helmet) |
| NAV-05 | GPX track segments were joined with an invented connector | **open** | Tier 2: preserve `<trkseg>` boundaries as explicit gaps. |
| NAV-06 | Cues cannot identify a pass/lap at a shared coordinate | **open** | Tier 2: optional route-relative offset in the cue waypoint (`<extensions>`), used when present. |
| REL-02 | No navigation foreground service; process death loses the ride | **open** | Tier 2: foreground service for navigation with a private session checkpoint. The biggest remaining gap for all-day use. |
| ROUTE-01 | Turn restrictions, barriers and access tags ignored by the router | **open** | Tier 2/3: packer keeps restriction relations and barrier nodes; router checks transitions. Affects planned routes only, not followed roadbooks. |
| ROUTE-02 | A destination or via snapped to an island (a gated driveway, a trail stub) made the whole route fail | **fixed** | `Router.snapCandidates` + `reachable`; `MultiRouter.snapConnected` takes the nearest candidate that leads somewhere. Used by the routing service and the planner; the phone's ROUTE HERE still uses the plain snap (next release). |
| NET-01 | Roadbook's network surface grew with the planner (fetch code, publish, browse) | **by design** | Each is a button; none sends an identifier; published files are rewritten server-side to line + cues; park codes expire in 24 h; the routing container sees coordinates only, logs no bodies. |
| — | "Tolerance = 2 × accuracy is a defect" | **rejected** | Deliberate: canyon fixes at 30–50 m accuracy would otherwise flap off-route. The honest fix is the DEGRADED state (done), not a tighter tolerance. |

## Round 2 (follow-up audit of 5a545c3, 25 Oct 2026)

| ID | Finding | Status | What changed |
|---|---|---|---|
| WEB-01 | Stored XSS through the `mode` metadata of a published roadbook | **fixed** | `gpx.js` accepts only the seven known modes (anything else is dropped at upload); the library sheet renders only known modes; every page now carries a Content-Security-Policy (`script-src 'self'`, no inline scripts — the home page's catalog table moved to `site.js`, MapLibre runs from its CSP build with a same-origin worker). |
| BACKEND-01 | `/packs/parked/…` and `/packs/library/…` served raw from R2, bypassing expiry and hiding, with public cache headers | **fixed** | `/packs/*` is a positive allowlist: `catalog.json`, `na-overview.pmtiles`, `<cc>/<region>-<kind>.(mappack|json)`. Parked and library objects are reachable only through `/api`. (Worker responses are not stored in Cloudflare's cache, so there was nothing to purge at the edge; browsers may hold copies until their own TTL lapses.) |
| BACKEND-02 | Trimming silently published the untrimmed ends when the track was short or had few points | **fixed** | `trimEnds` returns null whenever the ride would not survive; `publish` then refuses with the reason instead of publishing whole, and double-checks that both ends moved. Two- and three-point lines are interpolated, not skipped. |
| BACKEND-03 | The router logged every request URL (route points, searches); the site said nothing was stored | **fixed** | The container logs method, path, status and milliseconds; errors log the exception class only. The planner footer and manual say what the 24-hour hand-off stores and which third parties the browser talks to. Cloudflare's own invocation logs are the operator's setting to review (Workers → Observability). |
| BACKEND-04 | NaN coordinates loaded every pack; no admission control | **fixed** | Every coordinate must be finite and in range before the catalog is consulted (tests in `InputTest`); bbox ≤ 12° a side and ordered; ≤ 60 points and ≤ 4,000 km crow-flight per request; the HTTP executor has 8 workers and a queue of 16 — beyond that 503 at once; the routing lock waits 30 s, not 90. |
| BACKEND-05 | Work before admission; non-atomic daily counter; park without quota | **fixed** | The daily allowance is reserved first (`INSERT … ON CONFLICT … RETURNING n`, atomic), for both publish (40) and park (60); the body is read in chunks and abandoned at 6 MB; the parser caps points (200k), cues (5k) and words (200 chars); cue filtering uses a cell index (5k cues × 100k points in 0.7 s instead of a billion comparisons). Brave queries are cached a day; a per-day cap on them is still open. |
| BACKEND-06 | Tile and zip-directory caches keyed by build date only | **fixed** | Keys carry the catalog's byte size as well; a directory whose archive size disagrees with the catalog is refused (409) rather than mixed. |
| BACKEND-07 | A negative `limit` removed the cap | **fixed** | integers 1–500 only; bbox must be finite and ordered. |
| BACKEND-08 | Async endpoint failures escaped the JSON error wrapper | **fixed** | every endpoint is awaited inside the boundary; client errors return their status (400/405/413/429), server failures 502 with no internals. |
| CORE-02 | The cue grid dropped a hazard on any segment spanning more than 20 cells | **fixed** | long segments are kept on a separate list every cue checks; `LongSegmentCueTest` puts a level-3 washout halfway along a 33 km straight and finds it. The same index shape is used by the Worker and the planner. |
| CORE-05 | Hillshade shifted one tile east | **fixed** (builder) | the crop started at column 0 of the kernel's output instead of 255 (one apron tile minus the trimmed pixel). `packer/terrain/test_alignment.py` fails on the old arithmetic and passes on the new, and runs in CI before every terrain build. **Every published terrain pack is being rebuilt**; until a state's rebuild lands its hillshade is still offset. |
| WEB-02 | A cue added from the menu landed at the segment's end vertex | **fixed** | `alongLine` returns the projected point; the menu, cue creation and stop insertion use it. |
| WEB-03 | A failed reroute left the old route exportable under new points | **fixed** | points revert to the last route that succeeded (`lastGood`), with a message; drag previews no longer exist (rubber band + one route on release), so no mixed states. |
| WEB-04 | Reverse kept LEFT as LEFT and stale distances | **fixed** | reverse builds a new array (caches invalidate), mirrors directional turns, and flags cues with words or roundabout exits for review. |
| WEB-05 | rte + trk files tripled on import | **fixed** | tracks and route points parse separately; a route-only file loads as routing points. |
| WEB-06 | Quadratic cue snapping on the UI thread, unbounded import | **mitigated** | cell-indexed `alongLine`; 64 MB file / 200k points / 5k cues caps with messages. A Web Worker remains open. |
| WEB-07 | Elevation fetched from AWS without disclosure | **by design, disclosed** | the planner footer lists every third party the browser talks to; imagery, land and Wikipedia are opt-in by feature already. |
| WEB-08 | One malformed cue crashed import after replacing the editor state | **fixed** | the file is parsed and validated completely before any state changes; a bad file leaves the plan untouched and says why. |
| WEB-09 | No unsaved-edit warning | **fixed** (warning) | leaving with authored cues or a loaded track asks first. The URL carries routing controls only, as the manual says. |
| WEB-10 | Permanent negative cache for missing packs; stale viewport commits | **fixed** | a 404 is remembered for two minutes; transient failures are not; a viewport generation counter drops stale results. The style is applied before the checks run, so first paint no longer waits on fifty HEADs. |
| CORE-01 | Same-road out-and-back | **open** (Tier 2) | stateful matcher, as before. |
| CORE-03 | Cross-pack bridge hops to an edge endpoint up to ~1 km away and omits that geometry from the length | **open** (Tier 2) | needs a virtual node at the true join; planned with the matcher work. |
| CORE-04 / CORE-06 / APP-01..03, APP-05 / NAV-05 / NAV-06 / ROUTE-01 | as in round 1 | **open** (Tier 2) | unchanged. |
| APP-04 | navigation stops with the screen off (no foreground service; GPS throttled in the background) | **fixed** (26 Oct) | `NavService`: foreground service of type location for the whole navigation, its own 1 Hz GPS request, a partial wake lock, a notification with the next cue and an End button; the HUD tick drops to 4 Hz and camera moves pause while the activity is stopped. Route state already lived in `NavSession`. Remaining gap: if the system destroys the stopped activity under memory pressure the follower keeps its place but the sound logic, which lives in the activity, pauses until it is reopened. |
| BACKEND-09 | Undici advisories in dev tooling | **deferred** | dev-only; bump with the next wrangler release. |

Tests: `./gradlew :core:test :packer:test` — 37 tests, 0 failures. The six dataset-dependent tests now report as
**skipped** (with the reason) when `FOLD_GRAPH` / `FOLD_SEARCH` / `FOLD_MT` / `FOLD_WY` are unset, instead of
passing silently; with the datasets they run (verified: 37 passed, 0 skipped).

Rider-facing changes: the "WAITING FOR GPS" / "NO GPS · age" banner; a "?" after the distance on poor accuracy;
grouped cue wording; late "Passed. …" announcements for missed hazards; "Unsaved recording" offered from ●;
damaged packs listed under MAPS. GPX format: unchanged. Privacy/storage: unchanged (one extra cache file,
`last-overwritten.gpx`, kept after SAVE OVER FILE).

Still needs real hardware: GPS replay of a looped course with crossings; fix loss in a canyon and reacquisition;
process death mid-ride and mid-recording; a read-only folder after reboot; helmet audio on END; a multi-hour soak.

## Access evidence (26 Oct 2026, overnight pack rebuild)

ROUTE-01, first slice: the packer now reads OpenStreetMap access tags. A car-class way tagged
`access=private` (or `motor_vehicle` / `vehicle` / `motorcar` `=private`), and any way through a locked or
private gate, stays routable but carries a `PRIVATE` edge flag (`GraphFormat.PRIVATE`). The router weights
those edges ×8 (`Speeds.PRIVATE_PENALTY`), so a route only uses them when nothing else connects; the planner
draws such runs red-dashed, lists "Posted private" in the surface bar, and Inspect says so for the road under
the point. Packs also carry `pois.tsv` (fuel, food, camping, water, lodging, repair, towns with a 1–3
prominence) that `/near` serves with distance tiers (everything within 3 km, prominent places to 15 km,
towns/fuel/campgrounds to 60 km). Turn restrictions and barrier transitions remain open. The model is
evidence, not verdict: the tag is shown as what OSM says, never as a legal ruling; PAD-US and MVUM are the
next sources, to be shown the same way.

## Round 3 (audit of 74fed29, 26 Oct 2026) — dispositions

Implemented the same day unless marked otherwise. "Fixed" means the audit's counterexample now behaves as the
regression criteria ask and a test or probe covers it; "partial" names what remains.

| ID | Status | What changed |
|---|---|---|
| WEB-13 | **fixed** | Cues carry a stable `id`; the editor binds to it and resolves at save/delete (gone → told); promoting a generated cue clears `climb`; `climbCues` regenerates only cues it still owns; a route replacement closes an open editor. |
| WEB-17 | **fixed** | A pending note records the request number and the intended coordinate; it is committed only by that request's route and only if the stop landed within 1 km; a failed route, Clear or import drops it with a word. |
| WEB-11 | **fixed** | `alongLine` stops only when the best found is within the proven bound of every unvisited ring (edge distance + R cells, in metres at that latitude); otherwise the exact scan. 15,000 random points on cell edges agree with brute force. |
| WEB-12 | **fixed** | Route-only files over 60 stops are no longer truncated silently: the start, the finish and 58 evenly spread stops are kept and the status says so in red. |
| WEB-14 | **fixed** | `addNoteHere` projects the finding onto the route; when the spot is off the line the words carry the distance and direction. Both Inspect and the place popup agree. |
| WEB-16 | **fixed** | A warning raised during `setRoute` survives the end of "Routing…" (`clearRoutingStatus`); Inspect shows the snap's `private` / `privateBike` evidence (the line had been lost in an edit; the audit was right). |
| WEB-04 | **fixed** | Reverse flags every cue with authored words, mirrored or not. |
| WEB-15 | **fixed** | Inspect has a generation; closing the sheet retires it; every DOM/map commit and action registration checks it. |
| WEB-10 | **fixed** | An unchanged viewport re-checks packs that are still unknown; a negative entry's expiry triggers the check. |
| WEB-06 / CORE-04 | open | Dense-lap work still synchronous (no Worker/off-thread yet). The index is budgeted (below). |
| WEB-03 / WEB-09 | open | Export during a pending request; no local checkpoint. |
| CORE-07 | **fixed** | Public-first routing: a first pass treats posted-private edges as impassable for the profile; only when nothing connects (or a point sits on one) does the penalised pass run. Access evidence per vehicle: `PRIVATE` (motor) and `PRIVATE_BIKE` (bicycle); `motor_vehicle=private` + `bicycle=yes` does not detour a bike. Graph version 3 / pack format 3; readers accept 2 and 3 (a v2 pack's meaning is unchanged); v2 readers refuse v3 with "update Roadbook". `PrivateAccessTest`. Packs rebuilt. |
| BACKEND-02 | **fixed** | With a trim, cues are judged against the untrimmed line and must belong to the kept stretch by their position along it; a cue at a trimmed-off end never survives a hairpin. Probe: hairpin fixture. |
| BACKEND-05 | **fixed** | `segmentIndex` has a total-entry budget (2 M); segments past it or over 400 cells go to the always-checked list; `/park` bounds route length (5,000 km) and reports the cues actually kept. |
| BACKEND-04 | **fixed** | Warmer: two threads, an 8-deep bounded queue, one reservation per pack (`WarmTest`: 100 warms → 1 job); HTTP admission inside the handler (8 permits, 16 waiting ≤ 2 s) answers 503 + Retry-After instead of a reset. Memory-budget eviction: open. |
| BACKEND-06 | **partial** | Tile identity is the archive's live size **and ETag** (R2's is the upload checksum: same-size replacement changes it), cached a minute; the JVM pack stamp is build date + bytes; the packs workflow copies every built file with `--ignore-times`; the mirror's size shortcut is documented as the catch-up tool with a `force` input. Not done: hash-named immutable objects and an atomic snapshot across index/range reads (the one-minute window remains). |
| BACKEND-03 | **fixed** | `observability.logs.invocation_logs: false` in wrangler.jsonc; the API error log carries the endpoint name only, never a code or id. Retention is Cloudflare's default; nothing of ours keeps URLs. |
| BACKEND-10 | **fixed** | `web/site/_headers` carries the same CSP and headers as the Worker (a test asserts the two strings match), so static HTML served without the Worker is covered. |
| BACKEND-09 | **fixed** | wrangler 4.153.0 → undici 7.30.1; `npm audit` clean; `wrangler deploy --dry-run` passes. |
| CORE-08 | **fixed** | Unnamed fuel/food/lodging/repair kept (labelled by kind); every TSV field is cleaned of tabs/newlines/controls; `/near` reports `partial` when a loaded pack has no index, and the planner then uses the tiles. |
| APP-02 / APP-03 | **fixed** | Overwrite: a per-document backup is written before the file is touched and never replaced by unverified content; any failure after opening for write restores from it; verification reads the bytes back (no stat size, so pipe-backed providers work); when restoration itself fails the original is offered as a new file. New-file saves verify by read-back too. Serialisation still on the UI thread. |
| APP-05 | **fixed** | A foreground-start or GPS-registration failure sets the recorder not running with a reason the UI shows; the journal stays. |
| APP-01 | **fixed** (device check pending) | `stop()` and the final check + `play()` share one lock, so nothing starts after `stop()` returns; missed hazards are spoken as one combined phrase instead of latest-wins. |
| APP-04 | partial | As round 3 found: guidance is still activity-owned; the service keeps the process and GPS alive. Service-owned controller + checkpoint: next app milestone. |
| CORE-01 / CORE-03 / CORE-06 / NAV-05 / NAV-06 | open | unchanged, deferred. |
| Lint | **fixed** | `app:tint` on the two ImageButtons; `lintRoadbookRelease` reports 0 errors. |

