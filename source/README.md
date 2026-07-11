# source/ — the Android app (active working copy)

This is the **only** live Android source tree in the repo. (`/src/` at the repo root is a
stale, git-ignored upstream clone — never edit it.) The app is a GPLv3 fork of
[FsClock-Android](https://github.com/schorschii/FsClock-Android); the verbatim upstream
README is preserved as [README.upstream.md](README.upstream.md) — it describes the
*original* clock app, not this fork. Fork-wide context lives in the repo-root
[AGENTS.md](../AGENTS.md).

## Layout

| Path | What |
|---|---|
| `app/` | the single Gradle module — all code and resources (see [app/README.md](app/README.md)) |
| `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/` | Gradle 8.6 / AGP 8.4.2 wrapper setup, single module `:app` |
| `fastlane/` | store-listing metadata, 8 locales — currently stale upstream text (see its README) |
| `thabthaba.jks` | release signing keystore — **git-ignored, exists only locally + as a GitHub secret**. Losing it permanently breaks updates for installed devices |
| `local.properties` | git-ignored: `sdk.dir` + `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |
| `LICENSE.txt` | GPLv3 — binding on the whole app |
| `.github/` | upstream leftovers (funding, store badges) — not this repo's CI; the real CI is repo-root `.github/workflows/release.yml` |

## Building

```bash
cd source
./gradlew :app:assembleStandaloneRelease   # the flavor that ships to customers
```

- Signing credentials come from `local.properties`, falling back to **environment
  variables** of the same names (that fallback is how CI signs).
- Only a `release` build type is configured (minified + shrunk). There is no debug
  signing config; local debugging typically uses `assembleStandaloneDebug` with the
  default debug key, but note SecurePrefs/activation state is signing-cert-bound.
- Known constraint: the build may fail if the checkout path contains spaces (this
  OneDrive path does). CI is the reliable build path.
- Output: `app/build/outputs/apk/<flavor>/release/TS Wallpapers.apk`, and a gradle
  `doLast` task **copies the release APK to the repo root** as `TS Wallpapers.apk` —
  that root file is a build artifact, don't hand-edit or manually replace it.

## Releasing

Bump `versionCode` **and** `versionName` in `app/build.gradle` (lines ~19-20), commit
with a user-presentable subject line (it becomes the in-app changelog), push to `main`.
CI gates on versionCode being higher than the last published row in Supabase
`app_versions`. versionCode must never decrease and must stay above 113.
