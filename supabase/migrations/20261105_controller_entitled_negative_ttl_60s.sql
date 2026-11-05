-- 2026-11-05 — controller_wallpapers_entitled: negative-cache TTL 15 min -> 60 s.
-- APPLIED 2026-11-05 to ihgmqwzdpugdzddobhbc (owner: «صلح وانشر»).
--
-- WHY: a "not entitled" answer from the activation Worker was cached for 15 min in
-- controller_wallpaper_grants. When a car's dashboard licence is redeemed, BackButton / ذبذبة ستور
-- (get_device_status -> controller_licence_unlocks -> this function) kept getting 'unknown' for up
-- to 15 min, so BackButton's red mark stayed. Trigger: VIN-LVMGFBDL0TDB43926 redeemed 11:22:01Z.
--
-- WHAT: ONLY the "not entitled" interval changes (15 minutes -> 60 seconds). The positive cache (6 h),
-- the 1.5 s timeout, errors-not-cached, fail-closed, signature, SECURITY DEFINER, search_path,
-- owner (postgres) and grants (postgres, service_role only) are unchanged — CREATE OR REPLACE keeps
-- owner and ACL.
-- ROLLBACK: re-run this body with interval '15 minutes'.

CREATE OR REPLACE FUNCTION public.controller_wallpapers_entitled(hw text, legacy text DEFAULT NULL::text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    cached  public.controller_wallpaper_grants%rowtype;
    secret  text;
    resp    extensions.http_response;
    answer  jsonb;
    ok      boolean;
begin
    if hw is null or hw = '' then
        return false;
    end if;
    select * into cached from public.controller_wallpaper_grants g where g.hardware_id = hw;
    if found and cached.checked_at > now() - (case when cached.entitled then interval '6 hours' else interval '60 seconds' end) then
        return cached.entitled;
    end if;
    begin
        select decrypted_secret into secret from vault.decrypted_secrets
         where name = 'cf_activation_worker_secret';
        if secret is null then
            return false;
        end if;
        -- TIMEOUT_MS only (it caps the connect too). curl options persist on the pooled backend; the other http
        -- callers (activate_device 4 s, migrate_device_hardware_id 3 s, admin_set_device_block 8 s) each set their
        -- own TIMEOUT_MS first, but none sets CONNECTTIMEOUT_MS, so it is not set here (no leak into them).
        perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '1500');
        select * into resp from extensions.http((
            'POST', 'https://ts-activation.tsdash-qatar.workers.dev/v1/controller/entitled',
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object('hardware_id', hw,
                              'legacy_hw_id', nullif(legacy, ''))::text
        )::extensions.http_request);
        if resp.status <> 200 then
            raise warning 'controller_wallpapers_entitled: Worker HTTP % for %', resp.status, hw;
            return false;   -- not cached: an error is not a "no"
        end if;
        answer := resp.content::jsonb;
        if coalesce(answer->>'status', '') <> 'ok' or coalesce(jsonb_typeof(answer->'entitled'), '') <> 'boolean' then
            return false;
        end if;
        ok := (answer->>'entitled')::boolean;
        insert into public.controller_wallpaper_grants as g (hardware_id, entitled, checked_at)
        values (hw, ok, now())
        on conflict (hardware_id) do update set entitled = excluded.entitled, checked_at = excluded.checked_at;
        return ok;
    exception when others then
        raise warning 'controller_wallpapers_entitled: % for %', sqlerrm, hw;
        return false;
    end;
end;
$function$;
