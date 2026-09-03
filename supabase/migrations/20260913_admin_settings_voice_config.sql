-- Applied live as admin_settings_voice_config on 2026-09-04 ~03:4x local (Supabase MCP execute_sql).
--
-- WHY
--   The owner wants the voice-assistant API key (OpenRouter) to be ONE value he can change
--   from the admin page later, without a deploy or a fleet update - and the fleet rule says no
--   third-party key may ever sit inside an APK, an asset or anything a device can read.
--
-- WHAT (new names only)
--   public.admin_settings(key, value, updated_at, updated_by)
--       RLS: select/insert/update for the admin uid only; ZERO grants to anon. The dashboards
--       edit rows like 'voice.openrouter_key' and 'voice.model' with the admin session.
--   cf.voice_config(p_secret text) -> jsonb
--       SECURITY DEFINER, callable by anon/authenticated BUT returns rows only when p_secret
--       hashes (sha256) to admin_settings 'voice.proxy_secret_sha256' - the voice Worker's OWN secret,
--       never the activation Worker's (revised 2026-09-04 04:5x: reading the vault secret out was
--       refused, and one secret per Worker is the better boundary anyway);
--       anything else raises 'unauthorized'. The voice proxy Worker (server-to-server, secret
--       in its own env) reads 'voice.*' through it and caches ~60 s. No device holds the secret.
--
-- WHAT CANNOT BREAK
--   * No existing RPC/table touched. * Dashboards must never echo the full key back
--     (last 4 chars only) - that is a UI rule, recorded in the plan.
--
-- ROLLBACK: drop function cf.voice_config(text); drop table public.admin_settings;

create table if not exists public.admin_settings (
    key        text primary key,
    value      text,
    updated_at timestamptz not null default now(),
    updated_by uuid
);
alter table public.admin_settings enable row level security;
drop policy if exists "adm settings read"   on public.admin_settings;
drop policy if exists "adm settings insert" on public.admin_settings;
drop policy if exists "adm settings update" on public.admin_settings;
create policy "adm settings read"   on public.admin_settings for select to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "adm settings insert" on public.admin_settings for insert to authenticated with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
create policy "adm settings update" on public.admin_settings for update to authenticated using     (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid) with check (auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
grant select, insert, update on public.admin_settings to authenticated;
revoke all on public.admin_settings from anon;

-- Stamp who/when on every write, whatever the client sends.
create or replace function public.admin_settings_touch()
returns trigger language plpgsql as $function$
begin
    new.updated_at := now();
    new.updated_by := auth.uid();
    return new;
end $function$;
drop trigger if exists admin_settings_touch on public.admin_settings;
create trigger admin_settings_touch before insert or update on public.admin_settings
    for each row execute function public.admin_settings_touch();

insert into public.admin_settings (key, value) values
  ('voice.openrouter_key', null),
  ('voice.proxy_secret_sha256', null),  -- sha256 of the voice Worker's own SHARED_SECRET; set out of band
  ('voice.model',          'openai/gpt-4o-mini'),
  ('voice.daily_quota_per_car',  '60'),
  ('voice.minute_quota_per_car', '6')
on conflict (key) do nothing;

create or replace function cf.voice_config(p_secret text)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    expected_hash text;
begin
    -- The voice proxy Worker has its OWN secret (never the activation Worker's). Only its
    -- sha256 lives here, so an admin can rotate it by editing the row after `wrangler secret put`.
    select s.value into expected_hash from public.admin_settings s where s.key = 'voice.proxy_secret_sha256';
    if expected_hash is null or p_secret is null
       or encode(extensions.digest(p_secret, 'sha256'), 'hex') <> lower(expected_hash) then
        raise exception 'unauthorized' using errcode = '28000';
    end if;
    return coalesce((select jsonb_object_agg(s.key, s.value) from public.admin_settings s
                      where s.key like 'voice.%' and s.key <> 'voice.proxy_secret_sha256'), '{}'::jsonb);
end $function$;
revoke all on function cf.voice_config(text) from public;
grant execute on function cf.voice_config(text) to anon, authenticated;

-- PostgREST exposes only the public schema: thin wrapper for the Worker's server-to-server call
-- (POST /rest/v1/rpc/voice_config {"p_secret": ...}).
create or replace function public.voice_config(p_secret text)
returns jsonb
language sql security definer set search_path to 'public'
as $function$
  select cf.voice_config(p_secret);
$function$;
revoke all on function public.voice_config(text) from public;
grant execute on function public.voice_config(text) to anon, authenticated;
