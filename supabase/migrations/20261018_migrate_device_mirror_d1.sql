-- 2026-10-18 — migrate_device_hardware_id tells D1 about the rename.
--
-- WHY: the read RPCs (get_wallpapers, get_gwm/jetour/leopard_wallpapers, report_crash,
-- report_device_mode) rename a car's row from its legacy id (AID-/MAC-/SYS-/…) to its VIN through
-- this function, in Postgres only. D1 (ts-activation) kept the old name holding the serial, so:
--   * the nightly cf.reconcile_activation push of the VIN row died on D1's UNIQUE(serial_number)
--     — 'push HTTP 500' every night from 2026-08-05 to 2026-10-18, nothing reconciled at all;
--   * 114 activated cars had no D1 row under their VIN, and the ذبذبة controller's store gate (which
--     reads D1) refused their codes as «not activated» (VIN-byd96F0BA85E398916B, 2026-10-18).
-- The activation Worker already has POST /v1/devices/migrate (the same rename, same guards, plus
-- tokens / controller licence / fingerprint / phone link since 2026-10-18). This calls it right after
-- the local rename. Best-effort by design: a Worker that is down or slow must never fail the read RPC
-- that triggered the rename — the nightly reconcile (whose bulk-upsert now repairs «same serial,
-- different id» itself) is the safety net.
--
-- Body identical to the live definition of 2026-10-18 up to the alias insert; only the block
-- after it is new.

create or replace function public.migrate_device_hardware_id(old_id text, new_id text)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
    secret text;
    resp   extensions.http_response;
begin
    if old_id is null or old_id = '' or new_id is null or new_id = '' or old_id = new_id then
        return;
    end if;
    -- Direction guard (2026-09-03): only legacy -> VIN, or a case-only VIN rename.
    if new_id not like 'VIN-%' then
        return;
    end if;
    if old_id like 'VIN-%' and upper(old_id) <> upper(new_id) then
        return;
    end if;
    if exists (select 1 from public.devices d where d.hardware_id = new_id)
       or not exists (select 1 from public.devices d where d.hardware_id = old_id) then
        return;
    end if;
    update public.devices        set hardware_id = new_id where hardware_id = old_id;
    update public.wallpapers     set hardware_id = new_id where hardware_id = old_id;
    update public.wallpaper_hides set hardware_id = new_id where hardware_id = old_id;
    delete from public.device_id_aliases a
     where a.old_id = migrate_device_hardware_id.old_id;
    insert into public.device_id_aliases (old_id, current_id)
    values (migrate_device_hardware_id.old_id, migrate_device_hardware_id.new_id);

    -- NEW (2026-10-18): keep D1 in step. Never raises.
    begin
        select decrypted_secret into secret from vault.decrypted_secrets
         where name = 'cf_activation_worker_secret';
        if secret is not null then
            perform extensions.http_set_curlopt('CURLOPT_TIMEOUT_MS', '3000');
            perform extensions.http_set_curlopt('CURLOPT_CONNECTTIMEOUT_MS', '1500');
            select * into resp from extensions.http((
                'POST', 'https://ts-activation.tsdash-qatar.workers.dev/v1/devices/migrate',
                array[extensions.http_header('Authorization', 'Bearer ' || secret)],
                'application/json',
                json_build_object('old_id', migrate_device_hardware_id.old_id,
                                  'new_id', migrate_device_hardware_id.new_id)::text
            )::extensions.http_request);
        end if;
    exception when others then
        raise warning 'migrate_device_hardware_id: D1 mirror failed (% -> %): %', old_id, new_id, sqlerrm;
    end;
end;
$function$;

-- ROLLBACK: re-run the definition above without the "NEW (2026-10-18)" block (and without the
-- `declare` section) — that is byte-for-byte the definition that was live before this migration.
