-- Applied live as device_token_v2 on 2026-09-03 (Supabase MCP apply_migration).
-- Replaces the earlier draft 20260904_device_token_PENDING.sql.
--
-- WHY
--   Activation is proved today by hardware id alone (is_device_activated). A VIN is
--   printed on the windscreen, so anyone with the APK, the public anon key and a
--   photo of a car can answer "yes, I am that car". A per-car secret the attacker
--   cannot read off the glass closes that - for the apps that adopt it.
--
-- WHAT (new NAMES only - rule 1 of ts-backend-rpc-change: never change a live signature)
--   enroll_device(device_hw_id, app_id, app_version_code)               -> text | null
--   activate_device_v2(device_hw_id, activation_serial, legacy_hw_id, app_id, app_version_code) -> jsonb {status, token}
--   is_device_activated_v2(device_hw_id, device_token, app_id, app_version_code) -> boolean
--   D1 DECIDES: the token is minted by the Worker (/v1/devices/enroll, migration
--   0003_device_token.sql) and only its sha256 is mirrored here.
--
-- THE FENCE (the one real hole and how it is closed)
--   A stranger who knows a VIN could try to enrol the victim's car before the genuine
--   unit upgrades. enroll_device therefore issues a token only when:
--     * cf.settings 'token_min_version_code.<app_id>' exists and the caller's
--       app_version_code >= it  (unset = 999999 = nobody enrols yet; the operator opens
--       the window per app when that app's token build is published), and
--     * the car has checked in recently THROUGH THAT APP (store_installs.last_seen for
--       the store; devices.last_seen_at, written by report_device_mode, for the
--       wallpapers app), and
--     * the car is active, unblocked and has no token yet (first come wins, enforced
--       by D1's `WHERE token_hash IS NULL`).
--   A second enrol on an enrolled car is audited by the Worker as 'enroll_conflict' so
--   a lost race is visible to the operator, never silent.
--
-- LOST TOKEN (reinstall / factory reset) - no support call needed
--   activate_device_v2 forwards the activation serial to the Worker ONLY after
--   activate_device answered 'success'. D1 re-checks that serial against the row and
--   ROTATES the token (old one dies). Re-typing the car's own code is the same proof
--   the licence has always rested on. The operator path set-state {reset_token:true}
--   remains for everything else.
--
-- KNOWN LIMIT (state it, do not hide it)
--   TS Back Button has no update channel; cars that only run it stay on v1 for good,
--   and any app still on v1 answers the VIN-only question. The token raises the bar
--   per app as each app adopts it; it does not retire v1 while v1 clients exist.
--
-- WHAT CANNOT BREAK
--   * get_wallpapers / activate_device / is_device_activated / get_device_status are
--     untouched; every fielded APK keeps today's behaviour forever.
--   * No new writer of is_active / is_blocked / failed_attempts.
--   * enroll_device NEVER activates, blocks or renames; NULL means "keep doing what
--     you did", never "you are not licensed". Worker unreachable -> NULL, not error.
--   * New columns on public.devices are invisible to every existing RPC.
--
-- ROLLBACK: drop the three public functions and cf.enroll_device_impl (nothing old
--   calls them); keep the columns.

create extension if not exists pgcrypto with schema extensions;

alter table public.devices add column if not exists token_hash      text;
alter table public.devices add column if not exists token_issued_at timestamptz;
alter table public.devices add column if not exists token_version   int not null default 0;
alter table public.devices add column if not exists token_app_id    text;
create unique index if not exists devices_token_hash_idx
    on public.devices (token_hash) where token_hash is not null;

insert into cf.settings(key, value) values
  ('worker_enroll_url',                 'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/enroll'),
  ('token_enroll_window_days',          '14'),
  ('token_min_version_code.store',      '999999'),
  ('token_min_version_code.wallpapers', '999999'),
  ('token_min_version_code.backbutton', '999999'),
  ('token_min_version_code.tslink',     '999999')
on conflict (key) do nothing;

-- Internal: the one place that talks to the Worker. `serial` is non-null only when the
-- caller is activate_device_v2 right after a 'success' - it unlocks rotation.
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

create or replace function public.enroll_device(device_hw_id text, app_id text default 'wallpapers', app_version_code int default null)
returns text
language sql security definer set search_path to 'public'
as $function$
  select cf.enroll_device_impl(device_hw_id, app_id, app_version_code, null);
$function$;

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
        tok := cf.enroll_device_impl(device_hw_id, app_id, app_version_code, activation_serial);
    end if;
    return jsonb_build_object('status', st, 'token', tok);
end $function$;

create or replace function public.is_device_activated_v2(device_hw_id text, device_token text, app_id text default 'wallpapers', app_version_code int default null)
returns boolean
language sql security definer set search_path to 'public'
as $function$
  select exists (
    select 1 from public.devices d
     where d.hardware_id = public.resolve_device_id(is_device_activated_v2.device_hw_id)
       and d.is_active = true and d.is_blocked = false
       and d.token_hash is not null
       and is_device_activated_v2.device_token is not null
       and d.token_hash = encode(extensions.digest(is_device_activated_v2.device_token, 'sha256'), 'hex')
  );
$function$;

revoke all on function public.enroll_device(text, text, int) from public;
revoke all on function public.activate_device_v2(text, text, text, text, int) from public;
revoke all on function public.is_device_activated_v2(text, text, text, int) from public;
grant execute on function public.enroll_device(text, text, int) to anon, authenticated;
grant execute on function public.activate_device_v2(text, text, text, text, int) to anon, authenticated;
grant execute on function public.is_device_activated_v2(text, text, text, int) to anon, authenticated;
