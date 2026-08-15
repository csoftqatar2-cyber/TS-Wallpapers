-- ============================================================================
-- D1 schema for the shared device-activation store (database: ts-activation)
--
-- This mirrors ONLY the activation-identity core of Supabase's public.devices:
--   hardware_id, serial_number, is_active, is_blocked, activated_at
--
-- Everything else stays in Postgres on purpose:
--   * mode / app_version / app_version_code / last_seen_at  — high-frequency
--     telemetry written on every 5-minute sync, fleet-wide. Nothing about it
--     needs to be authoritative here, and routing it through HTTP would put a
--     cross-cloud call on the hottest path in the system.
--   * wallpapers / wallpaper_hides — a car's private wallpapers are tied to its
--     hardware_id by a real FK with ON UPDATE CASCADE. That cascade is what
--     keeps the 84 car-specific wallpapers attached to their cars when a car
--     starts reporting its VIN and its identity is renamed. D1 has no such
--     cascade and must never drive an identity rename on its own.
--
-- Apply with:
--   npx wrangler d1 execute ts-activation --remote --file=./schema.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS devices (
  hardware_id   TEXT PRIMARY KEY,
  serial_number TEXT,
  is_active     INTEGER NOT NULL DEFAULT 0,
  is_blocked    INTEGER NOT NULL DEFAULT 0,
  -- ISO-8601 UTC. Written by whoever owns the clock (Postgres passes its own
  -- now() through so the two sides can be compared byte-for-byte).
  activated_at  TEXT,
  updated_at    TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
  -- 'activate' | 'migrate' | 'admin' | 'bulk_import' | 'reconcile'
  --           | 'failed_attempt' | 'auto_block'
  source        TEXT NOT NULL DEFAULT 'activate',

  -- Brute-force lock (see FAILED_ATTEMPT_LIMIT in worker.js). Consecutive
  -- rejected codes; a successful activation sets it back to 0. A row can exist
  -- with nothing but these columns filled in: that is a hardware id which has
  -- only ever been typed codes at, never licensed, and it is exactly the row the
  -- operator needs to see.
  failed_attempts    INTEGER NOT NULL DEFAULT 0,
  first_failed_at    TEXT,
  last_failed_at     TEXT,
  last_failed_serial TEXT,
  blocked_at         TEXT,
  -- 'failed_attempts' (automatic) | 'admin' (blocked by hand) | NULL
  block_reason       TEXT
);

-- Mirrors the UNIQUE constraint on Postgres devices.serial_number. This is the
-- constraint that makes one serial usable on exactly one car, i.e. the actual
-- licensing rule — it is the main reason D1 is the decision authority at all.
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_serial
  ON devices (serial_number) WHERE serial_number IS NOT NULL;

-- Append-only history. Cheap, and for a licensing system the ability to answer
-- "when did this car's activation change, and what changed it" is worth having.
CREATE TABLE IF NOT EXISTS devices_audit (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  hardware_id TEXT NOT NULL,
  action      TEXT NOT NULL,
  before_json TEXT,
  after_json  TEXT,
  at          TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_hw ON devices_audit (hardware_id, at DESC);
