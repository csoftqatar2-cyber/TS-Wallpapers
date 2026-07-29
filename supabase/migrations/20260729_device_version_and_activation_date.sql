-- ============================================================================
-- "Which cars are on which build, and when was each one activated?"
--
-- Neither question could be answered before this. Publishing a version told us
-- an APK existed; it said nothing about how many of the 459 cars had actually
-- taken it, so every release was pushed blind. And `created_at` was the only
-- date on a device row — the day it first appeared, which is not the same thing
-- as the day it was activated and is not what the shop is asked about.
--
--   1. devices += app_version / app_version_code / last_seen_at / activated_at
--   2. report_device_mode() also records the build and the check-in time.
--   3. activate_device() stamps activated_at at the moment of activation.
-- ============================================================================

alter table public.devices
    add column if not exists app_version      text,
    add column if not exists app_version_code int,
    add column if not exists last_seen_at     timestamptz,
    add column if not exists activated_at     timestamptz;

comment on column public.devices.activated_at is
    'When the car was activated. Rows activated before this column existed were '
    'backfilled from created_at — right for cars whose row was created BY the '
    'activation (the normal path), an approximation for any created earlier.';

-- Backfill, once. Every device row is created by activate_device itself, so for
-- the overwhelming majority created_at IS the activation moment.
update public.devices
   set activated_at = created_at
 where is_active = true and activated_at is null;

-- ----------------------------------------------------------------------------
-- 2. report_device_mode: now also the fleet's check-in.
--
-- DROPPED and recreated rather than `create or replace`d with extra arguments:
-- adding parameters changes the signature, so the 3-argument version would live
-- on beside it and a 3-argument call (every APK before 3.5) would match BOTH —
-- Postgres answers "function is not unique" and mode reporting breaks fleet-wide.
-- One function with defaults keeps old and new APKs on the same code path.
-- ----------------------------------------------------------------------------
drop function if exists public.report_device_mode(text, text, text);

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

    -- The version is reported on every launch and must land even when the mode
    -- is missing or garbage — the two facts are independent, and dropping the
    -- check-in because of a bad mode string is how a car goes quiet for no
    -- reason. Only ever UPDATEs: activation remains the one thing that creates
    -- a device row.
    update public.devices
       set mode             = case
                                when device_mode in ('normal','fse','leopard','gwm','lynkco')
                                then device_mode else mode end,
           app_version      = coalesce(report_device_mode.app_version, devices.app_version),
           app_version_code = coalesce(report_device_mode.app_version_code, devices.app_version_code),
           last_seen_at     = now()
     where hardware_id = device_hw_id;
end;
$function$;

revoke all on function public.report_device_mode(text, text, text, text, int) from public;
grant execute on function public.report_device_mode(text, text, text, text, int)
    to anon, authenticated;

-- ----------------------------------------------------------------------------
-- 3. activate_device: stamp the activation date.
--
-- Body is otherwise byte-identical to the live version — only the insert and the
-- conflict branch changed. `activated_at` is set on the way in and refreshed on
-- a re-activation, because re-activating IS an activation as far as the shop is
-- concerned (a head unit swap, a re-flash), and the older date would describe a
-- device that no longer exists.
-- ----------------------------------------------------------------------------
create or replace function public.activate_device(
    device_hw_id text,
    activation_serial text,
    legacy_hw_id text default null::text
)
 returns text
 language plpgsql
 security definer
 set search_path to 'public'
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

    if activation_serial not like '7078%' then
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
