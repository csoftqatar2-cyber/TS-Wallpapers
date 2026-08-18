---
name: ts-admin-dashboard-change
description: >
  Change the TS Wallpapers admin dashboard — the single 4200-line wallpapers_manager.html at the
  repo root that the operator uses to upload wallpapers, target cars, block devices, publish APKs
  and read crashes. Gives the file map, the session/RLS model, which writes go through an RPC vs
  straight to PostgREST, how the page reaches production, and the traps (duplicated admin identity,
  never PATCH the block flag, stale comments). Use whenever the task edits wallpapers_manager.html,
  adds a control/column/filter to the manager, or debugs "الداشبورد مش شغالة" / an upload failure.
version: '1.0'
---

# Editing the admin dashboard

[`wallpapers_manager.html`](../../../wallpapers_manager.html) is **one file** — markup, CSS and
JS together, no build step, no framework, no bundler. UI wiring is global functions plus inline
`onclick`. Third-party pieces (SweetAlert2, Font Awesome, confetti, jsQR, Google Fonts) come from
CDNs at the top of the file. Follow that style; don't introduce tooling.

## Map (line numbers drift — grep the nearby anchor text before trusting them)

| Area | Around |
|---|---|
| CDN deps | 12–18 |
| CSS | …–1397 |
| Login overlay | 1404–1418 |
| Header / connection indicator / logout | 1422–1438 |
| Stat cards (incl. 7-day crashes) | 1440–1508 |
| Upload form: channel, target mode, global/private, device picker, files | 1516–1667 |
| Wallpaper library grid + filters | 1670–1707 |
| APK update publisher | 1710–… |
| Modals: private wallpapers / devices / crashes / per-car hides | 1749, 1778, 1812, 1839 |
| **Config constants** (project ref, anon key, REST endpoints, R2 worker, admin email) | 1871–1891 |
| Session: login, refresh, logout | 1888–1942 |
| Channel + target-mode maps | 1944–2065 |
| Boot + loaders (`startApp`, `loadDevices`, `loadWallpapers`, `loadWallpaperHides`) | 2069–2250 |
| Upload flow (`startUpload` → `uploadViaFunction`) | 2760–2938 |
| APK publish | 2944–3015 |
| Wallpaper delete / per-car hides | 3187–3391 |
| Crashes load + render | 3402–3506 |
| Device actions | 3509–3698 |

## The session and RLS model

- The page logs in as the **fixed** Supabase Auth user `admin@tswallpapers.app` — password only,
  the email is hardcoded. The access token lives in `SESSION_TOKEN`; the **refresh** token is in
  `localStorage` under `ts_wm_session`, and a refresh is scheduled ~1 min before expiry.
- The **anon key is public by design** (it is also embedded in the app and in CI). It is the
  bearer token before login and it can read nothing admin-scoped — RLS is the actual gate. A
  broken session therefore shows **empty lists, not errors**: always test logged in.
- Everything the page writes rides that admin session. No service-role key exists anywhere in
  this file, and none may be added.

**The admin identity is duplicated in four places** and they must stay coherent:
the Auth user itself, the UID-based RLS policies in Postgres, the same UID inside the R2 upload
Worker, the same UID inside `admin_set_device_block` — and `app_versions` policies keyed by
**email** rather than UID. Recreating the admin user changes the UID and silently breaks the
three UID checks while the email-based ones keep working.

## Which write goes where

| Action | Path |
|---|---|
| Upload wallpaper bytes | POST to the R2 Worker (`ts-wallpapers-upload.tsdash-qatar.workers.dev`) with the admin bearer token |
| Wallpaper metadata row | direct PostgREST insert into `wallpapers` |
| Delete wallpaper | delete the row **first**, then ask the Worker to drop the object (an R2 failure is only a warning — the row is what cars read) |
| Per-car hides | direct delete + bulk insert on `wallpaper_hides` |
| Rename / activate / deactivate device | PATCH `devices` |
| Delete device | DELETE `devices` |
| **Block / unblock** | **RPC `admin_set_device_block`** — never a PATCH |
| Publish an APK by hand | same Worker with an `apk` prefix, then insert into `app_versions` |

The `admin-upload` Edge Function is **superseded** — its own header says so. Nothing calls it; it
is kept as a rollback path, and it does not even accept `target_mode`, so wiring it back would
silently drop mode targeting. The function that uploads is still *named* `uploadViaFunction()`
and one comment above it still says "admin-upload": **stale name, stale comment, R2 code.**

## Traps

- **Never PATCH `is_blocked`.** Cloudflare D1 owns the activation decision; the RPC writes D1
  first and Postgres second and fails loudly if D1 is unreachable. A PATCH leaves the car still
  refused by D1 — the "unblock button is broken" bug (`ts-device-triage`).
- **Deleting a blocked device does not unblock it.** The D1 block survives while the Postgres row
  carrying the UI's unblock control is gone. Unblock first, delete after.
- **Device delete can be rejected** while private wallpapers still reference that
  `hardware_id` (`wallpapers.hardware_id` cascades on *update*, not on delete). Clear the car's
  private images first; the UI warns that they are not deleted automatically.
- **Insert `target_mode` with the row, not afterwards.** A global row that exists for even a
  moment without its target mode is eligible for the whole fleet and may already be cached.
- **RLS can answer "success" with zero rows changed.** The delete path asks for
  `Prefer: return=representation` and rejects an empty response — keep that check when you copy
  the pattern.
- **The 7-day crash count is computed client-side over the newest 300 rows.** More than 300 fleet
  crashes in a week undercounts the card.
- **Adding a car mode or channel means editing five maps in this file** — see
  `ts-car-mode-change`, touchpoint 11.
- **Line numbers in comments and docs go stale fast** (AGENTS.md pointed at 1519 for the config
  block, which is now upload markup). Grep for the constant name, don't jump to a line.

## Deploying

Vercel serves this file at `/` via [`vercel.json`](../../../vercel.json)
(`cleanUrls` + rewrite `/` → `/wallpapers_manager`), and
[`.vercelignore`](../../../.vercelignore) excludes everything else — sources, APKs, keystores,
SQL, Markdown. **Renaming or moving the file breaks the site** unless the rewrite moves with it.
The repo does not record how the Vercel project is linked or triggered, so after pushing a change
confirm the live page actually updated instead of assuming a deploy ran.

## Checklist

- [ ] Tested **logged in** (an empty list is what a session problem looks like).
- [ ] No secret added beyond the anon key; no service-role key.
- [ ] Any device-blocking write goes through the RPC.
- [ ] New wallpaper writes carry `channel` **and** `target_mode` in the original insert.
- [ ] Live page verified after deploy.

## Related

- `ts-device-triage` — the device rows this page edits.
- `ts-wallpaper-content-ops` — what the library rows mean to a car.
- `ts-car-mode-change` — the five maps a new mode touches here.
- `ts-backend-rpc-change` — if the change needs a new RPC or policy.
