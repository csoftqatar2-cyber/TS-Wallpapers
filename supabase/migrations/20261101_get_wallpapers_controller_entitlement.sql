-- 2026-11-01 — a valid ذبذبة Dashboard licence unlocks the cloud wallpapers.
--
-- WHY: owner, on a Denza B5 activated with a DASHBOARD code only (the store/family box refused it,
-- so devices has no active row): «مدام انا فعلت ذبذبة داش بورد لازم دي تتفعل». get_wallpapers answered
-- that car with the "inactive" sentinel and the dashboard showed «هذه السيارة غير مفعّلة لخلفيات
-- السحابة». This REVERSES the 2026-10-28 rule «dashboard licence must not unlock wallpapers/store» —
-- for the wallpapers only. The store, the family box, devices.is_active, is_device_activated and
-- every other RPC keep their rules: a dashboard licence still does NOT activate the car.
--
-- WHAT: get_wallpapers, on its "not activated" branch only, asks the activation Worker
-- (POST /v1/controller/entitled — D1 ts-activation controller_entitlements, revoked_at NULL, by
-- hardware id then legacy id, ANY kind or tier: code full / G700 core, grandfather snapshot /
-- worker-fallback / tsg700-live, TS G700 migration, manual — owner restated «اي تفعيل لذبذبة داش
-- بورد لازم الخلفيات تشتغل»; with no row at all, an active unblocked D1 family row also counts).
-- Builds already on the cars (890 and older) honour this with no app update: they only read
-- get_wallpapers' answer. Entitled → the same playlist an activated car gets: channel 'app',
-- global + this car's own, mode-targeted when a devices row carries a mode, minus the car's hides.
-- An admin-BLOCKED devices row stays blocked (sentinel). The activated path is byte-for-byte the
-- live definition of 2026-11-01.
--
-- COST: only cars the activated branch refuses reach the Worker. Answers are cached in
-- public.controller_wallpaper_grants (entitled 6 h, not entitled 15 min); a Worker error or timeout
-- is not cached and answers the sentinel, exactly as before this migration (fail closed). The http
-- call is capped at 1.5 s so get_wallpapers stays inside anon's 3 s statement_timeout.
--
-- DEPLOY ORDER: 1) deploy the activation Worker with /v1/controller/entitled; 2) apply this.
-- (Applied first, the Worker answers 404 → not entitled → sentinel: harmless, nothing breaks.)
--
-- SIDE EFFECT TO KNOW: older TS Wallpapers / Back Button builds that read "activated" from
-- get_wallpapers containing "http" will now read a dashboard-licensed car as activated too.

create table if not exists public.controller_wallpaper_grants (
    hardware_id text primary key,
    entitled    boolean not null,
    checked_at  timestamptz not null default now()
);
alter table public.controller_wallpaper_grants enable row level security;
revoke all on table public.controller_wallpaper_grants from public, anon, authenticated;

create or replace function public.controller_wallpapers_entitled(hw text, legacy text default null::text)
 returns boolean
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    cached  public.controller_wallpaper_grants%rowtype;
    secret  text;
    resp    extensions.http_response;
    answer  jsonb;
    ok      boolean;
begin
    if hw is null or hw = '' then
        return false;
    end if;
    select * into cached from public.controller_wallpaper_grants g where g.hardware_id = hw;
    if found and cached.checked_at > now() - (case when cached.entitled then interval '6 hours' else interval '15 minutes' end) then
        return cached.entitled;
    end if;
    begin
        select decrypted_secret into secret from vault.decrypted_secrets
         where name = 'cf_activation_worker_secret';
        if secret is null then
            return false;
        end if;
        perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '1500');
        perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '1000');
        select * into resp from extensions.http((
            'POST', 'https://ts-activation.tsdash-qatar.workers.dev/v1/controller/entitled',
            array[extensions.http_header('Authorization', 'Bearer ' || secret)],
            'application/json',
            json_build_object('hardware_id', hw,
                              'legacy_hw_id', nullif(legacy, ''))::text
        )::extensions.http_request);
        if resp.status <> 200 then
            raise warning 'controller_wallpapers_entitled: Worker HTTP % for %', resp.status, hw;
            return false;   -- not cached: an error is not a "no"
        end if;
        answer := resp.content::jsonb;
        if coalesce(answer->>'status', '') <> 'ok' or coalesce(jsonb_typeof(answer->'entitled'), '') <> 'boolean' then
            return false;
        end if;
        ok := (answer->>'entitled')::boolean;
        insert into public.controller_wallpaper_grants as g (hardware_id, entitled, checked_at)
        values (hw, ok, now())
        on conflict (hardware_id) do update set entitled = excluded.entitled, checked_at = excluded.checked_at;
        return ok;
    exception when others then
        raise warning 'controller_wallpapers_entitled: % for %', sqlerrm, hw;
        return false;
    end;
end;
$function$;

revoke all on function public.controller_wallpapers_entitled(text, text) from public, anon, authenticated;

-- CREATE OR REPLACE only, never DROP + CREATE (see 20260804_device_id_aliases: a 404 here flips
-- clients onto a fallback that can deactivate a licensed car).
create or replace function public.get_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    hw text;
begin
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    hw := public.resolve_device_id(device_hw_id);

    if exists (
        select 1 from public.devices d
        where d.hardware_id = hw and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        join public.devices d on d.hardware_id = hw
        where w.channel = 'app'
          and (w.is_global = true or w.hardware_id = hw)
          and (w.target_mode is null or w.target_mode = d.mode)
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = hw
          )
        order by w.created_at desc;
    -- NEW (2026-11-01): a valid dashboard licence, unless an admin blocked the car.
    elsif not exists (select 1 from public.devices d where d.hardware_id = hw and d.is_blocked = true)
          and public.controller_wallpapers_entitled(
                hw, coalesce(nullif(legacy_hw_id, ''), nullif(device_hw_id, hw))) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.channel = 'app'
          and (w.is_global = true or w.hardware_id = hw)
          and (w.target_mode is null
               or w.target_mode = (select d.mode from public.devices d where d.hardware_id = hw))
          and not exists (
              select 1 from public.wallpaper_hides h
              where h.wallpaper_id = w.id and h.hardware_id = hw
          )
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

-- ROLLBACK: re-run the get_wallpapers above without the "NEW (2026-11-01)" elsif branch (that is
-- byte-for-byte the live definition read on 2026-11-01), then optionally
--   drop function public.controller_wallpapers_entitled(text, text);
--   drop table public.controller_wallpaper_grants;
