-- Applied live as device_block_events on 2026-09-04 00:xx (Supabase MCP execute_sql).
--
-- WHY
--   blocked_at is erased on unblock and D1's devices_audit is not visible from the
--   dashboards, so "when was this car blocked / unblocked, by whom" had no answer in
--   Postgres - the source of two mix-ups during the 2026-09-03 bench sessions.
--
-- WHAT
--   public.device_block_events(hardware_id, blocked, reason, by_uid, via, at), written by
--   admin_set_device_block AFTER the Worker (D1) accepted the change. admin_set_device_block_all
--   goes through it, so both dashboard buttons leave a row. Admin-only read (RLS).
--   Signature of admin_set_device_block unchanged; only the trailing INSERT is new.
--
-- ROLLBACK: re-create admin_set_device_block without the INSERT; keep the table.

create table if not exists public.device_block_events (
    id          bigserial primary key,
    hardware_id text not null,
    blocked     boolean not null,
    reason      text,
    by_uid      uuid,
    via         text,
    at          timestamptz not null default now()
);
create index if not exists device_block_events_hw_idx on public.device_block_events (hardware_id, at desc);
alter table public.device_block_events enable row level security;
drop policy if exists "blockev admin read" on public.device_block_events;
create policy "blockev admin read" on public.device_block_events for select to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select on public.device_block_events to authenticated;
revoke all on public.device_block_events from anon;

-- admin_set_device_block: identical to the live body + the trailing INSERT below.
--   insert into public.device_block_events (hardware_id, blocked, reason, by_uid, via)
--   values (p_hardware_id, p_blocked, case when p_blocked then 'admin' else 'admin_unblock' end, auth.uid(), 'admin_set_device_block');
-- (full body recorded in schema.sql)
