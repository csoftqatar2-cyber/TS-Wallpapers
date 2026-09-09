/*
 * catalog-publish-dryrun — exercise the row surgery behind POST /catalog/publish on a copy of the
 * LIVE store catalog, without writing anything anywhere.
 *
 * Downloads catalog/apps.json from the public bucket, applies a fake bump to the ذبذبة خلفيات row
 * (store.thabthaba.clock -> 194 / "7.18" / 5591000 by default), prints a unified diff of the
 * changed lines and ends with "OK: only 3 lines changed" — or the reason it would have refused.
 *
 * Usage:
 *   node tools/catalog-publish-dryrun.mjs [packageName] [versionCode] [versionName] [sizeBytes]
 */

import { bumpCatalogRow, readCatalogRow, CatalogRowError } from "../catalog-row.mjs";

const CATALOG_URL = "https://pub-3d6cc5a5671c4be3829a384a375f7b11.r2.dev/catalog/apps.json";
const [pkg = "store.thabthaba.clock", vcArg = "194", versionName = "7.18", szArg = "5591000"] = process.argv.slice(2);
const versionCode = Number(vcArg), sizeBytes = Number(szArg);

const res = await fetch(`${CATALOG_URL}?cb=${Date.now()}`, { cache: "no-store" });
if (!res.ok) { console.error(`ERROR: catalog fetch HTTP ${res.status}`); process.exit(1); }
const text = await res.text();
console.log(`live catalog: ${Buffer.byteLength(text, "utf8")} bytes, ${JSON.parse(text).apps.length} apps`);

const current = readCatalogRow(text, pkg);
if (!current) { console.error(`FAIL: no_row — ${pkg} is not in the catalog (the Worker answers 404 and never creates rows)`); process.exit(1); }
console.log(`current row: ${pkg} versionCode=${current.versionCode} versionName=${current.versionName} sizeBytes=${current.sizeBytes}`);
if (!(versionCode > current.versionCode)) { console.error(`FAIL: not_newer — ${versionCode} is not > ${current.versionCode} (the Worker answers 409)`); process.exit(1); }

let out;
try {
  out = bumpCatalogRow(text, pkg, { versionCode, versionName, sizeBytes });
} catch (e) {
  console.error(`FAIL: ${e instanceof CatalogRowError ? e.code : "error"} — ${e.message}`);
  process.exit(1);
}

// Unified diff of the changed lines. The surgery replaces values in place, so the line count
// must not move; a different count is itself a failure worth seeing.
const a = text.split("\n"), b = out.text.split("\n");
console.log(`--- a/catalog/apps.json (live)\n+++ b/catalog/apps.json (after bump)`);
let changed = 0;
if (a.length !== b.length) {
  console.error(`FAIL: line count changed ${a.length} -> ${b.length}`);
  process.exit(1);
}
for (let i = 0; i < a.length; i++) {
  if (a[i] === b[i]) continue;
  changed++;
  console.log(`@@ -${i + 1} +${i + 1} @@`);
  console.log(`-${a[i]}`);
  console.log(`+${b[i]}`);
}
const sameBytes = Buffer.byteLength(text, "utf8") - Buffer.byteLength(out.text, "utf8");
console.log(`previous: ${JSON.stringify(out.previous)}  size delta: ${-sameBytes} bytes`);
if (changed === 3) console.log("OK: only 3 lines changed");
else { console.error(`FAIL: ${changed} lines changed, expected 3`); process.exit(1); }
