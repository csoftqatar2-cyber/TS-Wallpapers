-- ROLLBACK of 20261102_companions_controller_licence.sql — restores the live definitions read from
-- ihgmqwzdpugdzddobhbc on 2026-11-02 (pg_get_functiondef), then drops the helper.
-- Grants on is_device_activated / get_device_status are kept by CREATE OR REPLACE.

create or replace function public.is_device_activated(device_hw_id text)
returns boolean
language sql
security definer
set search_path = public
as $function$
  select exists (
    select 1
    from public.devices d
    where d.hardware_id = public.resolve_device_id(is_device_activated.device_hw_id)
      and d.is_active = true
      and d.is_blocked = false
  );
$function$;

create or replace function public.get_device_status(device_hw_id text, legacy_hw_id text default null::text)
returns text
language plpgsql
security definer
set search_path = public
as $function$
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
$function$;

drop function if exists public.controller_licence_unlocks(text, text);
