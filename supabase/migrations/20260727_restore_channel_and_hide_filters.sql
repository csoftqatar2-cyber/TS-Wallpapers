-- ============================================================================
-- REGRESSION FIX — the slideshow was serving GWM Split images.
--
-- 20260721_add_channel_for_gwm_split_delivery taught get_wallpapers two rules:
--   * channel = 'app'  -> a gwm_split image is a FILE DROP into the car's
--                         external GWMSplit_Styles folder (the same folder the
--                         Cars-installer script pushes photos to on a GWM car).
--                         Our own slideshow must never show it.
--   * wallpaper_hides  -> a global wallpaper hidden on one car stays hidden.
--
-- 20260726_lynkco_mode_and_targeting.sql then re-created the function from the
-- PRE-channel body and added target_mode to it, silently dropping BOTH rules.
-- In the field that meant: an image uploaded to the GWM Split channel ALSO
-- showed up inside our app (the reported bug), and every per-car hide exception
-- stopped being applied.
--
-- This restores all three filters in one body. The GWM channel keeps its own
-- RPC (get_gwm_wallpapers), which was never touched.
--
-- Applied to the live project as migration 20260727_restore_channel_and_hide_filters.
-- ============================================================================
create or replace function public.get_wallpapers(
    device_hw_id text,
    legacy_hw_id text default null::text
)
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
