---
name: ts-backend-rpc-change
description: >
  Change the TS Wallpapers Supabase backend without bricking cars in the field — RPC edits,
  new columns, migrations, grants. Encodes the frozen client contract, the "function is not
  unique" overload trap that breaks every car at once, the D1-first write order for anything
  touching activation, and the PENDING/applied migration convention. Use whenever the task
  touches an RPC, a migration, the devices/wallpapers/app_versions schema, RLS, grants, or
  the activation worker.
version: '1.0'
---

# TS Wallpapers backend change

The backend is shared by **three apps**, one of which (TS Back Button) has no auto-update.
Its request/response contract is therefore **frozen**: a fielded APK that gets an unexpected
answer has no way to recover. Source of truth lives in
[`supabase/`](../../../supabase/) — read [`supabase/README.md`](../../../supabase/README.md)
before touching anything.

## The three rules that prevent a fleet-wide outage

### 1. Never change an existing RPC's signature

`create or replace function f(a, b, c)` when `f(a, b)` already exists does **not** replace
it — it creates an *overload*. Postgres then answers a 2-arg call from an old APK with
`function is not unique`, and that call fails on **every car at once**.

Two safe moves, no third:

- **Keep the exact signature.** Add parameters only with `DEFAULT`s *and* drop the old
  signature in the same migration — never leave both live.
- **Create a new function under a new name.** This is what
  [`20260816_get_device_status.sql`](../../../supabase/migrations/20260816_get_device_status.sql)
  did rather than widening `get_wallpapers`'s answer: nothing is obliged to call it, and the
  app that does gains the distinction.

`report_device_mode` is the scar: it has 5 args with defaults and the 3-arg version was
**dropped**, deliberately.

### 2. Anything touching activation or block state goes to D1 first

Cloudflare D1 **decides** (it is the only place that sees attempts from all three apps, and
a counter inside an APK is defeated by clearing app data). Postgres **mirrors and serves**.

The dispatchers read their target from `cf.settings` (`worker_activate_url`,
`worker_set_state_url`) and call the worker over `pgsql-http` — budget ~300ms. Keep the
pass-through shape: when the worker sends nothing back, the function must still behave, so
the two deploys can land in either order and an older worker keeps working.

Do not add a second writer to `devices.is_blocked` / `is_active` / `failed_attempts`.

### 3. Preserve the client-visible contract

Every installed APK depends on:

- `get_wallpapers(device_hw_id, legacy_hw_id)` → rows `(url, type)`; the single row
  `('inactive','image')` is the "not activated" sentinel.
- `activate_device(device_hw_id, activation_serial, legacy_hw_id)` →
  `'success' | 'blocked' | 'invalid_format' | 'serial_already_used'` — exactly these strings.
- `is_device_activated(device_hw_id)` → boolean (the companion apps).
- `app_versions` anon-readable with `version_code, version_name, apk_url, changelog`.
- Serial prefixes `7078` / `578`; hardware-id prefixes `VIN-/MAC-/SYS-/BOOT-/SRL-/AID-`.
- `report_crash(...)` is deliberately forgiving — unknown device still stored, bad input a
  silent no-op. A crash report must never be the thing that errors on an already-broken car.

## Writing the migration

One file per change: `supabase/migrations/YYYYMMDD_short_name.sql`.

**Header first, and it explains WHY**, in the style of the existing files — the reasoning is
what makes the next change safe. Mark the state explicitly:

```sql
-- PENDING: not yet applied to the live project.
```

…and rewrite it once applied (`-- Applied live as 20260816_get_device_status_rpc`).
`supabase/README.md` treats **PENDING** as the marker for "live and repo differ".

Requirements for every migration:

- **Re-runnable**: `create or replace`, `add column if not exists`, `on conflict do nothing`.
- **`security definer` + `set search_path to 'public'`** on any RPC — devices are anonymous
  and reach data only through definer functions; the pinned search_path is what keeps
  advisor 0011 clear.
- **Explicit grants**, always in this shape:
  ```sql
  revoke all on function public.f(...) from public;
  grant execute on function public.f(...) to anon, authenticated;   -- device RPC
  -- admin RPC: revoke from public, anon;  grant to authenticated only
  ```
- **Wrap incoming ids** in `public.resolve_device_id()` so a car that changed its hardware id
  is still found through `device_id_aliases`.
- **A `_ROLLBACK.sql` sibling** for anything risky — precedent:
  `20260802_cf_activation_ROLLBACK.sql`, `20260804_device_id_aliases_ROLLBACK.sql`.

## Applying and verifying

1. Inspect the live schema first — don't guess: Supabase MCP `list_tables`, `execute_sql`.
   If the MCP server isn't authorized in this session, say so and ask the user rather than
   working from the snapshot alone.
2. Apply with `apply_migration`.
3. **Smoke-test the contract from the client's side**, not just the function's:
   ```sql
   select public.get_wallpapers('<A_REAL_ACTIVE_HARDWARE_ID>', null);   -- still a full playlist?
   select public.get_device_status('<A_REAL_ACTIVE_HARDWARE_ID>');      -- still 'active'?
   ```
4. Run `get_advisors` (security + performance) and confirm nothing new appeared.
5. **Update [`supabase/schema.sql`](../../../supabase/schema.sql)** to match live, and flip
   the migration header from PENDING to applied. An out-of-date snapshot is how the backend
   became irreproducible the first time.

## Never commit

- The `store_admin_*` shared secret — `schema.sql` deliberately carries a placeholder; the
  real value lives only in the live functions.
- The worker's `SHARED_SECRET` (set out of band: `npx wrangler secret put SHARED_SECRET`).
- The Supabase `service_role` key — GitHub Actions secret only.

The anon key and project URL are public by design; RLS and definer functions are the gate.

## Order of operations with an app release

Backend **first**, app second. The new backend must keep answering old APKs correctly before
any new APK depends on it. See `ts-wallpapers-release`.

## Related

- `ts-device-triage` — verifying the change against a real car.
- `supabase/README.md`, `AGENTS.md` §6 — the full backend picture.
