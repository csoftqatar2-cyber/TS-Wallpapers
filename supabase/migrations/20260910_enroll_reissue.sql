-- Applied live as enroll_reissue on 2026-09-03 (Supabase MCP execute_sql).
--
-- WHY (found on the bench with TS Link 0.1.52)
--   anon's statement_timeout is 3 s. cf.enroll_device_impl called the Worker with a 4 s
--   curl timeout: D1 minted the token, then Postgres hit its own timeout before mirroring
--   and answering - the car got an error, D1 says "enrolled", and every later enrol for that
--   (car, app) would have been refused as a conflict. A token nobody holds.
--
-- WHAT
--   * curl timeouts 2200/1200 ms so the round-trip stays inside anon's 3 s.
--   * Lost-mint recovery: when the Worker answers already_enrolled for a (car, app) that has
--     NO mirror row here, ask ONCE more with reissue=true; the Worker (version 41c484d8+,
--     audit action 'reissue_token') rotates the dead token and hands the caller - who already
--     passed the version and recency gates - a live one. A clone attempt on an app that IS
--     mirrored still gets already_enrolled + the conflict counters.
--   Signature unchanged.
--
-- ROLLBACK: re-create cf.enroll_device_impl from 20260906_device_app_seen.sql.

create or replace function cf.enroll_device_impl(device_hw_id text, app_id text, app_version_code int, serial text)
returns text
language plpgsql security definer set search_path to 'public'
as $function$
declare
    hw        text;
    d         public.devices;
    v_app     text := lower(coalesce(nullif(btrim(app_id), ''), 'wallpapers'));
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
    if device_hw_id is null or device_hw_id = '' then return null; end if;
    hw := public.resolve_device_id(device_hw_id);

    select * into d from public.devices where hardware_id = hw;
    if not found or not d.is_active or d.is_blocked then return null; end if;

    -- Per-app window: unset or not reached -> nobody enrols.
    select value::int into min_vc from cf.settings where key = 'token_min_version_code.' || v_app;
    if min_vc is null or coalesce(app_version_code, 0) < min_vc then
        return null;
    end if;
    select value::int into win_days from cf.settings where key = 'token_enroll_window_days';
    win_days := coalesce(win_days, 14);

    -- Recency through THIS app: device_ping heartbeat, or the app's historical source.
    seen_ok := exists (select 1 from public.device_app_seen a
                        where a.hardware_id = hw and a.app_id = v_app
                          and a.last_seen >= now() - make_interval(days => win_days));
    if not seen_ok and v_app = 'store' then
        select exists (select 1 from public.store_installs s
                        where s.hw_id in (device_hw_id, hw)
                          and s.last_seen >= now() - make_interval(days => win_days)) into seen_ok;
    elsif not seen_ok and v_app = 'wallpapers' then
        seen_ok := d.last_seen_at is not null and d.last_seen_at >= now() - make_interval(days => win_days);
    end if;
    if not seen_ok then return null; end if;

    select value into url from cf.settings where key = 'worker_enroll_url';
    select decrypted_secret into secret from vault.decrypted_secrets where name = 'cf_activation_worker_secret';
    if url is null or secret is null then return null; end if;

    -- anon's statement_timeout is 3s: keep the Worker call well inside it so Postgres never
    -- gives up AFTER D1 has already minted (the lost-mint case the reissue below repairs).
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
                json_build_object('hardware_id', hw, 'app_id', v_app, 'app_version_code', app_version_code,
                                  'activation_serial', serial,
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
        update public.device_tokens
           set enroll_conflicts = enroll_conflicts + 1, last_enroll_conflict_at = now()
         where hardware_id = hw and app_id = v_app;
        update public.devices
           set enroll_conflicts = enroll_conflicts + 1, last_enroll_conflict_at = now()
         where hardware_id = hw;
        return null;
    end if;

    if st not in ('enrolled', 'rotated') or (meta ->> 'token') is null then return null; end if;
    tok := meta ->> 'token';

    -- Mirror the hash (never the token). D1 already decided; mirror unconditionally.
    insert into public.device_tokens (hardware_id, app_id, token_hash, token_issued_at, token_version)
    values (hw, v_app, encode(extensions.digest(tok, 'sha256'), 'hex'), now(),
            coalesce((meta ->> 'token_version')::int, 1))
    on conflict (hardware_id, app_id) do update
       set token_hash      = excluded.token_hash,
           token_issued_at = excluded.token_issued_at,
           token_version   = greatest(public.device_tokens.token_version + 1, excluded.token_version);
    return tok;
end $function$;
revoke all on function cf.enroll_device_impl(text, text, int, text) from public;
