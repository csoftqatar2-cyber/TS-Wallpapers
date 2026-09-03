-- Applied live as migration_direction_guard on 2026-09-03 (Supabase MCP apply_migration).
--
-- WHY
-- get_wallpapers, report_device_mode, activate_device and the legacy fallback all
-- accept a caller-supplied legacy_hw_id and rename that row to device_hw_id
-- (migrate_device_hardware_id, or an inline copy of it). The only legitimate
-- rename a fielded app ever asks for is  MAC-/SYS-/BOOT-/SRL-/AID-/UNKNOWN  ->  VIN-
-- (WallpaperRepo.getLegacyHardwareId, BackButton ActivationManager:423-437,
-- Store ActivationManager:558-572, TS Link CarIdentity). No app ever sends a
-- VIN- id as legacy_hw_id. But nothing on the server said so, and that is how
-- one anonymous POST with  legacy_hw_id = 'VIN-<a VIN read off a windscreen>'
-- could move a paying car's activation (and its private wallpapers) under an
-- attacker-chosen id and return the victim's playlist in the same response.
--
-- THE GUARD
--   * the target id must be a VIN- id;
--   * a VIN- source may only move to the SAME VIN spelled in a different case
--     (46 such case-only renames happened on 2026-09-03 and must keep working).
-- Everything else the guard refuses is something no fielded app can produce.
--
-- WHAT CANNOT BREAK
--   * A car that already migrated has an alias row and its new id exists, so
--     the "new_id exists" early return fires exactly as before.
--   * A car with no VIN sends device_hw_id == legacy_hw_id and returns on
--     old_id = new_id, as before.
--   * Signatures are byte-identical to the live functions: no overloads.
--
-- ROLLBACK: 20260903_migration_direction_guard_ROLLBACK.sql (the live bodies as
-- they stood before this file).

CREATE OR REPLACE FUNCTION public.migrate_device_hardware_id(old_id text, new_id text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    if old_id is null or old_id = '' or new_id is null or new_id = '' or old_id = new_id then
        return;
    end if;
    -- Direction guard (2026-09-03): only legacy -> VIN, or a case-only VIN rename.
    if new_id not like 'VIN-%' then
        return;
    end if;
    if old_id like 'VIN-%' and upper(old_id) <> upper(new_id) then
        return;
    end if;
    -- Only ever migrate INTO an id that does not exist yet, so two devices can never be merged.
    if exists (select 1 from public.devices d where d.hardware_id = new_id)
       or not exists (select 1 from public.devices d where d.hardware_id = old_id) then
        return;
    end if;
    update public.devices        set hardware_id = new_id where hardware_id = old_id;
    update public.wallpapers     set hardware_id = new_id where hardware_id = old_id;
    update public.wallpaper_hides set hardware_id = new_id where hardware_id = old_id;
    delete from public.device_id_aliases a
     where a.old_id = migrate_device_hardware_id.old_id;
    insert into public.device_id_aliases (old_id, current_id)
    values (migrate_device_hardware_id.old_id, migrate_device_hardware_id.new_id);
end;
$function$;

-- The dispatcher's inline rename (runs only on the Worker's 'success').
CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    flag_on     boolean;
    secret      text;
    url         text;
    resp        extensions.http_response;
    meta        json;
    decision    text;
    attempts    int;
    blocked_now boolean;
begin
    select enabled into flag_on from cf.feature_flags
     where name = 'cf_activation_write_enabled';

    if not coalesce(flag_on, false) then
        return cf.activate_device_legacy(device_hw_id, activation_serial, legacy_hw_id);
    end if;

    select value into url from cf.settings where key = 'worker_activate_url';
    select decrypted_secret into secret from vault.decrypted_secrets
     where name = 'cf_activation_worker_secret';

    if url is null or secret is null then
        raise warning 'cf_activation: missing url/secret, falling back to legacy';
        return cf.activate_device_legacy(device_hw_id, activation_serial, legacy_hw_id);
    end if;

    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '4000');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '2000');

    begin
        select * into resp from extensions.http((
            'POST', url,
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object(
                'hardware_id',       device_hw_id,
                'activation_serial', activation_serial,
                'legacy_hw_id',      legacy_hw_id,
                'activated_at',      to_char(now() at time zone 'utc','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
            )::text
        )::extensions.http_request);
    exception when others then
        raise warning 'cf_activation: % unreachable: %', device_hw_id, SQLERRM;
        raise exception 'activation backend unreachable: %', SQLERRM;
    end;

    if resp.status <> 200 then
        raise warning 'cf_activation: % HTTP % %', device_hw_id, resp.status, coalesce(resp.content,'');
        raise exception 'activation backend returned HTTP %', resp.status;
    end if;

    meta     := resp.content::json;
    decision := meta ->> 'status';
    if decision is null then
        raise warning 'cf_activation: % no decision in %', device_hw_id, coalesce(resp.content,'');
        raise exception 'activation backend returned no decision';
    end if;

    attempts    := coalesce((meta ->> 'attempts')::int, 0);
    blocked_now := coalesce((meta ->> 'blocked_now')::boolean, false);

    if decision = 'success' then
        -- Same direction guard as migrate_device_hardware_id (2026-09-03).
        if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id
           and device_hw_id like 'VIN-%'
           and (legacy_hw_id not like 'VIN-%' or upper(legacy_hw_id) = upper(device_hw_id)) then
            if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
               and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
                update public.devices set hardware_id = device_hw_id
                 where hardware_id = legacy_hw_id;
            end if;
        end if;

        insert into public.devices (hardware_id, serial_number, is_active, activated_at)
        values (device_hw_id, activation_serial, true, now())
        on conflict (hardware_id)
        do update set serial_number = excluded.serial_number,
                      is_active     = true,
                      activated_at  = now(),
                      failed_attempts    = 0,
                      last_failed_at     = null,
                      last_failed_serial = null;

    elsif blocked_now then
        insert into public.devices (hardware_id, is_active, is_blocked, failed_attempts,
                                    last_failed_at, last_failed_serial, blocked_at, block_reason)
        values (device_hw_id, false, true, attempts, now(), activation_serial, now(),
                coalesce(meta ->> 'block_reason', 'failed_attempts'))
        on conflict (hardware_id) do update set
            is_blocked         = true,
            failed_attempts    = excluded.failed_attempts,
            last_failed_at     = excluded.last_failed_at,
            last_failed_serial = excluded.last_failed_serial,
            blocked_at         = coalesce(devices.blocked_at, excluded.blocked_at),
            block_reason       = coalesce(devices.block_reason, excluded.block_reason);

    elsif attempts > 0 then
        update public.devices
           set failed_attempts    = attempts,
               last_failed_at     = now(),
               last_failed_serial = activation_serial
         where hardware_id = device_hw_id
           and not is_blocked;
    end if;

    return decision;
end;
$function$;

-- The kill-switch fallback: same guard on its inline rename. Rest unchanged.
CREATE OR REPLACE FUNCTION cf.activate_device_legacy(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    existing_hw_id text;
    is_valid       boolean;
begin
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id
       and device_hw_id like 'VIN-%'
       and (legacy_hw_id not like 'VIN-%' or upper(legacy_hw_id) = upper(device_hw_id)) then
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

    is_valid :=
        (existing_hw_id is not distinct from device_hw_id)   -- this car re-typing its own code
        or (activation_serial like '578%'
            and not cf.serial_is_unissued_in_closed_block(activation_serial))
        or (activation_serial like '7078%' and existing_hw_id is not null);

    if not coalesce(is_valid, false) then
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
