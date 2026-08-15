/**
 * Device-activation decision service — the Cloudflare half of the shared
 * activation system used by all three programs on a car:
 *
 *   store.thabthaba.clock   (TS Wallpapers)
 *   com.csoft.backbutton    (TS Back Button)
 *   com.thabthaba.store     (ذبذبة ستور)
 *
 * WHO CALLS THIS: only Supabase Postgres, from inside the SECURITY DEFINER RPCs,
 * over the pgsql-http extension. No car ever talks to this Worker. The cars keep
 * calling the exact same Supabase RPC endpoints they always have — that contract
 * is frozen, because two of the three programs have no auto-update path at all
 * and a breaking change there can stay broken in the field indefinitely.
 *
 * WHY IT EXISTS: to move the actual licensing decision (is this serial already
 * used? is this car blocked? may this identity be renamed?) off Postgres and
 * into D1, so Supabase stops being the thing that owns activation.
 *
 * THE TWO RULES THAT SHAPE EVERYTHING HERE
 *
 *   1. A failure must surface as an ERROR, never as a successful "not activated"
 *      answer. All three clients keep their cached activation when a call errors,
 *      but clear it on a well-formed negative response. So: when in doubt, throw.
 *      Never invent a negative.
 *
 *   2. Postgres commits first; D1 is the decision authority, not the commit
 *      order. Divergence where Postgres says active and D1 does not is harmless
 *      (the car works, reconciliation fixes D1). Divergence the other way makes
 *      every program on the car deactivate itself. The caller is responsible for
 *      the ordering; what this Worker guarantees is that /activate is IDEMPOTENT
 *      for the same (hardware_id, serial) pair, so a retry after a failed local
 *      commit returns 'success' again and self-heals.
 *
 * Auth: a shared secret in `Authorization: Bearer <secret>`, set out-of-band with
 *   npx wrangler secret put SHARED_SECRET
 * It is never in this file and never in wrangler.toml. There is no CORS header
 * anywhere in here on purpose: no browser is meant to reach this.
 */

/**
 * Accepted activation-code prefixes.
 *   '578'  — everything issued from 2026-08-02 onward (578001, 578002, ...).
 *            Always valid for a brand-new activation.
 *   '7078' — the ~530 codes already in the field, issued up to 2026-08-02.
 *            Retired for NEW activations as of 2026-08-05: only honored when
 *            the serial is already on file (an existing car re-activating).
 *            An unused 7078 code can no longer activate a car for the first
 *            time. See handleActivate, which checks this against serialOwner.
 * Codes vary in length; only the prefix is checked, as it always has been.
 */
const NEW_SERIAL_PREFIX = '578';
const LEGACY_SERIAL_PREFIX = '7078';

/**
 * Brute-force lock. After this many CONSECUTIVE rejected codes, the hardware id
 * blocks itself and no code works on it again until an operator lifts the block
 * from the dashboard.
 *
 * Counted here rather than in the app because the app is the thing being
 * attacked: a counter in an APK is reset by clearing app data, and two of the
 * three programs sharing this system cannot be updated in the field at all.
 *
 * "Consecutive" is the whole rule — a successful activation zeroes it. So a
 * technician who mistypes a code twice on every install never drifts toward the
 * limit, while someone trying codes one after another reaches it in one sitting.
 * There is deliberately no time decay: a lock that quietly expires overnight is
 * one the operator never finds out about, and finding out is half the point.
 */
const FAILED_ATTEMPT_LIMIT = 10;

/** devices.block_reason — 'attempts' is the automatic lock, 'admin' the manual one. */
const BLOCK_REASON_ATTEMPTS = 'failed_attempts';

/** Response values, byte-identical to what activate_device has always returned. */
const RESULT = {
  SUCCESS: 'success',
  BLOCKED: 'blocked',
  INVALID_FORMAT: 'invalid_format',
  SERIAL_ALREADY_USED: 'serial_already_used',
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** Constant-time string compare, so the secret can't be recovered by timing. */
function secretMatches(given, expected) {
  if (typeof given !== 'string' || typeof expected !== 'string') return false;
  const a = new TextEncoder().encode(given);
  const b = new TextEncoder().encode(expected);
  if (a.byteLength !== b.byteLength) return false;
  return crypto.subtle.timingSafeEqual(a, b);
}

function authorized(request, env) {
  const header = request.headers.get('authorization') || '';
  const prefix = 'Bearer ';
  if (!header.startsWith(prefix)) return false;
  if (!env.SHARED_SECRET) return false;
  return secretMatches(header.slice(prefix.length), env.SHARED_SECRET);
}

function nowIso() {
  return new Date().toISOString();
}

/** A device row as the callers expect to see it (booleans, not SQLite 0/1). */
function shape(row) {
  if (!row) return null;
  return {
    hardware_id: row.hardware_id,
    serial_number: row.serial_number,
    is_active: !!row.is_active,
    is_blocked: !!row.is_blocked,
    activated_at: row.activated_at,
    updated_at: row.updated_at,
    source: row.source,
    failed_attempts: row.failed_attempts || 0,
    last_failed_at: row.last_failed_at || null,
    last_failed_serial: row.last_failed_serial || null,
    blocked_at: row.blocked_at || null,
    block_reason: row.block_reason || null,
  };
}

async function getDevice(db, hardwareId) {
  return db.prepare('SELECT * FROM devices WHERE hardware_id = ?').bind(hardwareId).first();
}

function auditStmt(db, hardwareId, action, before, after) {
  return db
    .prepare('INSERT INTO devices_audit (hardware_id, action, before_json, after_json) VALUES (?, ?, ?, ?)')
    .bind(
      hardwareId,
      action,
      before ? JSON.stringify(shape(before)) : null,
      after ? JSON.stringify(after) : null,
    );
}

/**
 * Identity rename, mirroring migrate_device_hardware_id's guards exactly.
 * Returns true when a row actually moved.
 *
 * NOTE the deliberate asymmetry with Postgres: there, this same rename also
 * cascades across wallpapers and wallpaper_hides via FKs. Here it only touches
 * the activation row, because the wallpaper tables do not live in D1. That is
 * precisely why Postgres — not this Worker — must be the one to execute a
 * rename against the real data; this endpoint exists to keep D1 in step, not to
 * drive the migration.
 */
async function migrateIdentity(db, oldId, newId) {
  if (!oldId || !newId || oldId === newId) return false;

  const target = await getDevice(db, newId);
  if (target) return false;                       // never merge two cars
  const source = await getDevice(db, oldId);
  if (!source) return false;                      // nothing to move

  await db.batch([
    db.prepare('UPDATE devices SET hardware_id = ?, updated_at = ?, source = ? WHERE hardware_id = ?')
      .bind(newId, nowIso(), 'migrate', oldId),
    auditStmt(db, newId, 'migrate', source, { ...shape(source), hardware_id: newId }),
  ]);
  return true;
}

/**
 * One rejected code. Increments the consecutive-failure counter for this
 * hardware id and, on the FAILED_ATTEMPT_LIMIT-th one, blocks it.
 *
 * The row is created if the id has never been seen: whoever is typing codes into
 * a head unit that owns no licence is exactly who this is for, and an attempt
 * that leaves no trace is one the operator can never be told about. serial_number
 * is never written here — a rejected code does not belong to this car, and
 * writing it would burn the UNIQUE index that IS the licensing rule.
 *
 * is_active is deliberately left alone. Blocking already stops the car; also
 * clearing the activation would mean an operator lifting a block by mistake has
 * a second, silent thing to put back.
 */
async function recordFailure(db, hardwareId, serial, before, status) {
  const attempts = ((before && before.failed_attempts) || 0) + 1;
  const blockNow = attempts >= FAILED_ATTEMPT_LIMIT;
  const stamp = nowIso();

  const after = {
    ...(shape(before) || { hardware_id: hardwareId, serial_number: null, is_active: false }),
    is_blocked: blockNow,
    failed_attempts: attempts,
    last_failed_at: stamp,
    last_failed_serial: serial,
    blocked_at: blockNow ? stamp : null,
    block_reason: blockNow ? BLOCK_REASON_ATTEMPTS : null,
  };

  await db.batch([
    db.prepare(
      `INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, failed_attempts,
                            first_failed_at, last_failed_at, last_failed_serial, blocked_at,
                            block_reason, updated_at, source)
       VALUES (?, NULL, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(hardware_id) DO UPDATE SET
         is_blocked         = excluded.is_blocked,
         failed_attempts    = excluded.failed_attempts,
         first_failed_at    = COALESCE(devices.first_failed_at, excluded.first_failed_at),
         last_failed_at     = excluded.last_failed_at,
         last_failed_serial = excluded.last_failed_serial,
         blocked_at         = COALESCE(devices.blocked_at, excluded.blocked_at),
         block_reason       = COALESCE(devices.block_reason, excluded.block_reason),
         updated_at         = excluded.updated_at,
         source             = excluded.source`,
    ).bind(
      hardwareId,
      blockNow ? 1 : 0,
      attempts,
      stamp,
      stamp,
      serial,
      blockNow ? stamp : null,
      blockNow ? BLOCK_REASON_ATTEMPTS : null,
      stamp,
      blockNow ? 'auto_block' : 'failed_attempt',
    ),
    auditStmt(db, hardwareId, blockNow ? 'auto_block' : 'failed_attempt', before, after),
  ]);

  // The 10th rejection answers 'blocked', not 'invalid_format': from this moment
  // that is the true state of the car, and all three programs already have a
  // translated message for it. The meta fields below are for Postgres, which
  // mirrors them into the dashboard; no car reads them.
  return json({
    status: blockNow ? RESULT.BLOCKED : status,
    row: shape(await getDevice(db, hardwareId)),
    attempts,
    attempts_limit: FAILED_ATTEMPT_LIMIT,
    attempts_left: Math.max(0, FAILED_ATTEMPT_LIMIT - attempts),
    blocked_now: blockNow,
    block_reason: blockNow ? BLOCK_REASON_ATTEMPTS : null,
    last_failed_serial: serial,
  });
}

/**
 * The activation decision. Mirrors activate_device's logic and its four return
 * values in the same order of checks, because the order is observable: a blocked
 * car with a malformed serial has always answered 'blocked', not 'invalid_format'.
 */
async function handleActivate(db, body) {
  const hardwareId = (body.hardware_id || '').trim();
  const serial = (body.activation_serial || '').trim();
  const legacyId = (body.legacy_hw_id || '').trim();
  const activatedAt = body.activated_at || nowIso();

  if (!hardwareId) return json({ error: 'hardware_id required' }, 400);
  if (!serial) return json({ error: 'activation_serial required' }, 400);

  // Same pre-step as the RPC: a car already registered under its legacy id that
  // now reports a VIN keeps its row, rather than registering itself as new.
  if (legacyId && legacyId !== hardwareId) {
    const existing = await getDevice(db, hardwareId);
    if (!existing) await migrateIdentity(db, legacyId, hardwareId);
  }

  const before = await getDevice(db, hardwareId);

  // An already-blocked car answers 'blocked' and its counter is left where it
  // was: there is nothing left to count toward, and a number that keeps climbing
  // after the lock only makes the dashboard harder to read.
  if (before && before.is_blocked) {
    return json({
      status: RESULT.BLOCKED,
      row: shape(before),
      attempts: before.failed_attempts || 0,
      attempts_limit: FAILED_ATTEMPT_LIMIT,
      blocked_now: false,
      block_reason: before.block_reason || null,
    });
  }

  const serialOwner = await db
    .prepare('SELECT hardware_id FROM devices WHERE serial_number = ?')
    .bind(serial)
    .first();

  // '578...' is always valid. '7078...' is valid only when it's already on
  // file — a genuinely new (never-seen) 7078 code no longer activates anything.
  const validFormat =
    serial.startsWith(NEW_SERIAL_PREFIX) ||
    (serial.startsWith(LEGACY_SERIAL_PREFIX) && !!serialOwner);

  // Both rejections count the same. A code belonging to someone else's car is
  // not a typo — if anything it is the more deliberate of the two.
  if (!validFormat) {
    return await recordFailure(db, hardwareId, serial, before, RESULT.INVALID_FORMAT);
  }

  if (serialOwner && serialOwner.hardware_id !== hardwareId) {
    return await recordFailure(db, hardwareId, serial, before, RESULT.SERIAL_ALREADY_USED);
  }

  // Idempotent by construction: re-activating the same car with the same serial
  // lands here and answers 'success' again. That is what makes a retry after a
  // failed commit on the Postgres side self-healing rather than a dead end.
  const after = {
    hardware_id: hardwareId,
    serial_number: serial,
    is_active: true,
    is_blocked: false,
    activated_at: activatedAt,
    failed_attempts: 0,
  };

  // The right code clears the run of wrong ones. Without this, a car whose
  // installer fumbled the code a few times on each of several visits would creep
  // toward the limit over its lifetime and lock out a paying customer.
  await db.batch([
    db.prepare(
      `INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at, updated_at, source)
       VALUES (?, ?, 1, 0, ?, ?, 'activate')
       ON CONFLICT(hardware_id) DO UPDATE SET
         serial_number      = excluded.serial_number,
         is_active          = 1,
         activated_at       = excluded.activated_at,
         updated_at         = excluded.updated_at,
         source             = 'activate',
         failed_attempts    = 0,
         first_failed_at    = NULL,
         last_failed_at     = NULL,
         last_failed_serial = NULL,
         blocked_at         = NULL,
         block_reason       = NULL`,
    ).bind(hardwareId, serial, activatedAt, nowIso()),
    auditStmt(db, hardwareId, 'activate', before, after),
  ]);

  return json({
    status: RESULT.SUCCESS,
    row: shape(await getDevice(db, hardwareId)),
    attempts: 0,
    attempts_limit: FAILED_ATTEMPT_LIMIT,
    blocked_now: false,
  });
}

/**
 * Admin block/unblock/activate toggle — the dashboard's write path.
 *
 * `reset_attempts` is what makes lifting an automatic block actually stick: leave
 * the counter at 10 and the very next wrong code re-locks the car instantly, so
 * from the operator's side the unblock button would look broken.
 *
 * `create` exists because a car can be blocked from the dashboard before D1 has
 * ever heard of it (Postgres has held the fleet far longer than D1 has). Without
 * it, blocking such a car would 404 and the two sides would disagree about the
 * one flag it is least acceptable to disagree about.
 */
async function handleSetState(db, body) {
  const hardwareId = (body.hardware_id || '').trim();
  if (!hardwareId) return json({ error: 'hardware_id required' }, 400);

  const before = await getDevice(db, hardwareId);
  if (!before && !body.create) return json({ error: 'unknown hardware_id' }, 404);

  const isActive = body.is_active === undefined ? !!(before && before.is_active) : !!body.is_active;
  const isBlocked = body.is_blocked === undefined ? !!(before && before.is_blocked) : !!body.is_blocked;
  const resetAttempts = !!body.reset_attempts;
  const stamp = nowIso();
  const attempts = resetAttempts ? 0 : (before && before.failed_attempts) || 0;
  const reason = isBlocked
    ? body.block_reason || (before && before.block_reason) || 'admin'
    : null;

  const after = {
    ...(shape(before) || { hardware_id: hardwareId, serial_number: null }),
    is_active: isActive,
    is_blocked: isBlocked,
    failed_attempts: attempts,
    blocked_at: isBlocked ? (before && before.blocked_at) || stamp : null,
    block_reason: reason,
  };

  await db.batch([
    db.prepare(
      `INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, failed_attempts,
                            blocked_at, block_reason, updated_at, source)
       VALUES (?, NULL, ?, ?, ?, ?, ?, ?, 'admin')
       ON CONFLICT(hardware_id) DO UPDATE SET
         is_active          = excluded.is_active,
         is_blocked         = excluded.is_blocked,
         failed_attempts    = excluded.failed_attempts,
         first_failed_at    = CASE WHEN excluded.failed_attempts = 0 THEN NULL ELSE devices.first_failed_at END,
         last_failed_at     = CASE WHEN excluded.failed_attempts = 0 THEN NULL ELSE devices.last_failed_at END,
         last_failed_serial = CASE WHEN excluded.failed_attempts = 0 THEN NULL ELSE devices.last_failed_serial END,
         blocked_at         = CASE WHEN excluded.is_blocked = 1 THEN COALESCE(devices.blocked_at, excluded.blocked_at) ELSE NULL END,
         block_reason       = excluded.block_reason,
         updated_at         = excluded.updated_at,
         source             = 'admin'`,
    ).bind(
      hardwareId,
      isActive ? 1 : 0,
      isBlocked ? 1 : 0,
      attempts,
      isBlocked ? stamp : null,
      reason,
      stamp,
    ),
    auditStmt(db, hardwareId, 'admin', before, after),
  ]);

  return json({ status: 'ok', row: shape(await getDevice(db, hardwareId)) });
}

/**
 * Bulk upsert — the initial load and the reconciliation sweep. Not on any hot
 * path. Chunked so one call can carry the whole fleet without hitting D1's
 * per-batch statement limit.
 */
async function handleBulkUpsert(db, body) {
  const rows = Array.isArray(body) ? body : body.rows;
  if (!Array.isArray(rows)) return json({ error: 'expected an array of rows' }, 400);

  const source = (!Array.isArray(body) && body.source) || 'bulk_import';
  const stamp = nowIso();
  const CHUNK = 50;
  let written = 0;

  for (let i = 0; i < rows.length; i += CHUNK) {
    const statements = rows.slice(i, i + CHUNK).map((r) =>
      db.prepare(
        `INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at, updated_at, source)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(hardware_id) DO UPDATE SET
           serial_number = excluded.serial_number,
           is_active     = excluded.is_active,
           is_blocked    = excluded.is_blocked,
           activated_at  = excluded.activated_at,
           updated_at    = excluded.updated_at,
           source        = excluded.source,
           -- A push that lifts a block also clears the counter behind it, for the
           -- same reason the unblock endpoint does: otherwise the nightly sweep
           -- would "unblock" a car that re-locks on its next wrong code. Cars
           -- that stay blocked keep their counter and reason untouched.
           failed_attempts = CASE WHEN excluded.is_blocked = 0 THEN 0 ELSE devices.failed_attempts END,
           block_reason    = CASE WHEN excluded.is_blocked = 0 THEN NULL ELSE devices.block_reason END,
           blocked_at      = CASE WHEN excluded.is_blocked = 0 THEN NULL ELSE devices.blocked_at END`,
      ).bind(
        r.hardware_id,
        r.serial_number ?? null,
        r.is_active ? 1 : 0,
        r.is_blocked ? 1 : 0,
        r.activated_at ?? null,
        stamp,
        source,
      ),
    );
    await db.batch(statements);
    written += statements.length;
  }

  return json({ status: 'ok', written });
}

/** Paginated dump, for checksum comparison against Postgres. */
async function handleExport(db, url) {
  const cursor = url.searchParams.get('cursor') || '';
  const limit = Math.min(parseInt(url.searchParams.get('limit') || '500', 10), 1000);

  const { results } = await db
    .prepare(
      `SELECT hardware_id, serial_number, is_active, is_blocked, activated_at,
              failed_attempts, last_failed_at, last_failed_serial, blocked_at, block_reason
         FROM devices WHERE hardware_id > ?
        ORDER BY hardware_id LIMIT ?`,
    )
    .bind(cursor, limit)
    .all();

  const rows = (results || []).map(shape);
  return json({
    rows,
    next_cursor: rows.length === limit ? rows[rows.length - 1].hardware_id : null,
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // Health is deliberately unauthenticated so external monitoring can watch it,
    // and deliberately says nothing beyond "the binding answers".
    if (path === '/v1/health') {
      try {
        await env.DB.prepare('SELECT 1').first();
        return json({ status: 'ok' });
      } catch (err) {
        return json({ status: 'error', error: String(err) }, 503);
      }
    }

    if (!authorized(request, env)) return json({ error: 'unauthorized' }, 401);

    const db = env.DB;

    try {
      if (request.method === 'GET' && path === '/v1/devices/export') {
        return await handleExport(db, url);
      }

      if (request.method === 'POST') {
        const body = await request.json();
        switch (path) {
          case '/v1/devices/activate':
            return await handleActivate(db, body);
          case '/v1/devices/set-state':
            return await handleSetState(db, body);
          case '/v1/devices/bulk-upsert':
            return await handleBulkUpsert(db, body);
          case '/v1/devices/migrate': {
            const moved = await migrateIdentity(db, (body.old_id || '').trim(), (body.new_id || '').trim());
            return json({ status: 'ok', moved });
          }
        }
      }

      return json({ error: 'not found' }, 404);
    } catch (err) {
      // Rule 1: surface the failure. Never degrade into a plausible-looking
      // negative answer that a car would read as "you are not activated".
      return json({ error: String(err && err.message ? err.message : err) }, 500);
    }
  },
};
