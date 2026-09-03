-- Applied live as device_app_seen on 2026-09-03 (Supabase MCP execute_sql; device_ping re-created once with
-- #variable_conflict use_column after the first version tripped on the app_id name clash).
--
-- WHY
--   enroll_device only hands a token to a car that was seen RECENTLY through the asking
--   app (the "fence" in 20260904_device_token.sql). Today only two apps leave such a
--   trace: the wallpapers app (report_device_mode -> devices.last_seen_at) and the store
--   (store_check_in -> store_installs.last_seen). TS Back Button, TS Link, the controller
--   and TS Leo Dash call only is_device_activated / get_device_status, which write
--   nothing - so they could never pass the gate and never enrol.
--
-- WHAT (new names only)
--   public.device_app_seen(hardware_id, app_id)  - one presence row per (car, app)
--   public.device_ping(device_hw_id, app_id, app_version_code, app_version) -> void
--       A new, cheap, anon-callable heartbeat any app may add next to its licence check.
--       It never activates, blocks or renames; it only stamps presence.
--   cf.enroll_device_impl: recency = the app's existing source OR device_app_seen.
--   public.activate_device_v2: a successful activation also stamps device_app_seen.
--   cf.settings: token_min_version_code.controller / .leo created (999999 = closed).
--
-- WHAT CANNOT BREAK
--   * No fielded RPC changes. No new writer of is_active / is_blocked / failed_attempts.
--   * device_ping for an unknown hardware_id is a no-op (no row is ever created in devices).
--
-- ROLLBACK: drop function public.device_ping; re-create cf.enroll_device_impl and
--   public.activate_device_v2 from 20260905_per_app_tokens.sql / 20260904_device_token.sql;
--   keep the table.

create table if not exists public.device_app_seen (
    hardware_id      text        not null references public.devices(hardware_id) on update cascade on delete cascade,
    app_id           text        not null,
    app_version_code int,
    app_version      text,
    first_seen       timestamptz not null default now(),
    last_seen        timestamptz not null default now(),
    pings            bigint      not null default 1,
    primary key (hardware_id, app_id)
);
create index if not exists device_app_seen_last_seen_idx on public.device_app_seen (app_id, last_seen desc);

alter table public.device_app_seen enable row level security;
drop policy if exists "seen admin read" on public.device_app_seen;
create policy "seen admin read" on public.device_app_seen for select to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select on public.device_app_seen to authenticated;
revoke all on public.device_app_seen from anon;

insert into cf.settings(key, value) values
  ('token_min_version_code.controller', '999999'),
  ('token_min_version_code.leo',        '999999')
on conflict (key) do nothing;

-- Presence heartbeat. Unknown car -> silently nothing (never creates a devices row).
create or replace function public.device_ping(device_hw_id text, app_id text, app_version_code int default null, app_version text default null)
returns void
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw    text;
    v_app text := lower(coalesce(nullif(btrim(device_ping.app_id), ''), 'wallpapers'));
    v_vc  int  := device_ping.app_version_code;
    v_ver text := device_ping.app_version;
begin
    if device_ping.device_hw_id is null or btrim(device_ping.device_hw_id) = '' then return; end if;
    hw := public.resolve_device_id(device_ping.device_hw_id);
    if not exists (select 1 from public.devices where hardware_id = hw) then return; end if;
    insert into public.device_app_seen (hardware_id, app_id, app_version_code, app_version)
    values (hw, left(v_app, 32), v_vc, left(v_ver, 40))
    on conflict (hardware_id, app_id) do update
       set last_seen        = now(),
           pings            = public.device_app_seen.pings + 1,
           app_version_code = coalesce(excluded.app_version_code, public.device_app_seen.app_version_code),
           app_version      = coalesce(excluded.app_version,      public.device_app_seen.app_version);
end $function$;
revoke all on function public.device_ping(text, text, int, text) from public;
grant execute on function public.device_ping(text, text, int, text) to anon, authenticated;

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

    -- Per-app window: unset or not reached -> nobody enrols.
    select value::int into min_vc from cf.settings where key = 'token_min_version_code.' || v_app;
    if min_vc is null or coalesce(app_version_code, 0) < min_vc then
        return null;
    end if;
    select value::int into win_days from cf.settings where key = 'token_enroll_window_days';
    win_days := coalesce(win_days, 14);

    -- The car must have been seen recently THROUGH this app: the app's historical source
    -- (store_installs for the store, devices.last_seen_at for the wallpapers app) or the
    -- per-app heartbeat device_ping writes for every app.
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

    -- D1 mints (or rotates) the token for (car, app).
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

create or replace function public.activate_device_v2(device_hw_id text, activation_serial text, legacy_hw_id text default null,
                                                     app_id text default 'wallpapers', app_version_code int default null)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
declare st text; tok text; hw text;
begin
    st := public.activate_device(device_hw_id, activation_serial, legacy_hw_id);
    if st = 'success' then
        hw := public.resolve_device_id(device_hw_id);
        -- A fresh, successful activation is itself proof of presence: stamp the check-in
        -- so the recency gate passes for this app, then let D1 mint or rotate.
        if lower(coalesce(app_id,'')) = 'store' then
            insert into public.store_installs (hw_id) values (device_hw_id)
            on conflict (hw_id) do update set last_seen = now();
        else
            update public.devices set last_seen_at = now() where hardware_id = hw;
        end if;
        perform public.device_ping(device_hw_id, app_id, app_version_code, null);
        tok := cf.enroll_device_impl(device_hw_id, app_id, app_version_code, activation_serial);
    end if;
    return jsonb_build_object('status', st, 'token', tok);
end $function$;
revoke all on function public.activate_device_v2(text, text, text, text, int) from public;
grant execute on function public.activate_device_v2(text, text, text, text, int) to anon, authenticated;
