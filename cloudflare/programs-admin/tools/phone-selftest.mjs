#!/usr/bin/env node
/**
 * Self-test for ../phone.mjs — plain Node, no dependencies.
 * Run: node cloudflare/programs-admin/tools/phone-selftest.mjs
 * Exits non-zero on any mismatch.
 */
import { normalizePhone, phoneCountry } from "../phone.mjs";

const CASES = [
  ["55512345", "+97455512345"],
  ["055512345", "+97455512345"],
  ["0 5551 2345", "+97455512345"],
  ["٥٥٥١٢٣٤٥", "+97455512345"],
  ["+97455512345", "+97455512345"],
  ["97455512345", "+97455512345"],
  ["00974 5551 2345", "+97455512345"],
  ["+966 50 123 4567", "+966501234567"],
  ["0501234567", null],
  ["+1 (415) 555-0132", "+14155550132"],
  ["+9665012345678901", null],
  ["12345", null],
  ["", null],
  ["abc", null],
  ["+", null],
];

const COUNTRY_CASES = [
  ["+97455512345", "قطر"],
  ["+966501234567", "السعودية"],
];

let failures = 0;

for (const [input, expected] of CASES) {
  const got = normalizePhone(input);
  const actual = got ? got.e164 : null;
  const ok = actual === expected;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"} normalizePhone(${JSON.stringify(input)}) → ${JSON.stringify(actual)} (expected ${JSON.stringify(expected)})`);
}

for (const [input, expected] of COUNTRY_CASES) {
  const actual = phoneCountry(input);
  const ok = actual === expected;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"} phoneCountry(${JSON.stringify(input)}) → ${JSON.stringify(actual)} (expected ${JSON.stringify(expected)})`);
}

console.log(failures === 0 ? `OK — ${CASES.length + COUNTRY_CASES.length} cases passed` : `${failures} case(s) FAILED`);
process.exit(failures === 0 ? 0 : 1);
