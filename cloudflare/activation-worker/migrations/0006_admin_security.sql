-- Applied to the remote D1 (ts-activation) on 2026-09-05 with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0006_admin_security.sql
-- Used by the admin site Worker (thabthaba-programs-admin), which shares this database:
-- every admin login goes through the Worker so wrong passwords can be counted per client;
-- five failures in fifteen minutes block that client (IP + Access identity) for 24 hours
-- and raise a security event the panel shows as a notification. Nothing here is read by
-- the activation path of the cars.
CREATE TABLE IF NOT EXISTS login_attempts (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     TEXT NOT NULL,
  ip     TEXT NOT NULL,
  email  TEXT,
  ua     TEXT,
  ok     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS login_attempts_ip_at ON login_attempts (ip, at);
CREATE TABLE IF NOT EXISTS blocked_clients (
  ip         TEXT PRIMARY KEY,
  email      TEXT,
  at         TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  reason     TEXT
);
CREATE TABLE IF NOT EXISTS security_events (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     TEXT NOT NULL,
  kind   TEXT NOT NULL,
  ip     TEXT,
  email  TEXT,
  detail TEXT
);
CREATE INDEX IF NOT EXISTS security_events_at ON security_events (at);
