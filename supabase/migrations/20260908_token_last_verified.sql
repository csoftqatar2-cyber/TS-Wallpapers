-- Applied live as token_last_verified on 2026-09-03 (Supabase MCP execute_sql); stamp fixed 2026-09-04 01:5x
-- (ambiguous app_id inside the UPDATE was silently swallowed - no car had ever been stamped).
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
#variable_conflict use_column
declare
    hw    text;
    v_app text := lower(coalesce(nullif(btrim(is_device_activated_v2.app_id), ''), 'wallpapers'));
    v_tok text := is_device_activated_v2.device_token;
    ok    boolean;
begin
    if v_tok is null or is_device_activated_v2.device_hw_id is null then return false; end if;
    hw := public.resolve_device_id(is_device_activated_v2.device_hw_id);
    select exists (
        select 1
          from public.devices d
          join public.device_tokens t on t.hardware_id = d.hardware_id and t.app_id = v_app
         where d.hardware_id = hw
           and d.is_active = true and d.is_blocked = false
           and t.token_hash = encode(extensions.digest(v_tok, 'sha256'), 'hex')
    ) into ok;
    if ok then
        -- Evidence stamp, at most once an hour. `#variable_conflict use_column` + the alias
        -- matter: the first version wrote `app_id = v_app` unqualified, plpgsql saw the
        -- parameter `app_id`, raised "ambiguous", the exception block swallowed it, and no
        -- car was ever stamped (found on the bench 2026-09-04 01:5x).
        begin
            update public.device_tokens t
               set last_verified_at = now()
             where t.hardware_id = hw and t.app_id = v_app
               and (t.last_verified_at is null or t.last_verified_at < now() - interval '1 hour');
        exception when others then
            raise warning 'is_device_activated_v2: stamp failed for %/%: %', hw, v_app, SQLERRM;
        end;
    end if;
    return coalesce(ok, false);
end $function$;
revoke all on function public.is_device_activated_v2(text, text, text, int) from public;
grant execute on function public.is_device_activated_v2(text, text, text, int) to anon, authenticated;
