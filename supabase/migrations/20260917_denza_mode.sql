-- ============================================================================
-- Denza operating mode — split out of "Leopard \ Denza".
--
-- Until now Denza head units ran under the 'leopard' wire value (same hand-off
-- behaviour: the app draws nothing, hands the file to Android's WallpaperManager).
-- The owner wants the two products separated so they can diverge later. Today the
-- app behaviour is identical; only the identity differs.
--
-- Nothing here touches an existing row: cars keep reporting 'leopard' until a
-- person re-picks Denza on the car, and get_wallpapers keeps its exact-match rule
-- (a 'denza' car receives untargeted rows + rows targeted 'denza').
--
-- Applied live 2026-09-05.
-- ============================================================================

alter table public.devices drop constraint if exists devices_mode_check;
alter table public.devices
    add constraint devices_mode_check
        check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza'));

alter table public.wallpapers drop constraint if exists wallpapers_target_mode_check;
alter table public.wallpapers
    add constraint wallpapers_target_mode_check
        check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza'));

-- Same 5-arg signature as live (never add an overload — see ts-backend-rpc-change).
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
                                when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;
