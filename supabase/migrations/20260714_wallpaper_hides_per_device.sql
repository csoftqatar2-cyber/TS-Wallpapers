-- ============================================================================
-- Per-device hiding of GLOBAL wallpapers (dashboard feature).
--
-- Problem: a wallpaper is either global (every activated car shows it) or private
-- to one hardware_id. There was no way to say "this global wallpaper, but not on
-- car X" — the only workaround was deleting it for everyone.
--
-- Solution: a small exception table. Each row = "wallpaper W is hidden on car H".
-- get_wallpapers subtracts those rows from the playlist it returns to that car.
-- Nothing else changes: a car with no rows here gets exactly the playlist it got
-- before, so installed devices are unaffected until the admin hides something.
--
-- The app ALSO has a local hide list (SharedPreferences "wallpaper-hidden-urls",
-- set from the device's own settings screen). The two are independent: this one is
-- controlled from the dashboard and follows the car even after a reinstall.
-- ============================================================================

create table if not exists public.wallpaper_hides (
    wallpaper_id bigint not null
        references public.wallpapers(id) on delete cascade,
    -- on update cascade: get_wallpapers rewrites devices.hardware_id when a car
    -- starts reporting its VIN, and the hide must follow the car
    hardware_id text not null
        references public.devices(hardware_id) on update cascade on delete cascade,
    created_at timestamptz not null default timezone('utc'::text, now()),
    primary key (wallpaper_id, hardware_id)
);

create index if not exists wallpaper_hides_hardware_id_idx
    on public.wallpaper_hides(hardware_id);

alter table public.wallpaper_hides enable row level security;

-- same admin-only lock as `wallpapers` / `devices`: only the dashboard user may
-- read or write. Devices never touch this table directly — get_wallpapers
-- (SECURITY DEFINER) reads it on their behalf.
drop policy if exists "wph admin read" on public.wallpaper_hides;
create policy "wph admin read" on public.wallpaper_hides
    for select to authenticated
    using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

drop policy if exists "wph admin insert" on public.wallpaper_hides;
create policy "wph admin insert" on public.wallpaper_hides
    for insert to authenticated
    with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

drop policy if exists "wph admin delete" on public.wallpaper_hides;
create policy "wph admin delete" on public.wallpaper_hides
    for delete to authenticated
    using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

grant select, insert, delete on public.wallpaper_hides to authenticated;
grant all on public.wallpaper_hides to service_role;

-- ---------------------------------------------------------------------------
-- get_wallpapers: identical to production, plus the "not hidden for this car"
-- filter on the playlist query.
-- ---------------------------------------------------------------------------
create or replace function public.get_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
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
        where (w.is_global = true or w.hardware_id = device_hw_id)
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = device_hw_id
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;
