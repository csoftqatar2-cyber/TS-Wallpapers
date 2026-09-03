-- Rollback for 20260903_store_counter_guards.sql: the live bodies as they stood
-- before it on 2026-09-03 (store_check_in as left by
-- 20260903_store_check_in_unified_block.sql). Same signatures; no drop/grant needed.
-- store_hw_id_ok can stay (nothing else calls it) or be dropped:
--   drop function if exists public.store_hw_id_ok(text);

CREATE OR REPLACE FUNCTION public.store_check_in(p_hw_id text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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

    select exists (
        select 1
          from public.devices d
         where d.hardware_id = public.resolve_device_id(p_hw_id)
           and d.is_blocked = true
    ) into v_device_blocked;

    return coalesce(v_store_blocked, false) or coalesce(v_device_blocked, false);
end
$function$;

CREATE OR REPLACE FUNCTION public.store_failed_activation(p_hw_id text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v_count int; v_blocked boolean;
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return 0; end if;
  insert into public.store_installs (hw_id, car, version, failed_attempts, last_failed_at)
       values (p_hw_id, nullif(p_car,''), nullif(p_version,''), 1, now())
  on conflict (hw_id) do update
       set failed_attempts = public.store_installs.failed_attempts + 1,
           last_failed_at  = now(),
           last_seen       = now(),
           car             = coalesce(nullif(excluded.car,''),     public.store_installs.car),
           version         = coalesce(nullif(excluded.version,''), public.store_installs.version)
  returning failed_attempts, blocked into v_count, v_blocked;

  if v_count >= 10 and not coalesce(v_blocked, false) then
    update public.store_installs
       set blocked = true, blocked_reason = 'failed_activation', blocked_at = now()
     where hw_id = p_hw_id;
  end if;
  perform public.store_event_write(p_hw_id, 'activate', p_car, p_version, null, null, null, false,
                                   'محاولة ' || v_count || case when v_count >= 10 then ' — حُظر تلقائياً' else '' end);
  return coalesce(v_count, 0);
end $function$;

CREATE OR REPLACE FUNCTION public.store_activation_ok(p_hw_id text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return; end if;
  update public.store_installs
     set failed_attempts = 0, last_failed_at = null
   where hw_id = p_hw_id and blocked = false;
  perform public.store_event_write(p_hw_id, 'activate', null, null, null, null, null, true, 'تفعيل ناجح');
end $function$;
