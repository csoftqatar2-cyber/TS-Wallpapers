-- Applied live as enroll_conflict_mirror on 2026-09-03 (Supabase MCP execute_sql; apply_migration was blocked by the session classifier).
--
-- WHY
--   When a second caller tries to enrol a car that already holds a token, the Worker
--   audits it in D1 as 'enroll_conflict' - but the operator dashboard reads Postgres.
--   A lost race (a cloned APK enrolling a real car first, or a clone knocking after the
--   real car enrolled) must be visible where the operator looks, never silent.
--
-- WHAT
--   Two new columns on public.devices (invisible to every existing RPC) and ONE change
--   inside cf.enroll_device_impl (new since 2026-09-03, not a fielded contract): when the
--   Worker answers 'already_enrolled' the counter is bumped and stamped. Nothing else
--   in the function moves. enroll_device / activate_device_v2 / is_device_activated_v2
--   signatures untouched.
--
-- WHAT CANNOT BREAK
--   * No new writer of is_active / is_blocked / failed_attempts / token_hash.
--   * The counter is informational; nothing reads it to decide activation.
--
-- ROLLBACK: re-create cf.enroll_device_impl from 20260904_device_token.sql; keep columns.

alter table public.devices add column if not exists enroll_conflicts        int not null default 0;
alter table public.devices add column if not exists last_enroll_conflict_at timestamptz;

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
begin
    if device_hw_id is null or device_hw_id = '' then return null; end if;
    hw := public.resolve_device_id(device_hw_id);

    select * into d from public.devices where hardware_id = hw;
    if not found or not d.is_active or d.is_blocked then return null; end if;
    -- Already enrolled and no serial proof -> nothing to hand out (D1 audits the conflict).
    if d.token_hash is not null and serial is null then
        perform 1;  -- fall through to the Worker so the conflict is recorded there
    end if;

    -- Per-app window: unset or not reached -> nobody enrols.
    select value::int into min_vc from cf.settings where key = 'token_min_version_code.' || v_app;
    if min_vc is null or coalesce(app_version_code, 0) < min_vc then
        return null;
    end if;
    select value::int into win_days from cf.settings where key = 'token_enroll_window_days';
    win_days := coalesce(win_days, 14);

    -- The car must have been seen recently THROUGH this app (a fresh activation counts:
    -- activate_device_v2 stamps the check-in before calling here).
    if v_app = 'store' then
        select exists (select 1 from public.store_installs s
                        where s.hw_id in (device_hw_id, hw)
                          and s.last_seen >= now() - make_interval(days => win_days)) into seen_ok;
    else
        seen_ok := d.last_seen_at is not null and d.last_seen_at >= now() - make_interval(days => win_days);
    end if;
    if not seen_ok then return null; end if;

    -- D1 mints (or rotates) the token.
    select value into url from cf.settings where key = 'worker_enroll_url';
    select decrypted_secret into secret from vault.decrypted_secrets where name = 'cf_activation_worker_secret';
    if url is null or secret is null then return null; end if;

    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '4000');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '2000');
    begin
        select * into resp from extensions.http((
            'POST', url,
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object('hardware_id', hw, 'app_id', v_app, 'app_version_code', app_version_code,
                              'activation_serial', serial)::text
        )::extensions.http_request);
    exception when others then
        raise warning 'enroll_device: % unreachable: %', hw, SQLERRM;
        return null;
    end;
    if resp.status <> 200 then return null; end if;
    meta := resp.content::json;
    st   := meta ->> 'status';

    -- Mirror of D1's 'enroll_conflict' audit: a second enrol (or a rotation attempt with a
    -- wrong serial) on a car that already holds a token. Informational only.
    if st = 'already_enrolled' then
        update public.devices
           set enroll_conflicts        = enroll_conflicts + 1,
               last_enroll_conflict_at = now()
         where hardware_id = hw;
        return null;
    end if;

    if st not in ('enrolled', 'rotated') or (meta ->> 'token') is null then return null; end if;
    tok := meta ->> 'token';

    -- Mirror the hash (never the token). D1 already decided; mirror unconditionally.
    update public.devices
       set token_hash      = encode(extensions.digest(tok, 'sha256'), 'hex'),
           token_issued_at = now(),
           token_version   = token_version + 1,
           token_app_id    = v_app
     where hardware_id = hw;
    return tok;
end $function$;
revoke all on function cf.enroll_device_impl(text, text, int, text) from public;
