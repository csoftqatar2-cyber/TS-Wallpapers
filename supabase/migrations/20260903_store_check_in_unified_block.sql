-- ============================================================================
-- store_check_in: one block list for the whole car, not one per program.
--
-- WHY
--   Until now there were two independent block flags for the same car:
--     * devices.is_blocked        — written by D1/activate_device (10 wrong codes)
--                                   and by the wallpapers dashboard
--                                   (admin_set_device_block). Read by
--                                   is_device_activated / get_wallpapers /
--                                   get_device_status — i.e. TS Wallpapers,
--                                   TS Back Button and the Leopard controller.
--     * store_installs.blocked    — written by store_failed_activation and the
--                                   store admin (store_admin_set_blocked). Read
--                                   ONLY by store_check_in, i.e. only ذبذبة ستور.
--   So blocking a car from the wallpapers dashboard left the store open on
--   that car, and blocking it from the store admin left the wallpapers open.
--
-- WHAT
--   store_check_in keeps its exact signature, its upsert and its grants, and
--   now answers true when EITHER flag is set. devices is looked up through
--   resolve_device_id so a store that still checks in under a pre-VIN id is
--   matched to the car's current row (device_id_aliases).
--
-- WHAT IT DOES NOT DO
--   It does not write devices.is_blocked (D1 is the only decider there — see
--   20260815_activation_failed_attempts.sql) and it does not touch
--   store_installs.blocked. Unblocking still has to clear whichever flag was
--   set: admin_set_device_block for devices, store_admin_set_blocked for
--   store_installs. A car blocked in both must be cleared in both.
--
-- CLIENT CONTRACT (unchanged)
--   store_check_in(p_hw_id, p_car, p_version) -> boolean  (true = blocked)
--   Older store APKs treat 404/non-2xx as "unknown", never as blocked.
--
-- Applied live as 20260903_store_check_in_unified_block. Re-runnable.
-- Rollback: 20260903_store_check_in_unified_block_ROLLBACK.sql
-- ============================================================================

create or replace function public.store_check_in(
    p_hw_id   text,
    p_car     text default null,
    p_version text default null
) returns boolean
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
    v_store_blocked  boolean;
    v_device_blocked boolean;
begin
    if p_hw_id is null or length(p_hw_id) = 0 then return false; end if;

    insert into public.store_installs (hw_id, car, version)
         values (p_hw_id, nullif(p_car,''), nullif(p_version,''))
    on conflict (hw_id) do update
         set last_seen = now(),
             car       = coalesce(nullif(excluded.car,''),     public.store_installs.car),
             version   = coalesce(nullif(excluded.version,''), public.store_installs.version)
    returning blocked into v_store_blocked;

    -- The car-wide flag every other program honours. resolve_device_id maps a
    -- legacy id (MAC-/SYS-/...) that has since become a VIN- row.
    select exists (
        select 1
          from public.devices d
         where d.hardware_id = public.resolve_device_id(p_hw_id)
           and d.is_blocked = true
    ) into v_device_blocked;

    return coalesce(v_store_blocked, false) or coalesce(v_device_blocked, false);
end
$function$;

revoke all on function public.store_check_in(text, text, text) from public;
grant execute on function public.store_check_in(text, text, text) to anon, authenticated;
