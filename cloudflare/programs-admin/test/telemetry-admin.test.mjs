// Controller telemetry on Cloudflare — admin routes + Supabase bridge, mocked bindings. No network.
//   node --test test/telemetry-admin.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { controllerRoute, telemetryRoute, runTelemetryBridge, ctrlOnCloudflare } from "../telemetry-admin.mjs";

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
async function readJsonBody(req) { const t = await req.text(); return t ? JSON.parse(t) : {}; }
async function passthrough(up) { return new Response(await up.text(), { status: up.status }); }
const post = (path, body) => new Request("https://admin.example" + path, { method: "POST", body: JSON.stringify(body || {}) });
const ctl = (env, name, body) => controllerRoute(post("/local/controller/" + name, body), env, name, { json, readJsonBody, passthrough });
const route = (env, method, path, body) => {
  const url = new URL("https://admin.example" + path);
  return telemetryRoute(new Request(url, { method, body: body ? JSON.stringify(body) : undefined }), env, url, { json, readJsonBody });
};

/** Replaces global fetch for one test; records Supabase RPC calls (name + parsed body). */
function mockFetch(t, reply = () => new Response("[]", { status: 200 })) {
  const calls = [];
  const orig = globalThis.fetch;
  globalThis.fetch = async (u, init) => { const name = String(u).split("/rpc/")[1]; const body = JSON.parse(init.body); calls.push({ name, body }); return reply(name, body); };
  t.after(() => { globalThis.fetch = orig; });
  return calls;
}
const SB = { CTRL_TELEMETRY_URL: "https://sb.example", CTRL_TELEMETRY_ANON: "anon", CONTROLLER_ADMIN_SECRET: "s3cret" };

test("every RPC name maps to the AdminTelemetry method without the prefix, same args, same JSON", async (t) => {
  mockFetch(t);
  const seen = [];
  const svc = new Proxy({}, { get: (_, m) => async (args) => { seen.push([m, args]); return m === "stats" ? { cars_total: 3 } : m === "set_note" ? undefined : [{ vin: "V1" }]; } });
  const env = { ...SB, TELEMETRY_SVC: svc, CTRL_MIRROR_CONFIG: "off" };
  let r = await ctl(env, "thab_admin_stats", { p_secret: "from-page" });
  assert.equal(r.status, 200); assert.deepEqual(await r.json(), { cars_total: 3 });
  assert.deepEqual(seen.pop(), ["stats", {}], "p_secret never reaches the method");
  r = await ctl(env, "thab_admin_events", { p_limit: 200, p_vin: null, p_kind: "voice", p_since_id: 5 });
  assert.deepEqual(await r.json(), [{ vin: "V1" }]);
  assert.deepEqual(seen.pop(), ["events", { p_limit: 200, p_vin: null, p_kind: "voice", p_since_id: 5 }]);
  r = await ctl(env, "thab_admin_set_note", { p_vin: "V1", p_note: "x" });
  assert.equal(r.status, 200); assert.equal(await r.text(), "null", "void RPC → null, which localPost reads as null");
  for (const n of ["crash_groups", "crashes", "gaps", "cars", "fuel_price_history", "voice_overlay_history"]) {
    assert.equal((await ctl(env, "thab_admin_" + n, {})).status, 200); assert.equal(seen.pop()[0], n);
  }
});

test("not allowed / missing method / thrown error keep the old status semantics", async (t) => {
  mockFetch(t);
  const env = { TELEMETRY_SVC: { async gaps() { throw new Error("unauthorized"); } } };
  assert.equal((await ctl(env, "thab_admin_forget", {})).status, 404);
  assert.equal((await ctl(env, "thab_ingest", {})).status, 404);
  assert.equal((await ctl(env, "thab_admin_stats", {})).status, 501);
  const r = await ctl(env, "thab_admin_gaps", {});
  assert.equal(r.status, 400); assert.equal((await r.json()).message, "unauthorized");
  const nb = { TELEMETRY_SVC: { async stats() { return { ok: false, reason: "binding_missing", message: "telemetry db missing" }; } } };
  assert.equal((await ctl(nb, "thab_admin_stats", {})).status, 503);
  // what AdminTelemetry really does without its D1 binding: adminCall THROWS "telemetry binding missing"
  const thrown = { TELEMETRY_SVC: { async cars() { throw new Error("telemetry binding missing"); } } };
  const tr = await ctl(thrown, "thab_admin_cars", {});
  assert.equal(tr.status, 503); assert.equal((await tr.json()).reason, "binding_missing");
});

test("CTRL_SOURCE=supabase (or no binding) is the old proxy, secret injected server-side", async (t) => {
  const calls = mockFetch(t, () => new Response('{"cars_total":1}', { status: 200 }));
  const svc = { async stats() { throw new Error("must not be called"); } };
  assert.equal(ctrlOnCloudflare({ TELEMETRY_SVC: svc }), true);
  assert.equal(ctrlOnCloudflare({ TELEMETRY_SVC: svc, CTRL_SOURCE: "supabase" }), false);
  assert.equal(ctrlOnCloudflare({}), false);
  const r = await ctl({ ...SB, TELEMETRY_SVC: svc, CTRL_SOURCE: "supabase" }, "thab_admin_stats", { p_secret: "evil" });
  assert.equal(r.status, 200); assert.equal(await r.text(), '{"cars_total":1}');
  assert.deepEqual(calls, [{ name: "thab_admin_stats", body: { p_secret: "s3cret" } }]);
  assert.equal((await ctl({}, "thab_admin_stats", {})).status, 503, "no binding and no secret");
});

test("config publishes are mirrored to Supabase for old cars; a restore mirrors as a publish of its entries", async (t) => {
  const calls = mockFetch(t, () => new Response('{"rev":9}', { status: 200 }));
  const env = { ...SB, TELEMETRY_SVC: {
    async fuel_price_publish(a) { return { rev: 4, price: a.p_price }; },
    async voice_overlay_restore(a) { return { rev: 7, restored_from: a.p_rev, entries: [{ phrase: "دريشة", canonical: "شباك" }], n: 1 }; },
    async voice_overlay_publish() { throw new Error("price out of sane range"); },
  } };
  let r = await ctl(env, "thab_admin_fuel_price_publish", { p_price: 2.1, p_currency: "QAR", p_grade: "super95", p_note: null });
  assert.deepEqual(await r.json(), { rev: 4, price: 2.1, mirror: "ok" });
  assert.deepEqual(calls.pop(), { name: "thab_admin_fuel_price_publish", body: { p_secret: "s3cret", p_price: 2.1, p_currency: "QAR", p_grade: "super95", p_note: null } });
  r = await ctl(env, "thab_admin_voice_overlay_restore", { p_rev: 3 });
  assert.equal((await r.json()).mirror, "ok");
  const m = calls.pop();
  assert.equal(m.name, "thab_admin_voice_overlay_publish");
  assert.deepEqual(m.body.p_entries, [{ phrase: "دريشة", canonical: "شباك" }]);
  // Cloudflare refused → nothing mirrored
  assert.equal((await ctl(env, "thab_admin_voice_overlay_publish", { p_entries: [] })).status, 400);
  assert.equal(calls.length, 0);
  r = await ctl({ ...env, CTRL_MIRROR_CONFIG: "off" }, "thab_admin_fuel_price_publish", { p_price: 2 });
  assert.equal((await r.json()).mirror, "skipped"); assert.equal(calls.length, 0);
});

test("usage / trips routes: binding, id validation, method mapping", async () => {
  const seen = [];
  const env = {
    USAGE: { async overview(a) { seen.push(["ov", a]); return { ok: true, cars: [] }; }, async car(a) { seen.push(["car", a]); return { ok: false, message: "not found" }; }, async forget(a) { seen.push(["forget", a]); return { ok: true }; } },
    // AdminTrips.car's real shape: the summary under `car`, the row lists beside it.
    TRIPS_SVC: { async overview(a) { seen.push(["tov", a]); return { ok: true, now: 1, cars: [{ hw: "HW123456", trips: 2 }] }; },
      async car(a) { seen.push(["trips", a]); return { ok: true, now: 1, car: { hw: a.hw, trips: 1 }, trips: [{ id: 1, start_ts: 1, dur_ms: 60000 }], charges: [], refuels: [] }; },
      async forget(a) { seen.push(["tforget", a]); return { ok: true }; } },
  };
  assert.equal(await route(env, "GET", "/local/usagex"), null);
  assert.equal((await route(env, "GET", "/local/usage")).status, 200);
  assert.deepEqual(seen.pop(), ["ov", { limit: 1000 }]);
  assert.equal((await route(env, "GET", "/local/usage/car?hw=%3Cscript%3E")).status, 400);
  assert.equal((await route(env, "GET", "/local/usage/car?hw=HW123456")).status, 404, "ok:false not found → 404 with message");
  assert.equal((await route(env, "POST", "/local/usage/forget", { hw: "HW123456" })).status, 200);
  assert.deepEqual(seen.pop(), ["forget", { hw: "HW123456" }]);
  assert.equal((await route(env, "GET", "/local/usage/forget")).status, 404);
  assert.equal((await route(env, "GET", "/local/trips")).status, 200);
  assert.deepEqual(seen.pop(), ["tov", { limit: 1000 }]);
  const tc = await route(env, "GET", "/local/trips/car?hw=VIN-LGXCE4CB8R0012345");
  assert.equal(tc.status, 200);
  const tj = await tc.json();
  assert.equal(tj.car.trips, 1); assert.equal(tj.trips[0].dur_ms, 60000, "passed through untouched");
  assert.deepEqual(seen.pop(), ["trips", { hw: "VIN-LGXCE4CB8R0012345" }]);
  assert.equal((await route(env, "POST", "/local/trips/forget", { hw: "VIN-LGXCE4CB8R0012345" })).status, 200);
  assert.deepEqual(seen.pop(), ["tforget", { hw: "VIN-LGXCE4CB8R0012345" }]);
  assert.equal((await route(env, "POST", "/local/trips/forget", { hw: "../x" })).status, 400);
  assert.equal(await route(env, "GET", "/local/applogs"), null, "AppLog lives under /local/diag/applog* (diag-admin.mjs)");
  assert.equal((await route({}, "GET", "/local/trips")).status, 503);
  const boom = { USAGE: { async overview() { throw new Error("x"); } } };
  assert.equal((await route(boom, "GET", "/local/usage")).status, 502);
});

function fakeDb(initial) {
  const kv = new Map(initial ? [["events_id", String(initial)]] : []);
  return { kv, prepare(sql) { return {
    bind(...b) { this.b = b; return this; },
    async first() { return kv.has("events_id") ? { v: kv.get("events_id") } : null; },
    async run() { assert.match(sql, /telemetry_bridge/); kv.set("events_id", this.b[0]); return {}; },
  }; } };
}

test("bridge: events from the watermark, ascending, chunked; watermark moves only after import; cars+crashes every 5 min", async (t) => {
  const events = Array.from({ length: 1200 }, (_, i) => ({ id: 2200 - i, rid: `r${2200 - i}`, vin: "V", kind: "voice" }));  // newest first, like the RPC
  const calls = mockFetch(t, (name) => new Response(JSON.stringify(name === "thab_admin_events" ? events : name === "thab_admin_cars" ? [{ vin: "V" }] : [{ rid: "c1" }]), { status: 200 }));
  const imported = [];
  // AdminTelemetry.import_rows({table, rows, keep_ids}) → {inserted, received} (worker/src/telemetry.js importRows)
  const env = { ...SB, DB: fakeDb(1000), TELEMETRY_SVC: { async import_rows(r) {
    imported.push(r); return { inserted: r.rows.length, received: r.rows.length };
  } } };
  const minute5 = 5 * 60000 * 1000;       // a scheduledTime on a 5-minute boundary
  const out = await runTelemetryBridge(env, minute5);
  assert.deepEqual(calls[0], { name: "thab_admin_events", body: { p_secret: "s3cret", p_limit: 2000, p_vin: null, p_kind: null, p_since_id: 1000 } });
  assert.deepEqual(imported.map((r) => [r.table, r.rows.length, r.keep_ids]), [["events", 500, false], ["events", 500, false], ["events", 200, false], ["cars", 1, false], ["crashes", 1, false]]);
  assert.equal(imported[0].rows[0].id, 1001, "oldest first");
  assert.equal(env.DB.kv.get("events_id"), "2200");
  assert.deepEqual(out.events, { sent: 1200, inserted: 1200 });
  // off-boundary minute: events only
  imported.length = 0; await runTelemetryBridge(env, minute5 + 60000);
  assert.ok(imported.every((r) => r.table === "events"));
});

test("bridge: a refused import keeps the watermark; off / no binding / no secret skip cleanly", async (t) => {
  mockFetch(t, () => new Response(JSON.stringify([{ id: 5, rid: "a" }]), { status: 200 }));
  // import_rows refuses by THROWING (the RPC error crosses the binding as a rejection)
  const env = { ...SB, DB: fakeDb(4), TELEMETRY_SVC: { async import_rows() { throw new Error("db down"); } } };
  await assert.rejects(runTelemetryBridge(env, 60000), /db down/);
  assert.equal(env.DB.kv.get("events_id"), "4");
  const odd = { ...env, TELEMETRY_SVC: { async import_rows() { return { ok: false }; } } };
  await assert.rejects(runTelemetryBridge(odd, 60000), /import_rows events/);
  assert.equal(env.DB.kv.get("events_id"), "4", "a reply without `received` never moves the watermark");
  assert.deepEqual(await runTelemetryBridge({ ...env, TELEMETRY_BRIDGE: "off" }), { skipped: "off" });
  assert.deepEqual(await runTelemetryBridge({ ...SB }), { skipped: "no_binding" });
  assert.deepEqual(await runTelemetryBridge({ TELEMETRY_SVC: env.TELEMETRY_SVC }), { skipped: "no_secret" });
  // no bridge table yet: since_id null, still imports (dedup by rid is the safety net)
  const calls = mockFetch(t, () => new Response("[]", { status: 200 }));
  const env2 = { ...SB, DB: { prepare() { return { bind() { return this; }, async first() { throw new Error("no such table"); }, async run() { throw new Error("no such table"); } }; } }, TELEMETRY_SVC: env.TELEMETRY_SVC };
  assert.deepEqual(await runTelemetryBridge(env2, 60000), {});
  assert.equal(calls[0].body.p_since_id, null);
});
