-- ============================================================================
-- TS Wallpapers — FULL Supabase backend snapshot (project ihgmqwzdpugdzddobhbc)
-- Regenerated from the LIVE project on 2026-08-02 (previous capture: 2026-07-11).
--
-- PURPOSE: disaster-recovery / documentation. This file recreates the whole
-- backend on an empty project. It is idempotent-ish (if not exists / or
-- replace / drop policy if exists) but review before running against a
-- project that already has data.
--
-- HOW TO KEEP IT HONEST: this snapshot is hand-maintained and drifted badly
-- between 2026-07-11 and 2026-08-02 (four tables and two RPCs existed live but
-- were missing here, and three function bodies were stale). Regenerate it
-- whenever a migration touches devices, the RPC bodies, or RLS.
--
-- NOT included here (create manually):
--   * Auth user admin@tswallpapers.app (Supabase Auth -> Add user). Its user
--     id is referenced by the RLS policies and the admin-upload Edge Function
--     (ADMIN_ID). On a NEW project, replace the UUID below everywhere.
--   * Edge Function admin-upload (source: supabase/functions/admin-upload/).
--   * The two secrets the Edge Function needs are provided automatically by
--     the platform (SUPABASE_URL / ANON_KEY / SERVICE_ROLE_KEY).
--   * The store-admin shared secret (see section 4, store_admin_* functions):
--     the real value lives ONLY in the live database. Never commit it.
-- ============================================================================

-- The single admin identity used by all admin policies:
--   auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'  (admin@tswallpapers.app)

-- ----------------------------------------------------------------------------
-- 1. TABLES
-- ----------------------------------------------------------------------------

-- Registered devices (cars). One row per hardware id; activation state lives here.
-- THIS TABLE IS SHARED with the two sibling programs (TS Back Button
-- com.csoft.backbutton, and ذبذبة ستور com.thabthaba.store): all three call
-- activate_device / is_device_activated against the same hardware_id, which is
-- what makes "activate one program, all of them unlock" work.
create table if not exists public.devices (
    hardware_id   text primary key,
    -- Two issued prefixes: '7078' for every code up to 2026-08-02 (~530 cars in
    -- the field), '578' for everything issued after. Length is not constrained;
    -- serials in the field run from 5 to 14 characters.
    serial_number text unique check (serial_number like '7078%' or serial_number like '578%'),
    client_name   text,
    is_active     boolean default false,
    created_at    timestamptz not null default timezone('utc', now()),
    is_blocked    boolean not null default false,
    -- Operating mode the device last reported
    -- (normal | fse | leopard | gwm | lynkco | jetour).
    -- null = an older APK that does not report yet. Set via report_device_mode RPC.
    mode          text check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco','jetour')),
    -- Fleet telemetry, all written by report_device_mode (added 2026-07-29).
    app_version      text,
    app_version_code int,
    last_seen_at     timestamptz,
    activated_at     timestamptz
);
comment on column public.devices.activated_at is
    'When the car was activated. Rows activated before this column existed were backfilled from created_at.';

-- Wallpaper catalog. is_global=true rows go to EVERY activated device;
-- otherwise the row belongs to exactly one device (hardware_id).
create table if not exists public.wallpapers (
    id          bigint generated always as identity primary key,
    url         text not null unique,
    type        text not null,              -- 'image' | 'video'
    is_global   boolean default false,
    -- ON UPDATE CASCADE so migrate_device_hardware_id can rename a device id
    -- without orphaning its private wallpapers.
    hardware_id text references public.devices(hardware_id) on update cascade,
    -- When set, this wallpaper only reaches cars whose last reported mode matches
    -- (e.g. 'lynkco' + is_global = "every Lynk & Co car"). null = the normal
    -- library that syncs to every car. See get_wallpapers.
    target_mode text check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco','jetour')),
    -- Delivery channel. 'app' = shown by our own slideshow. The others are NOT shown
    -- by us at all: the car downloads them into a folder the head unit's own app
    -- reads — 'gwm_split' into /sdcard/Pictures/GWMSplit_Styles (the same folder the
    -- Cars-installer script pushes photos to on a GWM car), 'jetour_g700' into
    -- /sdcard/Pictures/G700. Every channel has exactly one RPC and they must never
    -- leak into one another — see get_wallpapers / get_gwm_wallpapers /
    -- get_jetour_wallpapers. Deliberately unconstrained text: adding a car means
    -- adding an RPC, not migrating a check constraint.
    channel     text not null default 'app',
    created_at  timestamptz not null default timezone('utc', now())
);
create index if not exists wallpapers_channel_idx on public.wallpapers (channel);

-- Per-car hide list: a global wallpaper the operator hid on THIS car only.
-- (Was missing from this snapshot until 2026-08-02 even though get_wallpapers
-- has always referenced it — running the old snapshot on an empty project
-- would have failed.)
create table if not exists public.wallpaper_hides (
    wallpaper_id bigint not null references public.wallpapers(id) on delete cascade,
    hardware_id  text   not null references public.devices(hardware_id) on update cascade on delete cascade,
    created_at   timestamptz not null default timezone('utc', now()),
    primary key (wallpaper_id, hardware_id)
);
create index if not exists wallpaper_hides_hardware_id_idx on public.wallpaper_hides (hardware_id);

-- Published app builds; devices poll this to self-update (UpdateManager).
create table if not exists public.app_versions (
    id           bigint generated always as identity primary key,
    version_code int  not null,
    version_name text not null,
    apk_url      text not null,
    changelog    text,
    mandatory    boolean not null default false,  -- reserved, not yet used
    created_at   timestamptz not null default now()
);
create index if not exists app_versions_version_code_idx
    on public.app_versions (version_code desc);

-- Crash reports from the wallpapers app (written by report_crash; newest 50 per car kept).
create table if not exists public.device_crashes (
    id           bigserial primary key,
    hardware_id  text not null,
    app_version  text,
    version_code int,
    device_mode  text,
    crash_at     text,
    crash_text   text not null,
    received_at  timestamptz not null default now()
);
create index if not exists device_crashes_hardware_idx on public.device_crashes (hardware_id, received_at desc);
create index if not exists device_crashes_received_idx on public.device_crashes (received_at desc);

-- Store-app install tracking (the "ذبذبة ستور" companion program checks in here).
create table if not exists public.store_installs (
    hw_id      text primary key,
    car        text,
    version    text,
    blocked    boolean not null default false,
    first_seen timestamptz not null default now(),
    last_seen  timestamptz not null default now()
);

-- Crash reports from the store app (written by store_report_crash). Separate
-- from device_crashes on purpose: different program, different lifecycle.
create table if not exists public.store_crashes (
    id         bigint generated always as identity primary key,
    car        text,
    version    text,
    device     text,
    report     text not null,
    created_at timestamptz not null default now()
);

-- Safety net from the 2026-07-29 Cloudflare R2 media migration: the previous
-- Supabase Storage URLs, so the fleet can be pointed back if R2 ever goes away.
create table if not exists public.media_url_backup_r2 (
    id           bigint generated always as identity primary key,
    source       text not null,        -- which table the url came from
    row_id       bigint not null,
    old_url      text not null,
    backed_up_at timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- 2. ROW LEVEL SECURITY
--    Devices are ANONYMOUS: they never touch tables directly, only the
--    SECURITY DEFINER RPCs below. Tables are admin-only (or public-read where
--    devices need it). store_installs, store_crashes and media_url_backup_r2
--    intentionally have RLS enabled with NO policies: only their RPCs (and the
--    service-role key) can reach them.
-- ----------------------------------------------------------------------------

alter table public.devices             enable row level security;
alter table public.wallpapers          enable row level security;
alter table public.wallpaper_hides     enable row level security;
alter table public.app_versions        enable row level security;
alter table public.device_crashes      enable row level security;
alter table public.store_installs      enable row level security;
alter table public.store_crashes       enable row level security;
alter table public.media_url_backup_r2 enable row level security;

-- devices: admin-only (manager dashboard).
-- NOTE: wallpapers_manager.html PATCHes is_active / is_blocked directly through
-- these policies (patchDevice()), i.e. NOT through activate_device. Any change
-- to how activation state is stored must account for that write path.
drop policy if exists "dev admin read"   on public.devices;
drop policy if exists "dev admin insert" on public.devices;
drop policy if exists "dev admin update" on public.devices;
drop policy if exists "dev admin delete" on public.devices;
create policy "dev admin read"   on public.devices for select to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "dev admin insert" on public.devices for insert to authenticated with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "dev admin update" on public.devices for update to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid) with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "dev admin delete" on public.devices for delete to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

-- wallpapers: admin-only (devices get theirs through get_wallpapers RPC)
drop policy if exists "wp admin read"   on public.wallpapers;
drop policy if exists "wp admin insert" on public.wallpapers;
drop policy if exists "wp admin update" on public.wallpapers;
drop policy if exists "wp admin delete" on public.wallpapers;
create policy "wp admin read"   on public.wallpapers for select to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp admin insert" on public.wallpapers for insert to authenticated with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp admin update" on public.wallpapers for update to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid) with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp admin delete" on public.wallpapers for delete to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

-- wallpaper_hides: admin-only. There is deliberately NO update policy live —
-- a hide is inserted or deleted, never edited.
drop policy if exists "wph admin read"   on public.wallpaper_hides;
drop policy if exists "wph admin insert" on public.wallpaper_hides;
drop policy if exists "wph admin delete" on public.wallpaper_hides;
create policy "wph admin read"   on public.wallpaper_hides for select to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wph admin insert" on public.wallpaper_hides for insert to authenticated with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wph admin delete" on public.wallpaper_hides for delete to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

-- app_versions: EVERYONE (anon devices) may read; only the admin may write.
-- (CI writes with the service_role key, which bypasses RLS.)
drop policy if exists "app_versions public read" on public.app_versions;
drop policy if exists "app_versions anon write"  on public.app_versions;  -- legacy hole, keep dropped
drop policy if exists "ver admin insert" on public.app_versions;
drop policy if exists "ver admin update" on public.app_versions;
drop policy if exists "ver admin delete" on public.app_versions;
create policy "app_versions public read" on public.app_versions for select to anon, authenticated using (true);
create policy "ver admin insert" on public.app_versions for insert to authenticated with check ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');
create policy "ver admin update" on public.app_versions for update to authenticated using ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app') with check ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');
create policy "ver admin delete" on public.app_versions for delete to authenticated using ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');

-- device_crashes: read-only for the dashboard; rows are written by report_crash
-- (SECURITY DEFINER, so it bypasses RLS).
-- NOTE: live policy is `using (true)` for ANY authenticated user, not the admin
-- uid like every other table here. Documented as-is; tighten deliberately if wanted.
drop policy if exists "device_crashes_admin_read" on public.device_crashes;
create policy "device_crashes_admin_read" on public.device_crashes for select to authenticated using (true);

-- ----------------------------------------------------------------------------
-- 3. STORAGE
--    Both buckets are PUBLIC: devices download via
--    /storage/v1/object/public/<bucket>/<name>, which needs no policy.
--    Object writes (and listing, after the hardening migration) are admin-only.
--    NOTE: since the 2026-07-29 R2 migration, new wallpapers and APKs are served
--    from Cloudflare R2, not from these buckets. They are kept for old rows.
-- ----------------------------------------------------------------------------

insert into storage.buckets (id, name, public) values ('apk', 'apk', true)
    on conflict (id) do update set public = true;
insert into storage.buckets (id, name, public) values ('wallpapers', 'wallpapers', true)
    on conflict (id) do update set public = true;

drop policy if exists "apk anon read"  on storage.objects;                  -- legacy: allowed anon LISTING
drop policy if exists "apk anon write" on storage.objects;                  -- legacy hole, keep dropped
drop policy if exists "Allow anon select to wallpapers" on storage.objects; -- legacy: allowed anon LISTING
drop policy if exists "apk storage admin read"   on storage.objects;
drop policy if exists "apk storage admin insert" on storage.objects;
drop policy if exists "apk storage admin update" on storage.objects;
drop policy if exists "apk storage admin delete" on storage.objects;
drop policy if exists "wp storage admin read"    on storage.objects;
drop policy if exists "wp storage admin insert"  on storage.objects;
drop policy if exists "wp storage admin update"  on storage.objects;
drop policy if exists "wp storage admin delete"  on storage.objects;

create policy "apk storage admin read"   on storage.objects for select to authenticated using     (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin insert" on storage.objects for insert to authenticated with check (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin update" on storage.objects for update to authenticated using     (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid) with check (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin delete" on storage.objects for delete to authenticated using     (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp storage admin read"    on storage.objects for select to authenticated using     (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp storage admin insert"  on storage.objects for insert to authenticated with check (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp storage admin update"  on storage.objects for update to authenticated using     (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid) with check (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "wp storage admin delete"  on storage.objects for delete to authenticated using     (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

-- ----------------------------------------------------------------------------
-- 4. RPC FUNCTIONS (the device-facing API — anonymous, SECURITY DEFINER)
--    !!! These signatures/return shapes are a CONTRACT with every installed
--    APK of ALL THREE programs. Never rename them or change response values.
--    Two of the three (TS Back Button, ذبذبة ستور) have no auto-update path at
--    all, so a breaking change there can stay broken in the field indefinitely.
--
--    In particular:
--      * is_device_activated must NEVER 404. The sibling apps fall back to
--        probing get_wallpapers and treat "no http in the body" as NOT
--        activated — so an activated car with no wallpapers assigned would
--        deactivate itself. Always CREATE OR REPLACE, never DROP + CREATE.
--      * A failure must surface as an ERROR (non-2xx), never as a successful
--        "not activated" answer: all three clients keep their cached activation
--        on error, but clear it on a well-formed negative response.
-- ----------------------------------------------------------------------------

-- Device playlist. Activated+unblocked device -> its private rows + all global
-- rows. Anything else -> single sentinel row ('inactive','image'), which the
-- app understands as "not activated" (clears playlist, shows activation UI).
-- ANY app on the same device (same hardware id) gets the same answer — this is
-- why activating one of the programs activates all of them.
--
-- The filter below is three independent gates, all required:
--   channel='app'   -> GWM Split images are a file drop, never our slideshow
--   target_mode     -> a mode-targeted image only reaches cars in that mode
--   wallpaper_hides -> a global image hidden on this car stays hidden
CREATE OR REPLACE FUNCTION public.get_wallpapers(device_hw_id text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS TABLE(url text, type text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    hw text;
begin
    -- An already-registered device that now reports a VIN keeps its row
    -- (activation, wallpapers, hides, block status) under the new id.
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    -- ...and an app that has not learned the new id yet is still asking about the
    -- same car. See device_id_aliases.
    hw := public.resolve_device_id(device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        join public.devices d on d.hardware_id = hw
        where w.channel = 'app'
          and (w.is_global = true or w.hardware_id = hw)
          and (w.target_mode is null or w.target_mode = d.mode)
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = hw
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- Serial activation. Returns 'success' | 'blocked' | 'invalid_format' | 'serial_already_used'.
-- Called by all three programs. Stamps activated_at since 2026-07-29.
--
-- !!! SUPERSEDED LIVE on 2026-08-02. The body below is the pure-Postgres logic,
-- kept here because it is still the disaster-recovery definition and is still
-- what runs whenever the kill switch is off. In production this function is now
-- a dispatcher that asks Cloudflare D1 for the decision and falls back to this
-- exact body (as cf.activate_device_legacy) when cf_activation_write_enabled is
-- false. See supabase/migrations/20260802_cf_activation_*.sql for the live
-- definition; the request/response contract is identical either way.
CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    existing_hw_id text;
begin
    -- Direction guard (2026-09-03): same rule as migrate_device_hardware_id. The live
    -- activate_device is the CF dispatcher (20260802_cf_activation_*.sql) and carries it too.
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id
       and device_hw_id like 'VIN-%'
       and (legacy_hw_id not like 'VIN-%' or upper(legacy_hw_id) = upper(device_hw_id)) then
        if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
           and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
            update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
        end if;
    end if;

    if exists (select 1 from public.devices where hardware_id = device_hw_id and is_blocked = true) then
        return 'blocked';
    end if;

    if activation_serial not like '7078%' and activation_serial not like '578%' then
        return 'invalid_format';
    end if;

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    if existing_hw_id is not null and existing_hw_id <> device_hw_id then
        return 'serial_already_used';
    end if;

    insert into public.devices (hardware_id, serial_number, is_active, activated_at)
    values (device_hw_id, activation_serial, true, now())
    on conflict (hardware_id)
    do update set serial_number = excluded.serial_number,
                  is_active     = true,
                  activated_at  = now();

    return 'success';
end;
$function$;

-- Internal helper: move a device's row (activation + wallpapers + hides) from an
-- old hardware id to a new one. INTERNAL ONLY — called via `perform` from the
-- other RPCs. EXECUTE is revoked from anon/authenticated (see bottom of this
-- section): exposing it over REST let anyone who knew a victim's hardware id
-- (the car VIN) hijack that device's activation into an id they controlled.
--
-- The early-return guards below are also a load-bearing PERFORMANCE property:
-- report_device_mode calls this on every sync (every launch + every 5 minutes,
-- fleet-wide), and for an already-migrated car all three guards are pure local
-- primary-key lookups that return before doing any work.
CREATE OR REPLACE FUNCTION public.migrate_device_hardware_id(old_id text, new_id text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    if old_id is null or old_id = '' or new_id is null or new_id = '' or old_id = new_id then
        return;
    end if;
    -- Direction guard (2026-09-03, migrations/20260903_migration_direction_guard.sql):
    -- only legacy -> VIN, or a case-only VIN rename. A caller-named VIN- source is refused.
    if new_id not like 'VIN-%' then
        return;
    end if;
    if old_id like 'VIN-%' and upper(old_id) <> upper(new_id) then
        return;
    end if;
    -- Only ever migrate INTO an id that does not exist yet, so two devices can never be merged.
    if exists (select 1 from public.devices d where d.hardware_id = new_id)
       or not exists (select 1 from public.devices d where d.hardware_id = old_id) then
        return;
    end if;
    update public.devices        set hardware_id = new_id where hardware_id = old_id;
    update public.wallpapers     set hardware_id = new_id where hardware_id = old_id;
    update public.wallpaper_hides set hardware_id = new_id where hardware_id = old_id;
    -- The forwarding address, in the same transaction as the rename it describes. See
    -- device_id_aliases: the OTHER two apps keep asking under the id they were activated
    -- with, and one of them can never be updated to learn the new one.
    --
    -- Delete-then-insert rather than ON CONFLICT, and the alias `a` is not decoration: this
    -- function's parameters are called old_id and new_id, which are also column names here.
    -- plpgsql resolves a bare `old_id` to the PARAMETER, so `on conflict (old_id)` is an
    -- outright error and `where old_id = old_id` would silently be `where 'X' = 'X'` and
    -- empty the table. Every reference below therefore says which one it means.
    delete from public.device_id_aliases a
     where a.old_id = migrate_device_hardware_id.old_id;
    insert into public.device_id_aliases (old_id, current_id)
    values (migrate_device_hardware_id.old_id, migrate_device_hardware_id.new_id);
end;
$function$;

-- GWM-split channel playlist (companion program). Same activation gate as
-- get_wallpapers; filters wallpapers to channel='gwm_split'.
CREATE OR REPLACE FUNCTION public.get_gwm_wallpapers(device_hw_id text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS TABLE(url text, type text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    hw text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    -- ...and an app that has not learned the new id yet is still asking about the
    -- same car. See device_id_aliases.
    hw := public.resolve_device_id(device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.channel = 'gwm_split'
          and (w.is_global = true or w.hardware_id = hw)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- Jetour G700 channel playlist. The same function as get_gwm_wallpapers over a
-- different channel: same activation gate, same alias resolution, and the same
-- 'inactive' sentinel for a car that is not activated. One RPC per channel is
-- what keeps a GWM image from ever landing in a Jetour folder.
CREATE OR REPLACE FUNCTION public.get_jetour_wallpapers(device_hw_id text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS TABLE(url text, type text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    hw text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    hw := public.resolve_device_id(device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.channel = 'jetour_g700'
          and (w.is_global = true or w.hardware_id = hw)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- SECURITY: migrate_device_hardware_id is internal-only. Revoke direct REST
-- access so it cannot be used to hijack a device by its (guessable) VIN. The
-- `perform` calls inside the other RPCs still work because those functions are
-- SECURITY DEFINER (the inner call runs as the owner, not the caller).
revoke execute on function public.migrate_device_hardware_id(text, text) from anon;
revoke execute on function public.migrate_device_hardware_id(text, text) from authenticated;
revoke execute on function public.migrate_device_hardware_id(text, text) from public;

-- Cross-program activation check: the OTHER programs on the device call this
-- with the same hardware id to inherit the activation done in any one of them.
-- See the section header: this one must never 404.
CREATE OR REPLACE FUNCTION public.is_device_activated(device_hw_id text)
 RETURNS boolean
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select exists (
    select 1
    from public.devices d
    where d.hardware_id = public.resolve_device_id(is_device_activated.device_hw_id)
      and d.is_active = true
      and d.is_blocked = false
  );
$function$;

-- Operating-mode + version report: the app calls this on every sync so the
-- manager can see each car's mode and which build it runs. UPDATE-only (never
-- creates a device row); unknown device id is a silent no-op, and an unknown
-- mode keeps the previous mode instead of nulling it.
-- The 3-arg version was DROPPED (not overloaded) when the two version columns
-- were added, so old APKs sending 3 args still bind here via the defaults.
CREATE OR REPLACE FUNCTION public.report_device_mode(device_hw_id text, device_mode text, legacy_hw_id text DEFAULT NULL::text, app_version text DEFAULT NULL::text, app_version_code integer DEFAULT NULL::integer)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    update public.devices
       set mode             = case
                                when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;

-- Crash upload from the wallpapers app. Keeps the newest 50 reports per car.
-- Silent no-op on empty input; the client treats HTTP 404 as "sent" too, so an
-- older backend without this RPC does not spam retries.
CREATE OR REPLACE FUNCTION public.report_crash(device_hw_id text, crash_text text, app_version text DEFAULT NULL::text, app_version_code integer DEFAULT NULL::integer, device_mode text DEFAULT NULL::text, crash_at text DEFAULT NULL::text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    if device_hw_id is null or crash_text is null or length(trim(crash_text)) = 0 then
        return;
    end if;

    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    insert into public.device_crashes (
        hardware_id, app_version, version_code, device_mode,
        crash_at, crash_text
    ) values (
        device_hw_id, app_version, app_version_code, device_mode,
        crash_at, left(crash_text, 200000)
    );

    delete from public.device_crashes dc
     where dc.hardware_id = device_hw_id
       and dc.id not in (
            select d2.id from public.device_crashes d2
             where d2.hardware_id = device_hw_id
             order by d2.received_at desc
             limit 50
       );
end;
$function$;

-- Store-app check-in: upserts the install row, refreshes last_seen, returns
-- whether this install is blocked. NOTE: this blocking is store-specific
-- (store_installs.blocked) and is INDEPENDENT of devices.is_blocked.
CREATE OR REPLACE FUNCTION public.store_check_in(p_hw_id text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_store_blocked  boolean;
    v_device_blocked boolean;
begin
    if p_hw_id is null or length(p_hw_id) = 0 then return false; end if;

    insert into public.store_installs (hw_id, car, version)
         values (p_hw_id, nullif(p_car,''), nullif(p_version,''))
    on conflict (hw_id) do update
         set last_seen = now(),
             car       = coalesce(nullif(excluded.car,''),     public.store_installs.car),
             version   = coalesce(nullif(excluded.version,''), public.store_installs.version)
    returning blocked into v_store_blocked;

    -- One block list for the whole car (2026-09-03): the store also honours
    -- devices.is_blocked, which the wallpapers dashboard and D1 write. Looked
    -- up through resolve_device_id so a pre-VIN check-in id still matches.
    select exists (
        select 1
          from public.devices d
         where d.hardware_id = public.resolve_device_id(p_hw_id)
           and d.is_blocked = true
    ) into v_device_blocked;

    return coalesce(v_store_blocked, false) or coalesce(v_device_blocked, false);
end
$function$;

-- Crash upload from the store app (separate table from device_crashes).
CREATE OR REPLACE FUNCTION public.store_report_crash(p_report text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text, p_device text DEFAULT NULL::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if p_report is null or length(p_report) = 0 then return; end if;
  insert into public.store_crashes (car, version, device, report)
       values (nullif(p_car,''), nullif(p_version,''), nullif(p_device,''), left(p_report, 20000));
end $function$;

-- Store admin RPCs, gated by a shared secret (the secret value lives ONLY in
-- the live database function — the placeholder below must be replaced with the
-- real one when rebuilding; never commit the real secret to git).
--
-- KNOWN QUIRK (live, kept as-is so this file matches reality): the last output
-- column is named activated_at but the body selects d.created_at into it.
CREATE OR REPLACE FUNCTION public.store_admin_list(p_secret text)
 RETURNS TABLE(hw_id text, car text, version text, blocked boolean, first_seen timestamp with time zone, last_seen timestamp with time zone, serial_number text, is_active boolean, client_name text, activated_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if p_secret is distinct from 'REPLACE_WITH_REAL_STORE_ADMIN_SECRET' then
    raise exception 'unauthorized';
  end if;
  begin
    return query
      select i.hw_id, i.car, i.version, i.blocked, i.first_seen, i.last_seen,
             d.serial_number, d.is_active, d.client_name, d.created_at
        from public.store_installs i
        left join public.devices d on d.hardware_id = i.hw_id
       order by i.last_seen desc;
  exception when undefined_table or undefined_column then
    return query
      select i.hw_id, i.car, i.version, i.blocked, i.first_seen, i.last_seen,
             null::text, null::boolean, null::text, null::timestamptz
        from public.store_installs i
       order by i.last_seen desc;
  end;
end $function$;

-- Blocks/unblocks a STORE install only (store_installs.blocked).
-- It does NOT touch devices.is_blocked — the wallpapers activation gate is
-- unaffected by this call.
CREATE OR REPLACE FUNCTION public.store_admin_set_blocked(p_secret text, p_hw_id text, p_blocked boolean)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if p_secret is distinct from 'REPLACE_WITH_REAL_STORE_ADMIN_SECRET' then
    raise exception 'unauthorized';
  end if;
  update public.store_installs set blocked = p_blocked where hw_id = p_hw_id;
end $function$;
