-- Undo 20260804_device_id_aliases.sql: the four functions go back to what they were on
-- 2026-08-04, byte for byte, taken from the live database before the change.
--
-- CREATE OR REPLACE throughout, never DROP + CREATE. A missing is_device_activated — even for
-- the instant of a transaction — makes the clients fall back to reading activation out of
-- get_wallpapers, where a licensed car that happens to have no car-specific wallpapers reads
-- as unlicensed and deactivates itself.
--
-- The table is left in place on purpose. Dropping it loses the forwarding addresses, and any
-- car whose id was migrated while the change was live would then go dark for whichever of the
-- three apps had not learned its new id. Once nothing reads it, it costs a few kilobytes.
-- If it genuinely has to go, the last line does it — read the paragraph above first.

create or replace function public.migrate_device_hardware_id(old_id text, new_id text)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    if old_id is null or old_id = '' or new_id is null or new_id = '' or old_id = new_id then
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
end;
$function$;

create or replace function public.is_device_activated(device_hw_id text)
 returns boolean
 language sql
 security definer
 set search_path to 'public'
as $function$
  select exists (
    select 1
    from public.devices d
    where d.hardware_id = is_device_activated.device_hw_id
      and d.is_active = true
      and d.is_blocked = false
  );
$function$;

create or replace function public.get_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    -- An already-registered device that now reports a VIN keeps its row
    -- (activation, wallpapers, hides, block status) under the new id.
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = device_hw_id and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        join public.devices d on d.hardware_id = device_hw_id
        where w.channel = 'app'
          and (w.is_global = true or w.hardware_id = device_hw_id)
          and (w.target_mode is null or w.target_mode = d.mode)
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = device_hw_id
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

create or replace function public.get_gwm_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = device_hw_id and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.channel = 'gwm_split'
          and (w.is_global = true or w.hardware_id = device_hw_id)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- drop function if exists public.resolve_device_id(text);
-- drop table if exists public.device_id_aliases;
