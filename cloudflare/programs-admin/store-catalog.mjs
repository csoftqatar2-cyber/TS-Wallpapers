/**
 * store-catalog — the validation gate between the admin site's catalog editor
 * (/local/store-catalog PUT) and the live store catalog (catalog/apps.json) that
 * every car in the fleet reads.
 *
 * validateStoreCatalogText(nextText, currentText) guarantees, before anything is
 * written to R2: the next file parses as a JSON object (not an array, not a
 * primitive), it has exactly the same top-level keys as the current file (no key
 * may be added or dropped), it contains an `apps` array, and every entry in that
 * array is an object with a non-empty, trimmed, unique `packageName`. It returns
 * the parsed next object on success and throws an Arabic Error message otherwise.
 *
 * Pure functions, no I/O: imported by worker.js (Cloudflare, bundled by wrangler)
 * and by tools/store-catalog-selftest.mjs (Node) alike.
 */

export function sameKeys(a, b) {
  const aa = Object.keys(a).sort(), bb = Object.keys(b).sort();
  return aa.length === bb.length && aa.every((key, i) => key === bb[i]);
}

export function validateStoreCatalogText(nextText, currentText) {
  let next, current;
  try { next = JSON.parse(nextText); } catch (e) { throw new Error("الكتالوج الجديد ليس JSON صالحًا"); }
  try { current = JSON.parse(currentText); } catch (e) { throw new Error("تعذّرت قراءة الكتالوج الحالي"); }
  if (!next || typeof next !== "object" || Array.isArray(next) || !current || typeof current !== "object" || Array.isArray(current)) throw new Error("بنية الكتالوج العليا غير صالحة");
  if (!sameKeys(next, current)) throw new Error("لا يجوز إضافة مفاتيح عليا أو حذفها من الكتالوج");
  if (!Array.isArray(next.apps)) throw new Error("يجب أن يحتوي الكتالوج على مصفوفة apps");
  const seen = new Set();
  next.apps.forEach((app, i) => {
    const pkg = app && typeof app === "object" && !Array.isArray(app) ? String(app.packageName || "").trim() : "";
    if (!pkg) throw new Error(`التطبيق رقم ${i + 1} بلا packageName`);
    if (seen.has(pkg)) throw new Error(`packageName مكرر: ${pkg}`);
    seen.add(pkg);
  });
  return next;
}
