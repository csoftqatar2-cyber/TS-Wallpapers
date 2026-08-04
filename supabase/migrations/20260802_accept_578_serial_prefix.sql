-- Applied live as 20260802173958_accept_578_serial_prefix
--
-- New activation codes are issued as 578001, 578002, ... The ~530 codes already
-- in the field all start with 7078 and must keep working forever, so this widens
-- the rule rather than replacing it.
--
-- Note the existing serials vary in length (5 to 14 characters), so only the
-- prefix is constrained here, exactly as before.
--
-- Where else this prefix lives, all three kept in step:
--   * cloudflare/activation-worker/worker.js  -> SERIAL_PREFIXES (the authority)
--   * cf.activate_device_legacy               -> below, the kill-switch fallback
--   * WallpaperRepo.SERIAL_PREFIXES (Android) -> UX-only pre-check
-- TS Back Button and ذبذبة ستور do no client-side prefix check at all, so the
-- new codes work there the moment this lands — no app update needed.

alter table public.devices drop constraint if exists devices_serial_number_check;
alter table public.devices add constraint devices_serial_number_check
    check (serial_number like '7078%' or serial_number like '578%');

-- Keep the rollback path in step: if the kill switch is ever flipped, activation
-- falls back to this function, and it must accept the new codes too or issuing
-- 578 codes would silently start failing the moment we rolled back.
create or replace function cf.activate_device_legacy(
    device_hw_id text, activation_serial text, legacy_hw_id text default null
) returns text
language plpgsql security definer set search_path to 'public'
as $function$
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

    -- 7078 = the codes already in the field; 578 = everything issued from now on.
    if activation_serial not like '7078%' and activation_serial not like '578%' then
        return 'invalid_format';
    end if;

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    if existing_hw_id is not null and existing_hw_id <> device_hw_id then
        return 'serial_already_used';
    end if;

    insert into public.devices (hardware_id, serial_number, is_active, activated_at)
    values (device_hw_id, activation_serial, true, now())
    on conflict (hardware_id)
    do update set serial_number = excluded.serial_number,
                  is_active     = true,
                  activated_at  = now();

    return 'success';
end;
$function$;

revoke all on function cf.activate_device_legacy(text,text,text) from public, anon, authenticated;
