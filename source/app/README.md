# source/app/ — the Gradle module

Single Android module. Everything the shipped APK contains lives here.

## build.gradle facts (the contract-heavy file of this repo)

- `applicationId "store.thabthaba.clock"` but `namespace 'systems.sieber.fsclock'` —
  **intentional**: published id is rebranded, Java package kept from upstream. Do not
  unify them; ProGuard rules, manifests, and layout custom-view tags all use the
  `systems.sieber.fsclock` package.
- `minSdk 21`, `target/compileSdk 36`, `multiDexEnabled true`.
- `versionCode 118` / `versionName "1.4"` (as of 2026-07). The comment block above them
  documents the scheme; CI greps this file for the *first non-comment*
  `versionCode`/`versionName` — keep the real values on their own uncommented lines.
- **Flavors** (dimension `appstore`): `standalone` (ships to customers; no IAP, all
  settings unlocked), `google` (Play Billing 8.0.0), `amazon` (Amazon Appstore SDK).
  Flavor-specific sources are in `src/<flavor>/` (see [src/README.md](src/README.md)).
- Only `release` build type: `minifyEnabled` + `shrinkResources` + ProGuard.
- Signing: `storeFile ../thabthaba.jks`; credentials from `local.properties` → env-var fallback.
- Output config renames every release APK to `TS Wallpapers.apk` and a `doLast` hook
  copies it to the **repo root**. If you rename the app or output, update
  `.github/workflows/release.yml` (hardcodes the output path) in the same change.
- Dependencies: appcompat, material, constraintlayout, gson 2.8.6 (reflection — ProGuard
  keeps models), Glide 4.16.0 (images/GIFs), NanoHTTPD 2.3.1 (LAN upload server),
  ZXing core 3.5.3 (QR generation), plus per-flavor billing SDKs.

## proguard-rules.pro

Aggressive (5 passes, repackage to root, overload-aggressively). Before adding code that
uses reflection, Gson, or is referenced from XML/manifest, add a keep rule or release
builds will crash while debug builds work. Already kept: the four custom views
(`FsClockView`, `DateView`, `DigitalClockView`, `WallpaperView`), Gson models (`Event`,
`WallpaperItem`), manifest components, NanoHTTPD/ZXing/Glide/billing, and specific
members of `IntegrityGuard`/`SecurePrefs`/`WallpaperRepo` (reflection targets).
**`Log.v/d/i/w` calls are stripped from release builds** (`-assumenosideeffects`) —
never put side effects in log arguments and never rely on those logs in production;
`Log.e` survives.

## Directory map

| Path | What |
|---|---|
| `src/main/` | shared code + resources (98% of the app) |
| `src/{standalone,google,amazon}/` | flavor overlays — `FeatureCheck` + `SettingsActivity` variants |
| `src/main/java/systems/sieber/fsclock/` | all Java — see its README for the class map |
| `src/main/res/` | resources — see its README for pairing/locale invariants |
| `src/main/AndroidManifest.xml` | all components declared here (even the Amazon IAP receiver, `tools:ignore="MissingClass"`) |
