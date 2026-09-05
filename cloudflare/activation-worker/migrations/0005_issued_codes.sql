-- Applied to the remote D1 (ts-activation) on 2026-09-05 with
--   npx wrangler d1 execute ts-activation --remote --file migrations/0005_issued_codes.sql
-- Codes minted on the spot from the admin site ("المولّد"): 578 + 6 random digits, valid for
-- ten minutes and for one car. The rule in /v1/devices/activate is ADDITIVE: a serial that is
-- not in this table follows exactly the rules that existed before (open 578 space, the sold
-- closed block 578300001-578300100, on-file 7078), so no car in the field and none of the
-- sold-but-unused codes is affected. A serial that IS here must be unexpired and unused.
-- Re-running is harmless (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS issued_codes (
  serial      TEXT PRIMARY KEY,
  issued_at   TEXT NOT NULL,
  expires_at  TEXT NOT NULL,
  issued_by   TEXT,
  used_by     TEXT,
  used_at     TEXT,
  note        TEXT
);
CREATE INDEX IF NOT EXISTS issued_codes_issued_at ON issued_codes (issued_at);
