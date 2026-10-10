-- NOT YET APPLIED (2026-10-10). Apply only AFTER the activation Worker that understands
-- `fingerprint` is deployed (cloudflare/activation-worker, branch feat/fp-selfheal-companions) —
-- an older Worker ignores the field, so applying first is harmless but useless.
--
-- WHY
--   A BYD UI5->UI6 format (or an uninstall + reinstall) wipes every app's private storage. The
--   car keeps its hardware id (VIN-<vin>) and D1 keeps device_tokens(hw, app), so the anonymous
--   enroll_device answers already_enrolled for ever: TS Wallpapers on VIN-byd1108D9EBDCBD89E1 hit
--   23 enroll_conflicts on 2026-10-10 and stayed without a token. The controller already heals
--   through thab-voice /v1/controller/enroll with a hardware fingerprint; this lets every OTHER
--   app (wallpapers, store, tslink, …) present the SAME per-car fingerprint and get its own token
--   rotated — through Postgres, so the mirror row here is written exactly as for any other mint
--   (companion tokens are still verified by is_device_activated_v2 against this table).
--
-- WHAT
--   cf.enroll_device_impl2(hw, app, vc, serial, fingerprint): the live cf.enroll_device_impl body,
--   word for word, plus `fingerprint` in the JSON it sends to the Worker.
--   cf.enroll_device_impl(hw, app, vc, serial): now a one-line wrapper (fingerprint null) — same
--   answers as before for every existing caller (enroll_device, activate_device's serial road).
--   public.enroll_device_fp(device_hw_id, app_id, app_version_code, fingerprint): new, anon-callable,
--   same gates and same return (token or null) as enroll_device.
--
-- SECURITY (no trust on first use)
--   The fingerprint is only COMPARED here, never bound: D1 device_fingerprints is written only by
--   thab-voice with proof (the controller's live token, the car's own activation code, or the first
--   mint right after a code). The Worker refuses a fingerprint for a non-VIN id, caps wrong
--   fingerprints at 5 per car per UTC day (shared with thab-voice), and rotations at 3 per (car, app)
--   per UTC day. A car with no bound fingerprint keeps today's road: its activation code.
--
-- ROLLBACK
--   drop function public.enroll_device_fp(text, text, integer, text);
--   then re-create cf.enroll_device_impl(text,text,integer,text) from the body of impl2 below without
--   the 'fingerprint' key, and drop cf.enroll_device_impl2.

create or replace function cf.enroll_device_impl2(device_hw_id text, app_id text, app_version_code integer, serial text, fingerprint text)
 returns text
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw        text;
    d         public.devices;
    p_hw      text := enroll_device_impl2.device_hw_id;
    v_app     text := lower(coalesce(nullif(btrim(enroll_device_impl2.app_id), ''), 'wallpapers'));
    v_vc      int  := enroll_device_impl2.app_version_code;
    v_serial  text := enroll_device_impl2.serial;
    v_fp      text := nullif(btrim(coalesce(enroll_device_impl2.fingerprint, '')), '');
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
    if v_fp is not null and (length(v_fp) > 256 or v_fp !~ '^[0-9a-f]{64}$') then v_fp := null; end if;
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

    loop
        tries := tries + 1;
        begin
            select * into resp from extensions.http((
                'POST', url,
                array[extensions.http_header('Authorization', 'Bearer ' || secret)],
                'application/json',
                json_build_object('hardware_id', hw, 'app_id', v_app, 'app_version_code', v_vc,
                                  'activation_serial', v_serial,
                                  'fingerprint', v_fp,
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
        -- code (activate_device rotates by serial) or prove the unit (fingerprint, decided in D1).
        -- Never reissue on an anonymous call then.
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

revoke all on function cf.enroll_device_impl2(text, text, integer, text, text) from public, anon, authenticated;

create or replace function cf.enroll_device_impl(device_hw_id text, app_id text, app_version_code integer, serial text)
 returns text
 language sql
 security definer
 set search_path to 'public'
as $function$
  select cf.enroll_device_impl2(device_hw_id, app_id, app_version_code, serial, null);
$function$;

create or replace function public.enroll_device_fp(device_hw_id text, app_id text default 'wallpapers', app_version_code integer default null, fingerprint text default null)
 returns text
 language sql
 security definer
 set search_path to 'public'
as $function$
  select cf.enroll_device_impl2(device_hw_id, app_id, app_version_code, null, fingerprint);
$function$;

revoke all on function public.enroll_device_fp(text, text, integer, text) from public;
grant execute on function public.enroll_device_fp(text, text, integer, text) to anon, authenticated, service_role;
