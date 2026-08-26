/**
 * Verifies the CLOSED_BLOCKS carve-out by driving the REAL worker.js through a
 * minimal in-memory stand-in for D1 — not a copy of the logic, so the test can
 * still fail if worker.js changes underneath it.
 *
 * The point of the exercise is the fleet: ~877 cars are already activated and
 * two of the three programs on them cannot be updated in the field. So the
 * assertions that matter most are not "the new codes work" but the ones that
 * prove nothing already licensed can be refused.
 *
 * Run:  node test-closed-blocks.mjs
 */

import worker from './worker.js';

// Workers give crypto.subtle.timingSafeEqual; Node does not. The worker only
// uses it to compare the shared secret, which is not what's under test.
if (!globalThis.crypto?.subtle?.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a), y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    return x.every((v, i) => v === y[i]);
  };
}

const SECRET = 'test-secret';

/** Just enough D1 to serve the statements handleActivate actually issues. */
function makeDb(seed = []) {
  const rows = new Map();
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
  return (await res.json()).status;
}

let pass = 0, fail = 0;
async function check(label, actual, expected) {
  const ok = actual === expected;
  ok ? pass++ : fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}  → ${actual}${ok ? '' : `  (expected ${expected})`}`);
}

// ---------------------------------------------------------------- issued codes
{
  const db = makeDb();
  await check('578300001  first issued code', await activate(db, 'CAR-A', '578300001'), 'success');
  await check('578300100  last issued code',  await activate(db, 'CAR-B', '578300100'), 'success');
  await check('578300050  mid-block',         await activate(db, 'CAR-C', '578300050'), 'success');
}

// ------------------------------------------------- the guess this change stops
{
  const db = makeDb();
  await check('578300101  one past the block',   await activate(db, 'CAR-D', '578300101'), 'invalid_format');
  await check('578300102  the reported guess',   await activate(db, 'CAR-E', '578300102'), 'invalid_format');
  await check('578300000  one before the block', await activate(db, 'CAR-F', '578300000'), 'invalid_format');
  await check('578300999  far end of the band',  await activate(db, 'CAR-G', '578300999'), 'invalid_format');
  await check('5783000010 issued code + a digit', await activate(db, 'CAR-H', '5783000010'), 'invalid_format');
  await check('578300      bare block prefix',    await activate(db, 'CAR-I', '578300'),     'invalid_format');
  await check('57830000a   non-numeric tail',     await activate(db, 'CAR-J', '57830000a'),  'invalid_format');
}

// ------------------------------------------- the open space must be unchanged
{
  const db = makeDb();
  await check('578301001  neighbouring band, still open', await activate(db, 'CAR-K', '578301001'), 'success');
  await check('578999999  unrelated 578 code',            await activate(db, 'CAR-L', '578999999'), 'success');
  await check('578000     short legacy-style 578 code',   await activate(db, 'CAR-M', '578000'),    'success');
  await check('154300001  wrong prefix, still refused',   await activate(db, 'CAR-N', '154300001'), 'invalid_format');
}

// ------------------------------------------------------- THE FLEET GUARANTEES
{
  // A car already licensed on a code that now falls inside a closed block must
  // still re-activate. No car is in this state today (the band was empty when
  // the codes were drawn), but this is the property that makes adding future
  // blocks survivable rather than a fleet incident.
  const db = makeDb([{ hardware_id: 'CAR-OLD', serial_number: '578300777', is_active: true }]);
  await check('re-activation of an owned in-block code', await activate(db, 'CAR-OLD', '578300777'), 'success');
}
{
  const db = makeDb([{ hardware_id: 'CAR-P', serial_number: '578555555', is_active: true }]);
  await check('re-activation of an owned open code', await activate(db, 'CAR-P', '578555555'), 'success');
}
{
  const db = makeDb([{ hardware_id: 'CAR-Q', serial_number: '7078123456', is_active: true }]);
  await check('legacy 7078 re-activation still works', await activate(db, 'CAR-Q', '7078123456'), 'success');
}
{
  const db = makeDb([{ hardware_id: 'CAR-R', serial_number: '578300001', is_active: true }]);
  await check('issued code on a SECOND car', await activate(db, 'CAR-S', '578300001'), 'serial_already_used');
}
{
  const db = makeDb([{ hardware_id: 'CAR-T', is_blocked: true, block_reason: 'admin' }]);
  await check('blocked car answers blocked, not invalid', await activate(db, 'CAR-T', '578300101'), 'blocked');
}
{
  const db = makeDb();
  await check('unused 7078 still retired', await activate(db, 'CAR-U', '7078999999'), 'invalid_format');
}

// --------------------------------- rejected codes still count toward the lock
{
  // 578300200…209: inside the block, none of them issued.
  const db = makeDb();
  let last;
  for (let i = 0; i < 10; i++) last = await activate(db, 'CAR-V', '578300' + (200 + i));
  await check('10 in-block guesses auto-block the car', last, 'blocked');
}
{
  // ...and a correct code in the middle of a guessing run clears the count,
  // which is why the run above had to avoid the issued range to reach the lock.
  const db = makeDb();
  await activate(db, 'CAR-W', '578300201');
  await activate(db, 'CAR-W', '578300202');
  await check('right code clears the failure run', await activate(db, 'CAR-W', '578300007'), 'success');
  let last;
  for (let i = 0; i < 9; i++) last = await activate(db, 'CAR-W', '578300' + (300 + i));
  await check('9 fresh failures do not block yet', last, 'invalid_format');
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
