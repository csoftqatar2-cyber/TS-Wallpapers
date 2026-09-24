-- ############################################################################################
-- ##  SUPERSEDED — DO NOT APPLY (decision 2026-09-16, after comparing the two parallel C2   ##
-- ##  drafts). The C2 fix to apply is 20260924_enroll_first_mint_serial_gate.sql (+ its     ##
-- ##  _ROLLBACK.sql); its M9 companion is 20260924_device_status_rate_limit.sql.            ##
-- ##  WHY: this file ENFORCES at apply time (token_first_mint_guard.controller='activation', ##
-- ##  6 h window) and rewrites get_enroll_state to answer 'enrolled' for cars that hold no  ##
-- ##  token — refusing, the moment it lands, every fielded controller with no token whose  ##
-- ##  last code is older than 6 h, with no prompt at all on builds < 113 (2.10 … 2.12.x).  ##
-- ##  serial_gate ships observe-only ('legacy'), logs the would-be refusals, and is flipped ##
-- ##  per app by the owner. Applying BOTH would re-create cf.enroll_device_impl,             ##
-- ##  get_enroll_state and get_device_status twice with conflicting rules. Kept for the     ##
-- ##  prompt-bridge idea only; nothing in it is to be run.                                  ##
-- ############################################################################################
--
-- PENDING — NOT applied. Prepared 2026-09-16 for security review C2 + M9 of the controller
-- (Thabthaba Dashboard/reports/security-review-controller-2026-09-15.md). Dry-run in PGlite
-- (Postgres 18) against a schema rebuilt from these migrations, inside BEGIN … ROLLBACK; the
-- harness is in that repo's session notes (notes-B.md). Apply with the Supabase MCP / SQL editor
-- as `enroll_first_mint_guard`, then run the smoke checks at the bottom.
--
-- WHY (C2 — the real hole)
--   public.enroll_device is anon-callable and passes serial = NULL. The reissue guard restored on
--   2026-09-10 stops a stranger ROTATING a token; nothing stopped the FIRST mint. For a licensed
--   car that has not yet enrolled an app, anyone who reads its VIN off the windscreen can call
--   device_ping(VIN,'controller') then enroll_device(VIN,'controller',165) and walk away with a
--   live voice token: paid voice on the customer's quota, and the genuine car is refused as
--   already_enrolled when it arrives. «A stranger with the VIN alone can never rotate» is true;
--   «…can never enrol» was never true.
--
-- WHAT THE FIELD SENDS (verified in source + git history on 2026-09-16)
--   controller 2.4 (15) … 2.30.0 (165): activate_device (v1) → on success device_ping +
--     enroll_device(hw,'controller',vc). NO controller build ever sends the serial to the enrol
--     path (activate_device_v2 was never adopted there). Since 2.13.8 (113) an enrol refusal is
--     followed by get_enroll_state, and the literal answer 'enrolled' opens the activation-code
--     screen (DeviceToken.java:291, MainActivity.needsActivationCode).
--   wallpapers ≥184, store ≥99: activate_device_v2 (serial in-band) for the typed-code path and
--     anon enroll_device for the routine path. Neither calls get_enroll_state.
--   Back Button, Leo: no token at all (get_device_status / device_ping only).
--
-- THE ONE PROOF AN ANONYMOUS CALLER CANNOT FORGE
--   The activation serial — and on the anonymous path it exists only as a TIMESTAMP: every
--   activate_device body (v1 legacy, v1 dispatcher, v2) refreshes devices.activated_at on an
--   accepted code, including a car re-typing its own code. So:
--
--     an anonymous FIRST mint (no device_tokens row, serial = NULL) is honoured only while
--     devices.activated_at is within token_first_mint_activation_window_min (default 360 min).
--
--   Outside the window the caller gets NULL, like every other refusal. activate_device_v2 (serial
--   in-band, verified by D1) is untouched — it is the strict path already. Per-app switch
--   token_first_mint_guard.<app>: 'activation' for the controller (the paid service), 'off' for
--   the others, whose tokens gate nothing today (token_enforce_min_version_code = 999999).
--
-- WHAT OLD VERSIONS LOSE (stated, not hidden)
--   A car whose controller holds NO token and whose last accepted code is older than the window
--   can no longer enrol anonymously. That set is exactly the C2 target set, and it contains a few
--   legitimate cars: licensed through a sibling app long ago, controller installed later, never
--   enrolled (or its data wiped and the daemon copy lost). For them:
--     * controller ≥ 2.13.8 (113): get_enroll_state answers 'enrolled' (bridge below) → the panel
--       shows the activation-code screen → the code refreshes activated_at → the immediate enrol
--       that follows a typed code (DeviceToken.ensure(app,false)) passes the window. Self-heals
--       with one code entry; the car keeps every reading and command meanwhile.
--     * controller 15 … 112: no prompt exists; voice stays «غير جاهزة» until the app self-updates
--       (it has an update channel) or the owner re-types the code from the settings screen.
--   Cars that already hold a token, cars activated from the controller itself, and cars set up in
--   one sitting (store first, controller within the window) see no change at all.
--
-- WHY get_enroll_state SAYS 'enrolled' FOR A GUARDED CAR
--   The client rule written into 20260910_get_enroll_state.sql is «token missing locally AND
--   'enrolled' → show the activation code prompt». That prompt is the one road out for a guarded
--   car too, and a NEW word would be ignored by every fielded controller. So while
--   token_first_mint_prompt_code = 'on', a (car, app) with no token row that the guard would
--   refuse answers 'enrolled'. Nothing about the token is exposed either way; only which screen
--   the car shows changes. Set the key to 'off' to restore the literal meaning.
--
-- M9 (VIN oracle on get_device_status / get_enroll_state)
--   Both are anon-callable and answer per VIN, so a VIN list can be turned into a customer list.
--   cf.probe_hits counts DISTINCT hardware ids per source IP per hour; when
--   status_probe_ip_hourly_limit > 0 the (limit+1)th id from one IP in one hour raises
--   'rate_limited' (the first `limit` ids keep working — see probe_limited). Every fielded client
--   treats an error as «keep what you had», never as a negative: controller Activation.ask
--   (catch → nothing), wallpapers fetchStatus (→ null), Back Button deviceStatus (→ UNKNOWN), Leo
--   status() (throws). The limit ships at 0 = COLLECT ONLY: cars on mobile data sit behind
--   carrier NAT, so the real ids-per-IP distribution must be measured before a number is chosen
--   (query at the bottom). thab_overlay_seen (same finding) lives in the controller repo's own
--   migrations and is not touched here.
--
-- H2-style visibility
--   cf.enroll_guard_log records every anonymous first-mint decision (minted / reissued / refused)
--   with the source IP; public.admin_enroll_guard_log(p_limit) reads it for the admin session.
--
-- WHAT CANNOT BREAK
--   * No signature changes: enroll_device, activate_device_v2, is_device_activated_v2,
--     get_device_status, get_enroll_state, device_ping keep their exact shapes and words.
--     (get_enroll_state loses STABLE — it now writes a probe row — which PostgREST ignores.)
--   * No new writer of is_active / is_blocked / failed_attempts / activated_at / serial_number.
--   * Every new gate fails CLOSED for the stranger and OPEN for the fleet's cached state: an enrol
--     refusal is NULL, a probe refusal is an error — never a 'blocked' / 'inactive' word.
--   * The audit inserts sit in their own exception blocks: a log that cannot be written must not
--     turn a mint into an error (the mirror write itself is NOT wrapped — the 20260912 lesson).
--   * plpgsql hygiene throughout: #variable_conflict use_column, every table aliased, every
--     column qualified.
--
-- ROLLBACK (fast, no code):
--   update cf.settings set value = 'off' where key like 'token_first_mint_guard.%';
--   update cf.settings set value = '0'   where key = 'status_probe_ip_hourly_limit';
-- ROLLBACK (full): re-create cf.enroll_device_impl from 20260910_enroll_reissue_guard_restored.sql,
--   public.get_enroll_state from 20260910_get_enroll_state.sql, public.get_device_status from
--   20260816_get_device_status.sql; drop function public.admin_enroll_guard_log(int); keep tables.

-- ---------------------------------------------------------------------------------------------
-- Settings. ON CONFLICT DO NOTHING: re-running never resets a value the owner has changed.
-- ---------------------------------------------------------------------------------------------
insert into cf.settings(key, value) values
  ('token_first_mint_guard.controller',        'activation'),
  ('token_first_mint_guard.wallpapers',        'off'),
  ('token_first_mint_guard.store',             'off'),
  ('token_first_mint_guard.tslink',            'off'),
  ('token_first_mint_guard.backbutton',        'off'),
  ('token_first_mint_guard.leo',               'off'),
  ('token_first_mint_activation_window_min',   '360'),
  ('token_first_mint_prompt_code',             'on'),
  ('status_probe_ip_hourly_limit',             '0')
on conflict (key) do nothing;

-- ---------------------------------------------------------------------------------------------
-- Tables (schema cf: not exposed by PostgREST, no anon/authenticated usage).
-- ---------------------------------------------------------------------------------------------
create table if not exists cf.enroll_guard_log (
    id               bigserial   primary key,
    at               timestamptz not null default now(),
    hardware_id      text,
    app_id           text,
    outcome          text        not null,   -- minted_anon | reissued_anon | refused_first_mint
    ip               text,
    app_version_code int,
    activated_at     timestamptz
);
create index if not exists enroll_guard_log_at_idx on cf.enroll_guard_log (at desc);

create table if not exists cf.probe_hits (
    ip          text        not null,
    hour        timestamptz not null,
    hardware_id text        not null,
    primary key (ip, hour, hardware_id)
);

-- ---------------------------------------------------------------------------------------------
-- Helpers.
-- ---------------------------------------------------------------------------------------------

-- The caller's address as PostgREST hands it over, or NULL when it cannot be read (then nothing
-- is limited — an unreadable header must not become a fleet-wide refusal).
create or replace function cf.request_ip()
returns text
language plpgsql stable
as $function$
declare
    h    jsonb;
    v_ip text;
begin
    begin
        h := nullif(current_setting('request.headers', true), '')::jsonb;
    exception when others then
        return null;
    end;
    if h is null then return null; end if;
    v_ip := coalesce(nullif(btrim(h ->> 'cf-connecting-ip'), ''),
                     nullif(btrim(split_part(coalesce(h ->> 'x-forwarded-for', ''), ',', 1)), ''));
    return left(v_ip, 64);
end $function$;
revoke all on function cf.request_ip() from public;

-- TRUE when an anonymous first mint for p_app must be refused: the app's switch is 'activation'
-- and the car's last accepted code is older than the window (or it never had one).
create or replace function cf.first_mint_guarded(p_app text, p_activated_at timestamptz)
returns boolean
language plpgsql stable
as $function$
declare
    v_mode text;
    v_win  int;
begin
    select s.value into v_mode from cf.settings s where s.key = 'token_first_mint_guard.' || p_app;
    if coalesce(v_mode, 'off') <> 'activation' then return false; end if;
    select s.value::int into v_win from cf.settings s where s.key = 'token_first_mint_activation_window_min';
    v_win := coalesce(v_win, 360);
    return p_activated_at is null or p_activated_at < now() - make_interval(mins => v_win);
end $function$;
revoke all on function cf.first_mint_guarded(text, timestamptz) from public;

-- Audit row for the anonymous first-mint path. Own exception block: never lets a mint fail.
create or replace function cf.enroll_guard_note(p_hw text, p_app text, p_outcome text, p_vc int, p_activated_at timestamptz)
returns void
language plpgsql
as $function$
begin
    insert into cf.enroll_guard_log (hardware_id, app_id, outcome, ip, app_version_code, activated_at)
    values (p_hw, p_app, p_outcome, cf.request_ip(), p_vc, p_activated_at);
exception when others then
    raise warning 'enroll_guard_note: % % %: %', p_hw, p_app, p_outcome, SQLERRM;
end $function$;
revoke all on function cf.enroll_guard_note(text, text, text, int, timestamptz) from public;

-- Records one (ip, hour, hardware id) and says whether this IP has now asked about MORE distinct
-- ids this hour than status_probe_ip_hourly_limit allows. 0 / unset = count only, never limit.
-- Called inside a nested block by the RPCs, so any failure here reads as "not limited".
-- When the caller then raises, this insert rolls back with it — which is what keeps the first
-- `limit` ids working: they are already on file, re-asking about them adds no row.
create or replace function cf.probe_limited(p_hw text)
returns boolean
language plpgsql
as $function$
declare
    v_ip  text;
    v_h   timestamptz;
    v_lim int;
    v_n   bigint;
begin
    v_ip := cf.request_ip();
    if v_ip is null or p_hw is null or btrim(p_hw) = '' then return false; end if;
    v_h := date_trunc('hour', now());
    insert into cf.probe_hits as ph (ip, hour, hardware_id)
    values (v_ip, v_h, left(p_hw, 128))
    on conflict on constraint probe_hits_pkey do nothing;
    -- Opportunistic sweep: ~1 call in 50 clears rows older than three hours.
    if random() < 0.02 then
        delete from cf.probe_hits ph where ph.hour < now() - interval '3 hours';
    end if;
    select s.value::int into v_lim from cf.settings s where s.key = 'status_probe_ip_hourly_limit';
    if coalesce(v_lim, 0) <= 0 then return false; end if;
    select count(*) into v_n from cf.probe_hits ph where ph.ip = v_ip and ph.hour = v_h;
    return v_n > v_lim;
end $function$;
revoke all on function cf.probe_limited(text) from public;

-- ---------------------------------------------------------------------------------------------
-- cf.enroll_device_impl — the live body of 2026-09-10 (enroll_reissue_guard_restored) plus the
-- first-mint guard and the audit notes. Nothing else moved.
-- ---------------------------------------------------------------------------------------------
create or replace function cf.enroll_device_impl(device_hw_id text, app_id text, app_version_code integer, serial text)
 returns text
 language plpgsql
 security definer
 set search_path to 'public'
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

    select s.value::int into min_vc from cf.settings s where s.key = 'token_min_version_code.' || v_app;
    if min_vc is null or coalesce(v_vc, 0) < min_vc then
        return null;
    end if;
    select s.value::int into win_days from cf.settings s where s.key = 'token_enroll_window_days';
    win_days := coalesce(win_days, 14);

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

    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '2200');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '1200');
    have_row := exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app);

    -- C2 guard (2026-09-16). An anonymous caller (serial = NULL) asking for a FIRST token — no
    -- mirror row here, so either a true first mint or the lost-mint reissue below — is honoured
    -- only while this car's last accepted activation code is recent. The serial is the only
    -- proof a stranger with the VIN cannot produce, and on this path it is present only as
    -- devices.activated_at. Refused = NULL, the same answer as every other gate, so nothing is
    -- learnt; the decision itself is logged for the owner.
    if v_serial is null and not have_row and cf.first_mint_guarded(v_app, d.activated_at) then
        perform cf.enroll_guard_note(hw, v_app, 'refused_first_mint', v_vc, d.activated_at);
        return null;
    end if;

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
        -- Lost-mint recovery ONLY: D1 says enrolled but Postgres holds no mirror row, so the
        -- mint happened and its answer never reached the car. When Postgres DOES hold a row the
        -- caller is either a stranger with the VIN or a car that must re-type its activation
        -- code (activate_device rotates by serial). Never reissue on an anonymous call then.
        if st = 'already_enrolled' and not have_row and tries = 1 then
            continue;
        end if;
        exit;
    end loop;

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

    -- The anonymous mints that DID pass (inside the window) are the ones worth a second look.
    if v_serial is null then
        perform cf.enroll_guard_note(hw, v_app, case when st = 'rotated' then 'reissued_anon' else 'minted_anon' end,
                                     v_vc, d.activated_at);
    end if;

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

-- ---------------------------------------------------------------------------------------------
-- public.get_enroll_state — same signature and words; adds the probe and the bridge.
-- STABLE dropped (probe_limited writes). Answers exactly as before for every unguarded app.
-- ---------------------------------------------------------------------------------------------
create or replace function public.get_enroll_state(device_hw_id text, app_id text default 'wallpapers')
returns text
language plpgsql
security definer
set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw       text;
    p_hw     text := get_enroll_state.device_hw_id;
    v_app    text := lower(coalesce(nullif(btrim(get_enroll_state.app_id), ''), 'wallpapers'));
    d        public.devices;
    limited  boolean := false;
    v_prompt text;
begin
    if p_hw is null or p_hw = '' then return 'unknown'; end if;

    -- M9: count this (ip, id); refuse only past the owner's limit, and never on a probe failure.
    begin
        limited := cf.probe_limited(p_hw);
    exception when others then
        limited := false;
    end;
    if limited then
        raise exception 'rate_limited' using errcode = 'P0001';
    end if;

    hw := public.resolve_device_id(p_hw);
    select * into d from public.devices dv where dv.hardware_id = hw;
    if not found then return 'unknown'; end if;
    if d.is_blocked then return 'blocked'; end if;
    if not d.is_active then return 'inactive'; end if;
    if exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app) then
        return 'enrolled';
    end if;
    -- The bridge (see header): a car the first-mint guard would refuse is told the one thing every
    -- fielded controller acts on — «present the activation code» — spelled 'enrolled'.
    select s.value into v_prompt from cf.settings s where s.key = 'token_first_mint_prompt_code';
    if coalesce(v_prompt, 'off') = 'on' and cf.first_mint_guarded(v_app, d.activated_at) then
        return 'enrolled';
    end if;
    return 'not_enrolled';
end $function$;
revoke all on function public.get_enroll_state(text, text) from public;
grant execute on function public.get_enroll_state(text, text) to anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- public.get_device_status — the 20260816 body, unchanged in every answer, plus the probe.
-- ---------------------------------------------------------------------------------------------
create or replace function public.get_device_status(device_hw_id text, legacy_hw_id text default null)
returns text
language plpgsql
security definer
set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    d        public.devices;
    p_hw     text := get_device_status.device_hw_id;
    p_legacy text := get_device_status.legacy_hw_id;
    limited  boolean := false;
begin
    begin
        limited := cf.probe_limited(p_hw);
    exception when others then
        limited := false;
    end;
    if limited then
        raise exception 'rate_limited' using errcode = 'P0001';
    end if;

    select * into d from public.devices dv
     where dv.hardware_id = public.resolve_device_id(p_hw);

    if not found and p_legacy is not null and p_legacy <> '' then
        select * into d from public.devices dv
         where dv.hardware_id = public.resolve_device_id(p_legacy);
    end if;

    if not found      then return 'unknown';  end if;  -- never registered
    if d.is_blocked   then return 'blocked';  end if;  -- blocked: no serial will help
    if d.is_active    then return 'active';   end if;
    return 'inactive';                                  -- registered, activation withdrawn
end $function$;
revoke all on function public.get_device_status(text, text) from public;
grant execute on function public.get_device_status(text, text) to anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- Admin read of the guard log (dashboard session only, same uid rule as admin_set_device_block).
-- ---------------------------------------------------------------------------------------------
create or replace function public.admin_enroll_guard_log(p_limit int default 200)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
    v_out jsonb;
begin
    if auth.uid() is distinct from admin_uid then
        raise exception 'not authorized';
    end if;
    select coalesce(jsonb_agg(to_jsonb(g) order by g.at desc), '[]'::jsonb) into v_out
      from (select * from cf.enroll_guard_log l order by l.at desc limit greatest(1, least(coalesce(p_limit, 200), 2000))) g;
    return v_out;
end $function$;
revoke all on function public.admin_enroll_guard_log(int) from public, anon;
grant execute on function public.admin_enroll_guard_log(int) to authenticated;

-- ---------------------------------------------------------------------------------------------
-- SMOKE CHECKS after apply (read-only, run as the admin / SQL editor):
--   select key, value from cf.settings where key like 'token_first_mint%' or key = 'status_probe_ip_hourly_limit';
--   select outcome, count(*) from cf.enroll_guard_log where at > now() - interval '1 day' group by 1;
-- Cars that WOULD now be refused (licensed, controller-seen, no controller token, code older than
-- the window) — the ones that will get the code prompt:
--   select d.hardware_id, d.activated_at, a.last_seen, a.app_version_code
--     from public.devices d
--     join public.device_app_seen a on a.hardware_id = d.hardware_id and a.app_id = 'controller'
--    where d.is_active and not d.is_blocked
--      and not exists (select 1 from public.device_tokens t where t.hardware_id = d.hardware_id and t.app_id = 'controller')
--      and (d.activated_at is null or d.activated_at < now() - interval '360 minutes')
--    order by a.last_seen desc;
-- M9 sizing, after a day of collecting (pick a limit well above the busiest legitimate IP):
--   select ip, hour, count(*) as ids from cf.probe_hits group by 1, 2 order by ids desc limit 20;
--   then: update cf.settings set value = '<N>' where key = 'status_probe_ip_hourly_limit';
-- ---------------------------------------------------------------------------------------------
