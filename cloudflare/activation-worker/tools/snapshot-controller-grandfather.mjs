#!/usr/bin/env node
/**
 * Controller licence — grandfather snapshot (owner's decision 2026-10-07).
 *
 * Every car REGISTERED (activated at least once) before the cutoff keeps the ذبذبة Dashboard
 * Controller for free. This tool builds that set once and writes it into D1
 * `controller_entitlements` (migration 0008) as kind='grandfather'.
 *
 *   CUTOFF = 2026-10-07T21:00:00Z  (end of 2026-10-07, Qatar time, UTC+3)
 *
 * The set (union, first source wins for snapshot_source):
 *   pg.devices  — Postgres public.devices rows with created_at < CUTOFF that were activated at
 *                 least once (activated_at or serial_number set). Rows created only by failed
 *                 code attempts (never activated) are NOT included. is_active / is_blocked are
 *                 ignored on purpose: the family gate still governs blocked cars.
 *   pg.alias    — device_id_aliases.old_id whose current_id is in pg.devices (a car that later
 *                 started reporting its VIN keeps its licence under the old id too).
 *   d1.devices  — D1 devices rows with activated_at < CUTOFF (safety net).
 * NOT included: cars that only opened the store (store_installs) and never activated.
 *
 * Modes
 *   --dry-run      (default) read both sides, print counts, write the SQL file. Writes nothing remote.
 *   --apply        also executes the SQL file against the REMOTE D1. Refuses to run before the
 *                  cutoff unless --force-early is given (a car activated later today must be in).
 *                  INSERT OR IGNORE: re-running is safe and never touches an existing row
 *                  (a revoked or code-granted row keeps its state).
 *
 * Reaching Postgres (read-only SELECTs; nothing is ever written to Supabase):
 *   --pg-json <file>          pre-exported rows (see --print-pg-sql for the exact queries); shape
 *                             { "devices": [[hardware_id, created_at, activated_at, has_serial], …]
 *                               | [{hardware_id, created_at, activated_at, serial_number|has_serial}, …],
 *                               "aliases": [[old_id, current_id], …] | [{old_id, current_id}, …] }
 *   SUPABASE_ACCESS_TOKEN env  a Supabase personal access token; the tool then runs the two SELECTs
 *                             itself through the Management API (POST /v1/projects/<ref>/database/query).
 *                             The token is read from the environment only and never printed.
 *   --print-pg-sql            print the two SELECTs and exit.
 * Reaching D1: `npx wrangler d1 execute ts-activation --remote --json --command "SELECT …"`
 *   (uses the local wrangler login, or CLOUDFLARE_API_TOKEN if set).
 *
 * Other flags
 *   --out <file>               SQL output path (default tools/out/controller-grandfather-<stamp>.sql, git-ignored)
 *   --batch <n>                rows per INSERT statement (default 200)
 *   --expect <hardware_id>     add an id that MUST be in the set (repeatable). The bench/test ids below
 *                              are always asserted; a missing one fails the run (exit 2) before any apply.
 *
 * Run (from cloudflare/activation-worker):
 *   node tools/snapshot-controller-grandfather.mjs --pg-json pg.json            # dry run
 *   node tools/snapshot-controller-grandfather.mjs --pg-json pg.json --apply    # after the cutoff only
 */

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const CUTOFF = '2026-10-07T21:00:00Z';
const CUTOFF_MS = Date.parse(CUTOFF);
const GRANTED_BY = 'snapshot-2026-10-07';
const PG_PROJECT_REF = 'ihgmqwzdpugdzddobhbc';
const D1_NAME = 'ts-activation';

// Bench / test head units that must be grandfathered (resolved from repo docs + Postgres 2026-10-07).
const MUST_INCLUDE = [
  ['SYS-1b2021f9', 'AI Box test channel'],
  ['SYS-00000091439HSD215', 'HSD-215 bench (client_name «بنش HSD-215»)'],
  // BYD ids are 'VIN-' + the controller's own car id (thab_cars.vin in the controller telemetry project).
  ['VIN-byd00791B0130BDA6AF', 'Leopard 5 2025 UI6 bench (l5_2025_ui6, 192.168.0.224)'],
  ['VIN-byd6E11D7EE1B74FF21', 'Leopard 5 2025 UI6 second unit (l5_2025_ui6 car2)'],
  ['VIN-byd84212E16FCF16FEF', 'Leopard 8 2025 UI6 (l8_2025_ui6)'],
];

const here = dirname(fileURLToPath(import.meta.url));
const workerDir = resolve(here, '..');

// ------------------------------------------------------------------ args
const argv = process.argv.slice(2);
const opt = { apply: false, forceEarly: false, pgJson: null, out: null, batch: 200, expect: [], printSql: false };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--dry-run') opt.apply = false;
  else if (a === '--apply') opt.apply = true;
  else if (a === '--force-early') opt.forceEarly = true;
  else if (a === '--pg-json') opt.pgJson = argv[++i];
  else if (a === '--out') opt.out = argv[++i];
  else if (a === '--batch') opt.batch = Math.max(1, parseInt(argv[++i], 10) || 200);
  else if (a === '--expect') opt.expect.push(argv[++i]);
  else if (a === '--print-pg-sql') opt.printSql = true;
  else { console.error(`unknown argument: ${a}`); process.exit(64); }
}

const PG_DEVICES_SQL =
  `select hardware_id, created_at, activated_at, (serial_number is not null) as has_serial
     from public.devices where created_at < '${CUTOFF}'`;
const PG_ALIASES_SQL = `select old_id, current_id from public.device_id_aliases`;

if (opt.printSql) {
  console.log(PG_DEVICES_SQL + ';\n' + PG_ALIASES_SQL + ';');
  process.exit(0);
}

// The guard comes first: nothing is read or written if --apply is refused.
if (opt.apply && Date.now() < CUTOFF_MS && !opt.forceEarly) {
  console.error(`REFUSED: --apply before the cutoff (${CUTOFF}). Cars activated later today would be left out.`);
  console.error('Re-run after the cutoff, or pass --force-early if you really mean it.');
  process.exit(3);
}

// ------------------------------------------------------------------ helpers
function toMs(v) {
  if (v == null || v === '') return NaN;
  // Postgres text form "2026-09-04 22:49:41.545546+00" → ISO.
  let s = String(v).trim().replace(' ', 'T');
  if (/[+-]\d{2}$/.test(s)) s += ':00';
  return Date.parse(s);
}
function iso(v) {
  const ms = toMs(v);
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null;
}
const q = (s) => (s == null ? 'NULL' : `'${String(s).replace(/'/g, "''")}'`);

function wrangler(args) {
  // shell:true so `npx` resolves on Windows; every argument we pass is our own constant text.
  const cmd = ['npx', '--yes', 'wrangler', ...args].map((a) => (/[\s"*<>|&()]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a)).join(' ');
  const r = spawnSync(cmd, { cwd: workerDir, shell: true, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) {
    throw new Error(`wrangler ${args[0]} ${args[1]} failed (exit ${r.status}): ${(r.stderr || r.stdout || '').slice(-2000)}`);
  }
  return r.stdout;
}

function d1Select(sql) {
  const out = wrangler(['d1', 'execute', D1_NAME, '--remote', '--json', '--command', sql]);
  const start = out.indexOf('[');
  const parsed = JSON.parse(out.slice(start));
  if (!parsed[0] || parsed[0].success !== true) throw new Error('D1 query did not succeed');
  return parsed[0].results;
}

async function pgQuery(sql) {
  const token = process.env.SUPABASE_ACCESS_TOKEN;
  const res = await fetch(`https://api.supabase.com/v1/projects/${PG_PROJECT_REF}/database/query`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ query: sql, read_only: true }),
  });
  if (!res.ok) throw new Error(`Supabase Management API HTTP ${res.status}`);
  return await res.json();
}

async function loadPg() {
  let devices, aliases, how;
  if (opt.pgJson) {
    const j = JSON.parse(readFileSync(opt.pgJson, 'utf8'));
    devices = j.devices; aliases = j.aliases; how = `file ${opt.pgJson}`;
  } else if (process.env.SUPABASE_ACCESS_TOKEN) {
    devices = await pgQuery(PG_DEVICES_SQL);
    aliases = await pgQuery(PG_ALIASES_SQL);
    how = 'Supabase Management API';
  } else {
    console.error('No Postgres source: pass --pg-json <file> (see --print-pg-sql) or set SUPABASE_ACCESS_TOKEN.');
    process.exit(64);
  }
  if (!Array.isArray(devices) || !Array.isArray(aliases)) throw new Error('pg source must have devices[] and aliases[]');
  const norm = (r) => Array.isArray(r)
    ? { hardware_id: r[0], created_at: r[1], activated_at: r[2], has_serial: !!r[3] }
    : { hardware_id: r.hardware_id, created_at: r.created_at, activated_at: r.activated_at,
        has_serial: r.has_serial != null ? !!r.has_serial : r.serial_number != null };
  const normA = (r) => (Array.isArray(r) ? { old_id: r[0], current_id: r[1] } : r);
  return { devices: devices.map(norm), aliases: aliases.map(normA), how };
}

// ------------------------------------------------------------------ build the set
const pg = await loadPg();
const d1Rows = d1Select('SELECT hardware_id, activated_at, is_active, is_blocked FROM devices WHERE activated_at IS NOT NULL');

const set = new Map(); // hardware_id → { granted_at, source }
const stats = {
  'pg.devices': 0, 'pg.alias': 0, 'd1.devices': 0,
  pg_rows_read: pg.devices.length, pg_skipped_after_cutoff: 0, pg_skipped_never_activated: 0,
  alias_rows_read: pg.aliases.length, alias_skipped_current_not_in_set: 0, alias_already_in_set: 0,
  d1_rows_read: d1Rows.length, d1_skipped_after_cutoff: 0, d1_already_in_set: 0,
};

for (const r of pg.devices) {
  const id = (r.hardware_id || '').trim();
  if (!id) continue;
  const created = toMs(r.created_at);
  if (!(created < CUTOFF_MS)) { stats.pg_skipped_after_cutoff++; continue; }
  if (r.activated_at == null && !r.has_serial) { stats.pg_skipped_never_activated++; continue; }
  if (!set.has(id)) { set.set(id, { granted_at: iso(r.created_at), source: 'pg.devices' }); stats['pg.devices']++; }
}
const pgIds = new Set(set.keys());
for (const a of pg.aliases) {
  const oldId = (a.old_id || '').trim();
  const cur = (a.current_id || '').trim();
  if (!oldId) continue;
  if (!pgIds.has(cur)) { stats.alias_skipped_current_not_in_set++; continue; }
  if (set.has(oldId)) { stats.alias_already_in_set++; continue; }
  set.set(oldId, { granted_at: set.get(cur).granted_at, source: 'pg.alias' });
  stats['pg.alias']++;
}
for (const r of d1Rows) {
  const id = (r.hardware_id || '').trim();
  if (!id) continue;
  const act = toMs(r.activated_at);
  if (!(act < CUTOFF_MS)) { stats.d1_skipped_after_cutoff++; continue; }
  if (set.has(id)) { stats.d1_already_in_set++; continue; }
  set.set(id, { granted_at: iso(r.activated_at), source: 'd1.devices' });
  stats['d1.devices']++;
}

// ------------------------------------------------------------------ assertions
const expected = [...MUST_INCLUDE, ...opt.expect.map((id) => [id, '--expect'])];
let missing = 0;
console.log('Bench / test ids:');
for (const [id, label] of expected) {
  const hit = set.get(id);
  if (!hit) missing++;
  console.log(`  ${hit ? 'OK     ' : 'MISSING'} ${id}  (${label})${hit ? `  via ${hit.source}` : ''}`);
}

// ------------------------------------------------------------------ SQL
const now = new Date().toISOString();
const rows = [...set.entries()].sort((a, b) => a[0].localeCompare(b[0]));
const lines = [
  `-- controller_entitlements grandfather snapshot — generated ${now}`,
  `-- cutoff ${CUTOFF}; pg via ${pg.how}; ${rows.length} rows`,
  `-- INSERT OR IGNORE: never touches an existing row (revoked / code / manual rows keep their state).`,
];
for (let i = 0; i < rows.length; i += opt.batch) {
  const chunk = rows.slice(i, i + opt.batch).map(([id, v]) =>
    `(${q(id)}, 'grandfather', ${q(v.granted_at || now)}, ${q(GRANTED_BY)}, ${q(v.source)})`);
  lines.push(
    'INSERT OR IGNORE INTO controller_entitlements (hardware_id, kind, granted_at, granted_by, snapshot_source) VALUES\n  ' +
    chunk.join(',\n  ') + ';');
}
const outFile = resolve(opt.out || join(here, 'out', `controller-grandfather-${now.replace(/[:.]/g, '-')}.sql`));
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, lines.join('\n') + '\n', 'utf8');

console.log('\nCounts:');
for (const [k, v] of Object.entries(stats)) console.log(`  ${k.padEnd(34)} ${v}`);
console.log(`  ${'TOTAL grandfathered'.padEnd(34)} ${rows.length}`);
console.log(`\nSQL written: ${outFile}  (${Math.ceil(rows.length / opt.batch)} INSERT statements)`);

if (missing) {
  console.error(`\nFAIL: ${missing} required bench/test id(s) missing from the set — not applying.`);
  process.exit(2);
}

if (!opt.apply) {
  console.log('\nDry run: nothing written to D1.' + (Date.now() < CUTOFF_MS ? ` The real run must happen after ${CUTOFF}.` : ''));
  process.exit(0);
}

// ------------------------------------------------------------------ apply
const tables = d1Select("SELECT name FROM sqlite_master WHERE type='table' AND name='controller_entitlements'");
if (!tables.length) {
  console.error('controller_entitlements does not exist in D1 — apply migrations/0008_controller_licence.sql first.');
  process.exit(4);
}
const before = d1Select("SELECT COUNT(*) AS n FROM controller_entitlements WHERE kind='grandfather'")[0].n;
wrangler(['d1', 'execute', D1_NAME, '--remote', '--yes', '--file', outFile]);
const after = d1Select("SELECT COUNT(*) AS n FROM controller_entitlements WHERE kind='grandfather'")[0].n;
console.log(`\nApplied. grandfather rows in D1: ${before} → ${after}`);
