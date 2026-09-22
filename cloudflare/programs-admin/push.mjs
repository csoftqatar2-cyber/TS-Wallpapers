/**
 * Web Push for the admin panel: the owner's phones/laptops get a system notification for every new
 * car activation and every login on this site — with the browser closed.
 *
 *   cron (every minute) ──► D1 devices_audit (action='activate') + security_events (login_ok, …)
 *        │                   cursors in push_state (compare-and-set, so two runs never send twice)
 *        └──► Web Push (VAPID RFC 8292, payload aes128gcm RFC 8291) ──► FCM / APNs / Mozilla / WNS
 *                                                                        └──► /sw.js showNotification
 *
 * Why an encrypted payload and not an empty "tickle" push: the site is behind Cloudflare Access. A
 * service worker woken by an empty push would have to fetch the details from this origin, and that
 * fetch fails whenever the Access cookie has expired (and iOS forbids a push that shows nothing).
 * The payload is encrypted end-to-end to the subscribed browser, carries only a short Arabic label
 * (title, car id or device family, a tag) and needs no network at all on the device.
 *
 * No dependency: WebCrypto only (ECDSA P-256 for VAPID, ECDH + HKDF + AES-GCM for the payload).
 * Secrets: VAPID_PRIVATE_JWK (wrangler secret, JSON JWK of the P-256 private key).
 * Vars: VAPID_PUBLIC_KEY (base64url uncompressed point, 65 bytes), optional VAPID_SUBJECT.
 */

const te = new TextEncoder();
export const b64uEncode = bytes => {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = ""; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
export const b64uDecode = s => {
  const clean = String(s || "").replace(/-/g, "+").replace(/_/g, "/");
  if (!/^[A-Za-z0-9+/]*$/.test(clean)) throw new Error("bad base64url");
  return Uint8Array.from(atob(clean.padEnd(Math.ceil(clean.length / 4) * 4, "=")), c => c.charCodeAt(0));
};
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
};

// ---------- RFC 8291 payload encryption (aes128gcm, one record) ----------
async function hkdf(salt, ikm, info, bytes) {
  const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, bytes * 8));
}
/**
 * Encrypts `payload` (Uint8Array) for one subscription. `test` lets the unit test inject the RFC 8291
 * Appendix A sender key pair and salt; production always uses a fresh ephemeral key and random salt.
 */
export async function encryptPayload(payload, p256dh, auth, test) {
  const uaPublic = b64uDecode(p256dh), authSecret = b64uDecode(auth);
  if (uaPublic.length !== 65 || uaPublic[0] !== 4) throw new Error("bad p256dh");
  if (authSecret.length !== 16) throw new Error("bad auth");
  if (payload.length > 3800) throw new Error("payload too large");
  const as = test && test.keyPair ? test.keyPair : await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const asPublic = new Uint8Array(await crypto.subtle.exportKey("raw", as.publicKey));
  const uaKey = await crypto.subtle.importKey("raw", uaPublic, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const ecdhSecret = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: uaKey }, as.privateKey, 256));
  const ikm = await hkdf(authSecret, ecdhSecret, concat(te.encode("WebPush: info\0"), uaPublic, asPublic), 32);
  const salt = test && test.salt ? test.salt : crypto.getRandomValues(new Uint8Array(16));
  const cek = await hkdf(salt, ikm, te.encode("Content-Encoding: aes128gcm\0"), 16);
  const nonce = await hkdf(salt, ikm, te.encode("Content-Encoding: nonce\0"), 12);
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["encrypt"]);
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, tagLength: 128 }, key, concat(payload, new Uint8Array([2]))));
  const rs = new Uint8Array([0, 0, 0x10, 0]);            // record size 4096
  return concat(salt, rs, new Uint8Array([asPublic.length]), asPublic, cipher);
}

// ---------- RFC 8292 VAPID ----------
let vapidCache = { raw: null, key: null, jwts: new Map() };
async function vapidKey(env) {
  const cacheKey = `${env.VAPID_PRIVATE_JWK}|${env.VAPID_PUBLIC_KEY}`;
  if (vapidCache.key && vapidCache.raw === cacheKey) return vapidCache.key;
  const jwk = JSON.parse(env.VAPID_PRIVATE_JWK);
  const clean = { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, d: jwk.d };
  // The public key the browsers subscribed with must be the private key's own point.
  const point = b64uEncode(concat(new Uint8Array([4]), b64uDecode(jwk.x), b64uDecode(jwk.y)));
  if (point !== env.VAPID_PUBLIC_KEY) throw new Error("VAPID_PUBLIC_KEY does not match VAPID_PRIVATE_JWK");
  const key = await crypto.subtle.importKey("jwk", clean, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  vapidCache = { raw: cacheKey, key, jwts: new Map() };
  return key;
}
export async function vapidHeader(env, endpoint, now = Date.now()) {
  const aud = new URL(endpoint).origin;
  const hit = vapidCache.jwts.get(aud);
  if (hit && hit.until > now && vapidCache.raw === `${env.VAPID_PRIVATE_JWK}|${env.VAPID_PUBLIC_KEY}`) return hit.value;
  const key = await vapidKey(env);
  const head = b64uEncode(te.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })));
  const exp = Math.floor(now / 1000) + 12 * 3600;
  const claims = b64uEncode(te.encode(JSON.stringify({ aud, exp, sub: env.VAPID_SUBJECT || "https://thabthaba-programs-admin.tsdash-qatar.workers.dev" })));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, te.encode(`${head}.${claims}`));   // raw r||s = JWS ES256
  const value = `vapid t=${head}.${claims}.${b64uEncode(sig)}, k=${env.VAPID_PUBLIC_KEY}`;
  vapidCache.jwts.set(aud, { value, until: now + 3600_000 });
  return value;
}
export const pushReady = env => !!(env && env.DB && env.VAPID_PRIVATE_JWK && env.VAPID_PUBLIC_KEY);

// Only the real push services may be written to: an endpoint is an outbound URL this Worker will POST
// to, so an arbitrary host would turn the subscribe route into an SSRF primitive.
const PUSH_HOSTS = [/^fcm\.googleapis\.com$/, /^android\.googleapis\.com$/, /^updates\.push\.services\.mozilla\.com$/, /^[a-z0-9-]+\.notify\.windows\.com$/, /^web\.push\.apple\.com$/, /^[a-z0-9-]+\.push\.apple\.com$/];
export function validSubscription(body) {
  const endpoint = typeof body.endpoint === "string" ? body.endpoint : "";
  if (!endpoint || endpoint.length > 1024) return null;
  let u; try { u = new URL(endpoint); } catch (e) { return null; }
  if (u.protocol !== "https:" || u.port || u.username || u.password || !PUSH_HOSTS.some(re => re.test(u.hostname))) return null;
  const keys = body.keys && typeof body.keys === "object" ? body.keys : {};
  try {
    const p = b64uDecode(keys.p256dh), a = b64uDecode(keys.auth);
    if (p.length !== 65 || p[0] !== 4 || a.length !== 16) return null;
  } catch (e) { return null; }
  // Kept exactly as the browser reported it: unsubscribe/test hash the same string to find the row.
  return { endpoint, p256dh: String(keys.p256dh), auth: String(keys.auth) };
}

/** One push. Returns the push service's HTTP status (0 = network error). */
export async function sendPush(env, sub, message, opts = {}) {
  const body = await encryptPayload(te.encode(JSON.stringify(message)), sub.p256dh, sub.auth);
  const headers = {
    Authorization: await vapidHeader(env, sub.endpoint),
    "Content-Encoding": "aes128gcm",
    "Content-Type": "application/octet-stream",
    TTL: String(opts.ttl || 86400),
    Urgency: "high",
  };
  if (opts.topic && /^[A-Za-z0-9_-]{1,32}$/.test(opts.topic)) headers.Topic = opts.topic;
  try {
    const res = await fetch(sub.endpoint, { method: "POST", headers, body });
    return res.status;
  } catch (e) { return 0; }
}

async function recordResult(env, sub, status) {
  const now = new Date().toISOString();
  if (status >= 200 && status < 300) {
    await env.DB.prepare("UPDATE push_subscriptions SET last_ok_at = ?, fail_count = 0, last_error = NULL WHERE id = ?").bind(now, sub.id).run();
  } else if (status === 404 || status === 410) {
    // The browser unsubscribed or the subscription expired: it can never receive again.
    await env.DB.prepare("DELETE FROM push_subscriptions WHERE id = ?").bind(sub.id).run();
  } else {
    await env.DB.prepare("UPDATE push_subscriptions SET fail_count = fail_count + 1, last_error = ? WHERE id = ?").bind(`${now} HTTP ${status}`, sub.id).run();
    // 403 = VAPID key mismatch for this endpoint; drop after repeated failures so the loop stays cheap.
    await env.DB.prepare("DELETE FROM push_subscriptions WHERE id = ? AND fail_count >= 50").bind(sub.id).run();
  }
}

export async function subscriptionId(endpoint) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", te.encode(endpoint)))].map(b => b.toString(16).padStart(2, "0")).join("");
}

// ---------- events → messages ----------
const short = (s, n) => { s = String(s == null ? "" : s).replace(/[ -]/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; };
const deviceFamily = detail => { const m = /^(iPhone|iPad|Android|Windows|Macintosh|Linux|جهاز)/.exec(String(detail || "")); return m ? m[1] : ""; };
export function activationMessage(row) {
  let after = {}; try { after = JSON.parse(row.after_json || "{}") || {}; } catch (e) { after = {}; }
  const at = Date.parse(after.activated_at || row.at) || Date.now();
  const hw = String(row.hardware_id || after.hardware_id || "");
  // Same tag format as the panel's in-page notice (activate:<lower-case hw>:<ms>), so a device that
  // shows both collapses them into one.
  return { t: "تفعيل جديد", b: `السيارة ${short(hw, 28)}`, tag: `activate:${hw.toLowerCase()}:${at}`.slice(0, 120), u: "/", at, kind: "activate" };
}
export function securityMessage(ev) {
  const at = Date.parse(ev.at) || Date.now();
  const fam = deviceFamily(ev.detail);
  if (ev.kind === "login_ok") return { t: "دخول جديد إلى لوحة الإدارة", b: fam ? `من جهاز ${fam}` : "من جهاز جديد", tag: `sec-${ev.id}`, u: "/", at, kind: ev.kind };
  if (ev.kind === "client_blocked") return { t: "تم حظر جهاز حاول دخول اللوحة", b: "5 كلمات مرور خاطئة — حظر 24 ساعة", tag: `sec-${ev.id}`, u: "/", at, kind: ev.kind };
  if (ev.kind === "login_failed") return { t: "محاولة دخول فاشلة إلى اللوحة", b: short(ev.detail || "", 60), tag: `sec-${ev.id}`, u: "/", at, kind: ev.kind };
  return null;
}
const PUSH_SECURITY_KINDS = ["login_ok", "client_blocked", "login_failed"];
const MAX_PER_RUN = 6;   // above this, one summary replaces the tail (a flood never becomes 50 buzzes)

async function claimCursor(env, key, fromValue, toValue) {
  const r = await env.DB.prepare("UPDATE push_state SET value = ?, updated_at = ? WHERE key = ? AND value = ?").bind(String(toValue), new Date().toISOString(), key, String(fromValue)).run();
  return !!(r && r.meta && r.meta.changes === 1);
}
async function cursor(env, key, table) {
  const row = await env.DB.prepare("SELECT value FROM push_state WHERE key = ?").bind(key).first();
  if (row) return Number(row.value) || 0;
  // First run ever: start at "now" — history is never pushed.
  const max = await env.DB.prepare(`SELECT COALESCE(MAX(id), 0) AS m FROM ${table}`).first();
  await env.DB.prepare("INSERT OR IGNORE INTO push_state (key, value, updated_at) VALUES (?, ?, ?)").bind(key, String(max.m), new Date().toISOString()).run();
  return null;
}

/** The cron body. Returns a small summary (also logged). */
export async function runPushCron(env) {
  if (!pushReady(env)) return { skipped: "not configured" };
  const summary = { activations: 0, security: 0, sent: 0, failed: 0, subs: 0 };
  const messages = [];
  // Activations: devices_audit is written by the activation Worker for every activation decision,
  // whichever path the car took (generator code, reserve block, closed block, re-activation).
  const a0 = await cursor(env, "audit_id", "devices_audit");
  if (a0 != null) {
    const top = await env.DB.prepare("SELECT COALESCE(MAX(id), 0) AS m FROM devices_audit").first();
    if (top.m > a0 && await claimCursor(env, "audit_id", a0, top.m)) {
      const rows = (await env.DB.prepare("SELECT id, hardware_id, at, after_json FROM devices_audit WHERE id > ? AND id <= ? AND action = 'activate' ORDER BY id LIMIT 100").bind(a0, top.m).all()).results || [];
      const seen = new Set();
      for (const r of rows) { const m = activationMessage(r); const k = String(r.hardware_id).toLowerCase(); if (seen.has(k)) continue; seen.add(k); messages.push(m); }
      summary.activations = seen.size;
    }
  }
  const s0 = await cursor(env, "security_id", "security_events");
  if (s0 != null) {
    const top = await env.DB.prepare("SELECT COALESCE(MAX(id), 0) AS m FROM security_events").first();
    if (top.m > s0 && await claimCursor(env, "security_id", s0, top.m)) {
      const rows = (await env.DB.prepare(`SELECT id, at, kind, detail FROM security_events WHERE id > ? AND id <= ? AND kind IN (${PUSH_SECURITY_KINDS.map(() => "?").join(",")}) ORDER BY id LIMIT 100`).bind(s0, top.m, ...PUSH_SECURITY_KINDS).all()).results || [];
      for (const r of rows) { const m = securityMessage(r); if (m) messages.push(m); }
      summary.security = rows.length;
    }
  }
  if (!messages.length) return summary;
  let batch = messages;
  if (messages.length > MAX_PER_RUN) {
    batch = messages.slice(0, MAX_PER_RUN - 1);
    const rest = messages.length - batch.length;
    batch.push({ t: "أحداث جديدة في لوحة الإدارة", b: `و${rest} أحداث أخرى — افتح اللوحة`, tag: `summary:${Date.now()}`, u: "/", at: Date.now(), kind: "summary" });
  }
  const subs = (await env.DB.prepare("SELECT id, endpoint, p256dh, auth FROM push_subscriptions ORDER BY created_at LIMIT 20").all()).results || [];
  summary.subs = subs.length;
  for (const sub of subs) {
    for (const m of batch) {
      let status = 0;
      try { status = await sendPush(env, sub, m); } catch (e) { console.log(`push send failed: ${e && e.message}`); }
      if (status >= 200 && status < 300) summary.sent++; else summary.failed++;
      await recordResult(env, sub, status);
      if (status === 404 || status === 410) break;
    }
  }
  await env.DB.prepare("INSERT INTO push_log (at, kind, ref, sent, failed) VALUES (?, 'cron', ?, ?, ?)").bind(new Date().toISOString(), `${summary.activations}a/${summary.security}s/${summary.subs}d`, summary.sent, summary.failed).run();
  await env.DB.prepare("DELETE FROM push_log WHERE at < ?").bind(new Date(Date.now() - 14 * 86400_000).toISOString()).run();
  return summary;
}

// ---------- admin routes (the caller has already passed Access + the admin session check) ----------
export async function handlePushRoute(req, env, p, ctx) {
  const json = ctx.json;
  if (req.method === "GET" && p === "/local/push/config") {
    const n = env.DB ? await env.DB.prepare("SELECT COUNT(*) AS n FROM push_subscriptions").first().catch(() => null) : null;
    return json(200, { enabled: pushReady(env), publicKey: env.VAPID_PUBLIC_KEY || null, subscriptions: n ? n.n : null });
  }
  if (!pushReady(env)) return json(503, { message: "push not configured" });
  if (req.method !== "POST") return json(404, { message: "not found" });
  const body = await ctx.readJsonBody(req);
  if (p === "/local/push/subscribe") {
    const sub = validSubscription(body);
    if (!sub) return json(400, { message: "bad subscription" });
    const id = await subscriptionId(sub.endpoint);
    const exists = await env.DB.prepare("SELECT 1 AS x FROM push_subscriptions WHERE id = ?").bind(id).first();
    if (!exists) {
      const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM push_subscriptions").first();
      if (n.n >= 20) return json(409, { message: "too many subscriptions (20); remove one first" });
    }
    const label = short(body.label || "", 40);
    const c = ctx.client;
    await env.DB.prepare(`INSERT INTO push_subscriptions (id, endpoint, p256dh, auth, label, ua, session_prefix, created_at, fail_count)
                          VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                          ON CONFLICT(id) DO UPDATE SET p256dh = excluded.p256dh, auth = excluded.auth, label = COALESCE(NULLIF(excluded.label, ''), push_subscriptions.label),
                                                        ua = excluded.ua, session_prefix = excluded.session_prefix, fail_count = 0, last_error = NULL`)
      .bind(id, sub.endpoint, sub.p256dh, sub.auth, label, c.ua, ctx.sessionPrefix, new Date().toISOString()).run();
    return json(200, { ok: true, created: !exists });
  }
  if (p === "/local/push/unsubscribe") {
    const endpoint = typeof body.endpoint === "string" ? body.endpoint.slice(0, 1024) : "";
    if (!endpoint) return json(400, { message: "endpoint required" });
    const r = await env.DB.prepare("DELETE FROM push_subscriptions WHERE id = ?").bind(await subscriptionId(endpoint)).run();
    return json(200, { ok: true, removed: r && r.meta ? r.meta.changes : 0 });
  }
  if (p === "/local/push/test") {
    const endpoint = typeof body.endpoint === "string" ? body.endpoint.slice(0, 1024) : "";
    const id = endpoint ? await subscriptionId(endpoint) : "";
    const sub = id && await env.DB.prepare("SELECT id, endpoint, p256dh, auth, last_test_at FROM push_subscriptions WHERE id = ?").bind(id).first();
    if (!sub) return json(404, { message: "this device is not subscribed" });
    if (sub.last_test_at && Date.now() - Date.parse(sub.last_test_at) < 15_000) return json(429, { message: "wait 15 s between tests" });
    await env.DB.prepare("UPDATE push_subscriptions SET last_test_at = ? WHERE id = ?").bind(new Date().toISOString(), id).run();
    const status = await sendPush(env, sub, { t: "إشعار تجريبي", b: "الإشعارات تعمل على هذا الجهاز حتى والمتصفح مغلق", tag: `test:${Date.now()}`, u: "/", at: Date.now(), kind: "test" }, { ttl: 600 });
    await recordResult(env, sub, status);
    await env.DB.prepare("INSERT INTO push_log (at, kind, ref, sent, failed) VALUES (?, 'test', ?, ?, ?)").bind(new Date().toISOString(), id.slice(0, 8), status >= 200 && status < 300 ? 1 : 0, status >= 200 && status < 300 ? 0 : 1).run();
    return json(status >= 200 && status < 300 ? 200 : 502, { ok: status >= 200 && status < 300, status });
  }
  return json(404, { message: "not found" });
}

// ---------- the service worker (served at /sw.js, scope /) ----------
// No fetch handler on purpose: the SW never sits between the page and Access/the Worker.
export const SW_SOURCE = `// Thabthaba admin — push notifications only (no fetch handler, no caching).
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('push', event => {
  let d = {};
  try { d = event.data ? event.data.json() : {}; } catch (e) { d = {}; }
  const str = (v, n) => typeof v === 'string' ? v.slice(0, n) : '';
  const title = str(d.t, 80) || 'تنبيه جديد — لوحة ذبذبة';
  const body = str(d.b, 200) || 'افتح اللوحة لرؤية التفاصيل';
  const tag = str(d.tag, 120) || ('thab-' + Date.now());
  const url = typeof d.u === 'string' && d.u.charAt(0) === '/' && d.u.charAt(1) !== '/' ? d.u : '/';
  event.waitUntil(self.registration.showNotification(title, {
    body, tag, renotify: true, dir: 'rtl', lang: 'ar',
    icon: '/icon-192.png?v=3', badge: '/icon-192.png?v=3',
    timestamp: typeof d.at === 'number' ? d.at : Date.now(), data: { url }
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = new URL((event.notification.data && event.notification.data.url) || '/', self.location.origin);
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const same = wins.find(w => new URL(w.url).origin === target.origin && !new URL(w.url).pathname.startsWith('/gen'));
    if (same) { await same.focus(); return; }
    await self.clients.openWindow(target.href);
  })());
});
// The push service rotated the subscription: the open panel re-registers it on its next load
// (subscribing needs the admin session, which a service worker does not hold).
self.addEventListener('pushsubscriptionchange', event => {
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(ws => ws.forEach(w => w.postMessage({ type: 'push-resubscribe' }))));
});
`;
