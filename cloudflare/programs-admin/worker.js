/**
 * Thabthaba Programs Admin — the unified fleet control panel, reachable from a phone.
 *
 * One Worker: serves the page (control-panel.html, bundled as text) and the same read-only
 * proxies the local runner (serve.mjs) offers on the laptop — with one difference that is the
 * whole point of this file: on the internet there is no loopback to trust, so EVERY proxy call
 * must carry the Supabase admin session (Authorization: Bearer <access_token>) and the Worker
 * verifies it server-side against Supabase Auth before it injects any secret. A caller that is
 * not the admin uid gets 401 and nothing else — no secret ever leaves this Worker.
 *
 * Secrets (wrangler secret put): STORE_ADMIN_SECRET, TSLINK_ADMIN_TOKEN, LEO_ADMIN_TOKEN,
 * CONTROLLER_ADMIN_SECRET. Vars (wrangler.toml): SUPABASE_URL, SUPABASE_ANON, ADMIN_UID,
 * CTRL_TELEMETRY_URL, CTRL_TELEMETRY_ANON.
 */
import PAGE from "./control-panel.html";

const RPC_ALLOW = /^store_admin_[a-z0-9_]{1,40}$/u;
const TSLINK_ADMIN_BASE = "https://tslink-bot.tsdash-qatar.workers.dev/admin/api";
const TSLINK_GET_ALLOW = /^\/(overview|cars|versions|cars\/[A-Za-z0-9_.:@+-]{1,120})$/u;
const LEO_ADMIN_BASE = "https://tsleo-checkin.tsdash-qatar.workers.dev";
const LEO_GET_ALLOW = /^\/(crashes|cars)$/u;
const CTRL_RPC_ALLOW = /^thab_admin_(stats|cars|events|gaps|fuel_price_history|voice_overlay_history)$/u;
const CATALOG_URL = "https://pub-3d6cc5a5671c4be3829a384a375f7b11.r2.dev/catalog/apps.json";
const LEO_LATEST_URL = "https://pub-fbb386b3923a44879e64296817936d84.r2.dev/latest.json";
const MAX_BODY = 64 * 1024;

const SEC_HEADERS = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
};
const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...SEC_HEADERS } });

// Verified admin tokens are remembered for a minute per isolate so a page full of calls does
// not hit Supabase Auth once per call. The cache key is a hash, never the token itself.
const verified = new Map();
async function sha256(s) { const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)); return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join(""); }
async function isAdmin(req, env) {
  const auth = req.headers.get("Authorization") || "";
  const m = /^Bearer\s+([A-Za-z0-9._~+/=-]{20,4096})$/u.exec(auth);
  if (!m) return false;
  const token = m[1];
  const key = await sha256(token);
  const hit = verified.get(key);
  if (hit && hit > Date.now()) return true;
  const res = await fetch(`${env.SUPABASE_URL}/auth/v1/user`, { headers: { apikey: env.SUPABASE_ANON, Authorization: `Bearer ${token}` } });
  if (!res.ok) return false;
  const user = await res.json().catch(() => null);
  const ok = !!user && user.id === env.ADMIN_UID;
  if (ok) verified.set(key, Date.now() + 60_000);
  return ok;
}

async function readJsonBody(req) {
  const len = Number(req.headers.get("content-length") || 0);
  if (len > MAX_BODY) throw new Error("body too large");
  const raw = await req.text();
  if (raw.length > MAX_BODY) throw new Error("body too large");
  const body = raw ? JSON.parse(raw) : {};
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("bad body");
  return body;
}
async function passthrough(upstream) {
  const text = await upstream.text();
  return new Response(text, { status: upstream.status, headers: { "Content-Type": upstream.headers.get("content-type") || "application/json; charset=utf-8", ...SEC_HEADERS } });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;

    if (req.method === "GET" && (p === "/" || p === "/index.html" || p === "/control-panel.html")) {
      return new Response(PAGE, { status: 200, headers: { "Content-Type": "text/html; charset=utf-8", ...SEC_HEADERS } });
    }
    if (req.method === "GET" && p === "/local/ping") {
      return json(200, { local: true, remote: true, store: !!env.STORE_ADMIN_SECRET, tslink: !!env.TSLINK_ADMIN_TOKEN, leo: !!env.LEO_ADMIN_TOKEN, controller: !!env.CONTROLLER_ADMIN_SECRET });
    }
    if (!p.startsWith("/local/")) return json(404, { message: "not found" });

    // Public JSON mirrors (CORS relief only; the data is public anyway).
    if (req.method === "GET" && p === "/local/catalog") return passthrough(await fetch(`${CATALOG_URL}?cb=${Date.now()}`, { cache: "no-store" }));
    if (req.method === "GET" && p === "/local/leo-latest") return passthrough(await fetch(`${LEO_LATEST_URL}?cb=${Date.now()}`, { cache: "no-store" }));

    // Everything below injects a secret: admin session required, verified server-side.
    if (!(await isAdmin(req, env))) return json(401, { message: "admin session required" });

    try {
      if (req.method === "POST" && p.startsWith("/local/store/")) {
        const name = p.slice("/local/store/".length);
        if (!RPC_ALLOW.test(name)) return json(404, { message: "rpc not allowed" });
        if (!env.STORE_ADMIN_SECRET) return json(503, { message: "store secret not configured" });
        const body = await readJsonBody(req); delete body.p_secret;
        return passthrough(await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/${name}`, {
          method: "POST", headers: { "Content-Type": "application/json", apikey: env.SUPABASE_ANON, Authorization: `Bearer ${env.SUPABASE_ANON}` },
          body: JSON.stringify({ p_secret: env.STORE_ADMIN_SECRET, ...body }),
        }));
      }
      if (req.method === "POST" && p.startsWith("/local/controller/")) {
        const name = p.slice("/local/controller/".length);
        if (!CTRL_RPC_ALLOW.test(name)) return json(404, { message: "rpc not allowed" });
        if (!env.CONTROLLER_ADMIN_SECRET) return json(503, { message: "controller secret not configured" });
        const body = await readJsonBody(req); delete body.p_secret;
        return passthrough(await fetch(`${env.CTRL_TELEMETRY_URL}/rest/v1/rpc/${name}`, {
          method: "POST", headers: { "Content-Type": "application/json", apikey: env.CTRL_TELEMETRY_ANON, Authorization: `Bearer ${env.CTRL_TELEMETRY_ANON}` },
          body: JSON.stringify({ p_secret: env.CONTROLLER_ADMIN_SECRET, ...body }),
        }));
      }
      if (req.method === "GET" && p.startsWith("/local/tslink")) {
        const sub = p.slice("/local/tslink".length) || "/overview";
        if (!TSLINK_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.TSLINK_ADMIN_TOKEN) return json(503, { message: "ts-link token not configured" });
        return passthrough(await fetch(`${TSLINK_ADMIN_BASE}${sub}${url.search}`, { headers: { Authorization: `Bearer ${env.TSLINK_ADMIN_TOKEN}` }, cache: "no-store" }));
      }
      if (req.method === "GET" && p.startsWith("/local/leo")) {
        const sub = p.slice("/local/leo".length);
        if (!LEO_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.LEO_ADMIN_TOKEN) return json(503, { message: "leo token not configured" });
        return passthrough(await fetch(`${LEO_ADMIN_BASE}${sub}${url.search}`, { headers: { "X-Admin-Token": env.LEO_ADMIN_TOKEN }, cache: "no-store" }));
      }
      return json(404, { message: "not found" });
    } catch (e) {
      return json(400, { message: e && e.message ? e.message : "bad request" });
    }
  },
};
