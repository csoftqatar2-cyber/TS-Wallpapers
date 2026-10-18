/**
 * «Same serial, different id» (2026-10-18): Postgres renames a car's row from its legacy id to its VIN
 * (migrate_device_hardware_id, called from the read RPCs) without telling D1. The nightly reconcile
 * then pushed the VIN row, D1 refused it on UNIQUE(serial_number), and the whole push failed every
 * night from 2026-08-05. bulk-upsert must now carry out the legal rename itself, move the car's other
 * rows with it, skip (and report) a real clash, and still write every other row.
 *
 * Runs the REAL worker.js against a real SQLite (node:sqlite) behind a minimal D1 adapter, so the
 * UNIQUE index that caused the outage is the real one.
 *
 * Run:  node test-identity-sync.mjs
 */
import { DatabaseSync } from 'node:sqlite';
import worker from './worker.js';

if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    return x.length === y.length && x.every((v, i) => v === y[i]);
  };
}

const SECRET = 'test-secret';

function d1(sqlite) {
  const stmt = (sql, args = []) => ({
    sql, args,
    bind: (...a) => stmt(sql, a),
    first: async () => sqlite.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: sqlite.prepare(sql).all(...args) }),
    run: async () => { const r = sqlite.prepare(sql).run(...args); return { meta: { changes: r.changes } }; },
  });
  return {
    prepare: (sql) => stmt(sql),
    batch: async (list) => {
      sqlite.exec('BEGIN');
      try {
        const out = list.map((s) => {
          const p = sqlite.prepare(s.sql);
          return /^\s*(SELECT|WITH)/i.test(s.sql) || /RETURNING/i.test(s.sql)
            ? { results: p.all(...s.args) } : { meta: { changes: p.run(...s.args).changes } };
        });
        sqlite.exec('COMMIT');
        return out;
      } catch (e) { sqlite.exec('ROLLBACK'); throw e; }
    },
  };
}

function freshDb({ withCustomers = true } = {}) {
  const s = new DatabaseSync(':memory:');
  s.exec(`
    CREATE TABLE devices (hardware_id TEXT PRIMARY KEY, serial_number TEXT, is_active INTEGER NOT NULL DEFAULT 0,
      is_blocked INTEGER NOT NULL DEFAULT 0, activated_at TEXT, updated_at TEXT, source TEXT,
      failed_attempts INTEGER NOT NULL DEFAULT 0, first_failed_at TEXT, last_failed_at TEXT, last_failed_serial TEXT,
      blocked_at TEXT, block_reason TEXT, token_hash TEXT, token_issued_at TEXT, token_version INTEGER NOT NULL DEFAULT 0, token_app_id TEXT);
    CREATE UNIQUE INDEX idx_devices_serial ON devices (serial_number) WHERE serial_number IS NOT NULL;
    CREATE TABLE devices_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, hardware_id TEXT NOT NULL, action TEXT NOT NULL,
      before_json TEXT, after_json TEXT, at TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')));
    CREATE TABLE device_tokens (hardware_id TEXT NOT NULL, app_id TEXT NOT NULL, token_hash TEXT NOT NULL,
      token_issued_at TEXT NOT NULL, token_version INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (hardware_id, app_id));
    CREATE TABLE controller_entitlements (hardware_id TEXT PRIMARY KEY, kind TEXT NOT NULL, code TEXT, granted_at TEXT NOT NULL,
      granted_by TEXT, revoked_at TEXT, revoked_by TEXT, revoke_reason TEXT, note TEXT, snapshot_source TEXT);
    CREATE TABLE controller_attempts (hardware_id TEXT PRIMARY KEY, failed INTEGER NOT NULL DEFAULT 0, last_failed_at TEXT, locked_at TEXT);
    CREATE TABLE device_fingerprints (hardware_id TEXT PRIMARY KEY, fp_hash TEXT NOT NULL, bound_at TEXT NOT NULL,
      bound_by TEXT NOT NULL, last_seen_at TEXT, rotate_day TEXT, rotate_count INTEGER NOT NULL DEFAULT 0);
  `);
  if (withCustomers) s.exec(`CREATE TABLE car_customers (hw_id TEXT PRIMARY KEY, phone TEXT NOT NULL, serial TEXT,
      bound_at TEXT NOT NULL, updated_at TEXT NOT NULL, note TEXT);`);
  return s;
}

const T = '2026-07-10T10:00:00.000Z';
function seedCar(s, id, serial, extra = {}) {
  s.prepare(`INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at, updated_at, source)
             VALUES (?, ?, 1, 0, ?, ?, 'seed')`).run(id, serial, T, T);
  if (extra.token) s.prepare(`INSERT INTO device_tokens VALUES (?, 'controller', ?, ?, 1)`).run(id, extra.token, T);
  if (extra.ent) s.prepare(`INSERT INTO controller_entitlements (hardware_id, kind, granted_at) VALUES (?, 'grandfather', ?)`).run(id, T);
  if (extra.fp) s.prepare(`INSERT INTO device_fingerprints (hardware_id, fp_hash, bound_at, bound_by) VALUES (?, 'fp', ?, 'auto')`).run(id, T);
  if (extra.phone) s.prepare(`INSERT INTO car_customers VALUES (?, '+97400000000', ?, ?, ?, null)`).run(id, serial, T, T);
}

async function push(db, rows) {
  const req = new Request('https://x/v1/devices/bulk-upsert', {
    method: 'POST',
    headers: { Authorization: `Bearer ${SECRET}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ source: 'reconcile', rows }),
  });
  const res = await worker.fetch(req, { DB: db, SHARED_SECRET: SECRET });
  return { status: res.status, body: await res.json() };
}

let pass = 0, fail = 0;
function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  ok ? pass++ : fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  → ${JSON.stringify(got)}${ok ? '' : `  (expected ${JSON.stringify(want)})`}`);
}
const row = (s, sql, ...a) => s.prepare(sql).get(...a) ?? null;
const pgRow = (id, serial) => ({ hardware_id: id, serial_number: serial, is_active: true, is_blocked: false, activated_at: T });

{ // 1. The outage: a legacy AID row holding the serial, Postgres pushes the VIN row.
  const s = freshDb(); const db = d1(s);
  seedCar(s, 'AID-827694b9654925be', '7078159', { token: 'h1', ent: true, fp: true, phone: true });
  seedCar(s, 'MAC-AAAAAAAAAAAA', '7078001');                      // an unrelated car, untouched
  const r = await push(db, [pgRow('VIN-byd96F0BA85E398916B', '7078159'), pgRow('VIN-NOSERIAL1', null)]);
  check('push answers 200 (was HTTP 500 on UNIQUE)', r.status, 200);
  check('rename reported', r.body.migrated, [{ from: 'AID-827694b9654925be', to: 'VIN-byd96F0BA85E398916B' }]);
  check('no conflicts', r.body.conflicts, []);
  check('VIN row active with its serial', row(s, 'SELECT serial_number, is_active FROM devices WHERE hardware_id=?', 'VIN-byd96F0BA85E398916B'), { serial_number: '7078159', is_active: 1 });
  check('old row gone', row(s, 'SELECT 1 x FROM devices WHERE hardware_id=?', 'AID-827694b9654925be'), null);
  check('token moved', row(s, 'SELECT hardware_id FROM device_tokens WHERE token_hash=?', 'h1'), { hardware_id: 'VIN-byd96F0BA85E398916B' });
  check('controller licence moved', row(s, 'SELECT kind FROM controller_entitlements WHERE hardware_id=?', 'VIN-byd96F0BA85E398916B'), { kind: 'grandfather' });
  check('fingerprint moved', row(s, 'SELECT fp_hash FROM device_fingerprints WHERE hardware_id=?', 'VIN-byd96F0BA85E398916B'), { fp_hash: 'fp' });
  check('customer phone moved', row(s, 'SELECT phone FROM car_customers WHERE hw_id=?', 'VIN-byd96F0BA85E398916B'), { phone: '+97400000000' });
  check('rename audited under the new id', row(s, "SELECT action FROM devices_audit WHERE hardware_id=? AND action='migrate'", 'VIN-byd96F0BA85E398916B'), { action: 'migrate' });
  check('row without a serial still written', row(s, 'SELECT is_active FROM devices WHERE hardware_id=?', 'VIN-NOSERIAL1'), { is_active: 1 });
  check('unrelated car untouched', row(s, 'SELECT serial_number, source FROM devices WHERE hardware_id=?', 'MAC-AAAAAAAAAAAA'), { serial_number: '7078001', source: 'seed' });
}

{ // 2. A real clash (serial held by another VIN) is skipped and reported; the rest of the push lands.
  const s = freshDb(); const db = d1(s);
  seedCar(s, 'VIN-OTHERCAR', '7078500');
  const r = await push(db, [pgRow('VIN-NEWCAR', '7078500'), pgRow('VIN-FINE', '7078600')]);
  check('clash push still 200', r.status, 200);
  check('clash reported', r.body.conflicts, [{ hardware_id: 'VIN-NEWCAR', serial_number: '7078500', held_by: 'VIN-OTHERCAR' }]);
  check('clash: nobody renamed', r.body.migrated, []);
  check('clash: other car keeps its serial', row(s, 'SELECT hardware_id FROM devices WHERE serial_number=?', '7078500'), { hardware_id: 'VIN-OTHERCAR' });
  check('clash: the good row still written', row(s, 'SELECT serial_number FROM devices WHERE hardware_id=?', 'VIN-FINE'), { serial_number: '7078600' });
}

{ // 3. Never merge: both names already have rows here -> conflict, nothing moved.
  const s = freshDb(); const db = d1(s);
  seedCar(s, 'AID-OLD', '7078700');
  s.prepare(`INSERT INTO devices (hardware_id, is_active, updated_at) VALUES ('VIN-EXISTS', 0, ?)`).run(T);
  const r = await push(db, [pgRow('VIN-EXISTS', '7078700')]);
  check('both rows exist -> conflict', r.body.conflicts.length, 1);
  check('old row kept', row(s, 'SELECT serial_number FROM devices WHERE hardware_id=?', 'AID-OLD'), { serial_number: '7078700' });
}

{ // 4. VIN case-only rename is legal; the new id already holding a licence keeps its own.
  const s = freshDb(); const db = d1(s);
  seedCar(s, 'VIN-byd1234', '7078800', { ent: true });
  s.prepare(`INSERT INTO controller_entitlements (hardware_id, kind, granted_at) VALUES ('VIN-BYD1234', 'code', ?)`).run(T);
  const r = await push(db, [pgRow('VIN-BYD1234', '7078800')]);
  check('case rename done', r.body.migrated, [{ from: 'VIN-byd1234', to: 'VIN-BYD1234' }]);
  check('existing licence on the new id not overwritten', row(s, 'SELECT kind FROM controller_entitlements WHERE hardware_id=?', 'VIN-BYD1234'), { kind: 'code' });
}

{ // 5. A missing per-car table (another Worker's) does not stop the rename.
  const s = freshDb({ withCustomers: false }); const db = d1(s);
  seedCar(s, 'MAC-112233445566', '7078900');
  const r = await push(db, [pgRow('VIN-LGWTEST', '7078900')]);
  check('rename without car_customers table', r.body.migrated, [{ from: 'MAC-112233445566', to: 'VIN-LGWTEST' }]);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
