---
name: ts-crash-triage
description: >
  Read and act on TS Wallpapers crash reports from the fleet — find the crashes for one car or
  across the fleet, understand what a report contains, and keep the pipeline that produces them
  intact. Covers the on-car crash files, the forgiving report_crash RPC, the dashboard card, and
  the ProGuard rules without which every fleet trace becomes unreadable. Use whenever the task is
  "العربية بتقفل", "الأبليكيشن بيفصل", a crash/ANR report, reading device_crashes, or any edit to
  CrashReporter or proguard-rules.pro.
version: '1.0'
---

# Crash triage

Crashes are collected on the car and uploaded on the **next launch**, not at crash time — a
process that is dying cannot be trusted to make a network call. So a fresh crash appears in the
backend only after the app is opened again (or the car reboots into it).

## 1. What the fleet reported

```sql
-- last 7 days across the fleet, worst-first
select device_mode, app_version, count(*) , max(received_at)
  from public.device_crashes
 where received_at > now() - interval '7 days'
 group by 1,2 order by 3 desc;

-- one car, newest first
select crash_at, received_at, app_version, version_code, device_mode, left(crash_text, 800)
  from public.device_crashes
 where hardware_id = public.resolve_device_id('<HW_ID>')
 order by received_at desc limit 5;
```

Column names differ from the RPC arguments: the table stores `version_code`, the RPC takes
`app_version_code`. `crash_at` is what the **car** said (text, its own clock — cars are often
wrong); `received_at` is server truth. Order by `received_at`.

The dashboard shows the same data in the **"أعطال آخر ٧ أيام"** card
([`wallpapers_manager.html:1502`](../../../wallpapers_manager.html)) with a full modal at
`:1816`. Two things to know about that card: `device_crashes` is readable by **any authenticated
user** (`using (true)` — unlike every other table here, which is scoped to the admin UID; the
schema documents this deliberately), and the card counts client-side over the **newest 300 rows
only**, so a week with more than 300 fleet crashes undercounts. Query SQL directly when the
number matters.

## 2. What a report contains

Written by [`CrashReporter.java`](../../../source/app/src/main/java/systems/sieber/fsclock/CrashReporter.java)
to **internal** storage, `filesDir/crashes/crash-<ms>.txt` (not cache — cache can be evicted
before the car is ever opened again):

- a machine-readable JSON header (crash time, version name/code, mode, device id), then the same
  in human-readable form plus manufacturer/model, Android version, thread
- the stack trace
- **breadcrumbs** — a ring of the last 40 `CrashReporter.breadcrumb(...)` calls, the app's own
  trail of what it was doing. They exist because release builds strip `Log.v/d/i/w`; a breadcrumb
  is our own method, so it survives R8, and it also emits `Log.e`, which is not stripped.
- the tail of logcat (last 300 lines, capped at 24 KB)

Upload is capped at 60 KB of text; the server truncates at 200 000 characters anyway. The car
keeps the **newest 25 crash files** (sent and unsent together) — enough to see a pattern, few
enough that a crash loop cannot fill the disk.

## 3. Reading crashes on the car itself

- **In the app:** Settings → Updates → **"سجل الأعطال"**. The button only exists when the count
  is > 0 ([`BaseSettingsActivity.java:236`](../../../source/app/src/main/java/systems/sieber/fsclock/BaseSettingsActivity.java)),
  and it shows the last crash in full — that is deliberate, so a technician standing at an
  offline car can still read it.
- **Over ADB:** the files stay on the car after upload, renamed with a `.sent` suffix.

```bash
adb shell run-as store.thabthaba.clock ls files/crashes
```

  (`run-as` only works on a debuggable build; on a release install use the in-app viewer or the
  backend.)

## 4. Why a report may be missing

| Symptom | Cause |
|---|---|
| Car crashed, nothing in `device_crashes` | The app has not been launched since. Upload happens on the **next** start, from `FsClockApp.onCreate`. |
| Nothing ever, from one car | It may be offline, or on a very old APK. Check `last_seen_at` / `app_version_code` (`ts-device-triage`). |
| Report stored under a hardware id you don't recognise | `report_crash` calls `migrate_device_hardware_id` — wrap lookups in `resolve_device_id()`. |
| Silence with no error | **By design.** `report_crash` returns quietly on a null id or empty text, and the client treats HTTP 404 as "sent". A crash report must never be the thing that errors on a car that is already broken. |
| Older reports gone | The RPC keeps only the **newest 50 per car** server-side (and the car itself keeps 25 files). |

## 5. The two things that must not be broken

1. **`-keepnames class systems.sieber.fsclock.** { *; }`** plus
   `-keepattributes SourceFile,LineNumberTable` in
   [`proguard-rules.pro`](../../../source/app/proguard-rules.pro). Remove them while "hardening"
   obfuscation and every fleet trace turns back into `a.b.c(SourceFile:1)` — unusable, because
   nobody will still have the mapping file for the build that car is running.
2. **`CrashReporter.install(this)` is the first line of
   [`FsClockApp.onCreate`](../../../source/app/src/main/java/systems/sieber/fsclock/FsClockApp.java)** —
   before `migrateSettings()`, `IntegrityGuard.init()` and even `super.onCreate()`. A handler
   installed later misses exactly the startup crashes it exists to catch. Never reorder that
   block when adding startup work.

When you add risky startup or background work, add a `breadcrumb()` next to it rather than a
`Log.d` — the log line will not exist in the build that crashes on a customer's car.

## 6. Turning a report into a fix

1. Identify the car and mode from the row — a trace only reproducible in Leopard or Jetour is
   about the hand-off/mirror path, not the clock screen.
2. Reproduce on a bench car with a **debug** build for readable logs
   (`ts-local-build-and-test-car`, and read its warning about activation first).
3. Ship the fix with `ts-wallpapers-release`, then watch the card: crashes from that
   `version_code` should stop appearing while older cars keep reporting until they update.

## Related

- `ts-device-triage` — the car's row, version and connectivity.
- `ts-local-build-and-test-car` — reproducing on hardware.
- `ts-android-ui-change` — ProGuard keep rules when adding classes.
