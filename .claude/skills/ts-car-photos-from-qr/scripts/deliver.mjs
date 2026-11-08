// Upload files to R2 (wallpaper-upload Worker) and insert PRIVATE wallpapers rows for one car, then verify.
// Usage: node deliver.mjs --hw <HW_ID> --channel app|gwm_split|jetour_g700|leopard_dash
//                         [--target-mode <mode>] [--out <dir>] [--dry-run] <file> [<file> ...]
// Per file: random UUID name -> POST Worker (admin session + x-write-key) -> INSERT row
// (admin session + x-write-key; without the key RLS rejects it and the R2 file is orphaned) ->
// GET the public URL (HTTP 200 + same byte count). Finally the car's anon channel RPC must list
// every new URL. Writes <out>/delivered-<ts>.json. Exit 2 on any failed check.
import fs from "fs";
import path from "path";
import crypto from "crypto";
import { SB, R2_WORKER, CHANNELS, adminToken, adminHeaders, anonRpc, imageSize, parseArgs, mimeOf, exit } from "./lib.mjs";

const a = parseArgs(process.argv.slice(2));
const files = a._;
const ch = a.channel;
if (!a.hw || !CHANNELS[ch] || !files.length) { console.error("usage: node deliver.mjs --hw <HW_ID> --channel " + Object.keys(CHANNELS).join("|") + " [--target-mode m] [--dry-run] <file>..."); process.exit(1); }
const plan = files.map(f => { const b = fs.readFileSync(f); const s = imageSize(b); return { file: f, bytes: b.length, size: s ? `${s.w}x${s.h}` : "?" }; });
console.log(JSON.stringify({ hw: a.hw, channel: ch, rpc: CHANNELS[ch].rpc, target_mode: a["target-mode"] || null, files: plan }, null, 1));
if (a["dry-run"]) await exit(0);

const tok = await adminToken();
const out = [];
for (const f of files) {
  const buf = fs.readFileSync(f);
  const ext = (path.extname(f) || ".jpg").toLowerCase();
  const name = crypto.randomUUID() + ext;             // new URL every time: the car caches by URL
  const up = await fetch(R2_WORKER, { method: "POST", headers: adminHeaders(tok, { "Content-Type": mimeOf(f), "x-file-name": encodeURIComponent(name) }), body: buf });
  const uj = await up.json().catch(() => ({}));
  if (!up.ok || !uj.url) { console.error("upload FAILED", f, up.status, JSON.stringify(uj)); await exit(2); }
  const row = { url: uj.url, type: /^video\//.test(mimeOf(f)) ? "video" : "image", is_global: false, hardware_id: a.hw, channel: ch };
  if (a["target-mode"]) row.target_mode = a["target-mode"];
  const ins = await fetch(`${SB}/rest/v1/wallpapers`, { method: "POST", headers: adminHeaders(tok, { "Content-Type": "application/json", Prefer: "return=representation" }), body: JSON.stringify(row) });
  const ij = await ins.json().catch(() => null);
  if (!ins.ok || !Array.isArray(ij) || !ij[0]) { console.error("insert FAILED", f, ins.status, JSON.stringify(ij), "ORPHAN R2 object:", uj.url); await exit(2); }
  const g = await fetch(uj.url);
  const got = Buffer.from(await g.arrayBuffer()).length;
  const e = { file: f, id: ij[0].id, url: uj.url, bytes: buf.length, http: g.status, served: got, match: g.status === 200 && got === buf.length };
  out.push(e);
  console.log(JSON.stringify(e));
}
const rpc = await anonRpc(CHANNELS[ch].rpc, { device_hw_id: a.hw, legacy_hw_id: null });
for (const e of out) e.inRpc = rpc.text.includes(e.url);
const ok = rpc.status === 200 && out.every(e => e.match && e.inRpc);
const dir = a.out || ".";
const file = path.join(dir, `delivered-${Date.now()}.json`);
fs.writeFileSync(file, JSON.stringify({ hw: a.hw, channel: ch, rpc: CHANNELS[ch].rpc, rpcStatus: rpc.status, rows: out }, null, 1));
console.log(`rpc ${CHANNELS[ch].rpc} ${rpc.status}; ${out.filter(e => e.inRpc).length}/${out.length} new URLs listed; saved ${file}`);
await exit(ok ? 0 : 2);
