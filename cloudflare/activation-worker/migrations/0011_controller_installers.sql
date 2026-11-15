-- NOT YET APPLIED. Apply ONCE to the remote D1 (ts-activation), only on the owner's go:
--   npx wrangler d1 execute ts-activation --remote --file=./migrations/0011_controller_installers.sql
-- Additive only: three new tables + one index, nothing existing is altered. It changes no behaviour until
-- thab-voice reads them, and thab-voice reads them only while its INSTALL_GRANT var is "on".
--
-- Automatic dashboard licence by installer grant (owner approved 2026-11-15).
-- The installer (the RedMagic phone app or the .bat on one of the three laptops) signs a short grant for the
-- car it is installing, while the car sits on the shop Wi-Fi. The car presents it to thab-voice
-- POST /v1/controller/install-grant (Thabthaba Dashboard worker/src/licence.js), which checks it and writes a
-- controller_entitlements row kind 'installer', granted_by 'installer:<iid>' (INSERT OR IGNORE: an existing
-- row — live or revoked — is never touched). No new entitlement column is needed: kind/granted_by/note/
-- snapshot_source already exist (0008) and tier (thab-voice migrations-activation/20261031) defaults to 'full';
-- a Jetour G700 grant writes tier 'core' (owner 2026-11-15).
--
--   controller_installers     one row per trusted installer. id 'redmagic-<8hex>' | 'laptop-<HOSTNAME>';
--                             pubkey = ECDSA P-256 public key, SPKI DER, standard base64. revoked_at set = that
--                             installer's grants are refused (grant_error 'revoked_installer'); entitlements it
--                             already wrote stay (revoke those one by one in «تراخيص لوحة التحكم»).
--   controller_shop_wifi      the shop networks a grant may name. Matching is on the normalized ssid
--                             (lowercase, spaces/'_'/'-' removed), so 'SOFTWARE 5G' = 'software_5g'. bssid NULL =
--                             any access point; set = the grant's bssid must equal it (case-insensitive).
--                             enabled 0 = refused (grant_error 'ssid_not_allowed').
--   controller_install_grants every claim, accepted or refused, for audit. result 'ok' = accepted (granted 1 when
--                             it wrote a new entitlement row, 0 when the car already had one); anything else is the
--                             grant_error code. A nonce is spent only by an accepted claim (partial unique index).
--                             Refused claims never touch controller_attempts (the 10-try code lock).
-- All timestamps are ISO-8601 UTC strings except iat (the grant's own Unix seconds).

CREATE TABLE IF NOT EXISTS controller_installers (
  id         TEXT PRIMARY KEY,
  name       TEXT,
  pubkey     TEXT NOT NULL,
  added_at   TEXT NOT NULL,
  added_by   TEXT,
  revoked_at TEXT,
  revoked_by TEXT,
  note       TEXT
);

CREATE TABLE IF NOT EXISTS controller_shop_wifi (
  ssid       TEXT PRIMARY KEY,
  bssid      TEXT,
  enabled    INTEGER NOT NULL DEFAULT 1,
  added_at   TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
  added_by   TEXT,
  updated_at TEXT
);

INSERT OR IGNORE INTO controller_shop_wifi (ssid, enabled, added_by) VALUES
  ('ABOOD', 1, 'migration-0011'),
  ('ABOOD_5G', 1, 'migration-0011'),
  ('SOFTWARE', 1, 'migration-0011'),
  ('SOFTWARE 5G', 1, 'migration-0011'),
  ('Thabthaba_Store', 1, 'migration-0011'),
  ('Thabthaba_Guest', 1, 'migration-0011');

CREATE TABLE IF NOT EXISTS controller_install_grants (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  nonce      TEXT,
  iid        TEXT,
  hw         TEXT,      -- payload.hw (signed)
  req_hw     TEXT,      -- the request's hardware_id (the id the entitlement is written on)
  ssid       TEXT,
  bssid      TEXT,
  src        TEXT,      -- 'car' | 'installer'
  ip         TEXT,      -- CF-Connecting-IP
  iat        INTEGER,
  used_at    TEXT NOT NULL,
  result     TEXT NOT NULL,   -- 'ok' | bad_sig | unknown_installer | revoked_installer | hw_mismatch | expired | future | replay | ssid_not_allowed | disabled
  granted    INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS controller_install_grants_nonce_ok
  ON controller_install_grants (nonce) WHERE result = 'ok';
CREATE INDEX IF NOT EXISTS controller_install_grants_hw ON controller_install_grants (req_hw, used_at);
