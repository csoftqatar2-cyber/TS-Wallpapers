/**
 * A controller (dashboard) code typed into the family activation box (owner rule 2026-10-28, review S4
 * 2026-10-31): never claimed there, never registers/activates the car in devices, never writes an
 * entitlement, counted on neither counter — plain invalid_format so the app takes it to thab-voice
 * /v1/controller/redeem. A car the box registered before 2026-10-31 still heals with its own code.
 * Real SQLite (node:sqlite) with schema.sql + migration 0008, driving the REAL worker.js.
 *
 * Run:  node test-controller-register.mjs
 */
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from './worker.js';

if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    return x.length === y.length && x.every((v, i) => v === y[i]);
  };
}
const SECRET = 'test-secret';

function makeDb() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('./migrations/0008_controller_licence.sql', import.meta.url), 'utf8'));
  // Owned by the admin site's migrations (Website repo); same shape as production.
  db.exec('CREATE TABLE car_customers (hw_id TEXT PRIMARY KEY, phone TEXT NOT NULL, serial TEXT, bound_at TEXT NOT NULL, updated_at TEXT NOT NULL, note TEXT)');
  const exec = (sql, p) => {
    const s = db.prepare(sql);
    if (/^\s*(SELECT|WITH)/i.test(sql) || /\bRETURNING\b/i.test(sql)) {
      const rows = s.all(...p).map((r) => ({ ...r }));
      return { results: rows, meta: { changes: rows.length } };
    }
    const r = s.run(...p);
    return { results: [], meta: { changes: Number(r.changes) } };
  };
  class Stmt {
    constructor(sql, p = []) { this.sql = sql; this.p = p; }
    bind(...p) { return new Stmt(this.sql, p); }
    async first() { return exec(this.sql, this.p).results[0] ?? null; }
    async all() { return { success: true, ...exec(this.sql, this.p) }; }
    async run() { return { success: true, ...exec(this.sql, this.p) }; }
  }
  return {
    raw: db,
    prepare: (sql) => new Stmt(sql),
    async batch(stmts) {
      db.exec('BEGIN');
      try { const out = stmts.map((s) => exec(s.sql, s.p)); db.exec('COMMIT'); return out; }
      catch (e) { db.exec('ROLLBACK'); throw e; }
    },
  };
}

async function activate(db, hardware_id, activation_serial, legacy_hw_id) {
  const res = await worker.fetch(new Request('https://w/v1/devices/activate', {
    method: 'POST',
    headers: { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' },
    body: JSON.stringify({ hardware_id, activation_serial, legacy_hw_id }),
  }), { DB: db, SHARED_SECRET: SECRET });
  return await res.json();
}

let pass = 0, fail = 0;
function check(label, actual, expected) {
  const ok = actual === expected;
  ok ? pass++ : fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}  → ${actual}${ok ? '' : `  (expected ${expected})`}`);
}
const future = new Date(Date.now() + 30 * 60000).toISOString();
const past = new Date(Date.now() - 60000).toISOString();
const code = (db, c, { exp = future, used = null, cancelled = null } = {}) =>
  db.raw.prepare('INSERT INTO controller_codes (code, issued_at, expires_at, used_by, cancelled_at) VALUES (?, ?, ?, ?, ?)')
    .run(c, new Date().toISOString(), exp, used, cancelled);
const q = (db, sql, ...p) => db.raw.prepare(sql).get(...p);

const nothingWritten = (db, hw, c, label) => {
  check(`${label}: no family row`, q(db, 'SELECT COUNT(*) n FROM devices WHERE hardware_id=? AND is_active=1', hw).n, 0);
  check(`${label}: no entitlement`, q(db, 'SELECT COUNT(*) n FROM controller_entitlements WHERE hardware_id=?', hw).n, 0);
  check(`${label}: no controller counter`, q(db, 'SELECT COUNT(*) n FROM controller_attempts WHERE hardware_id=? AND failed > 0', hw).n, 0);
  if (c) check(`${label}: code not consumed`, q(db, 'SELECT used_by FROM controller_codes WHERE code=?', c).used_by, null);
};

// S4 (2026-10-31, owner rule 10-28): unknown car + valid dashboard code → plain refusal, nothing written,
// not counted, code free for thab-voice /v1/controller/redeem (the app's second door).
{
  const db = makeDb();
  code(db, '377548');
  db.raw.prepare("UPDATE controller_codes SET customer_phone = '+971555272407' WHERE code = '377548'").run();
  const r = await activate(db, 'VIN-bydNEW1', '377548', 'AID-x');
  check('unknown car: invalid_format (goes on to the dashboard redeem)', r.status, 'invalid_format');
  check('unknown car: attempts 0', r.attempts, 0);
  check('unknown car: why', r.why, 'dashboard_code');
  nothingWritten(db, 'VIN-bydNEW1', '377548', 'unknown car');
  check('unknown car: no devices row at all', q(db, 'SELECT COUNT(*) n FROM devices WHERE hardware_id=?', 'VIN-bydNEW1').n, 0);
  check('unknown car: no customer bound here', q(db, 'SELECT COUNT(*) n FROM car_customers WHERE hw_id=?', 'VIN-bydNEW1').n, 0);
  check('unknown car: audited', q(db, "SELECT json_extract(detail,'$.why') w FROM controller_audit WHERE hardware_id=? AND action='family_box_refused'", 'VIN-bydNEW1').w, 'dashboard_code');
}
// a row that only collected failed codes: refused, family counter untouched
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, failed_attempts, source) VALUES ('C2', 0, 3, 'failed_attempt')").run();
  code(db, '111222');
  check('failed-only row: invalid_format', (await activate(db, 'C2', '111222')).status, 'invalid_format');
  check('failed-only row: family counter unchanged', q(db, 'SELECT failed_attempts FROM devices WHERE hardware_id=?', 'C2').failed_attempts, 3);
  nothingWritten(db, 'C2', '111222', 'failed-only row');
}
// '579' + a six-digit code on file, and an unknown 579
{
  const db = makeDb();
  code(db, '654321');
  check('579 bridge: invalid_format', (await activate(db, 'C3', '579654321')).status, 'invalid_format');
  nothingWritten(db, 'C3', '654321', '579 bridge');
  check('unknown 579: invalid_format', (await activate(db, 'C3', '579000111')).status, 'invalid_format');
  nothingWritten(db, 'C3', null, 'unknown 579');
}
// expired / cancelled / used / locked / revoked / withdrawn / blocked — all plain, none counted
{
  const db = makeDb();
  code(db, '200001', { exp: past });
  code(db, '200002', { cancelled: past });
  code(db, '200003');
  code(db, '200004', { used: 'SOMEONE' });
  check('expired refused', (await activate(db, 'E1', '200001')).status, 'invalid_format');
  check('cancelled refused', (await activate(db, 'E1', '200002')).status, 'invalid_format');
  check('used by another car refused', (await activate(db, 'E1', '200004')).status, 'invalid_format');
  nothingWritten(db, 'E1', null, 'expired/cancelled/used');
  db.raw.prepare("INSERT INTO controller_attempts (hardware_id, failed, locked_at) VALUES ('L1', 10, ?)").run(past);
  check('locked car refused', (await activate(db, 'L1', '200003')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO controller_entitlements (hardware_id, kind, granted_at, revoked_at) VALUES ('R1','code',?,?)").run(past, past);
  check('revoked car refused', (await activate(db, 'R1', '200003')).status, 'invalid_format');
  check('revoked car: still revoked', q(db, 'SELECT revoked_at FROM controller_entitlements WHERE hardware_id=?', 'R1').revoked_at, past);
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active) VALUES ('W1', '578300001', 0)").run();
  check('withdrawn family licence not reopened', (await activate(db, 'W1', '200003')).status, 'invalid_format');
  check('withdrawn: still inactive', q(db, 'SELECT is_active FROM devices WHERE hardware_id=?', 'W1').is_active, 0);
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, is_blocked) VALUES ('B1', 0, 1)").run();
  check('blocked car answers blocked', (await activate(db, 'B1', '200003')).status, 'blocked');
  check('code still free after all refusals', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '200003').used_by, null);
  check('every non-member refusal audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE action='family_box_refused'").n, 6);
}
// Jetour G700 codes (car/tier columns present): same — the tier is written by thab-voice, never here
{
  const db = makeDb();
  db.raw.exec("ALTER TABLE controller_codes ADD COLUMN car TEXT; ALTER TABLE controller_codes ADD COLUMN tier TEXT DEFAULT 'full';");
  db.raw.prepare("INSERT INTO controller_codes (code, issued_at, expires_at, car, tier) VALUES ('510001', ?, ?, 'jetour_g700', 'core')").run(new Date().toISOString(), future);
  check('G700 core code: invalid_format', (await activate(db, 'T1', '510001')).status, 'invalid_format');
  nothingWritten(db, 'T1', '510001', 'G700 core code');
}
// family member already: unchanged answer, but visible
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active) VALUES ('A1', '578300002', 1)").run();
  code(db, '300001');
  const r = await activate(db, 'A1', '300001');
  check('active family car: invalid_format (redeem on thab-voice)', r.status, 'invalid_format');
  check('active family car: code untouched', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '300001').used_by, null);
  check('active family car: audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE hardware_id='A1' AND action='family_box_seen'").n, 1);
}
// a car the box registered with its own code before 2026-10-31 still heals (Postgres mirror retry)
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active, source) VALUES ('H1', '400001', 1, 'controller_code')").run();
  code(db, '400001', { used: 'H1' });
  check('own code on an active car: success (Postgres heal)', (await activate(db, 'H1', '400001')).status, 'success');
  check('…another car still refused', (await activate(db, 'H2', '400001')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active, source) VALUES ('H3', '579400002', 1, 'controller_code')").run();
  code(db, '400002', { used: 'H3' });
  check('579-registered car re-typing the six digits: success (heal)', (await activate(db, 'H3', '400002')).status, 'success');
  check('heal audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE hardware_id='H3' AND action='family_box_heal'").n, 1);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
