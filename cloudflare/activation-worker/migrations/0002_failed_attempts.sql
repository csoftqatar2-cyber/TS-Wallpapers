-- ============================================================================
-- Brute-force lock for the activation service (D1 database: ts-activation)
--
-- Adds the consecutive-failed-code counter that blocks a hardware id after
-- FAILED_ATTEMPT_LIMIT rejected codes. See worker.js.
--
-- Apply ONCE, against the live database:
--   npx wrangler d1 execute ts-activation --remote --file=./migrations/0002_failed_attempts.sql
--
-- SQLite has no ADD COLUMN IF NOT EXISTS: re-running this errors with
-- "duplicate column name", which is loud and harmless. schema.sql already
-- carries these columns for a database created from scratch.
-- ============================================================================

ALTER TABLE devices ADD COLUMN failed_attempts    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN first_failed_at    TEXT;
ALTER TABLE devices ADD COLUMN last_failed_at     TEXT;
ALTER TABLE devices ADD COLUMN last_failed_serial TEXT;
ALTER TABLE devices ADD COLUMN blocked_at         TEXT;
ALTER TABLE devices ADD COLUMN block_reason       TEXT;

-- The dashboard's "who is knocking" view: cars with a failure run, worst first.
-- Partial, so it costs nothing for the ~700 cars that have never failed a code.
CREATE INDEX IF NOT EXISTS idx_devices_failed
  ON devices (failed_attempts DESC) WHERE failed_attempts > 0;
