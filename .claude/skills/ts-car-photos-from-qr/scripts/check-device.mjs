// Read-only: confirm the decoded hardware id is exactly one usable car and fits the target channel.
// Usage: node check-device.mjs <HW_ID> --channel app|gwm_split|jetour_g700|leopard_dash
// Exit 0 = OK, 2 = STOP (no/many rows, inactive, blocked), 3 = mode mismatch (ask the owner). Never writes anything.
import { SB, CHANNELS, adminToken, anonKey, parseArgs, exit } from "./lib.mjs";

const a = parseArgs(process.argv.slice(2));
const hw = a._[0];
const ch = a.channel;
if (!hw || !CHANNELS[ch]) { console.error("usage: node check-device.mjs <HW_ID> --channel " + Object.keys(CHANNELS).join("|")); process.exit(1); }

const tok = await adminToken();
const H = { apikey: anonKey(), Authorization: `Bearer ${tok}` };
const cols = "hardware_id,client_name,serial_number,is_active,is_blocked,mode,mode_secondary,app_version,app_version_code,last_seen_at,activated_at";
const get = async (q) => { const r = await fetch(`${SB}/rest/v1/${q}`, { headers: H }); if (!r.ok) throw new Error(q + " " + r.status + " " + await r.text()); return r.json(); };

const exact = await get(`devices?select=${cols}&hardware_id=eq.${encodeURIComponent(hw)}`);
const stop = [];
const warn = [];
const mismatch = [];
let row = exact[0];
if (exact.length !== 1) {
  // A truncated id from the visible text: show candidates, never pick one.
  const like = await get(`devices?select=${cols}&hardware_id=ilike.*${encodeURIComponent(hw.replace(/^(VIN|MAC|SYS|BOOT|SRL|AID)-/, ""))}*&limit=5`);
  stop.push(`expected exactly 1 devices row for ${hw}, got ${exact.length}` + (like.length ? `; partial matches: ${like.map(x => x.hardware_id).join(", ")}` : ""));
} else {
  if (!row.is_active) stop.push("device not activated (is_active=false) — every channel RPC returns the 'inactive' sentinel");
  if (row.is_blocked) stop.push("device is BLOCKED");
  const need = CHANNELS[ch].mode;
  if (need && row.mode !== need) mismatch.push(`last reported mode is '${row.mode}' but channel ${ch} is mirrored only in mode '${need}' (FolderMirror.active) — flag to the owner before delivering`);
  if (!row.last_seen_at) warn.push("last_seen_at is null (only report_device_mode stamps it: app open / boot)");
}
const rows = row ? await get(`wallpapers?select=id,url,type,is_global,target_mode,channel,created_at&hardware_id=eq.${encodeURIComponent(hw)}&order=created_at.desc`) : [];
console.log(JSON.stringify({ device: row || null, privateRows: rows, stop, mismatch, warn }, null, 1));
await exit(stop.length ? 2 : mismatch.length ? 3 : 0);
