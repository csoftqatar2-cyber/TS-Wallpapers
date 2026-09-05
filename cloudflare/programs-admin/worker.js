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
import GEN from "./gen.html";
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

// ---------- admin login guard (D1 login_attempts / blocked_clients / security_events) ----------
const LOGIN_FAILS = 5, LOGIN_WINDOW_MS = 15 * 60_000, BLOCK_HOURS = 24;
const clientOf = req => ({ ip: req.headers.get("cf-connecting-ip") || "?", email: req.headers.get("cf-access-authenticated-user-email") || null, ua: (req.headers.get("user-agent") || "").slice(0, 200) });
// Answers true / false, or throws when D1 cannot answer (the caller fails CLOSED with 503).
async function isBlocked(env, ip) {
  if (!env.DB) return false;
  const row = await env.DB.prepare("SELECT expires_at FROM blocked_clients WHERE ip = ?").bind(ip).first();
  if (!row) return false;
  if (String(row.expires_at) > new Date().toISOString()) return true;
  await env.DB.prepare("DELETE FROM blocked_clients WHERE ip = ?").bind(ip).run();
  return false;
}
// ---------- device-bound sessions: refresh token stays in D1, the browser gets an HttpOnly id ----------
const DEV_COOKIE = "ts_dev", DEV_MAX_AGE = 30 * 24 * 3600;
function cookieOf(req, name) { const m = new RegExp(`(?:^|;\\s*)${name}=([A-Za-z0-9_-]{10,128})`).exec(req.headers.get("Cookie") || ""); return m ? m[1] : null; }
function randomId() { const b = new Uint8Array(24); crypto.getRandomValues(b); return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
function setCookie(id, clear) { return `${DEV_COOKIE}=${clear ? "" : id}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${clear ? 0 : DEV_MAX_AGE}`; }
async function supabaseRefresh(env, refreshToken) {
  const res = await fetch(`${env.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, { method: "POST", headers: { apikey: env.SUPABASE_ANON, "Content-Type": "application/json" }, body: JSON.stringify({ refresh_token: refreshToken }) });
  if (!res.ok) return null;
  const data = await res.json().catch(() => null);
  return data && data.access_token ? data : null;
}
async function handleSession(req, env) {
  const id = cookieOf(req, DEV_COOKIE); if (!id || !env.DB) return json(401, { message: "no device session" });
  const row = await env.DB.prepare("SELECT refresh_token, revoked FROM device_sessions WHERE id = ?").bind(id).first();
  if (!row || row.revoked) return new Response(JSON.stringify({ message: "session revoked" }), { status: 401, headers: { "Content-Type": "application/json; charset=utf-8", "Set-Cookie": setCookie(id, true), ...SEC_HEADERS } });
  const data = await supabaseRefresh(env, row.refresh_token);
  if (!data) { await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE id = ?").bind(id).run(); return json(401, { message: "refresh failed" }); }
  if (data.user && data.user.id !== env.ADMIN_UID) return json(403, { message: "not the admin" });
  const c = clientOf(req);
  await env.DB.prepare("UPDATE device_sessions SET refresh_token = ?, last_seen = ?, ip = ?, ua = ? WHERE id = ?").bind(data.refresh_token || row.refresh_token, new Date().toISOString(), c.ip, c.ua, id).run();
  return json(200, { access_token: data.access_token, expires_in: data.expires_in || 3600, token_type: "bearer" });
}
async function handleLogout(req, env) {
  const id = cookieOf(req, DEV_COOKIE);
  if (id && env.DB) await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE id = ?").bind(id).run();
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "Content-Type": "application/json; charset=utf-8", "Set-Cookie": setCookie(id || "x", true), ...SEC_HEADERS } });
}
async function securityEvent(env, kind, c, detail) {
  try { await env.DB.prepare("INSERT INTO security_events (at, kind, ip, email, detail) VALUES (?, ?, ?, ?, ?)").bind(new Date().toISOString(), kind, c.ip, c.email, detail || null).run(); } catch (e) {}
}
async function handleLogin(req, env) {
  const c = clientOf(req);
  if (!env.DB) return json(503, { message: "login guard not configured" });
  let body; try { body = await readJsonBody(req); } catch (e) { return json(400, { message: "bad body" }); }
  const password = typeof body.password === "string" ? body.password : "";
  if (!password || password.length > 256) return json(400, { message: "bad body" });
  const since = new Date(Date.now() - LOGIN_WINDOW_MS).toISOString();
  const now = new Date().toISOString();
  // Reserve the attempt FIRST (counted as a failure until proven otherwise), so parallel guesses
  // cannot all read the same count and slip past the limit together.
  const ins = await env.DB.prepare("INSERT INTO login_attempts (at, ip, email, ua, ok) VALUES (?, ?, ?, ?, 0)").bind(now, c.ip, c.email, c.ua).run();
  const attemptId = ins && ins.meta ? ins.meta.last_row_id : null;
  const fails = await env.DB.prepare("SELECT COUNT(*) AS n FROM login_attempts WHERE ip = ? AND ok = 0 AND at > ?").bind(c.ip, since).first();
  const n = (fails && fails.n) || 1;
  if (n > LOGIN_FAILS) return json(403, { message: "blocked" });
  const res = await fetch(`${env.SUPABASE_URL}/auth/v1/token?grant_type=password`, {
    method: "POST", headers: { apikey: env.SUPABASE_ANON, "Content-Type": "application/json" },
    body: JSON.stringify({ email: env.ADMIN_EMAIL || "admin@tswallpapers.app", password }),
  });
  if (res.ok) {
    const data = await res.json();
    if (data.user && data.user.id !== env.ADMIN_UID) return json(403, { message: "not the admin" });
    if (attemptId != null) await env.DB.prepare("UPDATE login_attempts SET ok = 1 WHERE id = ?").bind(attemptId).run();
    await securityEvent(env, "login_ok", c, null);
    // Device-bound session: the refresh token stays here; the browser only gets an HttpOnly id.
    const id = randomId();
    await env.DB.prepare("INSERT INTO device_sessions (id, refresh_token, created_at, last_seen, ip, ua, email, label) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .bind(id, data.refresh_token, now, now, c.ip, c.ua, c.email, (c.ua.match(/iPhone|iPad|Android|Windows|Macintosh|Linux/) || ["جهاز"])[0]).run();
    return new Response(JSON.stringify({ access_token: data.access_token, expires_in: data.expires_in || 3600, token_type: "bearer", user: { id: data.user && data.user.id } }),
      { status: 200, headers: { "Content-Type": "application/json; charset=utf-8", "Set-Cookie": setCookie(id, false), ...SEC_HEADERS } });
  }
  await securityEvent(env, "login_failed", c, `محاولة ${n} من ${LOGIN_FAILS}`);
  if (n >= LOGIN_FAILS) {
    const exp = new Date(Date.now() + BLOCK_HOURS * 3600_000).toISOString();
    await env.DB.prepare("INSERT OR REPLACE INTO blocked_clients (ip, email, at, expires_at, reason) VALUES (?, ?, ?, ?, ?)").bind(c.ip, c.email, now, exp, "5 wrong passwords in 15 min").run();
    await securityEvent(env, "client_blocked", c, `حُظر 24 ساعة`);
    return json(403, { message: "blocked" });
  }
  return json(401, { message: "wrong password", attempts_left: LOGIN_FAILS - n });
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
    // A client that brute-forced the password is refused everything, page included, for 24 h.
    // If the block list cannot be read the request fails CLOSED.
    let blocked = false;
    try { blocked = await isBlocked(env, clientOf(req).ip); } catch (e) { return json(503, { message: "guard unavailable" }); }
    if (blocked) return new Response("blocked", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });
    if (p.startsWith("/local/") && limited(clientOf(req).ip)) return json(429, { message: "slow down" });
    if (req.method === "POST" && p === "/local/auth/login") return handleLogin(req, env);
    if (req.method === "POST" && p === "/local/auth/session") return handleSession(req, env);
    if (req.method === "POST" && p === "/local/auth/logout") return handleLogout(req, env);

    if (req.method === "GET" && (p === "/" || p === "/index.html" || p === "/control-panel.html" || p === "/gen" || p === "/gen/")) {
      const n = nonce();
      // /gen is the stand-alone generator page (the car-side shortcut); everything else is the full panel.
      const html = (p.startsWith("/gen") ? GEN : PAGE).replace(/<script>/g, `<script nonce="${n}">`);
      return new Response(html, { status: 200, headers: { "Content-Type": "text/html; charset=utf-8", "Content-Security-Policy": csp(n), ...SEC_HEADERS } });
    }
    if (req.method === "GET" && p === "/local/ping") {
      return json(200, { local: true, remote: true, store: !!env.STORE_ADMIN_SECRET, tslink: !!env.TSLINK_ADMIN_TOKEN, leo: !!env.LEO_ADMIN_TOKEN, controller: !!env.CONTROLLER_ADMIN_SECRET, codes: !!env.DB, guard: !!env.DB, sessions: !!env.DB });
    }
    if (!p.startsWith("/local/")) return json(404, { message: "not found" });

    // Public JSON mirrors (CORS relief only; the data is public anyway).
    if (req.method === "GET" && p === "/local/catalog") return passthrough(await fetch(`${CATALOG_URL}?cb=${Date.now()}`, { cache: "no-store" }));
    if (req.method === "GET" && p === "/local/leo-latest") return passthrough(await fetch(`${LEO_LATEST_URL}?cb=${Date.now()}`, { cache: "no-store" }));

    // Everything below injects a secret: admin session required, verified server-side.
    if (!(await isAdmin(req, env))) return json(401, { message: "admin session required" });

    try {
      // ---- security: events for the panel's notifications, blocked list, unblock ----
      if (env.DB && req.method === "GET" && p === "/local/security/events") {
        const since = url.searchParams.get("since") || new Date(Date.now() - 86400_000).toISOString();
        const rows = await env.DB.prepare("SELECT id, at, kind, ip, email, detail FROM security_events WHERE at > ? ORDER BY at DESC LIMIT 100").bind(since).all();
        return json(200, { events: rows.results || [] });
      }
      if (env.DB && req.method === "GET" && p === "/local/security/blocked") {
        const rows = await env.DB.prepare("SELECT ip, email, at, expires_at, reason FROM blocked_clients ORDER BY at DESC LIMIT 100").all();
        return json(200, { blocked: rows.results || [] });
      }
      if (env.DB && req.method === "POST" && p === "/local/security/unblock") {
        const body = await readJsonBody(req); const ip = String(body.ip || "").slice(0, 64);
        if (!ip) return json(400, { message: "ip required" });
        await env.DB.prepare("DELETE FROM blocked_clients WHERE ip = ?").bind(ip).run();
        await env.DB.prepare("DELETE FROM login_attempts WHERE ip = ? AND ok = 0").bind(ip).run();
        await securityEvent(env, "client_unblocked", clientOf(req), ip);
        return json(200, { ok: true });
      }
      if (env.DB && req.method === "GET" && p === "/local/auth/devices") {
        const rows = await env.DB.prepare("SELECT id, created_at, last_seen, ip, ua, email, label, revoked FROM device_sessions ORDER BY last_seen DESC LIMIT 50").all();
        const me = cookieOf(req, DEV_COOKIE);
        return json(200, { devices: (rows.results || []).map(r => ({ ...r, id: r.id.slice(0, 8), current: r.id === me, full: undefined })) , me: me ? me.slice(0, 8) : null });
      }
      if (env.DB && req.method === "POST" && p === "/local/auth/revoke") {
        const body = await readJsonBody(req); const prefix = String(body.id || "").slice(0, 8);
        if (!/^[A-Za-z0-9_-]{8}$/.test(prefix)) return json(400, { message: "bad id" });
        await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE substr(id, 1, 8) = ?").bind(prefix).run();
        await securityEvent(env, "device_revoked", clientOf(req), prefix);
        return json(200, { ok: true });
      }
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
