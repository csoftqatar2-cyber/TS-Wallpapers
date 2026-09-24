-- ============================================================================
-- HAVAL V7 operating mode — the GWM Haval head unit (SA8155, Android 11).
--
-- Applied live 2026-09-23 as 20260923_havalv7_mode. Verified after apply:
-- report_device_mode still has exactly ONE signature (text,text,text,text,integer,text),
-- and all three CHECK constraints list havalv7.
--
-- Same backend shape as 20260918_icar03t_mode.sql: one new wire value the fleet
-- can report and the library can target. Nothing here touches an existing row.
--
-- ⚠️ THE SIGNATURE IS SIX ARGS, NOT FIVE. 20260922_mode_secondary_display.sql
-- added `display_role text default 'primary'` and a `mode_secondary` branch, so
-- the live function is
--     report_device_mode(text,text,text,text,integer,text)
-- and it was verified with pg_get_functiondef before this file was written. A
-- `create or replace` with the old five-arg list would NOT replace it — it would
-- create an overload, and a five-arg call from a fielded APK would then match
-- both and Postgres would answer "function is not unique", breaking mode
-- reporting on every car at once. THREE mode lists must be widened below: `mode`,
-- `mode_secondary`, and `wallpapers.target_mode`.
--
-- The car half is a THIRD kind of hand-off, different from both existing ones,
-- measured on a real unit (GWM/HAVAL, sa8155_cux_v35_b26g-1, Android 11) before
-- writing this:
--
--   * Leopard/Denza hand ONE file to Android's WallpaperManager.
--   * Lynk & Co hands ONE file to the Flyme theme app.
--   * Haval takes a LIST. Its launcher (com.gwm.app.launcher, priv-app) reads
--     Settings.Global 'home_wallpaper_list_data_central' — a Gson-parsed JSON
--     {"dataSource":0,"bean":[{"path":…,"preview":"","type":0,"isAlive":…}]} —
--     registers a ContentObserver on it, and renders the list itself in a pager
--     the driver swipes. dataSource 0 is the vendor's own "wallpaper store"
--     source; 1 means the launcher wrote it, and the launcher ignores its own
--     writes. The sibling key 'home_wallpaper_central_change' is the OUTPUT (the
--     path currently showing) — writing it alone does nothing unless that path
--     is already in the list, which is what made the first bench attempt look
--     like a dead end.
--
-- So on this car the driver, not the app, flips between wallpapers. Our job is
-- to put the chosen files somewhere the launcher can read (/sdcard works — the
-- launcher opened files there that were mode 600 and owned by another uid) and
-- to write that one settings key. Writing it needs WRITE_SECURE_SETTINGS,
-- granted once per car with `adb shell pm grant`, exactly as ذبذبة ستور already
-- is on these units.
--
-- ORDER: apply this BEFORE the app that reports 'havalv7' ships. Backwards, the
-- update case in report_device_mode silently drops the value and those cars stop
-- reporting a mode at all.
-- ============================================================================

alter table public.devices drop constraint if exists devices_mode_check;
alter table public.devices
    add constraint devices_mode_check
        check (mode is null or mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7'));

alter table public.devices drop constraint if exists devices_mode_secondary_check;
alter table public.devices
    add constraint devices_mode_secondary_check
        check (mode_secondary is null or mode_secondary in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7'));

alter table public.wallpapers drop constraint if exists wallpapers_target_mode_check;
alter table public.wallpapers
    add constraint wallpapers_target_mode_check
        check (target_mode is null or target_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7'));

-- Byte-for-byte the live body with 'havalv7' added to both mode lists, and the
-- SAME six-arg signature. Never add an overload — see the header.
create or replace function public.report_device_mode(
    device_hw_id     text,
    device_mode      text,
    legacy_hw_id     text default null,
    app_version      text default null,
    app_version_code int  default null,
    display_role     text default 'primary'
)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    if display_role = 'secondary' then
        update public.devices
           set mode_secondary   = case
                                    when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7')
                                    then device_mode else mode_secondary end,
               secondary_seen_at = now(),
               app_version      = coalesce(report_device_mode.app_version, devices.app_version),
               app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
               last_seen_at     = now()
         where hardware_id = device_hw_id;
        return;
    end if;

    update public.devices
       set mode             = case
                                when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;
