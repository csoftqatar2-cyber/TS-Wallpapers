# thabthaba-programs-admin (Cloudflare Worker)

The fleet control panel reachable from a phone: serves `control-panel.html` / `gen.html` behind
Cloudflare Access + the Supabase admin session, and proxies the admin RPCs of the store, TS Link,
Leo and the controller with the secrets injected server-side. Deployed with `deploy.cmd`
(`npx wrangler deploy` from this folder). Secrets live in Wrangler only — see the header of
`worker.js` and the comments in `wrangler.toml`.

## Store-catalog mirror — `POST /catalog/publish`

Owner's order 2026-09-09: when **ذبذبة خلفيات** (`store.thabthaba.clock`) or **TS Link**
(`com.thabthaba.tslink`) publishes a new build on its own channel, the THABTHABA STORE catalog
must show it as an available update without anyone running `sync-store.mjs`.

The store reads `catalog/apps.json` from the R2 bucket `thabthaba` (public host
`pub-3d6cc5a5671c4be3829a384a375f7b11.r2.dev`) and installs from the stable key
`apks/<packageName>.apk`; "update available" is simply `row.versionCode > installed`. This route
does the two writes that make a release visible there:

1. fetches the APK from the app's own channel, checks it (200, `content-length`, ZIP magic,
   size cap), computes SHA-256;
2. reads the catalog, finds the row for the package, refuses if the row is missing
   (`404 no_row` — **it never creates rows**; which apps are on the store is the owner's call)
   or the versionCode is not strictly newer (`409 not_newer`);
3. rewrites only `versionCode`, `versionName`, `sizeBytes` inside that row by text surgery
   (`catalog-row.mjs`) and asserts every other byte of the file is unchanged — otherwise `500`
   and nothing is written;
4. puts the APK at `apks/<packageName>.apk` (content type
   `application/vnd.android.package-archive`), then puts the catalog back.

Request: `Authorization: Bearer <CATALOG_PUBLISH_SECRET>` (constant-time compare, `401` otherwise;
this route sits **before** the Access / admin-session gate because the GitHub runner has neither),
body `{"packageName","versionName","versionCode","apkUrl"}`. `packageName` must be one of
`CATALOG_PUBLISH_PACKAGES`; `apkUrl` must be https on one of `CATALOG_PUBLISH_APK_HOSTS`
(the wallpapers channel `pub-3108628f…r2.dev` and the store bucket itself — add TS Link's channel
host there when its CI starts calling). Any method other than POST answers `405`.
Response `200 {ok, packageName, versionCode, versionName, sizeBytes, sha256, previousVersionCode}`.

### Setting it up once

```
cd cloudflare/programs-admin
npx wrangler secret put CATALOG_PUBLISH_SECRET        # paste a long random string
npx wrangler deploy                                    # picks up the CATALOG_R2 bucket binding
gh secret set CATALOG_PUBLISH_SECRET -R csoftqatar2-cyber/TS-Wallpapers   # same string, for release.yml
```

The caller today is `.github/workflows/release.yml` (step "Mirror release into the THABTHABA STORE
catalog"), which runs after the app's own publish already succeeded; a failure there fails the job
so it is noticed, but the in-app update is unaffected. Repeat the call by hand with the same body
if it ever has to be retried.

### Dry run

`node tools/catalog-publish-dryrun.mjs [packageName versionCode versionName sizeBytes]` downloads
the live catalog, applies the row surgery in memory and prints the diff — nothing is uploaded.
