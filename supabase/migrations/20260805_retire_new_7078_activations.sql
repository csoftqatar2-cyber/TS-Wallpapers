-- Retire NEW activations on the legacy '7078' prefix. The ~530 codes already in
-- the field must keep re-activating successfully (their serial_number already
-- exists in public.devices), but an unused 7078 code must no longer activate a
-- car for the first time. '578...' codes are unaffected — they were always the
-- "new" prefix.
--
-- Mirrors the equivalent change in cloudflare/activation-worker/worker.js
-- (handleActivate), which is the live decision authority when
-- cf_activation_write_enabled is on. This function is the kill-switch fallback
-- for when that flag is off, so it must match exactly.

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

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    -- '578' is always valid. '7078' is valid only when the serial is already on
    -- file (an existing car re-activating) — a never-seen 7078 code no longer
    -- activates anything new.
    if not (
        activation_serial like '578%'
        or (activation_serial like '7078%' and existing_hw_id is not null)
    ) then
        return 'invalid_format';
    end if;

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
