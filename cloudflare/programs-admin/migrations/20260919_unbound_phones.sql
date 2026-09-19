-- Re-runnable: preserves customer phone numbers from expired, unused activation codes.
-- Apply to the shared remote D1 from cloudflare/programs-admin with:
--   npx wrangler d1 execute ts-activation --remote --file migrations/20260919_unbound_phones.sql
CREATE TABLE IF NOT EXISTS unbound_phones (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  phone        TEXT NOT NULL,
  serial       TEXT,
  issued_at    TEXT,
  expired_at   TEXT NOT NULL,
  issued_by    TEXT,
  note         TEXT,
  dismissed_at TEXT,
  linked_hw    TEXT,
  linked_at    TEXT
);

CREATE INDEX IF NOT EXISTS unbound_phones_status_expiry
  ON unbound_phones (dismissed_at, expired_at);
