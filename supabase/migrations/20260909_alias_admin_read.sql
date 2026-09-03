-- Applied live as alias_admin_read on 2026-09-03 (Supabase MCP execute_sql).
-- device_id_aliases had RLS on with no SELECT policy, so the admin session read it as
-- empty and the dashboards could not merge a car's old ids with its current row.
-- Admin-only read, same uid gate as every other table; anon still sees nothing.
-- ROLLBACK: drop policy "alias admin read" on public.device_id_aliases;
drop policy if exists "alias admin read" on public.device_id_aliases;
create policy "alias admin read" on public.device_id_aliases for select to authenticated using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select on public.device_id_aliases to authenticated;
