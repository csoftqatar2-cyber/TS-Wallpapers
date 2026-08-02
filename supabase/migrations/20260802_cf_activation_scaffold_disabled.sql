-- Applied live as 20260802170040_cf_activation_scaffold_disabled
--
-- Scaffolding for routing the activation DECISION to Cloudflare D1.
-- Applied with the kill switch OFF: after this migration the system behaves
-- exactly as it did before it, and touches Cloudflare not at all.
--
-- Everything lives in schema `cf`, which PostgREST does not expose, so none of
-- it is reachable over the API by the fleet or by anyone holding the anon key.

create schema if not exists cf;
revoke all on schema cf from anon, authenticated;

create table if not exists cf.feature_flags (
    name       text primary key,
    enabled    boolean not null default false,
    updated_at timestamptz not null default now()
);

-- OFF. Turning this on is a separate, deliberate act.
insert into cf.feature_flags(name, enabled)
values ('cf_activation_write_enabled', false)
on conflict (name) do nothing;

-- A byte-for-byte copy of the activate_device body as it stands today (verified
-- by comparing the md5 of both function bodies after creation). This is the
-- fallback the dispatcher calls whenever the flag is off, and it is what makes
-- rollback instant: the old code path never stops existing.
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

revoke all on function cf.activate_device_legacy(text,text,text) from public, anon, authenticated;
