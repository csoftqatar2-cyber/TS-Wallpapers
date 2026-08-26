-- Closed code blocks: a fence around the serials we actually hand out.
--
-- WHY
-- The licensing rule has always been "any serial starting with 578 activates".
-- That means a customer who buys 578300001 activates a second car for free by
-- typing 578300002 — the code next to their own, and the one guess anyone
-- actually makes. A block registered here stops being open: inside it, only the
-- serials really issued are honored.
--
-- The rest of the 578 space is deliberately unchanged. This is not a change to
-- the licensing rule, it is a fence around one range of it.
--
-- WHERE THE REAL DECISION LIVES
-- cf_activation_write_enabled is on, so public.activate_device forwards to the
-- ts-activation Worker and THAT is the live authority. Its copy of this list is
-- CLOSED_BLOCKS in cloudflare/activation-worker/worker.js. What follows only
-- runs when the flag is turned off or the Worker's URL/secret is missing — an
-- incident. Without it, flipping that flag would silently reopen every sold
-- block at exactly the moment nobody is watching.
--
-- KEEP THE TWO LISTS IN STEP. Add a block here AND in worker.js BEFORE its codes
-- are handed to a customer; adding one afterwards rejects codes already paid for.
--
-- Applied to ihgmqwzdpugdzddobhbc on 2026-08-26 as migrations
-- closed_serial_blocks_in_legacy_activation + fix_null_logic_in_legacy_activation_fence.
-- This file is the combined final state.

create table if not exists cf.closed_serial_blocks (
  prefix        text primary key,
  digits        int  not null,
  first_issued  int  not null,
  last_issued   int  not null,
  note          text,
  created_at    timestamptz not null default now()
);

insert into cf.closed_serial_blocks (prefix, digits, first_issued, last_issued, note)
values ('578300', 3, 1, 100, 'Sold 2026-08-26: 578300001 … 578300100 (100 codes)')
on conflict (prefix) do update
   set digits       = excluded.digits,
       first_issued = excluded.first_issued,
       last_issued  = excluded.last_issued,
       note         = excluded.note;

-- True when the serial sits inside a closed block but is not one that block
-- issued. False for every serial outside every block, which is what keeps the
-- open 578 space behaving exactly as it always has.
--
-- STABLE, not IMMUTABLE: it reads cf.closed_serial_blocks, and marking a
-- table-reading function IMMUTABLE invites the planner to fold it to a constant.
create or replace function cf.serial_is_unissued_in_closed_block(activation_serial text)
returns boolean
language plpgsql
stable
set search_path to 'cf', 'public'
as $$
declare
    blk  record;
    tail text;
begin
    if activation_serial is null then
        return false;
    end if;

    for blk in select * from cf.closed_serial_blocks loop
        if activation_serial like blk.prefix || '%' then
            tail := substring(activation_serial from length(blk.prefix) + 1);

            -- Exact width matters: '5783000010' is an issued code with a digit
            -- appended, and must not read as 001.
            if tail !~ ('^[0-9]{' || blk.digits || '}$') then
                return true;
            end if;

            return tail::int < blk.first_issued or tail::int > blk.last_issued;
        end if;
    end loop;
    return false;
end;
$$;

create or replace function cf.activate_device_legacy(device_hw_id text, activation_serial text, legacy_hw_id text default null::text)
returns text
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
    existing_hw_id text;
    is_valid       boolean;
begin
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id then
        if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
           and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
            update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
        end if;
    end if;

    if exists (select 1 from public.devices where hardware_id = device_hw_id and is_blocked = true) then
        return 'blocked';
    end if;

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    -- `is not distinct from`, NOT `=`. When the serial is unowned, existing_hw_id
    -- is NULL and `existing_hw_id = device_hw_id` yields NULL rather than false;
    -- the whole OR-chain then collapses to NULL, `if not (NULL)` is not taken,
    -- and every rejected code falls through to 'success'. That is a fence that
    -- silently is not there — it is how the first version of this shipped and
    -- what the rollback-wrapped test caught. Keep every operand strictly boolean.
    --
    -- First operand: this car re-typing the code it already owns is honored ahead
    -- of every format rule, so no rule below can refuse a car already in the
    -- field. Mirrors `ownedByThisCar` in the Worker's handleActivate.
    is_valid :=
        (existing_hw_id is not distinct from device_hw_id)
        or (activation_serial like '578%'
            and not cf.serial_is_unissued_in_closed_block(activation_serial))
        or (activation_serial like '7078%' and existing_hw_id is not null);

    if not coalesce(is_valid, false) then
        return 'invalid_format';
    end if;

    if existing_hw_id is not null and existing_hw_id <> device_hw_id then
        return 'serial_already_used';
    end if;

    insert into public.devices (hardware_id, serial_number, is_active, activated_at)
    values (device_hw_id, activation_serial, true, now())
    on conflict (hardware_id)
    do update set serial_number = excluded.serial_number,
                  is_active     = true,
                  activated_at  = now();

    return 'success';
end;
$function$;
