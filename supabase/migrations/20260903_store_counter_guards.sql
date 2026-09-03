-- Applied live as store_counter_guards + store_hw_id_ok_search_path on 2026-09-03
-- (Supabase MCP apply_migration, project ihgmqwzdpugdzddobhbc).
--
-- WHY
--   The three store_* RPCs are anon-callable and keyed on a caller-supplied hw_id
--   that is only checked for length > 0. That allowed, with nothing but the APK's
--   anon key:
--     * planting an HTML hw_id in store_installs/store_events (stored XSS in the
--       store admin panel, which keeps R2 keys in localStorage);
--     * pushing ANY car - including a paying, activated one - to the 10-strike
--       block by calling store_failed_activation ten times with its VIN;
--     * resetting one's own failed-attempt counter with store_activation_ok, so
--       code guessing was unbounded.
--
-- WHAT
--   * store_hw_id_ok(text): the shape every real id has -
--       VIN-/MAC-/SYS-/BOOT-/SRL-/AID-/N10TEST-... (^[A-Z0-9]{3,8}-[A-Za-z0-9:_.-]{3,80}$)
--       or the literal UNKNOWN (Store ActivationManager.legacyHardwareId).
--   * store_check_in: ignores (returns false) any id that fails the shape gate.
--     Otherwise unchanged, including the unified block from
--     20260903_store_check_in_unified_block.sql.
--   * store_failed_activation: shape gate; and a car that is active+unblocked in
--     public.devices (via resolve_device_id) is never counted - it returns the
--     stored count and writes nothing.
--   * store_activation_ok: shape gate; resets the counter only for an
--     active+unblocked car.
--
-- WHAT CANNOT BREAK
--   * Signatures, return types and grants are byte-identical: no overloads, no 401.
--   * The Store app treats every answer from these three as advisory
--     (ActivationManager.kt: runCatching + getOrDefault(0); clearFailedAttempts
--     discards the result; checkInBlocking treats only `true` as blocked).
--   * A fielded car's real ids all pass the gate (verified live: 0 VIN- rows
--     rejected; N10TEST-, MAC- with colons, and UNKNOWN accepted).
--
-- ROLLBACK: 20260903_store_counter_guards_ROLLBACK.sql (the previous bodies).

create or replace function public.store_hw_id_ok(p_hw_id text)
returns boolean
language sql
immutable
set search_path = public
as $$ select p_hw_id is not null and (p_hw_id ~ '^[A-Z0-9]{3,8}-[A-Za-z0-9:_.-]{3,80}$' or p_hw_id = 'UNKNOWN') $$;
revoke all on function public.store_hw_id_ok(text) from public;

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
    if not public.store_hw_id_ok(p_hw_id) then return false; end if;

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

CREATE OR REPLACE FUNCTION public.store_failed_activation(p_hw_id text, p_car text DEFAULT NULL::text, p_version text DEFAULT NULL::text)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v_count int; v_blocked boolean; v_paid boolean := false;
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return 0; end if;
  if not public.store_hw_id_ok(p_hw_id) then return 0; end if;

  -- A car that already holds a licence cannot be counted toward a block by anyone.
  begin
    select exists (select 1 from public.devices d
                    where d.hardware_id = public.resolve_device_id(p_hw_id)
                      and d.is_active = true and d.is_blocked = false) into v_paid;
  exception when others then v_paid := false; end;
  if v_paid then
    select failed_attempts into v_count from public.store_installs where hw_id = p_hw_id;
    return coalesce(v_count, 0);
  end if;

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
declare v_paid boolean := false;
begin
  if p_hw_id is null or length(p_hw_id) = 0 then return; end if;
  if not public.store_hw_id_ok(p_hw_id) then return; end if;
  begin
    select exists (select 1 from public.devices d
                    where d.hardware_id = public.resolve_device_id(p_hw_id)
                      and d.is_active = true and d.is_blocked = false) into v_paid;
  exception when others then v_paid := false; end;
  if not v_paid then return; end if;
  update public.store_installs
     set failed_attempts = 0, last_failed_at = null
   where hw_id = p_hw_id and blocked = false;
  perform public.store_event_write(p_hw_id, 'activate', null, null, null, null, null, true, 'تفعيل ناجح');
end $function$;
