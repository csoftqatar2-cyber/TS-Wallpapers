// node --test test/  (from cloudflare/programs-admin) — /local/voice-router* handler, RPC stubbed.
import test from "node:test";
import assert from "node:assert/strict";
import { voiceRouterRoute } from "../voice-router-route.mjs";

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
const readJsonBody = async (req) => { const t = await req.text(); return t ? JSON.parse(t) : {}; };

function svc(overrides = {}) {
  const calls = [];
  const rec = (name, reply) => async (arg) => { calls.push({ name, arg }); return typeof reply === "function" ? reply(arg) : reply; };
  return {
    calls,
    snapshot: rec("snapshot", { ok: true, keys: [] }),
    compare: rec("compare", { ok: true }),
    usageFleet: rec("usageFleet", (a) => ({ ok: true, days: a.days, cars: [] })),
    usageCar: rec("usageCar", (a) => ({ ok: true, car: a.car, days: a.days, recent: [] })),
    setGoogleOrder: rec("setGoogleOrder", (a) => (Array.isArray(a.models) ? { ok: true, google_order: a.models } : { ok: false, message: "models must be a list" })),
    ...overrides,
  };
}
async function hit(env, method, path, body) {
  const url = new URL("https://admin.test" + path);
  const req = new Request(url, { method, body: body ? JSON.stringify(body) : undefined, headers: { "Content-Type": "application/json" } });
  const res = await voiceRouterRoute(req, env, url, url.pathname, { json, readJsonBody });
  return { status: res.status, body: await res.json() };
}

test("usage: fleet read with a sanitised days param", async () => {
  const S = svc();
  let r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/usage?days=30");
  assert.equal(r.status, 200);
  assert.deepEqual(S.calls.at(-1), { name: "usageFleet", arg: { days: 30 } });
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/usage?days=abc");
  assert.deepEqual(S.calls.at(-1).arg, { days: 0 });
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/usage?days=999999");
  assert.deepEqual(S.calls.at(-1).arg, { days: 0 });
});

test("car_usage: car id validated, days/recent bounded", async () => {
  const S = svc();
  let r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/car_usage?car=VIN-byd12EDCD&days=90&recent=20");
  assert.equal(r.status, 200);
  assert.deepEqual(S.calls.at(-1).arg, { car: "VIN-byd12EDCD", days: 90, recent: 20 });
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/car_usage?car=a%20b");
  assert.equal(r.status, 400);
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/car_usage");
  assert.equal(r.status, 400);
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/car_usage?car=hw:abc&recent=9999");
  assert.deepEqual(S.calls.at(-1).arg, { car: "hw:abc", days: 0, recent: 50 });
});

test("set_google_order is POST-only and passes refusals through as 400", async () => {
  const S = svc();
  let r = await hit({ VOICE_ROUTER_SVC: S }, "POST", "/local/voice-router/set_google_order", { models: ["gemini-3.5-flash-lite"] });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.google_order, ["gemini-3.5-flash-lite"]);
  r = await hit({ VOICE_ROUTER_SVC: S }, "POST", "/local/voice-router/set_google_order", { models: "x" });
  assert.equal(r.status, 400);
  r = await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router/set_google_order");
  assert.equal(r.status, 404);
  r = await hit({ VOICE_ROUTER_SVC: S }, "POST", "/local/voice-router/usage", {});
  assert.equal(r.status, 404, "reads are GET only");
});

test("existing actions unchanged; missing binding 503; RPC failure 502", async () => {
  const S = svc();
  assert.equal((await hit({ VOICE_ROUTER_SVC: S }, "GET", "/local/voice-router")).status, 200);
  assert.equal(S.calls.at(-1).name, "snapshot");
  assert.equal((await hit({}, "GET", "/local/voice-router/usage")).status, 503);
  const broken = svc({ usageFleet: async () => { throw new Error("boom"); } });
  const r = await hit({ VOICE_ROUTER_SVC: broken }, "GET", "/local/voice-router/usage");
  assert.equal(r.status, 502);
});
