-- Applied to the remote D1 (ts-activation) on 2026-09-03 with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0003_device_token.sql
-- Companion of supabase/migrations/20260904_device_token.sql. D1 is the authority for the
-- per-car token; Postgres mirrors token_hash. SQLite has no ADD COLUMN IF NOT EXISTS: re-running
-- this errors loudly and harmlessly (same as 0002_failed_attempts.sql).
ALTER TABLE devices ADD COLUMN token_hash      TEXT;
ALTER TABLE devices ADD COLUMN token_issued_at TEXT;
ALTER TABLE devices ADD COLUMN token_version   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN token_app_id    TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_token ON devices (token_hash) WHERE token_hash IS NOT NULL;
