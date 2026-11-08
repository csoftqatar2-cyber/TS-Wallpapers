// Shared helpers for the ts-car-photos-from-qr scripts.
// Secrets are read at runtime from where they already live — nothing secret is stored here.
//   admin password   ~/.ts-secrets/ts-wallpapers/admin-password.txt
//   panel write key  ~/.ts-secrets/ts-wallpapers/panel-write-key.txt   (RLS panel_write_ok() + R2 Worker)
//   DPQ gateway      C:/Work/Github/DPQ-V3/.env.local  DPQ_GATEWAY_SECRET=
// The Supabase anon key is public by design (AGENTS.md §5); it is read from wallpapers_manager.html
// so there is one copy of it in the repo.
import fs from "fs";
import os from "os";
import path from "path";
import { fileURLToPath } from "url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO = path.resolve(HERE, "../../../..");
export const SB = "https://ihgmqwzdpugdzddobhbc.supabase.co";
export const R2_WORKER = "https://ts-wallpapers-upload.tsdash-qatar.workers.dev";
export const DPQ_BASE = "https://dpq-v3.tsdash-qatar.workers.dev/dpq";

// channel -> { rpc the car reads, devices.mode it needs, DPQ template (null = as-is / ask) }
export const CHANNELS = {
  app:         { rpc: "get_wallpapers",         mode: null,      folder: null },
  gwm_split:   { rpc: "get_gwm_wallpapers",     mode: "gwm",     folder: "/sdcard/Pictures/GWMSplit_Styles" },
  jetour_g700: { rpc: "get_jetour_wallpapers",  mode: "jetour",  folder: "/sdcard/Pictures/G700" },
  leopard_dash:{ rpc: "get_leopard_wallpapers", mode: "leopard", folder: "/sdcard/Pictures/TS Leo Dashboard" },
};

const secretFile = (rel) => fs.readFileSync(path.join(os.homedir(), ".ts-secrets", rel), "utf8").trim();

export function anonKey() {
  const html = fs.readFileSync(path.join(REPO, "wallpapers_manager.html"), "utf8");
  const m = html.match(/const ANON_KEY = "([^"]+)"/);
  if (!m) throw new Error("ANON_KEY not found in wallpapers_manager.html");
  return m[1];
}

export function writeKey() { return secretFile("ts-wallpapers/panel-write-key.txt"); }

export function dpqSecret() {
  const env = fs.readFileSync("C:/Work/Github/DPQ-V3/.env.local", "utf8");
  const m = env.match(/^DPQ_GATEWAY_SECRET=(.*)$/m);
  if (!m) throw new Error("DPQ_GATEWAY_SECRET missing from DPQ-V3/.env.local");
  return m[1].trim().replace(/^"|"$/g, "");
}

export async function adminToken() {
  const ANON = anonKey();
  const r = await fetch(`${SB}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { apikey: ANON, "Content-Type": "application/json" },
    body: JSON.stringify({ email: "admin@tswallpapers.app", password: secretFile("ts-wallpapers/admin-password.txt") }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error(`admin login failed ${r.status}`);
  return j.access_token;
}

// Admin REST headers. x-write-key is REQUIRED for every write (RLS: auth.uid()=admin AND panel_write_ok()).
export function adminHeaders(tok, extra = {}) {
  return { apikey: anonKey(), Authorization: `Bearer ${tok}`, "x-write-key": writeKey(), ...extra };
}

export async function anonRpc(name, body) {
  const ANON = anonKey();
  const r = await fetch(`${SB}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: { apikey: ANON, Authorization: `Bearer ${ANON}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: r.status, text: await r.text() };
}

// JPEG/PNG pixel size without an image library.
export function imageSize(buf) {
  if (buf[0] === 0x89 && buf[1] === 0x50) return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20), kind: "png" };
  if (buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2;
    while (i < buf.length) {
      if (buf[i] !== 0xff) { i++; continue; }
      const m = buf[i + 1];
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc)
        return { h: buf.readUInt16BE(i + 5), w: buf.readUInt16BE(i + 7), kind: "jpeg" };
      i += 2 + buf.readUInt16BE(i + 2);
    }
  }
  return null;
}

const BOOL_FLAGS = new Set(["dry-run"]);
export function parseArgs(argv) {
  const o = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const k = a.slice(2);
      const v = !BOOL_FLAGS.has(k) && argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : true;
      o[k] = v;
    } else o._.push(a);
  }
  return o;
}

export const mimeOf = (f) => (/\.png$/i.test(f) ? "image/png" : /\.webp$/i.test(f) ? "image/webp" : /\.(mp4|m4v)$/i.test(f) ? "video/mp4" : "image/jpeg");

// process.exit() right after fetch() trips a libuv assertion on Windows (exit code 127 instead
// of ours). Let open handles settle first.
export async function exit(code) { await new Promise(r => setTimeout(r, 150)); process.exit(code); }
