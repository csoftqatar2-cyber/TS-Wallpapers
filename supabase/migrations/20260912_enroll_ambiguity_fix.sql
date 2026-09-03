-- Applied live as enroll_ambiguity_fix on 2026-09-04 ~02:1x local (Supabase MCP execute_sql).
--
-- WHY (the real cause of every "lost mint" today)
--   cf.enroll_device_impl has parameters named device_hw_id / app_id / app_version_code /
--   serial. Its mirror statements used the bare column names `app_id`, `hardware_id`, and
--   `on conflict (hardware_id, app_id)`. In plpgsql a bare name that matches both a column
--   and a parameter is an ERROR (42702 ambiguous), so:
--     * the token INSERT after a successful D1 mint failed -> the whole RPC errored ->
--       the car received an error, D1 kept a token nobody holds (bench TS Link at 17:51Z,
--       customer car VIN-LG…936782 store token at 18:23Z);
--     * the already_enrolled UPDATE failed the same way.
--   The 3 s statement_timeout theory in 20260910 was wrong (kept: shorter curl timeouts
--   and the reissue path are still right, and the reissue path is what now HEALS the
--   orphaned D1 tokens on the car's next attempt - verified on the bench: TS Link token
--   v4 minted 18:35:05Z and mirrored).
--
-- WHAT
--   Same function, same signature, same logic; `#variable_conflict use_column`, every
--   table reference aliased and qualified, parameters copied into p_/v_ locals, and the
--   upsert targets `on conflict on constraint device_tokens_pkey`.
--
-- LESSON (repo rule): in any plpgsql function whose parameter names equal column names,
--   ALWAYS add `#variable_conflict use_column`, alias every table and qualify every column,
--   and never rely on an exception block to hide a failed write.
--
-- ROLLBACK: re-create from 20260910_enroll_reissue.sql (do not - it is the broken one).

create or replace function cf.enroll_device_impl(device_hw_id text, app_id text, app_version_code int, serial text)
returns text
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw        text;
    d         public.devices;
    p_hw      text := enroll_device_impl.device_hw_id;
    v_app     text := lower(coalesce(nullif(btrim(enroll_device_impl.app_id), ''), 'wallpapers'));
    v_vc      int  := enroll_device_impl.app_version_code;
    v_serial  text := enroll_device_impl.serial;
    min_vc    int;
    win_days  int;
    seen_ok   boolean := false;
    url       text;
    secret    text;
    resp      extensions.http_response;
    meta      json;
    tok       text;
    st        text;
    have_row  boolean;
    tries     int := 0;
begin
    if p_hw is null or p_hw = '' then return null; end if;
    hw := public.resolve_device_id(p_hw);

    select * into d from public.devices dv where dv.hardware_id = hw;
    if not found or not d.is_active or d.is_blocked then return null; end if;

    -- Per-app window: unset or not reached -> nobody enrols.
    select s.value::int into min_vc from cf.settings s where s.key = 'token_min_version_code.' || v_app;
    if min_vc is null or coalesce(v_vc, 0) < min_vc then
        return null;
    end if;
    select s.value::int into win_days from cf.settings s where s.key = 'token_enroll_window_days';
    win_days := coalesce(win_days, 14);

    -- Recency through THIS app: device_ping heartbeat, or the app's historical source.
    seen_ok := exists (select 1 from public.device_app_seen a
                        where a.hardware_id = hw and a.app_id = v_app
                          and a.last_seen >= now() - make_interval(days => win_days));
    if not seen_ok and v_app = 'store' then
        select exists (select 1 from public.store_installs si
                        where si.hw_id in (p_hw, hw)
                          and si.last_seen >= now() - make_interval(days => win_days)) into seen_ok;
    elsif not seen_ok and v_app = 'wallpapers' then
        seen_ok := d.last_seen_at is not null and d.last_seen_at >= now() - make_interval(days => win_days);
    end if;
    if not seen_ok then return null; end if;

    select s.value into url from cf.settings s where s.key = 'worker_enroll_url';
    select ds.decrypted_secret into secret from vault.decrypted_secrets ds where ds.name = 'cf_activation_worker_secret';
    if url is null or secret is null then return null; end if;

    -- Keep the Worker round-trip inside anon's 3 s statement_timeout.
    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '2200');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '1200');
    have_row := exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app);

    loop
        tries := tries + 1;
        begin
            select * into resp from extensions.http((
                'POST', url,
                array[extensions.http_header('Authorization', 'Bearer ' || secret)],
                'application/json',
                json_build_object('hardware_id', hw, 'app_id', v_app, 'app_version_code', v_vc,
                                  'activation_serial', v_serial,
                                  'reissue', (tries = 2))::text
            )::extensions.http_request);
        exception when others then
            raise warning 'enroll_device: % unreachable: %', hw, SQLERRM;
            return null;
        end;
        if resp.status <> 200 then return null; end if;
        meta := resp.content::json;
        st   := meta ->> 'status';
        -- D1 holds a token for this (car, app) but we have no mirror row: a mint whose answer
        -- never reached the car. Ask once for a reissue; the caller already passed every gate.
        if st = 'already_enrolled' and not have_row and tries = 1 then
            continue;
        end if;
        exit;
    end loop;

    -- Mirror of D1's 'enroll_conflict' audit (per app and per car).
    if st = 'already_enrolled' then
        update public.device_tokens t
           set enroll_conflicts = t.enroll_conflicts + 1, last_enroll_conflict_at = now()
         where t.hardware_id = hw and t.app_id = v_app;
        update public.devices dv
           set enroll_conflicts = dv.enroll_conflicts + 1, last_enroll_conflict_at = now()
         where dv.hardware_id = hw;
        return null;
    end if;

    if st not in ('enrolled', 'rotated') or (meta ->> 'token') is null then return null; end if;
    tok := meta ->> 'token';

    -- Mirror the hash (never the token). D1 already decided; mirror unconditionally.
    insert into public.device_tokens as t (hardware_id, app_id, token_hash, token_issued_at, token_version)
    values (hw, v_app, encode(extensions.digest(tok, 'sha256'), 'hex'), now(),
            coalesce((meta ->> 'token_version')::int, 1))
    on conflict on constraint device_tokens_pkey do update
       set token_hash      = excluded.token_hash,
           token_issued_at = excluded.token_issued_at,
           token_version   = greatest(t.token_version + 1, excluded.token_version);
    return tok;
end $function$;
revoke all on function cf.enroll_device_impl(text, text, int, text) from public;
