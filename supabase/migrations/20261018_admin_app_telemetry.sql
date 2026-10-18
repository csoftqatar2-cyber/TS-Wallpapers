-- Applied live as admin_app_telemetry on 2026-10-18 (Supabase MCP execute_sql).
--
-- WHY
--   The admin panel's per-program telemetry cards (TS-Programs-Admin control-panel.html,
--   renderAppTelemetry: السيارات المسجّلة، نشطة آخر 7/30 يوم، خاملة، جديدة، فتحات 24 ساعة/30 يوم،
--   «عليه N سيارة») were counted in the browser from ~2 MB of raw rows (devices, store_installs via
--   store_admin_list, device_app_seen, device_app_daily, device_id_aliases), so they showed 0 until
--   every source of the page had arrived (5-10 s on a phone). This returns just the numbers.
--
-- WHAT (new name only; no table, policy or existing function changes)
--   public.admin_app_telemetry() -> jsonb
--     { generated_at, today, devices_total, apps: { <app>: { rows, registered, a7, a30, idle, fresh,
--       has_first, has_daily, opens24, opens30, codes: { "<version_code>": cars } } } }
--   Same rules as the page's usageRows()/dailyUsage(): one row per canonical car (lower-cased,
--   device_id_aliases chain followed), the newest check-in wins (store_installs before
--   device_app_seen on a tie), Qatar calendar days, device_app_daily window = today-30 .. today.
--   Admin-JWT gated exactly like admin_set_device_block_all (auth.uid() check, no anon grant).
--
-- ROLLBACK: drop function public.admin_app_telemetry();

create or replace function public.admin_app_telemetry()
returns jsonb
language plpgsql stable security definer set search_path to 'public'
as $function$
declare
    admin_uid constant uuid := '5b8e1336-ce54-4dd9-bd23-243158c178fe';
    v_now   timestamptz := now();
    v_today date := (now() at time zone 'Asia/Qatar')::date;
    v_out   jsonb;
begin
    if auth.uid() is distinct from admin_uid then raise exception 'not authorized'; end if;

    with recursive
    al as (
        select distinct on (lower(a.old_id)) lower(a.old_id) as o, lower(a.current_id) as c
          from public.device_id_aliases a
         where coalesce(a.old_id, '') <> '' and coalesce(a.current_id, '') <> ''
    ),
    daily_raw as (
        select dd.hardware_id, public_app_key.k as app, dd.day, dd.opens
          from public.device_app_daily dd
          cross join lateral (select case lower(dd.app_id)
                when 'store.thabthaba.clock' then 'wallpapers' when 'systems.sieber.fsclock' then 'wallpapers'
                when 'com.thabthaba.store' then 'store' when 'com.csoft.backbutton' then 'backbutton'
                when 'com.thabthaba.tslink' then 'tslink' when 'com.thabthaba.controller' then 'controller'
                when 'clusterlauncher' then 'leo' when 'com.codex.clusterlauncher' then 'leo'
                else lower(dd.app_id) end as k) public_app_key
         where dd.day >= v_today - 30 and dd.day <= v_today and dd.opens is not null and dd.opens >= 0
    ),
    src as (
        select 'wallpapers'::text as app, d.hardware_id as hw, d.app_version_code as code, d.last_seen_at as last, null::timestamptz as first, 0 as ord
          from public.devices d
        union all
        select 'store', i.hw_id, null::int, i.last_seen, null::timestamptz, 1
          from public.store_installs i
        union all
        select k.k, s.hardware_id, s.app_version_code, s.last_seen, s.first_seen, 2
          from public.device_app_seen s
          cross join lateral (select case lower(s.app_id)
                when 'store.thabthaba.clock' then 'wallpapers' when 'systems.sieber.fsclock' then 'wallpapers'
                when 'com.thabthaba.store' then 'store' when 'com.csoft.backbutton' then 'backbutton'
                when 'com.thabthaba.tslink' then 'tslink' when 'com.thabthaba.controller' then 'controller'
                when 'clusterlauncher' then 'leo' when 'com.codex.clusterlauncher' then 'leo'
                else lower(s.app_id) end as k) k
         where k.k <> 'wallpapers'
    ),
    ids as (
        select distinct lower(btrim(x)) as id
          from (select hw as x from src union all select hardware_id from daily_raw) u
         where btrim(coalesce(x, '')) <> ''
    ),
    -- canonicalHw(): follow old -> current until there is no alias or the next step was already visited.
    walk(id, cur, depth, path) as (
        select id, id, 0, array[id] from ids
        union all
        select w.id, a.c, w.depth + 1, w.path || a.c
          from walk w join al a on a.o = w.cur
         where w.depth < 32 and not (w.cur = any(w.path[1:array_length(w.path, 1) - 1]))
    ),
    canon as (select distinct on (id) id, cur from walk order by id, depth desc),
    rows_one as (
        select distinct on (s.app, c.cur) s.app, c.cur as hw, s.code, s.last, s.first
          from src s join canon c on c.id = lower(btrim(s.hw))
         order by s.app, c.cur, coalesce(s.last, 'epoch'::timestamptz) desc, s.ord
    ),
    daily as (select dr.app, c.cur as hw, dr.day, dr.opens from daily_raw dr join canon c on c.id = lower(btrim(dr.hardware_id))),
    apps as (select unnest(array['wallpapers', 'store', 'tslink', 'leo', 'controller', 'backbutton']) as app),
    per_app as (
        select a.app,
            (select count(*) from rows_one r where r.app = a.app) as n_rows,
            (select count(*) from rows_one r where r.app = a.app and r.last > 'epoch'::timestamptz and r.last <= v_now and r.last >= v_now - interval '7 days') as u7,
            (select count(*) from rows_one r where r.app = a.app and r.last > 'epoch'::timestamptz and r.last <= v_now and r.last >= v_now - interval '30 days') as u30,
            (select count(*) from rows_one r where r.app = a.app and r.last > 'epoch'::timestamptz and v_now - r.last > interval '30 days') as idle,
            (select count(*) > 0 from rows_one r where r.app = a.app and r.first > 'epoch'::timestamptz) as has_first,
            (select count(*) from rows_one r where r.app = a.app and r.first > 'epoch'::timestamptz and v_now - r.first <= interval '30 days') as fresh,
            (select count(*) from daily d where d.app = a.app) as n_daily,
            (select count(distinct d.hw) from daily d where d.app = a.app) as d_cars,
            (select count(distinct d.hw) from daily d where d.app = a.app and d.day >= v_today - 6 and d.opens > 0) as d7,
            (select count(distinct d.hw) from daily d where d.app = a.app and d.day >= v_today - 29 and d.opens > 0) as d30,
            (select coalesce(sum(d.opens), 0) from daily d where d.app = a.app and d.day = v_today) as opens24,
            (select coalesce(sum(d.opens), 0) from daily d where d.app = a.app and d.day >= v_today - 29) as opens30,
            (select coalesce(jsonb_object_agg(x.code::text, x.n), '{}'::jsonb)
               from (select r.code, count(*) as n from rows_one r where r.app = a.app and r.code is not null group by r.code) x) as codes
          from apps a
    )
    select jsonb_build_object(
        'generated_at', v_now,
        'today', v_today,
        'devices_total', (select count(*) from public.devices),
        'devices_fresh30', (select count(*) from public.devices d
                             where coalesce(d.activated_at, d.created_at) > 'epoch'::timestamptz
                               and v_now - coalesce(d.activated_at, d.created_at) <= interval '30 days'),
        'apps', (select jsonb_object_agg(p.app, jsonb_build_object(
            'rows', p.n_rows,
            'registered', case when p.n_rows > 0 then p.n_rows else p.d_cars end,
            'a7',  case when p.n_rows > 0 then p.u7  else p.d7  end,
            'a30', case when p.n_rows > 0 then p.u30 else p.d30 end,
            'idle', case when p.n_rows > 0 then p.idle else greatest(0, p.d_cars - p.d30) end,
            'has_first', p.has_first,
            'fresh', case when p.has_first then p.fresh else null end,
            'has_daily', p.n_daily > 0,
            'opens24', case when p.n_daily > 0 then p.opens24 else null end,
            'opens30', case when p.n_daily > 0 then p.opens30 else null end,
            'codes', p.codes)) from per_app p)
    ) into v_out;
    return v_out;
end $function$;
revoke all on function public.admin_app_telemetry() from public;
grant execute on function public.admin_app_telemetry() to authenticated;
-- Supabase default privileges hand EXECUTE to anon on new functions; take it back explicitly.
revoke execute on function public.admin_app_telemetry() from anon;
