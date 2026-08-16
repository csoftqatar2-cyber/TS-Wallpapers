-- Applied live as 20260816_get_device_status_rpc
--
-- A car that finds itself deactivated cannot tell WHY: get_wallpapers answers with the same
-- 'inactive' sentinel whether the device was never registered or has just been blocked. The
-- two need completely different screens — one asks for a serial, the other has to say plainly
-- that no serial will ever work — so the app needs to be able to ask.
--
-- A NEW function rather than a change to any existing one: the RPC contract the three programs
-- share is frozen (two of them cannot be updated in the field), and widening an existing answer
-- would reach clients that would mis-read it. Nothing is obliged to call this; the app that
-- does, gains the distinction. See FsClockView.refreshBlockedState().
--
-- Read-only on purpose. get_wallpapers migrates a car's identity as a side effect of being
-- asked; a status probe must not, so this only reads, checking the legacy id too rather than
-- renaming anything.
create or replace function public.get_device_status(device_hw_id text, legacy_hw_id text default null)
returns text
language plpgsql
security definer
set search_path to 'public'
as $fn$
declare
    d public.devices;
begin
    select * into d from public.devices
     where hardware_id = public.resolve_device_id(device_hw_id);

    if not found and legacy_hw_id is not null and legacy_hw_id <> '' then
        select * into d from public.devices
         where hardware_id = public.resolve_device_id(legacy_hw_id);
    end if;

    if not found      then return 'unknown';  end if;  -- never registered
    if d.is_blocked   then return 'blocked';  end if;  -- blocked: no serial will help
    if d.is_active    then return 'active';   end if;
    return 'inactive';                                  -- registered, activation withdrawn
end;
$fn$;

revoke all on function public.get_device_status(text, text) from public;
grant execute on function public.get_device_status(text, text) to anon, authenticated;
