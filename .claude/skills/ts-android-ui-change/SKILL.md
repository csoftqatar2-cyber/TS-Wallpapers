---
name: ts-android-ui-change
description: >
  Change a screen in the TS Wallpapers Android app without breaking rotation, release builds,
  or fielded settings. Encodes the paired portrait/landscape layout rule (the single highest-risk
  edit in the tree), the "CLOCK" preferences contract, the ProGuard keep rules a new custom view
  or model needs, and where the app's Arabic text actually lives. Use whenever the task edits a
  layout, adds a button/field/section to a screen, touches strings, or adds a custom view —
  "غيّر الشاشة", "زرار جديد", "شكل الشاشة", "add a setting".
version: '1.0'
---

# Editing a screen

Read [`source/app/src/main/res/README.md`](../../../source/app/src/main/res/README.md) for the
resource conventions in full. This skill is the procedure and the traps.

## Rule 1 — `view_fsclock.xml` is paired, and it is the risky one

The main clock/wallpaper screen exists twice:

- [`layout/view_fsclock.xml`](../../../source/app/src/main/res/layout/view_fsclock.xml) (portrait)
- [`layout-land/view_fsclock.xml`](../../../source/app/src/main/res/layout-land/view_fsclock.xml) (landscape)

`FsClockView.commonInit()` inflates whichever variant the current orientation selected and
`findViewById`s the same ids from it. **It is the only land-duplicated layout** — the other
layouts are portrait-only, so nothing else in the tree has this problem.

When you add a view to one file:

1. Add it to the other file too, with the **same id**, adapted to that orientation's nesting.
2. If it genuinely belongs to one orientation only, **null-guard every access to it in Java** —
   that is the existing pattern for the download-progress group (`layoutDownloadProgress`,
   `progressBarDownload`, `textViewDownloadCount`, `textViewDownloadPercent`,
   `buttonDownloadSkip`), which exists in portrait only and is guarded at
   [`FsClockView.java:673`](../../../source/app/src/main/java/systems/sieber/fsclock/FsClockView.java).
   Note the res README says the id sets are identical; those five are the standing exception,
   so **diff the id sets rather than trusting either document**:

```bash
cd "source/app/src/main/res" && diff \
  <(grep -o 'android:id="@+id/[A-Za-z0-9_]*' layout/view_fsclock.xml      | sed 's/.*\///' | sort) \
  <(grep -o 'android:id="@+id/[A-Za-z0-9_]*' layout-land/view_fsclock.xml | sed 's/.*\///' | sort)
```

Every id that comes out of that diff must be either added to the other file or null-guarded in
Java. An unguarded id present in one file only = **NullPointerException the first time the car
rotates** — and the activation overlay, the mode picker and the support/QR block all live in
this layout, so the failure lands on the screen a customer sees first.

3. Text lives inline. The activation overlay carries **literal Arabic strings duplicated in
   both files** (not `@string/` references). A wording change is two edits.

## Rule 2 — where the text lives

Default [`values/strings.xml`](../../../source/app/src/main/res/values/strings.xml) holds most of
the fork's shipped UI text; `values-{ar,de,es,…}` override only a handful of keys. Add new
strings to the **default** file; add a locale override only where a real translation exists.
Missing keys fall back to the default — that is the intended pattern here, not a bug to fix.

## Rule 3 — preferences are a cross-component contract

Every screen reads the same SharedPreferences domain, `"CLOCK"`
([`BaseSettingsActivity.java:87`](../../../source/app/src/main/java/systems/sieber/fsclock/BaseSettingsActivity.java)).
The key strings are shared by `FsClockView`, the widgets, `WallpaperRepo`, `UploadServer` and
`FsClockApp.migrateSettings()`. Renaming a key silently resets that setting on every car in the
field; if a rename is unavoidable, add a migration in `migrateSettings()` the way the color
keys were migrated.

Anything sensitive (activation state) goes through `SecurePrefs`, whose HMAC is bound to the
signing certificate — don't move such a value to a plain pref to make a screen simpler.

## Rule 4 — release builds strip and shrink

`minifyEnabled` + `shrinkResources` are on for release. Three consequences for UI work:

- **A new custom view referenced from XML needs a keep rule** in
  [`proguard-rules.pro`](../../../source/app/proguard-rules.pro). The file keeps `FsClockView`,
  `DateView`, `DigitalClockView`, `WallpaperView` explicitly. (`FitPreviewView` is in a layout
  but not on that list — if you touch it, verify inflation in a **release** build rather than
  assuming.) Same for any new Gson model, next to `Event` and `WallpaperItem`.
- **`Log.v/d/i/w` are removed** in release. Never gate UI logic on a log call; use
  `CrashReporter.breadcrumb` if you need the trace to survive.
- **`-keepnames class systems.sieber.fsclock.**`** is what keeps fleet crash reports readable.
  Don't remove it while "cleaning up" ProGuard.

## Rule 5 — API/config-qualified pairs

Clock faces (`drawable/` PNG + `drawable-v24/` vector), `color/` + `color-night/`,
`values/colors.xml` + `values-night/`, `values/styles.xml` + `values-v31|-w600dp|-w960dp`.
Edit both halves or the change appears on some cars only. Fonts are referenced **by index**
from `FontOptions.java` — never reorder them.

## Checklist before you call a UI change done

- [ ] `diff` of the two `view_fsclock.xml` id sets shows only intentional, null-guarded entries.
- [ ] Any literal text change applied in **both** layout variants.
- [ ] New strings in default `values/strings.xml`.
- [ ] New custom view / Gson model has a keep rule.
- [ ] Built as a **release** APK (`./gradlew assembleStandaloneRelease`) — a debug build does
      not exercise ProGuard, so a missing keep rule only appears in release.
- [ ] Tried on a real head unit, in the orientation that car actually uses, and rotated if the
      unit rotates (`ts-local-build-and-test-car`).

## Related

- `ts-local-build-and-test-car` — build and install the result on a car.
- `ts-car-mode-change` — mode radios live in the paired layout and in the settings screen.
- `ts-wallpapers-release` — shipping it.
