-- Applied live 2026-09-05 (execute_sql). Codex security review, item 1: a browser session
-- alone must not be able to write. Every INSERT/UPDATE/DELETE the admin panel performs on
-- devices, admin_settings, wallpapers, wallpaper_hides, app_versions — and the RPC
-- admin_set_device_block_all — now also requires the PostgREST request header
-- x-write-key to equal vault secret 'panel_write_key'. Only the admin site Worker and the
-- local runner add that header; the browser never sees the key. Reads are unchanged.
-- Item 2: the OpenRouter key row is excluded from the browser's SELECT policy; a trigger keeps
-- 'voice.openrouter_key_hint' (last 4 chars + length) for display.
-- The value of the vault secret is NOT in this file (vault.create_secret was run separately).
create or replace function public.panel_write_ok() returns boolean
language plpgsql security definer stable set search_path = public as $$
declare hdr text; want text;
begin
  begin hdr := coalesce((current_setting('request.headers', true))::json ->> 'x-write-key', ''); exception when others then hdr := ''; end;
  select decrypted_secret into want from vault.decrypted_secrets where name = 'panel_write_key';
  return want is not null and hdr <> '' and hdr = want;
end $$;
revoke all on function public.panel_write_ok() from public;
grant execute on function public.panel_write_ok() to authenticated;
-- policies altered (see the live definitions): dev admin insert/update/delete, adm settings insert/update,
-- wp admin insert/update/delete, wph admin insert/delete, ver admin insert/update/delete → ... and public.panel_write_ok()
-- adm settings read → ... and key <> 'voice.openrouter_key'
-- admin_set_device_block_all → raises unless public.panel_write_ok()
-- trigger admin_settings_key_hint_trg maintains voice.openrouter_key_hint
