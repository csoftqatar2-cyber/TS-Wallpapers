-- 2026-11-02 — a valid ذبذبة Dashboard licence also activates ذبذبة ستور and BackButton.
-- APPLIED 2026-11-02 to ihgmqwzdpugdzddobhbc (owner approved: «الداشبورد هو البرنامج الأساسي، لو اتفعل كل البرامج التانية تتفعل»,
-- covering TS Link / Leo / the dashboard family flag). Checked after: 03T true/active, store car unchanged.
--
-- WHY: owner rule 2026-11-02: ANY dashboard licence (D1 ts-activation controller_entitlements, not
-- revoked) must activate ذبذبة ستور, BackButton and TS Wallpapers on that car. The REVERSE stays false:
-- a store activation does NOT give a dashboard licence (thab-voice /v1/controller/licence is untouched).
-- Trigger: iCar 03T VIN-LVVETHCG3VD701920 (licensed manually in controller_entitlements) showed
-- BackButton "not activated". TS Wallpapers was done on 2026-11-01 (get_wallpapers, 20261101_*).
--
-- WHAT the apps already on the cars ask (no app update needed):
--   * ذبذبة ستور  : is_device_activated(device_hw_id) — once for the VIN id, once for the legacy id
--                   (is_device_activated_v2 answers false for a token car → TOKEN_FALLBACK_TO_V1=true asks
--                   v1); licence_status_v2 only acts on 'blocked'/'active', 'inactive' is ignored.
--   * BackButton  : get_device_status(device_hw_id, legacy_hw_id) — 'active' grants, 'blocked' revokes,
--                   'inactive'/'unknown' keep the stored flag; is_device_activated only on a 404.
--   * TS Wallpapers: get_wallpapers (done 11-01); get_device_status is read only for 'blocked'.
-- So the two RPCs below get the same branch get_wallpapers got: when the devices row does not
-- already answer "active", ask controller_licence_unlocks() — the existing controller_wallpapers_entitled
-- Worker call + cache (controller_wallpaper_grants: yes 6 h / no 15 min, 1.5 s, errors not cached,
-- fail closed) behind two block checks.
--
-- BLOCKS STILL WIN: a devices row with is_blocked under the car's id or legacy id, or a store_installs
-- row with blocked=true under either id, means NO grant through this path. get_device_status still
-- answers 'blocked' for a blocked devices row before anything else, exactly as today. A revoked
-- dashboard licence (revoked_at set) → the Worker answers entitled=false → nothing is granted.
--
-- UNCHANGED: every path that answered 'active'/true before answers the same, with no Worker call
-- (the activated branch is checked first and returns). devices / device_tokens / store_installs are
-- never written. is_device_activated_v2, licence_status_v2, activate_device*, store_check_in,
-- get_wallpapers are not touched.
--
-- SIDE EFFECTS TO KNOW (same RPCs, no app id in the call, so cannot be scoped server-side):
--   * The dashboard's own Activation (family flag) reads get_device_status too → a licensed car now
--     also reads "family activated". ControllerLicence still lets a revoke win (KEY_REVOKED first).
--   * TS Link and the old Leo launcher read get_device_status / is_device_activated as well → they
--     unlock on a dashboard-licensed car too.
--   * The apps cache a grant locally and clear it only on 'blocked'; a later dashboard revoke therefore
--     stops NEW grants (≤ 6 h cache) but does not lock an app that already opened. That is the same
--     property a withdrawn store activation has today.
--
-- DEPLOY: needs the activation Worker with /v1/controller/entitled (live since 2026-11-01, d385b997)
-- and controller_wallpapers_entitled (20261101_*, applied). Rollback: 20261102_companions_controller_licence_ROLLBACK.sql.

-- ---------------------------------------------------------------------------------------------------
-- Helper: does a valid dashboard licence unlock the companion apps on this car?
-- Not callable by clients (it reaches the Worker); only the SECURITY DEFINER RPCs below call it.
-- ---------------------------------------------------------------------------------------------------
create or replace function public.controller_licence_unlocks(p_hw text, p_legacy text default null::text)
returns boolean
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
    hw text;
    lg text := nullif(btrim(coalesce(p_legacy, '')), '');
begin
    if p_hw is null or btrim(p_hw) = '' then
        return false;
    end if;
    hw := public.resolve_device_id(p_hw);

    -- An admin block on the car, under any id it answers to, wins over the licence.
    if exists (
        select 1 from public.devices d
         where d.is_blocked = true
           and d.hardware_id in (hw, p_hw, lg, public.resolve_device_id(lg))
    ) then
        return false;
    end if;

    -- A store block (store_installs.blocked: admin block or the wrong-code lock) wins too.
    if exists (
        select 1 from public.store_installs s
         where s.blocked = true
           and s.hw_id in (hw, p_hw, lg)
    ) then
        return false;
    end if;

    -- The Worker checks the id, then the legacy id (D1 controller_entitlements, revoked_at NULL).
    -- Same id pair get_wallpapers sends: the raw legacy id, else the raw id when an alias moved it.
    return public.controller_wallpapers_entitled(hw, coalesce(lg, nullif(p_hw, hw)));
end;
$$;

revoke all on function public.controller_licence_unlocks(text, text) from public, anon, authenticated;
grant execute on function public.controller_licence_unlocks(text, text) to service_role;

-- ---------------------------------------------------------------------------------------------------
-- is_device_activated — ذبذبة ستور (v1, every check) and BackButton's 404 fallback.
-- First statement is the live definition, unchanged; the licence is asked only when it says false.
-- (CREATE OR REPLACE keeps the existing grants: PUBLIC, anon, authenticated, service_role.)
-- ---------------------------------------------------------------------------------------------------
create or replace function public.is_device_activated(device_hw_id text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
    if exists (
        select 1
          from public.devices d
         where d.hardware_id = public.resolve_device_id(is_device_activated.device_hw_id)
           and d.is_active = true
           and d.is_blocked = false
    ) then
        return true;
    end if;
    -- NEW (2026-11-02): a valid dashboard licence, unless the car is blocked.
    return public.controller_licence_unlocks(is_device_activated.device_hw_id, null);
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- get_device_status — BackButton (primary), TS Wallpapers (blocked only), dashboard, TS Link, Leo.
-- 'blocked' and 'active' from the devices row answer exactly as before. Only 'unknown'/'inactive'
-- become 'active' when the dashboard licence unlocks the car.
-- ---------------------------------------------------------------------------------------------------
create or replace function public.get_device_status(device_hw_id text, legacy_hw_id text default null::text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    d       public.devices;
    have_d  boolean;
begin
    select * into d from public.devices
     where hardware_id = public.resolve_device_id(device_hw_id);
    have_d := found;

    if not have_d and legacy_hw_id is not null and legacy_hw_id <> '' then
        select * into d from public.devices
         where hardware_id = public.resolve_device_id(legacy_hw_id);
        have_d := found;
    end if;

    if have_d and d.is_blocked then return 'blocked'; end if;  -- blocked: no serial will help
    if have_d and d.is_active  then return 'active';  end if;

    -- NEW (2026-11-02): a valid dashboard licence, unless the car is blocked.
    if public.controller_licence_unlocks(device_hw_id, legacy_hw_id) then
        return 'active';
    end if;

    if not have_d then return 'unknown'; end if;               -- never registered
    return 'inactive';                                          -- registered, activation withdrawn
end;
$$;
