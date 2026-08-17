---
name: ts-device-triage
description: >
  Diagnose one car in the TS Wallpapers fleet — why it shows the activation screen, why it
  is blocked, why it has no wallpapers, what version it runs, and when it last checked in.
  One query answers most of it. Covers the block/unblock path (D1 first, Postgres second)
  and the direct-UPDATE trap that makes the unblock button look broken. Use whenever a
  customer says a car stopped working, "العربية مش شغالة", "الجهاز محظور", a device won't
  activate, wallpapers disappeared, or you need a car's status/version.
version: '1.0'
---

# TS Wallpapers device triage

The fleet is keyed by **hardware id**, not by serial. Everything below runs against
Supabase Postgres (project `ihgmqwzdpugdzddobhbc`) via the Supabase MCP `execute_sql`.
Postgres mirrors every decision Cloudflare D1 makes and serves all reads — so start there
and only reach for D1 if Postgres and the car's behaviour disagree.

## 1. Get the hardware id

Prefixes: `VIN-` `MAC-` `SYS-` `BOOT-` `SRL-` `AID-`. Sources, in order of ease:

- The car's activation screen shows it.
- The dashboard ([`wallpapers_manager.html`](../../../wallpapers_manager.html)) — search by
  client name or serial.
- Over ADB, if the car is reachable (see `adb-headunit-automation`).

A car may have changed its id (e.g. it learned its VIN after a boot). `resolve_device_id()`
follows `device_id_aliases`, so always wrap the id in it — never compare `hardware_id` raw.

## 2. The one query that answers most tickets

```sql
select hardware_id, serial_number, client_name,
       is_active, is_blocked, block_reason, blocked_at,
       failed_attempts, last_failed_at, last_failed_serial,
       mode, app_version, app_version_code,
       activated_at, last_seen_at
  from public.devices
 where hardware_id = public.resolve_device_id('<HARDWARE_ID>');
```

### Reading the answer

| What you see | What it means | What to do |
|---|---|---|
| **No row** | Never registered. The car shows the activation screen and asks for a serial. | Give the customer a serial (`578…`; `7078…` is re-activation only). |
| `is_blocked = true`, `block_reason = 'failed_attempts'` | Auto-locked after 10 wrong codes. No serial will ever work. | Unblock via the dashboard (§4), then hand over the correct serial. |
| `is_blocked = true`, `block_reason = 'admin'` | Blocked by hand from the dashboard. | Confirm with the owner **before** unblocking — this is usually non-payment. |
| `is_active = false`, not blocked | Registered, activation withdrawn. | Re-activate from the dashboard, or with a `7078…` serial. |
| `is_active = true` but the car shows nothing | Not an activation problem → go to §3. | |
| `last_seen_at` old (days/weeks) | The car isn't reaching the backend at all. | Wi-Fi / connectivity on the car, not a backend issue. Leopard & Lynk&Co cars can legitimately go weeks without anyone opening the app — `BootReceiver` still checks in on each car start, so a truly stale `last_seen_at` means it isn't booting online. |
| `app_version_code` behind the latest | It never took the update. | Check `app_versions` (see `ts-wallpapers-release`) and whether it's online at all. |

## 3. What the car actually sees

Ask the backend the same two questions the app asks:

```sql
-- exactly what FsClockView.refreshBlockedState() gets: unknown | blocked | inactive | active
select public.get_device_status('<HARDWARE_ID>');

-- the playlist. A single row with url = 'inactive' is the "not activated" sentinel,
-- NOT a wallpaper. Zero rows = activated but nothing assigned to it.
select * from public.get_wallpapers('<HARDWARE_ID>', null);
```

An active car with an empty playlist means no wallpaper is assigned. Check the library and
its `target_mode` — a wallpaper tagged `target_mode = 'lynkco'` never reaches a `jetour` car,
and `mode` in `devices` is what the filter compares against. A car whose mode is `null`
(old APK that never reported) is invisible to every mode-targeted row.

Crash history for that car:

```sql
select crash_at, app_version, device_mode, left(crash_text, 400)
  from public.device_crashes
 where hardware_id = public.resolve_device_id('<HARDWARE_ID>')
 order by crash_at desc limit 5;
```

## 4. Blocking and unblocking — use the dashboard button

The button calls `admin_set_device_block(p_hardware_id, p_blocked)`, which writes
**Cloudflare D1 first, Postgres second**, and on unblock resets `failed_attempts` on *both*
sides.

```sql
-- authenticated (admin) only; anon is revoked
select public.admin_set_device_block('<HARDWARE_ID>', false);
```

> **Never `update public.devices set is_blocked = false` directly.** D1 owns the activation
> decision; Postgres only mirrors it. A direct UPDATE leaves D1 still blocking, so the car
> keeps being refused, and the stale counter re-locks it on the very next wrong code — which
> is exactly what makes the unblock button look broken.

## 5. When Postgres and reality disagree

Only then read D1 directly. The worker is `ts-activation`
([`cloudflare/activation-worker/`](../../../cloudflare/activation-worker/)), every endpoint
except `/v1/health` needs `Authorization: Bearer <SHARED_SECRET>`.

```bash
curl -s "https://ts-activation.tsdash-qatar.workers.dev/v1/devices/export?limit=1000" \
  -H "Authorization: Bearer $SHARED_SECRET"
```

The secret is **not in the repo** and not on this machine — ask the user for it (and for a
Cloudflare API token if `wrangler` is needed). Health check needs no secret:

```bash
curl -s https://ts-activation.tsdash-qatar.workers.dev/v1/health
```

## Facts that change the diagnosis

- **Three apps share this `devices` table** (TS Wallpapers, ذبذبة ستور, TS Back Button).
  Activating in any one activates all of them on that car. TS Back Button has no
  auto-update, which is why the RPC contract is frozen.
- **The car re-syncs every 5 minutes.** A block or an unblock is picked up within 5 min;
  the "Recheck" button in the app forces it immediately.
- **Serial prefixes:** `578…` for everything issued from 2026-08-02 on, `7078…` legacy /
  re-activation only. Enforced client-side (UX) *and* by a DB CHECK constraint.
- **A blocked car is frozen at the moment of blocking** — its record stops updating, so
  `last_seen_at` on a blocked device tells you when it was blocked, not whether it is online.
- **Car .57 (BYD FSE) cannot be screenshotted** — display 2 is FLAG_SECURE. Read state over
  ADB/logcat instead of trying to capture the screen.

## Related

- `ts-backend-rpc-change` — if the fix needs a schema or RPC change.
- `adb-headunit-automation` — reaching a car over ADB.
