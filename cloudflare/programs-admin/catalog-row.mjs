/**
 * catalog-row — bump ONE app row inside the store catalog (catalog/apps.json) by text surgery.
 *
 * The THABTHABA STORE reads catalog/apps.json from the `thabthaba` R2 bucket and decides
 * "update available" by comparing each row's versionCode with the installed one. The file is
 * pretty-printed JSON (2-space indent, `"key": value`) and every other tool that edits it
 * (sync-store.mjs, remove-catalog-row.mjs in the Cars-installer repo) works on the raw bytes
 * rather than re-serialising, so a diff stays readable and nothing outside the touched row can
 * change by accident. This module does the same for the three fields a release moves:
 * `versionCode`, `versionName`, `sizeBytes`. It never creates a row and never touches any other
 * key — which apps are on the store is the owner's decision, made elsewhere.
 *
 * Pure functions, no I/O: imported by worker.js (Cloudflare, bundled by wrangler) and by
 * tools/catalog-publish-dryrun.mjs (Node) alike.
 */

const FIELDS = ["versionCode", "versionName", "sizeBytes"];

export class CatalogRowError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

/** Locate the object that carries `"packageName": "<pkg>"` and return its [start, end) span. */
export function findRow(text, packageName) {
  const anchor = `"packageName": ${JSON.stringify(packageName)}`;
  const first = text.indexOf(anchor);
  if (first < 0) return null;
  if (text.indexOf(anchor, first + anchor.length) >= 0) throw new CatalogRowError("ambiguous_row", `${packageName} appears more than once`);
  const start = text.lastIndexOf("{", first);
  if (start < 0) throw new CatalogRowError("bad_row", "no opening brace before packageName");
  // Walk braces (string-aware) from the row's own `{` to its matching `}`.
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (inStr) { if (esc) esc = false; else if (c === "\\") esc = true; else if (c === "\"") inStr = false; continue; }
    if (c === "\"") inStr = true;
    else if (c === "{") depth++;
    else if (c === "}") { depth--; if (depth === 0) return { start, end: i + 1 }; }
  }
  throw new CatalogRowError("bad_row", "no closing brace for the row");
}

/**
 * Spans of the row's OWN `"key": value` pairs (depth 1 inside the row). A row may carry a
 * `carBuilds` object whose per-car entries repeat versionCode/versionName/sizeBytes — those sit
 * at depth 2+ and are deliberately skipped.
 */
function topLevelFields(rowText) {
  const found = {};
  let depth = 0, inStr = false, esc = false;
  for (let i = 0; i < rowText.length; i++) {
    const c = rowText[i];
    if (inStr) { if (esc) esc = false; else if (c === "\\") esc = true; else if (c === "\"") inStr = false; continue; }
    if (c === "{" || c === "[") { depth++; continue; }
    if (c === "}" || c === "]") { depth--; continue; }
    if (c !== "\"" || depth !== 1) { if (c === "\"") inStr = true; continue; }
    // A string opening at depth 1: either a key or a top-level string value. Try to read `"key": value`.
    const m = /^"(versionCode|versionName|sizeBytes)":\s*("(?:[^"\\]|\\.)*"|-?\d+)/.exec(rowText.slice(i, i + 200));
    if (m) {
      if (found[m[1]]) throw new CatalogRowError("bad_row", `duplicate ${m[1]} in the row`);
      const valueStart = i + m[0].length - m[2].length;
      found[m[1]] = { start: valueStart, end: i + m[0].length, raw: m[2] };
      i += m[0].length - 1;
      continue;
    }
    inStr = true;                                           // some other string; skip it as usual
  }
  return found;
}

/** Current `{ versionCode, versionName, sizeBytes }` of the row, or null when the app has no row. */
export function readCatalogRow(text, packageName) {
  const span = findRow(text, packageName);
  if (!span) return null;
  const f = topLevelFields(text.slice(span.start, span.end));
  for (const k of FIELDS) if (!f[k]) throw new CatalogRowError("bad_row", `row has no ${k}`);
  return { versionCode: Number(f.versionCode.raw), versionName: JSON.parse(f.versionName.raw), sizeBytes: Number(f.sizeBytes.raw) };
}

/** The file with the row's three values replaced by placeholders — the "everything else" both sides must agree on. */
function normalise(text, packageName) {
  const span = findRow(text, packageName);
  if (!span) throw new CatalogRowError("no_row", `${packageName} has no row`);
  const row = text.slice(span.start, span.end);
  const f = topLevelFields(row);
  const cuts = FIELDS.map(k => f[k]).filter(Boolean).sort((a, b) => a.start - b.start);
  let out = "", at = 0;
  for (const c of cuts) { out += row.slice(at, c.start) + "_"; at = c.end; }
  return text.slice(0, span.start) + out + row.slice(at) + text.slice(span.end);
}

/**
 * Rewrite versionCode / versionName / sizeBytes of one row. Returns { text, previous }.
 * Throws CatalogRowError: no_row | ambiguous_row | bad_row | verify_failed. Never writes.
 */
export function bumpCatalogRow(text, packageName, { versionCode, versionName, sizeBytes }) {
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) throw new CatalogRowError("bad_input", "versionCode must be a positive integer");
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) throw new CatalogRowError("bad_input", "sizeBytes must be a positive integer");
  if (typeof versionName !== "string" || !versionName) throw new CatalogRowError("bad_input", "versionName must be a non-empty string");
  const span = findRow(text, packageName);
  if (!span) throw new CatalogRowError("no_row", `${packageName} has no row in the catalog`);
  const row = text.slice(span.start, span.end);
  const f = topLevelFields(row);
  for (const k of FIELDS) if (!f[k]) throw new CatalogRowError("bad_row", `row has no ${k}`);
  const previous = { versionCode: Number(f.versionCode.raw), versionName: JSON.parse(f.versionName.raw), sizeBytes: Number(f.sizeBytes.raw) };
  const value = { versionCode: String(versionCode), versionName: JSON.stringify(versionName), sizeBytes: String(sizeBytes) };
  const cuts = FIELDS.map(k => ({ ...f[k], key: k })).sort((a, b) => a.start - b.start);
  let newRow = "", at = 0;
  for (const c of cuts) { newRow += row.slice(at, c.start) + value[c.key]; at = c.end; }
  newRow += row.slice(at);
  const out = text.slice(0, span.start) + newRow + text.slice(span.end);

  // Assertion 1: byte-for-byte, the two files differ in nothing but those three values.
  if (normalise(out, packageName) !== normalise(text, packageName)) throw new CatalogRowError("verify_failed", "edit touched bytes outside the three fields");
  // Assertion 2: still valid JSON, and the parsed row differs only in those keys.
  let before, after;
  try { before = JSON.parse(text); after = JSON.parse(out); } catch (e) { throw new CatalogRowError("verify_failed", "edit produced invalid JSON: " + e.message); }
  const rows = Array.isArray(after.apps) ? after.apps.filter(a => a.packageName === packageName) : [];
  if (rows.length !== 1) throw new CatalogRowError("verify_failed", "row count changed");
  const got = rows[0];
  if (got.versionCode !== versionCode || got.versionName !== versionName || got.sizeBytes !== sizeBytes) throw new CatalogRowError("verify_failed", "row does not carry the new values");
  const strip = a => JSON.stringify({ ...a, versionCode: 0, versionName: "", sizeBytes: 0 });
  const was = before.apps.find(a => a.packageName === packageName);
  if (strip(was) !== strip(got)) throw new CatalogRowError("verify_failed", "row changed outside the three fields");
  return { text: out, previous };
}
