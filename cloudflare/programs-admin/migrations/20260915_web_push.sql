-- Web Push for the admin panel (programs-admin push.mjs). ADDITIVE ONLY: three new tables, nothing
-- existing is altered or dropped. Shared D1 `ts-activation`; applied by hand:
--   npx wrangler d1 execute ts-activation --remote --file=./migrations/20260915_web_push.sql
-- ROLLBACK (only if the feature is removed): DROP TABLE push_subscriptions; DROP TABLE push_state; DROP TABLE push_log;

-- One row per subscribed browser (owner's phones/laptops). id = sha256(endpoint) hex.
-- Only an authenticated admin session (Access + Supabase admin JWT) can insert here.
CREATE TABLE IF NOT EXISTS push_subscriptions (
  id             TEXT PRIMARY KEY,
  endpoint       TEXT NOT NULL UNIQUE,
  p256dh         TEXT NOT NULL,
  auth           TEXT NOT NULL,
  label          TEXT,
  ua             TEXT,
  session_prefix TEXT,
  created_at     TEXT NOT NULL,
  last_ok_at     TEXT,
  last_test_at   TEXT,
  last_error     TEXT,
  fail_count     INTEGER NOT NULL DEFAULT 0
);

-- Cron cursors: audit_id (devices_audit.id), security_id (security_events.id). Compare-and-set updates.
CREATE TABLE IF NOT EXISTS push_state (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- What the cron / test button sent (counts only, no payloads). Pruned to 14 days by the cron.
CREATE TABLE IF NOT EXISTS push_log (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     TEXT NOT NULL,
  kind   TEXT NOT NULL,
  ref    TEXT,
  sent   INTEGER NOT NULL DEFAULT 0,
  failed INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS push_log_at ON push_log (at);
