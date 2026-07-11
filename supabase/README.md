# supabase/ — backend source of truth (project `ihgmqwzdpugdzddobhbc`)

Until 2026-07 most of the backend existed ONLY in the live Supabase project.
This directory captures all of it so the backend is reproducible and reviewable.

| File | What |
|---|---|
| [schema.sql](schema.sql) | Complete snapshot of the live backend: 4 tables, RLS policies, 2 storage buckets, 6 RPC functions. Restores everything on an empty project (read its header first). |
| [migrations/](migrations/) | Incremental changes. A migration file that says **PENDING** in its header has NOT been applied to the live project yet. |
| [functions/admin-upload/index.ts](functions/admin-upload/index.ts) | Source of the `admin-upload` Edge Function (deployed version 3). Deploy with `supabase functions deploy admin-upload`. |
| [../supabase_app_update_setup.sql](../supabase_app_update_setup.sql) | Legacy run-once setup for the self-update piece only — superseded by `schema.sql`, kept aligned so re-running it cannot reopen old security holes. |

## The contract with installed devices (never break these)

Every installed APK depends on:
- RPC `get_wallpapers(device_hw_id, legacy_hw_id)` → rows `(url, type)`; the single
  row `('inactive','image')` means "device not activated".
- RPC `activate_device(device_hw_id, activation_serial, legacy_hw_id)` →
  `'success' | 'blocked' | 'invalid_format' | 'serial_already_used'`.
- RPC `is_device_activated(device_hw_id)` → boolean — used by the companion
  programs; activation done in any one program activates all of them because
  they share the device's hardware id.
- Table `app_versions` (anon-readable) columns `version_code, version_name, apk_url, changelog`.
- Public download URLs `/storage/v1/object/public/{apk,wallpapers}/...`.
- Serial format `7078…` and hardware-id prefixes `VIN-/MAC-/SYS-/BOOT-/SRL-/AID-`.

## Privacy model (why private wallpapers stay private)

- Device rows are keyed by hardware id; `get_wallpapers` returns
  `is_global = true` rows **plus only** rows whose `hardware_id` matches the caller.
- Uploads get random UUID filenames (edge function enforces this server-side,
  `upsert:false`) so one customer's file can never overwrite another's.
- Direct table access is admin-only via RLS; after the pending migration,
  anonymous storage LISTING is also blocked, so private wallpaper URLs cannot
  be enumerated — they are only ever handed to the matching device.

## Secrets hygiene

- `schema.sql` deliberately replaces the `store_admin_*` shared secret with a
  placeholder — the real value lives only in the live functions. Never commit it.
- The admin user (`admin@tswallpapers.app`, uid `5b8e1336-…`) is created manually
  in Supabase Auth; policies and the Edge Function reference that uid.
- Advisors still recommend enabling **leaked password protection** (Auth →
  Passwords) — a dashboard toggle, cannot be done from SQL.
