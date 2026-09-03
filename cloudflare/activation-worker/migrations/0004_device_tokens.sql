-- Applied to the remote D1 (ts-activation) on 2026-09-03 with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0004_device_tokens.sql
-- One token per (car, app). Six apps (store, wallpapers, Back Button, TS Link, controller,
-- Leo dash) unlock from the same devices row but are separate APKs with separate private
-- storage, so a token issued to one app is unreadable by the others: a per-car token would
-- let only the first app enrol and make every later app look like a cloner.
-- The devices.token_* columns from 0003 become inert (never read or written again).
-- Re-running is harmless (IF NOT EXISTS / OR IGNORE).
CREATE TABLE IF NOT EXISTS device_tokens (
  hardware_id     TEXT NOT NULL,
  app_id          TEXT NOT NULL,
  token_hash      TEXT NOT NULL,
  token_issued_at TEXT NOT NULL,
  token_version   INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (hardware_id, app_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_tokens_hash ON device_tokens (token_hash);
-- Carry over the tokens issued under 0003 (per car) into their app's row.
INSERT OR IGNORE INTO device_tokens (hardware_id, app_id, token_hash, token_issued_at, token_version)
  SELECT hardware_id, COALESCE(token_app_id, 'wallpapers'), token_hash, token_issued_at, token_version
    FROM devices WHERE token_hash IS NOT NULL;
