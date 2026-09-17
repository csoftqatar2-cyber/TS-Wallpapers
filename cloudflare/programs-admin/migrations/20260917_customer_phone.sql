-- Run this file ONCE: SQLite ALTER TABLE ADD COLUMN has no IF NOT EXISTS, so a second run fails.
-- Adds the optional customer phone to issued codes and the durable car-to-customer association.
-- Apply to the shared remote D1 from cloudflare/programs-admin with:
--   npx wrangler d1 execute ts-activation --remote --file migrations/20260917_customer_phone.sql
ALTER TABLE issued_codes ADD COLUMN customer_phone TEXT;

CREATE TABLE IF NOT EXISTS car_customers (
  hw_id      TEXT PRIMARY KEY,
  phone      TEXT NOT NULL,
  serial     TEXT,
  bound_at   TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  note       TEXT
);

CREATE INDEX IF NOT EXISTS car_customers_phone ON car_customers (phone);
