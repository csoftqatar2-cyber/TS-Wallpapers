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

  if (before && before.is_blocked) {
    return json({ status: RESULT.BLOCKED, row: shape(before) });
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

  if (!validFormat) {
    return json({ status: RESULT.INVALID_FORMAT, row: shape(before) });
  }

  if (serialOwner && serialOwner.hardware_id !== hardwareId) {
    return json({ status: RESULT.SERIAL_ALREADY_USED, row: shape(before) });
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
  };

  await db.batch([
    db.prepare(
      `INSERT INTO devices (hardware_id, serial_number, is_active, is_blocked, activated_at, updated_at, source)
       VALUES (?, ?, 1, 0, ?, ?, 'activate')
       ON CONFLICT(hardware_id) DO UPDATE SET
         serial_number = excluded.serial_number,
         is_active     = 1,
         activated_at  = excluded.activated_at,
         updated_at    = excluded.updated_at,
         source        = 'activate'`,
    ).bind(hardwareId, serial, activatedAt, nowIso()),
    auditStmt(db, hardwareId, 'activate', before, after),
  ]);

  return json({ status: RESULT.SUCCESS, row: shape(await getDevice(db, hardwareId)) });
}

/** Admin block/unblock/activate toggle — the dashboard's write path. */
async function handleSetState(db, body) {
  const hardwareId = (body.hardware_id || '').trim();
  if (!hardwareId) return json({ error: 'hardware_id required' }, 400);

  const before = await getDevice(db, hardwareId);
  if (!before) return json({ error: 'unknown hardware_id' }, 404);

  const isActive = body.is_active === undefined ? !!before.is_active : !!body.is_active;
  const isBlocked = body.is_blocked === undefined ? !!before.is_blocked : !!body.is_blocked;
  const after = { ...shape(before), is_active: isActive, is_blocked: isBlocked };

  await db.batch([
    db.prepare('UPDATE devices SET is_active = ?, is_blocked = ?, updated_at = ?, source = ? WHERE hardware_id = ?')
      .bind(isActive ? 1 : 0, isBlocked ? 1 : 0, nowIso(), 'admin', hardwareId),
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
           source        = excluded.source`,
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
      `SELECT hardware_id, serial_number, is_active, is_blocked, activated_at
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
