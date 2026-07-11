-- ============================================================================
-- PENDING HARDENING MIGRATION — not yet applied to the live project.
-- Apply in the Supabase SQL Editor (or ask the agent to apply it via MCP).
--
-- Fixes two security-advisor findings WITHOUT any effect on installed devices:
--
-- 1) Pins search_path on the two device-facing SECURITY DEFINER RPCs
--    (advisor 0011 "Function Search Path Mutable"). Bodies are byte-identical
--    to production; only "SET search_path" is added.
--
-- 2) Removes ANONYMOUS LISTING of storage objects (advisor 0025). Anyone with
--    the public anon key could previously enumerate every file in the
--    'wallpapers' bucket — including private per-car wallpapers whose only
--    protection is their unguessable UUID filename. Both buckets are
--    public=true, so devices keep downloading via
--    /storage/v1/object/public/... which does NOT consult these policies.
--    Only the list/search API is affected, and it becomes admin-only.
--
-- Device safety review (why nothing breaks):
--   * App downloads wallpapers/APKs via public object URLs  -> unaffected.
--   * App calls get_wallpapers / activate_device RPCs        -> same behavior.
--   * Manager dashboard never lists storage objects          -> unaffected.
--   * CI uploads with service_role                            -> bypasses RLS.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_wallpapers(device_hw_id text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS TABLE(url text, type text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
    -- One-time in-place migration: an already-registered device that now reports a
    -- VIN keeps its row (activation, wallpapers, block status) under the new id.
    if legacy_hw_id is not null and legacy_hw_id <> '' and legacy_hw_id <> device_hw_id then
        if not exists (select 1 from public.devices d where d.hardware_id = device_hw_id)
           and exists (select 1 from public.devices d where d.hardware_id = legacy_hw_id) then
            update public.devices set hardware_id = device_hw_id where hardware_id = legacy_hw_id;
        end if;
    end if;

    if exists (
        select 1 from public.devices d
        where d.hardware_id = device_hw_id and d.is_active = true and d.is_blocked = false
    ) then
        return query
        select w.url, w.type
        from public.wallpapers w
        where w.is_global = true or w.hardware_id = device_hw_id
        order by w.created_at desc;
    else
        return query select 'inactive'::text, 'image'::text;
    end if;
end;
$function$;

CREATE OR REPLACE FUNCTION public.activate_device(device_hw_id text, activation_serial text, legacy_hw_id text DEFAULT NULL::text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    existing_hw_id text;
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

    if activation_serial not like '7078%' then
        return 'invalid_format';
    end if;

    select hardware_id into existing_hw_id
    from public.devices
    where serial_number = activation_serial;

    if existing_hw_id is not null and existing_hw_id <> device_hw_id then
        return 'serial_already_used';
    end if;

    insert into public.devices (hardware_id, serial_number, is_active)
    values (device_hw_id, activation_serial, true)
    on conflict (hardware_id)
    do update set serial_number = excluded.serial_number, is_active = true;

    return 'success';
end;
$function$;

drop policy if exists "apk anon read" on storage.objects;
drop policy if exists "Allow anon select to wallpapers" on storage.objects;

create policy "apk storage admin read"
on storage.objects for select to authenticated
using (bucket_id = 'apk' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);

create policy "wp storage admin read"
on storage.objects for select to authenticated
using (bucket_id = 'wallpapers' and auth.uid() = '5b8e1336-ce54-4dd9-bd23-243158c178fe'::uuid);
