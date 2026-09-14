-- Applied live 2026-09-14 (project ihgmqwzdpugdzddobhbc).
--
-- WHY: the owner's rule «متسألوش السائق يختار نوع العربية وهو مختار خلاص» has two publishers of
-- that choice. The controller writes /data/local/tmp/thabd/records/car.txt + car_family.txt on
-- the unit, but most cars carry ذبذبة ستور and no controller — and the store already reports its
-- car picker's answer on every check-in (store_installs.car: leopard / denza / tank500 / …). This
-- read-only function hands that answer to any companion app by hardware id, so ذبذبة خلفيات can
-- open straight in the matching mode (Leopard on the driver screen, FSE on the passenger
-- instance) on a car where only the store was ever configured.
--
-- Returns the store's car id text or NULL (unknown unit, or the store never checked in). Device
-- ids are resolved through device_id_aliases like every other RPC; both the raw and the resolved
-- id are matched because store_installs historically keyed on the raw one.

create or replace function public.get_car_type(device_hw_id text)
returns text
language plpgsql
stable
security definer
set search_path to 'public'
as $function$
declare
    hw  text;
    car text;
begin
    if device_hw_id is null or device_hw_id = '' then return null; end if;
    hw := public.resolve_device_id(device_hw_id);
    select si.car into car
      from public.store_installs si
     where si.hw_id in (device_hw_id, hw) and si.car is not null and si.car <> ''
     order by si.last_seen desc nulls last
     limit 1;
    return car;
end $function$;

revoke all on function public.get_car_type(text) from public;
grant execute on function public.get_car_type(text) to anon, authenticated;
