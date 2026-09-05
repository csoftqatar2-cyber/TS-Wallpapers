-- Applied live as device_app_daily_realtime on 2026-09-05 (Supabase MCP execute_sql).
--
-- WHY: the owner wants, per program, "how many users, on which version, last open, how many
--   opens a day" and desktop notifications on every activation in the local dashboard.
-- WHAT (new names only; device_ping keeps its signature):
--   public.device_app_daily(hardware_id, app_id, day, opens, app_version_code) - upserted by
--     device_ping (Asia/Qatar day), admin read only.
--   device_ping now also counts the daily open. Every app must call device_ping ONCE per real
--     open (not per return to foreground): Leo, Back Button, wallpapers 185, store 99.
--   Realtime publication supabase_realtime gains devices, device_block_events, device_tokens,
--     device_app_seen (RLS applies to the stream; the dashboard subscribes with the admin JWT).
-- ROLLBACK: alter publication supabase_realtime drop table ...; re-create device_ping from
--   20260906_device_app_seen.sql; drop table public.device_app_daily.

create table if not exists public.device_app_daily (
    hardware_id text not null references public.devices(hardware_id) on update cascade on delete cascade,
    app_id      text not null,
    day         date not null,
    opens       int  not null default 1,
    app_version_code int,
    primary key (hardware_id, app_id, day)
);
create index if not exists device_app_daily_app_day_idx on public.device_app_daily (app_id, day desc);
alter table public.device_app_daily enable row level security;
drop policy if exists "daily admin read" on public.device_app_daily;
create policy "daily admin read" on public.device_app_daily for select to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select on public.device_app_daily to authenticated;
revoke all on public.device_app_daily from anon;

create or replace function public.device_ping(device_hw_id text, app_id text, app_version_code int default null, app_version text default null)
returns void
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw    text;
    v_app text := lower(coalesce(nullif(btrim(device_ping.app_id), ''), 'wallpapers'));
    v_vc  int  := device_ping.app_version_code;
    v_ver text := device_ping.app_version;
begin
    if device_ping.device_hw_id is null or btrim(device_ping.device_hw_id) = '' then return; end if;
    hw := public.resolve_device_id(device_ping.device_hw_id);
    if not exists (select 1 from public.devices dv where dv.hardware_id = hw) then return; end if;
    insert into public.device_app_seen as a (hardware_id, app_id, app_version_code, app_version)
    values (hw, left(v_app, 32), v_vc, left(v_ver, 40))
    on conflict on constraint device_app_seen_pkey do update
       set last_seen        = now(),
           pings            = a.pings + 1,
           app_version_code = coalesce(excluded.app_version_code, a.app_version_code),
           app_version      = coalesce(excluded.app_version,      a.app_version);
    -- Opens per day (2026-09-05): the owner wants "how many times a day" per program.
    insert into public.device_app_daily as dd (hardware_id, app_id, day, opens, app_version_code)
    values (hw, left(v_app, 32), (now() at time zone 'Asia/Qatar')::date, 1, v_vc)
    on conflict on constraint device_app_daily_pkey do update
       set opens = dd.opens + 1,
           app_version_code = coalesce(excluded.app_version_code, dd.app_version_code);
end $function$;
revoke all on function public.device_ping(text, text, int, text) from public;
grant execute on function public.device_ping(text, text, int, text) to anon, authenticated;

alter publication supabase_realtime add table public.devices;
alter publication supabase_realtime add table public.device_block_events;
alter publication supabase_realtime add table public.device_tokens;
alter publication supabase_realtime add table public.device_app_seen;
