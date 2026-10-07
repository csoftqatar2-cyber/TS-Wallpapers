-- NOT YET APPLIED. Apply to the remote D1 (ts-activation) with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0009_device_fingerprints.sql
--
-- Self-healing controller token (2026-10-07). A BYD UI5->UI6 format wipes the dashboard's private
-- storage: the controller loses its token while its hardware id (VIN-<vin>) and
-- device_tokens(hw,'controller') survive, so enrol answers already_enrolled forever. The thab-voice
-- Worker (Thabthaba Dashboard repo, worker/src/enrol.js) binds a hardware fingerprint per car here and
-- rotates the controller token for a car that presents the same fingerprint again.
--
--   fp_hash      sha256(fingerprint + FP_PEPPER secret of thab-voice) — the raw fingerprint is never stored.
--   bound_by     'enroll' (bound at first mint) | 'bind' (POST /v1/controller/fingerprint with a valid token)
--                | 'serial' (re-bound by the car's own activation serial).
--   rotate_day / rotate_count  fingerprint rotations per UTC day (capped at 3 by the Worker).
--
-- Additive only: one new table, nothing existing is altered, so applying it changes no behaviour until
-- a Worker reads it. Re-running is harmless (IF NOT EXISTS). All timestamps ISO-8601 UTC.
CREATE TABLE IF NOT EXISTS device_fingerprints (
  hardware_id  TEXT PRIMARY KEY,
  fp_hash      TEXT NOT NULL,
  bound_at     TEXT NOT NULL,
  bound_by     TEXT NOT NULL,
  last_seen_at TEXT,
  rotate_day   TEXT,
  rotate_count INTEGER NOT NULL DEFAULT 0
);
