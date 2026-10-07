-- NOT YET APPLIED. Apply to the remote D1 (ts-activation) with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0008_controller_licence.sql
--
-- Controller licence (owner's decision 2026-10-07) for the ذبذبة Dashboard Controller
-- (com.thabthaba.controller, Leopard cars only). Additive only: four new tables, nothing
-- existing is altered, so applying it changes no behaviour until a Worker reads them.
--
--   controller_entitlements — one row per hardware id allowed to run the controller.
--       kind 'grandfather' = registered (activated) before 2026-10-07T21:00:00Z, written once by
--       tools/snapshot-controller-grandfather.mjs (snapshot_source pg.devices|pg.alias|d1.devices|d1.audit);
--       'code' = redeemed a 579xxxxxx code; 'manual' = granted by the admin.
--       revoked_at set = revoked; revoke is final — only an admin "restore" clears it, a new code
--       does NOT re-unlock a revoked car.
--   controller_codes — '579' + 6 random digits, single use, bound to the redeeming hardware id
--       (used_by), valid 30 minutes (expires_at).
--   controller_attempts — failed-redeem counter per hardware id (separate from devices.failed_attempts,
--       so the controller path never moves a car toward the activation auto-block).
--   controller_audit — append-only history.
--
-- All timestamps are ISO-8601 UTC strings.

CREATE TABLE IF NOT EXISTS controller_entitlements (
  hardware_id     TEXT PRIMARY KEY,
  kind            TEXT NOT NULL,   -- 'grandfather' | 'code' | 'manual'
  code            TEXT,
  granted_at      TEXT NOT NULL,
  granted_by      TEXT,
  revoked_at      TEXT,
  revoked_by      TEXT,
  revoke_reason   TEXT,
  note            TEXT,
  snapshot_source TEXT
);

CREATE TABLE IF NOT EXISTS controller_codes (
  code           TEXT PRIMARY KEY,
  issued_at      TEXT NOT NULL,
  expires_at     TEXT NOT NULL,
  issued_by      TEXT,
  note           TEXT,
  customer_phone TEXT,
  used_by        TEXT,
  used_at        TEXT,
  cancelled_at   TEXT
);

CREATE TABLE IF NOT EXISTS controller_attempts (
  hardware_id    TEXT PRIMARY KEY,
  failed         INTEGER NOT NULL DEFAULT 0,
  last_failed_at TEXT,
  locked_at      TEXT
);

CREATE TABLE IF NOT EXISTS controller_audit (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  hardware_id TEXT,
  action      TEXT,
  detail      TEXT,
  by          TEXT,
  at          TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now'))
);
