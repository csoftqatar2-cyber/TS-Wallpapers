-- An id a car used to have keeps answering for it.
--
-- WHY
-- Three separately-shipped apps share public.devices, and each one works out the car's
-- hardware_id with its OWN copy of the discovery code. They do not agree, and they cannot be
-- made to: TS Back Button has no update channel at all, so whatever it computes today it will
-- keep computing for years.
--
-- migrate_device_hardware_id RENAMES the row when a car discovers its VIN. That is right for
-- the app that discovered it and fatal for the others: they go on asking
-- is_device_activated('<the id they were activated under>'), which is an exact match on a row
-- that no longer carries that name, get back a perfectly successful `false`, and — per the
-- clients' own logic — write activated = false and lock the customer out. Re-entering the
-- serial then fails with serial_already_used, because the serial is on the renamed row. The
-- car is stuck, and nothing in the system says why.
--
-- This is not hypothetical. TS Wallpapers knows six VIN properties and a settings scan; Back
-- Button knows two properties. Every car in the gap between those two lists — a BYD under
-- persist.sys.dms.config.vin, a Geely under geely_gpt_vin, a Chery under persist.sys.navi.vin
-- — is a car where one app renames the row out from under the other two.
--
-- WHAT
-- The rename stays (the wallpapers and hides ride along with it through the FK cascade, and
-- the VIN is the identity that survives a factory reset). What changes is that the old name is
-- no longer thrown away: it is recorded, and the read paths accept it.
--
-- SAFETY
-- The table starts empty, and every branch added below is guarded by a lookup in it. On the
-- day this is applied, every RPC therefore behaves byte-for-byte as it does now for every one
-- of the ~530 cars on record. Only a rename that happens AFTER this point can change any
-- answer, and the only answer it changes is "not activated" -> "activated" for a car that was
-- already activated. Nothing here can deactivate anything.
--
-- Aliases are resolved on READ only (is_device_activated, get_wallpapers, get_gwm_wallpapers).
-- activate_device is untouched: consuming a serial must go on meaning exactly what it means
-- today.
--
-- Rollback: 20260804_device_id_aliases_ROLLBACK.sql

create table if not exists public.device_id_aliases (
    old_id     text primary key,
    -- ON UPDATE CASCADE keeps a chain correct for free: rename A->B (alias A->B), later
    -- rename B->C, and this row follows to A->C without anyone having to walk the chain.
    -- ON DELETE CASCADE means removing a device takes its old names with it.
    current_id text not null
        references public.devices(hardware_id) on update cascade on delete cascade,
    created_at timestamptz not null default now()
);

create index if not exists device_id_aliases_current_id_idx
    on public.device_id_aliases (current_id);

-- Same posture as the rest of the schema: nothing reaches this table except the SECURITY
-- DEFINER functions below, so RLS on with no policy is the correct "nobody" default.
alter table public.device_id_aliases enable row level security;

-- ---------------------------------------------------------------------------
-- The rename, now with a forwarding address.
-- ---------------------------------------------------------------------------
create or replace function public.migrate_device_hardware_id(old_id text, new_id text)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    if old_id is null or old_id = '' or new_id is null or new_id = '' or old_id = new_id then
        return;
    end if;
    -- Only ever migrate INTO an id that does not exist yet, so two devices can never be merged.
    if exists (select 1 from public.devices d where d.hardware_id = new_id)
       or not exists (select 1 from public.devices d where d.hardware_id = old_id) then
        return;
    end if;
    update public.devices        set hardware_id = new_id where hardware_id = old_id;
    update public.wallpapers     set hardware_id = new_id where hardware_id = old_id;
    update public.wallpaper_hides set hardware_id = new_id where hardware_id = old_id;
    -- The forwarding address, in the same transaction as the rename it describes.
    --
    -- Delete-then-insert rather than ON CONFLICT, and the alias `a` is not decoration: this
    -- function's parameters are called old_id and new_id, which are also column names here.
    -- plpgsql resolves a bare `old_id` to the PARAMETER, so `on conflict (old_id)` is an
    -- outright error and `where old_id = old_id` would silently be `where 'X' = 'X'` and empty
    -- the table. Every reference below therefore says which one it means.
    delete from public.device_id_aliases a
     where a.old_id = migrate_device_hardware_id.old_id;
    insert into public.device_id_aliases (old_id, current_id)
    values (migrate_device_hardware_id.old_id, migrate_device_hardware_id.new_id);
end;
$function$;

-- ---------------------------------------------------------------------------
-- Resolution, in one place.
-- ---------------------------------------------------------------------------
-- A real row always wins over a forwarding address. That matters when an id is reused: if some
-- device genuinely holds this hardware_id today, its own state is the answer — including when
-- that answer is "blocked" or "not active". Only when nothing holds the name do we ask where
-- it went.
create or replace function public.resolve_device_id(asked text)
 returns text
 language sql
 stable
 security definer
 set search_path to 'public'
as $function$
  select coalesce(
    (select d.hardware_id from public.devices d where d.hardware_id = resolve_device_id.asked),
    (select a.current_id  from public.device_id_aliases a where a.old_id = resolve_device_id.asked),
    resolve_device_id.asked
  );
$function$;

-- ---------------------------------------------------------------------------
-- The three read paths.
-- ---------------------------------------------------------------------------
-- CREATE OR REPLACE only, never DROP + CREATE: a 404 from is_device_activated, even for the
-- one instant of a transaction, sends the clients down a fallback that reads "activated" from
-- get_wallpapers returning something containing "http" — so a licensed car with no
-- car-specific wallpapers would deactivate itself.
create or replace function public.is_device_activated(device_hw_id text)
 returns boolean
 language sql
 security definer
 set search_path to 'public'
as $function$
  select exists (
    select 1
    from public.devices d
    where d.hardware_id = public.resolve_device_id(is_device_activated.device_hw_id)
      and d.is_active = true
      and d.is_blocked = false
  );
$function$;

create or replace function public.get_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
 returns table(url text, type text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    hw text;
begin
    -- An already-registered device that now reports a VIN keeps its row
    -- (activation, wallpapers, hides, block status) under the new id.
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);
    -- ...and an app that has not learned the new id yet is still asking about the same car.
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
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

create or replace function public.get_gwm_wallpapers(device_hw_id text, legacy_hw_id text default null::text)
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
        where w.channel = 'gwm_split'
          and (w.is_global = true or w.hardware_id = hw)
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;
