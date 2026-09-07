-- ============================================================================
-- ICAR 03T operating mode — the Chery iCAR 03T head unit.
--
-- Applied live 2026-09-07. Verified: both CHECK constraints list icar03t, and
-- report_device_mode still has exactly one signature (text,text,text,text,integer).
--
-- Same shape as 20260917_denza_mode.sql, but NOT the same behaviour: this car's
-- launcher paints its own carousel over the system wallpaper, so the app writes
-- the picture into a folder the launcher reads instead of handing it to Android's
-- WallpaperManager. See ICAR-03T.md. The backend half is identical either way —
-- a new wire value the fleet can report and the library can target.
--
-- Measured on the car (VIN-less bench unit, MENGBO S56_HQX, Android 9) before
-- writing this: the launcher window com.mengbo.launcher3/.ui.NewMainActivity
-- carries FLAG_SHOW_WALLPAPER, the unit declares FEATURE_LIVE_WALLPAPER, and
-- AOSP's com.android.wallpaper.livepicker is installed and lists our engine. So
-- Android's stock wallpaper system is the whole mechanism — no vendor app is
-- involved, unlike Lynk & Co.
--
-- Nothing here touches an existing row. Cars only start reporting 'icar03t' once
-- a person picks the mode on a build that knows it, and get_wallpapers keeps its
-- exact-match rule (an 'icar03t' car receives untargeted rows + rows targeted
-- 'icar03t').
--
-- ORDER: apply this BEFORE the app that reports 'icar03t' ships. Backwards, the
-- update case in report_device_mode silently drops the value and those cars stop
-- reporting a mode at all.
-- ============================================================================

alter table public.devices drop constraint if exists devices_mode_check;
alter table public.devices
    add constraint devices_mode_check
        check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t'));

alter table public.wallpapers drop constraint if exists wallpapers_target_mode_check;
alter table public.wallpapers
    add constraint wallpapers_target_mode_check
        check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t'));

-- Same 5-arg signature as live. NEVER add an overload: a 3-arg call from a
-- pre-3.5 APK would match both and Postgres answers "function is not unique",
-- which breaks mode reporting on every car in the field at once.
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
                                when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;
