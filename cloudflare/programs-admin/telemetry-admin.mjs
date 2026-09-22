/**
 * Controller telemetry on Cloudflare — the admin site's half (controller repo:
 * Thabthaba Dashboard/reports/cloudflare-sync-audit-2026-09-21.md, worker/src/*).
 *
 * The owner, 2026-09-21: «انقل كل حاجة حرفيا على كلاود فلير ماعدا التفعيل». Everything the car
 * records now lives in thab-voice (D1 `thab-telemetry`, UsageHub, R2 `thab-applogs`) and is read
 * here through RPC entrypoints on service bindings — no public route exists for any of it.
 *
 *   POST /local/controller/thab_admin_<rpc>   → TELEMETRY_SVC.<rpc>(args)  (AdminTelemetry)
 *        The page keeps calling the old Supabase RPC names with the old args; the method is the
 *        name without the prefix and returns the SAME JSON, so control-panel.html is unchanged for
 *        every existing view. CTRL_SOURCE = "supabase" switches back to the old proxy in one deploy.
 *   GET  /local/usage                → USAGE.overview()          (AdminUsage: Quran/adhkar/prayer)
 *   GET  /local/usage/car?hw=        → USAGE.car({hw})
 *   POST /local/usage/forget {hw}    → USAGE.forget({hw})
 *   GET  /local/trips                → TRIPS_SVC.overview()      (AdminTrips: trips/charges/refuels)
 *   GET  /local/trips/car?hw=        → TRIPS_SVC.car({hw})       → {ok, now, car:summary, trips, charges, refuels}
 *   POST /local/trips/forget {hw}    → TRIPS_SVC.forget({hw})
 * AppLog uploads (diag probe «applog», R2 thab-applogs) are NOT here: they belong to the remote-query
 * flow that asks for them, so they are served by diag-admin.mjs (/local/diag/applogs, /local/diag/applog)
 * through AdminDiag.applogList / applogGet.
 *
 * Bridge for cars that have not updated yet (≤ 2.30.15 still post to Supabase qghyelhgusphkathgkom):
 * runTelemetryBridge() runs from the site's every-minute cron, reads the NEW rows through the same
 * admin RPCs the panel used, and hands them table by table to TELEMETRY_SVC.import_rows({table, rows,
 * keep_ids:false}), which dedups events/crashes by record id (rid) and merges car rows by taking the
 * larger counter. keep_ids stays false here: after the one-time backfill (worker/scripts/backfill.mjs,
 * which does keep Supabase's ids) D1 numbers new rows itself. It stops being needed once the version
 * histogram shows no car below 2.30.16; set TELEMETRY_BRIDGE = "off" then.
 *
 * Config the owner publishes (fuel price, voice glossary) is written to Cloudflare first and then
 * mirrored to Supabase while old cars remain, because those cars read their config from Supabase —
 * a glossary published only to D1 would silently never reach them.
 *
 * Every route here runs only after worker.js passed Access and isAdmin(). Nothing is logged except
 * the action and ok/failed — never args, rows or log contents.
 */

export const CTRL_RPC_ALLOW = /^thab_admin_(stats|cars|events|gaps|fuel_price_history|voice_overlay_history|voice_overlay_publish|voice_overlay_restore|fuel_price_publish|set_note|crashes|crash_groups)$/u;
/** Writes that old cars must also see (they read config from Supabase until they update). */
const MIRRORED = new Set(["fuel_price_publish", "voice_overlay_publish", "voice_overlay_restore"]);
export const HW_RE = /^[A-Za-z0-9._:-]{4,64}$/u;            // = thab-voice worker/src/diag.js HW_RE

/** Cloudflare unless the binding is missing or the owner rolled back with CTRL_SOURCE = "supabase". */
export const ctrlOnCloudflare = (env) => !!env.TELEMETRY_SVC && String(env.CTRL_SOURCE || "cloudflare").toLowerCase() !== "supabase";

function supabaseRpc(env, name, body) {
  return fetch(`${env.CTRL_TELEMETRY_URL}/rest/v1/rpc/${name}`, {
    method: "POST", headers: { "Content-Type": "application/json", apikey: env.CTRL_TELEMETRY_ANON, Authorization: `Bearer ${env.CTRL_TELEMETRY_ANON}` },
    body: JSON.stringify({ p_secret: env.CONTROLLER_ADMIN_SECRET, ...body }),
  });
}

const errText = (e) => String((e && e.message) || e || "error").replace(/^Error:\s*/u, "").slice(0, 200);

/**
 * /local/controller/<name>. Supabase answered a raised exception with HTTP 400 {message}; a thrown
 * RPC error is mapped to the same, so the page's error text path does not change. AdminTelemetry
 * signals a missing D1 binding by THROWING "telemetry binding missing" (it has no {ok:false} shape,
 * because its results are the RPCs' bare arrays/objects); that one becomes 503.
 */
export async function controllerRoute(req, env, name, { json, readJsonBody, passthrough }) {
  if (!CTRL_RPC_ALLOW.test(name)) return json(404, { message: "rpc not allowed" });
  const body = await readJsonBody(req); delete body.p_secret;
  if (!ctrlOnCloudflare(env)) {
    if (!env.CONTROLLER_ADMIN_SECRET) return json(503, { message: "controller secret not configured" });
    return passthrough(await supabaseRpc(env, name, body));
  }
  const method = name.slice("thab_admin_".length);
  let r;
  try {
    if (typeof env.TELEMETRY_SVC[method] !== "function") return json(501, { message: `telemetry method ${method} missing` });
    r = await env.TELEMETRY_SVC[method](body);
  } catch (e) {
    console.log("controller", method, "failed");
    const msg = errText(e);
    if (/binding missing/u.test(msg)) return json(503, { message: msg, reason: "binding_missing" });
    return json(400, { message: msg });
  }
  if (r && typeof r === "object" && !Array.isArray(r) && r.ok === false && r.reason === "binding_missing") {
    return json(503, { message: String(r.message || "telemetry binding missing").slice(0, 160), reason: r.reason });
  }
  if (MIRRORED.has(method)) {
    const m = await mirrorConfig(env, method, body, r);
    console.log("controller", method, "ok", "mirror", m);
    if (r && typeof r === "object" && !Array.isArray(r)) r = { ...r, mirror: m };
  }
  return json(200, r === undefined ? null : r);
}

/**
 * Best-effort copy of a config publish to Supabase for cars ≤ 2.30.15. Revision numbers differ
 * between the two stores, so a restore is mirrored as a publish of the restored entries.
 * Returns "ok" | "failed" | "skipped" — the Cloudflare write already succeeded either way.
 */
export async function mirrorConfig(env, method, body, result) {
  if (String(env.CTRL_MIRROR_CONFIG || "on").toLowerCase() === "off") return "skipped";
  if (!env.CONTROLLER_ADMIN_SECRET || !env.CTRL_TELEMETRY_URL) return "skipped";
  let name = `thab_admin_${method}`, args = body;
  if (method === "voice_overlay_restore") {
    if (!result || !Array.isArray(result.entries)) return "skipped";
    name = "thab_admin_voice_overlay_publish";
    args = { p_entries: result.entries, p_note: `رجوع إلى النسخة ${Number(body.p_rev) || "?"} (مرآة Cloudflare)` };
  }
  try {
    const res = await supabaseRpc(env, name, args);
    return res.ok ? "ok" : "failed";
  } catch (e) { return "failed"; }
}

/* ------------------------------------------------------------------ usage / trips */

const intParam = (v, lo, hi, dflt) => {
  const s = String(v == null ? "" : v).trim();
  if (!/^\d{1,6}$/u.test(s)) return dflt;
  const n = Number(s);
  return n >= lo && n <= hi ? n : dflt;
};

function okReply(json, r) {
  if (!r || typeof r !== "object") return json(502, { message: "bad reply" });
  if (r.ok !== true) {
    const st = r.reason === "binding_missing" ? 503 : r.message === "not found" ? 404 : 400;
    return json(st, { message: String(r.message || "refused").slice(0, 160), reason: r.reason || null });
  }
  return json(200, r);
}

/** Returns a Response for /local/usage*, /local/trips*, or null for any other path. */
export async function telemetryRoute(req, env, url, { json, readJsonBody }) {
  const m = /^\/local\/(usage|trips)(?:\/([a-z]+))?$/u.exec(url.pathname);
  if (!m) return null;
  const [, area, action = ""] = m;
  // Both areas have the same three calls on the same argument shapes (AdminUsage / AdminTrips).
  const svc = area === "usage" ? env.USAGE : env.TRIPS_SVC;
  const hwOf = (v) => { const s = String(v == null ? "" : v).trim(); return HW_RE.test(s) ? s : null; };
  try {
    if (!svc) return json(503, { message: `${area} binding missing`, reason: "binding_missing" });
    if (req.method === "GET" && action === "") return okReply(json, await svc.overview({ limit: intParam(url.searchParams.get("limit"), 1, 5000, 1000) }));
    if (req.method === "GET" && action === "car") {
      const hw = hwOf(url.searchParams.get("hw")); if (!hw) return json(400, { message: "bad car id" });
      return okReply(json, await svc.car({ hw }));
    }
    if (req.method === "POST" && action === "forget") {
      const hw = hwOf((await readJsonBody(req)).hw); if (!hw) return json(400, { message: "bad car id" });
      const r = await svc.forget({ hw });
      console.log(area, "forget", r && r.ok === true ? "ok" : "refused");
      return okReply(json, r);
    }
    return json(404, { message: "not found" });
  } catch (e) {
    console.log(area, action || "list", "rpc failed");
    return json(502, { message: `${area} service unreachable`, reason: "rpc_failed" });
  }
}

/* ------------------------------------------------------------------ bridge from Supabase (old cars) */

const EVENTS_PAGE = 2000;          // the RPC's own cap (thab_admin_events: least(p_limit, 2000))
const IMPORT_CHUNK = 500;          // import_rows refuses more than 500 rows per call (one bound JSON value ≤ 2 MB)
const SLOW_EVERY_MIN = 5;          // cars + crashes: every 5 minutes (crashes are rare, cars change slowly)

async function sbRows(env, name, args) {
  const res = await supabaseRpc(env, name, args);
  if (!res.ok) throw new Error(`${name} ${res.status}`);
  const j = await res.json();
  return Array.isArray(j) ? j : [];
}

async function readMark(env) {
  if (!env.DB) return null;
  try {
    const r = await env.DB.prepare("SELECT v FROM telemetry_bridge WHERE k = 'events_id'").first();
    return r && /^\d+$/u.test(String(r.v)) ? Number(r.v) : null;
  } catch (e) { return null; }          // table not migrated yet: every pull is a full page, dedup by rid keeps it correct
}
async function writeMark(env, id, nowIso) {
  if (!env.DB) return;
  try {
    await env.DB.prepare("INSERT INTO telemetry_bridge (k, v, at) VALUES ('events_id', ?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v, at = excluded.at")
      .bind(String(id), nowIso).run();
  } catch (e) { /* same as readMark */ }
}

/**
 * AdminTelemetry.import_rows({table, rows, keep_ids}) → {inserted, received}. It THROWS on a refusal
 * (unknown table, > 500 rows, no D1 binding); anything else without a numeric `received` is treated
 * the same, so the watermark never moves past rows that did not land.
 */
async function importChunks(env, table, rows) {
  const out = { sent: rows.length, inserted: 0 };
  for (let i = 0; i < rows.length; i += IMPORT_CHUNK) {
    const r = await env.TELEMETRY_SVC.import_rows({ table, rows: rows.slice(i, i + IMPORT_CHUNK), keep_ids: false });
    if (!r || typeof r !== "object" || !Number.isFinite(Number(r.received))) throw new Error(`import_rows ${table}: ${(r && r.message) || "bad reply"}`);
    out.inserted += Number(r.inserted) || 0;
  }
  return out;
}

/**
 * One cron tick. Events every minute from the id watermark; cars and crashes every 5 minutes.
 * The watermark (Supabase's thab_events.id, kept in ts-activation D1 telemetry_bridge) only moves
 * after import_rows accepted the rows, so a failed tick is retried whole.
 * Returns a small summary for the log (counts only).
 */
export async function runTelemetryBridge(env, scheduledTime = Date.now()) {
  if (String(env.TELEMETRY_BRIDGE || "on").toLowerCase() === "off") return { skipped: "off" };
  if (!env.TELEMETRY_SVC) return { skipped: "no_binding" };
  if (!env.CONTROLLER_ADMIN_SECRET || !env.CTRL_TELEMETRY_URL) return { skipped: "no_secret" };
  const out = {};
  const since = await readMark(env);
  const events = await sbRows(env, "thab_admin_events", { p_limit: EVENTS_PAGE, p_vin: null, p_kind: null, p_since_id: since });
  if (events.length) {
    // The RPC returns the NEWEST page above the watermark. A full page means more arrived in one
    // minute than it can return; the rows in between are only recoverable by re-running the backfill.
    if (events.length >= EVENTS_PAGE) console.log("telemetry bridge: full events page, re-run worker/scripts/backfill.mjs for the gap");
    const asc = events.slice().sort((a, b) => Number(a.id) - Number(b.id));
    out.events = await importChunks(env, "events", asc);
    await writeMark(env, Number(asc[asc.length - 1].id), new Date(scheduledTime).toISOString());
  }
  const minute = Math.floor(scheduledTime / 60000);
  if (minute % SLOW_EVERY_MIN === 0) {
    const cars = await sbRows(env, "thab_admin_cars", {});
    if (cars.length) out.cars = await importChunks(env, "cars", cars);
    const crashes = await sbRows(env, "thab_admin_crashes", { p_vin: null, p_limit: 100 });
    if (crashes.length) out.crashes = await importChunks(env, "crashes", crashes);
  }
  return out;
}
