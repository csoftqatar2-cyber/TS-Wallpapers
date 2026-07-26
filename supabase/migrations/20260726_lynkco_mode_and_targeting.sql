-- ============================================================================
-- Lynk & Co operating mode + mode-targeted wallpapers.
--
-- Builds on 20260725_device_operating_mode.sql. Two things:
--   1. Allow the new 'lynkco' value everywhere a device mode is accepted
--      (the devices.mode column and the report_device_mode RPC).
--   2. Let a wallpaper be aimed at cars running one particular mode, so the
--      manager can push images/videos to "Lynk & Co cars only" — while the
--      global library still syncs to every car exactly as before.
-- ============================================================================

-- 1. devices.mode: add 'lynkco' to the allowed set. The column's inline check
--    was created unnamed, which Postgres auto-names 'devices_mode_check'.
alter table public.devices drop constraint if exists devices_mode_check;
alter table public.devices
    add constraint devices_mode_check
        check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco'));

-- 2. report_device_mode: accept 'lynkco' (otherwise a Lynk & Co car's report is
--    silently dropped and the manager never sees the mode). Body is otherwise
--    identical to the original.
create or replace function public.report_device_mode(
    device_hw_id text,
    device_mode  text,
    legacy_hw_id text default null
)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    if device_mode is null or device_mode not in ('normal','fse','leopard','gwm','lynkco') then
        return;
    end if;

    update public.devices
       set mode = device_mode
     where hardware_id = device_hw_id;
end;
$function$;

-- 3. Mode-targeted wallpapers. Nullable: null = the normal, everyone-gets-it
--    library (unchanged behaviour). When set, the wallpaper only reaches cars
--    whose LAST REPORTED mode matches — e.g. target_mode='lynkco' + is_global
--    means "every Lynk & Co car". A car that has not reported a mode yet (null)
--    matches nothing targeted, which is correct: we only target what we can see.
alter table public.wallpapers
    add column if not exists target_mode text
        check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco'));

-- 4. get_wallpapers: same activation gate and VIN-migration courtesy as before,
--    but a targeted wallpaper is now filtered against the device's own mode. The
--    join is safe: we are already inside the branch that proved the device row
--    exists (is_active = true).
create or replace function public.get_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null
)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    -- One-time in-place migration: an already-registered device that now reports
    -- a VIN keeps its row (activation, wallpapers, block status) under the new id.
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
        join public.devices d on d.hardware_id = device_hw_id
        where (w.is_global = true or w.hardware_id = device_hw_id)
          and (w.target_mode is null or w.target_mode = d.mode)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;
