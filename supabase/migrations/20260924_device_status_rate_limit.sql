-- PENDING: not yet applied to the live project (ihgmqwzdpugdzddobhbc).
--
-- M9 (Medium) in Thabthaba Dashboard/reports/security-review-controller-2026-09-15.md:
--   get_device_status and get_enroll_state are anon-callable oracles keyed by a VIN; a loop
--   over plausible VINs maps which cars are registered / active / blocked / enrolled. The
--   answers themselves are contract (Activation.java, FsClockView.refreshBlockedState) and
--   cannot change, so the fix is a per-IP budget with a signal a real car never trips:
--   a car asks about ITS OWN id and sees 'unknown' only while it is unregistered; an
--   enumerator sees 'unknown' for almost every id it tries.
--
-- VERDICT: SAFE-TO-APPLY as a file — rate_limit_mode starts 'off': counters are recorded
--   (cf.rate_hits) but nothing is refused, and both functions answer exactly what they
--   answer today. ‼️ Switching rate_limit_mode to 'on' is OWNER APPROVAL NEEDED (low risk):
--   a caller over budget then gets HTTP 429 (SQLSTATE PT429) instead of an answer; every
--   fielded client treats a non-2xx from these probes as "no answer this time" (the
--   controller: "anything else means not this problem"), never as 'unknown'/'blocked'.
--
-- WHAT (signatures unchanged; no overloads; answers unchanged)
--   cf.rate_hits, cf.client_ip(), cf.rate_hit(bucket, limit, window), cf.rate_count(...)
--   public.get_device_status(device_hw_id, legacy_hw_id)  — + budget
--   public.get_enroll_state(device_hw_id, app_id)          — + budget; declared VOLATILE now
--     (was STABLE) because it writes a counter. Same body otherwise.
--   cf.settings: rate_limit_mode ('off'|'on'), rate_status_calls_per_day (3000),
--     rate_status_unknown_per_day (60) — per client IP.
--
-- IP source: cf-connecting-ip / x-real-ip / x-forwarded-for from request.headers, exactly as
--   the controller's telemetry project does (thab_client_ip). No header → no budget (fail-open).
--   A garage/CGNAT sharing one IP shares one budget: 3000 probes and 60 'unknown' answers a day
--   is far above what a handful of cars produce; raise the keys if cf.rate_hits says otherwise.
--
-- STAGING (owner)
--   1. apply; 2. after a few days:
--      select bucket, window_start, n from cf.rate_hits order by n desc limit 50;
--   3. update cf.settings set value = 'on' where key = 'rate_limit_mode';
--
-- ROLLBACK: update cf.settings set value = 'off' where key = 'rate_limit_mode';  — or re-create
--   get_device_status from 20260816_get_device_status.sql and get_enroll_state from
--   20260910_get_enroll_state.sql (same signatures, `create or replace`, grants restated).

insert into cf.settings(key, value) values
  ('rate_limit_mode',             'off'),
  ('rate_status_calls_per_day',   '3000'),
  ('rate_status_unknown_per_day', '60')
on conflict (key) do nothing;

create table if not exists cf.rate_hits (
    bucket       text        not null,
    window_start timestamptz not null,
    n            int         not null default 0,
    primary key (bucket, window_start)
);
create index if not exists rate_hits_window_idx on cf.rate_hits (window_start);
revoke all on table cf.rate_hits from public, anon, authenticated;

-- The caller's real IP, or null. Never raises.
create or replace function cf.client_ip()
returns text
language plpgsql stable security definer set search_path to 'public'
as $function$
declare h json; v text;
begin
    begin
        h := nullif(current_setting('request.headers', true), '')::json;
    exception when others then
        return null;
    end;
    if h is null then return null; end if;
    v := coalesce(h->>'cf-connecting-ip', h->>'x-real-ip', h->>'x-forwarded-for');
    if v is null then return null; end if;
    return nullif(btrim(split_part(v, ',', 1)), '');
end $function$;
revoke all on function cf.client_ip() from public, anon, authenticated;

-- Counts one hit in the current window; true while within p_limit.
create or replace function cf.rate_hit(p_bucket text, p_limit int, p_window interval)
returns boolean
language plpgsql volatile security definer set search_path to 'public'
as $function$
declare
    v_secs  numeric := extract(epoch from p_window);
    v_start timestamptz;
    v_n     int;
begin
    if p_bucket is null or p_limit is null or v_secs is null or v_secs <= 0 then return true; end if;
    v_start := to_timestamp(floor(extract(epoch from now()) / v_secs) * v_secs);
    insert into cf.rate_hits as r (bucket, window_start, n)
         values (left(p_bucket, 200), v_start, 1)
    on conflict (bucket, window_start) do update set n = r.n + 1
    returning r.n into v_n;
    if random() < 0.005 then
        delete from cf.rate_hits where window_start < now() - interval '2 days';
    end if;
    return v_n <= p_limit;
end $function$;
revoke all on function cf.rate_hit(text, int, interval) from public, anon, authenticated;

create or replace function cf.rate_enforced()
returns boolean
language sql stable security definer set search_path to 'public'
as $function$
    select coalesce((select s.value from cf.settings s where s.key = 'rate_limit_mode'), 'off') = 'on';
$function$;
revoke all on function cf.rate_enforced() from public, anon, authenticated;

create or replace function cf.rate_setting_int(p_key text, p_default int)
returns int
language sql stable security definer set search_path to 'public'
as $function$
    select coalesce((select nullif(s.value, '')::int from cf.settings s where s.key = p_key), p_default);
$function$;
revoke all on function cf.rate_setting_int(text, int) from public, anon, authenticated;

-- get_device_status: body of 20260816_get_device_status.sql, plus the budget. Read-only on
-- devices, as before (never migrates an identity).
create or replace function public.get_device_status(device_hw_id text, legacy_hw_id text default null)
returns text
language plpgsql
security definer
set search_path to 'public'
as $fn$
declare
    d      public.devices;
    v_ans  text;
    v_ip   text;
    v_ok   boolean := true;
begin
    select * into d from public.devices
     where hardware_id = public.resolve_device_id(device_hw_id);

    if not found and legacy_hw_id is not null and legacy_hw_id <> '' then
        select * into d from public.devices
         where hardware_id = public.resolve_device_id(legacy_hw_id);
    end if;

    if not found          then v_ans := 'unknown';
    elsif d.is_blocked    then v_ans := 'blocked';
    elsif d.is_active     then v_ans := 'active';
    else                       v_ans := 'inactive';
    end if;

    -- M9 budget per client IP: all probes, and 'unknown' answers on their own.
    v_ip := cf.client_ip();
    if v_ip is not null then
        v_ok := cf.rate_hit('status:' || v_ip, cf.rate_setting_int('rate_status_calls_per_day', 3000), interval '1 day');
        if v_ans = 'unknown' then
            v_ok := cf.rate_hit('status_unknown:' || v_ip, cf.rate_setting_int('rate_status_unknown_per_day', 60), interval '1 day') and v_ok;
        end if;
        if not v_ok and cf.rate_enforced() then
            raise sqlstate 'PT429' using message = 'rate limited';
        end if;
    end if;
    return v_ans;
end;
$fn$;
revoke all on function public.get_device_status(text, text) from public;
grant execute on function public.get_device_status(text, text) to anon, authenticated;

-- get_enroll_state: body of 20260910_get_enroll_state.sql, plus the budget (shares the
-- same per-IP buckets: an enumerator switching between the two probes gets one budget).
create or replace function public.get_enroll_state(device_hw_id text, app_id text default 'wallpapers')
returns text
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
    hw     text;
    v_app  text := lower(coalesce(nullif(btrim(app_id), ''), 'wallpapers'));
    d      public.devices;
    v_ans  text;
    v_ip   text;
    v_ok   boolean := true;
begin
    if device_hw_id is null or device_hw_id = '' then return 'unknown'; end if;
    hw := public.resolve_device_id(device_hw_id);
    select * into d from public.devices dv where dv.hardware_id = hw;
    if not found then v_ans := 'unknown';
    elsif d.is_blocked then v_ans := 'blocked';
    elsif not d.is_active then v_ans := 'inactive';
    elsif exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app) then
        v_ans := 'enrolled';
    else
        v_ans := 'not_enrolled';
    end if;

    v_ip := cf.client_ip();
    if v_ip is not null then
        v_ok := cf.rate_hit('status:' || v_ip, cf.rate_setting_int('rate_status_calls_per_day', 3000), interval '1 day');
        if v_ans = 'unknown' then
            v_ok := cf.rate_hit('status_unknown:' || v_ip, cf.rate_setting_int('rate_status_unknown_per_day', 60), interval '1 day') and v_ok;
        end if;
        if not v_ok and cf.rate_enforced() then
            raise sqlstate 'PT429' using message = 'rate limited';
        end if;
    end if;
    return v_ans;
end $function$;
revoke all on function public.get_enroll_state(text, text) from public;
grant execute on function public.get_enroll_state(text, text) to anon, authenticated;

-- VERIFY (after applying; mode still 'off')
--   select public.get_device_status('<A_REAL_ACTIVE_HARDWARE_ID>');            -- 'active'
--   select public.get_enroll_state('<A_REAL_ACTIVE_HARDWARE_ID>', 'controller'); -- as before
--   select * from cf.rate_hits order by window_start desc limit 10;
