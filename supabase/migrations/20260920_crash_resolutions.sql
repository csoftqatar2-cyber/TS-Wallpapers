-- ============================================================================
-- crash_resolutions — the owner marks a crash signature as FIXED from the admin
-- site, so resolved crashes move out of the "current" list into their own
-- section. A signature = app + exception class + top frame (computed client-side
-- from the report text, the same way the site groups crashes). If a crash with
-- that signature arrives again from a build newer than fixed_version_code the
-- site shows it as "returned".
--
-- Read: the admin session (uid 5b8e1336-…). Write: only through the admin
-- Worker, which adds the x-write-key header (public.panel_write_ok()).
-- Applied live 2026-09-08.
-- ============================================================================
create table if not exists public.crash_resolutions (
  id                 bigint generated always as identity primary key,
  app_id             text        not null,            -- wallpapers | store | leo | controller | tslink | backbutton
  signature          text        not null,
  note               text,
  fixed_version_code integer,
  resolved_at        timestamptz not null default now(),
  unique (app_id, signature)
);
alter table public.crash_resolutions enable row level security;
drop policy if exists crash_resolutions_read  on public.crash_resolutions;
drop policy if exists crash_resolutions_write on public.crash_resolutions;
create policy crash_resolutions_read on public.crash_resolutions for select to authenticated
  using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy crash_resolutions_write on public.crash_resolutions for all to authenticated
  using (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid and public.panel_write_ok())
  with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid and public.panel_write_ok());
grant select, insert, update, delete on public.crash_resolutions to authenticated;
