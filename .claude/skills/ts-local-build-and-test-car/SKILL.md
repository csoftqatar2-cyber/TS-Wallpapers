---
name: ts-local-build-and-test-car
description: >
  Build the TS Wallpapers APK on this machine and put it on a real head unit to try a change
  before shipping it to the fleet. Covers the gradle commands and flavors, where the APK lands,
  the signing setup that must exist locally, ADB over Wi-Fi to the test cars, launching on the
  right display, and the debug-build trap that wipes a car's activation. Use whenever the task
  is "جرب على العربية", "install on the test car", "build the apk locally", "ابني نسخة", or
  verifying a fix on hardware rather than in CI.
version: '1.0'
---

# Local build → test car

This is the **try it** loop. It never publishes anything: the fleet only gets a build when a
`versionCode` bump is pushed to `main` (see `ts-wallpapers-release`). Build locally as often
as you like — just don't bump the version to do it.

## 1. Build

```bash
cd "C:/Users/abdor/OneDrive/Documents/Github/Apps/APks/TS WALLPAPERS/source" && ./gradlew assembleStandaloneRelease
```

- **Always the `standalone` flavor.** `google` and `amazon` exist for stores that were never
  used; a build from either is not what cars run.
- **Debug variant:** `./gradlew assembleStandaloneDebug` — faster, no ProGuard, readable
  stack traces. Read the trap in §4 before installing one on a customer's car.
- The path contains spaces and the build works anyway (verified 2026-08-18) — AGENTS.md §7.8
  still warns against it; treat that warning as stale, but quote the path.

### What must exist locally (git-ignored)

| File | Contains |
|---|---|
| `source/local.properties` | `sdk.dir`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |
| `source/thabthaba.jks` | the release signing keystore |

Gradle falls back to environment variables of the same names — that is how CI signs. A
release build with either missing fails at the signing step, not at compile.

### Where the APK lands

- `source/app/build/outputs/apk/standalone/release/TS Wallpapers.apk`
- **and** a copy at the repo root — a `doLast` task copies **release builds only** there.
  That root copy is tracked in git; it is the "attached build" in the repo, so committing it
  is a deliberate act, not something to do on every experiment.
- Debug builds land under `.../apk/standalone/debug/` and are **not** copied to the root.

## 2. Reach the car

```bash
adb connect 192.168.0.237:5555   # then: adb devices
```

Known test cars:

| Car | Address | Mode | Screens |
|---|---|---|---|
| L946 Leopard (Geely/Flyme) | `192.168.0.237` | Leopard | passenger `1280x640` (display 1), driver `5120x1600` |
| BYD FSE | `192.168.0.57` | FSE | the app's screen is **display 2**, `1920x720` |

If ADB won't connect at all, the privileged-channel playbook is a separate skill:
`adb-headunit-automation` (per-model quirks, Device Owner, re-arming adbd).

## 3. Install and launch

```bash
adb install -r "C:/Users/abdor/OneDrive/Documents/Github/Apps/APks/TS WALLPAPERS/TS Wallpapers.apk"
adb shell am start -n store.thabthaba.clock/systems.sieber.fsclock.FullscreenActivity
```

- The application id is `store.thabthaba.clock`; the Java package is `systems.sieber.fsclock`.
  Both are correct — do not "fix" the mismatch.
- **Multi-screen cars:** add `--display <id>` to `am start`, and list displays with
  `adb shell dumpsys display | grep -i "mDisplayId\|uniqueId"`. On the Leopard car the
  passenger screen is the one to watch.
- **Car .57 cannot be screenshotted** — its display 2 is FLAG_SECURE, so `screencap` returns
  black. Verify by reading state over logcat instead, or by looking at the physical screen.

Confirm what is actually installed:

```bash
adb shell dumpsys package store.thabthaba.clock | grep -E "versionCode|versionName"
```

## 4. The debug-build trap (read before installing a debug APK)

A debug build is signed with the **debug keystore**, not `thabthaba.jks`. Two consequences,
both invisible until they bite:

1. `adb install -r` over a release install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
   the only way through is `adb uninstall store.thabthaba.clock`, which **erases the car's
   prefs, including its activation**.
2. Even after a clean install, `SecurePrefs` derives its HMAC key from the APK signing
   certificate ([`SecurePrefs.java:91`](../../../source/app/src/main/java/systems/sieber/fsclock/SecurePrefs.java)),
   so anything stored by the release build is unreadable to the debug build and vice-versa.
   The car falls back to "not activated".

Recovering the car means re-activating it — a `7078…` serial (re-activation) or a
dashboard re-activate; see `ts-device-triage`. On your own bench car that is a minor
annoyance; on a customer's car it is a support call. **Prefer a signed release build for
anything installed on a car that is in service.**

## 5. Reading what the car did

```bash
adb logcat -d -s FSCLOCK WallpaperRepo migrate FolderMirror
```

Remember `Log.v/d/i/w` are **stripped from release builds** — a release APK only prints
`Log.e`. The mechanism that survives is `CrashReporter.breadcrumb`, and crash files are kept
on the car under the app's internal `files/crashes/` (see `ts-crash-triage`). If you need
verbose tracing on hardware, that is the one legitimate reason to install a debug build.

## 6. When the change is good

Ship it with `ts-wallpapers-release` — bump `versionCode`/`versionName`, write the Arabic
commit subject (it becomes the user-visible changelog), push to `main`, and let CI publish.
Local builds never reach the fleet; the repo-root APK copy is a convenience artifact, not
the distribution channel.

## Related

- `ts-wallpapers-release` — the real publish path.
- `adb-headunit-automation` — getting ADB working on a stubborn head unit.
- `ts-device-triage` — the car after a debug install says it is not activated.
- `ts-crash-triage` — reading crashes off a car.
