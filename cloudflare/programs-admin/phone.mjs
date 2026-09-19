/**
 * Phone normalization for the programs-admin panel.
 *
 * Rule: no country code typed → assume Qatar (+974); a country code (+…, 00…, bare 974…) →
 * kept as typed. normalizePhone returns { e164, cc } in E.164 form, or null if the input
 * is not a recognizable phone number. phoneCountry maps the country code to its Arabic name.
 */
export const PHONE_COUNTRIES = {
  "974": "قطر", "966": "السعودية", "971": "الإمارات", "973": "البحرين", "965": "الكويت", "968": "عُمان",
  "20": "مصر", "962": "الأردن", "963": "سوريا", "964": "العراق", "961": "لبنان", "967": "اليمن",
  "249": "السودان", "970": "فلسطين", "212": "المغرب", "213": "الجزائر", "216": "تونس", "218": "ليبيا",
  "90": "تركيا", "44": "المملكة المتحدة", "1": "الولايات المتحدة/كندا", "91": "الهند", "92": "باكستان",
  "880": "بنغلاديش", "977": "نيبال", "94": "سريلانكا", "63": "الفلبين",
};

export function normalizePhone(raw) {
  let value = String(raw == null ? "" : raw).trim()
    .replace(/[٠-٩]/g, d => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)))
    .replace(/[۰-۹]/g, d => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)))
    .replace(/[\s\-./()]/g, "");
  if (value.startsWith("00")) value = `+${value.slice(2)}`;
  let e164;
  if (value.startsWith("+")) {
    if (!/^\+[1-9]\d{6,14}$/.test(value)) return null;
    e164 = value;
  } else {
    if (value.startsWith("0")) value = value.slice(1);
    if (/^\d{8}$/.test(value)) e164 = `+974${value}`;
    else if (/^974\d{8}$/.test(value)) e164 = `+${value}`;
    else return null;
  }
  const digits = e164.slice(1);
  const cc = Object.keys(PHONE_COUNTRIES).sort((a, b) => b.length - a.length).find(code => digits.startsWith(code)) || digits.slice(0, 3);
  return { e164, cc };
}

export function phoneCountry(phone) {
  const normalized = normalizePhone(phone);
  return normalized ? (PHONE_COUNTRIES[normalized.cc] || normalized.cc) : null;
}
