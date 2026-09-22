/**
 * «استعلام عن بُعد» — the admin half of remote diagnostics (controller repo:
 * Thabthaba Dashboard/docs/remote-diagnostics.md, worker/src/diag.js).
 *
 * Every route here runs only after worker.js has passed Access and isAdmin(). It talks to thab-voice's
 * AdminDiag RPC entrypoint (service binding DIAG_SVC — no public route exists) and is the ONLY place
 * that holds the request-signing private key (secret DIAG_SIGNING_JWK, ECDSA P-256). thab-voice
 * validates and stores; it can verify a signature but never produce one.
 *
 *   GET  /local/diag                 overview: cars seen polling, last 50 requests, test cars, switch
 *   GET  /local/diag/request?id=     one request with its per-car results
 *   GET  /local/diag/audit           audit log
 *   POST /local/diag/create          {target, probe, params, ttl_s, confirm_speak}
 *   POST /local/diag/cancel          {id}
 *   POST /local/diag/test-car        {hw, on}
 *   POST /local/diag/enabled         {enabled}
 *   GET  /local/diag/applogs?hw=|id= the AppLogs cars uploaded for «applog» requests (newest first)
 *   GET  /local/diag/applog?id=&hw=[&as=text]  one of them: the .log.gz as sent, or inflated text
 *
 * Nothing is logged except the action and ok/refused — never params or results.
 */

export const DIAG_SIG_CONTEXT = "thab-diag-v1\n";
let signingKey = null;
let signingKid = null;
let signingFrom = null;   // the secret text the cached key was imported from (never logged)

async function importSigningKey(env) {
  const raw = env.DIAG_SIGNING_JWK;
  if (!raw) return null;
  if (signingKey && signingFrom === raw) return { key: signingKey, kid: signingKid };
  const jwk = JSON.parse(raw);
  const kid = typeof jwk.kid === "string" ? jwk.kid : "k1";
  const { kid: _drop, ...material } = jwk;
  signingKey = await crypto.subtle.importKey("jwk", material, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  signingKid = kid;
  signingFrom = raw;
  return { key: signingKey, kid };
}

const b64u = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** Signs SIG_CONTEXT + p. Exported for the unit test. */
export async function signPayload(key, p) {
  return b64u(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(DIAG_SIG_CONTEXT + p)));
}

const ID_RE = /^[a-f0-9]{32}$/;
const HW_RE = /^[A-Za-z0-9._:-]{4,64}$/;

/** Returns a Response for /local/diag*, or null for any other path. */
export async function handleDiagRoute(req, env, { url, json, readJsonBody, actor }) {
  const p = url.pathname;
  if (p !== "/local/diag" && !p.startsWith("/local/diag/")) return null;
  if (!env.DIAG_SVC) return json(503, { message: "diag service binding missing", reason: "binding_missing" });
  const action = p.slice("/local/diag".length).replace(/^\//, "");
  const who = String(actor || "admin").slice(0, 120);
  let r;
  try {
    if (req.method === "GET" && action === "") r = await env.DIAG_SVC.overview();
    else if (req.method === "GET" && action === "request") {
      const id = String(url.searchParams.get("id") || "");
      if (!ID_RE.test(id)) return json(400, { message: "bad id" });
      r = await env.DIAG_SVC.requestDetail({ id });
    } else if (req.method === "GET" && action === "audit") r = await env.DIAG_SVC.auditLog({ limit: 200 });
    else if (req.method === "GET" && action === "applogs") {
      const id = url.searchParams.get("id"), hw = url.searchParams.get("hw");
      if (id != null && !ID_RE.test(id)) return json(400, { message: "bad id" });
      if (hw != null && !HW_RE.test(hw)) return json(400, { message: "bad car id" });
      r = await env.DIAG_SVC.applogList({ id: id ?? undefined, hw: hw ?? undefined, limit: 200 });
    } else if (req.method === "GET" && action === "applog") {
      // A download, not JSON: the file itself, named for the car and the request. thab-voice audits
      // every read with the actor.
      const id = String(url.searchParams.get("id") || ""), hw = String(url.searchParams.get("hw") || "");
      if (!ID_RE.test(id) || !HW_RE.test(hw)) return json(400, { message: "bad id" });
      const as = url.searchParams.get("as") === "text" ? "text" : "gzip";
      const g = await env.DIAG_SVC.applogGet({ id, hw, actor: who, as });
      if (!g || g.ok !== true) return json(g && g.reason === "binding_missing" ? 503 : g && g.reason === "gone" ? 410 : 404, { message: String((g && g.message) || "not found").slice(0, 160), reason: (g && g.reason) || null });
      const name = String(g.filename || "applog.log").replace(/[^A-Za-z0-9._-]/g, "_");
      const bodyOut = as === "text" ? g.text : Uint8Array.from(atob(g.gzip_b64), (c) => c.charCodeAt(0));
      console.log("diag applog download");
      return new Response(bodyOut, { status: 200, headers: {
        "Content-Type": as === "text" ? "text/plain; charset=utf-8" : "application/gzip",
        "Content-Disposition": `attachment; filename="${name}"`,
        "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
      } });
    }
    else if (req.method === "POST" && action === "create") {
      const body = await readJsonBody(req);
      const k = await importSigningKey(env);
      if (!k) return json(503, { message: "signing key not configured", reason: "not_configured" });
      const prep = await env.DIAG_SVC.prepare({
        target: body.target, probe: body.probe, params: body.params, ttl_s: body.ttl_s, confirm_speak: body.confirm_speak === true,
      });
      if (!prep || prep.ok !== true) { r = prep; }
      else {
        const pl = JSON.parse(prep.p);
        if (pl.kid !== k.kid) return json(503, { message: `signing key ${k.kid} does not match published key ${pl.kid}`, reason: "kid_mismatch" });
        const s = await signPayload(k.key, prep.p);
        r = await env.DIAG_SVC.commit({ p: prep.p, s, actor: who });
      }
      console.log("diag create", r && r.ok === true ? "ok" : "refused");
    } else if (req.method === "POST" && action === "cancel") {
      const body = await readJsonBody(req);
      r = await env.DIAG_SVC.cancel({ id: body.id, actor: who });
    } else if (req.method === "POST" && action === "test-car") {
      const body = await readJsonBody(req);
      r = await env.DIAG_SVC.setTestCar({ hw: body.hw, on: body.on === true, actor: who });
    } else if (req.method === "POST" && action === "enabled") {
      const body = await readJsonBody(req);
      r = await env.DIAG_SVC.setEnabled({ enabled: body.enabled === true, actor: who });
    } else return json(404, { message: "not found" });
  } catch (e) {
    console.log("diag rpc failed");
    return json(502, { message: "diag service unreachable", reason: "rpc_failed" });
  }
  if (!r || typeof r !== "object") return json(502, { message: "bad reply" });
  if (r.ok !== true) return json(r.reason === "binding_missing" || r.reason === "not_configured" ? 503 : 400, { message: String(r.message || "refused").slice(0, 160), reason: r.reason || null });
  return json(200, r);
}
