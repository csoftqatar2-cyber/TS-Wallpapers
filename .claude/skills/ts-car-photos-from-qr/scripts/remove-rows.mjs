// Delete superseded PRIVATE rows of ONE car, the way the dashboard's حذف does: rows first, R2 object after.
// Usage: node remove-rows.mjs --hw <HW_ID> [--dry-run] <wallpaper-id> [<id> ...]
// Refuses any id that is global, belongs to another car, or whose URL is also referenced by another
// car's row (deleting by url would remove it there too). Run ONLY after deliver.mjs verified the new rows.
import { SB, R2_WORKER, adminToken, adminHeaders, anonKey, parseArgs, exit } from "./lib.mjs";

const a = parseArgs(process.argv.slice(2));
const ids = a._;
if (!a.hw || !ids.length) { console.error("usage: node remove-rows.mjs --hw <HW_ID> [--dry-run] <id>..."); process.exit(1); }
const tok = await adminToken();
const read = async (q) => (await fetch(`${SB}/rest/v1/${q}`, { headers: { apikey: anonKey(), Authorization: `Bearer ${tok}` } })).json();

const rows = await read(`wallpapers?select=id,url,is_global,hardware_id,channel&id=in.(${ids.map(encodeURIComponent).join(",")})`);
if (rows.length !== ids.length) { console.error("some ids not found:", ids.filter(i => !rows.some(r => String(r.id) === String(i)))); await exit(2); }
for (const r of rows) {
  if (r.is_global || r.hardware_id !== a.hw) { console.error("REFUSED (not this car's private row):", JSON.stringify(r)); await exit(2); }
  const same = await read(`wallpapers?select=id,hardware_id&url=eq.${encodeURIComponent(r.url)}`);
  if (same.some(s => s.hardware_id !== a.hw)) { console.error("REFUSED (url shared with another row):", r.url, JSON.stringify(same)); await exit(2); }
}
console.log(JSON.stringify(rows, null, 1));
if (a["dry-run"]) await exit(0);

for (const r of rows) {
  const d = await fetch(`${SB}/rest/v1/wallpapers?id=eq.${r.id}&hardware_id=eq.${encodeURIComponent(a.hw)}`, { method: "DELETE", headers: adminHeaders(tok, { Prefer: "return=representation" }) });
  const dj = await d.json().catch(() => []);
  // 200 with [] means RLS silently deleted nothing (usually: x-write-key missing/wrong).
  if (!d.ok || !Array.isArray(dj) || dj.length === 0) { console.error("row delete FAILED", r.id, d.status, JSON.stringify(dj)); await exit(2); }
  const leaf = r.url.split("?")[0].split("/").pop();
  const s = await fetch(R2_WORKER, { method: "DELETE", headers: adminHeaders(tok, { "x-file-name": encodeURIComponent(leaf) }) });
  console.log(JSON.stringify({ id: r.id, rowDeleted: true, r2Delete: s.status }));  // R2 failure = warning only
}
