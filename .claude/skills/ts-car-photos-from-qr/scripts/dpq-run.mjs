// Run photos through DPQ-V3, wait for QA, retry a failed/QA-rejected project once, download finals.
// Usage: node dpq-run.mjs --template <uuid> --out <dir> [--client "<name>"] <photo> [<photo> ...]
// Writes <out>/dpq-result.json and <out>/<n>-<project-id>.jpg. Exit 2 if any final is missing,
// failed, QA-rejected after the retry, or not exactly the template size. LOOK at every output after.
// Costs money (OpenRouter) — run once per photo set; never loop it.
import fs from "fs";
import path from "path";
import { DPQ_BASE, dpqSecret, imageSize, parseArgs, mimeOf, exit } from "./lib.mjs";

const a = parseArgs(process.argv.slice(2));
const files = a._;
if (!a.template || !a.out || !files.length) { console.error("usage: node dpq-run.mjs --template <uuid> --out <dir> <photo>..."); process.exit(1); }
const H = { "x-dpq-gateway": dpqSecret() };

const tpls = await (await fetch(`${DPQ_BASE}/api/templates`, { headers: H })).json();
const tpl = tpls.find(t => t.id === a.template);
if (tpl) fs.mkdirSync(a.out, { recursive: true });
if (!tpl) { console.error("template not found; live templates:\n" + tpls.map(t => `${t.id} ${t.name} ${t.width}x${t.height}`).join("\n")); await exit(1); }
console.log(`template ${tpl.name} ${tpl.width}x${tpl.height}`);

const urls = [];
for (const f of files) {
  const fd = new FormData();
  fd.append("file", new Blob([fs.readFileSync(f)], { type: mimeOf(f) }), path.basename(f));
  fd.append("folder", "original");
  const r = await fetch(`${DPQ_BASE}/api/storage/upload`, { method: "POST", headers: H, body: fd });
  const j = await r.json();
  if (!r.ok || !j.url) throw new Error(`upload ${f} ${r.status} ${JSON.stringify(j)}`);
  urls.push(j.url);
}
const st = await fetch(`${DPQ_BASE}/api/projects/start`, {
  method: "POST", headers: { ...H, "Content-Type": "application/json" },
  body: JSON.stringify({ templateId: tpl.id, originalUrls: urls, clientName: a.client || undefined }),
});
const sj = await st.json();
if (!st.ok) throw new Error("start " + st.status + " " + JSON.stringify(sj));
const ids = sj.projects.map(p => p.id);
console.log("session", sj.sessionId, ids.join(","));

const fetchP = async (id) => (await fetch(`${DPQ_BASE}/api/projects/${id}?include=costs`, { headers: H })).json();
const terminal = (p) => p.status === "done" || p.status === "failed";
const bad = (p) => p.status === "failed" || p.review_passed === false;
const retried = new Set();
const retriedAt = {};
let rows;
for (let t = 0; t < 90; t++) {           // ~30 min at 20 s
  rows = await Promise.all(ids.map(fetchP));
  console.log(new Date().toISOString(), rows.map(r => `${r.project.short_id}:${r.project.status}`).join(" "));
  let waiting = false;
  for (const r of rows) {
    const p = r.project;
    if (!terminal(p)) { waiting = true; continue; }
    // A just-requested reprocess may not have flipped the status yet: give it 60 s.
    if (retried.has(p.id) && Date.now() - retriedAt[p.id] < 60000) { waiting = true; continue; }
    if (bad(p) && !retried.has(p.id)) {   // the one allowed retry, from Phase 1
      retried.add(p.id);
      retriedAt[p.id] = Date.now();
      await fetch(`${DPQ_BASE}/api/projects/${p.id}/reprocess`, { method: "POST", headers: { ...H, "Content-Type": "application/json" }, body: JSON.stringify({ fromPhase: 1 }) });
      console.log("retry", p.id);
      waiting = true;
    }
  }
  if (!waiting) break;
  await new Promise(r => setTimeout(r, 20000));
}

const out = { template: { id: tpl.id, name: tpl.name, w: tpl.width, h: tpl.height }, session: sj.sessionId, projects: [] };
let ok = true, usd = 0;
for (const [i, r] of rows.entries()) {
  const p = r.project;
  const cost = (r.costs || []).reduce((s, x) => s + Number(x.cost_usd ?? x.cost ?? 0), 0);
  usd += cost;
  const e = { source: files[i], id: p.id, short_id: p.short_id, status: p.status, review_passed: p.review_passed, review: p.review_result, retried: retried.has(p.id), usd: cost, url: p.processed_image_url };
  if (p.status === "done" && p.processed_image_url) {
    const buf = Buffer.from(await (await fetch(p.processed_image_url)).arrayBuffer());
    const sz = imageSize(buf);
    e.file = path.join(a.out, `${i + 1}-${p.id}.jpg`);
    fs.writeFileSync(e.file, buf);
    e.size = sz && `${sz.w}x${sz.h}`;
    e.exactSize = !!sz && sz.w === tpl.width && sz.h === tpl.height;
  }
  if (p.status !== "done" || p.review_passed === false || !e.exactSize) ok = false;
  out.projects.push(e);
}
out.totalUsd = usd;
fs.writeFileSync(path.join(a.out, "dpq-result.json"), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1));
await exit(ok ? 0 : 2);
