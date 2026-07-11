-- ============================================================
-- TS Wallpapers - In-app self update: Supabase setup
-- Run this once in the Supabase SQL Editor
-- (Dashboard -> SQL Editor -> New query -> Run)
--
-- NOTE: this covers ONLY the self-update piece (app_versions +
-- apk bucket). The complete backend (devices, wallpapers, RPCs,
-- storage policies) is captured in supabase/schema.sql.
-- Safe to re-run: it recreates the CURRENT security model and
-- explicitly drops the legacy anon-write policies.
-- ============================================================

-- 1) Table that holds the published app versions -------------
create table if not exists public.app_versions (
    id           bigint generated always as identity primary key,
    version_code integer     not null,               -- must match BuildConfig.VERSION_CODE (gradle versionCode)
    version_name text        not null,               -- e.g. "1.6"
    apk_url      text        not null,               -- public URL of the uploaded APK
    changelog    text,                               -- shown to the user in the update dialog
    mandatory    boolean     not null default false, -- reserved for future forced updates
    created_at   timestamptz not null default now()
);

create index if not exists app_versions_version_code_idx
    on public.app_versions (version_code desc);

-- 2) Row Level Security --------------------------------------
alter table public.app_versions enable row level security;

-- the app (anon key) needs to READ the latest version
drop policy if exists "app_versions public read" on public.app_versions;
create policy "app_versions public read"
    on public.app_versions for select
    to anon, authenticated
    using (true);

-- WRITES are admin-only. (The old "app_versions anon write" policy let anyone
-- holding the public anon key publish a fake update to every device — dropped
-- for good; do not bring it back.) CI writes with the service_role key, which
-- bypasses RLS entirely; the manager page writes as the signed-in admin.
drop policy if exists "app_versions anon write" on public.app_versions;
drop policy if exists "ver admin insert" on public.app_versions;
drop policy if exists "ver admin update" on public.app_versions;
drop policy if exists "ver admin delete" on public.app_versions;
create policy "ver admin insert"
    on public.app_versions for insert to authenticated
    with check ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');
create policy "ver admin update"
    on public.app_versions for update to authenticated
    using ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app')
    with check ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');
create policy "ver admin delete"
    on public.app_versions for delete to authenticated
    using ((auth.jwt() ->> 'email') = 'admin@tswallpapers.app');

-- 3) Public storage bucket for the APK files -----------------
-- Devices download via /storage/v1/object/public/apk/... (bucket flag only,
-- no object policy needed). Object writes are admin-only; anonymous LISTING
-- is intentionally not allowed.
insert into storage.buckets (id, name, public)
values ('apk', 'apk', true)
on conflict (id) do update set public = true;

drop policy if exists "apk anon read"  on storage.objects;  -- legacy: allowed anon listing
drop policy if exists "apk anon write" on storage.objects;  -- legacy hole, keep dropped
drop policy if exists "apk storage admin read"   on storage.objects;
drop policy if exists "apk storage admin insert" on storage.objects;
drop policy if exists "apk storage admin update" on storage.objects;
drop policy if exists "apk storage admin delete" on storage.objects;
create policy "apk storage admin read"
    on storage.objects for select to authenticated
    using (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin insert"
    on storage.objects for insert to authenticated
    with check (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin update"
    on storage.objects for update to authenticated
    using (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid)
    with check (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "apk storage admin delete"
    on storage.objects for delete to authenticated
    using (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

-- ============================================================
-- Done. Publish a version like this (or use the manager page,
-- or just push a versionCode bump — CI does this automatically):
--
--   insert into public.app_versions (version_code, version_name, apk_url, changelog)
--   values (
--     120,
--     '1.6',
--     'https://ihgmqwzdpugdzddobhbc.supabase.co/storage/v1/object/public/apk/ts-wallpapers-1.6-120.apk',
--     'Bug fixes'
--   );
--
-- IMPORTANT: version_code MUST be greater than the versionCode in
-- source/app/build.gradle of the currently installed build, otherwise
-- the app will consider itself up to date.
-- ============================================================
