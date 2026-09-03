/**
 * Per-(car, app) token: enroll / verify / reset_token / rotation against a minimal
 * in-memory D1 (devices + device_tokens, migration 0004).
 * Run:  node test-device-token.mjs
 */
// Workers give crypto.subtle.timingSafeEqual; Node does not. The worker only
// uses it to compare secrets, which is not what's under test.
if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    return x.every((v, i) => v === y[i]);
  };
}

import worker from './worker.js';

const SECRET = 'test-secret';

function makeDb(seed = []) {
  const rows = new Map();
  const tokens = new Map(); // key `${hw}|${app}`
  for (const r of seed) {
    rows.set(r.hardware_id, {
      hardware_id: r.hardware_id, serial_number: r.serial_number ?? null,
      is_active: r.is_active ? 1 : 0, is_blocked: r.is_blocked ? 1 : 0,
      activated_at: r.activated_at ?? null, updated_at: null, source: 'seed',
      failed_attempts: 0, first_failed_at: null, last_failed_at: null,
      last_failed_serial: null, blocked_at: null, block_reason: null,
    });
  }
  const audit = [];
  const key = (hw, app) => `${hw}|${app}`;
  const run = (sql, args) => {
    const s = sql.replace(/\s+/g, ' ').trim();
    if (s.startsWith('SELECT * FROM devices WHERE hardware_id')) return { row: rows.get(args[0]) ?? null, changes: 0 };
    if (s.startsWith('SELECT * FROM device_tokens WHERE hardware_id = ? AND app_id = ?')) return { row: tokens.get(key(args[0], args[1])) ?? null, changes: 0 };
    if (s.startsWith('SELECT app_id, token_issued_at, token_version FROM device_tokens')) {
      const list = [...tokens.values()].filter((t) => t.hardware_id === args[0]).sort((a, b) => a.app_id.localeCompare(b.app_id));
      return { rows: list, changes: 0 };
    }
    if (s.startsWith('INSERT INTO devices_audit')) { audit.push({ action: args[1], after: JSON.parse(args[3] || 'null') }); return { row: null, changes: 1 }; }
    if (s.startsWith('INSERT INTO device_tokens') && s.includes('ON CONFLICT(hardware_id, app_id) DO UPDATE')) {
      const [hw, app, hash, issuedAt] = args;
      const k = key(hw, app), prev = tokens.get(k);
      tokens.set(k, { hardware_id: hw, app_id: app, token_hash: hash, token_issued_at: issuedAt, token_version: prev ? prev.token_version + 1 : 1 });
      return { row: null, changes: 1 };
    }
    if (s.startsWith('INSERT OR IGNORE INTO device_tokens')) {
      const [hw, app, hash, issuedAt] = args;
      const k = key(hw, app);
      if (tokens.has(k)) return { row: null, changes: 0 };
      tokens.set(k, { hardware_id: hw, app_id: app, token_hash: hash, token_issued_at: issuedAt, token_version: 1 });
      return { row: null, changes: 1 };
    }
    if (s.startsWith('DELETE FROM device_tokens WHERE hardware_id = ? AND app_id = ?')) { const n = tokens.delete(key(args[0], args[1])) ? 1 : 0; return { row: null, changes: n }; }
    if (s.startsWith('DELETE FROM device_tokens WHERE hardware_id = ?')) {
      let n = 0; for (const [k, t] of tokens) if (t.hardware_id === args[0]) { tokens.delete(k); n++; }
      return { row: null, changes: n };
    }
    if (s.startsWith('UPDATE OR IGNORE device_tokens SET hardware_id')) {
      const [newId, oldId] = args; let n = 0;
      for (const [k, t] of [...tokens]) if (t.hardware_id === oldId) { tokens.delete(k); t.hardware_id = newId; tokens.set(key(newId, t.app_id), t); n++; }
      return { row: null, changes: n };
    }
    if (s.startsWith('UPDATE devices SET hardware_id = ?')) {
      const [newId, , , oldId] = args; const r = rows.get(oldId); if (!r) return { row: null, changes: 0 };
      rows.delete(oldId); r.hardware_id = newId; rows.set(newId, r); return { row: null, changes: 1 };
    }
    if (s.startsWith('INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, failed_attempts')) {
      const [hardware_id, isActive, isBlocked, attempts] = args;
      const prev = rows.get(hardware_id) || { serial_number: null };
      rows.set(hardware_id, { ...prev, hardware_id, is_active: isActive, is_blocked: isBlocked, failed_attempts: attempts });
      return { row: null, changes: 1 };
    }
    throw new Error('unhandled SQL: ' + s);
  };
  const prepare = (sql) => ({
    bind: (...args) => ({
      first: async () => run(sql, args).row,
      run: async () => ({ meta: { changes: run(sql, args).changes } }),
      all: async () => ({ results: run(sql, args).rows || [] }),
      __exec: () => run(sql, args),
    }),
    first: async () => run(sql, []).row,
  });
  return { prepare, batch: async (stmts) => stmts.map((st) => st.__exec()), __rows: rows, __tokens: tokens, __audit: audit };
}

async function post(db, path, body) {
  const res = await worker.fetch(
    new Request('https://w' + path, {
      method: 'POST',
      headers: { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    }),
    { DB: db, SHARED_SECRET: SECRET },
  );
  return await res.json();
}

let pass = 0, fail = 0;
function check(label, actual, expected) {
  const ok = actual === expected;
  ok ? pass++ : fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}  → ${actual}${ok ? '' : `  (expected ${expected})`}`);
}
const actions = (db) => db.__audit.map((a) => a.action);

{
  const db = makeDb([
    { hardware_id: 'VIN-ACTIVE', serial_number: '578300001', is_active: true },
    { hardware_id: 'VIN-BLOCKED', serial_number: '578300002', is_active: true, is_blocked: true },
    { hardware_id: 'VIN-INACTIVE', serial_number: null, is_active: false },
  ]);

  // --- wallpapers enrols first ---
  const wp = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'wallpapers', app_version_code: 182 });
  check('wallpapers enrols', wp.status, 'enrolled');
  check('token is 64 hex chars', /^[0-9a-f]{64}$/.test(wp.token || ''), true);
  check('hash stored, not the token', db.__tokens.get('VIN-ACTIVE|wallpapers').token_hash !== wp.token, true);
  check('audit enroll carries app_id', db.__audit.find((a) => a.action === 'enroll')?.after.app_id, 'wallpapers');

  // --- the STORE on the same car enrols too: NOT a conflict ---
  const st = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', app_version_code: 98 });
  check('store on same car enrols (own row)', st.status, 'enrolled');
  check('store token differs from wallpapers token', st.token !== wp.token, true);
  check('no conflict audited for a second APP', actions(db).includes('enroll_conflict'), false);
  check('two token rows for the car', [...db.__tokens.keys()].filter((k) => k.startsWith('VIN-ACTIVE|')).length, 2);

  // --- a second enrol for the SAME app is the conflict ---
  const clone = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store' });
  check('second store enrol (clone) refused', clone.status, 'already_enrolled');
  check('clone gets no token', clone.token, null);
  check('conflict audited with app_id', db.__audit.find((a) => a.action === 'enroll_conflict')?.after.app_id, 'store');

  // --- app_id defaults to wallpapers when missing (v1-era Postgres callers) ---
  check('missing app_id -> wallpapers row (already enrolled)', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE' })).status, 'already_enrolled');

  check('blocked car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-BLOCKED', app_id: 'store' })).status, 'refused');
  check('inactive car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-INACTIVE', app_id: 'store' })).status, 'refused');
  check('unknown car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-NOBODY', app_id: 'store' })).status, 'refused');

  // --- verify is per app ---
  check('verify wallpapers token as wallpapers', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'wallpapers', token: wp.token })).active, true);
  check('verify store token as store', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: st.token })).active, true);
  check('wallpapers token does NOT verify as store', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: wp.token })).active, false);
  check('wrong token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: 'f'.repeat(64) })).active, false);
  check('no token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store' })).active, false);
  check('app never enrolled -> enrolled=false', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'backbutton', token: 'x' })).enrolled, false);
  check('verify reports enrolled for store', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: 'x' })).enrolled, true);

  // --- rotation by own serial, per app ---
  const rot = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', activation_serial: '578300001' });
  check('own serial rotates the store token', rot.status, 'rotated');
  check('rotated token verifies', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: rot.token })).active, true);
  check('previous store token dead', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'store', token: st.token })).active, false);
  check('wallpapers token untouched by store rotation', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', app_id: 'wallpapers', token: wp.token })).active, true);
  check('store token_version is 2', db.__tokens.get('VIN-ACTIVE|store').token_version, 2);
  check('wrong serial cannot rotate', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', activation_serial: '578300099' })).status, 'already_enrolled');
  check('audit has rotate_token', actions(db).includes('rotate_token'), true);

  // --- operator reset: one app only ---
  const r1 = await post(db, '/v1/devices/set-state', { hardware_id: 'VIN-ACTIVE', reset_token: true, reset_token_app_id: 'store' });
  check('reset_token for store accepted', r1.status, 'ok');
  check('store token gone', db.__tokens.has('VIN-ACTIVE|store'), false);
  check('wallpapers token kept', db.__tokens.has('VIN-ACTIVE|wallpapers'), true);
  check('set-state reports remaining tokens', r1.tokens.map((t) => t.app_id).join(','), 'wallpapers');
  check('store can enrol again', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store' })).status, 'enrolled');

  // --- operator reset: all apps ---
  await post(db, '/v1/devices/set-state', { hardware_id: 'VIN-ACTIVE', reset_token: true });
  check('all tokens gone', [...db.__tokens.keys()].filter((k) => k.startsWith('VIN-ACTIVE|')).length, 0);
  check('audit has reset_token', actions(db).includes('reset_token'), true);

  // --- rename keeps tokens ---
  await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'wallpapers' });
  const mv = await post(db, '/v1/devices/migrate', { old_id: 'VIN-ACTIVE', new_id: 'VIN-RENAMED' });
  check('migrate moved the row', mv.moved, true);
  check('token followed the rename', db.__tokens.has('VIN-RENAMED|wallpapers'), true);
  check('old key gone', db.__tokens.has('VIN-ACTIVE|wallpapers'), false);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
