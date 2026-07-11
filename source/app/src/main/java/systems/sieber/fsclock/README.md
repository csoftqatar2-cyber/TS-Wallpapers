# systems.sieber.fsclock — class map and invariants

All app Java lives in this one package (upstream package name kept; published
applicationId is `store.thabthaba.clock`). Read this before editing any class.

## Entry points (declared in src/main/AndroidManifest.xml)

| Component | Class | Notes |
|---|---|---|
| Application | `FsClockApp` | pref migration, night mode, `IntegrityGuard.init()` |
| Launcher activity (mobile + TV) | `FullscreenActivity` | hosts `FsClockView`; fling/DPAD = switch wallpaper; tap toggles clock overlay; update badge |
| Settings | `SettingsActivity` (flavor subclass of `BaseSettingsActivity`) | |
| Screensaver (DayDream) | `FullscreenDream` | re-inflates `FsClockView` on rotation (OS bug workaround) |
| Widgets | `FsClockWidgetDigitalProvider` / `FsClockWidgetAnalogProvider` (+ 2 config activities) | AlarmManager 60s self-refresh, re-armed on boot |
| Notification listener | `NotificationListener` | broadcasts count via action `systems.sieber.fsclock.notifi` |
| Power receiver | `PowerReceiver` | launches app on power connect |
| FileProvider | `${applicationId}.fileprovider` | serves the downloaded update APK to the installer |

## Subsystems

### Clock rendering
- `FsClockView` — **the god view** (~1300 lines, FrameLayout). Inflates `view_fsclock.xml`,
  owns every sub-view (analog hands, `DigitalClockView`, `DateView`, Hijri date, weather,
  battery/alarm/notification bar, events, activation overlay, `WallpaperView`). Runs the
  timers: analog tick, calendar/event checks, burn-in pixel shift, 60s wallpaper
  auto-switch, 5-min Supabase re-sync. Used by both `FullscreenActivity` and `FullscreenDream`.
- `DigitalClockView`, `DateView` — custom-drawn, auto-sizing; also rendered to bitmaps by the widgets.
- `FontOptions` — static font table (DSEG 7/14-segment, Cairo ×3, Roboto Thin) with per-font x-correction.
- `GraphicSelectionAdapter` — static arrays of analog face/hand drawables; id `-1` = user image.

### Wallpaper system
- `WallpaperRepo` — playlist + network layer. Merges (a) local folder
  `externalFilesDir/Wallpapers/` and (b) the cached Supabase playlist (pref
  `wallpaper-cache-json`). Device-id derivation `getHardwareId()` (prefix order:
  `VIN- → MAC- → SYS- → BOOT- → SRL- → AID-`) — **changing this format breaks
  server-side device matching for every fielded device**. Sync = POST RPC
  `get_wallpapers {device_hw_id, legacy_hw_id}`; response `"inactive"` de-activates.
  Activation = POST RPC `activate_device`; serial must start `7078` (also enforced at
  `FsClockView.java:224`). Videos pre-cached to `cacheDir/wallpapers/vid_{hex}.bin`.
- `WallpaperItem` — Gson model `{type,url}` (ProGuard-kept), type guessed from extension.
- `WallpaperView` — two ping-pong slots with crossfade; Glide for image/GIF,
  TextureView+MediaPlayer (muted, looping, center-crop) for video; `sampleLuminance()`
  drives auto-contrast clock colors.
- `StorageControl` — file paths for the static clock face/hands/background PNGs in
  `externalFilesDir/` (`clockface.png`, `hour.png`, `minute.png`, `second.png`, `bg.png`).
- `UploadServer` — NanoHTTPD on **port 8089**, Arabic HTML upload page; started/stopped
  around the pair-phone dialog in settings (must always be stopped — socket leak otherwise).
  Saves uploads into the same `Wallpapers/` folder the repo scans.
- `QrCode` — ZXing wrapper (pairing URL QR, device-id QR).

### Settings
- `BaseSettingsActivity` — everything configurable; `SHARED_PREF_DOMAIN = "CLOCK"`.
  Wallpaper source management, pairing, device-id display, update check, events editor.
- `BaseFeatureCheck` — unlock state (see `../../../README.md` for the flavor matrix).

### Update system
- `UpdateManager` — GET `app_versions?order=version_code.desc&limit=1`;
  update offered iff `version_code > BuildConfig.VERSION_CODE`. Download via
  DownloadManager to `externalFilesDir/apk/update.apk`, install via FileProvider +
  `ACTION_VIEW`. Callers: `FullscreenActivity` (silent badge) and settings (dialog).

### Security layer (know what it actually does)
- `IntegrityGuard` — root/debugger/Xposed heuristics + signature check. **The signature
  check is self-trusting**: expected hash is recorded at first run, never baked in
  (`sExpectedSigHash` starts null) — effectively always passes. Don't assume it protects anything.
- `SecurePrefs` — HMAC-SHA256-signed prefs, key derived from the APK signing cert
  (fallback literal `TS-WP-FALLBACK-KEY-2026`). Stores only `device-active`. Re-signing
  the APK or changing the HMAC scheme invalidates activation on all installs.
- `StringObfuscator` — XOR-encoded Supabase project URL + anon key; **the only source of
  those values in the app**. Trivially reversible (key is co-located) — never route real
  secrets through it. Sentinel: if the decoded URL contains `YOUR_SUPABASE_PROJECT`,
  activation fail-opens and sync/update silently no-op.

### Misc
- `Event` — Gson model (ProGuard-kept) for user alarms/events, pref key `events`.
- `Weather` — keyless: Open-Meteo geocoding/forecast + `http://ip-api.com` IP fallback
  (cleartext; `usesCleartextTraffic=true` exists for this). Arabic output strings.

## Invariants (break these → production incident)

1. `view_fsclock.xml` exists in `layout/` AND `layout-land/`; `commonInit()` findViewById's
   ~25 ids from whichever inflates. Edit both files together, keep ID sets identical.
2. Pref domain `"CLOCK"` and every pref key string are cross-class contracts; also see
   `FsClockApp.migrateSettings()` before renaming any key (it maps legacy → new names).
3. Server contract: RPC names/shapes (`get_wallpapers`, `activate_device`, `"inactive"`
   sentinel), `app_versions` columns, hardware-id prefixes, serial prefix `7078`.
4. ProGuard: new XML-referenced views / Gson models / reflection targets need keep rules.
   `Log.v/d/i/w` stripped in release.
5. Release JSON in `WallpaperRepo` is built by string concatenation (`:523`, `:556`) —
   inputs are not escaped; be careful what reaches those strings.

## Bug status (see root AGENTS.md §8 for the full log)

Fixed 2026-07: `WallpaperRepo` thread safety (volatile + snapshots), RPC bodies via
`JSONObject`, widget 24h default aligned to the clock, `checkCode()` stream-order,
notification-count guard, Amazon receiver moved to the amazon flavor manifest.
Still present by choice: dead review-dialog code in `FullscreenActivity` (R8 strips it),
self-trusting IntegrityGuard (baking a hash could brick fielded activations).
Robustness guarantee worth knowing: `sync()` failure paths never touch the cached
playlist — wallpapers only leave a device when the server explicitly returns
`"inactive"` or the admin deletes rows. Keep it that way.
