/**
 * POST /v1/controller/entitled (2026-11-01): a valid dashboard licence unlocks the cloud wallpapers.
 * Drives the REAL worker.js with a tiny in-memory D1 stand-in.
 *
 * Run:  node test-controller-entitled.mjs
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

function makeDb(ents, { broken = false, devices = {} } = {}) {
  return {
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (broken) throw new Error('D1 down');
              if (/FROM controller_entitlements WHERE hardware_id = \?/.test(sql)) return ents[args[0]] ?? null;
              if (/FROM devices WHERE hardware_id = \?/.test(sql)) return devices[args[0]] ?? null;
              throw new Error('unexpected SQL: ' + sql);
            },
          };
        },
      };
    },
  };
}

async function ask(db, body, secret = SECRET) {
  const r = await worker.fetch(
    new Request('https://x/v1/controller/entitled', {
      method: 'POST',
      headers: { authorization: 'Bearer ' + secret, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    }),
    { DB: db, SHARED_SECRET: SECRET },
  );
  return { status: r.status, body: await r.json() };
}

let fail = 0;
function check(name, cond, got) {
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : ' -> ' + JSON.stringify(got)));
  if (!cond) fail++;
}

const ents = {
  'VIN-LIVE': { hardware_id: 'VIN-LIVE', revoked_at: null },
  'VIN-REV': { hardware_id: 'VIN-REV', revoked_at: '2026-10-20T00:00:00Z' },
  'AID-OLD': { hardware_id: 'AID-OLD', revoked_at: null },
};
const db = makeDb(ents);

let r = await ask(db, { hardware_id: 'VIN-LIVE' });
check('live entitlement -> entitled', r.status === 200 && r.body.entitled === true && r.body.via === 'id', r);
r = await ask(db, { hardware_id: 'VIN-REV' });
check('revoked -> not entitled', r.status === 200 && r.body.entitled === false, r);
r = await ask(db, { hardware_id: 'VIN-REV', legacy_hw_id: 'AID-OLD' });
check('revoked id row is not rescued by legacy', r.body.entitled === false, r);
r = await ask(db, { hardware_id: 'VIN-NEW', legacy_hw_id: 'AID-OLD' });
check('legacy id entitlement -> entitled', r.body.entitled === true && r.body.via === 'legacy', r);
r = await ask(db, { hardware_id: 'VIN-NONE' });
check('no row -> not entitled', r.status === 200 && r.body.entitled === false, r);
r = await ask(db, {});
check('missing id -> 400', r.status === 400, r);
r = await ask(db, { hardware_id: 'VIN-LIVE' }, 'wrong');
check('bad secret -> 401', r.status === 401, r);
r = await ask(makeDb(ents, { broken: true }), { hardware_id: 'VIN-LIVE' });
check('D1 failure -> 500, never an invented no', r.status === 500, r);

const fam = makeDb(ents, { devices: { 'VIN-FAM': { is_active: 1, is_blocked: 0 }, 'VIN-BLK': { is_active: 1, is_blocked: 1 }, 'VIN-REV': { is_active: 1, is_blocked: 0 } } });
r = await ask(fam, { hardware_id: 'VIN-FAM' });
check('no row + active D1 family (grandfather / G700 core) -> entitled', r.body.entitled === true && r.body.via === 'family', r);
r = await ask(fam, { hardware_id: 'VIN-BLK' });
check('no row + blocked family -> not entitled', r.body.entitled === false, r);
r = await ask(fam, { hardware_id: 'VIN-REV' });
check('revoked entitlement wins over an active family row', r.body.entitled === false, r);
r = await ask(makeDb({ 'VIN-CORE': { hardware_id: 'VIN-CORE', revoked_at: null, tier: 'core', kind: 'code' } }), { hardware_id: 'VIN-CORE' });
check('G700 core-tier code row -> entitled', r.body.entitled === true, r);

if (fail) { console.log(fail + ' failed'); process.exit(1); }
console.log('all passed');
