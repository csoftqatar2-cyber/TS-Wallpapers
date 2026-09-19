#!/usr/bin/env node
/**
 * store-catalog self-test — plain Node, no dependencies.
 *
 * Runs validateStoreCatalogText (and sameKeys through it) against a small
 * representative catalog: the two edits the admin site legitimately makes
 * (no change, one car id added to an app) must pass, and every malformed
 * input must throw. One line per case; exits non-zero on any mismatch.
 */
import { sameKeys, validateStoreCatalogText } from "../store-catalog.mjs";

const CURRENT = JSON.stringify({
  store: { name: "THABTHABA STORE" },
  cars: ["leopard", "tank500"],
  apps: [
    { packageName: "a.b", cars: ["leopard"] },
    { packageName: "c.d", cars: ["tank500"] },
  ],
  blockedDevices: [],
});
const currentObj = JSON.parse(CURRENT);
const clone = () => JSON.parse(JSON.stringify(currentObj));
const text = obj => JSON.stringify(obj);

let failed = 0;
function expectPasses(name, nextText, currentText = CURRENT) {
  try {
    const out = validateStoreCatalogText(nextText, currentText);
    if (out && typeof out === "object" && !Array.isArray(out)) console.log(`PASS  ${name}`);
    else { failed++; console.log(`FAIL  ${name} — returned a non-object`); }
  } catch (e) { failed++; console.log(`FAIL  ${name} — threw unexpectedly: ${e.message}`); }
}
function expectThrows(name, nextText, currentText = CURRENT) {
  try {
    validateStoreCatalogText(nextText, currentText);
    failed++; console.log(`FAIL  ${name} — did not throw`);
  } catch (e) { console.log(`PASS  ${name} — ${e.message}`); }
}

// passes
expectPasses("valid unchanged", CURRENT);
{
  const next = clone();
  next.apps[0].cars.push("tank500");   // one car id added to an app
  expectPasses("one car id added to an app", text(next));
}
// throws
expectThrows("invalid JSON", "{oops");
expectThrows("top-level array", text(["a.b", "c.d"]));
{
  const next = clone();
  next.extra = true;
  expectThrows("extra top-level key", text(next));
}
{
  const next = clone();
  delete next.blockedDevices;
  expectThrows("missing top-level key", text(next));
}
{
  const next = clone();
  next.apps = { "a.b": {} };
  expectThrows("apps not an array", text(next));
}
{
  const next = clone();
  delete next.apps[1].packageName;
  expectThrows("app without packageName", text(next));
}
{
  const next = clone();
  next.apps[1].packageName = "a.b";
  expectThrows("duplicate packageName", text(next));
}
expectThrows("current text invalid", CURRENT, "not json");

if (!sameKeys(JSON.parse(CURRENT), currentObj) || sameKeys({ a: 1 }, { a: 1, b: 2 })) {
  failed++; console.log("FAIL  sameKeys direct check");
} else {
  console.log("PASS  sameKeys direct check");
}

console.log(failed ? `\n${failed} case(s) FAILED` : "\nall cases passed");
process.exit(failed ? 1 : 0);
