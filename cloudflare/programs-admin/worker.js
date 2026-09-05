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
import ICON180 from "./icons/icon-180.png";
import ICON192 from "./icons/icon-192.png";
import ICON512 from "./icons/icon-512.png";

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
// Content Security Policy: the page's one inline script gets a per-response nonce; everything else
// is pinned to the few hosts the panel actually uses. SweetAlert injects inline styles, hence
// 'unsafe-inline' for styles only (never scripts).
function csp(nonce) {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com`,
    "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com https://cdnjs.cloudflare.com",
    "img-src 'self' data: blob: https://*.r2.dev",
    "media-src 'self' blob: https://*.r2.dev",
    "connect-src 'self' https://ihgmqwzdpugdzddobhbc.supabase.co wss://ihgmqwzdpugdzddobhbc.supabase.co https://ts-wallpapers-upload.tsdash-qatar.workers.dev https://*.r2.dev",
    "frame-ancestors 'none'", "base-uri 'self'", "form-action 'self'", "object-src 'none'", "upgrade-insecure-requests",
  ].join("; ");
}
function nonce() { const b = new Uint8Array(16); crypto.getRandomValues(b); return btoa(String.fromCharCode(...b)); }

// Per-IP rate limit for the proxies (per isolate; a coarse brake, not the only one).
const hits = new Map();
function limited(ip) {
  const now = Date.now(); const slot = hits.get(ip);
  if (!slot || now - slot.t > 60_000) { hits.set(ip, { t: now, n: 1 }); if (hits.size > 5000) hits.clear(); return false; }
  slot.n += 1; return slot.n > 240;
}

// ---------- Cloudflare Access (Zero Trust) in front of everything ----------
// When ACCESS_TEAM_DOMAIN and ACCESS_AUD are set, every request must carry a valid Access JWT
// (Cf-Access-Jwt-Assertion, RS256, signed by the team's public keys, aud = this application).
// This closes the direct-to-workers.dev bypass: the Worker itself refuses anyone Access did not let
// through. Until the two vars exist (owner enables Zero Trust), the check is skipped and the
// Supabase admin session remains the only gate.
const jwks = { at: 0, keys: [] };
const b64u = s => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=")), c => c.charCodeAt(0));
async function accessKeys(team) {
  if (Date.now() - jwks.at < 3600_000 && jwks.keys.length) return jwks.keys;
  const res = await fetch(`https://${team}/cdn-cgi/access/certs`, { cf: { cacheTtl: 3600 } });
  if (!res.ok) return jwks.keys;
  const data = await res.json();
  jwks.keys = (data.keys || []).filter(k => k.kty === "RSA"); jwks.at = Date.now();
  return jwks.keys;
}
async function accessOk(req, env) {
  if (!env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) return true;        // not enabled yet
  const jwt = req.headers.get("Cf-Access-Jwt-Assertion") || "";
  const parts = jwt.split(".");
  if (parts.length !== 3) return false;
  let header, payload;
  try { header = JSON.parse(new TextDecoder().decode(b64u(parts[0]))); payload = JSON.parse(new TextDecoder().decode(b64u(parts[1]))); } catch (e) { return false; }
  if (header.alg !== "RS256") return false;
  const now = Math.floor(Date.now() / 1000);
  const aud = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!aud.includes(env.ACCESS_AUD) || !payload.exp || payload.exp < now || payload.iss !== `https://${env.ACCESS_TEAM_DOMAIN}`) return false;
  const keys = await accessKeys(env.ACCESS_TEAM_DOMAIN);
  const jwk = keys.find(k => k.kid === header.kid); if (!jwk) return false;
  try {
    const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64u(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  } catch (e) { return false; }
}

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
    // Home-screen icon + manifest: public, cacheable, and outside Access (installers fetch them cookie-less).
    const icons = { "/icon-180.png": ICON180, "/icon-192.png": ICON192, "/icon-512.png": ICON512, "/apple-touch-icon.png": ICON180, "/favicon.ico": ICON192 };
    if (req.method === "GET" && icons[p]) return new Response(icons[p], { status: 200, headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=86400" } });
    if (req.method === "GET" && p === "/manifest.webmanifest") {
      return new Response(JSON.stringify({ name: "Thabthaba Programs Admin", short_name: "Thabthaba", start_url: "/gen", display: "standalone", background_color: "#211a12", theme_color: "#211a12", dir: "rtl", lang: "ar",
        icons: [{ src: "/icon-192.png", sizes: "192x192", type: "image/png" }, { src: "/icon-512.png", sizes: "512x512", type: "image/png" }] }),
        { status: 200, headers: { "Content-Type": "application/manifest+json", "Cache-Control": "public, max-age=3600" } });
    }
    if (!(await accessOk(req, env))) return new Response("Access required", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });

    if (req.method === "GET" && (p === "/" || p === "/index.html" || p === "/control-panel.html" || p === "/gen" || p === "/gen/")) {
      const n = nonce();
      const html = PAGE.replace(/<script>/g, `<script nonce="${n}">`);
      return new Response(html, { status: 200, headers: { "Content-Type": "text/html; charset=utf-8", "Content-Security-Policy": csp(n), ...SEC_HEADERS } });
    }
    if (p.startsWith("/local/") && limited(req.headers.get("cf-connecting-ip") || "?")) return json(429, { message: "slow down" });
    if (req.method === "GET" && p === "/local/ping") {
      return json(200, { local: true, remote: true, store: !!env.STORE_ADMIN_SECRET, tslink: !!env.TSLINK_ADMIN_TOKEN, leo: !!env.LEO_ADMIN_TOKEN, controller: !!env.CONTROLLER_ADMIN_SECRET, codes: !!env.DB });
    }
    if (!p.startsWith("/local/")) return json(404, { message: "not found" });

    // Public JSON mirrors (CORS relief only; the data is public anyway).
    if (req.method === "GET" && p === "/local/catalog") return passthrough(await fetch(`${CATALOG_URL}?cb=${Date.now()}`, { cache: "no-store" }));
    if (req.method === "GET" && p === "/local/leo-latest") return passthrough(await fetch(`${LEO_LATEST_URL}?cb=${Date.now()}`, { cache: "no-store" }));

    // Everything below injects a secret: admin session required, verified server-side.
    if (!(await isAdmin(req, env))) return json(401, { message: "admin session required" });

    try {
      // ---- activation code generator (D1 issued_codes) ----
      if (env.DB && req.method === "POST" && p === "/local/codes/issue") {
        const now = new Date(); const expires = new Date(now.getTime() + 10 * 60_000);
        const iso = d => d.toISOString();
        let serial = null;
        for (let i = 0; i < 12 && !serial; i++) {
          const r = new Uint32Array(1); crypto.getRandomValues(r);
          const cand = "578" + String(r[0] % 1000000).padStart(6, "0");
          if (cand.startsWith("578300")) continue;                       // the sold closed block
          const taken = await env.DB.prepare("SELECT 1 AS x FROM devices WHERE serial_number = ? UNION ALL SELECT 1 FROM issued_codes WHERE serial = ?").bind(cand, cand).first();
          if (!taken) serial = cand;
        }
        if (!serial) return json(503, { message: "could not mint a unique code" });
        await env.DB.prepare("INSERT INTO issued_codes (serial, issued_at, expires_at, issued_by, note) VALUES (?, ?, ?, ?, ?)")
          .bind(serial, iso(now), iso(expires), "programs-admin", "generator").run();
        return json(200, { serial, issued_at: iso(now), expires_at: iso(expires) });
      }
      if (env.DB && req.method === "GET" && p === "/local/codes/status") {
        const serial = (url.searchParams.get("serial") || "").trim();
        if (!/^578\d{6}$/.test(serial)) return json(400, { message: "bad serial" });
        const row = await env.DB.prepare("SELECT serial, issued_at, expires_at, used_by, used_at FROM issued_codes WHERE serial = ?").bind(serial).first();
        if (!row) return json(404, { message: "unknown code" });
        return json(200, row);
      }
      if (env.DB && req.method === "GET" && p === "/local/codes/recent") {
        const rows = await env.DB.prepare("SELECT serial, issued_at, expires_at, used_by, used_at FROM issued_codes ORDER BY issued_at DESC LIMIT 30").all();
        return json(200, { codes: rows.results || [] });
      }
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
