/**
 * Automatic controller-token reissue (2026-10-10) against a node:sqlite D1 with the real schema
 * (devices, devices_audit, device_tokens 0004+0010, controller_entitlements 0008, device_fingerprints 0009).
 * Run:  node test-auto-reissue.mjs
 */
if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    return x.every((v, i) => v === y[i]);
  };
}
import { DatabaseSync } from 'node:sqlite';
import worker from './worker.js';

const SECRET = 'test-secret';
const SCHEMA = `
CREATE TABLE devices (hardware_id TEXT PRIMARY KEY, serial_number TEXT, is_active INTEGER NOT NULL DEFAULT 0,
  is_blocked INTEGER NOT NULL DEFAULT 0, activated_at TEXT, updated_at TEXT, source TEXT, failed_attempts INTEGER NOT NULL DEFAULT 0,
  first_failed_at TEXT, last_failed_at TEXT, last_failed_serial TEXT, blocked_at TEXT, block_reason TEXT,
  token_hash TEXT, token_issued_at TEXT, token_version INTEGER NOT NULL DEFAULT 0, token_app_id TEXT);
CREATE TABLE devices_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, hardware_id TEXT NOT NULL, action TEXT NOT NULL,
  before_json TEXT, after_json TEXT, at TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TABLE device_tokens (hardware_id TEXT NOT NULL, app_id TEXT NOT NULL, token_hash TEXT NOT NULL,
  token_issued_at TEXT NOT NULL, token_version INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (hardware_id, app_id));
ALTER TABLE device_tokens ADD COLUMN prev_token_hash TEXT;
ALTER TABLE device_tokens ADD COLUMN prev_valid_until TEXT;
CREATE TABLE controller_entitlements (hardware_id TEXT PRIMARY KEY, kind TEXT NOT NULL, code TEXT, granted_at TEXT NOT NULL,
  granted_by TEXT, revoked_at TEXT, revoked_by TEXT, revoke_reason TEXT, note TEXT, snapshot_source TEXT);
CREATE TABLE device_fingerprints (hardware_id TEXT PRIMARY KEY, fp_hash TEXT NOT NULL, bound_at TEXT NOT NULL,
  bound_by TEXT NOT NULL, last_seen_at TEXT, rotate_day TEXT, rotate_count INTEGER NOT NULL DEFAULT 0);
`;

function makeDb() {
  const db = new DatabaseSync(':memory:');
  db.exec(SCHEMA);
  const norm = (v) => (typeof v === 'boolean' ? (v ? 1 : 0) : v === undefined ? null : v);
  const exec = (sql, params) => {
    const s = db.prepare(sql);
    const p = params.map(norm);
    if (/^\s*(SELECT|WITH)/i.test(sql) || /\bRETURNING\b/i.test(sql)) {
      const rows = s.all(...p).map((r) => ({ ...r }));
      return { results: rows, meta: { changes: /^\s*SELECT/i.test(sql) ? 0 : rows.length } };
    }
    const r = s.run(...p);
    return { results: [], meta: { changes: Number(r.changes) } };
  };
  class Stmt {
    constructor(sql, params = []) { this.sql = sql; this.params = params; }
    bind(...p) { return new Stmt(this.sql, p); }
    async first(col) { const r = exec(this.sql, this.params).results[0]; return r == null ? null : col ? r[col] : r; }
    async all() { return { success: true, ...exec(this.sql, this.params) }; }
    async run() { return { success: true, ...exec(this.sql, this.params) }; }
  }
  return {
    raw: db,
    prepare: (sql) => new Stmt(sql),
    async batch(stmts) { return stmts.map((s) => ({ success: true, ...exec(s.sql, s.params) })); },
  };
}

let pass = 0, fail = 0;
function check(name, cond, got) {
  if (cond) { pass++; console.log('PASS ', name); } else { fail++; console.log('FAIL ', name, '→', JSON.stringify(got)); }
}
async function call(env, path, body) {
  const r = await worker.fetch(new Request('https://x' + path, {
    method: 'POST', headers: { authorization: 'Bearer ' + SECRET, 'content-type': 'application/json' }, body: JSON.stringify(body),
  }), env);
  return r.json();
}
const ago = (ms) => new Date(Date.now() - ms).toISOString();
const H = 3600 * 1000;

function seed(db, hw, { activated = ago(30 * 24 * H), active = 1, blocked = 0, ent = 'grandfather', revoked = null, fp = false, issued = ago(5 * 24 * H) } = {}) {
  db.raw.prepare('INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at) VALUES (?,?,?,?,?)').run(hw, '123456', active, blocked, activated);
  db.raw.prepare("INSERT INTO device_tokens (hardware_id, app_id, token_hash, token_issued_at, token_version) VALUES (?, 'controller', ?, ?, 1)")
    .run(hw, 'a'.repeat(64), issued);
  if (ent) db.raw.prepare('INSERT INTO controller_entitlements (hardware_id, kind, granted_at, revoked_at) VALUES (?,?,?,?)').run(hw, ent, ago(H), revoked);
  if (fp) db.raw.prepare("INSERT INTO device_fingerprints (hardware_id, fp_hash, bound_at, bound_by) VALUES (?, 'f', ?, 'bind')").run(hw, ago(H));
}
const enrol = (env, hw, app = 'controller') => call(env, '/v1/devices/enroll', { hardware_id: hw, app_id: app, app_version_code: 224 });
const verify = (env, hw, token) => call(env, '/v1/devices/verify', { hardware_id: hw, app_id: 'controller', token });
const audits = (db, hw, action) => db.raw.prepare('SELECT COUNT(*) n FROM devices_audit WHERE hardware_id = ? AND action = ?').get(hw, action).n;

{
  const db = makeDb(); const env = { DB: db, SHARED_SECRET: SECRET };
  // The binding name the worker reads.
  env.ACTIVATION_DB = db;
  const hw = 'VIN-bydAAAA';
  seed(db, hw);
  // Give the original car a known token so it can be verified after the reissue.
  const orig = 'b'.repeat(64);
  const origHash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(orig)))].map((x) => x.toString(16).padStart(2, '0')).join('');
  db.raw.prepare("UPDATE device_tokens SET token_hash = ? WHERE hardware_id = ?").run(origHash, hw);

  const a = await enrol(env, hw);
  check('licensed VIN car with no fingerprint → rotated', a.status === 'rotated' && /^[0-9a-f]{64}$/.test(a.token) && a.token_version === 2, a);
  check('audited auto_reissue', audits(db, hw, 'auto_reissue') === 1, null);
  check('new token verifies', (await verify(env, hw, a.token)).active === true, null);
  check('ORIGINAL token still verifies (30-day grace)', (await verify(env, hw, orig)).active === true, null);

  db.raw.prepare('UPDATE device_tokens SET token_issued_at = ? WHERE hardware_id = ?').run(ago(10 * 60 * 1000), hw);
  const b = await enrol(env, hw);
  check('second attempt inside 24 h → already_enrolled (cap)', b.status === 'already_enrolled' && b.token == null, b);
  check('capped attempt audited', audits(db, hw, 'auto_reissue_capped') === 1, null);

  // Pretend the first reissue was 25 h ago: a second one is allowed and the ORIGINAL stays in grace.
  db.raw.prepare("UPDATE devices_audit SET at = ? WHERE action = 'auto_reissue'").run(ago(25 * H));
  db.raw.prepare("UPDATE device_tokens SET token_issued_at = ? WHERE hardware_id = ?").run(ago(25 * H), hw);
  const c = await enrol(env, hw);
  check('after 24 h → rotated again', c.status === 'rotated' && c.token_version === 3, c);
  check('original still in grace after the second reissue', (await verify(env, hw, orig)).active === true, null);
  check('the first reissued token is gone', (await verify(env, hw, a.token)).active === false, null);
  check('the newest verifies', (await verify(env, hw, c.token)).active === true, null);

  db.raw.prepare("UPDATE devices_audit SET at = ? WHERE action = 'auto_reissue'").run(ago(2 * 24 * H));
  db.raw.prepare("UPDATE device_tokens SET token_issued_at = ? WHERE hardware_id = ?").run(ago(25 * H), hw);
  const d = await enrol(env, hw);
  check('third within 30 days → rotated', d.status === 'rotated', d);
  db.raw.prepare("UPDATE devices_audit SET at = ? WHERE action = 'auto_reissue'").run(ago(3 * 24 * H));
  db.raw.prepare("UPDATE device_tokens SET token_issued_at = ? WHERE hardware_id = ?").run(ago(25 * H), hw);
  const e = await enrol(env, hw);
  check('fourth within 30 days → already_enrolled (30-day cap)', e.status === 'already_enrolled', e);

  // Grace expiry.
  db.raw.prepare("UPDATE device_tokens SET prev_valid_until = ? WHERE hardware_id = ?").run(ago(1000), hw);
  check('original refused once the grace has passed', (await verify(env, hw, orig)).active === false, null);

  // A typed code (strong proof) clears the grace slot.
  db.raw.prepare("UPDATE device_tokens SET prev_token_hash = ?, prev_valid_until = ? WHERE hardware_id = ?").run(origHash, new Date(Date.now() + H).toISOString(), hw);
  const s = await call(env, '/v1/devices/enroll', { hardware_id: hw, app_id: 'controller', activation_serial: '123456' });
  check('typed serial rotates', s.status === 'rotated', s);
  check('typed serial clears the grace slot', (await verify(env, hw, orig)).active === false, null);
}

{
  const db = makeDb(); const env = { DB: db, ACTIVATION_DB: db, SHARED_SECRET: SECRET };
  seed(db, 'VIN-fp', { fp: true });
  check('bound fingerprint → no automatic reissue', (await enrol(env, 'VIN-fp')).status === 'already_enrolled', null);
  seed(db, 'SYS-123456789', {});
  check('legacy SYS- id → no automatic reissue', (await enrol(env, 'SYS-123456789')).status === 'already_enrolled', null);
  seed(db, 'VIN-revoked', { revoked: ago(H) });
  check('revoked controller licence → no automatic reissue', (await enrol(env, 'VIN-revoked')).status === 'already_enrolled', null);
  seed(db, 'VIN-new', { ent: null, activated: ago(H) });
  check('no entitlement, activated after the cut-off → no automatic reissue', (await enrol(env, 'VIN-new')).status === 'already_enrolled', null);
  seed(db, 'VIN-old', { ent: null, activated: '2026-09-01T00:00:00Z' });
  check('no entitlement row, activated before the cut-off → rotated', (await enrol(env, 'VIN-old')).status === 'rotated', null);
  seed(db, 'VIN-code', { ent: 'code', activated: ago(H) });
  check('code entitlement → rotated', (await enrol(env, 'VIN-code')).status === 'rotated', null);
  seed(db, 'VIN-fresh', { issued: ago(30 * 1000) });
  check('token minted 30 s ago → no automatic reissue', (await enrol(env, 'VIN-fresh')).status === 'already_enrolled', null);
  seed(db, 'VIN-blocked', { blocked: 1 });
  check('blocked family row → refused', (await enrol(env, 'VIN-blocked')).status === 'refused', null);
  seed(db, 'VIN-store', {});
  db.raw.prepare("INSERT INTO device_tokens (hardware_id, app_id, token_hash, token_issued_at) VALUES ('VIN-store', 'store', 'c', ?)").run(ago(5 * 24 * H));
  check('store app keeps already_enrolled (controller only)', (await enrol(env, 'VIN-store', 'store')).status === 'already_enrolled', null);
  const off = { ...env, AUTO_REISSUE_CONTROLLER: 'off' };
  seed(db, 'VIN-off', {});
  check('AUTO_REISSUE_CONTROLLER=off → already_enrolled', (await enrol(off, 'VIN-off')).status === 'already_enrolled', null);
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
