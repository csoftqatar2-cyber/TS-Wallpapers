-- ============================================================================
-- Leopard dashboard channel — a folder mirror for the BYD Leopard car.
--
-- Applied live 2026-09-07. Verified: exactly one signature, anon can execute, an
-- activated Leopard car gets an authoritative empty list and an unknown id gets the
-- 'inactive' sentinel.
--
-- Third instance of the GWM Split / Jetour G700 mechanism, and the first one on
-- a hand-off mode. A Leopard car already hands its picked wallpaper to Android's
-- WallpaperManager; that is untouched. What this adds is a SECOND, independent
-- delivery: the operator publishes to channel 'leopard_dash' and the app mirrors
-- it into /sdcard/Pictures/TS Leo Dashboard, which is the folder TS Leo Dash
-- (com.codex.clusterlauncher, the car's own multi-display launcher) already
-- reads its dashboard wallpapers from -- see ScreenTheme.CAR_THEME_DIR there.
-- That app lists both pictures and video, so this channel carries both.
--
-- Nothing about modes changes: 'leopard' has been a valid devices.mode and
-- wallpapers.target_mode value since the mode shipped. The only new thing is a
-- channel value, and wallpapers.channel is deliberately unconstrained text.
--
-- ORDER: apply this BEFORE the app build that calls get_leopard_wallpapers
-- ships. Backwards, every Leopard car's mirror pass throws on a missing
-- function; harmless (the folder is left untouched) but it would report a
-- failure on every settings screen.
-- ============================================================================

-- Leopard dashboard channel playlist. Character-for-character get_jetour_wallpapers
-- over a different channel: same activation gate, same alias resolution, same
-- 'inactive' sentinel. One RPC per channel is what keeps a Leopard image out of a
-- GWM folder and vice versa.
create or replace function public.get_leopard_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null::text
)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    hw text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    hw := public.resolve_device_id(device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.channel = 'leopard_dash'
          and (w.is_global = true or w.hardware_id = hw)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- Devices are anonymous; the RPC is the gate. Same grants the other two carry.
grant execute on function public.get_leopard_wallpapers(text, text) to anon, authenticated;
