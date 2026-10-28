/**
 * A controller code typed into the family activation box. Since 2026-10-28 (licence decoupled from the
 * family) a dashboard code NEVER registers or activates a car in `devices` here: on a non-member it is
 * refused plainly, not counted, not claimed, audited as family_box_seen; the app redeems it on thab-voice.
 * The member branch (its own code heals, any other is refused) is unchanged. Real SQLite (node:sqlite) with schema.sql + migration 0008, driving the REAL worker.js.
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

const snapDevices = (db) => JSON.stringify(db.raw.prepare('SELECT * FROM devices ORDER BY hardware_id').all());
const nDevices = (db) => q(db, 'SELECT COUNT(*) n FROM devices').n;

// 2026-10-28 (licence decoupled): unknown car + valid dashboard code → REFUSED; nothing claimed, devices untouched
{
  const db = makeDb();
  code(db, '377548');
  db.raw.prepare("UPDATE controller_codes SET customer_phone = '+971555272407' WHERE code = '377548'").run();
  const before = snapDevices(db);
  const r = await activate(db, 'VIN-bydNEW1', '377548', 'AID-x');
  check('unknown car + dashboard code: refused', r.status, 'invalid_format');
  check('…reason controller_code', r.reason, 'controller_code');
  check('…why controller_code', r.why, 'controller_code');
  check('…not counted (family)', r.attempts, 0);
  check('…counted:false', r.counted, false);
  check('…devices unchanged (byte-identical)', snapDevices(db), before);
  check('…no devices row for the car', q(db, 'SELECT COUNT(*) n FROM devices WHERE hardware_id=?', 'VIN-bydNEW1').n, 0);
  check('…no devices_audit activate', q(db, "SELECT COUNT(*) n FROM devices_audit WHERE action='activate'").n, 0);
  check('…code NOT claimed', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '377548').used_by, null);
  check('…no entitlement written', q(db, 'SELECT COUNT(*) n FROM controller_entitlements').n, 0);
  check('…controller counter untouched', q(db, 'SELECT COUNT(*) n FROM controller_attempts').n, 0);
  check('…no customer bound here (redeem does it)', q(db, 'SELECT COUNT(*) n FROM car_customers').n, 0);
  check('…audited family_box_seen / not_member',
    q(db, "SELECT json_extract(detail,'$.why') w FROM controller_audit WHERE hardware_id=? AND action='family_box_seen'", 'VIN-bydNEW1').w, 'not_member');
  // Typing it again changes nothing either, and another car is refused the same way.
  check('…again: refused', (await activate(db, 'VIN-bydNEW1', '377548')).status, 'invalid_format');
  check('…another car: refused', (await activate(db, 'VIN-bydOTHER', '377548')).status, 'invalid_format');
  check('…devices still unchanged', snapDevices(db), before);
}
// a row that only collected failed codes is not a member: refused, its family counter untouched
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, failed_attempts, source) VALUES ('C2', 0, 3, 'failed_attempt')").run();
  code(db, '111222');
  const before = snapDevices(db);
  check('failed-only row: refused', (await activate(db, 'C2', '111222')).status, 'invalid_format');
  check('failed-only row: devices unchanged', snapDevices(db), before);
  check('failed-only row: code free', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '111222').used_by, null);
}
// '579' + a six-digit code on file, non-member: refused, not claimed
{
  const db = makeDb();
  code(db, '654321');
  check('579 bridge non-member: refused', (await activate(db, 'C3', '579654321')).status, 'invalid_format');
  check('579 bridge non-member: no devices row', nDevices(db), 0);
  check('579 bridge non-member: code free', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '654321').used_by, null);
}
// expired / cancelled / locked / revoked / withdrawn: all the same plain refusal, nothing counted; blocked answers blocked
{
  const db = makeDb();
  code(db, '200001', { exp: past });
  code(db, '200002', { cancelled: past });
  code(db, '200003');
  check('expired refused', (await activate(db, 'E1', '200001')).status, 'invalid_format');
  check('cancelled refused', (await activate(db, 'E1', '200002')).status, 'invalid_format');
  check('nothing counted on the controller counter', q(db, 'SELECT COUNT(*) n FROM controller_attempts').n, 0);
  db.raw.prepare("INSERT INTO controller_entitlements (hardware_id, kind, granted_at, revoked_at) VALUES ('R1','code',?,?)").run(past, past);
  check('revoked car refused', (await activate(db, 'R1', '200003')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active) VALUES ('W1', '578300001', 0)").run();
  check('withdrawn family licence not reopened', (await activate(db, 'W1', '200003')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, is_blocked) VALUES ('B1', 0, 1)").run();
  check('blocked car answers blocked', (await activate(db, 'B1', '200003')).status, 'blocked');
  check('code still free after all refusals', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '200003').used_by, null);
  check('no family row created', q(db, "SELECT COUNT(*) n FROM devices WHERE hardware_id IN ('E1','R1')").n, 0);
  check('every non-member refusal audited as family_box_seen', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE action='family_box_seen'").n, 4);
}
// family member already: unchanged answer, but visible
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active) VALUES ('A1', '578300002', 1)").run();
  code(db, '300001');
  const r = await activate(db, 'A1', '300001');
  check('active family car: invalid_format (redeem on thab-voice)', r.status, 'invalid_format');
  check('active family car: why family_active', r.why, 'family_active');
  check('active family car: code untouched', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '300001').used_by, null);
  check('active family car: audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE hardware_id='A1' AND action='family_box_seen'").n, 1);
}

// a car registered by its own code BEFORE 2026-10-28 (member branch unchanged) still heals
{
  const db = makeDb();
  code(db, '400001', { used: 'H1' });
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active, source) VALUES ('H1', '400001', 1, 'controller_code')").run();
  check('own code on an active car: success (Rule 2 / Postgres heal)', (await activate(db, 'H1', '400001')).status, 'success');
  check('…another car still refused', (await activate(db, 'H2', '400001')).status, 'invalid_format');
  code(db, '400002', { used: 'H3' });
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active, source) VALUES ('H3', '579400002', 1, 'controller_code')").run();
  check('579-registered car re-typing the six digits: success (heal)', (await activate(db, 'H3', '400002')).status, 'success');
  check('heal audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE hardware_id='H3' AND action='family_box_heal'").n, 1);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
