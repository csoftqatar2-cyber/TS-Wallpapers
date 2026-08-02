-- Applied live as 20260802171432_cf_activation_reconcile
--                + 20260802172106_cf_activation_reconcile_schedule
--
-- Scheduled reconciliation between Postgres and D1.
--
-- The two directions of drift are NOT equally dangerous, and this function
-- treats them differently on purpose:
--
--   Postgres active, D1 not  -> harmless. The car works (all reads are local).
--                               Auto-healed by pushing to D1.
--   D1 active, Postgres not  -> dangerous, and should be impossible given the
--                               dispatcher only writes locally after D1 says
--                               success. ALERTED, never auto-fixed: silently
--                               activating a car in Postgres on D1's word would
--                               turn a D1 fault into a fleet-wide one.
--
-- Both directions were exercised before this was trusted: a Postgres-only row
-- was healed into D1 (source='reconcile'), and a D1-only active row produced an
-- alert naming it while creating nothing in Postgres.

create table if not exists cf.reconcile_log (
    id            bigserial primary key,
    ran_at        timestamptz not null default now(),
    pg_rows       int,
    d1_rows       int,
    pushed        int,
    alerts        int,
    alert_details text,
    error         text
);

create or replace function cf.reconcile_activation()
returns jsonb
language plpgsql security definer set search_path to 'public'
as $fn$
declare
    secret     text;
    base_url   text;
    cursor_val text := '';
    page       json;
    resp       extensions.http_response;
    pushed     int := 0;
    alerts     int := 0;
    alert_txt  text := '';
    n_pg       int;
    n_d1       int;
    payload    text;
begin
    select decrypted_secret into secret from vault.decrypted_secrets
     where name = 'cf_activation_worker_secret';
    if secret is null then
        insert into cf.reconcile_log(error) values ('missing vault secret');
        return jsonb_build_object('status','error','error','missing vault secret');
    end if;

    base_url := 'https://ts-activation.tsdash-qatar.workers.dev';

    -- Background job, so it can afford to wait longer than the activation path.
    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '20000');
    perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '5000');

    create temp table if not exists _d1_snapshot (
        hardware_id text primary key, serial_number text,
        is_active boolean, is_blocked boolean, activated_at text
    ) on commit drop;
    delete from _d1_snapshot;

    -- Page through the whole D1 table; the fleet outgrows a single page eventually.
    loop
        begin
            select * into resp from extensions.http((
                'GET', base_url || '/v1/devices/export?limit=500&cursor=' || cursor_val,
                array[extensions.http_header('Authorization','Bearer '||secret)],
                null, null)::extensions.http_request);
        exception when others then
            insert into cf.reconcile_log(error) values ('export unreachable: '||SQLERRM);
            return jsonb_build_object('status','error','error',SQLERRM);
        end;

        if resp.status <> 200 then
            insert into cf.reconcile_log(error) values ('export HTTP '||resp.status);
            return jsonb_build_object('status','error','error','HTTP '||resp.status);
        end if;

        page := resp.content::json;

        insert into _d1_snapshot
        select r->>'hardware_id', nullif(r->>'serial_number',''),
               (r->>'is_active')::boolean, (r->>'is_blocked')::boolean, r->>'activated_at'
          from json_array_elements(page->'rows') r
        on conflict (hardware_id) do nothing;

        exit when page->>'next_cursor' is null;
        cursor_val := page->>'next_cursor';
    end loop;

    select count(*) into n_pg from public.devices;
    select count(*) into n_d1 from _d1_snapshot;

    -- Direction 1: Postgres is ahead or differs -> push. Safe, so automatic.
    select json_agg(json_build_object(
             'hardware_id', p.hardware_id, 'serial_number', p.serial_number,
             'is_active', coalesce(p.is_active,false), 'is_blocked', p.is_blocked,
             'activated_at', to_char(p.activated_at at time zone 'utc','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
           ))::text
      into payload
      from public.devices p
      left join _d1_snapshot d using (hardware_id)
     where d.hardware_id is null
        or coalesce(p.is_active,false) is distinct from d.is_active
        or p.is_blocked                is distinct from d.is_blocked
        or p.serial_number             is distinct from d.serial_number
        or to_char(p.activated_at at time zone 'utc','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                                       is distinct from d.activated_at;

    if payload is not null then
        select json_array_length(payload::json) into pushed;
        begin
            select * into resp from extensions.http((
                'POST', base_url || '/v1/devices/bulk-upsert',
                array[extensions.http_header('Authorization','Bearer '||secret)],
                'application/json',
                json_build_object('source','reconcile','rows', payload::json)::text
            )::extensions.http_request);
            if resp.status <> 200 then
                insert into cf.reconcile_log(pg_rows,d1_rows,error)
                     values (n_pg,n_d1,'push HTTP '||resp.status);
                return jsonb_build_object('status','error','error','push HTTP '||resp.status);
            end if;
        exception when others then
            insert into cf.reconcile_log(pg_rows,d1_rows,error)
                 values (n_pg,n_d1,'push failed: '||SQLERRM);
            return jsonb_build_object('status','error','error',SQLERRM);
        end;
    end if;

    -- Direction 2: D1 claims an activation Postgres does not have. Never healed
    -- automatically -- its appearance is itself the signal that something is wrong.
    select count(*), coalesce(string_agg(hardware_id, ', '), '')
      into alerts, alert_txt
      from (
        select d.hardware_id
          from _d1_snapshot d
          left join public.devices p using (hardware_id)
         where d.is_active and not d.is_blocked
           and (p.hardware_id is null or not coalesce(p.is_active,false) or p.is_blocked)
         limit 50
      ) x;

    if alerts > 0 then
        raise warning 'cf_reconcile: % car(s) active in D1 but not in Postgres: %', alerts, alert_txt;
    end if;

    insert into cf.reconcile_log(pg_rows,d1_rows,pushed,alerts,alert_details)
         values (n_pg,n_d1,pushed,alerts,nullif(alert_txt,''));

    return jsonb_build_object('status','ok','pg_rows',n_pg,'d1_rows',n_d1,
                              'pushed',pushed,'alerts',alerts);
end;
$fn$;

revoke all on function cf.reconcile_activation() from public, anon, authenticated;

-- Schedule it nightly. pg_cron keeps this inside the database: no external
-- runner, no second copy of the secret, nothing to forget to renew.
create extension if not exists pg_cron;

do $$
begin
  perform cron.unschedule('cf_activation_reconcile');
exception when others then
  null;  -- not scheduled yet
end $$;

select cron.schedule('cf_activation_reconcile', '30 2 * * *',
                     $$select cf.reconcile_activation()$$);
