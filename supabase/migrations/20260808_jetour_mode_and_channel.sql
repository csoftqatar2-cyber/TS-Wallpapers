-- ============================================================================
-- Jetour operating mode + the 'jetour_g700' delivery channel.
--
-- Jetour is Others with one addition, exactly like GWM: the car runs our own
-- slideshow AND mirrors one cloud channel into a folder another app on the head
-- unit reads — /sdcard/Pictures/G700 for the Jetour G700.
--
-- Four things, none of which touch an existing row:
--   1. Allow 'jetour' wherever a device mode is accepted (devices.mode and the
--      report_device_mode RPC), or a Jetour car's report is silently dropped and
--      the manager keeps showing it under whatever it was before.
--   2. Allow 'jetour' as a wallpapers.target_mode, so the app channel can be
--      aimed at Jetour cars the same way it can at Lynk & Co ones.
--   3. get_jetour_wallpapers: the mirror manifest RPC, a twin of
--      get_gwm_wallpapers over channel='jetour_g700'.
--   4. wallpapers.channel needs nothing — it is a plain text column with no
--      check constraint, so the new value is accepted as-is. The gate that keeps
--      channels apart is the per-channel RPC, not the column.
-- ============================================================================

-- 1. devices.mode: add 'jetour' to the allowed set.
alter table public.devices drop constraint if exists devices_mode_check;
alter table public.devices
    add constraint devices_mode_check
        check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco','jetour'));

-- 2. wallpapers.target_mode: same addition. The constraint was created inline
--    and is auto-named 'wallpapers_target_mode_check'.
alter table public.wallpapers drop constraint if exists wallpapers_target_mode_check;
alter table public.wallpapers
    add constraint wallpapers_target_mode_check
        check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco','jetour'));

-- 3. report_device_mode: accept 'jetour'. Body is otherwise byte-identical to
--    the version in schema.sql (telemetry columns included) — only the allowed
--    mode list changes.
create or replace function public.report_device_mode(
    device_hw_id     text,
    device_mode      text,
    legacy_hw_id     text default null,
    app_version      text default null,
    app_version_code int  default null
)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
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

-- 4. Jetour G700 channel playlist (companion app on the head unit). Same
--    activation gate as get_wallpapers / get_gwm_wallpapers; filters wallpapers
--    to channel='jetour_g700'. An unactivated or blocked car gets the single
--    'inactive' sentinel and therefore writes nothing into the shared folder.
create or replace function public.get_jetour_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null
)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
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
        where w.channel = 'jetour_g700'
          and (w.is_global = true or w.hardware_id = hw)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;
