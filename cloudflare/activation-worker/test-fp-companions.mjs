/**
 * Same-unit fingerprint for every app (2026-10-10): /v1/devices/enroll with `fingerprint`, against a
 * real SQLite (node:sqlite) carrying devices, devices_audit, device_tokens (0004),
 * device_fingerprints (0009) and device_fp_rotations (0010).
 * Run:  node test-fp-companions.mjs
 */
if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    return x.every((v, i) => v === y[i]);
  };
}
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import worker from './worker.js';

const SECRET = 'test-secret';
const PEPPER = 'pep';
const HW = 'VIN-byd1108D9EBDCBD89E1';
const FP = 'a'.repeat(64);
const FP2 = 'b'.repeat(64);
const sha = (s) => createHash('sha256').update(s).digest('hex');

function makeDb() {
  const raw = new DatabaseSync(':memory:');
  raw.exec(`
    CREATE TABLE devices (hardware_id TEXT PRIMARY KEY, serial_number TEXT, is_active INTEGER NOT NULL DEFAULT 0,
      is_blocked INTEGER NOT NULL DEFAULT 0, activated_at TEXT, updated_at TEXT, source TEXT, failed_attempts INTEGER DEFAULT 0,
      first_failed_at TEXT, last_failed_at TEXT, last_failed_serial TEXT, blocked_at TEXT, block_reason TEXT);
    CREATE TABLE devices_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, hardware_id TEXT NOT NULL, action TEXT NOT NULL,
      before_json TEXT, after_json TEXT, at TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')));
    CREATE TABLE device_tokens (hardware_id TEXT NOT NULL, app_id TEXT NOT NULL, token_hash TEXT NOT NULL,
      token_issued_at TEXT NOT NULL, token_version INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (hardware_id, app_id));
    CREATE TABLE device_fingerprints (hardware_id TEXT PRIMARY KEY, fp_hash TEXT NOT NULL, bound_at TEXT NOT NULL,
      bound_by TEXT NOT NULL, last_seen_at TEXT, rotate_day TEXT, rotate_count INTEGER NOT NULL DEFAULT 0);
  `);
  raw.exec(readFileSync(new URL('./migrations/0010_device_fp_rotations.sql', import.meta.url), 'utf8'));
  const norm = (v) => (v === undefined ? null : typeof v === 'boolean' ? (v ? 1 : 0) : v);
  class Stmt {
    constructor(sql) { this.sql = sql; this.args = []; }
    bind(...a) { this.args = a.map(norm); return this; }
    async first() { return raw.prepare(this.sql).get(...this.args) ?? null; }
    async all() { return { results: raw.prepare(this.sql).all(...this.args) }; }
    async run() { const r = raw.prepare(this.sql).run(...this.args); return { meta: { changes: Number(r.changes) } }; }
  }
  return {
    raw,
    prepare: (sql) => new Stmt(sql),
    batch: async (stmts) => { const out = []; for (const s of stmts) out.push(await s.run()); return out; },
  };
}

let passed = 0, failed = 0;
function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  → ${JSON.stringify(got)}${ok ? '' : `  (expected ${JSON.stringify(want)})`}`);
  ok ? passed++ : failed++;
}

function setup({ bound = FP, hw = HW, pepper = PEPPER } = {}) {
  const db = makeDb();
  db.raw.prepare("INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at) VALUES (?, '257342', 1, 0, '2026-09-20T13:39:14Z')").run(hw);
  db.raw.prepare("INSERT INTO device_tokens VALUES (?, 'wallpapers', ?, '2026-09-20T13:39:30Z', 1)").run(hw, sha('old-token'));
  if (bound) db.raw.prepare("INSERT INTO device_fingerprints VALUES (?, ?, '2026-10-10T00:00:00Z', 'bind', NULL, NULL, 0)").run(hw, sha(bound + PEPPER));
  const env = { DB: db, SHARED_SECRET: SECRET };
  if (pepper) env.FP_PEPPER = pepper;
  return { db, env, hw };
}
async function enroll(t, extra) {
  const res = await worker.fetch(new Request('https://w.test/v1/devices/enroll', {
    method: 'POST',
    headers: { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' },
    body: JSON.stringify({ hardware_id: t.hw, app_id: 'wallpapers', app_version_code: 206, ...extra }),
  }), t.env, null);
  return res.json();
}
const actions = (t) => t.db.raw.prepare('SELECT action FROM devices_audit ORDER BY id').all().map((r) => r.action);
const tokHash = (t) => t.db.raw.prepare("SELECT token_hash FROM device_tokens WHERE app_id='wallpapers'").get().token_hash;

// 1. The bound fingerprint rotates the wallpapers token; the old one is gone.
{
  const t = setup();
  const r = await enroll(t, { fingerprint: FP });
  check('same unit → rotated', [r.status, r.token_version], ['rotated', 2]);
  check('new hash stored', tokHash(t) === sha(r.token), true);
  check('audited rotate_fp', actions(t).at(-1), 'rotate_fp');
  const again = await enroll(t, { fingerprint: FP.toUpperCase() });
  check('upper-case hex accepted', again.status, 'rotated');
}
// 2. No fingerprint / wrong one / nothing bound / non-VIN / no pepper: today's answer.
{
  const t = setup();
  check('no fingerprint → already_enrolled', (await enroll(t, {})).status, 'already_enrolled');
  check('wrong fingerprint → already_enrolled', (await enroll(t, { fingerprint: FP2 })).status, 'already_enrolled');
  check('mismatch audited', actions(t).includes('enroll_fp_mismatch'), true);
  check('token untouched', tokHash(t), sha('old-token'));
}
{
  const t = setup({ bound: null });
  check('nothing bound → already_enrolled (no TOFU)', (await enroll(t, { fingerprint: FP })).status, 'already_enrolled');
  check('nothing bound → nothing bound now', t.db.raw.prepare('SELECT COUNT(*) n FROM device_fingerprints').get().n, 0);
  check('audited enroll_fp_unbound', actions(t).includes('enroll_fp_unbound'), true);
}
{
  const t = setup({ hw: 'SYS-00000091439HSD215' });
  check('non-VIN id → already_enrolled', (await enroll(t, { fingerprint: FP })).status, 'already_enrolled');
}
{
  const t = setup({ pepper: null });
  check('no FP_PEPPER → off', (await enroll(t, { fingerprint: FP })).status, 'already_enrolled');
}
{
  const t = setup();
  check('not 64-hex → ignored', (await enroll(t, { fingerprint: 'serial=ABC' })).status, 'already_enrolled');
}
// 3. 5 mismatches in a day lock the comparison (the right one too); the code still rotates.
{
  const t = setup();
  for (let i = 0; i < 5; i++) await enroll(t, { fingerprint: String(i).repeat(64) });
  const r = await enroll(t, { fingerprint: FP });
  check('locked after 5 mismatches', r.status, 'already_enrolled');
  check('audited enroll_fp_locked', actions(t).includes('enroll_fp_locked'), true);
  check('own code still rotates', (await enroll(t, { activation_serial: '257342' })).status, 'rotated');
}
// 4. Per (car, app) daily rotation cap: 3, then rotate_capped; another app is counted apart.
{
  const t = setup();
  t.db.raw.prepare("INSERT INTO device_tokens VALUES (?, 'store', ?, '2026-09-20T13:39:20Z', 1)").run(HW, sha('store-old'));
  for (let i = 0; i < 3; i++) await enroll(t, { fingerprint: FP });
  const r = await enroll(t, { fingerprint: FP });
  check('4th rotation → rotate_capped', [r.status, r.reason], ['already_enrolled', 'rotate_capped']);
  const s = await enroll(t, { fingerprint: FP, app_id: 'store' });
  check('store counted apart → rotated', s.status, 'rotated');
}
// 5. Inactive / blocked cars are still refused before any fingerprint is looked at.
{
  const t = setup();
  t.db.raw.prepare('UPDATE devices SET is_blocked = 1').run();
  check('blocked → refused', (await enroll(t, { fingerprint: FP })).status, 'refused');
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
