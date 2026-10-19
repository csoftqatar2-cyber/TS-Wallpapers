/**
 * A controller code typed into the family activation box on a car no backend knows (owner 2026-10-19,
 * VIN-bydD28D8914F1D780D0 / 377548): it registers + activates the car and grants the controller in one
 * go; every refusal is counted on the controller's own counter and audited; the family counter is never
 * touched. Real SQLite (node:sqlite) with schema.sql + migration 0008, driving the REAL worker.js.
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

// unknown car + valid code → registered, active, entitled, code used, audited
{
  const db = makeDb();
  code(db, '377548');
  const r = await activate(db, 'VIN-bydNEW1', '377548', 'AID-x');
  check('unknown car: success', r.status, 'success');
  check('row active', q(db, 'SELECT is_active FROM devices WHERE hardware_id=?', 'VIN-bydNEW1').is_active, 1);
  check('row source', q(db, 'SELECT source FROM devices WHERE hardware_id=?', 'VIN-bydNEW1').source, 'controller_code');
  check('entitlement kind', q(db, 'SELECT kind FROM controller_entitlements WHERE hardware_id=?', 'VIN-bydNEW1').kind, 'code');
  check('code used_by', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '377548').used_by, 'VIN-bydNEW1');
  check('audit redeem', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE hardware_id=? AND action='redeem'", 'VIN-bydNEW1').n, 1);
  check('devices_audit activate', q(db, "SELECT COUNT(*) n FROM devices_audit WHERE hardware_id=? AND action='activate'", 'VIN-bydNEW1').n, 1);
  check('re-typed (Rule 2) still success', (await activate(db, 'VIN-bydNEW1', '377548')).status, 'success');
  check('another car with the same code refused', (await activate(db, 'VIN-bydOTHER', '377548')).status, 'invalid_format');
  check('…counted on the controller counter', q(db, 'SELECT failed FROM controller_attempts WHERE hardware_id=?', 'VIN-bydOTHER').failed, 1);
  check('…family row not created', q(db, 'SELECT COUNT(*) n FROM devices WHERE hardware_id=?', 'VIN-bydOTHER').n, 0);
  check('…audited', q(db, "SELECT json_extract(detail,'$.why') w FROM controller_audit WHERE hardware_id=? AND action='family_box_refused'", 'VIN-bydOTHER').w, 'used');
}
// a row that only collected failed codes counts as unknown
{
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, failed_attempts, source) VALUES ('C2', 0, 3, 'failed_attempt')").run();
  code(db, '111222');
  check('failed-only row: success', (await activate(db, 'C2', '111222')).status, 'success');
  check('failed counter reset', q(db, 'SELECT failed_attempts FROM devices WHERE hardware_id=?', 'C2').failed_attempts, 0);
}
// '579' + a six-digit code on file
{
  const db = makeDb();
  code(db, '654321');
  check('579 bridge: success', (await activate(db, 'C3', '579654321')).status, 'success');
  check('579 bridge: entitlement on the 6-digit code', q(db, 'SELECT code FROM controller_entitlements WHERE hardware_id=?', 'C3').code, '654321');
}
// expired / cancelled / locked / revoked / withdrawn / blocked
{
  const db = makeDb();
  code(db, '200001', { exp: past });
  code(db, '200002', { cancelled: past });
  code(db, '200003');
  check('expired refused', (await activate(db, 'E1', '200001')).status, 'invalid_format');
  check('expired: no family row', q(db, 'SELECT COUNT(*) n FROM devices WHERE hardware_id=?', 'E1').n, 0);
  check('cancelled refused', (await activate(db, 'E1', '200002')).status, 'invalid_format');
  check('two failures counted', q(db, 'SELECT failed FROM controller_attempts WHERE hardware_id=?', 'E1').failed, 2);
  db.raw.prepare("INSERT INTO controller_attempts (hardware_id, failed, locked_at) VALUES ('L1', 10, ?)").run(past);
  check('locked car refused', (await activate(db, 'L1', '200003')).status, 'invalid_format');
  check('locked: code not consumed', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '200003').used_by, null);
  db.raw.prepare("INSERT INTO controller_entitlements (hardware_id, kind, granted_at, revoked_at) VALUES ('R1','code',?,?)").run(past, past);
  check('revoked car refused', (await activate(db, 'R1', '200003')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active) VALUES ('W1', '578300001', 0)").run();
  check('withdrawn family licence not reopened', (await activate(db, 'W1', '200003')).status, 'invalid_format');
  db.raw.prepare("INSERT INTO devices (hardware_id, is_active, is_blocked) VALUES ('B1', 0, 1)").run();
  check('blocked car answers blocked', (await activate(db, 'B1', '200003')).status, 'blocked');
  check('code still free after all refusals', q(db, 'SELECT used_by FROM controller_codes WHERE code=?', '200003').used_by, null);
  check('every refusal audited', q(db, "SELECT COUNT(*) n FROM controller_audit WHERE action='family_box_refused'").n, 5);
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

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
