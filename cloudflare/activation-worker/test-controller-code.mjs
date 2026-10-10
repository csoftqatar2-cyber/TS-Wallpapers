/**
 * Controller licence codes ('579' + 6 digits, 2026-10-07) typed into the activation box must be
 * refused as 'invalid_format' WITHOUT counting toward the brute-force lock (FAILED_ATTEMPT_LIMIT).
 * Drives the REAL worker.js through the same in-memory D1 stand-in as test-closed-blocks.mjs.
 *
 * Run:  node test-controller-code.mjs
 */

import worker from './worker.js';

if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    return x.every((v, i) => v === y[i]);
  };
}

const SECRET = 'test-secret';

/** Just enough D1 to serve the statements handleActivate actually issues. */
function makeDb(seed = [], { ctrl = [], issued = [] } = {}) {
  const rows = new Map();
  const ctrlCodes = new Set(ctrl), issuedCodes = new Set(issued);
  for (const r of seed) {
    rows.set(r.hardware_id, {
      hardware_id: r.hardware_id, serial_number: r.serial_number ?? null,
      is_active: r.is_active ? 1 : 0, is_blocked: r.is_blocked ? 1 : 0,
      activated_at: r.activated_at ?? null, updated_at: null, source: 'seed',
      failed_attempts: r.failed_attempts ?? 0, first_failed_at: null,
      last_failed_at: null, last_failed_serial: null, blocked_at: null,
      block_reason: r.block_reason ?? null,
    });
  }

  const run = (sql, args) => {
    const s = sql.replace(/\s+/g, ' ').trim();

    if (s.startsWith('SELECT * FROM devices WHERE hardware_id'))
      return rows.get(args[0]) ?? null;

    if (s.startsWith('SELECT hardware_id FROM devices WHERE serial_number')) {
      for (const r of rows.values()) if (r.serial_number === args[0]) return r;
      return null;
    }

    if (s.startsWith('INSERT INTO devices_audit')) return null;

    if (s.startsWith('SELECT 1 AS x FROM controller_codes WHERE code')) return ctrlCodes.has(args[0]) ? { x: 1 } : null;
    if (s.startsWith('SELECT expires_at, used_by FROM issued_codes WHERE serial'))
      return issuedCodes.has(args[0]) ? { expires_at: '2999-01-01T00:00:00Z', used_by: null } : null;
    if (s.startsWith('UPDATE issued_codes')) return null;

    if (s.startsWith('INSERT INTO devices') && s.includes("'activate'")) {
      const [hardware_id, serial_number, activated_at, updated_at] = args;
      const prev = rows.get(hardware_id) || {};
      rows.set(hardware_id, {
        ...prev, hardware_id, serial_number, is_active: 1, is_blocked: 0,
        activated_at, updated_at, source: 'activate', failed_attempts: 0,
        first_failed_at: null, last_failed_at: null, last_failed_serial: null,
        blocked_at: null, block_reason: null,
      });
      return null;
    }

    if (s.startsWith('INSERT INTO devices')) {           // recordFailure
      const [hardware_id, isBlocked, attempts, , lastFailedAt, lastSerial, blockedAt, reason] = args;
      const prev = rows.get(hardware_id) || { serial_number: null, is_active: 0 };
      rows.set(hardware_id, {
        ...prev, hardware_id, is_blocked: isBlocked, failed_attempts: attempts,
        last_failed_at: lastFailedAt, last_failed_serial: lastSerial,
        blocked_at: prev.blocked_at ?? blockedAt, block_reason: prev.block_reason ?? reason,
      });
      return null;
    }

    throw new Error('unhandled SQL: ' + s);
  };

  const prepare = (sql) => ({
    bind: (...args) => ({
      first: async () => run(sql, args),
      all: async () => ({ results: [] }),
      __exec: () => run(sql, args),
    }),
    first: async () => run(sql, []),
  });

  return { prepare, batch: async (stmts) => stmts.map((s) => s.__exec()), __rows: rows };
}

async function activate(db, hardware_id, activation_serial) {
  const res = await worker.fetch(
    new Request('https://w/v1/devices/activate', {
      method: 'POST',
      headers: { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' },
      body: JSON.stringify({ hardware_id, activation_serial }),
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

// ------------------------------------------------- refused, not counted
{
  const db = makeDb();
  const r = await activate(db, 'CAR-A', '579123456');
  check('579+6 digits answers invalid_format', r.status, 'invalid_format');
  check('response attempts is 0 (Postgres mirror no-op)', r.attempts, 0);
  check('response blocked_now false', r.blocked_now, false);
  check('no D1 row created for an unknown car', db.__rows.has('CAR-A'), false);
}
{
  const db = makeDb([{ hardware_id: 'CAR-B', serial_number: '578300005', is_active: true, failed_attempts: 9 }]);
  let last;
  for (let i = 0; i < 25; i++) last = await activate(db, 'CAR-B', '579' + String(100000 + i));
  check('25 controller codes on a car at 9/10 never block it', last.status, 'invalid_format');
  check('counter untouched (still 9)', db.__rows.get('CAR-B').failed_attempts, 9);
  check('car still not blocked', db.__rows.get('CAR-B').is_blocked, 0);
  check('last_failed_serial not written', db.__rows.get('CAR-B').last_failed_serial, null);
  check('car still active', db.__rows.get('CAR-B').is_active, 1);
  check('its own code still re-activates', (await activate(db, 'CAR-B', '578300005')).status, 'success');
}

// ------------------------------------------------- order and neighbours unchanged
{
  const db = makeDb([{ hardware_id: 'CAR-C', is_blocked: true, block_reason: 'admin' }]);
  check('blocked car typing a controller code answers blocked', (await activate(db, 'CAR-C', '579123456')).status, 'blocked');
}
{
  const db = makeDb([{ hardware_id: 'CAR-D', serial_number: '579000111', is_active: true }]);
  check('a 579 serial already on file for this car still re-activates (Rule 2)', (await activate(db, 'CAR-D', '579000111')).status, 'success');
}
{
  // Not the controller shape: 8 or 10 digits after 579 → the ordinary counted rejection.
  const db = makeDb();
  const r1 = await activate(db, 'CAR-E', '57912345');
  check('579+5 digits is an ordinary rejection', r1.status, 'invalid_format');
  check('...and it IS counted', r1.attempts, 1);
  const r2 = await activate(db, 'CAR-E', '5791234567');
  check('579+7 digits is counted too', r2.attempts, 2);
  const r3 = await activate(db, 'CAR-E', '579123456');
  check('a controller code after them leaves the count at 2', db.__rows.get('CAR-E').failed_attempts, 2);
  check('...and keeps the last counted serial', db.__rows.get('CAR-E').last_failed_serial, '5791234567');
}
{
  const db = makeDb();
  let last;
  for (let i = 0; i < 10; i++) last = await activate(db, 'CAR-F', '578300' + (200 + i));
  check('ordinary guesses still auto-block at 10', last.status, 'blocked');
}

// ------------------------------------------------- 6-digit controller codes (2026-10-10)
{
  const db = makeDb([{ hardware_id: 'CAR-G', serial_number: '578300007', is_active: true, failed_attempts: 9 }], { ctrl: ['048213', '777001'] });
  const r = await activate(db, 'CAR-G', '048213');
  check('a 6-digit controller code on file answers invalid_format', r.status, 'invalid_format');
  check('...with attempts 0 (Postgres mirror no-op)', r.attempts, 0);
  check('...and reason controller_code', r.reason, 'controller_code');
  for (let i = 0; i < 5; i++) await activate(db, 'CAR-G', '777001');
  check('a car at 9/10 typing controller codes is not counted', db.__rows.get('CAR-G').failed_attempts, 9);
  check('...not blocked', db.__rows.get('CAR-G').is_blocked, 0);
  check('...still active with its own serial', db.__rows.get('CAR-G').serial_number, '578300007');
  const n = await activate(db, 'CAR-G', '123987');
  check('a 6-digit code that is NOT a controller code is still an ordinary counted rejection', n.attempts, 10);
}
{
  const db = makeDb([], { ctrl: ['048213'] });
  await activate(db, 'CAR-H', '048213');
  check('no D1 row created for an unknown car typing a 6-digit controller code', db.__rows.has('CAR-H'), false);
}
{
  const db = makeDb([], { ctrl: ['555666'], issued: ['555666'] });
  check('a minted store code wins over a same-digit controller row (never minted, guard only)', (await activate(db, 'CAR-I', '555666')).status, 'success');
}
{
  const db = makeDb([{ hardware_id: 'CAR-J', serial_number: '246810', is_active: true }], { ctrl: ['246810'] });
  check('Rule 2: a car re-typing its own 6-digit serial still re-activates', (await activate(db, 'CAR-J', '246810')).status, 'success');
}

console.log(`
${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
