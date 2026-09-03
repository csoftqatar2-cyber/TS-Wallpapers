-- PENDING: not yet applied to the live project. DO NOT APPLY before the Worker
-- endpoints (/v1/devices/enroll, /v1/devices/verify, set-state reset_token) and
-- the D1 migration 0003_device_token.sql are live, and before at least one app
-- build that sends app_version_code exists. Applying enroll_device early would let
-- a stranger enrol a victim's car before the real unit upgrades (see WINDOW below).
--
-- WHY
--   Activation is proved today by hardware id alone (is_device_activated,
--   schema.sql). A VIN is printed on the windscreen, so anyone with the APK, the
--   public anon key and a photo of a car can answer "yes, I am that car". A
--   per-car secret the attacker cannot read off the glass closes that.
--
-- WHAT
--   New NAMES only — enroll_device / activate_device_v2 / is_device_activated_v2.
--   Rule 1 of ts-backend-rpc-change: never change a live signature. D1 decides
--   (token_hash is written there first by the Worker); these columns MIRROR it.
--
-- WINDOW (the one real hole and its fence)
--   enroll_device only issues a token when the row already reports
--   app_version_code >= cf.settings 'token_min_version_code' — i.e. the genuine
--   car has checked in on a build that knows about tokens — and only within
--   'token_enroll_window_days' of that check-in. A stranger with an old-build
--   car's VIN cannot enrol it; the real unit enrols on its first launch after
--   upgrade, and the window closes behind it.
--
-- WHAT CANNOT BREAK
--   * get_wallpapers / activate_device / is_device_activated / get_device_status
--     are not touched. Every fielded APK keeps today's behaviour forever.
--   * No new writer of is_active / is_blocked / failed_attempts.
--   * enroll_device NEVER activates, blocks, or renames anything; NULL means
--     "keep doing what you did", never "you are not licensed".
--   * Adding columns to public.devices is invisible to every existing RPC.
--
-- ROLLBACK: drop the three functions (nothing old calls them); keep the columns
--   (dropping them would orphan tokens already issued to enrolled cars).

create extension if not exists pgcrypto with schema extensions;

alter table public.devices add column if not exists token_hash      text;
alter table public.devices add column if not exists token_issued_at timestamptz;
alter table public.devices add column if not exists token_version   int not null default 0;
create unique index if not exists devices_token_hash_idx
    on public.devices (token_hash) where token_hash is not null;

insert into cf.settings(key, value) values
  ('worker_enroll_url',          'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/enroll'),
  ('worker_verify_url',          'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/verify'),
  ('token_min_version_code',     '999999'),   -- set to the first token-aware build per app before enabling
  ('token_enroll_window_days',   '14')
on conflict (key) do nothing;

-- Issue this car's token, exactly once. Returns the plaintext ONCE, or NULL if
-- the car already has one (first come wins), is not active+unblocked, or is
-- outside the enrolment window.
create or replace function public.enroll_device(device_hw_id text, app_version_code int default null)
returns text
language plpgsql security definer set search_path to 'public'
as $function$
declare
    hw       text;
    tok      text;
    d        public.devices;
    min_vc   int;
    win_days int;
begin
    hw := public.resolve_device_id(device_hw_id);
    if hw is null then return null; end if;

    select * into d from public.devices where hardware_id = hw;
    if not found or not d.is_active or d.is_blocked or d.token_hash is not null then
        return null;
    end if;

    select value::int into min_vc   from cf.settings where key = 'token_min_version_code';
    select value::int into win_days from cf.settings where key = 'token_enroll_window_days';
    -- The car must have checked in on a token-aware build, recently. Anything else
    -- is a stranger (or a car that has not upgraded yet): answer NULL, change nothing.
    if d.app_version_code is null or d.app_version_code < coalesce(min_vc, 999999)
       or coalesce(app_version_code, 0) < coalesce(min_vc, 999999)
       or d.last_seen_at is null
       or d.last_seen_at < now() - make_interval(days => coalesce(win_days, 14)) then
        return null;
    end if;

    tok := encode(extensions.gen_random_bytes(32), 'hex');

    -- WHERE token_hash IS NULL is the race guard: two concurrent enrols, one row
    -- updated, the loser sees 0 rows and answers NULL.
    update public.devices
       set token_hash      = encode(extensions.digest(tok, 'sha256'), 'hex'),
           token_issued_at = now(),
           token_version   = token_version + 1
     where hardware_id = hw and token_hash is null;
    if not found then return null; end if;

    -- DEPLOY NOTE: when the Worker route is live, dispatch to worker_enroll_url
    -- FIRST (same shape as 20260802_cf_activation_dispatcher.sql) and mirror its
    -- answer here; until then this function must stay unapplied.
    return tok;
end $function$;

-- Same four result strings as activate_device, wrapped: {"status":…,"token":…}
create or replace function public.activate_device_v2(
    device_hw_id text, activation_serial text, legacy_hw_id text default null, app_version_code int default null)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
declare st text; tok text;
begin
    st := public.activate_device(device_hw_id, activation_serial, legacy_hw_id);
    if st = 'success' then
        -- A fresh activation is the one moment the window rule can be skipped:
        -- the serial itself is the proof. Stamp the build so enroll's gate passes.
        update public.devices
           set app_version_code = greatest(coalesce(app_version_code, 0), coalesce(activate_device_v2.app_version_code, 0)),
               last_seen_at     = now()
         where hardware_id = public.resolve_device_id(device_hw_id);
        tok := public.enroll_device(device_hw_id, app_version_code);
    end if;
    return jsonb_build_object('status', st, 'token', tok);
end $function$;

-- Active AND the token matches. No/wrong token -> false, never an error.
create or replace function public.is_device_activated_v2(device_hw_id text, device_token text, app_version_code int default null)
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

revoke all on function public.enroll_device(text, int) from public;
revoke all on function public.activate_device_v2(text, text, text, int) from public;
revoke all on function public.is_device_activated_v2(text, text, int) from public;
grant execute on function public.enroll_device(text, int) to anon, authenticated;
grant execute on function public.activate_device_v2(text, text, text, int) to anon, authenticated;
grant execute on function public.is_device_activated_v2(text, text, int) to anon, authenticated;
