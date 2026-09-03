-- PENDING: not applied. Companion of supabase/migrations/20260904_device_token_PENDING.sql.
-- D1 is the authority for the per-car token; Postgres mirrors it.
-- SQLite has no ADD COLUMN IF NOT EXISTS: re-running this errors loudly and harmlessly
-- (same as 0002_failed_attempts.sql).
ALTER TABLE devices ADD COLUMN token_hash      TEXT;
ALTER TABLE devices ADD COLUMN token_issued_at TEXT;
ALTER TABLE devices ADD COLUMN token_version   INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_token ON devices (token_hash) WHERE token_hash IS NOT NULL;
-- Worker routes to add with this (worker.js router):
--   POST /v1/devices/enroll   {hardware_id, app_version_code}  -> {status:'enrolled'|'already_enrolled'|'refused', token|null}
--        UPDATE devices SET token_hash=?,token_issued_at=?,token_version=token_version+1
--         WHERE hardware_id=? AND token_hash IS NULL AND is_active=1 AND is_blocked=0   -- race guard
--   POST /v1/devices/verify   {hardware_id, token} -> {active: bool}   (timing-safe compare of sha256)
--   POST /v1/devices/set-state gains {reset_token:true}  -> clears token_hash, bumps token_version (operator path)
