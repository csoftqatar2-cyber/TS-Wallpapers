-- Applied live as 20260802170159_cf_activation_dispatcher
--
-- Replaces activate_device with a dispatcher. With cf_activation_write_enabled
-- OFF (its default), this is a pure pass-through to the byte-identical legacy
-- body, so applying this migration changes no observable behaviour at all.
--
-- CREATE OR REPLACE only, never DROP + CREATE: the signature must stay bound in
-- PostgREST continuously. Every installed APK of all three programs calls this,
-- and two of them have no auto-update path.
--
-- To roll back, flip the flag; see 20260802_cf_activation_ROLLBACK.sql.

create table if not exists cf.settings (
    key   text primary key,
    value text not null
);
insert into cf.settings(key, value) values
    ('worker_activate_url', 'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/activate')
on conflict (key) do nothing;

CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    flag_on  boolean;
    secret   text;
    url      text;
    resp     extensions.http_response;
    decision text;
begin
    select enabled into flag_on from cf.feature_flags
     where name = 'cf_activation_write_enabled';

    -- Kill switch. Off = exactly the behaviour this system has always had, with
    -- no Cloudflare involvement whatsoever. Flipping it back off is the rollback.
    if not coalesce(flag_on, false) then
        return cf.activate_device_legacy(device_hw_id, activation_serial, legacy_hw_id);
    end if;

    select value into url from cf.settings where key = 'worker_activate_url';
    select decrypted_secret into secret from vault.decrypted_secrets
     where name = 'cf_activation_worker_secret';

    if url is null or secret is null then
        raise warning 'cf_activation: missing url/secret, falling back to legacy';
        return cf.activate_device_legacy(device_hw_id, activation_serial, legacy_hw_id);
    end if;

    -- Hard caps. A Cloudflare edge that hangs rather than fails would otherwise
    -- hold this connection for curl's default; a few of those at once starve the
    -- pool for the whole fleet. Activation is rare, so waiting is never worth it.
    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '4000');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '2000');

    begin
        select * into resp from extensions.http((
            'POST', url,
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object(
                'hardware_id',       device_hw_id,
                'activation_serial', activation_serial,
                'legacy_hw_id',      legacy_hw_id,
                'activated_at',      to_char(now() at time zone 'utc','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
            )::text
        )::extensions.http_request);
    exception when others then
        -- RAISE WARNING, not an INSERT: the exception below rolls the transaction
        -- back, and a logged row would roll back with it. Warnings reach the
        -- Postgres log and survive.
        raise warning 'cf_activation: % unreachable: %', device_hw_id, SQLERRM;
        -- Surface the failure. Never answer with something a client would read as
        -- a well-formed "you are not activated" — all three programs keep their
        -- cached activation on an error, but clear it on a negative answer.
        raise exception 'activation backend unreachable: %', SQLERRM;
    end;

    if resp.status <> 200 then
        raise warning 'cf_activation: % HTTP % %', device_hw_id, resp.status, coalesce(resp.content,'');
        raise exception 'activation backend returned HTTP %', resp.status;
    end if;

    decision := resp.content::json ->> 'status';
    if decision is null then
        raise warning 'cf_activation: % no decision in %', device_hw_id, coalesce(resp.content,'');
        raise exception 'activation backend returned no decision';
    end if;

    -- Only 'success' touches local state; the other three answers are returned
    -- verbatim. Note the ordering: D1 decides, Postgres records. The identity
    -- rename stays here because the FK ON UPDATE CASCADE on wallpapers and
    -- wallpaper_hides is what carries a car's private images across with it.
    if decision = 'success' then
        if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id then
            if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
               and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
                update public.devices set hardware_id = device_hw_id
                 where hardware_id = legacy_hw_id;
            end if;
        end if;

        insert into public.devices (hardware_id, serial_number, is_active, activated_at)
        values (device_hw_id, activation_serial, true, now())
        on conflict (hardware_id)
        do update set serial_number = excluded.serial_number,
                      is_active     = true,
                      activated_at  = now();
    end if;

    return decision;
end;
$function$;
