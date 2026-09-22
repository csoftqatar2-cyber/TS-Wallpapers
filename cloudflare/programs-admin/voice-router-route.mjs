// /local/voice-router* — the admin site's side of thab-voice's AdminRouter RPC entrypoint.
// Called by worker.js ONLY after isAdmin() passed. Kept in its own module so it can be unit-tested
// under plain Node (test/voice-router-route.test.mjs) without the page/icon imports of worker.js.
//
// Voice model router «إدارة الموديلات والمفاتيح»: Google keys, per-model limits, thinking flags, the
// Google model order, live usage and per-car provider attribution (Google vs OpenRouter).
// The router never returns a key value; api_key only travels browser → here → thab-voice on save.

export const VOICE_ROUTER_POST = {
  save_key: "saveKey", delete_key: "deleteKey", set_limit: "setLimit", delete_limit: "deleteLimit",
  reset_state: "resetState", set_thinking: "setThinking", set_settings: "setSettings", test_key: "testKey",
  set_google_order: "setGoogleOrder",
};

const intParam = (v, lo, hi) => {
  const s = String(v == null ? "" : v).trim();
  if (!/^\d{1,5}$/.test(s)) return null;
  const n = Number(s);
  return n >= lo && n <= hi ? n : null;
};

export async function voiceRouterRoute(req, env, url, p, { json, readJsonBody }) {
  if (!env.VOICE_ROUTER_SVC) return json(503, { message: "voice router binding missing", reason: "binding_missing" });
  const action = p.slice("/local/voice-router".length).replace(/^\//, "");
  let r;
  try {
    if (req.method === "GET" && action === "") r = await env.VOICE_ROUTER_SVC.snapshot();
    else if (req.method === "GET" && action === "compare") {
      const sw = String(url.searchParams.get("switch") || "").slice(0, 40);
      r = await env.VOICE_ROUTER_SVC.compare({ switch: /^[\d\-T:.Z+]{10,40}$/.test(sw) ? sw : null });
    } else if (req.method === "GET" && action === "usage") {
      // Fleet: totals, per-day series by provider, per model, one row per car. days=0 → since tracking began.
      r = await env.VOICE_ROUTER_SVC.usageFleet({ days: intParam(url.searchParams.get("days"), 0, 3650) ?? 0 });
    } else if (req.method === "GET" && action === "car_usage") {
      // One car: VIN, "VIN-…" or legacy hardware id.
      const car = String(url.searchParams.get("car") || "").trim();
      if (!/^[\w:.\-]{1,90}$/.test(car)) return json(400, { message: "bad car id" });
      r = await env.VOICE_ROUTER_SVC.usageCar({
        car,
        days: intParam(url.searchParams.get("days"), 0, 3650) ?? 0,
        recent: intParam(url.searchParams.get("recent"), 0, 500) ?? 50,
      });
    } else if (req.method === "POST" && VOICE_ROUTER_POST[action]) {
      const body = await readJsonBody(req);
      r = await env.VOICE_ROUTER_SVC[VOICE_ROUTER_POST[action]](body);
      console.log("voice-router", action, r && r.ok === true ? "ok" : "refused"); // never the body: it may carry a key
    } else return json(404, { message: "not found" });
  } catch (e) { console.log("voice-router rpc failed"); return json(502, { message: "voice service unreachable", reason: "rpc_failed" }); }
  if (!r || typeof r !== "object") return json(502, { message: "bad reply" });
  if (r.ok !== true) return json(r.reason === "binding_missing" ? 503 : 400, { message: String(r.message || "refused").slice(0, 160), reason: r.reason || null });
  return json(200, r);
}
