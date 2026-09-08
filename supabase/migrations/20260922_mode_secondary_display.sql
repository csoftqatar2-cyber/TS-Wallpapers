-- ============================================================================
-- Two screens, one car, one hardware id (BYD DiLink: the passenger "FSE" strip
-- runs the app as Android user 999). Both instances called report_device_mode
-- and overwrote the single `mode` column — last writer wins — so the owner was
-- forced to keep both screens in one mode just to make the site (and the
-- Leopard-dash channel filter) read the car right.
--
-- From app 7.17/193 the secondary instance sends display_role = 'secondary'.
-- Its mode lands in `mode_secondary`; `mode` stays the MAIN screen's mode and is
-- what every filter and target_mode comparison keeps using. Old APKs send no
-- display_role → 'primary' → unchanged behaviour.
--
-- Signature rule (ts-backend-rpc-change): the 5-arg function is DROPPED and the
-- 6-arg one (all defaults) created in the same migration — never two overloads.
-- Applied live 2026-09-08.
-- ============================================================================
alter table public.devices add column if not exists mode_secondary text
  check (mode_secondary is null or mode_secondary in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t'));
alter table public.devices add column if not exists secondary_seen_at timestamptz;

drop function if exists public.report_device_mode(text, text, text, text, integer);

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
                                    when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t')
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
                                when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;

revoke all on function public.report_device_mode(text, text, text, text, integer, text) from public;
grant execute on function public.report_device_mode(text, text, text, text, integer, text) to anon, authenticated;
