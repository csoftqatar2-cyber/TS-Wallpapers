-- ============================================================================
-- TS Wallpapers — FULL Supabase backend snapshot (project ihgmqwzdpugdzddobhbc)
-- Captured from the LIVE project on 2026-07-11.
--
-- PURPOSE: disaster-recovery / documentation. This file recreates the whole
-- backend on an empty project. It is idempotent-ish (if not exists / or
-- replace / drop policy if exists) but review before running against a
-- project that already has data.
--
-- NOT included here (create manually):
--   * Auth user admin@tswallpapers.app (Supabase Auth -> Add user). Its user
--     id is referenced by the RLS policies and the admin-upload Edge Function
--     (ADMIN_ID). On a NEW project, replace the UUID below everywhere.
--   * Edge Function admin-upload (source: supabase/functions/admin-upload/).
--   * The two secrets the Edge Function needs are provided automatically by
--     the platform (SUPABASE_URL / ANON_KEY / SERVICE_ROLE_KEY).
-- ============================================================================

-- The single admin identity used by all admin policies:
--   auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'  (admin@tswallpapers.app)

-- ----------------------------------------------------------------------------
-- 1. TABLES
-- ----------------------------------------------------------------------------

-- Registered devices (cars). One row per hardware id; activation state lives here.
create table if not exists public.devices (
    hardware_id   text primary key,
    serial_number text unique check (serial_number like '7078%'),
    client_name   text,
    is_active     boolean default false,
    created_at    timestamptz not null default timezone('utc', now()),
    is_blocked    boolean not null default false
);

-- Wallpaper catalog. is_global=true rows go to EVERY activated device;
-- otherwise the row belongs to exactly one device (hardware_id).
create table if not exists public.wallpapers (
    id          bigint generated always as identity primary key,
    url         text not null unique,
    type        text not null,              -- 'image' | 'video'
    is_global   boolean default false,
    hardware_id text references public.devices(hardware_id),
    created_at  timestamptz not null default timezone('utc', now())
);

-- Published app builds; devices poll this to self-update.
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

-- Store-app install tracking (the "ذبذبة ستور" companion program checks in here).
create table if not exists public.store_installs (
    hw_id      text primary key,
    car        text,
    version    text,
    blocked    boolean not null default false,
    first_seen timestamptz not null default now(),
    last_seen  timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- 2. ROW LEVEL SECURITY
--    Devices are ANONYMOUS: they never touch tables directly, only the
--    SECURITY DEFINER RPCs below. Tables are admin-only (or public-read where
--    devices need it). store_installs intentionally has RLS enabled with NO
--    policies: only its RPCs can reach it.
-- ----------------------------------------------------------------------------

alter table public.devices        enable row level security;
alter table public.wallpapers     enable row level security;
alter table public.app_versions   enable row level security;
alter table public.store_installs enable row level security;

-- devices: admin-only (manager dashboard)
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

-- ----------------------------------------------------------------------------
-- 3. STORAGE
--    Both buckets are PUBLIC: devices download via
--    /storage/v1/object/public/<bucket>/<name>, which needs no policy.
--    Object writes (and listing, after the hardening migration) are admin-only.
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
--    APK. Never rename them or change response values.
-- ----------------------------------------------------------------------------

-- Device playlist. Activated+unblocked device -> its private rows + all global
-- rows. Anything else -> single sentinel row ('inactive','image'), which the
-- app understands as "not activated" (clears playlist, shows activation UI).
-- ANY app on the same device (same hardware id) gets the same answer — this is
-- why activating one of the programs activates all of them.
CREATE OR REPLACE FUNCTION public.get_wallpapers(device_hw_id text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS TABLE(url text, type text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    -- One-time in-place migration: an already-registered device that now reports a
    -- VIN keeps its row (activation, wallpapers, block status) under the new id.
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id then
        if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
           and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
            update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
        end if;
    end if;

    if exists (
        select 1 from public.devices d
        where d.hardware_id = device_hw_id and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.is_global = true or w.hardware_id = device_hw_id
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- Serial activation. Returns 'success' | 'blocked' | 'invalid_format' | 'serial_already_used'.
CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    existing_hw_id text;
begin
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id then
        if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
           and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
            update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
        end if;
    end if;

    if exists (select 1 from public.devices where hardware_id = device_hw_id and is_blocked = true) then
        return 'blocked';
    end if;

    if activation_serial not like '7078%' then
        return 'invalid_format';
    end if;

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    if existing_hw_id is not null and existing_hw_id <> device_hw_id then
        return 'serial_already_used';
    end if;

    insert into public.devices (hardware_id, serial_number, is_active)
    values (device_hw_id, activation_serial, true)
    on conflict (hardware_id)
    do update set serial_number = excluded.serial_number, is_active = true;

    return 'success';
end;
$function$;

-- Cross-program activation check: the OTHER programs on the device call this
-- with the same hardware id to inherit the activation done in any one of them.
CREATE OR REPLACE FUNCTION public.is_device_activated(device_hw_id text)
 RETURNS boolean
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select exists (
    select 1
    from public.devices d
    where d.hardware_id = is_device_activated.device_hw_id
      and d.is_active = true
      and d.is_blocked = false
  );
$function$;

-- Store-app check-in: upserts the install row, refreshes last_seen, returns
-- whether this install is blocked.
CREATE OR REPLACE FUNCTION public.store_check_in(p_hw_id text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v_blocked boolean;
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return false; end if;
  insert into public.store_installs (hw_id, car, version)
       values (p_hw_id, nullif(p_car,''), nullif(p_version,''))
  on conflict (hw_id) do update
       set last_seen = now(),
           car       = coalesce(nullif(excluded.car,''),     public.store_installs.car),
           version   = coalesce(nullif(excluded.version,''), public.store_installs.version)
  returning blocked into v_blocked;
  return coalesce(v_blocked, false);
end $function$;

-- Store admin RPCs, gated by a shared secret (the secret value lives ONLY in
-- the live database function — the placeholder below must be replaced with the
-- real one when rebuilding; never commit the real secret to git).
CREATE OR REPLACE FUNCTION public.store_admin_list(p_secret text)
 RETURNS TABLE(hw_id text, car text, version text, blocked boolean, first_seen timestamptz, last_seen timestamptz, serial_number text, is_active boolean, client_name text)
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
             d.serial_number, d.is_active, d.client_name
        from public.store_installs i
        left join public.devices d on d.hardware_id = i.hw_id
       order by i.last_seen desc;
  exception when undefined_table or undefined_column then
    return query
      select i.hw_id, i.car, i.version, i.blocked, i.first_seen, i.last_seen,
             null::text, null::boolean, null::text
        from public.store_installs i
       order by i.last_seen desc;
  end;
end $function$;

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
