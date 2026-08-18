---
name: ts-car-mode-change
description: >
  Add or change a TS Wallpapers operating mode (the "which car is this?" product switch —
  Normal/Others, FSE, Leopard, GWM, Lynk & Co, Jetour). A mode is not a setting: it decides
  whether the app draws the screen, hands the wallpaper to the head unit, or mirrors a cloud
  channel into a folder, and it is what the backend targets wallpapers by. Encodes the full
  touchpoint list (11 places), the wire-value contract, and the order that keeps fielded cars
  working. Use whenever the task is "عربية جديدة", "وضع جديد", "add a car", a new head-unit
  brand, or any edit to OperatingMode / mode targeting / folder mirrors.
version: '1.0'
---

# Adding or changing a car mode

A mode is a **product**, not a preference. [`OperatingMode.java`](../../../source/app/src/main/java/systems/sieber/fsclock/OperatingMode.java)
is the single source of truth; 14 files read it.

| Mode | Wire value | What it is |
|---|---|---|
| NORMAL | `normal` | The app owns the screen: slideshow + clock. Labelled **"Others"** in the UI. |
| FSE | `fse` | Normal, window pinned to 1920×720 for ultra-wide units. Still on its **original** boolean key `PREF_FSE_SCREEN`. |
| LEOPARD | `leopard` | Hand-off: the app draws nothing, hands the file to Android's `WallpaperManager`. |
| GWM | `gwm` | Normal on screen **plus** a folder mirror (`gwm_split` → `/sdcard/Pictures/GWMSplit_Styles`). |
| LYNKCO | `lynkco` | Hand-off to the Flyme theme app (`com.flyme.auto.customize`) — Android's WallpaperManager is ignored on those units. |
| JETOUR | `jetour` | Normal on screen **plus** a folder mirror (`jetour_g700` → `/sdcard/Pictures/G700`). |

**A new mode is almost always one of three shapes.** Decide which before writing code — it
tells you exactly which existing mode to copy:

- **Others-like + folder mirror** (a head unit with its own gallery app) → copy **Jetour**.
  This is the cheapest new car: one `FolderMirror` constant + one RPC.
- **Hand-off** (the head unit owns the wallpaper) → copy **Lynkco** if a vendor app must be
  handed the file, **Leopard** if Android's `WallpaperManager` works.
- **Screen-geometry only** → copy **FSE**. Rare.

## The 11 touchpoints, in order

Work top to bottom; the list is the checklist.

### App

1. **[`OperatingMode.java`](../../../source/app/src/main/java/systems/sieber/fsclock/OperatingMode.java)** —
   new constant, new `PREF_<NAME>` boolean, a branch in `get()`, a line in `set()`
   (`set()` writes **all** flags together so two modes can never both be on — add yours or
   the new mode never turns off), an `isX()` helper, and a case in `wire()`.
2. **Family predicates** in the same file — `isOthersLike()` (draws our screen) and
   `isHandoff()` (picker only). **Forgetting this is the classic bug:** the mode compiles,
   reports itself correctly, and silently loses the slideshow, the download gate or the
   picker, because everything asks the family, not the mode.
3. **Support gate** — if the mode only works on some units, add an `isXSupported(Context)`
   next to `isSupported()` (Leopard: `FEATURE_LIVE_WALLPAPER`) and `isLynkcoSupported()`
   (queries the vendor package — the package must be declared in `<queries>` in the
   manifest, or `queryIntentActivities` returns empty on API 30+ and the mode looks
   unsupported on every car).
4. **Settings picker** — [`activity_settings.xml`](../../../source/app/src/main/res/layout/activity_settings.xml)
   (`radioGroupMode`, one `radioMode<Name>`) plus, in
   [`BaseSettingsActivity.java`](../../../source/app/src/main/java/systems/sieber/fsclock/BaseSettingsActivity.java):
   the `modeViews` array (~:1007, controls visibility), the checked-id → mode map (~:2091),
   `radioFor()` (~:2107), and the mode list in the dialog variant (~:2157).
   `activity_settings.xml` has **no** landscape variant — one file only.
5. **Activation overlay picker** — `radioActivation<Name>` in **both**
   [`layout/view_fsclock.xml`](../../../source/app/src/main/res/layout/view_fsclock.xml) **and**
   [`layout-land/view_fsclock.xml`](../../../source/app/src/main/res/layout-land/view_fsclock.xml),
   plus `initActivationModePicker()` / `updateActivationModeDesc()` in `FsClockView.java`.
   See `ts-android-ui-change` — this is the paired layout, the highest-risk file in the tree.
6. **Confirm gate** — [`activity_mode_confirm.xml`](../../../source/app/src/main/res/layout/activity_mode_confirm.xml)
   (`radioConfirm<Name>`) and the three maps in
   [`ModeConfirmActivity.java`](../../../source/app/src/main/java/systems/sieber/fsclock/ModeConfirmActivity.java):
   `check()`, `updateDesc()`, `apply()`.
7. **Strings** — `mode_<name>`, `mode_<name>_desc`, `chip_mode_<name>` in
   [`values/strings.xml`](../../../source/app/src/main/res/values/strings.xml) (the default
   file carries the shipped text; `values-ar` overrides only a handful — follow the existing
   pattern, don't add a locale file for one string).
8. **Behaviour** — the mode's actual job:
   - folder-mirror shape → one constant in [`FolderMirror.java`](../../../source/app/src/main/java/systems/sieber/fsclock/FolderMirror.java)
     (prefs keys, folder name, RPC name, fallback prefix) and a branch in its per-mode
     selector — exactly one mirror is active at a time, because the modes are exclusive;
   - hand-off shape → an applier class next to
     [`LynkcoApplier.java`](../../../source/app/src/main/java/systems/sieber/fsclock/LynkcoApplier.java) /
     [`LeopardApplier.java`](../../../source/app/src/main/java/systems/sieber/fsclock/LeopardApplier.java),
     and the picker route in `FsClockView` / `FullscreenActivity`.

### Backend (before the app ships — see order below)

9. **Accept the wire value.** `report_device_mode` writes `devices.mode`; if a CHECK
   constraint or an enum lists the allowed modes, the new value must be accepted **first**,
   or every car that updates reports a mode the server rejects. Follow `ts-backend-rpc-change`.
10. **Targeting + channel.** `wallpapers.target_mode` is compared against `devices.mode` in
    `get_wallpapers`, and it carries a **CHECK constraint listing the six current modes** —
    migrate that constraint or no wallpaper can ever be targeted at the new mode (the insert
    fails in the dashboard with a constraint error). `wallpapers.channel` is deliberately
    unconstrained text, so a new channel needs no constraint change — only an RPC.
    A folder-mirror mode also needs its own channel value and its own
    `get_<name>_wallpapers` RPC with the same shape and the same activation gate as
    `get_gwm_wallpapers` / `get_jetour_wallpapers`. Model the migration on
    [`20260808_jetour_mode_and_channel.sql`](../../../supabase/migrations/20260808_jetour_mode_and_channel.sql).

### Dashboard

11. **[`wallpapers_manager.html`](../../../wallpapers_manager.html)** — the mode `<option>` in
    the upload target selector (~:1557), the channel map (~:1960), the label map (~:1991),
    the device-list icon/label map (~:3705), and a filter button if the mode has its own
    channel (~:1695). A mode the dashboard doesn't know shows as a blank/unknown badge on
    every car reporting it, and its wallpapers cannot be targeted at all.

## Order of operations

1. **Backend first** (accept the value, add the channel/RPC, verify with `execute_sql`).
2. **Dashboard second** — it only reads; deploying it early is harmless.
3. **App last**, as a normal release (`ts-wallpapers-release`).

Backwards is what breaks: an APK that reports `newcar` to a server that rejects it loses its
mode check-in, and the manager then shows those cars as unanswered.

## Traps

- **`wire()` values are a frozen contract.** They are stored in `devices.mode`, targeted by
  `wallpapers.target_mode`, and rendered by the dashboard. Renaming one orphans every car
  already reporting it. Add values; never rename.
- **`get()` is ordered.** It returns the first flag that is set. A car whose prefs somehow
  carry two flags silently resolves to whichever is checked first — which is why `set()`
  must always write the whole set.
- **FSE keeps its old key on purpose** (`PREF_FSE_SCREEN`). Don't "tidy" it into a
  `fse-mode` key: every car in the field stores it under the old name.
- **Fielded cars can't pick a mode their APK doesn't know.** That is why
  `ModeConfirmActivity` carries an update button — a new mode only becomes selectable after
  the car takes the update. Expect the customer's car to sit on the confirm gate until it
  updates.
- **`migrateOperatingMode`** ([`FsClockApp.java:82`](../../../source/app/src/main/java/systems/sieber/fsclock/FsClockApp.java))
  pins existing installs to NORMAL/FSE once and sets `operating-mode-migrated`. It is a
  guess, not a decision — which is exactly why the confirm gate exists. Never mark a
  migrated mode as confirmed.
- **A car with `mode = null`** (old APK that never reported) is invisible to every
  mode-targeted wallpaper row. If a new car sees an empty playlist, check `devices.mode`
  before touching the library.
- **The mode is exclusive by construction.** Don't build a "mode + mode" combination; if a
  car needs Others *plus* a mirror, that is the GWM/Jetour shape — copy it.

## Verify before shipping

```sql
-- the car reports the new mode
select hardware_id, mode, app_version_code, last_seen_at
  from public.devices where hardware_id = public.resolve_device_id('<HW_ID>');

-- and the targeted rows actually reach it
select * from public.get_wallpapers('<HW_ID>', null);
```

On the car itself: the chip in the header shows the mode, and the Settings radio must
survive a restart of the app (that proves `set()` wrote every flag).

## Related

- `ts-android-ui-change` — the paired `view_fsclock.xml` edit in touchpoint 5.
- `ts-backend-rpc-change` — touchpoints 9–10.
- `ts-wallpapers-release` — shipping the app half.
- `ts-local-build-and-test-car` — trying the mode on a real head unit first.
