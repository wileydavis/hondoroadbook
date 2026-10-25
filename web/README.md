# hondoroadbook.com

One Cloudflare Worker (`wrangler.jsonc` at the repo root) does two jobs:

* serves the static site in `web/site/` (Workers static assets);
* serves `/packs/*` from the R2 bucket `roadbook-packs`, with HTTP range support so the app can
  resume a download, and `/packs/catalog.json` with a five-minute cache.

The bucket is filled by `.github/workflows/packs.yml`, which builds packs on GitHub's runners.

## One-time setup

1. **R2 bucket.** Cloudflare dashboard → R2 → Create bucket → name `roadbook-packs` (the name in `wrangler.jsonc`).
2. **R2 API token** for the workflow. R2 → Manage R2 API Tokens → Create → *Object Read & Write*,
   scoped to `roadbook-packs`. Put its values in the GitHub repo (Settings → Secrets and variables →
   Actions): `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, and `R2_ACCOUNT_ID` (shown on the R2 overview page).
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

`kind` is `roads` today; `terrain` and `imagery` packs will use the same entry with a different
kind, and the app installs them side by side (the pack `name` must differ: "Montana terrain").
Hand-built packs for other regions can be added by uploading the `.mappack` and a matching `.json`
sidecar to the bucket and re-running the *packs* workflow's catalog step (or any run).
