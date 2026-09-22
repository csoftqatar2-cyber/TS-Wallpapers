-- Controller telemetry bridge (telemetry-admin.mjs runTelemetryBridge): the last Supabase thab_events.id
-- already handed to thab-voice's AdminTelemetry.import_rows. Without this table the bridge still works
-- (it re-sends the newest page every minute and import_rows dedups by rid), it only does more work.
-- Re-runnable. Apply to the shared remote D1 from cloudflare/programs-admin with:
--   npx wrangler d1 execute ts-activation --remote --file migrations/20260921_telemetry_bridge.sql
CREATE TABLE IF NOT EXISTS telemetry_bridge (
  k  TEXT PRIMARY KEY,
  v  TEXT NOT NULL,
  at TEXT NOT NULL
);
