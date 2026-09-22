// «استعلام عن بُعد» admin routes: signing + RPC wiring, mocked service binding. No network.
//   node --test test/diag-admin.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { handleDiagRoute, signPayload, DIAG_SIG_CONTEXT } from "../diag-admin.mjs";

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
async function readJsonBody(req) { return JSON.parse(await req.text()); }

const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const jwk = { ...(await crypto.subtle.exportKey("jwk", kp.privateKey)), kid: "k1" };

function svc(calls) {
  return {
    async prepare(b) { calls.push(["prepare", b]); return { ok: true, id: "a".repeat(32), p: JSON.stringify({ v: 1, kid: "k1", id: "a".repeat(32), probe: b.probe }) }; },
    async commit(x) { calls.push(["commit", x]); return { ok: true, id: "a".repeat(32) }; },
    async overview() { return { ok: true, cars: [] }; },
    async requestDetail(x) { calls.push(["detail", x]); return { ok: true }; },
    async setTestCar(x) { calls.push(["test", x]); return { ok: true }; },
  };
}
const call = (env, method, path, body) => {
  const url = new URL("https://admin.example" + path);
  const req = new Request(url, { method, body: body ? JSON.stringify(body) : undefined });
  return handleDiagRoute(req, env, { url, json, readJsonBody, actor: "owner@x" });
};

test("other paths are not handled", async () => {
  assert.equal(await call({}, "GET", "/local/diagnostics"), null);
  assert.equal(await call({}, "GET", "/local/voice-router"), null);
});

test("create signs exactly the prepared text with the private key, and the signature verifies", async () => {
  const calls = [];
  const env = { DIAG_SIGNING_JWK: JSON.stringify(jwk), DIAG_SVC: svc(calls) };
  const r = await call(env, "POST", "/local/diag/create", { probe: "tts", target: { kind: "all" }, params: {}, extra: "dropped" });
  assert.equal(r.status, 200);
  const [, prepared] = calls.find((c) => c[0] === "prepare");
  assert.deepEqual(Object.keys(prepared).sort(), ["confirm_speak", "params", "probe", "target", "ttl_s"]);
  const [, committed] = calls.find((c) => c[0] === "commit");
  assert.equal(committed.actor, "owner@x");
  const sig = Uint8Array.from(atob(committed.s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - committed.s.length % 4) % 4)), (c) => c.charCodeAt(0));
  assert.equal(sig.length, 64);
  assert.equal(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, kp.publicKey, sig, new TextEncoder().encode(DIAG_SIG_CONTEXT + committed.p)), true);
});

test("no signing key → 503, nothing committed; bad id → 400", async () => {
  const calls = [];
  const r = await call({ DIAG_SVC: svc(calls) }, "POST", "/local/diag/create", { probe: "tts", target: { kind: "all" } });
  assert.equal(r.status, 503);
  assert.equal(calls.length, 0);
  assert.equal((await call({ DIAG_SVC: svc(calls) }, "GET", "/local/diag/request?id=../../x")).status, 400);
});

test("refusals from thab-voice come back as 400 with the message", async () => {
  const env = { DIAG_SIGNING_JWK: JSON.stringify(jwk), DIAG_SVC: { async prepare() { return { ok: false, message: "unknown probe" }; } } };
  const r = await call(env, "POST", "/local/diag/create", { probe: "shell", target: { kind: "all" } });
  assert.equal(r.status, 400);
  assert.equal((await r.json()).message, "unknown probe");
});

test("signPayload output is base64url of 64 bytes", async () => {
  const k = await crypto.subtle.importKey("jwk", { ...jwk, kid: undefined }, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const s = await signPayload(k, "{}");
  assert.match(s, /^[A-Za-z0-9_-]{86}$/);
});

test("applog: list passes filters through; download returns the file with a safe name; refusals map", async () => {
  const calls = [];
  const gz = new Uint8Array([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3]);
  const env = { DIAG_SVC: {
    async applogList(x) { calls.push(["list", x]); return { ok: true, applogs: [] }; },
    async applogGet(x) {
      calls.push(["get", x]);
      if (x.id === "b".repeat(32)) return { ok: false, message: "log no longer stored", reason: "gone" };
      return x.as === "text" ? { ok: true, filename: "applog-l5.log", text: "line 1\nسطر 2\n" }
        : { ok: true, filename: 'applog-l5"; x=.log.gz', gzip_b64: btoa(String.fromCharCode(...gz)) };
    },
  } };
  const HW = "VIN-LGXCE4CB0R0000001";
  assert.equal((await call(env, "GET", `/local/diag/applogs?hw=${HW}`)).status, 200);
  assert.deepEqual(calls.pop(), ["list", { id: undefined, hw: HW, limit: 200 }]);
  assert.equal((await call(env, "GET", "/local/diag/applogs?hw=../x")).status, 400);
  assert.equal((await call(env, "GET", "/local/diag/applog?id=" + "a".repeat(32))).status, 400, "hw required");

  const r = await call(env, "GET", `/local/diag/applog?id=${"a".repeat(32)}&hw=${HW}`);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Content-Type"), "application/gzip");
  assert.equal(r.headers.get("Content-Disposition"), 'attachment; filename="applog-l5___x_.log.gz"');
  assert.deepEqual(new Uint8Array(await r.arrayBuffer()), gz);
  assert.equal(calls.pop()[1].actor, "owner@x");

  const t = await call(env, "GET", `/local/diag/applog?id=${"a".repeat(32)}&hw=${HW}&as=text`);
  assert.equal(await t.text(), "line 1\nسطر 2\n");
  assert.equal((await call(env, "GET", `/local/diag/applog?id=${"b".repeat(32)}&hw=${HW}`)).status, 410);
});
