-- ============================================================================
-- Device operating mode reporting.
--
-- Each install runs in exactly one of four mutually-exclusive modes — normal,
-- fse, leopard, gwm — but until now that was a purely LOCAL flag on the device
-- (SharedPreferences); the backend had no idea which car ran which mode.
--
-- This migration:
--   1. Adds devices.mode so the manager can SEE each car's mode.
--   2. Adds report_device_mode(), the anonymous device-facing RPC the app calls
--      on every sync to keep that column current.
-- ============================================================================

-- 1. Column. Nullable: null = an older APK that does not report yet / never synced.
alter table public.devices
    add column if not exists mode text
        check (mode is null or mode in ('normal','fse','leopard','gwm'));

-- 2. Device-facing RPC — anonymous, SECURITY DEFINER, same shape as the other
--    device RPCs. It only ever UPDATES an existing (registered) device row; it
--    never creates one, because activation is the single thing allowed to do
--    that. An unknown device id or an unknown/garbage mode is a silent no-op, so
--    a stale or hostile caller can neither error the sync nor corrupt the column.
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
    -- Same VIN-migration courtesy the other device RPCs give: a car that just
    -- started reporting its VIN still lands on its existing row. The inner
    -- function has EXECUTE revoked from anon, but this SECURITY DEFINER wrapper
    -- runs as the owner, exactly like get_gwm_wallpapers does.
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    if device_mode is null or device_mode not in ('normal','fse','leopard','gwm') then
        return;
    end if;

    update public.devices
       set mode = device_mode
     where hardware_id = device_hw_id;
end;
$function$;
