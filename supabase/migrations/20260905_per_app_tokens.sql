-- Applied live as per_app_tokens on 2026-09-03 (Supabase MCP execute_sql).
--
-- WHY
--   Six apps (ذبذبة ستور, TS Wallpapers, TS Back Button, TS Link, the controller, TS Leo
--   Dash) unlock from the SAME devices row: one code activates them all, and each asks
--   the server on its own. But each is its own APK with its own private storage, so a
--   token handed to one app is unreadable by the others. 20260904_device_token.sql kept
--   ONE token per car (devices.token_hash) - the first app to enrol would take it and
--   every later app on that car would be refused and counted as a "clone". Discovered
--   on the bench: it held the wallpapers token, so the store could never enrol.
--
-- WHAT
--   public.device_tokens keyed (hardware_id, app_id) mirrors D1's device_tokens
--   (migration 0004). The three public RPCs keep their exact signatures (they already
--   carry app_id); only their bodies now look at the per-app row. devices.token_* and
--   devices.enroll_conflicts stay: token_* become inert, enroll_conflicts keeps counting
--   per car for the dashboard headline while device_tokens counts per app.
--
-- WHAT CANNOT BREAK
--   * No change to any fielded RPC (is_device_activated / activate_device / get_*).
--   * No new writer of is_active / is_blocked / failed_attempts.
--   * The wallpapers token already issued to the bench is carried over unchanged.
--
-- ROLLBACK: re-create the three functions from 20260904_device_token.sql +
--   20260905_enroll_conflict_mirror.sql; drop table public.device_tokens.

create table if not exists public.device_tokens (
    hardware_id             text        not null references public.devices(hardware_id) on update cascade on delete cascade,
    app_id                  text        not null,
    token_hash              text        not null,
    token_issued_at         timestamptz not null default now(),
    token_version           int         not null default 1,
    enroll_conflicts        int         not null default 0,
    last_enroll_conflict_at timestamptz,
    primary key (hardware_id, app_id)
);
create unique index if not exists device_tokens_hash_idx on public.device_tokens (token_hash);

alter table public.device_tokens enable row level security;
drop policy if exists "tok admin read"   on public.device_tokens;
drop policy if exists "tok admin delete" on public.device_tokens;
create policy "tok admin read"   on public.device_tokens for select to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "tok admin delete" on public.device_tokens for delete to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select, delete on public.device_tokens to authenticated;
revoke all on public.device_tokens from anon;

-- Carry over the per-car tokens issued under 20260904 into their app's row.
insert into public.device_tokens (hardware_id, app_id, token_hash, token_issued_at, token_version)
select hardware_id, coalesce(token_app_id, 'wallpapers'), token_hash, coalesce(token_issued_at, now()), greatest(token_version, 1)
  from public.devices where token_hash is not null
on conflict (hardware_id, app_id) do nothing;

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

    -- Mirror of D1's 'enroll_conflict' audit: a second enrol for THIS app on a car that
    -- already holds this app's token (or a rotation attempt with a wrong serial).
    -- Counted per app here and per car on devices for the dashboard headline.
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

create or replace function public.is_device_activated_v2(device_hw_id text, device_token text, app_id text default 'wallpapers', app_version_code int default null)
returns boolean
language sql security definer set search_path to 'public'
as $function$
  select exists (
    select 1
      from public.devices d
      join public.device_tokens t
        on t.hardware_id = d.hardware_id
       and t.app_id = lower(coalesce(nullif(btrim(is_device_activated_v2.app_id), ''), 'wallpapers'))
     where d.hardware_id = public.resolve_device_id(is_device_activated_v2.device_hw_id)
       and d.is_active = true and d.is_blocked = false
       and is_device_activated_v2.device_token is not null
       and t.token_hash = encode(extensions.digest(is_device_activated_v2.device_token, 'sha256'), 'hex')
  );
$function$;
revoke all on function public.is_device_activated_v2(text, text, text, int) from public;
grant execute on function public.is_device_activated_v2(text, text, text, int) to anon, authenticated;
