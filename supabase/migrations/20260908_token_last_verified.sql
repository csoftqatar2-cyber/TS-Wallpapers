-- Applied live as token_last_verified on 2026-09-03 (Supabase MCP execute_sql).
--
-- WHY
--   The day TOKEN_FALLBACK_TO_V1 is flipped in an app, a car whose token was ISSUED but
--   never USED (file lost to a data wipe, a car parked for weeks) turns into a hard
--   `false`. The cut-over condition must therefore be "every active car holds a token
--   AND we have seen it verify with it", not "we issued tokens to everyone" (point raised
--   by the TS Link chat). This records the evidence.
--
-- WHAT
--   device_tokens.last_verified_at, stamped by is_device_activated_v2 when the token
--   matches - at most once per hour per (car, app) so the check stays cheap. Same
--   signature, same boolean answer; only the side effect is new.
--
-- WHAT CANNOT BREAK
--   * Signature and result of is_device_activated_v2 unchanged (Wallpapers 182 is fielded).
--   * A failed UPDATE can never turn the answer false: the stamp runs after the match
--     inside its own exception block.
--
-- ROLLBACK: re-create is_device_activated_v2 from 20260905_per_app_tokens.sql; keep column.

alter table public.device_tokens add column if not exists last_verified_at timestamptz;

create or replace function public.is_device_activated_v2(device_hw_id text, device_token text, app_id text default 'wallpapers', app_version_code int default null)
returns boolean
language plpgsql security definer set search_path to 'public'
as $function$
declare
    hw    text;
    v_app text := lower(coalesce(nullif(btrim(is_device_activated_v2.app_id), ''), 'wallpapers'));
    ok    boolean;
begin
    if is_device_activated_v2.device_token is null or is_device_activated_v2.device_hw_id is null then return false; end if;
    hw := public.resolve_device_id(is_device_activated_v2.device_hw_id);
    select exists (
        select 1
          from public.devices d
          join public.device_tokens t on t.hardware_id = d.hardware_id and t.app_id = v_app
         where d.hardware_id = hw
           and d.is_active = true and d.is_blocked = false
           and t.token_hash = encode(extensions.digest(is_device_activated_v2.device_token, 'sha256'), 'hex')
    ) into ok;
    if ok then
        begin
            update public.device_tokens
               set last_verified_at = now()
             where hardware_id = hw and app_id = v_app
               and (last_verified_at is null or last_verified_at < now() - interval '1 hour');
        exception when others then
            null;  -- evidence only; never let it cost a car its answer
        end;
    end if;
    return coalesce(ok, false);
end $function$;
revoke all on function public.is_device_activated_v2(text, text, text, int) from public;
grant execute on function public.is_device_activated_v2(text, text, text, int) to anon, authenticated;
