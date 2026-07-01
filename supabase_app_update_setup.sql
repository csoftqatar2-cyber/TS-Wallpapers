-- ============================================================
-- TS Wallpapers - In-app self update: Supabase setup
-- Run this once in the Supabase SQL Editor
-- (Dashboard -> SQL Editor -> New query -> Run)
-- ============================================================

-- 1) Table that holds the published app versions -------------
create table if not exists public.app_versions (
    id           bigint generated always as identity primary key,
    version_code integer     not null,               -- must match BuildConfig.VERSION_CODE (gradle versionCode)
    version_name text        not null,               -- e.g. "2.4"
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

-- the manager page (anon key) needs to INSERT / UPDATE / DELETE versions
-- (matches the existing security model used for the wallpapers/devices tables)
drop policy if exists "app_versions anon write" on public.app_versions;
create policy "app_versions anon write"
    on public.app_versions for all
    to anon, authenticated
    using (true)
    with check (true);

-- 3) Public storage bucket for the APK files -----------------
insert into storage.buckets (id, name, public)
values ('apk', 'apk', true)
on conflict (id) do update set public = true;

-- read is already public via the bucket flag, but we still allow
-- the manager (anon key) to upload / overwrite / delete APK files
drop policy if exists "apk anon read" on storage.objects;
create policy "apk anon read"
    on storage.objects for select
    to anon, authenticated
    using (bucket_id = 'apk');

drop policy if exists "apk anon write" on storage.objects;
create policy "apk anon write"
    on storage.objects for all
    to anon, authenticated
    using (bucket_id = 'apk')
    with check (bucket_id = 'apk');

-- ============================================================
-- Done. Publish a version like this (or use the manager page):
--
--   insert into public.app_versions (version_code, version_name, apk_url, changelog)
--   values (
--     43,
--     '2.4',
--     'https://ihgmqwzdpugdzddobhbc.supabase.co/storage/v1/object/public/apk/ts-wallpapers-2.4.apk',
--     'Bug fixes and new wallpapers'
--   );
--
-- IMPORTANT: version_code MUST be greater than the versionCode in
-- app/build.gradle of the currently installed build, otherwise the
-- app will consider itself up to date.
-- ============================================================
