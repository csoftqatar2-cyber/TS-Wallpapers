# Settings Screen Redesign Brief — TS Wallpapers (ذبذبة خلفيات)

> For a designer / design AI. Goal: fully redesign the Settings screen (visual, structure, UX)
> within the technical constraints in §6. Nothing has been omitted — this is a complete inventory
> of every element currently on the screen.
>
> **Note on text:** UI strings are shown verbatim. Many are Arabic — the designer needs the real
> strings to judge line lengths and wrapping. English translations are given in parentheses.

---

## 1. Product context

- **App:** TS Wallpapers (Arabic name: ذبذبة خلفيات), brand "THABTHABA STORE".
- **What it does:** a wallpaper / screensaver app (images, GIFs, MP4 video) displayed fullscreen, with a clock overlay on top (analog and/or digital), plus date and weather.
- **Primary target device:** an **Android car head-unit screen**. The critical size is **FSE = 1920×720** — an extremely wide display (≈2.67:1 aspect). The app also runs on TVs, Fire TV, tablets, and phones.
- **Input:** touch in the car, but **also D-pad / TV remote**. Every interactive element needs a **strong focus state** (focus, not hover).
- **Language:** Arabic-first, mixed with English. The app is **RTL** (`supportsRtl=true`). Other locales present: de, es, fr, it, iw, nl, ru.
- **Who opens this screen:** the installer technician at the shop, and the car owner. It must be approachable but still expose advanced settings.

---

## 2. Current state (what we want to replace)

- Black toolbar with a back arrow. Title is set at runtime: **"ذبذبة خلفيات 2.0"** (app name + version).
- **No save button at all** — every setting persists the moment it changes (auto-save). This philosophy must stay.
- Background `#ECEFF1` (light grey); content sits in **Material cards** (18dp corners, 3dp elevation, 18dp padding, white background).
- Section headers: Large text, bold, **orange `#ff9a3d`**.
- The screen is **permanently two side-by-side columns** (horizontal LinearLayout, each column weight=1) — **even on a narrow phone**. This is the biggest problem today.
- Everything lives inside a single vertical ScrollView.

### Known problems the redesign should solve

1. **The two columns are hardcoded.** On a phone each column gets 50% of the width, causing cramped layout and broken text. We need real responsive behavior (one column when narrow, two on tablet/FSE).
2. **The columns are badly unbalanced.** Left (General + Analog + Wallpapers + Clock layout) is far taller than right (Digital + Background + About).
3. **No dependent enable/disable anywhere.** "Auto-switch" can be off while "display duration" stays live; "Show weather" can be off while the city field stays editable; "Show analog clock" can be off while all four of its color/design rows remain visible and active. **Every sub-setting is always visible and always enabled regardless of its parent.** The new design must address this (hide, dim, or collapse).
4. **The activation dialog is black-and-gold** while the rest of the screen is white-and-grey — two clashing visual identities.
5. **The Wallpapers card holds 14 elements** stacked in one card — it needs sub-grouping.
6. All dialogs are default AlertDialogs with no identity, except two with custom layouts.

---

## 3. Full element inventory

### 3.1 Above the cards (standalone rows)

| Element | Type | Visibility |
|---|---|---|
| Fire TV notice | Long warning text | **Only** on Amazon Fire TV devices |
| High-contrast notice | Warning text | **Only** if the user has "High Contrast Text" enabled in Android accessibility settings |
| Purchase / activation block | **"Unlock Settings"** button + explainer text + 3 demo clock graphics (80dp each) | **Only while the device is not activated.** Once activated the whole block disappears and the rest of the settings unlock |
| **"Set as screensaver in Android settings"** | Borderless orange button | Hidden on TV, on Fire TV, and on Android older than 4.3 |

> **Important:** while the device is not activated, **42 elements on the screen are disabled (greyed out)** — visible but not editable. This is a core state that needs a clear design (locked / empty state).

---

### 3.2 Card: **General**

| Element | Type | Displayed text | Default |
|---|---|---|---|
| FSE | CheckBox | `FSE (1920×720)` | off |
| Keep screen on | CheckBox | `Keep Screen On` | **on** |
| Auto-start on boot | CheckBox | `فتح التطبيق تلقائياً عند تشغيل السيارة` (Auto-start the app when the car turns on) | off |
| Show weather | CheckBox | `إظهار الطقس الحالي` (Show current weather) | **on** |
| Weather city | EditText | hint: `المدينة (اتركها فارغة للتحديد التلقائي حسب الموقع)` (City — leave empty to auto-detect by location) | `Doha` |

- **Auto-start** opens a permission dialog when checked: `إذن مطلوب` (Permission required) / `حتى يفتح التطبيق تلقائياً عند تشغيل السيارة، فعّل إذن "الظهور فوق التطبيقات الأخرى" في الشاشة التالية.` (To let the app open automatically when the car starts, enable "Display over other apps" on the next screen.) → OK sends the user to system settings.
- **The city field should depend on Show weather** (it currently does not).

---

### 3.3 Card: **Analog Clock**

The header has a **circular orange (?) icon** next to it that opens a help dialog about custom images.

| Element | Type | Text |
|---|---|---|
| Show analog clock | CheckBox | `Show Analog Clock` (default: on) |
| Seconds hand | CheckBox | `Show Seconds Hand (disable to save energy)` (on) |
| Smooth hands | CheckBox | `Use Smooth Clock Hands (disable to save energy)` (on) |

Then **4 structurally identical rows** — each is [color swatch + label] on the leading side, [design Spinner] on the trailing side:

| Row | Color label | Spinner options |
|---|---|---|
| 1 | `Clock Face Color` | Classic / Rounded / Elegant / Rustic / Outlined / Dot / Numbers / Square / Vinyl / No Image / **Custom Image** |
| 2 | `Hour Hand Color` | same list |
| 3 | `Minute Hand Color` | same list |
| 4 | `Seconds Hand Color` | same list |

- The color swatch is a small square with a grey border showing the current color → tapping it opens the **color picker dialog**.
- Choosing "Custom Image" in a Spinner opens a file picker.
- The Spinner uses a hand-drawn arrow (the default arrow is hidden).

---

### 3.4 Card: **Digital Clock**

| Element | Type | Text | Default |
|---|---|---|---|
| Show | CheckBox | `Show Digital Clock` | on |
| 24-hour format | CheckBox | `Use 24-Hours-Format` | off |
| Arabic numerals | CheckBox | `أرقام عربية (١٢٣) بدل (123)` (Arabic numerals ١٢٣ instead of 123) | off |
| Hijri date | CheckBox | `إظهار التاريخ الهجري تحت الميلادي` (Show the Hijri date under the Gregorian one) | on |
| Auto contrast | CheckBox | `لون الساعة تلقائي عكس الخلفية (للوضوح)` (Clock color auto-inverts against the background, for legibility) | on |
| Seconds | CheckBox | `Show Seconds` | on |
| **Composite row** | CheckBox `Show Date` + date-format EditText (hint `Date Format`) + (?) icon | — | system default format |
| **Radio row** | RadioButton `Gregorian Calendar` / `Hijri Calendar` | hidden on Android older than 8 | Gregorian |

Then **3 [color + font Spinner] rows**:

| Row | Color label | Default font |
|---|---|---|
| 1 | `Digital Clock Color` | DSEG7 Classic |
| 2 | `Date Color` | Cairo Regular |
| 3 | `Event Color` | Cairo Regular |

Below them, a small font-licensing text block (11sp): DSEG / Cairo / Roboto licenses.

- The (?) next to the date format opens a dialog with a monospace table of pattern tokens (yyyy, MM, dd, EEEE…) plus a **"Reset default"** button.

---

### 3.5 Card: **الخلفيات (Wallpapers)** ← the most important and most crowded card

| # | Element | Type | Text |
|---|---|---|---|
| 1 | Device ID | **gold bold 15sp** runtime text | `كود الجهاز (Device ID): SRL-xxxxx`<br>`الحالة (Status): نشط (Activated)` or `غير نشط (Pending Activation)` |
| 2 | Linking code | **gold bold 15sp**, selectable | `كود الربط (Linking Code): SRL-xxxxx` |
| 3 | QR | Colored button | `عرض كود الربط كـ QR (Show Linking Code QR)` |
| 4 | Enable wallpapers | CheckBox | `تفعيل عرض الخلفيات والتبديل بينها` (Enable wallpaper display and switching) — default on |
| 5 | Auto-switch | CheckBox | `التبديل التلقائي بين الصور` (Automatically switch between images) — off |
| 6 | Duration | Row: label + Spinner | `مدة عرض الصورة قبل التبديل` (How long each image shows before switching) → 30 seconds / 1 minute / 5 minutes / 10 minutes / 1 hour |
| 7 | Manage | Colored button | `🖼️ إدارة الصور (إظهار / إخفاء)` (Manage images — show/hide) |
| 8 | Refresh | Colored button | `🔄 التحقق من الخلفيات الجديدة (عامة/مخصوصة)` (Check for new wallpapers — global/assigned) |
| 9 | Status | 12sp runtime text | `تم تحميل 12 خلفية ✓` (12 wallpapers loaded) / `جارٍ التحقق…` (Checking…) / `تمت إضافة 3 خلفية جديدة ✓ (الإجمالي 15)` (3 new added, 15 total) / `الخلفيات محدثة — لا يوجد جديد (12 خلفية)` (Up to date — nothing new) / `فشل التحديث: …` (Update failed) |
| 10 | Help | Long 11sp text | `اسحب يمين/شمال على الشاشة للتبديل بين الخلفيات. ادعم: صور JPG/PNG، GIF متحرك، وفيديو MP4…` (Swipe left/right to switch wallpapers. Supports JPG/PNG, animated GIF, MP4 video…) |
| 11 | Local images | CheckBox | `عرض الصور/الفيديو من مجلد الجهاز كمان` (Also show images/video from the device folder) — on |
| 12 | Add | Colored button | `إضافة صور/فيديو من الجهاز` (Add images/video from the device) |
| 13 | Pair phone | Colored button | `رفع صورة من هاتف العميل (QR / واي‑فاي)` (Upload an image from the customer's phone via QR / Wi-Fi) |
| 14 | Folder path | 11sp runtime text | `مجلد الخلفيات على الجهاز:\n/storage/…` (Wallpaper folder on this device) |

> **Suggestion for the designer:** this card mixes three unrelated topics — (a) **device identity & activation**: items 1–3, (b) **playback behavior**: 4–6, (c) **image sources**: 7–14. It could become three cards, or sub-sections within one card.

---

### 3.6 Card: **شكل الساعة (Clock layout)**

| Element | Type | Options |
|---|---|---|
| `إظهار الساعة فوق الخلفية` (Show the clock over the wallpaper) | CheckBox | default on |
| `مكان الساعة` (Clock position) | Spinner | المنتصف (center) / أسفل يسار (bottom left) / أسفل يمين (bottom right) / أعلى يسار (top left) / أعلى يمين (top right) — default: bottom left |
| `حجم الساعة` (Clock size) | Spinner | صغير (small) / متوسط (medium) / كبير (large) / كامل الشاشة (fullscreen) — default: small |

---

### 3.7 Card: **Background**

A single row: [color swatch + `Choose color`] + [Spinner: No Image / Custom Image (Stretch) / Custom Image (Zoom)]. Default color is black.

---

### 3.8 Card: **About / updates**

| Element | Type | Text |
|---|---|---|
| Header | Headline text | `Fork Me` |
| Body | Body text | `This app is Open Source. If you want to improve the app or its translations, please check out the repository on Github.` |
| Link | Borderless button | `https://github.com/schorschii/FsClock-Android` |
| Update | Colored button | `التحقق من التحديثات` (Check for updates) |
| Build info | 11sp, **ALL CAPS, 50% opacity, centered** | `VERSION 2.0 (124) release standalone` |

> Technical note: this build-info text is the **last element on the screen** and absorbs the navigation-bar insets. Whatever the new design puts at the bottom must take over that spacing role.

---

## 4. All dialogs

| Dialog | Content | Current form |
|---|---|---|
| **Color picker** | CheckBox `Use Custom Color` + hex field (monospace) + 3 SeekBars (Red/Green/Blue, 0–255) + 30dp preview swatch + `OK` button + `Apply for all` button | Custom layout, no theme, full-width |
| **Date format help** | Title + monospace token table + buttons: `OK` and `Reset default` | Default AlertDialog |
| **Custom images help** | Text: `Custom images should be square… transparent background… hands centered pointing upwards` | AlertDialog with an **empty title** (visual bug) |
| **Device QR** | Text `امسح الكود ده أو انسخه، واربط بيه صورة الجهاز من لوحة التحكم.` (Scan or copy this code, then assign an image to the device from the dashboard.) + **240dp×240dp** QR image + 18sp selectable code text + OK | Built in code, AlertDialog |
| **Manage images** | 12sp explainer: `شيل علامة الصح من أي صورة عشان تختفي من العرض على هذه السيارة فقط…` (Uncheck any image to hide it on this car only…) + **ListView of images with checkboxes** + 3 buttons: `Save` / `إلغاء` (Cancel) / `تحديد الكل` (Select all) | Built in code. If empty → toast `لا توجد صور بعد — اضغط «التحقق من الخلفيات الجديدة» أولاً.` (No images yet — tap "Check for new wallpapers" first.) |
| **Pair phone** | Text: `الزبون يصوّر الكود ده بكاميرا موبايله (لازم نفس شبكة الواي‑فاي) ويرفع الصورة…` (The customer scans this code with their phone camera — must be on the same Wi-Fi — and uploads the image…) + 240dp QR of the URL + the URL as text + a **live status line** that flips to `تم استلام الصورة ✓ وأصبحت الخلفية الافتراضية` (Image received ✓ and set as the default wallpaper) | Built in code |
| **Update available** | `يوجد تحديث جديد` (Update available) / `صدر إصدار جديد (2.1). هل تريد التحديث الآن؟` (A new version 2.1 is out. Update now?) + optional changelog + `حدّث الآن` (Update now) / `لاحقاً` (Later) | AlertDialog |
| **Activation** ⚠️ | Title **"تفعيل الإعدادات (Activation)"** + a **6-digit numeric field only**, hint `------`, gold centered text + gold OK button with black text | **The only one with a completely different identity: black `#1c160e` + gold `#ffd27a`** |

---

## 5. Current color palette

| Name | Value | Used for |
|---|---|---|
| Primary | `#14100b` | Toolbar (near-black brown) |
| **Accent** | `#ff9a3d` | Orange — section headers, buttons, icons |
| Settings BG | `#ECEFF1` | Screen background |
| Card | `#FFFFFF` | Card background |
| **Gold** | `#ffd27a` | Device ID / linking code text + activation dialog |
| Gold dark | `#ff9a3d` | — |
| Dialog BG | `#1c160e` | Activation dialog only |
| Preview border | `#757575` | Color swatch borders |

A **night mode (values-night)** already exists.

---

## 6. Technical constraints the design must respect

1. **Android XML layouts** — implementation uses `LinearLayout` / `ConstraintLayout` / `MaterialCardView` / `ScrollView`. No Compose. So: **avoid heavy effects** (blur, mesh gradients, heavy glassmorphism, complex animation). Shadows, rounded corners, and simple gradients are fine.
2. **Theme:** `Theme.MaterialComponents.DayNight.Bridge` (Material Components are available).
3. **No save button** — auto-save. Any design that assumes a Save/Apply action is wrong.
4. **D-pad / remote:** every interactive element needs a **very strong focus state** (focus, not hover). Custom styles already exist to fix contrast problems on Fire TV — the design must also work on a TV from 3 meters away.
5. **Full RTL** — use start/end, not left/right. The "left" column renders on the right in Arabic.
6. **Sizes to cover:**
   - **1920×720 (FSE)** — the most important, an ultra-wide car display
   - Phone (360–420dp wide)
   - Tablet (600dp+ / 960dp+)
   - 1080p TV
7. **Arabic and English mix within the same line** — the design must not break on this.
8. **Some text is very long** (the help text, the Fire TV notice, the font licenses) — it needs a proper home, or collapsing/hiding.
9. Icons in use today: `ic_help_outline` (circular ?), `spinner_arrow`, `ic_check_white`, `ic_settings_white`.

---

## 7. What we need from the design

1. **A new information architecture** — how to organize 7 cards / ~60 settings sensibly (tabs? side nav? collapsible sections? sub-pages?).
2. **Real responsive behavior** — distinct behavior per size class, with particular focus on FSE 1920×720.
3. **Solve the dependency problem** — how to hide/dim sub-settings when their parent is off.
4. **Unify the visual identity** — especially reconciling the black/gold activation dialog with the rest of the screen, or the reverse (should the whole screen go dark with a gold identity? That's the design call).
5. **Design the "device not activated" state** — it's the first thing every new user sees.
6. **Design the Wallpapers section** — the most crowded part; it needs a solution.
7. **Design all seven dialogs** with one coherent identity.
8. **A final color palette, type scale, and spacing scale** (a clear ladder: 4/8/12/16/24).
9. **Deliverable:** ideally specs in dp + hex colors + the anatomy of each component (checkbox / spinner / color row / card / button) so it can be implemented directly in XML.
