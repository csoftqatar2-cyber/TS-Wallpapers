-- ============================================================================
-- Brute-force lock on activation: 10 wrong codes block the hardware id.
--
-- WHERE EACH HALF LIVES
--   Cloudflare D1 counts and decides (it is the only place that sees every
--   attempt from all three programs, and a counter inside an APK is defeated by
--   clearing app data). Postgres mirrors the outcome, because Postgres is what
--   the dashboard reads and what the cars read. Same split as the activation
--   decision itself: D1 decides, Postgres records and serves.
--
-- WHAT THIS ADDS
--   1. devices.failed_attempts / last_failed_at / last_failed_serial /
--      blocked_at / block_reason — so the dashboard can say WHY a car is
--      blocked and how it got there, not just that it is.
--   2. activate_device now mirrors the Worker's counter into those columns. It
--      stays a pass-through when the Worker does not send them, so the order of
--      the two deploys does not matter and an older Worker keeps working.
--   3. admin_set_device_block() — the dashboard's block/unblock button. It
--      writes Cloudflare FIRST and Postgres second, and lifting a block clears
--      the counter on both sides. Without that reset the next wrong code
--      re-locks the car instantly and the button looks broken.
--
-- Safe to re-run.
-- ============================================================================

alter table public.devices
    add column if not exists failed_attempts    int not null default 0,
    add column if not exists last_failed_at     timestamptz,
    add column if not exists last_failed_serial text,
    add column if not exists blocked_at         timestamptz,
    -- 'failed_attempts' (automatic) | 'admin' (blocked by hand) | null
    add column if not exists block_reason       text;

comment on column public.devices.failed_attempts is
    'Consecutive rejected activation codes. Reset to 0 by a successful activation or by an operator lifting the block.';
comment on column public.devices.block_reason is
    '''failed_attempts'' = locked itself after 10 wrong codes, ''admin'' = blocked from the dashboard.';

insert into cf.settings(key, value) values
    ('worker_set_state_url', 'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/set-state')
on conflict (key) do nothing;


-- ---------------------------------------------------------------------------
-- Dispatcher: unchanged contract (still returns one of the same four strings),
-- now also mirroring the attempt counter the Worker reports back.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    flag_on     boolean;
    secret      text;
    url         text;
    resp        extensions.http_response;
    meta        json;
    decision    text;
    attempts    int;
    blocked_now boolean;
begin
    select enabled into flag_on from cf.feature_flags
     where name = 'cf_activation_write_enabled';

    -- Kill switch. Off = exactly the behaviour this system has always had, with
    -- no Cloudflare involvement whatsoever. Flipping it back off is the rollback.
    -- Note that the attempt counter lives on the Cloudflare side, so with the
    -- flag off there is no lock — the legacy body is left byte-identical on
    -- purpose, because its whole job is to be the escape hatch.
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

    meta     := resp.content::json;
    decision := meta ->> 'status';
    if decision is null then
        raise warning 'cf_activation: % no decision in %', device_hw_id, coalesce(resp.content,'');
        raise exception 'activation backend returned no decision';
    end if;

    -- Absent on an older Worker build. Everything below then behaves exactly as
    -- it did before this migration.
    attempts    := coalesce((meta ->> 'attempts')::int, 0);
    blocked_now := coalesce((meta ->> 'blocked_now')::boolean, false);

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
                      activated_at  = now(),
                      -- the right code clears the run of wrong ones
                      failed_attempts    = 0,
                      last_failed_at     = null,
                      last_failed_serial = null;

    elsif blocked_now then
        -- The car just locked itself. INSERT, not UPDATE: the id may never have
        -- been registered at all — someone typing codes into a head unit that
        -- owns no licence is precisely the case this exists for, and it has to
        -- reach the dashboard's block list all the same.
        --
        -- serial_number stays null: a rejected code is not this car's, and
        -- writing it would collide with the UNIQUE index that IS the licence.
        -- is_active is left alone too — being blocked already stops the car, and
        -- an operator who lifts the block by mistake then has nothing else to
        -- put back by hand.
        insert into public.devices (hardware_id, is_active, is_blocked, failed_attempts,
                                    last_failed_at, last_failed_serial, blocked_at, block_reason)
        values (device_hw_id, false, true, attempts, now(), activation_serial, now(),
                coalesce(meta ->> 'block_reason', 'failed_attempts'))
        on conflict (hardware_id) do update set
            is_blocked         = true,
            failed_attempts    = excluded.failed_attempts,
            last_failed_at     = excluded.last_failed_at,
            last_failed_serial = excluded.last_failed_serial,
            blocked_at         = coalesce(devices.blocked_at, excluded.blocked_at),
            block_reason       = coalesce(devices.block_reason, excluded.block_reason);

    elsif attempts > 0 then
        -- A wrong code on a car that is already registered. Only ever an UPDATE:
        -- a single typo must not conjure a row into the fleet list.
        --
        -- `not is_blocked` freezes the record at the moment the lock closed. An
        -- already-blocked car is refused before its code is even read, so every
        -- later knock comes back carrying the same count — without this guard it
        -- would still rewrite last_failed_serial, and the dashboard would end up
        -- showing a code that was tried AFTER the block as if it were one of the
        -- ten that caused it.
        update public.devices
           set failed_attempts    = attempts,
               last_failed_at     = now(),
               last_failed_serial = activation_serial
         where hardware_id = device_hw_id
           and not is_blocked;
    end if;

    return decision;
end;
$function$;


-- ---------------------------------------------------------------------------
-- The dashboard's block / unblock button.
--
-- Exists as an RPC rather than a PATCH on the table because the flag has to
-- reach Cloudflare in the same breath. A PATCH only moved Postgres, leaving D1
-- to catch up on the 02:30 reconciliation sweep — so an unblocked car could
-- still be refused a code for most of a day, which is the one thing the operator
-- would read as "the button did nothing".
-- ---------------------------------------------------------------------------
create or replace function public.admin_set_device_block(p_hardware_id text, p_blocked boolean)
returns public.devices
language plpgsql
security definer
set search_path to 'public'
as $fn$
declare
    -- Same identity the RLS policies on public.devices use. SECURITY DEFINER
    -- bypasses those policies, so the check has to be repeated here by hand.
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
    dev       public.devices;
    secret    text;
    url       text;
    resp      extensions.http_response;
begin
    if auth.uid() is distinct from admin_uid then
        raise exception 'not authorized';
    end if;

    select * into dev from public.devices where hardware_id = p_hardware_id;
    if not found then
        raise exception 'unknown device %', p_hardware_id;
    end if;

    select value into url from cf.settings where key = 'worker_set_state_url';
    select decrypted_secret into secret from vault.decrypted_secrets
     where name = 'cf_activation_worker_secret';
    if url is null or secret is null then
        raise exception 'activation worker not configured';
    end if;

    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '8000');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '3000');

    -- Cloudflare first, deliberately. If it fails, this raises and Postgres is
    -- left untouched, so the two sides still agree and the operator sees a real
    -- error instead of a change that only half happened.
    begin
        select * into resp from extensions.http((
            'POST', url,
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object(
                'hardware_id',    p_hardware_id,
                'is_blocked',     p_blocked,
                'is_active',      coalesce(dev.is_active, false),
                'create',         true,          -- car may predate D1
                'reset_attempts', not p_blocked, -- lifting a block clears the counter
                'block_reason',   case when p_blocked then 'admin' else null end
            )::text
        )::extensions.http_request);
    exception when others then
        raise exception 'تعذّر الاتصال بخدمة التفعيل (Cloudflare): %', SQLERRM;
    end;

    if resp.status <> 200 then
        raise exception 'خدمة التفعيل ردّت HTTP %: %', resp.status, left(coalesce(resp.content,''), 200);
    end if;

    update public.devices set
        is_blocked         = p_blocked,
        blocked_at         = case when p_blocked then coalesce(blocked_at, now()) else null end,
        block_reason       = case when p_blocked then 'admin' else null end,
        failed_attempts    = case when p_blocked then failed_attempts else 0 end,
        last_failed_at     = case when p_blocked then last_failed_at else null end,
        last_failed_serial = case when p_blocked then last_failed_serial else null end
      where hardware_id = p_hardware_id
      returning * into dev;

    return dev;
end;
$fn$;

revoke all on function public.admin_set_device_block(text, boolean) from public, anon;
grant execute on function public.admin_set_device_block(text, boolean) to authenticated;
