#!/usr/bin/env node
/**
 * TS G700 -> ذبذبة Dashboard controller licence (owner's request 2026-10-28).
 *
 * Every car activated in the TS G700 app gets the dashboard with no code. The G700 app and the
 * dashboard name a car the same way (CarIdentity: "VIN-" + the 17-char VIN, legacy chain otherwise),
 * and the store already holds tokens under the same VIN-LVM... ids, so the G700 hwId IS the
 * dashboard's hardware_id. No mapping table is needed.
 *
 * What it writes: one controller_entitlements row per new car, kind='grandfather' (the Ti7 pattern,
 * 2026-10-20 — a plain row the thab-voice Worker already honours, no Worker change), granted_by and
 * snapshot_source = SOURCE, plus one controller_audit row. INSERT OR IGNORE: an existing row (code,
 * grandfather, revoked) is never touched, so a revoked car stays revoked.
 *
 * Read-only on the G700 side: one GET /cars on tsdash-checkin with the admin token from
 * ~/.ts-secrets/jetour-g700/admin-token.txt (never printed). Nothing is written there.
 *
 * Eligible = G700 car with a code, not blocked (car or code) in G700, AND an active, unblocked
 * family row in D1 `devices`. A car with no family row is REPORTED, not granted, by default: making
 * it a family member is a different decision (it also opens the store/wallpapers), and this tool
 * never writes `devices`.
 *
 * --include-no-family (2026-10-28, licence decoupled from the family — feat/licence-decouple-1028):
 * the no-family cars get DASHBOARD-ONLY rows too. Since that branch the thab-voice licence check
 * reads the entitlement alone, so a row with no family row behind it opens the dashboard and
 * nothing else. One row per id the car has been known by in the G700 KV (hwId, alsoKnownAs[], and
 * ids[] if a record carries one), so whichever id the dashboard reports — VIN-, SYS-, MAC-… — finds
 * it. A car any of whose ids holds a REVOKED row is skipped whole (revoke is final across ids).
 * Needs the decoupled thab-voice deployed first: on the old Worker these rows do nothing (family_inactive).
 *
 *   node tools/migrate-g700-entitlements.mjs                      # dry run (default): counts + SQL file
 *   node tools/migrate-g700-entitlements.mjs --include-no-family  # dry run incl. dashboard-only rows
 *   node tools/migrate-g700-entitlements.mjs --apply [--include-no-family]   # backup, then insert
 *   --out-dir <dir>   where backups/SQL go (default tools/out, git-ignored)
 *
 * Re-runnable: new G700 activations after a run are picked up by the next run.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOURCE = 'tsg700-migration-2026-10-28';
const G700_CARS_URL = 'https://tsdash-checkin.tsdash-qatar.workers.dev/cars';
const D1_NAME = 'ts-activation';

const here = dirname(fileURLToPath(import.meta.url));
const workerDir = resolve(here, '..');
const argv = process.argv.slice(2);
const apply = argv.includes('--apply');
const includeNoFamily = argv.includes('--include-no-family');
const oi = argv.indexOf('--out-dir');
const outDir = resolve(oi >= 0 ? argv[oi + 1] : join(here, 'out'));
mkdirSync(outDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, '-');

const q = (s) => (s == null ? 'NULL' : `'${String(s).replace(/'/g, "''")}'`);

function wrangler(args) {
  const cmd = ['npx', '--yes', 'wrangler', ...args].map((a) => (/[\s"*<>|&()']/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a)).join(' ');
  const r = spawnSync(cmd, { cwd: workerDir, shell: true, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) throw new Error(`wrangler ${args[0]} ${args[1]} failed (exit ${r.status}): ${(r.stderr || r.stdout || '').slice(-1500)}`);
  return r.stdout;
}
function d1Select(sql) {
  const out = wrangler(['d1', 'execute', D1_NAME, '--remote', '--json', '--command', sql]);
  const parsed = JSON.parse(out.slice(out.indexOf('[')));
  if (!parsed[0] || parsed[0].success !== true) throw new Error('D1 query did not succeed');
  return parsed[0].results;
}

// ---------------------------------------------------------------- G700 side (read-only)
const token = readFileSync(join(homedir(), '.ts-secrets', 'jetour-g700', 'admin-token.txt'), 'utf8').trim();
const res = await fetch(G700_CARS_URL, { headers: { 'X-Admin-Token': token } });
if (!res.ok) throw new Error(`G700 /cars HTTP ${res.status}`);
const g700Raw = await res.text();
writeFileSync(join(outDir, `g700-cars-${stamp}.json`), g700Raw, 'utf8');
const cars = JSON.parse(g700Raw).cars || [];

const valid = (id) => typeof id === 'string' && id.trim() && id.length <= 200 && !/[\u0000-\u001f\u007f']/.test(id);
const activated = cars.filter((c) => valid(c.hwId) && String(c.code || '').trim());
const g700Blocked = activated.filter((c) => c.blocked || c.codeBlocked);
const candidates = activated.filter((c) => !(c.blocked || c.codeBlocked));

// ---------------------------------------------------------------- dashboard side
const inList = (ids) => ids.map(q).join(',');
const ids = candidates.map((c) => c.hwId.trim());
const ent = ids.length ? d1Select(`SELECT hardware_id, kind, granted_by, revoked_at FROM controller_entitlements WHERE hardware_id IN (${inList(ids)})`) : [];
const dev = ids.length ? d1Select(`SELECT hardware_id, is_active, is_blocked FROM devices WHERE hardware_id IN (${inList(ids)})`) : [];
const entBy = new Map(ent.map((r) => [r.hardware_id, r]));
const devBy = new Map(dev.map((r) => [r.hardware_id, r]));

/* Every id a G700 record has been known by, the reported one first (unique, valid only). */
const idsOfCar = (c) => {
  const out = [];
  for (const raw of [c.hwId, ...(Array.isArray(c.alsoKnownAs) ? c.alsoKnownAs : []), ...(Array.isArray(c.ids) ? c.ids : [])]) {
    if (!valid(raw)) continue;
    const id = String(raw).trim();
    if (!out.includes(id)) out.push(id);
  }
  return out;
};

const already = [], revoked = [], noFamily = [], familyBlocked = [], toInsert = [];
for (const c of candidates) {
  const id = c.hwId.trim();
  const e = entBy.get(id);
  if (e) { (e.revoked_at != null ? revoked : already).push(id); continue; }
  const d = devBy.get(id);
  if (!d || Number(d.is_active) !== 1) { noFamily.push(id); continue; }
  if (Number(d.is_blocked) === 1) { familyBlocked.push(id); continue; }
  toInsert.push(c);
}

const mask = (id) => id.length > 12 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
console.log(`G700 cars on the server:            ${cars.length}`);
console.log(`  activated (have a code):          ${activated.length}`);
console.log(`  blocked in G700 (skipped):        ${g700Blocked.length}`);
console.log(`Already entitled on the dashboard:  ${already.length}`);
console.log(`Revoked on the dashboard (kept):    ${revoked.length}`);
console.log(`No active family row (NOT granted): ${noFamily.length}  ${noFamily.map(mask).join(' ')}`);
console.log(`Family-blocked (NOT granted):       ${familyBlocked.length}  ${familyBlocked.map(mask).join(' ')}`);
console.log(`NEW entitlements to write:          ${toInsert.length}  ${toInsert.map((c) => mask(c.hwId)).join(' ')}`);

/* --include-no-family: dashboard-only rows for the no-family cars, one per alias. Their aliases are
   checked against controller_entitlements first (a revoked alias skips the car; an alias already
   entitled is left alone, INSERT OR IGNORE). */
const dashOnly = [];            // [{ car, ids: [...] }]
const dashOnlySkipped = [];     // ids of cars skipped for a revoked alias
if (includeNoFamily && noFamily.length) {
  const byId = new Map(candidates.map((c) => [c.hwId.trim(), c]));
  const cars = noFamily.map((id) => byId.get(id)).filter(Boolean);
  const all = [...new Set(cars.flatMap(idsOfCar))];
  const aliasEnt = all.length ? d1Select(`SELECT hardware_id, revoked_at FROM controller_entitlements WHERE hardware_id IN (${inList(all)})`) : [];
  const revokedIds = new Set(aliasEnt.filter((r) => r.revoked_at != null).map((r) => r.hardware_id));
  for (const c of cars) {
    const ids = idsOfCar(c);
    if (ids.some((id) => revokedIds.has(id))) { dashOnlySkipped.push(c.hwId.trim()); continue; }
    dashOnly.push({ car: c, ids });
  }
  console.log(`--include-no-family: dashboard-only cars ${dashOnly.length} (${dashOnly.reduce((n, x) => n + x.ids.length, 0)} ids)  `
    + dashOnly.map((x) => x.ids.map(mask).join('+')).join(' '));
  if (dashOnlySkipped.length) console.log(`  skipped (a revoked alias): ${dashOnlySkipped.length}  ${dashOnlySkipped.map(mask).join(' ')}`);
} else if (noFamily.length) {
  console.log('(no-family cars are not granted; --include-no-family writes dashboard-only rows for them)');
}

const now = new Date().toISOString();
const nRows = toInsert.length + dashOnly.reduce((n, x) => n + x.ids.length, 0);
const lines = [`-- ${SOURCE}: generated ${now}; ${nRows} rows; INSERT OR IGNORE never touches an existing row. Never writes devices.`];
for (const c of toInsert) {
  const id = c.hwId.trim();
  lines.push(`INSERT OR IGNORE INTO controller_entitlements (hardware_id, kind, granted_at, granted_by, snapshot_source, note) VALUES (${q(id)}, 'grandfather', ${q(now)}, ${q(SOURCE)}, ${q(SOURCE)}, ${q('TS G700 activated car')});`);
  lines.push(`INSERT INTO controller_audit (hardware_id, action, detail, by, at) SELECT ${q(id)}, 'grandfather_g700', ${q(JSON.stringify({ source: SOURCE }))}, ${q(SOURCE)}, ${q(now)} WHERE changes() = 1;`);
}
for (const { car, ids } of dashOnly) {
  for (const id of ids) {
    lines.push(`INSERT OR IGNORE INTO controller_entitlements (hardware_id, kind, granted_at, granted_by, snapshot_source, note) VALUES (${q(id)}, 'grandfather', ${q(now)}, ${q(SOURCE)}, ${q(SOURCE)}, ${q('TS G700 activated car — dashboard only (no family row)')});`);
    lines.push(`INSERT INTO controller_audit (hardware_id, action, detail, by, at) SELECT ${q(id)}, 'grandfather_g700_dashboard_only', ${q(JSON.stringify({ source: SOURCE, g700_hw_id: car.hwId.trim(), aliases: ids }))}, ${q(SOURCE)}, ${q(now)} WHERE changes() = 1;`);
  }
}
const sqlFile = join(outDir, `g700-entitlements-${stamp}.sql`);
writeFileSync(sqlFile, lines.join('\n') + '\n', 'utf8');
console.log(`SQL: ${sqlFile}`);

if (!apply) { console.log('Dry run: nothing written.'); process.exit(0); }
if (!nRows) { console.log('Nothing new to write.'); process.exit(0); }

// ---------------------------------------------------------------- backup, then write
const backup = d1Select('SELECT * FROM controller_entitlements');
const backupFile = join(outDir, `controller_entitlements-backup-${stamp}.json`);
writeFileSync(backupFile, JSON.stringify(backup, null, 1), 'utf8');
console.log(`Backup: ${backup.length} rows -> ${backupFile}`);
wrangler(['d1', 'execute', D1_NAME, '--remote', '--yes', '--file', sqlFile]);
const after = d1Select(`SELECT hardware_id FROM controller_entitlements WHERE granted_by = ${q(SOURCE)} AND revoked_at IS NULL`);
console.log(`Applied. rows with granted_by=${SOURCE}: ${after.length}`);
