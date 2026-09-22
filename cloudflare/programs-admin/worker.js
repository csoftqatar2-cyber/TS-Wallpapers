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
 * G700_ADMIN_TOKEN, LYNK_ADMIN_TOKEN, CONTROLLER_ADMIN_SECRET, CATALOG_PUBLISH_SECRET, VAPID_PRIVATE_JWK (Web Push, push.mjs; cron every minute). Vars (wrangler.toml): SUPABASE_URL,
 * SUPABASE_ANON, ADMIN_UID, CTRL_TELEMETRY_URL, CTRL_TELEMETRY_ANON, CTRL_SOURCE, TELEMETRY_BRIDGE, CTRL_MIRROR_CONFIG
 * (controller telemetry on Cloudflare, telemetry-admin.mjs).
 *
 * One machine-to-machine route lives outside the Access/admin-session gate: POST /catalog/publish
 * (bearer CATALOG_PUBLISH_SECRET). The release CI of ذبذبة خلفيات / TS Link calls it after its own
 * publish so the THABTHABA STORE catalog shows the new build as an update — see handleCatalogPublish.
 */
import PAGE from "./control-panel.html";
import GEN from "./gen.html";
import ICON180 from "./icons/icon-180.png";
import ICON192 from "./icons/icon-192.png";
import ICON512 from "./icons/icon-512.png";
import { bumpCatalogRow, readCatalogRow, CatalogRowError } from "./catalog-row.mjs";
import { SW_SOURCE, handlePushRoute, pushReady, runPushCron } from "./push.mjs";
import { voiceRouterRoute } from "./voice-router-route.mjs";
import { handleDiagRoute } from "./diag-admin.mjs";
import { validateStoreCatalogText } from "./store-catalog.mjs";
import { normalizePhone, phoneCountry } from "./phone.mjs";
import { controllerRoute, telemetryRoute, runTelemetryBridge, ctrlOnCloudflare } from "./telemetry-admin.mjs";

const RPC_ALLOW = /^store_admin_[a-z0-9_]{1,40}$/u;
const TSLINK_ADMIN_BASE = "https://tslink-bot.tsdash-qatar.workers.dev/admin/api";
const TSLINK_GET_ALLOW = /^\/(overview|cars|versions|crashes|crashes\/recent|cars\/[A-Za-z0-9_.:@+-]{1,120})$/u;   // crashes: relay D1 groups (+ recent rows once the relay ships them)
const LEO_ADMIN_BASE = "https://tsleo-checkin.tsdash-qatar.workers.dev";
const LEO_GET_ALLOW = /^\/(crashes|cars)$/u;
// TS G700 (com.tsdash.jetourg700) and TS Lynk & Co (com.carfs.fullscreen) each keep their OWN
// activation system on their own Worker — nothing of theirs lives in the shared Supabase. The panel
// reads them the same way it reads TS Link: GET only, allowlisted paths, the admin token injected
// here and never handed to the page. No write route of either Worker is reachable from here on
// purpose (block / reissue / mergecars / releasecode / adoptcodes / resetusage stay on their own
// local admin pages): this tab is a window, not a second hand on the wheel.
const G700_ADMIN_BASE = "https://tsdash-checkin.tsdash-qatar.workers.dev";
const G700_GET_ALLOW = /^\/(cars|crashes|codes|unissued|attempts|logs|blockedcodes)$/u;
const G700_LATEST_URL = "https://pub-b7e6e084a54e46acb74f3dfe7c6533b1.r2.dev/latest.json";
const LYNK_ADMIN_BASE = "https://ts-lynk-report.tsdash-qatar.workers.dev";
const LYNK_GET_ALLOW = /^\/devices$/u;
// The Lynk bucket is public-read (the app itself fetches both files with no key), so these two are
// mirrored for CORS relief only — the same reason /local/catalog and /local/leo-latest exist.
const LYNK_PUBLIC_BASE = "https://pub-1c493a648f424eba933a07d3b2371d56.r2.dev";
// Controller telemetry RPCs the site may call (reads + the owner's voice/fuel settings writes + crash
// reports): the allow-list, the Cloudflare/Supabase switch and the bridge for not-yet-updated cars
// live in telemetry-admin.mjs since the 2026-09-21 move to Cloudflare.
const CATALOG_URL = "https://pub-3d6cc5a5671c4be3829a384a375f7b11.r2.dev/catalog/apps.json";
const LEO_LATEST_URL = "https://pub-fbb386b3923a44879e64296817936d84.r2.dev/latest.json";
const MAX_BODY = 64 * 1024;
async function sweepUnboundPhones(env, nowIso) {
  try {
    await env.DB.prepare(`INSERT INTO unbound_phones (phone, serial, issued_at, expired_at, issued_by, note)
      SELECT ic.customer_phone, ic.serial, ic.issued_at, ic.expires_at, ic.issued_by,
             'انتهت صلاحية الكود قبل استخدامه'
        FROM issued_codes ic
       WHERE ic.used_by IS NULL
         AND ic.expires_at < ?1
         AND ic.customer_phone IS NOT NULL
         AND ic.customer_phone <> ''
         AND NOT EXISTS (SELECT 1 FROM unbound_phones up WHERE up.serial = ic.serial)`)
      .bind(nowIso).run();
  } catch (e) {
    // Rollout safety: minting must keep working until the new table exists.
  }
}

// ---------- store-catalog mirror (POST /catalog/publish) ----------
// Owner's order 2026-09-09: when ذبذبة خلفيات or TS Link publishes on its own channel, the
// THABTHABA STORE catalog must show the new build as an update by itself. The apps that may be
// mirrored are fixed here; the Worker only BUMPS a row that already exists in catalog/apps.json
// (never creates one — which apps are on the store stays the owner's decision).
// Owner's list (2026-09-09): the three apps that publish on their own channel AND are sold through the
// store. Closed by name on purpose — `com.thabthaba.controller` and `com.codex.clusterlauncher` must
// never appear in the catalog at all (own channels only), so they are not merely absent here, they
// are forbidden: adding them is a product decision, not a config change.
const CATALOG_PUBLISH_PACKAGES = ["store.thabthaba.clock", "com.thabthaba.tslink", "com.tsdash.jetourg700"];
const CATALOG_PUBLISH_FORBIDDEN = ["com.thabthaba.controller", "com.codex.clusterlauncher"];
// Where a mirrored APK may be fetched from (https only). Add TS Link's own channel host here when
// its CI starts calling this route; the store bucket's public host is listed so a manual re-mirror
// of an APK already on the store works too.
const CATALOG_PUBLISH_APK_HOSTS = [
  "pub-3108628f0bc04bb4a97214eb7732e284.r2.dev",   // ts-wallpapers channel (release.yml R2_PUBLIC_BASE)
  "pub-3d6cc5a5671c4be3829a384a375f7b11.r2.dev",   // thabthaba store bucket itself
  "pub-b7e6e084a54e46acb74f3dfe7c6533b1.r2.dev",   // TS Dash Jetour G700 channel (TS Dash/Jetour G700/publish.ps1)
];
const CATALOG_KEY = "catalog/apps.json";
const CATALOG_APK_PREFIX = "apks/";                 // stable key the store installs from: apks/<packageName>.apk
const CATALOG_MAX_APK = 100 * 1024 * 1024;         // both apps are 5–10 MB; anything near this is not one of them
const STORE_CATALOG_MAX_APK = 95 * 1024 * 1024;
const STORE_CATALOG_MAX_ICON = 2 * 1024 * 1024;
const STORE_CATALOG_MAX_JSON = 5 * 1024 * 1024;
const STORE_CATALOG_PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/u;
const VERSION_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/u;

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
    `script-src 'self' 'nonce-${nonce}'`,          // vendor JS is served from /vendor (Static Assets), no CDN
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self'",
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
async function accessKeys(team, force) {
  if (!force && Date.now() - jwks.at < 3600_000 && jwks.keys.length) return jwks.keys;
  if (force && Date.now() - jwks.at < 60_000) return jwks.keys;   // rollover refetch, at most once a minute
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
  // Identity, not just signature: a user token must carry one of the owner's emails; a service
  // token (curl tests) carries common_name instead of email and is accepted as Access let it in.
  const allowed = String(env.ACCESS_ALLOWED_EMAILS || "").toLowerCase().split(",").map(x => x.trim()).filter(Boolean);
  if (allowed.length) {
    const email = String(payload.email || "").toLowerCase();
    if (email ? !allowed.includes(email) : !payload.common_name) return false;
  }
  let keys = await accessKeys(env.ACCESS_TEAM_DOMAIN);
  let jwk = keys.find(k => k.kid === header.kid);
  if (!jwk) { keys = await accessKeys(env.ACCESS_TEAM_DOMAIN, true); jwk = keys.find(k => k.kid === header.kid); }
  if (!jwk) return false;
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
// { data } on success, { dead: true } when Supabase rejects the token itself (400/401/403 = invalid,
// already used, or revoked), { data: null } on any upstream/network trouble — the caller must NOT
// revoke the device session for the last case, or one Supabase hiccup logs the owner out.
async function supabaseRefresh(env, refreshToken) {
  let res;
  try {
    res = await fetch(`${env.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, { method: "POST", headers: { apikey: env.SUPABASE_ANON, "Content-Type": "application/json" }, body: JSON.stringify({ refresh_token: refreshToken }) });
  } catch (e) { return { data: null }; }
  if ([400, 401, 403].includes(res.status)) return { dead: true };
  if (!res.ok) return { data: null };
  const data = await res.json().catch(() => null);
  return { data: data && data.access_token ? data : null };
}
async function handleSession(req, env) {
  const id = cookieOf(req, DEV_COOKIE); if (!id || !env.DB) return json(401, { message: "no device session" });
  const row = await env.DB.prepare("SELECT refresh_token, revoked FROM device_sessions WHERE id = ?").bind(id).first();
  if (!row || row.revoked) return new Response(JSON.stringify({ message: "session revoked" }), { status: 401, headers: { "Content-Type": "application/json; charset=utf-8", "Set-Cookie": setCookie(id, true), ...SEC_HEADERS } });
  const ref = await supabaseRefresh(env, row.refresh_token);
  if (ref.dead) { await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE id = ?").bind(id).run(); return new Response(JSON.stringify({ message: "session expired" }), { status: 401, headers: { "Content-Type": "application/json; charset=utf-8", "Set-Cookie": setCookie(id, true), ...SEC_HEADERS } }); }
  const data = ref.data;
  if (!data) return json(503, { message: "auth upstream unavailable, try again" });
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
  await env.DB.prepare("DELETE FROM login_attempts WHERE at < ?").bind(new Date(Date.now() - 7 * 24 * 3600_000).toISOString()).run().catch(() => {});
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
    // Device-bound session: the refresh token stays here; the browser only gets an HttpOnly id.
    const id = randomId();
    const family = (c.ua.match(/iPhone|iPad|Android|Windows|Macintosh|Linux/) || ["جهاز"])[0];
    // login_ok = a NEW device session (session refreshes never log one). The detail carries the device
    // family (push label) and the session prefix, so the panel does not notify a device of its own login.
    await securityEvent(env, "login_ok", c, `${family} · sid:${id.slice(0, 8)}`);
    await env.DB.prepare("INSERT INTO device_sessions (id, refresh_token, created_at, last_seen, ip, ua, email, label) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .bind(id, data.refresh_token, now, now, c.ip, c.ua, c.email, family).run();
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

// ---------- protected store catalog editor (/local/store-catalog/*) ----------
// These helpers deliberately use the R2 binding only. No R2 credential, signature or bucket secret
// crosses the admin-session boundary into the browser.
function r2Etag(obj) { return String((obj && obj.etag) || "").replace(/^\"|\"$/g, ""); }
async function rawBody(req, max, tooLargeMessage) {
  const declared = Number(req.headers.get("content-length") || 0);
  if (Number.isFinite(declared) && declared > max) throw new RangeError(tooLargeMessage);
  const bytes = await req.arrayBuffer();
  if (bytes.byteLength > max) throw new RangeError(tooLargeMessage);
  return bytes;
}
async function handleStoreCatalogRoute(req, env, url, p) {
  if (!env.CATALOG_R2) return json(503, { message: "إدارة الكتالوج غير مهيأة على هذا الخادم" });
  const base = "/local/store-catalog/";
  const op = p.slice(base.length);
  if (op === "catalog" && req.method === "GET") {
    const obj = await env.CATALOG_R2.get(CATALOG_KEY);
    if (!obj) return json(404, { message: "ملف الكتالوج غير موجود" });
    return json(200, { etag: r2Etag(obj), text: await obj.text() });
  }
  if (op === "catalog" && req.method === "PUT") {
    let body;
    try {
      const bytes = await rawBody(req, STORE_CATALOG_MAX_JSON, "ملف الكتالوج أكبر من الحد المسموح");
      body = JSON.parse(new TextDecoder().decode(bytes));
    } catch (e) { return json(e instanceof RangeError ? 413 : 400, { message: e.message || "طلب غير صالح" }); }
    if (!body || typeof body !== "object" || Array.isArray(body) || typeof body.text !== "string" || typeof body.etag !== "string") return json(400, { message: "يلزم إرسال text وetag" });
    const current = await env.CATALOG_R2.get(CATALOG_KEY);
    if (!current) return json(404, { message: "ملف الكتالوج غير موجود" });
    const currentEtag = r2Etag(current), sentEtag = body.etag.replace(/^\"|\"$/g, "");
    if (!sentEtag || sentEtag !== currentEtag) return json(409, { message: "تغيّر الكتالوج منذ تحميله. أعد تحميله قبل النشر.", etag: currentEtag });
    const currentText = await current.text();
    try { validateStoreCatalogText(body.text, currentText); }
    catch (e) { return json(422, { message: e.message }); }
    const stamp = new Date().toISOString();
    const backupKey = `catalog/backups/apps-${stamp}.json`;
    await env.CATALOG_R2.put(backupKey, currentText, { httpMetadata: { contentType: "application/json", cacheControl: "no-cache, max-age=0" } });
    const written = await env.CATALOG_R2.put(CATALOG_KEY, body.text, {
      onlyIf: { etagMatches: currentEtag },
      httpMetadata: { contentType: "application/json", cacheControl: "no-cache, max-age=0" },
    });
    if (!written) return json(409, { message: "تغيّر الكتالوج أثناء النشر. لم يُستبدل الملف." });
    return json(200, { ok: true, etag: r2Etag(written), backupKey });
  }
  if (op === "apk" && req.method === "PUT") {
    const pkg = String(url.searchParams.get("pkg") || "");
    if (!STORE_CATALOG_PACKAGE_RE.test(pkg)) return json(400, { message: "اسم الحزمة غير صالح" });
    let bytes;
    try { bytes = await rawBody(req, STORE_CATALOG_MAX_APK, "الملف أكبر من 95 ميجابايت. استخدم أداة مزامنة Cars installer للملفات الضخمة."); }
    catch (e) { return json(413, { message: e.message }); }
    const head = new Uint8Array(bytes, 0, Math.min(4, bytes.byteLength));
    if (head.length < 4 || head[0] !== 0x50 || head[1] !== 0x4b || head[2] !== 0x03 || head[3] !== 0x04) return json(422, { message: "الملف المرفوع ليس APK صالحًا" });
    const sha256hex = hex(await crypto.subtle.digest("SHA-256", bytes));
    const key = `${CATALOG_APK_PREFIX}${pkg}.apk`;
    const out = await env.CATALOG_R2.put(key, bytes, {
      httpMetadata: { contentType: "application/vnd.android.package-archive", cacheControl: "no-cache, max-age=0" },
      customMetadata: { sha256: sha256hex }, sha256: sha256hex,
    });
    return json(200, { ok: true, key, sizeBytes: bytes.byteLength, sha256: sha256hex, etag: r2Etag(out) });
  }
  if (op === "icon" && req.method === "PUT") {
    const pkg = String(url.searchParams.get("pkg") || "");
    if (!STORE_CATALOG_PACKAGE_RE.test(pkg)) return json(400, { message: "اسم الحزمة غير صالح" });
    let bytes;
    try { bytes = await rawBody(req, STORE_CATALOG_MAX_ICON, "الأيقونة أكبر من 2 ميجابايت" ); }
    catch (e) { return json(413, { message: e.message }); }
    const u = new Uint8Array(bytes);
    const png = u.length >= 8 && u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47;
    const webp = u.length >= 12 && String.fromCharCode(...u.slice(0, 4)) === "RIFF" && String.fromCharCode(...u.slice(8, 12)) === "WEBP";
    if (!png && !webp) return json(422, { message: "الأيقونة يجب أن تكون PNG أو WebP" });
    const key = `icons/${pkg}.png`;
    const out = await env.CATALOG_R2.put(key, bytes, { httpMetadata: { contentType: png ? "image/png" : "image/webp", cacheControl: "no-cache, max-age=0" } });
    return json(200, { ok: true, key, sizeBytes: bytes.byteLength, etag: r2Etag(out) });
  }
  if (op === "store-apk" && req.method === "PUT") {
    let bytes;
    try { bytes = await rawBody(req, STORE_CATALOG_MAX_APK, "الملف أكبر من 95 ميجابايت. استخدم أداة مزامنة Cars installer للملفات الضخمة."); }
    catch (e) { return json(413, { message: e.message }); }
    const head = new Uint8Array(bytes, 0, Math.min(4, bytes.byteLength));
    if (head.length < 4 || head[0] !== 0x50 || head[1] !== 0x4b || head[2] !== 0x03 || head[3] !== 0x04) return json(422, { message: "الملف المرفوع ليس APK صالحًا" });
    const sha256hex = hex(await crypto.subtle.digest("SHA-256", bytes));
    const key = "store/thabthaba-store.apk";
    const out = await env.CATALOG_R2.put(key, bytes, {
      httpMetadata: { contentType: "application/vnd.android.package-archive", cacheControl: "no-cache, max-age=0" },
      customMetadata: { sha256: sha256hex }, sha256: sha256hex,
    });
    return json(200, { ok: true, key, sizeBytes: bytes.byteLength, sha256: sha256hex, etag: r2Etag(out) });
  }
  return new Response(JSON.stringify({ message: "المسار غير موجود" }), { status: 404, headers: { "Content-Type": "application/json; charset=utf-8", ...SEC_HEADERS } });
}

// ---------- POST /catalog/publish: mirror a release into the store catalog ----------
// Constant-time bearer check: both sides are hashed first so the comparison runs over equal-length
// buffers and the loop never exits early — the token's length and prefix stay unobservable.
async function bearerMatches(req, secret) {
  const m = /^Bearer\s+(\S{16,256})$/u.exec(req.headers.get("Authorization") || "");
  if (!m || !secret) return false;
  const [a, b] = await Promise.all([crypto.subtle.digest("SHA-256", new TextEncoder().encode(m[1])), crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret))]);
  const x = new Uint8Array(a), y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}
const hex = buf => [...new Uint8Array(buf)].map(x => x.toString(16).padStart(2, "0")).join("");
// Body: { packageName, versionName, versionCode, apkUrl }. Steps: validate → fetch the APK from the
// app's own channel → read the catalog row (404 no_row / 409 not_newer) → compute the row edit in
// memory (500 if the surgery would touch anything else — nothing written in that case) → put the
// APK at its stable key → put the catalog. A partial failure between the two puts leaves a newer
// APK under an older row, which the store treats as "no update" — harmless, and the next call heals it.
async function handleCatalogPublish(req, env) {
  if (req.method !== "POST") return new Response(JSON.stringify({ error: "method_not_allowed" }), { status: 405, headers: { Allow: "POST", "Content-Type": "application/json; charset=utf-8", ...SEC_HEADERS } });
  if (!env.CATALOG_PUBLISH_SECRET) return json(503, { error: "not_configured", detail: "CATALOG_PUBLISH_SECRET missing" });
  if (!(await bearerMatches(req, env.CATALOG_PUBLISH_SECRET))) return json(401, { error: "unauthorized" });
  if (!env.CATALOG_R2) return json(503, { error: "not_configured", detail: "CATALOG_R2 binding missing" });
  let body;
  try { body = await readJsonBody(req); } catch (e) { return json(400, { error: "bad_body" }); }
  const packageName = String(body.packageName || "");
  const versionName = String(body.versionName || "");
  const versionCode = Number(body.versionCode);
  if (CATALOG_PUBLISH_FORBIDDEN.includes(packageName)) return json(403, { error: "package_forbidden", packageName });
  if (!CATALOG_PUBLISH_PACKAGES.includes(packageName)) return json(403, { error: "package_not_allowed", packageName });
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) return json(400, { error: "bad_version_code" });
  if (!VERSION_NAME_RE.test(versionName)) return json(400, { error: "bad_version_name" });
  let apkUrl;
  try { apkUrl = new URL(String(body.apkUrl || "")); } catch (e) { return json(400, { error: "bad_apk_url" }); }
  if (apkUrl.protocol !== "https:" || !CATALOG_PUBLISH_APK_HOSTS.includes(apkUrl.hostname)) return json(400, { error: "apk_host_not_allowed", host: apkUrl.hostname });

  // (a) the APK bytes, from the app's own channel
  const src = await fetch(apkUrl.toString(), { cache: "no-store" });
  if (src.status !== 200) return json(502, { error: "apk_fetch_failed", status: src.status });
  const declared = Number(src.headers.get("content-length"));
  if (!Number.isSafeInteger(declared) || declared <= 0) return json(502, { error: "apk_no_content_length" });
  if (declared > CATALOG_MAX_APK) return json(502, { error: "apk_too_large", sizeBytes: declared });
  const bytes = await src.arrayBuffer();
  if (bytes.byteLength !== declared) return json(502, { error: "apk_truncated", expected: declared, got: bytes.byteLength });
  const head = new Uint8Array(bytes, 0, 4);
  if (!(head[0] === 0x50 && head[1] === 0x4b && head[2] === 0x03 && head[3] === 0x04)) return json(502, { error: "apk_not_zip" });   // an error page, not an APK
  const sizeBytes = bytes.byteLength;
  const sha256hex = hex(await crypto.subtle.digest("SHA-256", bytes));
  // Optional integrity check: a caller that knows the hash of what it published (its own
  // latest.json / CI output) sends `sha256`; a truncated or wrong download is then refused
  // instead of being mirrored with a wrong size. Callers without it are still accepted.
  if (body.sha256 != null) {
    const want = String(body.sha256).trim().toLowerCase();
    if (!/^[0-9a-f]{64}$/.test(want)) return json(400, { error: "bad_sha256" });
    if (want !== sha256hex) return json(422, { error: "sha256_mismatch", expected: want, actual: sha256hex });
  }

  // (b) the catalog row as it stands
  const obj = await env.CATALOG_R2.get(CATALOG_KEY);
  if (!obj) return json(500, { error: "catalog_missing" });
  const text = await obj.text();
  let current;
  try { current = readCatalogRow(text, packageName); } catch (e) { return json(500, { error: e instanceof CatalogRowError ? e.code : "catalog_unreadable", detail: e.message }); }
  if (!current) return json(404, { error: "no_row", packageName });
  if (!(versionCode > current.versionCode)) return json(409, { error: "not_newer", current: current.versionCode, requested: versionCode });

  // (d, computed first) the row edit — refused outright if anything but the three fields would move
  let edit;
  try { edit = bumpCatalogRow(text, packageName, { versionCode, versionName, sizeBytes }); }
  catch (e) { return json(500, { error: e instanceof CatalogRowError ? e.code : "surgery_failed", detail: e.message }); }

  // (c) the APK at its stable key, then (e) the catalog
  await env.CATALOG_R2.put(`${CATALOG_APK_PREFIX}${packageName}.apk`, bytes, { httpMetadata: { contentType: "application/vnd.android.package-archive", cacheControl: "no-cache, max-age=0" }, customMetadata: { sha256: sha256hex, versionCode: String(versionCode), versionName, source: apkUrl.toString() } });
  await env.CATALOG_R2.put(CATALOG_KEY, edit.text, { httpMetadata: { contentType: "application/json", cacheControl: "no-cache, max-age=0" } });
  console.log(`catalog/publish ${packageName} ${edit.previous.versionCode}->${versionCode} (${versionName}) ${sizeBytes} bytes sha256=${sha256hex.slice(0, 16)} from ${apkUrl.hostname}`);
  return json(200, { ok: true, packageName, versionCode, versionName, sizeBytes, sha256: sha256hex, previousVersionCode: edit.previous.versionCode });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    // Home-screen icon + manifest: public, cacheable, and outside Access (installers fetch them cookie-less).
    const icons = { "/icon-180.png": ICON180, "/icon-192.png": ICON192, "/icon-512.png": ICON512, "/apple-touch-icon.png": ICON180, "/favicon.ico": ICON192 };
    // Self-hosted vendor files (supabase-js, SweetAlert2, Font Awesome, the two Google fonts) from
    // ./public via Static Assets. Public like the icons: nothing secret, and the browser fetches
    // them with the Access cookie anyway. Cached a day; the file names carry their version.
    if (req.method === "GET" && p.startsWith("/vendor/") && env.ASSETS) {
      const a = await env.ASSETS.fetch(new Request(url.origin + p, { method: "GET" }));
      if (a.status !== 200) return a;
      const h = new Headers(a.headers); h.set("Cache-Control", "public, max-age=86400"); h.set("X-Content-Type-Options", "nosniff");
      return new Response(a.body, { status: 200, headers: h });
    }
    if (req.method === "GET" && icons[p]) return new Response(icons[p], { status: 200, headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=600" } });
    // Push service worker (push.mjs): nothing secret in it. Placed before the Worker's own Access check so
    // an Access bypass rule for /sw.js (optional) keeps background updates working with an expired cookie;
    // without that rule the edge still asks for Access and the browser simply keeps the installed copy.
    if (req.method === "GET" && p === "/sw.js") {
      return new Response(SW_SOURCE, { status: 200, headers: { "Content-Type": "application/javascript; charset=utf-8", "Cache-Control": "no-cache", "X-Content-Type-Options": "nosniff", "Service-Worker-Allowed": "/" } });
    }
    if (req.method === "GET" && p === "/manifest.webmanifest") {
      return new Response(JSON.stringify({ name: "Thabthaba Programs Admin", short_name: "Thabthaba", start_url: "/gen", display: "standalone", background_color: "#211a12", theme_color: "#211a12", dir: "rtl", lang: "ar",
        icons: [{ src: "/icon-192.png?v=3", sizes: "192x192", type: "image/png" }, { src: "/icon-512.png?v=3", sizes: "512x512", type: "image/png" }] }),
        { status: 200, headers: { "Content-Type": "application/manifest+json", "Cache-Control": "public, max-age=3600" } });
    }
    // Machine route for the release CIs: its own bearer secret is the whole gate, so it sits before
    // Access (GitHub runners carry no Access JWT) and before the admin-session check. Everything it
    // can do is fixed by the allow-lists above; a wrong or missing token gets 401 and nothing else.
    if (p === "/catalog/publish") {
      if (limited(clientOf(req).ip)) return json(429, { error: "slow down" });
      try { return await handleCatalogPublish(req, env); }
      catch (e) { console.log(`catalog/publish failed: ${e && e.message}`); return json(500, { error: "internal", detail: e && e.message ? e.message : String(e) }); }
    }
    if (!(await accessOk(req, env))) return new Response("Access required", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });
    // A client that brute-forced the password is refused everything, page included, for 24 h.
    // If the block list cannot be read the request fails CLOSED.
    // Exception: a request carrying a live device session (the owner's own phones) is never
    // locked out by an IP block, so a brute-force from the same network cannot shut him out.
    let blocked = false;
    try {
      blocked = await isBlocked(env, clientOf(req).ip);
      if (blocked && env.DB) {
        const sid = cookieOf(req, DEV_COOKIE);
        const live = sid && await env.DB.prepare("SELECT 1 AS x FROM device_sessions WHERE id = ? AND revoked = 0").bind(sid).first();
        if (live) blocked = false;
      }
    } catch (e) { return json(503, { message: "guard unavailable" }); }
    if (blocked) return new Response("blocked", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });
    if (req.method === "GET" && p.startsWith("/cars/") && env.ASSETS) {
      const a = await env.ASSETS.fetch(new Request(url.origin + p, { method: "GET" }));
      if (a.status !== 200) return a;
      const h = new Headers(a.headers); h.set("Cache-Control", "public, max-age=86400"); h.set("X-Content-Type-Options", "nosniff");
      return new Response(a.body, { status: 200, headers: h });
    }
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
      return json(200, { local: true, remote: true, store: !!env.STORE_ADMIN_SECRET, tslink: !!env.TSLINK_ADMIN_TOKEN, leo: !!env.LEO_ADMIN_TOKEN, g700: !!env.G700_ADMIN_TOKEN, lynk: !!env.LYNK_ADMIN_TOKEN, tank: !!env.TANK, controller: ctrlOnCloudflare(env) || !!env.CONTROLLER_ADMIN_SECRET, ctrlSource: ctrlOnCloudflare(env) ? "cloudflare" : "supabase", usage: !!env.USAGE, trips: !!env.TRIPS_SVC, codes: !!env.DB, guard: !!env.DB, sessions: !!env.DB, writes: !!env.PANEL_WRITE_KEY, catalog: !!(env.CATALOG_PUBLISH_SECRET && env.CATALOG_R2), storeCatalog: !!env.CATALOG_R2, push: pushReady(env) });
    }
    if (!p.startsWith("/local/")) return json(404, { message: "not found" });

    // Public JSON mirrors (CORS relief only; the data is public anyway).
    if (req.method === "GET" && p === "/local/catalog") return passthrough(await fetch(`${CATALOG_URL}?cb=${Date.now()}`, { cache: "no-store" }));
    if (req.method === "GET" && p === "/local/leo-latest") return passthrough(await fetch(`${LEO_LATEST_URL}?cb=${Date.now()}`, { cache: "no-store" }));

    // Everything below injects a secret: admin session required, verified server-side.
    if (!(await isAdmin(req, env))) return json(401, { message: "admin session required" });

    // Full catalog and binary management. This stays below both Cloudflare Access and the
    // Supabase-admin-session guard; the browser never receives an R2 credential.
    if (p.startsWith("/local/store-catalog/")) {
      try { return await handleStoreCatalogRoute(req, env, url, p); }
      catch (e) { console.log(`store catalog failed: ${e && e.message}`); return json(500, { message: "تعذّرت إدارة الكتالوج" }); }
    }

    // Voice cost calculator: public OpenRouter list prices for the models the owner configured
    // (primary + fallbacks). No key is sent; the page's CSP cannot reach openrouter.ai itself.
    if (req.method === "GET" && p === "/local/openrouter-prices") {
      const ids = String(url.searchParams.get("models") || "").split(",").map(s => s.trim())
        .filter(s => /^[\w.:\/-]{1,120}$/.test(s)).slice(0, 10);
      if (!ids.length) return json(400, { message: "models required" });
      let all = [];
      try {
        const r = await fetch("https://openrouter.ai/api/v1/models", { cf: { cacheTtl: 3600, cacheEverything: true } });
        if (!r.ok) return json(502, { message: `openrouter ${r.status}` });
        all = ((await r.json()) || {}).data || [];
      } catch (e) { return json(502, { message: "openrouter unreachable" }); }
      const num = v => (v == null || v === "" || !Number.isFinite(Number(v))) ? null : Number(v);
      const models = {};
      for (const id of ids) {
        const m = all.find(x => x && (x.id === id || x.canonical_slug === id));
        const pr = (m && m.pricing) || {};
        models[id] = m ? { name: String(m.name || id), prompt: num(pr.prompt), completion: num(pr.completion), audio: num(pr.audio ?? pr.input_audio), request: num(pr.request) } : null;
      }
      return json(200, { models });
    }

    // Controller stat tile «رصيد OpenRouter»: the balance left on the account that pays for voice.
    // Asked of thab-voice over its AdminCredits RPC entrypoint (service binding; no public route
    // exists), which holds the key and returns numbers only — the key never reaches this Worker.
    // Only known numeric/enum fields are copied through, and it is cached ~60 s over there.
    if (req.method === "GET" && p === "/local/openrouter-credits") {
      if (!env.VOICE_SVC) return json(503, { message: "voice service binding missing", reason: "binding_missing" });
      let r;
      try { r = await env.VOICE_SVC.credits(); }
      catch (e) { console.log("openrouter-credits rpc failed"); return json(502, { message: "voice service unreachable", reason: "rpc_failed" }); }
      const n = v => (v == null || !Number.isFinite(Number(v))) ? null : Number(v);
      const s = v => (typeof v === "string" && /^[a-z_]{1,48}$/.test(v)) ? v : null;
      const out = r && typeof r === "object" ? {
        ok: r.ok === true, source: s(r.source), reason: s(r.reason),
        remaining: n(r.remaining), total_credits: n(r.total_credits), total_usage: n(r.total_usage),
        limit: n(r.limit), limit_remaining: n(r.limit_remaining), key_usage: n(r.key_usage),
        credits_status: n(r.credits_status), key_status: n(r.key_status),
        fetched_at: typeof r.fetched_at === "string" ? r.fetched_at.slice(0, 40) : null, cached: r.cached === true,
      } : { ok: false, reason: "bad_reply" };
      return json(200, out);
    }

    // Voice model router «إدارة الموديلات والمفاتيح»: Google keys, per-model limits, thinking flags,
    // live usage. Talks to thab-voice's AdminRouter RPC entrypoint (service binding; no public route).
    // The router never returns a key value; api_key only travels browser → here → thab-voice on save.
    // Per-car Google/OpenRouter usage (GET usage, car_usage) and the Google order (POST set_google_order)
    // since 2026-09-16. Handler in voice-router-route.mjs (unit-tested there).
    if (p === "/local/voice-router" || p.startsWith("/local/voice-router/")) {
      return voiceRouterRoute(req, env, url, p, { json, readJsonBody });
    }

    // «استعلام عن بُعد» remote diagnostics: read-only probes for live cars. Signs here (DIAG_SIGNING_JWK),
    // stores/serves through thab-voice's AdminDiag entrypoint (DIAG_SVC). See diag-admin.mjs.
    if (p === "/local/diag" || p.startsWith("/local/diag/")) {
      return handleDiagRoute(req, env, { url, json, readJsonBody, actor: clientOf(req).email });
    }

    // Controller records on Cloudflare: Quran/adhkar/prayer usage (AdminUsage) and trips/charges/refuels
    // (AdminTrips). See telemetry-admin.mjs. AppLog downloads are /local/diag/applog* (diag-admin.mjs).
    if (/^\/local\/(usage|trips)(\/|$)/u.test(p)) {
      return (await telemetryRoute(req, env, url, { json, readJsonBody })) || json(404, { message: "not found" });
    }

    // TS Tank activation admin proxy: talks to thab-voice's AdminTank RPC entrypoint.
    // The D1 and code space live there; this Worker only allow-lists method names and sanity-checks args.
    if (req.method === "POST" && p.startsWith("/local/tank/")) {
      if (!env.TANK) return json(503, { message: "tank binding not configured" });
      const method = p.slice("/local/tank/".length);
      const TANK_METHODS = ["codes", "generate", "revoke_code", "devices", "unban", "reset_attempts", "ban", "audit"];
      if (!TANK_METHODS.includes(method)) return json(404, { message: "not found" });
      let body;
      try { body = await readJsonBody(req); }
      catch (e) { return json(400, { message: "bad request" }); }
      const okArgs = (() => {
        if (!body || typeof body !== "object" || Array.isArray(body)) return false;
        if (body.count != null && (!Number.isInteger(body.count) || body.count < 1 || body.count > 999)) return false;
        if (body.code != null && !/^[0-9]{6}$/.test(String(body.code))) return false;
        if (body.hw != null && !/^[A-Za-z0-9._:-]{4,64}$/u.test(String(body.hw))) return false;
        if (body.limit != null && (!Number.isInteger(body.limit) || body.limit < 1)) return false;
        return true;
      })();
      if (!okArgs) return json(400, { message: "bad request" });
      try {
        const result = await env.TANK[method](body);
        return json(200, result || {});
      } catch (e) {
        console.log(`tank rpc failed: ${e && e.message}`);
        return json(502, { message: "tank unavailable" });
      }
    }

    try {
      // ---- writes: the browser never holds the write key; every write is allow-listed here ----
      if (env.PANEL_WRITE_KEY && req.method === "POST" && p.startsWith("/local/write/rpc/")) {
        const name = p.slice("/local/write/rpc/".length);
        if (!/^admin_set_device_block_all$/.test(name)) return json(404, { message: "rpc not allowed" });
        const body = await readJsonBody(req);
        return passthrough(await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/${name}`, {
          method: "POST", headers: { "Content-Type": "application/json", apikey: env.SUPABASE_ANON, Authorization: req.headers.get("Authorization"), "x-write-key": env.PANEL_WRITE_KEY },
          body: JSON.stringify(body),
        }));
      }
      if (env.PANEL_WRITE_KEY && req.method === "POST" && p === "/local/write/rest") {
        const w = await readJsonBody(req);
        const method = String(w.method || "").toUpperCase(), path = String(w.path || ""), prefer = String(w.prefer || "return=minimal");
        const keys = w.body && typeof w.body === "object" && !Array.isArray(w.body) ? Object.keys(w.body) : [];
        const ok =
          (method === "PATCH" && /^devices\?hardware_id=eq\.[^&]+$/.test(path) && keys.every(k => k === "client_name")) ||
          (method === "PATCH" && /^admin_settings\?key=eq\.voice\.[a-z_]+$/.test(path) && keys.every(k => ["value", "updated_at"].includes(k))) ||
          (method === "POST" && /^(wallpapers|wallpaper_hides)(\?.*)?$/.test(path)) ||
          (method === "DELETE" && /^(wallpapers|wallpaper_hides)\?(id|url|wallpaper_id)=eq\.[^&]+(&hardware_id=eq\.[^&]+)?$/.test(path)) ||
          (method === "POST" && /^app_versions(\?.*)?$/.test(path)) ||
          // resolved-crash marks (crash_resolutions): insert/upsert, edit or remove one signature
          (method === "POST" && /^crash_resolutions(\?.*)?$/.test(path)) ||
          (["PATCH", "DELETE"].includes(method) && /^crash_resolutions\?id=eq\.\d+$/.test(path));
        if (!ok) return json(404, { message: "write not allowed" });
        if (!/^return=(minimal|representation)$/.test(prefer)) return json(400, { message: "bad prefer" });
        const init = { method, headers: { "Content-Type": "application/json", apikey: env.SUPABASE_ANON, Authorization: req.headers.get("Authorization"), "x-write-key": env.PANEL_WRITE_KEY, Prefer: prefer } };
        if (w.body !== undefined && method !== "DELETE") init.body = JSON.stringify(w.body);
        return passthrough(await fetch(`${env.SUPABASE_URL}/rest/v1/${path}`, init));
      }
      if (env.PANEL_WRITE_KEY && (req.method === "POST" || req.method === "DELETE") && p === "/local/write/upload") {
        if (!env.UPLOAD_SVC) return json(503, { message: "upload service binding missing" });
        const h = new Headers();
        for (const k of ["authorization", "content-type", "x-file-name", "x-prefix"]) { const v = req.headers.get(k); if (v) h.set(k, v); }
        h.set("x-write-key", env.PANEL_WRITE_KEY);
        const upstream = await env.UPLOAD_SVC.fetch(new Request("https://ts-wallpapers-upload.tsdash-qatar.workers.dev/", { method: req.method, headers: h, body: req.method === "POST" ? req.body : undefined }));
        return passthrough(upstream);
      }
      // ---- Web Push: subscribe / unsubscribe / test for this admin device (push.mjs) ----
      if (p.startsWith("/local/push/")) {
        return await handlePushRoute(req, env, p, { json, readJsonBody, client: clientOf(req), sessionPrefix: (cookieOf(req, DEV_COOKIE) || "").slice(0, 8) || null });
      }
      // ---- security: events for the panel's notifications, blocked list, unblock ----
      if (env.DB && req.method === "GET" && p === "/local/security/events") {
        const since = url.searchParams.get("since") || new Date(Date.now() - 86400_000).toISOString();
        const rows = await env.DB.prepare("SELECT id, at, kind, ip, email, detail FROM security_events WHERE at > ? ORDER BY at DESC LIMIT 100").bind(since).all();
        const mine = (cookieOf(req, DEV_COOKIE) || "").slice(0, 8);
        // self: this login_ok created the caller's own device session (the panel skips notifying it).
        return json(200, { events: (rows.results || []).map(r => ({ ...r, self: !!mine && r.kind === "login_ok" && String(r.detail || "").endsWith(`sid:${mine}`) })) });
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
      if (env.DB && req.method === "POST" && p === "/local/auth/devices/rename") {
        const body = await readJsonBody(req); const prefix = String(body.id || "").slice(0, 8); const label = String(body.label || "").trim().slice(0, 40);
        if (!/^[A-Za-z0-9_-]{8}$/.test(prefix) || !label) return json(400, { message: "bad id/label" });
        await env.DB.prepare("UPDATE device_sessions SET label = ? WHERE substr(id, 1, 8) = ?").bind(label, prefix).run();
        return json(200, { ok: true });
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
        const body = await readJsonBody(req);
        const hasPhone = body.phone != null && String(body.phone).trim() !== "";
        const normalized = hasPhone ? normalizePhone(body.phone) : null;
        if (hasPhone && !normalized) return json(400, { message: "رقم الهاتف غير صالح" });
        // 30 minutes (owner's call 2026-09-14, was 10): the code is typed on a car screen by a
        // customer on the phone with us, and ten minutes ran out too often. Still one car, one use.
        const now = new Date(); const expires = new Date(now.getTime() + 30 * 60_000);
        // Owner's rule: an expired, never-used code goes back to the pool so it can be minted again later.
        await sweepUnboundPhones(env, now.toISOString());
        await env.DB.prepare("DELETE FROM issued_codes WHERE used_by IS NULL AND expires_at < ?").bind(now.toISOString()).run();
        const iso = d => d.toISOString();
        let serial = null;
        for (let i = 0; i < 12 && !serial; i++) {
          const r = new Uint32Array(1); crypto.getRandomValues(r);
          // Owner's rule (2026-09-07): six random digits, no prefix. Never shaped like the
          // reserve block (572xxx), the closed 578 space, or a legacy 7078xx code.
          const cand = String(r[0] % 1000000).padStart(6, "0");
          if (/^(572|578|7078)/.test(cand)) continue;
          const taken = await env.DB.prepare("SELECT 1 AS x FROM devices WHERE serial_number = ? UNION ALL SELECT 1 FROM issued_codes WHERE serial = ?").bind(cand, cand).first();
          if (!taken) serial = cand;
        }
        if (!serial) return json(503, { message: "could not mint a unique code" });
        // issued_by = the first 8 chars of the minting device's session id (its label is joined at read time)
        const dev = cookieOf(req, DEV_COOKIE); const by = dev ? dev.slice(0, 8) : "programs-admin";
        try {
          await env.DB.prepare("INSERT INTO issued_codes (serial, issued_at, expires_at, issued_by, note, customer_phone) VALUES (?, ?, ?, ?, ?, ?)")
            .bind(serial, iso(now), iso(expires), by, "generator", normalized && normalized.e164).run();
        } catch (e) {
          // Rollout safety: until the one-time phone migration is applied, ordinary code minting
          // must continue on the old schema. A supplied phone cannot be claimed as saved.
          if (normalized) return json(503, { message: "ميزة رقم الهاتف غير جاهزة بعد" });
          await env.DB.prepare("INSERT INTO issued_codes (serial, issued_at, expires_at, issued_by, note) VALUES (?, ?, ?, ?, ?)")
            .bind(serial, iso(now), iso(expires), by, "generator").run();
        }
        return json(200, { serial, issued_at: iso(now), expires_at: iso(expires), issued_by: by, customer_phone: normalized ? normalized.e164 : null });
      }
      if (env.DB && req.method === "POST" && p === "/local/codes/phone") {
        const body = await readJsonBody(req);
        const serial = String(body.serial || "").trim();
        if (!/^(\d{6}|578\d{6})$/.test(serial)) return json(400, { message: "كود التفعيل غير صالح" });
        const code = await env.DB.prepare("SELECT serial, issued_at, used_by, used_at FROM issued_codes WHERE serial = ?").bind(serial).first();
        if (!code) return json(404, { message: "كود التفعيل غير موجود" });
        const rawPhone = body.phone == null ? "" : String(body.phone).trim();
        const normalized = rawPhone ? normalizePhone(rawPhone) : null;
        if (rawPhone && !normalized) return json(400, { message: "أدخل رقمًا قطريًا من ثمانية أرقام، أو اكتب رمز الدولة مثل ‎+966" });
        try {
          await env.DB.prepare("UPDATE issued_codes SET customer_phone = ? WHERE serial = ?").bind(normalized ? normalized.e164 : null, serial).run();
          if (normalized && code.used_by) {
            const now = new Date().toISOString();
            try {
              await env.DB.prepare(`INSERT INTO car_customers (hw_id, phone, serial, bound_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(hw_id) DO UPDATE SET phone = excluded.phone, serial = excluded.serial,
                  bound_at = excluded.bound_at, updated_at = excluded.updated_at
                WHERE excluded.bound_at >= car_customers.bound_at`)
                .bind(code.used_by, normalized.e164, serial, code.used_at || code.issued_at, now).run();
            } catch (e) { /* The table may not exist during migration rollout; the code phone is still saved. */ }
          }
        } catch (e) {
          return json(503, { message: "ميزة رقم الهاتف غير جاهزة بعد" });
        }
        return json(200, { serial, customer_phone: normalized ? normalized.e164 : null, country: normalized ? phoneCountry(normalized.e164) : null });
      }
      if (env.DB && req.method === "GET" && p === "/local/codes/status") {
        const serial = (url.searchParams.get("serial") || "").trim();
        if (!/^(\d{6}|578\d{6})$/.test(serial)) return json(400, { message: "bad serial" });
        let row;
        // Phone data is optional during rollout: every new-column/table read is isolated so an
        // unapplied migration degrades to no phone data instead of breaking the generator.
        try {
          row = await env.DB.prepare("SELECT serial, issued_at, expires_at, used_by, used_at, customer_phone FROM issued_codes WHERE serial = ?").bind(serial).first();
        } catch (e) {
          row = await env.DB.prepare("SELECT serial, issued_at, expires_at, used_by, used_at FROM issued_codes WHERE serial = ?").bind(serial).first();
          if (row) row.customer_phone = null;
        }
        if (!row) return json(404, { message: "unknown code" });
        if (row.used_by && row.customer_phone) {
          try {
            const now = new Date().toISOString();
            await env.DB.prepare(`INSERT INTO car_customers (hw_id, phone, serial, bound_at, updated_at)
              VALUES (?, ?, ?, ?, ?)
              ON CONFLICT(hw_id) DO UPDATE SET phone = excluded.phone, serial = excluded.serial,
                bound_at = excluded.bound_at, updated_at = excluded.updated_at
              WHERE excluded.bound_at > car_customers.bound_at`)
              .bind(row.used_by, row.customer_phone, row.serial, row.used_at || row.issued_at, now).run();
          } catch (e) { /* Migration not applied yet: status remains available without phone association. */ }
        }
        return json(200, row);
      }
      if (env.DB && req.method === "GET" && p === "/local/codes/recent") {
        let rows;
        try {
          rows = await env.DB.prepare("SELECT ic.serial, ic.issued_at, ic.expires_at, ic.used_by, ic.used_at, ic.issued_by, ic.customer_phone, ds.label AS issued_by_label FROM issued_codes ic LEFT JOIN device_sessions ds ON substr(ds.id, 1, 8) = ic.issued_by ORDER BY ic.issued_at DESC LIMIT 30").all();
        } catch (e) {
          rows = await env.DB.prepare("SELECT ic.serial, ic.issued_at, ic.expires_at, ic.used_by, ic.used_at, ic.issued_by, ds.label AS issued_by_label FROM issued_codes ic LEFT JOIN device_sessions ds ON substr(ds.id, 1, 8) = ic.issued_by ORDER BY ic.issued_at DESC LIMIT 30").all();
          rows.results = (rows.results || []).map(row => ({ ...row, customer_phone: null }));
        }
        return json(200, { codes: rows.results || [] });
      }
      if (env.DB && req.method === "GET" && p === "/local/customers") {
        const rawLimit = Number(url.searchParams.get("limit") || 500);
        const limit = Number.isInteger(rawLimit) ? Math.min(2000, Math.max(1, rawLimit)) : 500;
        const hw = (url.searchParams.get("hw") || "").trim();
        const phone = (url.searchParams.get("phone") || "").trim();
        if (hw && !/^[A-Za-z0-9:_.\-]{1,120}$/.test(hw)) return json(400, { message: "معرّف السيارة غير صالح" });
        if (phone && !/^\+[1-9]\d{6,14}$/.test(phone)) return json(400, { message: "رقم الهاتف غير صالح" });
        try {
          const now = new Date().toISOString();
          await env.DB.prepare(`INSERT INTO car_customers (hw_id, phone, serial, bound_at, updated_at)
            SELECT used_by, customer_phone, serial, COALESCE(used_at, issued_at), ?1
              FROM issued_codes
             WHERE used_by IS NOT NULL AND customer_phone IS NOT NULL AND customer_phone <> ''
            ON CONFLICT(hw_id) DO UPDATE SET
              phone = excluded.phone, serial = excluded.serial, bound_at = excluded.bound_at, updated_at = excluded.updated_at
            WHERE excluded.bound_at > car_customers.bound_at`).bind(now).run();
          const clauses = []; const binds = [];
          if (hw) { clauses.push("hw_id = ?"); binds.push(hw); }
          if (phone) { clauses.push("phone = ?"); binds.push(phone); }
          binds.push(limit);
          const rows = await env.DB.prepare(`SELECT hw_id, phone, serial, bound_at, updated_at, note FROM car_customers${clauses.length ? ` WHERE ${clauses.join(" AND ")}` : ""} ORDER BY bound_at DESC LIMIT ?`).bind(...binds).all();
          const customers = (rows.results || []).map(row => ({ ...row, country: phoneCountry(row.phone) }));
          return json(200, { customers, count: customers.length });
        } catch (e) {
          // Both the sync and read touch migration-owned objects. Before rollout, answer as an
          // empty phone directory rather than turning every admin-page load into a 500.
          return json(200, { customers: [], count: 0 });
        }
      }
      if (env.DB && req.method === "GET" && p === "/local/customers/unbound") {
        const rawLimit = Number(url.searchParams.get("limit") || 100);
        const limit = Number.isInteger(rawLimit) ? Math.min(500, Math.max(1, rawLimit)) : 100;
        const now = new Date().toISOString();
        await sweepUnboundPhones(env, now);
        try {
          const [rows, total] = await Promise.all([
            env.DB.prepare(`SELECT id, phone, serial, issued_at, expired_at, issued_by
              FROM unbound_phones
              WHERE dismissed_at IS NULL AND linked_at IS NULL
              ORDER BY expired_at DESC, id DESC LIMIT ?`).bind(limit).all(),
            env.DB.prepare("SELECT COUNT(*) AS n FROM unbound_phones WHERE dismissed_at IS NULL AND linked_at IS NULL").first(),
          ]);
          const items = (rows.results || []).map(row => ({ ...row, country: phoneCountry(row.phone) }));
          return json(200, { items, count: Number(total && total.n) || 0 });
        } catch (e) {
          return json(200, { items: [], count: 0 });
        }
      }
      if (env.DB && req.method === "POST" && p === "/local/customers/unbound/dismiss") {
        const body = await readJsonBody(req); const id = Number(body.id);
        if (!Number.isSafeInteger(id) || id < 1) return json(400, { message: "معرّف الرقم غير صالح" });
        try {
          const result = await env.DB.prepare("UPDATE unbound_phones SET dismissed_at = ? WHERE id = ? AND dismissed_at IS NULL AND linked_at IS NULL")
            .bind(new Date().toISOString(), id).run();
          if (!result.meta || !result.meta.changes) return json(404, { message: "الرقم غير موجود" });
          return json(200, { ok: true, id });
        } catch (e) {
          return json(503, { message: "قائمة الأرقام غير المربوطة غير جاهزة بعد" });
        }
      }
      if (env.DB && req.method === "POST" && p === "/local/customers/unbound/link") {
        const body = await readJsonBody(req); const id = Number(body.id); const hwId = String(body.hw_id || "").trim();
        if (!Number.isSafeInteger(id) || id < 1) return json(400, { message: "معرّف الرقم غير صالح" });
        if (!/^[A-Za-z0-9:_.\-]{1,120}$/.test(hwId)) return json(400, { message: "معرّف السيارة غير صالح" });
        try {
          const item = await env.DB.prepare("SELECT id, phone, serial, expired_at FROM unbound_phones WHERE id = ? AND dismissed_at IS NULL AND linked_at IS NULL").bind(id).first();
          if (!item) return json(404, { message: "الرقم غير موجود" });
          const now = new Date().toISOString();
          const note = "رُبط يدويًا من قائمة الأرقام غير المربوطة";
          await env.DB.batch([
            env.DB.prepare(`INSERT INTO car_customers (hw_id, phone, serial, bound_at, updated_at, note)
              VALUES (?, ?, ?, ?, ?, ?)
              ON CONFLICT(hw_id) DO UPDATE SET phone = excluded.phone, serial = excluded.serial,
                bound_at = excluded.bound_at, updated_at = excluded.updated_at, note = excluded.note`)
              .bind(hwId, item.phone, item.serial, now, now, note),
            env.DB.prepare("UPDATE unbound_phones SET linked_hw = ?, linked_at = ? WHERE id = ? AND dismissed_at IS NULL AND linked_at IS NULL")
              .bind(hwId, now, id),
          ]);
          return json(200, { ok: true, id, hw_id: hwId, phone: item.phone, country: phoneCountry(item.phone) });
        } catch (e) {
          return json(503, { message: "تعذّر ربط الرقم بالسيارة" });
        }
      }
      if (env.DB && req.method === "POST" && p === "/local/customers/set") {
        const body = await readJsonBody(req);
        const hwId = String(body.hw_id || "").trim();
        if (!/^[A-Za-z0-9:_.\-]{1,120}$/.test(hwId)) return json(400, { message: "معرّف السيارة غير صالح" });
        const rawPhone = body.phone == null ? "" : String(body.phone).trim();
        const normalized = rawPhone ? normalizePhone(rawPhone) : null;
        if (rawPhone && !normalized) return json(400, { message: "رقم الهاتف غير صالح" });
        const now = new Date().toISOString();
        try {
          if (!normalized) {
            await env.DB.prepare("DELETE FROM car_customers WHERE hw_id = ?").bind(hwId).run();
            return json(200, { hw_id: hwId, phone: null, country: null, serial: null, bound_at: null, updated_at: now, note: null, deleted: true });
          }
          const note = body.note == null ? null : String(body.note).trim().slice(0, 500) || null;
          await env.DB.prepare(`INSERT INTO car_customers (hw_id, phone, serial, bound_at, updated_at, note)
            VALUES (?, ?, NULL, ?, ?, ?)
            ON CONFLICT(hw_id) DO UPDATE SET phone = excluded.phone, serial = NULL,
              bound_at = excluded.bound_at, updated_at = excluded.updated_at, note = excluded.note`)
            .bind(hwId, normalized.e164, now, now, note).run();
          const row = await env.DB.prepare("SELECT hw_id, phone, serial, bound_at, updated_at, note FROM car_customers WHERE hw_id = ?").bind(hwId).first();
          return json(200, { ...row, country: phoneCountry(row.phone) });
        } catch (e) {
          return json(503, { message: "ميزة رقم الهاتف غير جاهزة بعد" });
        }
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
        // thab-voice's AdminTelemetry (D1) by default; the old Supabase proxy when CTRL_SOURCE = "supabase".
        return await controllerRoute(req, env, p.slice("/local/controller/".length), { json, readJsonBody, passthrough });
      }
      if (req.method === "GET" && p.startsWith("/local/tslink")) {
        const sub = p.slice("/local/tslink".length) || "/overview";
        if (!TSLINK_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.TSLINK_ADMIN_TOKEN) return json(503, { message: "ts-link token not configured" });
        if (!env.TSLINK_SVC) return json(503, { message: "tslink service binding missing" });
        const upstream = await env.TSLINK_SVC.fetch(new Request(`${TSLINK_ADMIN_BASE}${sub}${url.search}`, { method: "GET", headers: { Authorization: `Bearer ${env.TSLINK_ADMIN_TOKEN}`, Accept: "application/json" } }));
        return passthrough(upstream);
      }
      if (req.method === "GET" && p.startsWith("/local/leo")) {
        const sub = p.slice("/local/leo".length);
        if (!LEO_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.LEO_ADMIN_TOKEN) return json(503, { message: "leo token not configured" });
        const f = env.LEO_SVC ? env.LEO_SVC.fetch.bind(env.LEO_SVC) : fetch;
        return passthrough(await f(`${LEO_ADMIN_BASE}${sub}${url.search}`, { headers: { "X-Admin-Token": env.LEO_ADMIN_TOKEN }, cache: "no-store" }));
      }
      // TS G700 — tsdash-checkin (KV). `/latest` is the app's own update manifest on R2 (public,
      // but no CORS header of its own, so the page cannot read it directly).
      if (req.method === "GET" && p.startsWith("/local/g700")) {
        const sub = p.slice("/local/g700".length);
        if (sub === "/latest") return passthrough(await fetch(`${G700_LATEST_URL}?cb=${Date.now()}`, { cache: "no-store" }));
        if (!G700_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.G700_ADMIN_TOKEN) return json(503, { message: "g700 token not configured" });
        // Worker-to-Worker over the public hostname fails with error 1042 inside one account, so the
        // service binding is the real path; plain fetch stays as the fallback for a deploy without it.
        const f = env.G700_SVC ? env.G700_SVC.fetch.bind(env.G700_SVC) : fetch;
        return passthrough(await f(`${G700_ADMIN_BASE}${sub}${url.search}`, { headers: { "X-Admin-Token": env.G700_ADMIN_TOKEN }, cache: "no-store" }));
      }
      // TS Lynk & Co — ts-lynk-report (R2). `/devices` needs the admin token; `/latest` and `/codes`
      // are the bucket's own public files.
      if (req.method === "GET" && p.startsWith("/local/lynk")) {
        const sub = p.slice("/local/lynk".length);
        if (sub === "/latest") return passthrough(await fetch(`${LYNK_PUBLIC_BASE}/latest.json?cb=${Date.now()}`, { cache: "no-store" }));
        if (sub === "/codes") return passthrough(await fetch(`${LYNK_PUBLIC_BASE}/activation/codes.json?cb=${Date.now()}`, { cache: "no-store" }));
        if (!LYNK_GET_ALLOW.test(sub)) return json(404, { message: "path not allowed" });
        if (!env.LYNK_ADMIN_TOKEN) return json(503, { message: "lynk token not configured" });
        const f = env.LYNK_SVC ? env.LYNK_SVC.fetch.bind(env.LYNK_SVC) : fetch;
        return passthrough(await f(`${LYNK_ADMIN_BASE}${sub}${url.search}`, { headers: { "x-ts-admin": env.LYNK_ADMIN_TOKEN }, cache: "no-store" }));
      }
      return json(404, { message: "not found" });
    } catch (e) {
      return json(400, { message: e && e.message ? e.message : "bad request" });
    }
  },
  // Cron Trigger (wrangler.toml [triggers]): push new activations / logins to subscribed devices.
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runPushCron(env).then(r => { if (r && (r.sent || r.failed)) console.log(`push cron ${JSON.stringify(r)}`); }).catch(e => console.log(`push cron failed: ${e && e.message}`)));
    // Same tick: new rows that cars <= 2.30.15 still post to Supabase -> D1 (telemetry-admin.mjs). Counts only in the log.
    ctx.waitUntil(runTelemetryBridge(env, event.scheduledTime).then(r => { if (r && (r.events || r.cars || r.crashes)) console.log(`telemetry bridge ${JSON.stringify(r)}`); }).catch(e => console.log(`telemetry bridge failed: ${e && e.message}`)));
  },
};
