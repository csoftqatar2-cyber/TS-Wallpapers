# ICAR 03T — كل ما عرفناه عن العربية دي

> السيارة: **Chery iCAR 03T**، هيد يونت **MENGBO S56_HQX**، أندرويد 9 (API 28).
> شاشة رئيسية 1920×1080 (density 141) + شريط HDMI 1920×384.
> اللانشر: `com.mengbo.launcher3`. إعدادات السيارة: `com.chery.carsettings`.
> عربية البنش: `VIN-LVVETHCG6VD702334`، وصلنا لها على `adb connect 192.168.0.80:5555`.
>
> الوضع في البرنامج: **`icar03t`** (اسم العرض «ICAR 03T»).
> آخر تحديث للمستند: 2026-09-07.

---

## 1. الخلاصة في سطرين

اللانشر بتاع العربية دي **بيرسم كاروسيل صوره الخاصة فوق خلفية النظام**، فخلفية أندرويد
الحيّة بتاعتنا بتتسجّل وبتشتغل و**عمرها ما تتشاف**. الطريقة الوحيدة اللي بتوصل للشاشة هي إننا
نكتب الصورة في فولدر على التخزين المشترك ونبعت برودكاست للانشر يقراها.

المود بقى **hand-off للانشر** (شبه Lynk & Co)، **مش** hand-off لأندرويد (Leopard/Denza).

---

## 2. الوصفة اللي بتشتغل

**الملفات** — لازم الاتنين، والأسماء دي بالحرف:

```
/sdcard/Download/holiday/<اسم>/launcher/img_launch_wall_light.png
/sdcard/Download/holiday/<اسم>/launcher/img_launch_wall_dark.png
```

**الزناد:**

```bash
adb shell am broadcast -a com.mengbo.holiday.mode --ei status 1 --es holiday <اسم>   # ضيف
adb shell am broadcast -a com.mengbo.holiday.mode --ei status 0 --es holiday <اسم>   # شيل
```

**مصدر المعلومة** — مفكوك من `MB_Launcher.apk`، كلاس `com.mengbo.wallpager.WallPaperFragment`:

```java
pathLight = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
          + "/holiday/" + holiday + "/launcher/img_launch_wall_light.png";
pathDark  = ... + "/img_launch_wall_dark.png";
drawableWallLight = BitmapUtil.loadDrawableFromPath(ctx, pathLight);
if (drawableWallLight == null || drawableWallDark == null) return;   // بيسيبها بصمت
WallPaperManager.getInstance().addWallPaper(pathLight, pathDark, index);
```

و`BitmapUtil.loadDrawableFromPath` بيستعمل **`BitmapFactory.decodeFile`** — يعني بيشمّ
المحتوى مش الامتداد، فملف JPEG باسم `.png` بيتفك عادي. (عشان كده بنكتب JPEG: صورة 1920×1080
كـPNG كامل بتطلع ٣ ميجا، واتنين منها بيتفكوا في نفس اللحظة جوّه عملية اللانشر.)

---

## 3. القيود اللي الآلية دي بتفرضها

| القيد | التفاصيل |
|---|---|
| **الملفين أو مفيش** | لو واحد ناقص، اللانشر بيسيبها من غير رسالة (بيطبع `没有加载到图片` على مستوى debug) |
| **سلوت واحد بس** | اللانشر بيحتفظ بمدخل «مناسبة» واحد، وبيمسح اللي قبله مع كل إضافة. فده منتج «الخلفية الحالية» زي Leopard — مش مكتبة صور زي GWM/Jetour |
| **متعيدش استخدام نفس الاسم** | اللانشر بيحمّل عن طريق Glide اللي بيكاش بالمسار. صورة جديدة بنفس اسم الفولدر = القديمة تفضل على الشاشة. كل تطبيق لازم ياخد اسم فولدر جديد |
| **صور بس** | اللانشر بيفك صورة ثابتة. الفيديو مالوش أي مكان هنا |
| **صور السيارة الأصلية مش بتتمسح مننا** | فحصنا مانيفست اللانشر كله: مفيش broadcast ولا ContentProvider بيعرّض `deleteWallPaper`. الصور جوّه الـAPK وبتتدار من شاشة اللانشر نفسها |

---

## 4. مشكلة مفتوحة (أهم حاجة تكمل منها)

**من ADB الصورة ظهرت، ومن جوّه البرنامج مظهرتش.**

- من ADB: صورة اختبار **٨ كيلوبايت** (مربع أزرق) ظهرت على الكاروسيل والمالك شافها ✅
- من البرنامج: كتب الفولدر صح، بعت البرودكاست، واللانشر استقبله
  (`MBL_LoadedApkPlugs: long-running onReceive, 160ms: NewMainActivity$4`) — **ومظهرتش**

اللي **اتنفى** كسبب:
- مش فشل في فك الصورة — `loadDrawableFromPath` بيطبع `Failed to load bitmap from file`
  على مستوى **E** وده بيظهر في اللوج، وماظهرش
- مش `setPackage` — جرّبنا من ADB من غيره وبنفس النتيجة

الشك الباقي، بالترتيب:
1. **الحجم**: اللي نجح كان ٨ كيلو، واللي فشل كان ٣ ميجا ×٢. آخر اختبار (١٠٥ كيلو JPEG)
   اترفع على العربية بس **العربية مشيت قبل ما نتأكد** — ده أول حاجة تتجرب.
2. `WallPaperFragment` (اللي فيه المستقبِل اللي بيضيف للكاروسيل فعلاً) ممكن ما يكونش موجود
   وقت البرودكاست. `NewMainActivity$4` اللي شفناه بيرد ده بيطبّق **سكين المناسبة**، وده
   مسار تاني غير الخلفية.
3. فيه مستقبِل تاني على نفس الأكشن عايز `title` (`BlessingCardsTrigger: onReceive: title == null`)
   — يمكن الحزمة الكاملة عايزة إكسترات أكتر.

**ملحوظة مهمة على التشخيص:** الوحدة دي **بتفلتر لوجات التطبيقات على مستوى debug**.
كل `Log.d` بتاع اللانشر (وكل تاجات `Festival`) مش بتظهر خالص. اللي بيظهر `Log.e` بس.
فمتستنتجش «مافيش لوج = ماشتغلش».

---

## 5. حاجات في الوحدة دي هتضيّع وقتك لو ماتعرفهاش

- **اللمس بيضيع في النافذة الصغيرة.** اللانشر بيفتح كل التطبيقات freeform في
  `Rect(660,90 - 1890,900)`، وجوّه النافذة دي **الضغط على عناصر برنامجنا مابيوصلش** — لا من
  ADB ولا بصباع المستخدم (اتجرب). أول ما تعمل
  `am task resize <taskId> 0 0 1920 1080` كل حاجة بتشتغل من أول ضغطة.
  الوايت ليست بتاعة الفول سكرين في `/system/etc/defaultConfigList.json` والنظام read-only.
  الحل اللي شغال من ADB: `am task resize` + `settings put global setting_global_fullscreen 1`
  (النظام بيرجّع المفتاح لـ0 لوحده على Home).
- **`SettingsActivity` بتاعتنا بتتفتح بـ`am start` عادي** على الوحدة دي.
- **`/sdcard/Anim/Holiday` و`/Pendant` موجودين وفاضيين** — مش دول المسار الصح.
- **`/sdcard/launcher` مش موجود.** ملفات اللانشر الداخلية في
  `/data/data/com.mengbo.launcher3/files/wallpapers` — ومفيش root.
- العربية بتشغّل كل تطبيق في قايمة البدء الذاتي فول سكرين بعد الإقلاع بحوالي ٤١ ثانية.

---

## 6. اللي اتعمل في الكود

**كلاس جديد:** [`Icar03tApplier.java`](source/app/src/main/java/systems/sieber/fsclock/Icar03tApplier.java)
— بيقصّ الصورة على مقاس الشاشة (cover)، يكتبها JPEG في الملفين، يبعت البرودكاست، ويمسح
السلوت القديم بعد ما الجديد يدخل. وفيه `clear()` بيشيل بتاعتنا ويرجّع الكاروسيل زي ما كان.

**الوضع:** `OperatingMode.ICAR03T = 7`، مفتاح `icar03t-mode`، wire value **`icar03t`**.
مش في `isLeopardFamily` (عشان مايلمسش منطق WallpaperManager)، بس في `isHandoffMode`.
بوابة الدعم = وجود حزمة `com.mengbo.launcher3` (متضافة في `<queries>` في المانيفست).

**الواجهة في الوضع ده بس:** الزرار بيقول **«تنزيل الخلفية»** مش «تعيين الخلفية»،
وفيه زرار إدارة (سلة) في الهيدر بيفتح خيارين: «شيل خلفيتنا من السيارة» و«افتح شاشة خلفيات
السيارة» (`com.mengbo.wallpager.WallPaperActivity` — مُصدَّرة). الفيديو بيقول رسالة صريحة.

**باج عام اتصلح على الطريق:** ديالوج اختيار الوضع (من الشارة في الهيدر) كانت صفوفه
**مابتستجبش للمس** على أي عربية — `RadioButton` جوّه `ListView` كان `clickable=false` بس
**focusable=true**، وعنصر واحد focusable كفاية إن الـListView مايناديش `onItemClick`.
اتصلح في [`AuroraDialog.java`](source/app/src/main/java/systems/sieber/fsclock/AuroraDialog.java).

**الباك أب:** ٢٣ خلفية أصلية من العربية في
[`Wallpapers/ICAR 03T - stock backup/`](Wallpapers/ICAR%2003T%20-%20stock%20backup/) — مستخرجة
من `MB_Launcher.apk` (كانت `.webp` بأسماء مشفّرة).

**المايجريشن:** [`supabase/migrations/20260918_icar03t_mode.sql`](supabase/migrations/20260918_icar03t_mode.sql)
— **PENDING**، بيوسّع الـCHECK بتاع `devices.mode` و`wallpapers.target_mode` ويحدّث
`report_device_mode`. **لازم يتطبّق قبل ما أي عربية تبلّغ `icar03t`.**

**الداشبورد:** ٣ مواضع في `wallpapers_manager.html` (خيار الاستهداف، `TARGET_MODE_NAMES`،
`MODE_LABELS`).

---

## 7. أوامر جاهزة

```bash
# تجربة يدوية كاملة
adb connect 192.168.0.80:5555
adb shell mkdir -p /sdcard/Download/holiday/test1/launcher
adb push wall.jpg /sdcard/Download/holiday/test1/launcher/img_launch_wall_light.png
adb push wall.jpg /sdcard/Download/holiday/test1/launcher/img_launch_wall_dark.png
adb shell am broadcast -a com.mengbo.holiday.mode --ei status 1 --es holiday test1

# اللمس مايضيعش
adb shell "dumpsys activity activities | grep -oE 'TaskRecord\{[^ ]* #[0-9]+ A=store\.thabthaba\.clock'"
adb shell am task resize <taskId> 0 0 1920 1080

# شوف رد اللانشر (E بس — الـdebug مفلتر)
adb logcat -d --pid=$(adb shell pidof com.mengbo.launcher3) | grep -iE "DrawableLoader|onReceive"

# شوف بتاعنا
adb logcat -d | grep "E fsclock"
```
