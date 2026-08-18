---
name: ts-wallpaper-content-ops
description: >
  Add, target, replace, hide or remove wallpaper content for the TS Wallpapers fleet — the
  library model (global vs per-car, target_mode, channels), how a car actually receives and
  caches it, the R2 upload path, and what a Leopard/Lynk & Co car does with a picked file.
  Encodes the caching trap that makes a "replaced" wallpaper never change on the car, and the
  privacy rules that keep one customer's picture off another customer's screen. Use whenever the
  task is "ضيف خلفية", "الصورة مش ظاهرة", "امسح الخلفية", targeting a wallpaper at one car or one
  car type, or any edit to the wallpapers table / channels / folder mirrors.
version: '1.0'
---

# Wallpaper content operations

## The model

One table, [`public.wallpapers`](../../../supabase/schema.sql), decides everything:

| Column | Meaning |
|---|---|
| `url` | **unique.** The bytes live on Cloudflare R2, not Supabase Storage. |
| `type` | `image` \| `video` — set at upload from the file extension. |
| `is_global` | true = every activated car. |
| `hardware_id` | set = private to that one car (`on update cascade`, so an id migration keeps it). |
| `target_mode` | null = every car; otherwise only cars whose **last reported** mode matches. **A CHECK constraint lists the allowed modes** — a new car mode needs this constraint migrated. |
| `channel` | `app` = our slideshow. `gwm_split` / `jetour_g700` = folder mirrors, never shown by us. Deliberately unconstrained text. |

Plus `public.wallpaper_hides (wallpaper_id, hardware_id)` — one car hiding one global wallpaper,
which is how "this customer doesn't want that picture" is expressed without touching the library.

`get_wallpapers` returns, for an **active, unblocked** car only:
`channel = 'app'` AND (`is_global` OR `hardware_id` = this car) AND (`target_mode` is null OR
matches the car's `mode`) AND not hidden — newest first. An inactive car gets the single
sentinel row `url = 'inactive'`.

## Adding content

Use the dashboard ([`wallpapers_manager.html`](../../../wallpapers_manager.html)) — the upload is
two services on purpose and doing it by hand gets it wrong:

1. the **bytes** go to the R2 Worker (`ts-wallpapers-upload.tsdash-qatar.workers.dev`), which
   checks the caller's admin session;
2. the **row** is inserted straight through PostgREST under that same admin session.

Both steps matter for privacy: every upload is renamed to a **random UUID** before it is sent.
Customers all send `1.jpg`; identical names once let one car's private wallpaper overwrite
another's at the same URL. Anonymous storage *listing* is blocked as well, so a private URL is
unguessable rather than merely unlinked.

A failed row insert leaves an orphan object in R2 — the harmless direction (a few unreferenced
KB). The opposite, a row pointing at bytes that were never stored, is a broken wallpaper on
every car it reaches.

## Replacing content — the trap

**Never overwrite the bytes at an existing URL.** The car keys its cache off the URL:

- the playlist is cached in SharedPreferences under `wallpaper-cache-json`;
- videos are pre-downloaded to `cacheDir/wallpapers/vid_<hash-of-url>.bin`.

Same URL → same cache entry → the car keeps showing the old picture, and there is no way to
tell it otherwise. It is worse than one cache: a folder mirror skips a destination file that
already exists, and the Lynk & Co stager returns the already-staged file purely because it is
there. The R2 Worker itself refuses to overwrite an existing wallpaper key, which is the
guardrail. **Upload the new file (new random name = new URL) and delete the old row.**

The app-channel sync runs every ~5 minutes — but that timer belongs to the clock screen. The
hand-off modes (Leopard, Lynk & Co) have no slideshow: their picker refreshes once per open, so
a Leopard car sees the change the next time somebody opens the app, not five minutes later.

## Removing content

- **From one car only:** add a `wallpaper_hides` row (the dashboard's hide button). The library
  keeps the wallpaper for everyone else. **Hides only work on the `app` channel** — the mirror
  RPCs (`get_gwm_wallpapers`, `get_jetour_wallpapers`) do not consult `wallpaper_hides`, even
  though the dashboard offers the hide button on those rows too.
- **From everyone:** delete the `wallpapers` row first, then the R2 object — that is the order
  the dashboard uses, and a failed object delete is only a warning because the row is what cars
  read. The other order serves a broken image until the next sync.
- A car that is offline keeps showing its cached playlist until it syncs. That is intended, not
  a bug to work around.

## Where a car gets its pictures from, in order

1. **The cloud playlist** (above), cached.
2. **Local files** in `externalFilesDir/Wallpapers` — the file picker and the LAN phone upload
   (QR → local HTTP server). Files that arrived from a phone carry a `RECEIVED_` filename
   prefix; that prefix is how the app tells them apart, so don't rename them.
3. **Pre-activation prefetch** — `get_prefetch_wallpapers()` lets a car start downloading the
   shared library while the activation card is still on screen. It exposes **global,
   non-targeted** rows only, capped at 60. Nothing device-specific; the activation gate on
   `get_wallpapers` is untouched. Don't "extend" it to per-car rows.

## Hand-off cars (Leopard, Lynk & Co) — the fit is baked, not drawn

In the hand-off modes the app does not render anything: it gives a **file** to the head unit.
So a crop/zoom/rotation edit only reaches the screen if something re-bakes that file.
[`LeopardPickerActivity`](../../../source/app/src/main/java/systems/sieber/fsclock/LeopardPickerActivity.java)
bakes the current fit into `cacheDir/leopard/<stable-name-per-url>.jpg` (JPEG q95) at pick time,
and re-bakes stale uploads; the same source URL always bakes to the same file name so a re-edit
replaces it.

Freshness is decided by timestamp: every fit/focal/reset writes `wp-fit-at:<key>`, and a baked
file counts as current only when its mtime is at least that stamp — otherwise the picker rebakes.

Consequences worth remembering:

- Editing the fit anywhere that does not bake changes nothing on a Leopard/Lynkco car.
- **Changing the source bytes does not invalidate a bake** — only the fit timestamp does. A new
  URL (new content identity) is the reliable way.
- **Lynk & Co stages a copy** under `/sdcard/ThabthabaWallpaper/` before handing the path to the
  Flyme theme app, and it skips the copy if a file is already staged at that name — so a
  re-baked edit can be handed an older staged copy. If a Lynkco car keeps showing the previous
  crop, that staged file is the thing to clear.
- Leopard copies the chosen still to `filesDir/leopard-current/current.jpg` and bumps a revision
  for the live-wallpaper service; videos play on the wallpaper surface directly (never baked).
- A failed bake falls back to the picture as it arrived — not to a blank screen.
- Clearing the app's cache drops baked files; they are re-created on the next pick.

## Folder-mirror cars (GWM, Jetour)

Their content is **not** the `app` channel. Each mirrors one channel into one on-device folder
(`gwm_split` → `/sdcard/Pictures/GWMSplit_Styles`, `jetour_g700` → `/sdcard/Pictures/G700`) via
its own RPC, so the head unit's own gallery app reads it. Uploading a picture to the wrong
channel is the usual reason "the picture never showed up": check `channel` before anything else.
The mirror makes the folder match the manifest — it removes files it manages and leaves files it
doesn't (a technician's own photos and QR uploads are not ours to delete). An inactive, blocked
or unrecognised response becomes "no manifest", **not** an empty one, so the folder is left
untouched rather than emptied — that safety belt is why one unrecognised call no longer wipes a
car's folder. Keep it if you touch that code.

## When a wallpaper doesn't reach a car — check in this order

```sql
select is_active, is_blocked, mode from public.devices
 where hardware_id = public.resolve_device_id('<HW_ID>');           -- 1. gate + mode

select * from public.get_wallpapers('<HW_ID>', null);                -- 2. what it really gets

select id, is_global, hardware_id, target_mode, channel, created_at  -- 3. the row itself
  from public.wallpapers order by created_at desc limit 10;

select * from public.wallpaper_hides                                  -- 4. hidden for this car?
 where hardware_id = public.resolve_device_id('<HW_ID>');
```

Most common answers: the car's `mode` is null or different from `target_mode`; the row landed on
a mirror channel instead of `app`; the car hasn't synced yet; or it is inactive/blocked and is
getting the `inactive` sentinel (`ts-device-triage`).

**If instead the wrong content reaches cars** — GWM pictures appearing in the slideshow, or hides
stopping working — suspect `get_wallpapers` itself: a migration once recreated it without the
`channel` and `wallpaper_hides` filters and did exactly that (fixed by
[`20260727_restore_channel_and_hide_filters.sql`](../../../supabase/migrations/20260727_restore_channel_and_hide_filters.sql)).
All three filters — channel, ownership/target, hides — must survive every rewrite of that
function.

## Related

- `ts-device-triage` — the car's gate, mode and sync state.
- `ts-car-mode-change` — adding a mode also means migrating the `target_mode` CHECK and adding a
  channel RPC.
- `ts-backend-rpc-change` — changing `get_wallpapers` or a mirror RPC.
