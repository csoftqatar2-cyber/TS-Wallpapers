-- ============================================================================
-- Activation codes, owner's rule of 2026-09-07:
--   * the open '578' space is CLOSED for new activations. Only the sold closed
--     block(s) in cf.closed_serial_blocks keep working (578300001..578300100),
--     and every car already activated with a 578 code keeps re-activating.
--   * the normal path is now the admin-site generator: six random digits, ten
--     minutes, one car (D1 issued_codes — the Cloudflare worker decides).
--   * reserve block typed by hand when the generator is unavailable: '572' + 3
--     digits (572001..572999), single use.
--
-- Postgres only COMMITS what the worker decided, so two things must accept the
-- new shapes: the CHECK on devices.serial_number and the legacy fallback
-- (cf.activate_device_legacy, used only when the worker is unreachable — it
-- cannot see minted codes, so a generator code fails closed there).
--
-- Applied live 2026-09-07.
-- ============================================================================

alter table public.devices drop constraint if exists devices_serial_number_check;
alter table public.devices
    add constraint devices_serial_number_check
        check (serial_number like '7078%' or serial_number like '578%'
               or serial_number ~ '^572[0-9]{3}$' or serial_number ~ '^[0-9]{6}$');

create or replace function cf.activate_device_legacy(device_hw_id text, activation_serial text, legacy_hw_id text default null)
 returns text language plpgsql security definer set search_path to 'public'
as $function$
declare existing_hw_id text; is_valid boolean;
begin
  if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id
     and device_hw_id like 'VIN-%' and (legacy_hw_id not like 'VIN-%' or upper(legacy_hw_id) = upper(device_hw_id)) then
    if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
       and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
      update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
    end if;
  end if;
  if exists (select 1 from public.devices where hardware_id = device_hw_id and is_blocked = true) then
    return 'blocked';
  end if;
  select hardware_id into existing_hw_id from public.devices where serial_number = activation_serial;
  is_valid := (existing_hw_id is not distinct from device_hw_id)
           or (exists (select 1 from cf.closed_serial_blocks b where activation_serial like b.prefix || '%')
               and not cf.serial_is_unissued_in_closed_block(activation_serial))
           or (activation_serial ~ '^572[0-9]{3}$')
           or (activation_serial like '7078%' and existing_hw_id is not null);
  if not coalesce(is_valid, false) then return 'invalid_format'; end if;
  if existing_hw_id is not null and existing_hw_id <> device_hw_id then return 'serial_already_used'; end if;
  insert into public.devices (hardware_id, serial_number, is_active, activated_at)
  values (device_hw_id, activation_serial, true, now())
  on conflict (hardware_id) do update set serial_number = excluded.serial_number, is_active = true, activated_at = now();
  return 'success';
end;
$function$;
