# مراجعة أمان الموقع — Gemini 3.8 (flash-high) — 2026-09-05

مراجعة قراءة فقط لـ cloudflare/programs-admin (worker.js, gen.html, control-panel.html, wrangler.toml) + wallpaper-upload/worker.js.

ما نُفِّذ من التقرير في نفس الليلة: 1، 2، 3، 4، 5، 6، 9. لم يُنفَّذ: 7 (تضمين السكربتات محلياً — SRI مثبَّت حالياً)، 8، 10، mTLS/Passkeys (قرار المالك).

---

# التقرير الأمني الشامل: مراجعة موقع إدارة الأسطول (Cloudflare Workers)

تمت مراجعة شيفرة النظام بالكامل وبشكل استباقي/عدائي (Adversarial Static Review) دون تعديل أي ملف وبدون تشغيل أوامر طرفية، مع أخذ التحديثات المعمارية الأخيرة في الحسبان (حصر الكتابة بالـ Worker، عزل الـ Refresh Token خلف HttpOnly Cookie، استخدام SRI وتضمين الترويسات الأمنية).

---

## أولاً: الإجابة الصريحة والمفصلة على الأسئلة الستة

### 1. هل يمكن لأي طلب الوصول إلى مسار يحقن أسراراً دون اجتياز Access وجلسة الأدمن معاً؟
**النتيجة: الترتيب سليم برمجياً داخل [worker.js](cloudflare/programs-admin/worker.js).**
- **الترتيب:** 
  1. المسارات العامة المستثناة صراحة هي الأيقونات والمانيفست فقط (السطور 223–229).
  2. يتم فحص `accessOk(req, env)` في السطر 230؛ أي طلب يفشل فيه يُرفض فوراً بكود `403`.
  3. يتم فحص الحظر ومعدل الطلبات في السطور 233–236.
  4. مسارات المصادقة العامة وتجديد الجلسة وصفحات العرض و `/local/ping` و `/local/catalog` و `/local/leo-latest` تقع بين السطور 237–255 (ولا يحقن أيٌّ منها أسراراً؛ مسار ping يعيد قيم `boolean` فقط).
  5. في السطر 257: يقع الجدار الحاسم: `if (!(await isAdmin(req, env))) return json(401, ...)`.
  6. جميع مسارات حقن الأسرار (`/local/store/*`, `/local/controller/*`, `/local/tslink*`, `/local/leo*`, `/local/write/*`) تقع حصراً **بعد** السطر 257.
- **تنبيه:** إذا أُزيل المتغيران `ACCESS_TEAM_DOMAIN` أو `ACCESS_AUD` من الإعدادات، فإن دالة `accessOk` تعيد `true` تصميماً (السطر 81)، لتبقى جلسة الأدمن هي الحارس الوحيد.

---

### 2. هل التحقق من JWT الخاص بـ Cloudflare Access سليم؟
**النتيجة: التحقق من التوقيع الرياضي سليم، لكن توجد ثغرة في التحقق من هوية المستخدم وثغرة توافر عند تدوير المفاتيح.**
- **Alg Confusion:** محمي تماماً؛ السطر 87 يفرض `header.alg === "RS256"`، والسطور 77 و94 تقيّد المفاتيح المستوردة بنوع `RSA` وخوارزمية `RSASSA-PKCS1-v1_5` و `SHA-256`. محاولات التبديل إلى `none` أو `HS256` مستحيلة.
- **Audience Array:** سليم؛ السطور 89–90 تتعامل مع `aud` سواء كان نصاً مفرداً أو مصفوفة نصوص وتتحقق من اشتماله على `env.ACCESS_AUD`.
- **Exp & Iss:** سليم؛ يتم التحقق من عدم انتهاء الصلاحية بالنسبة لزمن السيرفر الحالي، ومطابقة `iss` بدقة للرابط `https://${env.ACCESS_TEAM_DOMAIN}`.
- **ثغرة Cert Cache & Key Rollover:** الكاش في السطر 73 يحفظ المفاتيح لمدّة ساعة كاملة (`3600_000 ms`). إذا قامت Cloudflare بتدوير المفاتيح وأصدرت توكناً بـ `kid` جديد، لا تقوم دالة `accessKeys` بجلب المفاتيح الحديثة طالما لم تنتهِ الساعة، مما يؤدي إلى رفض دخول المالك وإغلاق الموقع أمامه لمدة تصل إلى 60 دقيقة.
- **ثغرة عدم فحص الإيميل داخل الـ Payload:** لا تفحص دالة `accessOk` إطلاقاً حقل `payload.email` داخل الـ JWT، وتعتمد دالة `clientOf` على ترويسة المتصفح `cf-access-authenticated-user-email` التي يمكن تزييفها في حال الاتصال المباشر بنطاق `workers.dev`.

---

### 3. هل نظام حماية تسجيل الدخول (Login Guard) قابل للتجاوز؟
- **تزييف الـ IP عبر الترويسات:** **غير ممكن عبر Cloudflare Edge**؛ لأن خوادم Cloudflare تطغى على ترويسة `cf-connecting-ip` وتضبطها بعنوان العميل الحقيقي. ولكن في شبكات الجوال (IPv6)، يملك المهاجم نطاق `/64` يمنحه ملايين العناوين المتغيرة التي تتجاوز حظر الـ IP الفردي.
- **سباق الطلبات المتوازية (Race Condition):** **محمي ومصمم بذكاء**؛ السطر 151 يُسجل المحاولة أولاً في D1 بقيمة `ok = 0` قبل الاستعلام عن العدد وققبل الاتصال بـ Supabase. إذا أُرسلت 10 طلبات متزامنة، تسجل جميعها في D1، ولن يمر إلى Supabase سوى أول 5 طلبات كحد أقصى.
- **حظر المالك نفسه (Self-DoS):** **متحقق وثغرة خطيرة جداً!** إذا أخطأ المالك 5 مرات، أو قام مهاجم بإرسال 5 محاولات فاشلة على عنوان IP المالك، يُدرج الـ IP في `blocked_clients`. وبما أن فحص `isBlocked` يقع في السطر 235 قبل توجيه المسارات، يُحرم المالك من فتح الموقع، ومن توليد الأكواد في السيارة، ومن تجديد جلسته، وحتى من استخدام زر فك الحظر `/local/security/unblock` لأنه يقع خلف فحص الحظر!
- **فشل D1:** دالة `isBlocked` تطبق مبدأ **Fail-Closed** عند الخطأ وتُرجع `503`. لكن في `handleLogin`، لا توجد كتلة `try/catch` حول عمليات D1، فإذا تعطلت D1 ينهار الـ Worker بخطأ 500 غير معالج.

---

### 4. هل الـ CSP والترويسات محكمة؟ وما هي كلفة الاعتماد على الـ CDN وكيفية معالجتها؟
- **الترويسات:** قوية وممتازة (`HSTS`, `nosniff`, `DENY`, `no-store`, `no-referrer`, `Permissions-Policy`).
- **الـ CSP:** يتم توليد Nonce عشوائي مشفر (128 بت) لكل استجابة، ويُستبدل في كود الـ HTML بنجاح.
- **كلفة وثغرة الـ CDN:**
  تسمح سياسة `script-src` بالنطاقين بالكامل: `https://cdn.jsdelivr.net` و `https://cdnjs.cloudflare.com`.
  - **الكلفة الأمنية:** نطاق `cdn.jsdelivr.net` هو بيئة مفتوحة تخدم أي ملف من GitHub أو npm. في حال وجود أي ثغرة حقن أو تضمين مستقبلي، يستطيع المهاجم استدعاء أي سكربت خبيث مرفوع على حسابه في GitHub عبر jsDelivr، مما **يُفرغ سياسة CSP من قوتها الدفاعية**.
  - **الحل الجذري:** بما أن موقع الأدمن يُحزم عبر Wrangler ويخدم من Worker، يجب تنزيل ملفات `sweetalert2.all.min.js` و `supabase.js` وخطوط FontAwesome وتضمينها محلياً داخل الـ Worker، وحذف نطاقات الـ CDN بالكامل من `script-src` والاكتفاء بـ `'self' 'nonce-${nonce}'`.

---

### 5. هل توجد ثغرات في `gen.html` أو في تصيير `innerHTML` داخل لوحة التحكم؟
- **في [gen.html](cloudflare/programs-admin/gen.html):**
  - تم التخلص تماماً من تخزين الـ Token في `localStorage`؛ التوكن يعيش في الذاكرة فقط ويُجدد عبر الكوكي المحمي `HttpOnly`.
  - تصيير `used_by` و `serial` محمي تماماً بدالة الهروب `esc()`.
  - الحافظة (Clipboard) تقوم فقط بنسخ نص الـ Serial الآمن.
- **في [control-panel.html](cloudflare/programs-admin/control-panel.html):**
  - تم استخدام دالة التعقيم `esc()` بدقة عبر كافة الجداول والتفاصيل (`names`, `serials`, `hw`, `crashes`, `events`, `urls`).
  - الروابط تفحص البروتوكول عبر التعبير النمطي `wallpaperUrl` لمنع هجمات `javascript:`.
  - تخزين الـ Refresh Token في `localStorage` ملغى عند العمل عبر الـ Worker (يُحذف في السطر 911 ما دامت الجلسة خلف الكوكي).

---

### 6. كيف نحقق "أقصى حماية ممكنة مع عدم مطالبة المالك بالدخول نهائياً على أجهزته الأربعة"؟
الترتيب التنازلي حسب العائد الأمني مقابل الجهد:
1. **شهادات الأجهزة المتبادلة (mTLS / Client Certificates via Cloudflare Zero Trust) [عائد أقصى / جهد منخفض]:**
   توليد 4 شهادات رقمية وتثبيتها في مخزن الشهادات لأجهزة المالك الأربعة. تفرض Cloudflare Access مطابقة الشهادة عند مصافحة الـ TLS. النتيجة: لن يدخل الموقع سوى هذه الأجهزة، مع بقاء التجربة صامتة تماماً دون أي نافذة منبثقة أو كلمة مرور.
2. **استخدام Passkeys / WebAuthn في Cloudflare Access [عائد عالي / جهد منخفض جداً]:**
   إلغاء رمز البريد (OTP) والاعتماد على بصمة الوجه/الإصبع المدمجة بالأجهزة مع إبقاء جلسة Access على 30 يوماً.
3. **تصحيح ثغرة الإلغاء الفوري للجلسات في الـ Worker [عائد حرج / جهد 5 دقائق]:**
   منع إلغاء جلسة الجهاز في D1 عند حدوث انقطاع مؤقت لخدمات Supabase.
4. **ربط الجلسة في D1 بنطاق الشبكة والبصمة الخفيفة (Device Fingerprint) [عائد متوسط / جهد متوسط]:**
   ربط معرّف `ts_dev` برقم نظام التشغيل وعائلة المتصفح في D1 لمنع نقل الكوكي المسروق إلى بيئة مختلفة.

---

## ثانياً: الثغرات الأمنية مرتبة حسب الخطورة

```
[حرجة جداً] 1. الإلغاء القسري لجلسات المالك بسبب أخطاء Supabase المؤقتة (DoS on Device Sessions)
[حرجة]      2. ثغرة Fail-Open في فحص مفتاح الكتابة بـ upload-worker.js
[عالية]     3. الحظر الذاتي للمالك وتعطيل قدرته على فك الحظر (Owner Self-Lockout)
[عالية]     4. غياب فحص البريد داخل Access JWT وتصديق ترويسة البريد غير الموثوقة
[عالية]     5. إمكانية الحذف الشامل لجداول الخلفيات عبر DELETE دون شرط في PostgREST
[متوسطة]    6. انقطاع الخدمة المؤقت (60 دقيقة) عند تدوير مفاتيح Cloudflare Access
[متوسطة]    7. تقويض حماية الـ CSP بالسماح بكامل نطاق jsDelivr
[منخفضة]    8. ترتيب دمج الأسرار الخادمي في RPC Supabase
[منخفضة]    9. تراكم سجلات محاولات الدخول في D1 بدون تنظيف
[منخفضة]    10. غياب التحقق من انتهاء الصلاحية الزمني لجلسات D1
```

---

### 1. الإلغاء القسري لجلسات المالك عند حدوث اضطراب مؤقت في Supabase
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L126-L128)، داخل `handleSession`:
  ```javascript
  const data = await supabaseRefresh(env, row.refresh_token);
  if (!data) { await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE id = ?").bind(id).run(); return json(401, { message: "refresh failed" }); }
  ```
  وفي دالة `supabaseRefresh` (السطر 116)، إذا فشل طلب الشبكة، أو حدث بطء، أو واجهت Supabase خطأ خادمي (502 Gateway Timeout / 503 Service Unavailable)، ترجع الدالة `null`. يقوم الكود مباشرة بوسم جلسة المالك في D1 بأنها **ملغاة نهائياً** (`revoked = 1`).
- **سيناريو الاستغلال / الأثر:**
  أي انقطاع للإنترنت لثانية واحدة في سيارة المالك، أو صيانة خوادم Supabase المؤقتة، يؤدي فوراً إلى تدمير جلسة الجهاز ومسح الكوكي نهائياً، وإجبار المالك على كتابة كلمة المرور المعقدة من جديد، وهو ما يناقض تماماً متطلب العمل الأساسي.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js)، تعديل `supabaseRefresh` لتعيد كود الحالة، وإلغاء الجلسة حصراً إذا كان الرد `400 Bad Request` (مما يعني أن التوكن غير صالح أو تم إبطاله بالفعل):
  ```javascript
  // في worker.js: استبدال السطور 116-128
  async function supabaseRefresh(env, refreshToken) {
    try {
      const res = await fetch(`${env.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, {
        method: "POST",
        headers: { apikey: env.SUPABASE_ANON, "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: refreshToken })
      });
      if (res.status === 400) return { fatal: true };
      if (!res.ok) return { fatal: false };
      const data = await res.json().catch(() => null);
      return data && data.access_token ? { fatal: false, data } : { fatal: false };
    } catch (e) {
      return { fatal: false };
    }
  }

  // في handleSession:
  const ref = await supabaseRefresh(env, row.refresh_token);
  if (ref.fatal) {
    await env.DB.prepare("UPDATE device_sessions SET revoked = 1 WHERE id = ?").bind(id).run();
    return new Response(JSON.stringify({ message: "session expired" }), { status: 401, headers: { "Content-Type": "application/json", "Set-Cookie": setCookie(id, true), ...SEC_HEADERS } });
  }
  if (!ref.data) return json(503, { message: "auth upstream temporarily unavailable" });
  const data = ref.data;
  ```

---

### 2. تجاوز فحص مفتاح الكتابة في `upload-worker.js` (Fail-Open)
- **الضعف بدقة:**
  في [upload-worker.js](cloudflare/programs-admin/upload-worker.js#L66):
  ```javascript
  if (env.WRITE_KEY && (req.headers.get('x-write-key') || '') !== env.WRITE_KEY) return json({ error: 'write key required' }, 403);
  ```
  الشرط مشروط بوجود المتغير `env.WRITE_KEY`. إذا نسي المطور ضبط هذا المتغير السري في Cloudflare Dashboard للـ `upload-worker`، فإن الشرط يتخطى الفحص بالكامل ويفشل بنمط مفتوح (Fails Open).
- **سيناريو الاستغلال:**
  إذا سُرق توكن الأدمن الخاص بـ Supabase، يستطيع المهاجم إرسال طلبات رفع أو حذف مباشرة إلى نطاق `https://ts-wallpapers-upload.tsdash-qatar.workers.dev/` بدون الحاجة للمرور عبر Worker لوحة التحكم وبدون تقديم ترويسة `x-write-key`، مما يمكنه من استبدال ملفات APK الخاصة بالتطبيقات في R2.
- **الحل البرمجي الدقيق:**
  في [upload-worker.js](cloudflare/programs-admin/upload-worker.js#L66): فرض الفحص بحزم (Fail-Closed):
  ```javascript
  // السطر 66 في upload-worker.js
  if (!env.WRITE_KEY || (req.headers.get('x-write-key') || '') !== env.WRITE_KEY) {
    return json({ error: 'valid write key required' }, 403);
  }
  ```

---

### 3. الحظر الذاتي التلقائي للمالك (Self-DoS) ومنعه من فك حظر نفسه
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L233-L235)، فحص `isBlocked(env, clientOf(req).ip)` يسبق كل شيء:
  ```javascript
  let blocked = false;
  try { blocked = await isBlocked(env, clientOf(req).ip); } catch (e) { return json(503, { message: "guard unavailable" }); }
  if (blocked) return new Response("blocked", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });
  ```
  عند حظر الـ IP، يتم إرجاع 403 لجميع المسارات. مسار فك الحظر `/local/security/unblock` يقع في السطر 303، ومسار تجديد الجلسة بالكوكي `/local/auth/session` يقع في السطر 238، وكلاهما خلف فحص الحظر.
- **سيناريو الاستغلال:**
  يستطيع أي مهاجم يعرف الـ IP الثابت لمكتب المالك، أو في حال قيام المالك بإدخال كلمة المرور خطأ 5 مرات على شبكة هاتفه، حظر الـ IP لمدة 24 ساعة. لن يتمكن المالك من فتح لوحة التحكم، ولا تشغيل `/gen`، ولن يتمكن حتى من استخدام زر إلغاء الحظر، لأن الـ Worker يقطع اتصاله في البوابة الخارجية.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js):
  1. استثناء الجلسات المسجلة الشرعية مسبقاً (التي تحمل كوكي `ts_dev` صالح ومسجل في D1) من حظر الـ IP العام.
  2. السماح للمشرفين المصرحين بتشغيل مسار فك الحظر:
  ```javascript
  // في worker.js قبل فحص الحظر في السطر 233:
  const hasValidDevCookie = cookieOf(req, DEV_COOKIE);
  // لا يتم حظر الـ IP إذا كان الطلب يملك جلسة جهاز سارية وموثقة في D1
  if (blocked && !hasValidDevCookie) {
    return new Response("blocked", { status: 403, headers: { "Content-Type": "text/plain; charset=utf-8", ...SEC_HEADERS } });
  }
  ```

---

### 4. إهمال فحص البريد الإلكتروني في الـ JWT وقبول ترويسة مزيفة
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L80-L97)، دالة `accessOk` تفحص صحة التوقيع والـ AUD فقط. لا تتحقق من أن المستخدم صاحب التوكن هو أحد بريدي المالك المسموح لهما. وفي السطر 101:
  ```javascript
  const clientOf = req => ({ ip: req.headers.get("cf-connecting-ip") || "?", email: req.headers.get("cf-access-authenticated-user-email") || null, ua: ... });
  ```
  تعتمد الدالة على ترويسة الطلب المباشرة لتحديد البريد في سجلات الأمان والأجهزة بدلاً من استخراجها من الـ Payload الموقع رقمياً.
- **سيناريو الاستغلال:**
  إذا كان لدى المالك تطبيقات أخرى داخل نفس فريق Cloudflare Access بنفس الـ AUD أو حصل مستخدم مصرح له في مؤسسة المالك على توكن لتطبيق آخر، فإن الـ Worker سيقبله كأدمن. وإذا تم الاتصال بـ `workers.dev` مباشرة يمكن إرسال ترويسة بريد مزيفة لتسجيل أحداث مغلوطة في جدول `security_events`.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js):
  1. التحقق من `payload.email` ومطابقته للبريد المعتمد للمالك داخل `accessOk`.
  2. تمرير البريد المستخرج من التوكن المفحوص وليس من ترويسة الطلب.
  ```javascript
  // في السطر 90 من worker.js:
  const allowedEmail = env.ADMIN_EMAIL || "admin@tswallpapers.app";
  if (payload.email && payload.email.toLowerCase() !== allowedEmail.toLowerCase()) return false;
  req.verifiedEmail = payload.email; // تمرير البريد الموثق
  ```

---

### 5. إمكانية الحذف الجماعي لجداول الخلفيات عبر `DELETE` غير المشروط
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L277):
  ```javascript
  (["POST", "DELETE"].includes(method) && /^(wallpapers|wallpaper_hides)(\?.*)?$/.test(path))
  ```
  التعبير النمطي يسمح بمسار `path = "wallpapers"` أو `path = "wallpaper_hides"` دون علامة استفهام أو معاملات تصفية إطلاقاً.
- **سيناريو الاستغلال:**
  في بروتوكول PostgREST (Supabase REST API)، إرسال طلب `DELETE` على المسار `/rest/v1/wallpapers` بدون تذييل استعلام (Query String) يقوم **بحذف كافة صفوف الجدول فوراً (Truncate/Wipe)**. إذا أرسل متصفح مخترق أو سكربت خاطئ هذا الطلب، سيتم تفريغ مكتبة الخلفيات واستثناءات السيارات بالكامل بطلب واحد.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js#L277): فرض وجود معرّف شرطي دقيق في عمليات الحذف:
  ```javascript
  // استبدال السطر 277 في worker.js:
  (method === "DELETE" && /^(wallpapers\?(id|url)=eq\.[^&]+|wallpaper_hides\?(wallpaper_id|hardware_id)=eq\.[^&]+)$/.test(path)) ||
  (method === "POST" && /^(wallpapers|wallpaper_hides)(\?.*)?$/.test(path)) ||
  ```

---

### 6. انقطاع الخدمة المؤقت عند تدوير مفاتيح Cloudflare Access (Key Rollover)
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L72-L79):
  ```javascript
  async function accessKeys(team) {
    if (Date.now() - jwks.at < 3600_000 && jwks.keys.length) return jwks.keys;
    ...
  ```
  إذا لم يجد الـ Worker مفتاح الـ `kid` الموجود في التوكن الجديد، فإنه يعيد `false` مباشرة طالما أن عمر الكاش أقل من ساعة.
- **سيناريو الاستغلال / الأثر:**
  عند قيام Cloudflare تلقائياً بتدوير شهادات الفريق، سيتم طرد المالك من الموقع وظهور رسالة `Access required` ولن يتمكن من الدخول حتى انتهاء مؤقت الساعة ليعاد جلب المفاتيح.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js#L91-L93): إذا لم يُعثر على `header.kid`، إجبار دالة `accessKeys` على إعادة التحميل فوراً:
  ```javascript
  let keys = await accessKeys(env.ACCESS_TEAM_DOMAIN);
  let jwk = keys.find(k => k.kid === header.kid);
  if (!jwk) {
    jwks.at = 0; // تصفير الكاش وإعادة المحاولة
    keys = await accessKeys(env.ACCESS_TEAM_DOMAIN);
    jwk = keys.find(k => k.kid === header.kid);
    if (!jwk) return false;
  }
  ```

---

### 7. إضعاف سياسة CSP بالسماح بكامل نطاق jsDelivr
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L45):
  `script-src 'self' 'nonce-${nonce}' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com`
- **سيناريو الاستغلال:**
  تسمح هذه الصياغة لمتصفح الأدمن بتنفيذ أي سكربت JS موجود على `cdn.jsdelivr.net`. يمكن لمهاجم رفع ملف JS خبيث إلى مستودع GitHub عام، واستدعاؤه كـ `<script src="https://cdn.jsdelivr.net/gh/attacker/payload@main/xss.js">` ليتجاوز الـ CSP تماماً.
- **الحل البرمجي الدقيق:**
  تضمين السكربتات محلياً ضمن ملفات الـ Worker كملفات نصية أو أصول ثابتة وتعديل الـ CSP في [worker.js](cloudflare/programs-admin/worker.js#L45) لتكون:
  ```javascript
  `script-src 'self' 'nonce-${nonce}'`,
  ```

---

### 8. ترتيب دمج الخصائص في كائن استدعاء RPC
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L369 و L378):
  ```javascript
  body: JSON.stringify({ p_secret: env.STORE_ADMIN_SECRET, ...body })
  ```
- **المخاطرة البرمجية:**
  في لغة JavaScript، وضع `...body` بعد الخاصية يعني أنه إذا استطاع الطلب تمرير خاصية `p_secret` بأي شكل (مثلاً Prototype أو تلاعب في التحليل)، فإن قيمتها ستطغى وتلغي `env.STORE_ADMIN_SECRET`.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js#L369 و L378):
  ```javascript
  body: JSON.stringify({ ...body, p_secret: env.STORE_ADMIN_SECRET }),
  ```

---

### 9. تراكم سجلات محاولات الدخول في D1 بدون تنظيف دوري
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L151)، يتم إجراء `INSERT INTO login_attempts` مع كل محاولة. لا يوجد في النظام أي أمر `DELETE` لتنظيف المحاولات القديمة، كما أن تسجيل الدخول الناجح في السطر 163 يحدث سطراً واحداً فقط بدلاً من تنظيف تاريخ الـ IP.
- **الأثر:**
  نمو لا نهائي لجدول المحاولات مما يبطئ استعلام الـ `COUNT(*)` ويزيد من استهلاك سعة التخزين المجانية في D1.
- **الحل البرمجي الدقيق:**
  إضافة استعلام تنظيف تلقائي للمحاولات التي مر عليها أكثر من 24 ساعة، وحذف سجلات الفشل عند نجاح الدخول:
  ```javascript
  // عند نجاح الدخول في السطر 163 من worker.js:
  await env.DB.prepare("DELETE FROM login_attempts WHERE ip = ?").bind(c.ip).run();
  // حذف المحاولات التي مضى عليها أكثر من 24 ساعة:
  const yesterday = new Date(Date.now() - 86400_000).toISOString();
  await env.DB.prepare("DELETE FROM login_attempts WHERE at < ?").bind(yesterday).run();
  ```

---

### 10. عدم ضبط انتهاء صلاحية خادمي لجلسات الأجهزة في D1
- **الضعف بدقة:**
  في [worker.js](cloudflare/programs-admin/worker.js#L124)، استعلام `SELECT refresh_token, revoked FROM device_sessions WHERE id = ?` لا يتحقق من حقل `last_seen` أو تاريخ الإنشاء.
- **الأثر:**
  إذا حصل مهاجم مستقبلاً على معرّف الكوكي القديم، وكان توكن Supabase ما يزال فعالاً، فإن الجلسة في D1 تظل صالحة للأبد لعدم وجود فحص TTL خادمي في قاعدة البيانات.
- **الحل البرمجي الدقيق:**
  في [worker.js](cloudflare/programs-admin/worker.js#L124): اشتراط ألا يكون قد مضى أكثر من 30 يوماً على آخر ظهور للجهاز:
  ```javascript
  const row = await env.DB.prepare("SELECT refresh_token, revoked, last_seen FROM device_sessions WHERE id = ?").bind(id).first();
  const maxIdle = 30 * 24 * 3600 * 1000;
  if (!row || row.revoked || (Date.now() - new Date(row.last_seen).getTime() > maxIdle)) {
    return new Response(JSON.stringify({ message: "session expired" }), { status: 401, headers: { "Content-Type": "application/json", "Set-Cookie": setCookie(id, true), ...SEC_HEADERS } });
  }
  ```

---

## ثالثاً: إجراءات تقوية أمنية عاجلة وموصى بها اليوم (Hardening Steps)

1. **تفعيل mTLS (شهادات العميل) في Cloudflare Zero Trust:**
   إنشاء 4 شهادات لأجهزة المالك وتثبيتها. هذا الإجراء الفردي يرفع الحماية إلى مستوى دفاعي مطلق، حيث تُسقط Cloudflare أي اتصال لا يملك الشهادة الرقمية للمالك قبل وصول الطلب إلى الـ Worker.
2. **تصحيح فحص `WRITE_KEY` في `upload-worker.js`:**
   تعديل السطر 66 ليصبح إلزامياً ولا يمر دونه أي طلب رفع نهائياً.
3. **تصحيح شرط إلغاء الجلسات في `worker.js`:**
   استبدال الإلغاء الفوري للجلسات بفحص خطأ `400 Bad Request` الصريح من Supabase لمنع طرد المالك.
4. **تقييد `DELETE` في PostgREST:**
   تعديل التعبير النمطي في السطر 277 ليمنع حذف الجداول بدون شروط دقيقة.
5. **تضمين السكربتات محلياً (Self-Host Vendor Scripts):**
   وضع نسخ السكربتات داخل مشروع الـ Worker بدلاً من جلبها عبر CDN وإلغاء نطاق jsDelivr من الـ CSP.

---

## رابعاً: أمور سليمة ومحكمة تماماً في النظام (يجب الإبقاء عليها كما هي)

لضمان عدم قيام أي مطور مستقبلي بـ "إصلاح" ما هو سليم وتخريب النظام، هذه النقاط صلبة ومعماريتها صحيحة:
1. **علانية مفاتيح Anon:** وجود `SUPABASE_ANON` و `CTRL_TELEMETRY_ANON` في ملفات الواجهة والـ Worker أمر طبيعي ومقصود معمارياً في Supabase؛ الأمان الحقيقي مبني على سياسات Postgres RLS وحقن الأسرار الخادمي.
2. **عزل مفاتيح وأسرار الخادم (Zero Leakage):** أسرار `STORE_ADMIN_SECRET` و `TSLINK_ADMIN_TOKEN` و `LEO_ADMIN_TOKEN` و `CONTROLLER_ADMIN_SECRET` محقونة بالكامل خادمياً عبر الـ Worker ولا تُرسل للمتصفح نهائياً.
3. **حفظ الـ Refresh Token في الخادم عبر HttpOnly Cookie:** تم التخلص من حفظ التوكن في `localStorage` بنجاح؛ المتصفح يحمل معرفاً عشوائياً مشفراً بـ 192 بت (`ts_dev`) لا يمكن قراءته عبر جافاسكريبت ومحمي بخصائص `SameSite=Strict; Secure; HttpOnly`.
4. **استخدام Service Bindings لربط الـ Workers:** الربط الداخلي بين الـ Workers عبر Service Bindings سليم ومثالي يمنع مشاكل خطأ 1042 ويمنع خروج الحركة للإنترنت العام.
5. **تعقيم المدخلات في الواجهة بدالة `esc()`:** كافة حقول البيانات القادمة من قاعدة البيانات يتم تعقيمها بدقة لمنع ثغرات XSS، كما يتم التأكد من بروتوكول الروابط قبل فتحها.
6. **منع هجمات المسار (Directory Traversal) في رفع الملفات:** دالة تقطيع المسار `.split(/[\\/]/).pop()` مع حصر الـ prefixes في `wallpapers` و `apk` تمنع الكتابة خارج المجلدات المحددة في R2.
7. **الحجز المسبق لمحاولات الدخول لمنع السباق (Race Condition Mitigation):** تسجيل المحاولة في D1 بقيمة `0` قبل التحقق والاتصال بـ Supabase فكرة هندسية بارعة تحمي من تجاوز حد الـ 5 محاولات عبر الطلبات المتوازية.