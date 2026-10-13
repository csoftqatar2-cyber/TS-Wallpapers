-- ============================================================================
-- get_wallpapers / get_leopard_wallpapers: an optional device_mode from the caller.
--
-- NOT APPLIED. Written 2026-10-13 for the ذبذبة dashboard (com.thabthaba.controller), which
-- asks get_wallpapers for the same car as TS WALLPAPERS but never reports a mode: on a car
-- that has only the dashboard, devices.mode stays NULL, and `w.target_mode = d.mode` drops
-- every wallpaper the admin targeted at a car type («ليوبارد», «دينزا», …).
--
-- What changes:
--   * get_wallpapers(device_hw_id, legacy_hw_id, device_mode default null) — the mode the
--     caller sends is used ONLY when devices.mode is NULL (a mode the TS WALLPAPERS app
--     reported always wins), and only when it is one of the known modes.
--   * get_leopard_wallpapers gets the same parameter and the same two filters
--     get_wallpapers already has: target_mode and the per-car hides (wallpaper_hides). On this
--     Leopard-only channel a car with no mode at all counts as 'leopard', so nothing that
--     reaches a Leopard today stops reaching it.
--
-- Backward compatible: the old two-argument call (every TS WALLPAPERS build, every dashboard
-- build before this) resolves to the new function through the default — so the old
-- signature is DROPPED, not overloaded (two candidates for a two-argument call would make
-- PostgREST answer 300 "ambiguous" to every car). One transaction; grants re-made.
--
-- ORDER: apply any time. The dashboard build that sends device_mode falls back to asking
-- without it when the function does not take it (HTTP 404/400), so either order works.
--
-- Verify after applying:
--   select pg_get_function_identity_arguments(oid) from pg_proc
--    where proname in ('get_wallpapers','get_leopard_wallpapers');      -- one row each, 3 args
--   select * from public.get_wallpapers('<an activated hw id>');              -- as before
--   select * from public.get_wallpapers('<a dashboard-only hw id>', null, 'leopard');
--                                     -- now includes the target_mode = 'leopard' wallpapers
-- ============================================================================

begin;

drop function if exists public.get_wallpapers(text, text);

create or replace function public.get_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null::text,
    device_mode  text default null::text
)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    hw text;
    asked text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    hw := public.resolve_device_id(device_hw_id);
    -- Only a mode this database knows (the same list as devices_mode_check).
    asked := case when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7')
                  then device_mode end;

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        join public.devices d on d.hardware_id = hw
        where w.channel = 'app'
          and (w.is_global = true or w.hardware_id = hw)
          and (w.target_mode is null or w.target_mode = coalesce(d.mode, asked))
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = hw
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

grant execute on function public.get_wallpapers(text, text, text) to anon, authenticated;

drop function if exists public.get_leopard_wallpapers(text, text);

create or replace function public.get_leopard_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null::text,
    device_mode  text default null::text
)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    hw text;
    asked text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    hw := public.resolve_device_id(device_hw_id);
    asked := case when device_mode in ('normal','fse','leopard','gwm','lynkco','jetour','denza','icar03t','havalv7')
                  then device_mode end;

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        join public.devices d on d.hardware_id = hw
        where w.channel = 'leopard_dash'
          and (w.is_global = true or w.hardware_id = hw)
          -- A Leopard-only channel: a car that never reported a mode (and did not send one) is a
          -- Leopard here — rows the panel saved with target_mode 'leopard' must keep reaching it.
          and (w.target_mode is null or w.target_mode = coalesce(d.mode, asked, 'leopard'))
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = hw
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

grant execute on function public.get_leopard_wallpapers(text, text, text) to anon, authenticated;

commit;

notify pgrst, 'reload schema';
