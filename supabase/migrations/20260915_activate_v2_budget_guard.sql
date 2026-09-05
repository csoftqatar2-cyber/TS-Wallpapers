-- Applied live as activate_v2_budget_guard on 2026-09-05 (Supabase MCP execute_sql).
--
-- WHY (a real car, 2026-09-04 21:04-21:05Z)
--   activate_device_v2 = activate_device (Worker /activate + D1, measured ~2.9 s cold) + an
--   inline enroll_device_impl (Worker /enroll). anon's statement_timeout is 3 s. Four real
--   activation attempts answered HTTP 500 (origin 3.2-3.3 s, "canceling statement due to
--   statement timeout"); one squeezed through at 2.95 s. The store client falls back to v1 on
--   a 5xx, but TS Wallpapers 183 throws on any non-404 error - the customer sees "failed"
--   even though the car IS activated on the server.
--
-- WHAT
--   Same signature, same answers. The inline enrol runs only when < 1.4 s have elapsed since the
--   statement started; otherwise 'success' is returned with token=null and the app's daily
--   enroll_device (its own 3 s statement) fetches the token. Token rotation by re-typing the
--   code still works whenever the Worker is warm.
--
-- ROLLBACK: re-create public.activate_device_v2 from 20260906_device_app_seen.sql.
-- FOLLOW-UP: wallpapers 184 must fall back to activate_device (v1) on any non-2xx from v2.

create or replace function public.activate_device_v2(device_hw_id text, activation_serial text, legacy_hw_id text default null,
                                                     app_id text default 'wallpapers', app_version_code int default null)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    st         text;
    tok        text;
    hw         text;
    v_app      text := lower(coalesce(nullif(btrim(activate_device_v2.app_id), ''), 'wallpapers'));
    elapsed_ms numeric;
begin
    st := public.activate_device(activate_device_v2.device_hw_id, activate_device_v2.activation_serial, activate_device_v2.legacy_hw_id);
    if st = 'success' then
        hw := public.resolve_device_id(activate_device_v2.device_hw_id);
        if v_app = 'store' then
            insert into public.store_installs (hw_id) values (activate_device_v2.device_hw_id)
            on conflict (hw_id) do update set last_seen = now();
        else
            update public.devices dv set last_seen_at = now() where dv.hardware_id = hw;
        end if;
        perform public.device_ping(activate_device_v2.device_hw_id, v_app, activate_device_v2.app_version_code, null);

        -- Budget guard: only ask D1 for the token when enough of the 3 s statement budget remains.
        elapsed_ms := extract(epoch from (clock_timestamp() - statement_timestamp())) * 1000;
        if elapsed_ms < 1400 then
            tok := cf.enroll_device_impl(activate_device_v2.device_hw_id, v_app, activate_device_v2.app_version_code, activate_device_v2.activation_serial);
        end if;
    end if;
    return jsonb_build_object('status', st, 'token', tok);
end $function$;
revoke all on function public.activate_device_v2(text, text, text, text, int) from public;
grant execute on function public.activate_device_v2(text, text, text, text, int) to anon, authenticated;
