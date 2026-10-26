# hondoroadbook.com

One Cloudflare Worker (`wrangler.jsonc` at the repo root) does all of it:

* the static site in `web/site/`, including the planner (`plan.html`, `plan.js`, `plan.css`, `map/`);
* `/packs/*` from the R2 bucket `roadbook-packs` (or the GitHub release when the bucket has no copy),
  with HTTP range support so the app can resume a download; `/packs/catalog.json` with a short cache;
* `/tiles/<pack>/<file>` — a file inside a pack by byte range (`web/tiles.js`), which is how the
  planner's map reads `tiles.pmtiles`, `terrain.pmtiles` and `roads.pmtiles` straight out of the
  stored zips without unpacking anything;
* `/api/*` (`web/api.js`): `route`, `snap`, `search` go to the routing container; `park` and
  `parked/<code>` hold a roadbook for 24 h for the phone; `roadbooks` is the public library (D1 +
  R2); `web` is Brave web search for the inspect panel.

The bucket is filled by `.github/workflows/packs.yml`, which builds packs on GitHub's runners.

## The routing service (Cloudflare Containers)

`server/` is the app's core behind a tiny HTTP server (`fold.server.MainKt`); `server/Dockerfile`
builds it into an image that Cloudflare runs as a container bound to the Durable Object class
`RouterContainer`. Two instances at most (`west` for points west of 97°W, `east` otherwise). An
instance downloads the graphs it needs from the packs on first use (a byte range of the .mappack,
75 MB for Nebraska in a few seconds), keeps them on its disk, and sleeps twenty minutes after the
last request. The first route after a sleep takes a few seconds; after that, tens of milliseconds.

Deploying the container needs the image built. Two ways:

* **Locally**: Docker running, then `npm install && npx wrangler deploy` from the repo root. Wrangler
  builds `server/Dockerfile` with the repo root as context (`.dockerignore` keeps it to `core/`,
  `server/` and the Gradle wrapper) and pushes it.
* **Workers Builds** (the GitHub-connected deploy): Cloudflare builds the image itself as part of the
  deploy. Leave the build command empty; the deploy command is `npx wrangler deploy`.

To deploy the Worker without touching the container (site changes only): `npx wrangler deploy --containers-rollout=none`.

Run it anywhere else with `./gradlew :server:fatJar && PORT=8080 java -jar server/build/libs/fold-server.jar`
and point the Worker at it with a `ROUTER_URL` variable instead of the container.

## The library (D1 + R2)

Once: `npx wrangler d1 create roadbook-library`, paste the printed `database_id` into
`wrangler.jsonc`, then `npx wrangler d1 execute roadbook-library --remote --file web/schema.sql`.
GPX bodies go into the existing R2 bucket under `library/`; parked roadbooks under `parked/`
(expired ones are deleted when next asked for; an R2 lifecycle rule on `parked/` of two days keeps
the bucket tidy). Until the database exists the planner's PUBLISH and the app's BROWSE say the
library is not set up; everything else works.

Secrets (`npx wrangler secret put NAME`):

* `ADMIN_TOKEN` — to moderate: `curl -X POST -H "Authorization: Bearer $TOKEN" https://hondoroadbook.com/api/roadbooks/<id>/hide` (`unhide`, `delete` likewise).
* `BRAVE_KEY` — a Brave Search API key ($5 per 1,000 queries, $5 of credit free each month) for the
  inspect panel's web search. Without it the panel shows a link to a web search instead. Nothing is
  searched until a rider presses a section's button; each query is cached a week site-wide; and a
  site-wide daily budget (`WEB_DAILY` var, default 2,000 cache misses ≈ $10) caps the bill — past it,
  sections offer the same search as a link into the rider's own browser. The counter is one row per day
  with nothing about who asked.
* `SALT` — any string; it salts the daily per-visitor upload counter so no address is stored.

Uploads are capped at 6 MB and 40 a day per visitor; published files are rewritten to the line and
the cues only (no timestamps, no extensions, no device names).

## One-time setup

1. **R2 bucket.** Cloudflare dashboard → R2 → Create bucket → name `roadbook-packs` (the name in `wrangler.jsonc`).
2. **R2 API token** for the workflow. R2 → Manage R2 API Tokens → Create → *Object Read & Write*,
   scoped to `roadbook-packs`. Put its values in the GitHub repo (Settings → Secrets and variables →
   Actions): `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, and `R2_ACCOUNT_ID` (shown on the R2 overview page).
   **This matters for speed**: without the mirror every byte range the planner's map and the routing
   container ask for goes through GitHub's release storage (a second or two each); from R2 it is tens of
   milliseconds. Once the secrets exist, run Actions → *mirror* once to copy what is already built; every
   pack run mirrors its own output from then on. The site serves from R2 first and the release second.
3. **Worker.** The Worker you connected to the repo deploys with `npx wrangler deploy` from the repo
   root; leave the build command empty. Under the Worker's Settings → Domains & Routes make sure
   `hondoroadbook.com` and `www.hondoroadbook.com` are custom domains (the config asks for them, so
   the first deploy usually adds them).
4. **Build packs.** Actions → *packs* → Run workflow → `montana wyoming idaho` (or `all`).
   Each state runs as its own job; `all` is about 50 jobs, six at a time, two to three hours.
   The last job rewrites `catalog.json`. The site table and the app's MAPS → DOWNLOAD read it.
5. **The download button.** `/download/roadbook.apk` serves the R2 object `app/roadbook.apk` when
   the release workflow has uploaded one, and until then the APK committed at `dist/roadbook.apk`
   (proxied from GitHub and cached at the edge). So a build is live as soon as it is committed.
6. **Release an APK.** Add `ROADBOOK_KEYSTORE_B64` (`base64 -w0 keys/roadbook-release.jks`) and
   `ROADBOOK_KEYSTORE_PASSWORD` as secrets, then `git tag v0.1 && git push --tags`. The *release*
   workflow builds, signs and attaches `roadbook.apk`; the site's download button points at the
   latest release.

## Catalog format

```json
{ "version": 1, "updated": "2026-10-25",
  "packs": [ { "id": "us-montana-roads", "name": "Montana", "group": "United States", "kind": "roads",
               "url": "https://hondoroadbook.com/packs/us/montana-roads.mappack",
               "bytes": 471234567, "built": "2026-10-25", "bbox": [-116.05, 44.36, -104.04, 49.0] } ] }
```

`kind` is `roads` or `terrain` (hillshade); `imagery` would use the same entry. Packs of different
kinds for one region share `region` and the app lists them under it; the pack `name` must differ
("Montana", "Montana terrain"). Regions come from `packer/regions.json`.
Hand-built packs for other regions can be added by uploading the `.mappack` and a matching `.json`
sidecar to the bucket and re-running the *packs* workflow's catalog step (or any run).
