-- NOT YET APPLIED (2026-10-10). Apply to the remote D1 (ts-activation) BEFORE deploying the Worker
-- that reads it:
--   npx wrangler d1 execute ts-activation --remote --file migrations/0010_device_fp_rotations.sql
--
-- Same-unit fingerprint for every app (worker.js fingerprintProof): a companion app (wallpapers,
-- store, tslink, …) whose token was wiped by a format/reinstall gets it rotated when it presents the
-- per-car fingerprint bound in device_fingerprints (0009). This table caps those rotations at 3 per
-- (car, app) per UTC day. Additive only; re-running is harmless.
CREATE TABLE IF NOT EXISTS device_fp_rotations (
  hardware_id  TEXT NOT NULL,
  app_id       TEXT NOT NULL,
  rotate_day   TEXT NOT NULL,
  rotate_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (hardware_id, app_id)
);
