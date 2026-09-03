/**
 * Per-car token: enroll / verify / reset_token against a minimal in-memory D1.
 * Run:  node test-device-token.mjs
 * Companion of test-closed-blocks.mjs (same harness idea, extended with the
 * UPDATE statements the token paths issue and a `.run()` that reports changes).
 */
// Workers give crypto.subtle.timingSafeEqual; Node does not. The worker only
// uses it to compare the shared secret, which is not what's under test.
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
  for (const r of seed) {
    rows.set(r.hardware_id, {
      hardware_id: r.hardware_id, serial_number: r.serial_number ?? null,
      is_active: r.is_active ? 1 : 0, is_blocked: r.is_blocked ? 1 : 0,
      activated_at: r.activated_at ?? null, updated_at: null, source: 'seed',
      failed_attempts: 0, first_failed_at: null, last_failed_at: null,
      last_failed_serial: null, blocked_at: null, block_reason: null,
      token_hash: r.token_hash ?? null, token_issued_at: null, token_version: r.token_version ?? 0,
      token_app_id: null,
    });
  }
  const audit = [];
  const run = (sql, args) => {
    const s = sql.replace(/\s+/g, ' ').trim();
    if (s.startsWith('SELECT * FROM devices WHERE hardware_id')) return { row: rows.get(args[0]) ?? null, changes: 0 };
    if (s.startsWith('INSERT INTO devices_audit')) { audit.push(args[1]); return { row: null, changes: 1 }; }
    if (s.startsWith('UPDATE devices SET token_hash = ?') && s.includes('serial_number = ?')) {
      const [hash, issuedAt, appId, updatedAt, hardwareId, serial] = args;
      const r = rows.get(hardwareId);
      if (!r || r.serial_number !== serial || !r.is_active || r.is_blocked) return { row: null, changes: 0 };
      Object.assign(r, { token_hash: hash, token_issued_at: issuedAt, token_version: r.token_version + 1, token_app_id: appId, updated_at: updatedAt });
      return { row: null, changes: 1 };
    }
    if (s.startsWith('UPDATE devices SET token_hash = ?')) {
      const [hash, issuedAt, appId, updatedAt, hardwareId] = args;
      const r = rows.get(hardwareId);
      if (!r || r.token_hash !== null || !r.is_active || r.is_blocked) return { row: null, changes: 0 };
      Object.assign(r, { token_hash: hash, token_issued_at: issuedAt, token_version: r.token_version + 1, token_app_id: appId, updated_at: updatedAt });
      return { row: null, changes: 1 };
    }
    if (s.startsWith('UPDATE devices SET token_hash = NULL')) {
      const [updatedAt, hardwareId] = args;
      const r = rows.get(hardwareId);
      if (!r) return { row: null, changes: 0 };
      Object.assign(r, { token_hash: null, token_issued_at: null, token_version: r.token_version + 1, updated_at: updatedAt });
      return { row: null, changes: 1 };
    }
    if (s.startsWith('INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, failed_attempts')) {
      // handleSetState upsert
      const [hardware_id, isActive, isBlocked, attempts] = args;
      const prev = rows.get(hardware_id) || { serial_number: null, token_hash: null, token_version: 0 };
      rows.set(hardware_id, { ...prev, hardware_id, is_active: isActive, is_blocked: isBlocked, failed_attempts: attempts });
      return { row: null, changes: 1 };
    }
    throw new Error('unhandled SQL: ' + s);
  };
  const prepare = (sql) => ({
    bind: (...args) => ({
      first: async () => run(sql, args).row,
      run: async () => ({ meta: { changes: run(sql, args).changes } }),
      all: async () => ({ results: [] }),
      __exec: () => run(sql, args),
    }),
    first: async () => run(sql, []).row,
  });
  return { prepare, batch: async (stmts) => stmts.map((st) => st.__exec()), __rows: rows, __audit: audit };
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

{
  const db = makeDb([
    { hardware_id: 'VIN-ACTIVE', serial_number: '578300001', is_active: true },
    { hardware_id: 'VIN-BLOCKED', serial_number: '578300002', is_active: true, is_blocked: true },
    { hardware_id: 'VIN-INACTIVE', serial_number: null, is_active: false },
  ]);

  const first = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', app_version_code: 99 });
  check('active car enrols once', first.status, 'enrolled');
  check('token is 64 hex chars', /^[0-9a-f]{64}$/.test(first.token || ''), true);
  check('hash stored, not the token', db.__rows.get('VIN-ACTIVE').token_hash !== first.token && !!db.__rows.get('VIN-ACTIVE').token_hash, true);
  check('audit row written', db.__audit.includes('enroll'), true);
  check('app_id recorded', db.__rows.get('VIN-ACTIVE').token_app_id, 'store');

  const second = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store' });
  check('second enrol (stolen APK) gets no token', second.status, 'already_enrolled');
  check('second enrol returns null token', second.token, null);

  check('blocked car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-BLOCKED' })).status, 'refused');
  check('inactive car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-INACTIVE' })).status, 'refused');
  check('unknown car refused', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-NOBODY' })).status, 'refused');

  check('verify: right token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: first.token })).active, true);
  check('verify: wrong token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: 'f'.repeat(64) })).active, false);
  check('verify: no token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE' })).active, false);
  check('verify: car without token is not active-by-token', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-INACTIVE', token: 'x' })).active, false);
  check('verify reports enrolled', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: 'x' })).enrolled, true);

  // Operator resets the token (factory-reset car) -> a NEW token can be issued once more
  const reset = await post(db, '/v1/devices/set-state', { hardware_id: 'VIN-ACTIVE', reset_token: true });
  check('reset_token accepted', reset.status ?? 'ok', 'ok');
  check('reset clears the hash', db.__rows.get('VIN-ACTIVE').token_hash, null);
  check('reset bumps token_version', db.__rows.get('VIN-ACTIVE').token_version, 2);
  check('old token no longer verifies', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: first.token })).active, false);
  const third = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store' });
  check('re-enrol after reset works', third.status, 'enrolled');
  check('new token differs', third.token !== first.token, true);
  check('audit has reset_token', db.__audit.includes('reset_token'), true);
  check('second enrol was audited as a conflict', db.__audit.includes('enroll_conflict'), true);

  // Reinstalled genuine car re-types its own code -> token rotates without the operator
  const rot = await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', activation_serial: '578300001' });
  check('own serial rotates the token', rot.status, 'rotated');
  check('rotated token verifies', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: rot.token })).active, true);
  check('previous token dead after rotation', (await post(db, '/v1/devices/verify', { hardware_id: 'VIN-ACTIVE', token: third.token })).active, false);
  check('wrong serial cannot rotate', (await post(db, '/v1/devices/enroll', { hardware_id: 'VIN-ACTIVE', app_id: 'store', activation_serial: '578300099' })).status, 'already_enrolled');
  check('audit has rotate_token', db.__audit.includes('rotate_token'), true);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
