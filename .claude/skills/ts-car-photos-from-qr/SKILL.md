---
name: ts-car-photos-from-qr
description: >
  Use when photos arrive together with a screenshot of a car's QR / "Device Linking Code"
  (كود ربط الجهاز) and must be put on that one car — "ضيف صور للعربية", "حط الصور دي على
  العربية", "GWM split", "Jetour" / "جيتور", "صور العدادات", "صور الطبلون", "صور الشاشة
  الرئيسية", or a SendMessage from another session forwarding a customer's photos plus QR.
  Also when replacing photos delivered earlier to one car's special screen.
version: '1.0'
---

# Car photos from a QR screenshot

One customer, one car, one screen. The QR names the car; the owner names the screen. Plain
photos for the **main screen** go up as they are; photos for any **special screen** (GWM Split,
Jetour strip, instrument cluster / طبلون) are made by **DPQ** first. Background on the library
model, caching and removal: `ts-wallpaper-content-ops` (read it if anything below is unclear).

## Screen → channel → RPC → DPQ template

| Owner says | `channel` | Car reads via | Car must report `mode` | Lands on the car | DPQ template (D1, live 2026-11-08) |
|---|---|---|---|---|---|
| main screen / خلفيات عادية / the app's own wallpaper | `app` | `get_wallpapers` | any (leave `target_mode` null) | our slideshow, or the picker on Leopard/Lynk&Co | **none — upload as-is** |
| GWM Split | `gwm_split` | `get_gwm_wallpapers` | `gwm` | `/sdcard/Pictures/GWMSplit_Styles` → MediaStore → vendor GWM Split app | **Tank 500** `58e129ab-0347-4aea-831a-54fd6ff8a1af`, 1280×480 |
| Jetour G700 strip picture (centre/right slot of the 4032×284 cluster strip) | `jetour_g700` | `get_jetour_wallpapers` | `jetour` | `/sdcard/Pictures/G700` → TS Dash G700 picker (`CustomArt`, slot 1400×284) | **Jetour** `adff5856-4303-4105-b08c-39fcdf7ddb21`, 4200×852 (= 3 × 1400×284) |
| Leopard TS Leo Dash (instrument screen) | `leopard_dash` | `get_leopard_wallpapers` | `leopard` | `/sdcard/Pictures/TS Leo Dashboard` | **no exact template** — screen is 1920×720, DPQ's "BYD Dashboard" / "Full BYD Dashboard" are 1980×720 → **ask** |
| Jetour panoramic background (DP_3, 4032×284), any other Jetour/other screen | — | — | — | no channel exists | **STOP and ask the owner. Do not improvise a channel, a template or a crop.** |

Every delivery here is **private**: `is_global=false`, `hardware_id=<full id>`, no `target_mode`.
Sources: channels + modes `wallpapers_manager.html` `FOLDER_CHANNELS`; RPCs `supabase/schema.sql`
and `FolderMirror.java` (`GWM`/`JETOUR`/`LEOPARD`); `target_mode` CHECK `schema.sql:77`;
templates `GET /dpq/api/templates`; Jetour slot sizes TS-Dash-Jetour-G700 `CustomArt.java` /
DPQ-V3 `docs/pipelines/jetour-4-1.md`; Leo Dash 1920×720 the hint in `FOLDER_CHANNELS`.
If the live template list ever changes, `node scripts/dpq-run.mjs --template x --out x x` prints it.

## Procedure

Scripts live in `scripts/` next to this file (run from there; secrets are read at runtime from
`~/.ts-secrets/ts-wallpapers/` and `DPQ-V3/.env.local` — never paste them anywhere).

0. **Request came from another session?** Reply `استلمت: <n> صور لـ<screen>، جارٍ التنفيذ` by
   SendMessage to its `from=` first, then the result at the end.
1. **Find the QR screenshot** among the images — it is the linking screen, not a wallpaper:
   `py -3.14 -I scripts/decode-qr.py <all images>`. The image with a `qr` value is the linking
   screen; every image with `qr: []` is a photo. Use the **decoded** id: the on-screen text is
   often cut off. No QR decodes → ask for a clearer screenshot; never reconstruct the id by eye.
2. **Check the car:** `node scripts/check-device.mjs <HW_ID> --channel <channel>`.
   Exit 2 = stop (zero/many rows, inactive, blocked). Exit 3 = the car's last mode does not fit the
   channel (the mirror only runs in that mode) — tell the owner and wait. Its `privateRows` show
   what this car already has on that channel.
3. **DPQ or as-is.** Main screen → as-is, skip to 5. Special screen → DPQ, unless every photo is
   *already exactly* the template size (then skip DPQ and say so in the report). No template for
   that screen → stop and ask.
4. **DPQ:** `node scripts/dpq-run.mjs --template <id> --out <dir> <photos>` (costs money — one run
   per set). It waits for QA, re-runs a failed/QA-rejected project **once** from Phase 1, downloads
   the finals and checks exact size. Then **open and look at every output** (Read the jpg): subject
   whole, not cropped, nothing odd. A bad one after the retry → report it, don't deliver it.
5. **Deliver:** `node scripts/deliver.mjs --hw <HW_ID> --channel <channel> --out <dir> --dry-run <files>`,
   check the plan, then the same without `--dry-run`. It uploads to R2 under a new UUID name,
   inserts the row **with `x-write-key`**, checks HTTP 200 + byte count, and confirms the anon RPC
   for that channel lists every new URL. Exit 2 = something failed; read its output (an
   "ORPHAN R2 object" line means the row insert failed after the upload).
6. **Replacing earlier photos?** Only after step 5 passed:
   `node scripts/remove-rows.mjs --hw <HW_ID> --dry-run <old ids>`, then without `--dry-run`.
   Row first, R2 object after (the dashboard's حذف order). It refuses global rows, other cars'
   rows and URLs shared with another car.
7. **Report** (below). No commit — content ops change no repo file.

## Report — these parts, in this order

1. Car: full hardware id (VIN), `is_active`/`is_blocked`, last mode, app version, `last_seen_at`.
2. Screen → channel → RPC used.
3. DPQ: template, project ids, QA result per image, retries, total USD — or "as-is" / "already
   template size, DPQ skipped".
4. Rows: new ids + URLs; HTTP/bytes check; RPC listed n/n. Removed old ids, if any.
5. When the car picks them up: folder channels — app launch, clock screen open/resume and its
   5-minute tick, car start (`BootReceiver`), or Settings → Wallpapers → «زامن الآن» in that
   channel's section (bypasses the 30 s debounce; needs the all-files permission). `app`
   channel — app open/resume + 5-minute tick; on Leopard/Lynk&Co the next picker open.
6. "Not verified on the car screen" — unless it actually was.

## Traps

- **The vendor GWM Split app shows only 1280×480 (8:3).** Raw 387×513, 370×400 and 1024×1024
  photos *did* reach a car's folder (edge logs showed three full fetches) and the app showed one
  of three. All stock TANK photos in `Cars installer\Assets\TANK\Photos` are 1280×480. That is
  why GWM goes through DPQ Tank 500 — never "just upload" phone photos to `gwm_split`.
- **The mirror filters nothing and never re-downloads.** `FolderMirror` fetches every URL the
  RPC lists (no size/type/count check); the file name is the URL's UUID, and an existing file is
  skipped (`FolderMirror.java` ~295, `fileNameFor` ~337). A replacement is always a new upload;
  overwriting bytes at an old URL is never seen. Deleted rows → files removed on the next sync.
  A failed download is retried next sync.
- **GWM Split reads MediaStore, not the folder.** The mirror calls `MediaScannerConnection.scanFile`
  after downloading. Files present but not shown → «زامن الآن», then fully close and reopen the
  GWM Split app. With ADB available: `adb shell ls /sdcard/Pictures/GWMSplit_Styles` is definitive
  (this skill itself never uses ADB).
- **`x-write-key` on every write.** RLS on `wallpapers` is `auth.uid()=admin AND panel_write_ok()`;
  without the header the insert is refused after the R2 upload succeeded → orphan R2 file (happened
  twice). A DELETE without it returns 200 with `[]` and deletes nothing. The repo copy of
  `wallpapers_manager.html` does not send the key — the live panel goes through the admin site
  Worker, which adds it server-side — so don't copy its fetch calls.
- **`last_seen_at` is stamped only by `report_device_mode`** (app open / boot), not by mirror
  syncs. A stale value doesn't mean the car never fetched; Supabase edge logs (24 h) show
  `get_*_wallpapers` calls from the car's Dalvik user agent.
- **Hides don't work on mirror channels** — the mirror RPCs ignore `wallpaper_hides`. Remove = delete the row.
- **Only this car.** Never touch another car's rows, never make a row global, never set
  `target_mode` on a private delivery. Two images that look alike for two customers are two uploads.
- Customer-visible Arabic text (if you write any) is فصحى. No ADB, no car commands, no commits.

## Related

- `ts-wallpaper-content-ops` — library model, caching, removal, folder mirrors.
- `ts-device-triage` — the car is inactive, blocked or in the wrong mode.
- DPQ-V3 `AGENTS.md` / `PIPELINE.md` — the pipeline behind `dpq-run.mjs`.
