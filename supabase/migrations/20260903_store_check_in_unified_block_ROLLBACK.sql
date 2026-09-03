-- ============================================================================
-- ROLLBACK for 20260903_store_check_in_unified_block.sql
-- Restores the previous body: store_check_in answers store_installs.blocked
-- only, ignoring devices.is_blocked. Same signature, same grants.
-- Run only if a store that must stay open is being refused because its car is
-- blocked in `devices`; the right fix is usually to clear that block instead.
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
declare v_blocked boolean;
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return false; end if;
  insert into public.store_installs (hw_id, car, version)
       values (p_hw_id, nullif(p_car,''), nullif(p_version,''))
  on conflict (hw_id) do update
       set last_seen = now(),
           car       = coalesce(nullif(excluded.car,''),     public.store_installs.car),
           version   = coalesce(nullif(excluded.version,''), public.store_installs.version)
  returning blocked into v_blocked;
  return coalesce(v_blocked, false);
end $function$;

revoke all on function public.store_check_in(text, text, text) from public;
grant execute on function public.store_check_in(text, text, text) to anon, authenticated;
