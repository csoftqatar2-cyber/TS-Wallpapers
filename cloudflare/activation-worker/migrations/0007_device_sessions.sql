-- Applied to the remote D1 (ts-activation) on 2026-09-05 with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0007_device_sessions.sql
-- Admin site: the Supabase refresh token never reaches the browser any more. Each of the owner's
-- devices gets a random id in an HttpOnly cookie; the refresh token lives here, keyed by that id,
-- and the Worker exchanges it for short-lived access tokens on request. A stolen browser storage
-- yields nothing durable; a stolen device is revoked from the panel (revoked = 1).
CREATE TABLE IF NOT EXISTS device_sessions (
  id            TEXT PRIMARY KEY,
  refresh_token TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  last_seen     TEXT NOT NULL,
  ip            TEXT,
  ua            TEXT,
  email         TEXT,
  label         TEXT,
  revoked       INTEGER NOT NULL DEFAULT 0
);
