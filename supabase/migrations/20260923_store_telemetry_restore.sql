-- ============================================================================
-- Store telemetry: three silent faults in the Supabase channel.
-- Applied live 2026-09-13 as `store_telemetry_restore_open_ip_and_kinds`.
--
-- Found by the weekly crash review (THABTHABA STORE, reports/crash-reviews/
-- «مراجعة اعطال 2026-09-05 إلى 2026-09-12», §3 and §7). None of them raised an
-- error anywhere; each was visible only as a number that stopped moving.
--
-- 1) store_log_event accepted only ('install','uninstall').
--    The store app sends car_setting, vin_probe and token_reject through the same
--    RPC (util/StoreLog.kt). All three were discarded from the day they were added —
--    store 2.1.7 (115) shipped specifically to deliver car_setting, on 59 devices,
--    and not one row ever arrived. Whitelist widened; it stays a whitelist, and the
--    install counter stays gated on p_kind = 'install'.
--
-- 2) store_check_in stopped writing the 'open' event.
-- 3) store_check_in stopped capturing the client IP.
--    Both were dropped by 20260903_store_check_in_unified_block.sql, which rewrote
--    the function to add the unified block. Last 'open' row and max(last_ip_at) are
--    the same instant: 2026-09-03 11:46:53.428Z, 68 s before that migration's version
--    stamp. opens_7d in store_admin_stats has read zero since, and the panel's
--    location column showed a ten-day-old city as current.
--
-- RULE FOR THE NEXT REWRITE OF store_check_in
--   Start from THIS body, not from 20260903_store_check_in_unified_block.sql or its
--   ROLLBACK. It must keep, in order: the store_hw_id_ok gate, the IP capture with
--   coalesce (an unknown IP never erases a known one), the unified block, and the
--   'open' event after the gate. Signatures and grants are unchanged here.
--
-- VERIFIED on a Leopard (store 2.1.7/115, VIN-byd4DBD3D6D24AC5814) right after apply:
--   open row 1.5 s after launch; last_ip = the bench network's public IP;
--   two car_setting rows (microG allow, then block) with their LeopardSelfStart
--   diagnosis in detail; install counter still moves on a successful install.
--
-- Weekly guard — should return zero rows (vin_probe is once-per-install and
-- token_reject fires only on a v1/v2 disagreement, so those two may legitimately
-- stay empty for a while):
--   with expected(kind) as (values ('install'),('uninstall'),('open'),('activate'),
--                                  ('car_setting'),('vin_probe'),('token_reject'))
--   select e.kind from expected e
--    where not exists (select 1 from public.store_events s
--                       where s.kind = e.kind and s.created_at >= now() - interval '14 days');
-- ============================================================================

create or replace function public.store_log_event(
  p_hw_id text, p_kind text, p_car text default null, p_version text default null,
  p_package text default null, p_app_name text default null, p_app_version text default null,
  p_ok boolean default true, p_detail text default null
) returns void
language plpgsql security definer set search_path to 'public'
as $function$
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return; end if;
  if p_kind is null or p_kind not in
       ('install','uninstall','car_setting','vin_probe','token_reject')
  then return; end if;
  perform public.store_event_write(p_hw_id, p_kind, p_car, p_version,
                                   p_package, p_app_name, p_app_version, p_ok, p_detail);
  if coalesce(p_ok, true) and p_kind = 'install' then
    insert into public.store_installs (hw_id, car, version, installs, last_install_at)
         values (p_hw_id, nullif(p_car,''), nullif(p_version,''), 1, now())
    on conflict (hw_id) do update
         set installs        = public.store_installs.installs + 1,
             last_install_at = now(),
             last_seen       = now(),
             car             = coalesce(nullif(excluded.car,''),     public.store_installs.car),
             version         = coalesce(nullif(excluded.version,''), public.store_installs.version);
  end if;
end $function$;

create or replace function public.store_check_in(
  p_hw_id text, p_car text default null, p_version text default null
) returns boolean
language plpgsql security definer set search_path to 'public'
as $function$
declare
    v_store_blocked  boolean;
    v_device_blocked boolean;
    v_ip             text;
begin
    if p_hw_id is null or length(p_hw_id) = 0 then return false; end if;
    if not public.store_hw_id_ok(p_hw_id) then return false; end if;

    v_ip := public.store_client_ip();
    insert into public.store_installs (hw_id, car, version, last_ip, last_ip_at)
         values (p_hw_id, nullif(p_car,''), nullif(p_version,''),
                 v_ip, case when v_ip is null then null else now() end)
    on conflict (hw_id) do update
         set last_seen  = now(),
             car        = coalesce(nullif(excluded.car,''),     public.store_installs.car),
             version    = coalesce(nullif(excluded.version,''), public.store_installs.version),
             last_ip    = coalesce(excluded.last_ip, public.store_installs.last_ip),
             last_ip_at = case when excluded.last_ip is not null
                               then now() else public.store_installs.last_ip_at end
    returning blocked into v_store_blocked;

    select exists (
        select 1
          from public.devices d
         where d.hardware_id = public.resolve_device_id(p_hw_id)
           and d.is_blocked = true
    ) into v_device_blocked;

    perform public.store_event_write(p_hw_id, 'open', p_car, p_version,
                                     null, null, null, true, null);

    return coalesce(v_store_blocked, false) or coalesce(v_device_blocked, false);
end
$function$;
