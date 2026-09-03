-- Rollback for 20260903_migration_direction_guard.sql: restores the three live
-- bodies exactly as pulled from ihgmqwzdpugdzddobhbc on 2026-09-03 before the guard.
-- Same signatures, so no drop/grant is needed.

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

-- activate_device and cf.activate_device_legacy: re-apply the bodies in
-- 20260903_migration_direction_guard.sql with the two guard clauses
--   "and device_hw_id like 'VIN-%' and (legacy_hw_id not like 'VIN-%' or upper(...) = upper(...))"
-- removed from the inline rename. Nothing else in those bodies changed.
