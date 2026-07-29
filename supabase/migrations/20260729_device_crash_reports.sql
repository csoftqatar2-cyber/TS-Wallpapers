-- ============================================================================
-- Crash reports from the fleet.
--
-- Until now a crash on a car left no trace anyone could reach: the car is with
-- a customer, nobody is watching logcat, and Android's log ring has rolled over
-- long before a technician gets to it. Every fault therefore arrived as a
-- description of a symptom ("the screen goes black") with nothing to act on.
--
-- The app writes every crash to internal storage AND posts it here on the next
-- launch, so a fault on one car out of hundreds becomes something we can read,
-- fix, and ship an update for without anyone driving anywhere.
--
--   1. public.device_crashes  — one row per crash, tied to the device row.
--   2. public.report_crash()  — the anonymous device-facing RPC, same shape and
--                               same guarantees as report_device_mode().
-- ============================================================================

create table if not exists public.device_crashes (
    id            bigserial primary key,
    -- No foreign key on purpose. The report we most need is the one from a car
    -- that is NOT in a clean state — never activated, or wiped and re-imaged —
    -- and a reference would reject exactly that row.
    hardware_id   text not null,
    app_version   text,
    version_code  int,
    device_mode   text,
    -- The car's own clock as a string, not a timestamp: head units boot with a
    -- wrong clock all the time and a bad value must not fail the insert. Use
    -- received_at for anything you sort or filter on.
    crash_at      text,
    crash_text    text not null,
    received_at   timestamptz not null default now()
);

-- The two questions anyone actually asks: "what has this car been doing?" and
-- "what is breaking across the fleet right now?"
create index if not exists device_crashes_hardware_idx
    on public.device_crashes (hardware_id, received_at desc);
create index if not exists device_crashes_received_idx
    on public.device_crashes (received_at desc);

-- Read is for the manager (authenticated admin) only. Devices post through the
-- SECURITY DEFINER function below and never touch the table directly, so anon
-- gets no policy at all — a leaked anon key cannot read other cars' logs.
alter table public.device_crashes enable row level security;

drop policy if exists device_crashes_admin_read on public.device_crashes;
create policy device_crashes_admin_read
    on public.device_crashes for select
    to authenticated
    using (true);

-- ----------------------------------------------------------------------------
-- Device-facing RPC. Anonymous, SECURITY DEFINER, deliberately forgiving: a
-- crash report must never be the thing that errors on a car that is already
-- misbehaving. An unknown device still gets its report stored under whatever
-- hardware id it gave — the whole point is to hear from a car we may not have a
-- clean row for.
-- ----------------------------------------------------------------------------
create or replace function public.report_crash(
    device_hw_id     text,
    crash_text       text,
    app_version      text default null,
    app_version_code int  default null,
    device_mode      text default null,
    crash_at         text default null,
    legacy_hw_id     text default null
)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
    if device_hw_id is null or crash_text is null or length(trim(crash_text)) = 0 then
        return;
    end if;

    -- Same VIN-migration courtesy the other device RPCs give, so a car that has
    -- just started reporting its VIN still lands on its existing row.
    perform public.migrate_device_hardware_id(legacy_hw_id, device_hw_id);

    -- A runaway crash loop must not become a runaway table: one car can hold at
    -- most 50 reports, oldest dropped first.
    insert into public.device_crashes (
        hardware_id, app_version, version_code, device_mode,
        crash_at, crash_text
    ) values (
        device_hw_id, app_version, app_version_code, device_mode,
        crash_at, left(crash_text, 200000)
    );

    delete from public.device_crashes dc
     where dc.hardware_id = device_hw_id
       and dc.id not in (
            select d2.id from public.device_crashes d2
             where d2.hardware_id = device_hw_id
             order by d2.received_at desc
             limit 50
       );
end;
$function$;

revoke all on function public.report_crash(text, text, text, int, text, text, text) from public;
grant execute on function public.report_crash(text, text, text, int, text, text, text)
    to anon, authenticated;
