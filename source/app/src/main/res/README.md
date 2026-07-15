# res/ — resource conventions and sync rules

App identity: `app_name` = "TS Wallpapers" (`values/strings.xml`), Arabic override
"ذبذبة خلفيات" (`values-ar/strings.xml`).

## Rule 1 — the paired layout (highest-risk edit in this tree)

`layout/view_fsclock.xml` (portrait) and `layout-land/view_fsclock.xml` (landscape) are
**parallel variants of the same screen and must keep identical view-ID sets**. Java
(`FsClockView.commonInit()`) findViewById's these ids from whichever variant inflated;
an id present in one file only → NullPointerException on rotation. It is the ONLY
land-duplicated layout (the other 11 layouts are portrait-only).

Shared ID set includes: `fsclockRootView`, `wallpaperView`, `imageViewBackground`,
`textViewEvents`, `analogClockContainer` + face/hour/minute/second ImageViews,
`digitalClock`, `textViewDate`, `textViewHijriDate`, the bottom bar
(`linearLayoutWeather/Notifications/Alarm/Battery` + children), and the activation
overlay (`layoutActivation`, `editTextActivationSerial`, `checkBoxFse`,
`buttonActivate`, `textViewActivationStatus`, `textViewActivationDeviceId`).

What legitimately differs: orientation-specific nesting (portrait stacks analog above
digital; landscape puts them side-by-side), weights, autoSize minimums, margins/padding.
Text/attribute changes (e.g. the activation PIN field) must be applied to **both** files.
Note: the activation overlay contains literal Arabic strings duplicated in both files
(not `@string/` refs) — a wording change means editing 2 places.

## Rule 2 — locale string files

Default `values/strings.xml` has ~162 strings, **including most of the fork's Arabic UI
text hardcoded as defaults**. Translations exist in `values-{ar,de,es,fr,it,iw,nl,ru}/`
but are sparse (values-ar overrides only 13 strings). When adding a string: add to
default `values/`; add locale overrides only where a translation actually exists (missing
keys fall back to default — that is the existing pattern, keep it).

## Rule 3 — API/config-qualified variants (edit in pairs)

| Base | Variant | Meaning |
|---|---|---|
| `drawable/analog_*.png` | `drawable-v24/analog_*.xml` | clock faces/hands: PNG fallback below API 24, vector at 24+. A face/hand change needs BOTH |
| `color/color_button_*.xml` | `color-night/` | day/night button colors — keep in sync |
| `values/colors.xml` | `values-night/colors.xml` | dark theme |
| `values/styles.xml` | `values-v31/`, `values-w600dp/`, `values-w960dp/` | Android 12 / tablet / TV styles |
| `drawable/ripple_bg.xml` | `drawable-v21/ripple_bg.xml` | ripple variant |

## Other contents

- `font/` — cairo_{regular,light,bold} (Arabic UI), dseg7classic/dseg14classic/dsegweather
  (7-segment clock faces; **SIL OFL license, text in `raw/dseg_license.txt` — must ship**),
  roboto_thin. Referenced by index in `FontOptions.java` — don't reorder/rename without updating it.
- `layout/` — settings, dialogs (color/event/inputbox/review), widget layouts, dream host.
- `mipmap-*` — launcher icons (adaptive in `mipmap-anydpi-v26`).
- `xml/` — `dream.xml` (screensaver meta), `file_paths.xml` (FileProvider paths for the
  update APK — must cover `externalFilesDir/apk/`), widget descriptors.
- Custom views referenced by class name in layouts (`systems.sieber.fsclock.FsClockView`,
  `WallpaperView`, `DigitalClockView`, `DateView`) — renaming/moving those classes breaks
  layout inflation and their ProGuard keep rules.
- `shrinkResources` is on: resources referenced only from code built by reflection may be
  stripped from release builds.
