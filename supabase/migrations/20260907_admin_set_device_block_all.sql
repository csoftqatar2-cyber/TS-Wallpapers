-- Applied live as admin_set_device_block_all on 2026-09-03 (Supabase MCP execute_sql).
--
-- WHY
--   Blocking a car today is two switches: devices.is_blocked (D1 decides, Postgres mirrors,
--   via admin_set_device_block) and store_installs.blocked (store_admin_set_blocked, secret
--   gated). A car blocked from one dashboard kept working in the apps that read the other
--   switch. The owner wants ONE button that blocks every program on the car.
--
-- WHAT (new name only)
--   public.admin_set_device_block_all(p_hardware_id, p_blocked) -> devices
--     admin-JWT gated exactly like admin_set_device_block; calls it (so D1 stays the
--     authority and the audit trail is written), then sets store_installs.blocked for the
--     car under its current id and every alias. Unblocking clears the store counters the
--     same way store_admin_set_blocked does.
--
-- WHAT CANNOT BREAK
--   * admin_set_device_block and store_admin_set_blocked are untouched.
--   * Anonymous callers cannot execute it (auth.uid() check + no anon grant).
--
-- ROLLBACK: drop function public.admin_set_device_block_all(text, boolean);

create or replace function public.admin_set_device_block_all(p_hardware_id text, p_blocked boolean)
returns public.devices
language plpgsql security definer set search_path to 'public'
as $function$
declare
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
    dev public.devices;
    hw  text;
begin
    if auth.uid() is distinct from admin_uid then raise exception 'not authorized'; end if;
    hw  := public.resolve_device_id(p_hardware_id);
    -- D1 + devices (+ audit) first: it raises if the worker is unreachable, and then
    -- nothing else moves - the two switches never end up disagreeing.
    dev := public.admin_set_device_block(hw, p_blocked);

    update public.store_installs
       set blocked         = p_blocked,
           blocked_reason  = case when p_blocked then coalesce(blocked_reason, 'admin') else null end,
           blocked_at      = case when p_blocked then coalesce(blocked_at, now()) else null end,
           failed_attempts = case when p_blocked then failed_attempts else 0 end,
           last_failed_at  = case when p_blocked then last_failed_at else null end
     where hw_id = hw
        or hw_id = p_hardware_id
        or hw_id in (select old_id from public.device_id_aliases where current_id = hw);
    return dev;
end $function$;
revoke all on function public.admin_set_device_block_all(text, boolean) from public;
grant execute on function public.admin_set_device_block_all(text, boolean) to authenticated;
-- Supabase default privileges hand EXECUTE to anon on new functions; take it back explicitly.
revoke execute on function public.admin_set_device_block_all(text, boolean) from anon;
