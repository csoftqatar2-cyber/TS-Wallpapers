-- PENDING: not yet applied to the live project (ihgmqwzdpugdzddobhbc).
--
-- C2 (Critical) in Thabthaba Dashboard/reports/security-review-controller-2026-09-15.md:
--   a stranger who knows a customer's VIN can MINT the first token for any (car, app) that
--   has not enrolled yet: device_ping (anon) plants presence, enroll_device (anon, serial =
--   null) passes every gate, D1 `INSERT OR IGNORE`s a token and hands it back. The reissue
--   guard (20260910_enroll_reissue_guard_restored.sql) stops ROTATION without a serial; it
--   never stopped the FIRST mint. Result: free voice on the victim's quota, and the real car
--   later gets already_enrolled and must re-type its code.
--
-- VERDICT: SAFE-TO-APPLY as a file — every policy starts at 'legacy', so no fielded call
--   changes its answer. The gate only OBSERVES (cf.enroll_refusals, enforced = false).
--   ‼️ FLIPPING a policy key to 'serial' is OWNER APPROVAL NEEDED (see STAGING) — it can
--   refuse the first mint of an OLD build on an old activation.
--
-- WHAT (signatures unchanged; nothing dropped; no new writer of is_active/is_blocked)
--   cf.enroll_device_impl — same signature, same body as 20260910_enroll_reissue_guard_restored
--     with ONE inserted block: a first mint (no mirror row) requested WITHOUT a serial is
--     allowed only when
--       (a) devices.activated_at is within token_first_mint_grace_minutes (default 1440):
--           the car has just proved the code. activate_device (v1) refreshes activated_at
--           on every successful code — the controller activates through v1 and calls
--           enroll_device immediately after (DeviceToken "immediate path"), so the
--           legitimate first mint of a freshly-typed code is untouched; or
--       (b) the operator opened a window for that (car, app): admin_open_enroll_window.
--     Otherwise: policy 'legacy'  → proceed as today, log the would-be refusal;
--                policy 'serial'  → return null (same null as every other refusal — an
--                                   anonymous caller must not learn which gate it hit).
--     Rotation (serial present, from activate_device_v2) and the already-enrolled paths
--     are byte-for-byte what they were.
--   cf.settings: token_first_mint_policy.<app> ('legacy' | 'serial' | 'serial_new_builds'),
--     token_first_mint_policy_min_version_code.<app> (used by 'serial_new_builds' only),
--     token_first_mint_grace_minutes.
--   cf.enroll_windows, cf.enroll_refusals (new tables, cf schema — not exposed by PostgREST).
--   public.admin_open_enroll_window(device_hw_id, app_id, minutes, p_note) — dashboard,
--     admin uid only. public.admin_enroll_refusals(p_limit) — dashboard read.
--
-- 'serial_new_builds' IS NOT A SECURITY BOUNDARY. It enforces only when the caller SAYS
--   app_version_code >= the key, and a stranger says whatever he likes. It exists so the
--   owner can watch the new build go through the gate before 'serial' hits every build.
--   The lever that really closes old builds out is the existing token_min_version_code.<app>.
--
-- WHO IS AFFECTED WHEN policy = 'serial' (per app)
--   · A car activated long ago, running an OLD build, that never enrolled this app: its
--     first mint is refused until (1) it updates and re-types the code (activate_device →
--     activated_at refreshed → grace), or (2) the operator opens a window. Count them first:
--     select * from public.admin_enroll_refusals(500)  — every enforced = false row is a
--     car that WOULD be refused today.
--   · Lost-mint recovery (D1 enrolled, no mirror row) on an anonymous call now needs the
--     same grace/window. A car that lost its token normally already shows the code prompt.
--   · Nothing changes for: rotation by code, cars that already hold a mirror row, apps
--     whose key stays 'legacy'.
--
-- STAGING (owner)
--   1. apply this file (observe only);
--   2. after a few days: select * from public.admin_enroll_refusals(500); open windows or
--      plan updates for the legit cars listed;
--   3. update cf.settings set value = 'serial' where key = 'token_first_mint_policy.controller';
--      (one app at a time; the controller first — it is the paid one);
--   4. repeat per app.
--
-- ROLLBACK: 20260924_enroll_first_mint_serial_gate_ROLLBACK.sql (the guard_restored body),
--   or simply set every token_first_mint_policy.* back to 'legacy' — that alone restores
--   today's behaviour without touching the function.

insert into cf.settings(key, value) values
  ('token_first_mint_policy.controller', 'legacy'),
  ('token_first_mint_policy.store',      'legacy'),
  ('token_first_mint_policy.wallpapers', 'legacy'),
  ('token_first_mint_policy.backbutton', 'legacy'),
  ('token_first_mint_policy.tslink',     'legacy'),
  ('token_first_mint_policy.leo',        'legacy'),
  ('token_first_mint_policy_min_version_code.controller', '999999'),
  ('token_first_mint_policy_min_version_code.store',      '999999'),
  ('token_first_mint_policy_min_version_code.wallpapers', '999999'),
  ('token_first_mint_policy_min_version_code.backbutton', '999999'),
  ('token_first_mint_policy_min_version_code.tslink',     '999999'),
  ('token_first_mint_policy_min_version_code.leo',        '999999'),
  ('token_first_mint_grace_minutes', '1440')
on conflict (key) do nothing;

-- Operator-opened windows: "this car may take its first token for this app until …".
create table if not exists cf.enroll_windows (
    hardware_id text        not null,
    app_id      text        not null,
    open_until  timestamptz not null,
    opened_by   uuid,
    opened_at   timestamptz not null default now(),
    note        text,
    primary key (hardware_id, app_id)
);
revoke all on table cf.enroll_windows from public, anon, authenticated;

-- Audit of first mints the gate refused (enforced) or would have refused (observe).
create table if not exists cf.enroll_refusals (
    id               bigint generated always as identity primary key,
    at               timestamptz not null default now(),
    hardware_id      text not null,
    app_id           text not null,
    app_version_code int,
    policy           text,
    enforced         boolean not null,
    reason           text
);
create index if not exists enroll_refusals_at_idx on cf.enroll_refusals (at desc);
revoke all on table cf.enroll_refusals from public, anon, authenticated;

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
    -- C2 gate (2026-09-24)
    v_policy  text;
    v_pol_vc  int;
    v_enforce boolean := false;
    v_grace   int;
    v_allowed boolean;
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

    have_row := exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app);

    -- ── C2 gate (2026-09-24) ─────────────────────────────────────────────────────────────
    -- A FIRST mint asked for WITHOUT the activation serial. Presence (device_ping) is
    -- anonymous and plantable; the only things a stranger cannot fake are a code typed
    -- into the car just now (activated_at) and a window the operator opened by hand.
    if v_serial is null and not have_row then
        select s.value into v_policy from cf.settings s where s.key = 'token_first_mint_policy.' || v_app;
        v_policy := coalesce(v_policy, 'legacy');
        if v_policy = 'serial' then
            v_enforce := true;
        elsif v_policy = 'serial_new_builds' then
            select s.value::int into v_pol_vc from cf.settings s
             where s.key = 'token_first_mint_policy_min_version_code.' || v_app;
            v_enforce := v_pol_vc is not null and coalesce(v_vc, 0) >= v_pol_vc;
        else
            v_enforce := false;
        end if;

        select s.value::int into v_grace from cf.settings s where s.key = 'token_first_mint_grace_minutes';
        v_grace := coalesce(v_grace, 1440);

        v_allowed := (d.activated_at is not null
                      and d.activated_at >= now() - make_interval(mins => v_grace))
                  or exists (select 1 from cf.enroll_windows w
                              where w.hardware_id = hw and w.app_id = v_app
                                and w.open_until >= now());
        if not v_allowed then
            begin
                insert into cf.enroll_refusals (hardware_id, app_id, app_version_code, policy, enforced, reason)
                values (hw, v_app, v_vc, v_policy, v_enforce, 'first_mint_without_serial');
                if random() < 0.01 then
                    delete from cf.enroll_refusals r where r.at < now() - interval '90 days';
                end if;
            exception when others then
                null;   -- the audit row is advisory; the decision below stands
            end;
            if v_enforce then
                return null;   -- same null as every other refusal: reveals nothing
            end if;
        end if;
    end if;
    -- ── end C2 gate ─────────────────────────────────────────────────────────────────────

    select s.value into url from cf.settings s where s.key = 'worker_enroll_url';
    select ds.decrypted_secret into secret from vault.decrypted_secrets ds where ds.name = 'cf_activation_worker_secret';
    if url is null or secret is null then return null; end if;

    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '2200');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '1200');

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

-- Dashboard: open a first-mint window for one (car, app). Admin uid only, as
-- admin_set_device_block (20260907). Never activates, blocks or renames.
create or replace function public.admin_open_enroll_window(device_hw_id text, app_id text,
                                                           minutes int default 60, p_note text default null)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
    hw     text;
    v_app  text := lower(coalesce(nullif(btrim(admin_open_enroll_window.app_id), ''), 'wallpapers'));
    v_min  int  := greatest(1, least(coalesce(admin_open_enroll_window.minutes, 60), 10080));
    v_till timestamptz := now() + make_interval(mins => greatest(1, least(coalesce(admin_open_enroll_window.minutes, 60), 10080)));
begin
    if auth.uid() is distinct from admin_uid then raise exception 'not authorized'; end if;
    if admin_open_enroll_window.device_hw_id is null or btrim(admin_open_enroll_window.device_hw_id) = '' then
        return jsonb_build_object('ok', false, 'reason', 'empty');
    end if;
    hw := public.resolve_device_id(admin_open_enroll_window.device_hw_id);
    if not exists (select 1 from public.devices dv where dv.hardware_id = hw) then
        return jsonb_build_object('ok', false, 'reason', 'unknown');
    end if;
    insert into cf.enroll_windows as w (hardware_id, app_id, open_until, opened_by, note)
    values (hw, left(v_app, 32), v_till, auth.uid(), left(admin_open_enroll_window.p_note, 200))
    on conflict (hardware_id, app_id) do update
       set open_until = excluded.open_until,
           opened_by  = excluded.opened_by,
           opened_at  = now(),
           note       = excluded.note;
    return jsonb_build_object('ok', true, 'hardware_id', hw, 'app_id', v_app, 'open_until', v_till, 'minutes', v_min);
end $function$;
revoke all on function public.admin_open_enroll_window(text, text, int, text) from public, anon;
grant execute on function public.admin_open_enroll_window(text, text, int, text) to authenticated;

-- Dashboard: the refusal log (observe rows have enforced = false).
create or replace function public.admin_enroll_refusals(p_limit int default 200)
returns table (at timestamptz, hardware_id text, app_id text, app_version_code int,
               policy text, enforced boolean, reason text)
language plpgsql security definer set search_path to 'public'
as $function$
declare
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
begin
    if auth.uid() is distinct from admin_uid then raise exception 'not authorized'; end if;
    return query
        select r.at, r.hardware_id, r.app_id, r.app_version_code, r.policy, r.enforced, r.reason
          from cf.enroll_refusals r
         order by r.at desc
         limit greatest(1, least(coalesce(p_limit, 200), 2000));
end $function$;
revoke all on function public.admin_enroll_refusals(int) from public, anon;
grant execute on function public.admin_enroll_refusals(int) to authenticated;

-- VERIFY (after applying; policies still 'legacy')
--   select public.get_enroll_state('<A_REAL_ENROLLED_HARDWARE_ID>', 'controller');  -- 'enrolled'
--   select cf.enroll_device_impl('<A_REAL_ENROLLED_HARDWARE_ID>', 'controller', 165, null); -- null (already_enrolled path, as before)
--   select * from cf.settings where key like 'token_first_mint%' order by key;
