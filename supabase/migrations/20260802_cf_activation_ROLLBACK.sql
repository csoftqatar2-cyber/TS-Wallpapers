-- ============================================================================
-- ROLLBACK — NOT APPLIED. Written ahead of time so it is never improvised
-- during an incident. Two tiers; try tier 1 first, it is almost always enough.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- TIER 1 (seconds): stop using Cloudflare, keep everything else in place.
--
-- The dispatcher checks this flag as its first statement, so this takes effect
-- on the very next call. Activation immediately goes back to the byte-identical
-- legacy body, with no Cloudflare involvement at all.
--
-- This is lossless because Postgres never stopped being a complete, current
-- copy: the dispatcher writes every successful activation locally, so nothing
-- has to be restored from D1.
-- ---------------------------------------------------------------------------

update cf.feature_flags
   set enabled = false, updated_at = now()
 where name = 'cf_activation_write_enabled';

-- Verify: this must answer 'success' in a few milliseconds, without any
-- outbound request.
-- select cf.activate_device_legacy('ROLLBACK-CHECK','7078000000');
-- delete from public.devices where hardware_id = 'ROLLBACK-CHECK';


-- ---------------------------------------------------------------------------
-- TIER 2 (a minute): remove the dispatcher entirely, restoring the exact
-- function that existed before 20260802_cf_activation_dispatcher.
--
-- Only needed if the dispatcher itself misbehaves (e.g. it cannot even read the
-- flag). CREATE OR REPLACE, never DROP: is_device_activated and activate_device
-- must never 404, because the sibling apps read a 404 as "fall back to probing
-- get_wallpapers", and a car with no wallpapers assigned would then deactivate
-- itself.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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

    -- 7078 = the codes already in the field; 578 = everything issued from
    -- 2026-08-02 on. Rolling back must not start rejecting the new codes.
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

-- The cf schema can be left in place after tier 2; nothing references it once
-- the dispatcher is gone, and keeping it preserves the audit trail and makes a
-- second attempt cheap. Drop it only when the approach is abandoned for good:
--   drop schema cf cascade;
