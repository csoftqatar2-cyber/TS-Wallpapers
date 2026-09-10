-- Applied live 2026-09-10 as enroll_reissue_guard_restored (project ihgmqwzdpugdzddobhbc).
--
-- WHY THIS EXISTS
-- Earlier today the controller team removed `and not have_row` from the reissue branch of
-- cf.enroll_device_impl (migration enroll_device_reissue_when_client_lost_token) so a car that
-- lost its local token could enrol again. The intent was right; the change was not:
--
--   public.enroll_device(device_hw_id, app_id, app_version_code) is ANON-callable and passes
--   serial = null. Without the guard, ANY caller who knows a car's hardware id (the VIN is printed
--   on the car, shown on the settings screen and in the QR) gets, on its second request, a brand
--   new token for that car: the Worker rotates the row and hands the token to the caller
--   (`reissue` → `rotate`). That is token theft for the impersonator and a lockout for the real
--   car, which then re-enrols and rotates it back — ping-pong forever. The Worker's own comment
--   states the property the guard protected: "a stranger with the VIN alone can never rotate".
--
-- The guard is restored EXACTLY as it was. The legitimate recovery path is unchanged and already
-- exists: a car that holds no token while the server holds a row must present the activation
-- serial (activate_device → Worker `bySerial` rotation), i.e. the app shows the activation code
-- prompt again. That is the product change the apps need (the controller today shows «الخدمة
-- الصوتية غير جاهزة» and asks nothing). The one car that was stuck (VIN-byd4DBD3D6D24AC5814,
-- controller) already received a fresh token at 17:24 UTC while the guard was off; it keeps it.
--
-- Everything else in the function is byte-identical to the live definition of 2026-09-10 19:40.

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
