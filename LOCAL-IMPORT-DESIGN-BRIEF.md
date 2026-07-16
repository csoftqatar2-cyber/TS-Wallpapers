# Local Image Import — Design Brief (TS Wallpapers / ذبذبة خلفيات)

> **Scope of this brief:** design the **local image import experience only** — i.e. everything the
> user sees when an image enters the app from **the device itself (system file picker)** or from
> **a phone over the local QR-code upload**. This covers (a) a new per-image **Fit Editor**, and
> (b) a new **"Local import settings"** group inside the existing Settings screen that holds the
> defaults for that editor.
>
> This is a **companion** to `SETTINGS-DESIGN-BRIEF.md` (the full-settings redesign). Read §1 and
> §6 of that document for product context and constraints; they apply here unchanged. This brief
> repeats only what is needed to design in isolation.
>
> **Note on text:** all UI strings below are given verbatim in Arabic (the app is Arabic-first and
> RTL) with an English translation in parentheses. The Arabic is what ships — use it to judge line
> length and wrapping. Strings marked *(proposed)* are not final; suggest better ones if you can.

---

## 1. Product context (short version)

- **App:** TS Wallpapers (ذبذبة خلفيات), brand **THABTHABA STORE**. A wallpaper/screensaver app
  showing fullscreen images, GIFs and MP4 video with a clock overlay.
- **Primary device:** an **Android car head-unit**. The critical screen is **FSE = 1920×720** — an
  extremely wide display, **≈2.67:1**. The app also runs on TV, Fire TV, tablets, phones.
- **Input:** touch in the car, **but also D-pad / TV remote**. Every interactive element needs a
  strong **focus** state (not hover). Sliders must be operable by D-pad left/right.
- **Language:** Arabic-first, RTL (`supportsRtl=true`). Other locales exist (de/es/fr/it/iw/nl/ru).
- **Who uses this:** the installer technician at the shop, and the car owner — often the owner's
  friend/family standing next to the car holding their own phone.

---

## 2. The problem we are solving

Anyone can push an image to the car screen by scanning a QR code with their phone (§4.2). In
practice **people upload photos and screenshots shot in Reel / Story format — tall 9:16 portrait.**

The app currently renders **every** wallpaper as a **center-crop fill**: it scales the image until
it covers the whole screen and throws away the overflow. On a 2.67:1 screen a 9:16 image gets
scaled ≈**4.7×**, so the viewer sees a **massively zoomed, brutally cropped** sliver of the middle
of the photo — heads cut off, the subject gone. This is the single complaint driving this work.

There is currently **no editing UI of any kind** for imported images, and **no zoom control**. The
only existing adjustment is a hidden pan gesture on the fullscreen screen (§4.3).

### What we want instead

When an image is too tall for the screen, **fit** it whole (letterbox) and **fill the empty side
bars** with one of:

1. **A blurred copy of the same image**, scaled up to cover the full screen, sitting behind the
   sharp fitted image — the familiar "Instagram / YouTube Shorts blurred backdrop" look. The
   **amount of blur is controlled by a slider**.
2. **A solid color** (black by default), **chosen from a color slider**.

Both must be **previewable live** and **saved per image**.

---

## 3. What you are designing

Two connected surfaces:

| # | Surface | Where it lives | Purpose |
|---|---|---|---|
| **A** | **Fit Editor** | Full-screen dialog / screen on the head-unit | Per-image: pick fit mode, set blur amount, set bar color, reposition. Live preview. |
| **B** | **"Local import settings"** group | A new sub-group inside the existing **Wallpapers** card in Settings | The **defaults** that surface A opens with, so the technician sets it once and every future upload just works. |

Surface **A** is the priority. Surface **B** is a handful of rows.

---

## 4. How images get in today (the flows you are designing into)

### 4.1 Device file picker
Settings → **"إضافة صور من الجهاز"** (Add images from the device) → Android system picker,
**multi-select allowed**, `image/*` + `video/*`. Files are copied into the app's local wallpaper
folder and appear in the slideshow. **No confirmation, no preview, nothing** — the user is dumped
back on Settings.

> **Design consequence:** multi-select means the editor may be asked to handle **N images at once**.
> See the open question in §9.

### 4.2 QR-code upload from a phone
Settings → **"ربط الهاتف"** (Pair phone) → a dialog on the head-unit shows a **QR code**. The phone
scans it, opens a small web page **served by the car itself** over the local Wi-Fi, picks a file,
uploads. On success the uploaded image **immediately becomes the wallpaper** and the phone shows
**"تم الرفع بنجاح ✓"** (Uploaded successfully).

The upload web page is a separate, very plain page (dark `#15171e`, gold `#E6BE6A` heading, one
file input, one button, a progress line). **It is in scope only if you want to propose changes** —
see §9.

> **Design consequence:** at the moment the upload lands, **the pairing dialog with the QR code is
> still open on the head-unit screen**, and the person who uploaded is looking at *their phone*, not
> the car screen. Whoever is at the car screen is a different person. The editor must not assume the
> uploader is standing at the head-unit.

### 4.3 The existing hidden "adjust" gesture (context — do not redesign, but stay consistent)
On the fullscreen wallpaper screen, **only when FSE mode is on**: **long-press** enters adjust mode
(the clock hides, an Arabic toast appears), **drag** pans the image, **single tap** saves and exits.
The pan position is saved per image. There is no zoom and no scale control. The new editor's
reposition control should feel like a sibling of this, and ideally the two should agree.

---

## 5. Surface A — the Fit Editor

### 5.1 When it opens
- Right after an import from the device picker (§4.1).
- Right after a QR upload lands (§4.2).
- On demand later, from the wallpaper list (long-press an image → **"تعديل"** *(Edit)*).

### 5.2 The live preview
This is the centrepiece and the hardest part of the layout.

- It must show the image **exactly as it will appear on the wallpaper screen** — same aspect ratio
  as the target screen (**1920×720 on FSE**, otherwise the device's own screen aspect).
- Every control updates it **immediately** (no Apply button — the whole app is auto-save, see §7).
- **The preview is itself ≈2.67:1 and very wide.** On a 1920×720 head-unit, a preview wide enough to
  be useful eats most of the screen and leaves a short, wide strip for the controls. **Solving this
  layout tension is the main design problem of this brief.** Consider: preview on top and a single
  row of controls beneath; preview on one side and a control column on the other; controls as a
  translucent bar floating over the preview; or a collapsing control tray.
- The preview should make the *problem* legible — the user must be able to see at a glance that the
  bars are being filled and how.

### 5.3 The controls

#### 1) Fit mode — 3 mutually-exclusive options
| Value | String | Meaning |
|---|---|---|
| Fill | `ملء الشاشة (قص)` *(Fill screen — crop)* | Today's behavior. Zoom to cover; edges cropped. Correct for wide/landscape photos. |
| Blur | `احتواء + خلفية ضبابية` *(Fit + blurred backdrop)* | The whole image is visible; sides filled with a blurred, zoomed copy of itself. **The default for tall images.** |
| Color | `احتواء + لون ثابت` *(Fit + solid color)* | The whole image is visible; sides filled with a solid color. |

Needs to be glanceable and D-pad reachable. Icons/thumbnails communicate this far better than
words — consider three tiny preview tiles the user picks between rather than radio buttons.

#### 2) Blur amount — slider. **Only relevant in Blur mode.**
- String: `قوة الضبابية` *(Blur strength)*.
- Range 0–100. `0` = the backdrop is a plain zoomed copy, not blurred (ugly, but it is the honest
  bottom of the range). Default ≈ **60**.
- Show the current value, or at least a min/max hint. Must move by D-pad.
- **Design the disabled/hidden state**: what happens to this row in Fill and Color mode? The parent
  settings brief explicitly calls out "no dependent enable/disable anywhere" as a **known defect to
  fix** — so do not repeat it here. Hide, dim, or collapse, but pick one and be deliberate.

#### 3) Bar color — **only relevant in Color mode.**
- String: `لون الجانبين` *(Color of the two sides)*.
- Default **black** (`#000000`).
- A color **swatch** row that opens the app's **existing color dialog** (`dialog_color.xml`) — a
  hex field + **three sliders: Red, Green, Blue** + a live preview square + an OK button. **Reuse
  it; do not design a new color picker** unless you also want to restyle that shared dialog (it is
  used by 8 other settings). If you do want to, say so explicitly and treat it as separate work.
- Offering **2–5 one-tap presets** (black / white / dark grey / a color sampled from the image's own
  edges) next to the swatch would remove the dialog trip for ~90% of users. Recommended.

#### 4) Reposition — **only relevant in Fill mode** (in fit modes, nothing is cropped, so there is
   nothing to reposition).
- The image can be dragged to choose which part survives the crop. Saved per image.
- Needs a **D-pad path**, not just drag. Today's gesture is touch-only.
- String: `اسحب الصورة لتحديد الجزء الظاهر` *(Drag the image to choose the visible part)*.

#### 5) Actions
| String | Meaning |
|---|---|
| `تم` *(Done)* | Closes the editor. |
| `إعادة الضبط` *(Reset)* | Back to the defaults from Surface B. |
| `حذف الصورة` *(Delete image)* | Removes the imported file. **Destructive** — needs confirm, and needs to be visually separated from Done. |
| `تطبيق على الكل` *(Apply to all)* *(proposed)* | Applies these settings to every local image. See §9. |

### 5.4 Auto-detection (important — this is what makes it "just work")
On import the app **measures the image** and preselects a mode:

- Image much taller/narrower than the screen (a Reel — 9:16 on a 2.67:1 screen) → **Blur**.
- Image close to the screen's aspect ratio → **Fill**.

So the editor usually opens **already correct**, and the user just confirms. Design for that: the
happy path is "look at it, press تم". The editor is a **confirmation** surface first and an editing
surface second. It should not feel like homework.

There is a matching default in Surface B: `تلقائي` *(Automatic)*, which is the recommended default.

### 5.5 States you must design
| State | Notes |
|---|---|
| **Loading** | The image is being decoded/copied. On a weak head-unit SoC with a 10 MB phone photo this is visible. |
| **Blur mode, tall image** | The main case. Big bars, blurred. |
| **Blur mode, wide image** | Bars are thin or nonexistent — blur is nearly invisible. The slider still "works" but does nothing perceptible. Does the UI say so? |
| **Fill mode** | No bars. Blur + color rows are irrelevant. |
| **Video / GIF** | **Blur is not available for video** (per-frame blur is too expensive on this hardware). A video in fit mode gets **solid color bars only**. The Blur option must be visibly unavailable — design that. GIFs: blur uses the first frame only, and is available. |
| **Multiple images just imported** | See §9. |
| **Uploaded from a phone (QR)** | Nobody may be standing at the car screen. See §4.2. |
| **D-pad focus** | Every control. This is a TV-remote device too. |

---

## 6. Surface B — "Local import settings" in the Settings screen

A new sub-group inside the existing **Wallpapers** card (that card already holds 14 elements and is
called out in the parent brief as needing sub-grouping — this is a chance to do it).

Proposed header: **`إعدادات استيراد الصور` *(Image import settings)***, with the subtitle
`تنطبق على الصور المرفوعة من الجهاز أو من الهاتف عبر QR` *(Applies to images imported from the
device or from a phone via QR)* — the user needs to know these two paths share one setting.

| Element | Type | String | Default |
|---|---|---|---|
| Default fit mode | 4 options | `الوضع الافتراضي` *(Default mode)*: `تلقائي` *(Automatic — recommended)* / `ملء الشاشة (قص)` / `احتواء + خلفية ضبابية` / `احتواء + لون ثابت` | **تلقائي** |
| Default blur strength | Slider 0–100 | `قوة الضبابية الافتراضية` *(Default blur strength)* | 60 |
| Default bar color | Color swatch | `لون الجانبين الافتراضي` *(Default side color)* | Black |
| Open the editor after each import | CheckBox | `افتح شاشة التعديل بعد كل استيراد` *(Open the editor after every import)* | **on** |

- Blur/color defaults should **depend on** the mode above (in `تلقائي` both are still meaningful, since
  auto resolves to blur for tall images — so both stay live except when the mode is explicitly Fill).
- Turning the last row **off** is the "I trust the auto-detection, stop asking me" escape hatch —
  images then import silently with the defaults applied. That must be a legible promise, not a
  mystery toggle.

---

## 7. Rules that are not negotiable

1. **No save button, anywhere.** Every setting in this app persists the instant it changes. The
   editor's `تم` closes the editor; it does not "commit". Design accordingly — there is no
   Cancel/Discard concept in this product.
2. **The original file is never modified.** All of this is display-time settings stored per image.
   Everything is reversible; "Reset" always fully restores.
3. **RTL.** Sliders, back arrows, the whole layout. Note the string `لون الجانبين` (color of the two
   sides) — "left and right" is symmetric here, so RTL does not invert the meaning, but it does
   invert your layout.
4. **D-pad.** Every control needs a focus state and a keyboard path.

---

## 8. Technical constraints (hard — the implementation is Java + XML)

Repeated from `SETTINGS-DESIGN-BRIEF.md` §6 because they will kill a design that ignores them:

- **Pure Java, XML layouts. No Jetpack Compose, no Kotlin, no Fragments.** Views are
  `LinearLayout` / `FrameLayout` / `ScrollView` / `ImageView` / `SeekBar` / `CheckBox` /
  `RadioButton` / `Button` / Material `CardView`, plus custom `View` subclasses where needed.
- **minSdk 21** (Android 5.0). Nothing may *require* API > 21.
- The existing sliders are plain `SeekBar`. The existing color dialog is `dialog_color.xml`
  (hex field + R/G/B `SeekBar`s + preview + OK). Image loading is **Glide 4.16**.
- **The blur is computed by us in software** (RenderScript is deprecated; `RenderEffect` needs
  API 31). It is a downscale → box-blur → upscale. This is cheap **but not free**: assume the blur
  updates at a *slightly* lower framerate than the slider. **Do not design a blur interaction that
  demands a perfectly smooth 60fps live update while dragging** — a fast-settling update is what the
  hardware will give. Design the slider so a ~100ms settle reads as intentional, not broken.
- Target hardware is a **cheap car head-unit**: weak CPU, no meaningful GPU budget. Prefer flat
  fills, avoid stacked translucency and large animated surfaces over the preview.

### Existing brand palette to work from
| Token | Value | Used for |
|---|---|---|
| Gold | `#E6BE6A` | Activation dialog, QR upload page, brand accents |
| Orange | `#ff9a3d` | Settings section headers, borderless buttons |
| Dark | `#15171e` | Activation dialog, upload page background |
| Light grey | `#ECEFF1` | Settings screen background |
| Cards | White, 18dp corners, 3dp elevation, 18dp padding | Settings cards |

The parent brief flags that the app currently has **two clashing identities** (black-and-gold
dialogs vs. white-and-grey settings) and wants that resolved. The Fit Editor is a full-screen
surface showing a photo — a **dark** chrome is the obvious fit, which puts it on the black-and-gold
side. Say which side you are picking and why.

---

## 9. Open questions — please answer these in your proposal

1. **Multi-select import.** The device picker lets the user select 10 images at once. Options:
   (a) open the editor once per image, in a queue with `1 / 10` progress; (b) apply the auto-detected
   defaults to all silently and let the user edit individual ones later from the list; (c) one editor
   with a filmstrip of the batch. **(b) is our lean** — it is the only one that does not punish the
   bulk case — but the queue in (a) is more discoverable. Pick one and defend it.
2. **QR upload.** When the upload lands, the pairing dialog with the QR code is still on screen and
   the uploader is looking at their phone. Does the editor take over the head-unit screen
   immediately? Does the pairing dialog show a small preview + an **"تعديل"** button? Does the QR
   stay up so the next person can upload?
3. **Should the phone's upload page carry any of this?** It could show a live preview of the fit
   before the file even lands, or a simple mode toggle — the uploader is right there holding the
   image. It is plain HTML we control, so it is cheap. But it splits the settings across two screens
   and two people. **Our lean is no** — keep the phone page dumb, keep every decision on the car
   screen. Push back if you disagree.
4. **"Apply to all"** — worth it, or clutter? It is the technician's tool: 40 images from one
   customer, all reels, all wanting the same treatment.
5. **The bars in Blur mode.** Just the blurred image, or blur **+** a subtle darkening scrim / inner
   shadow / hairline edge where the sharp image meets the blur? A hard seam between sharp and blurred
   at this scale can look like a rendering bug. This is a real call and it is yours.

---

## 10. Deliverables

1. **Layouts for the Fit Editor** at **1920×720 (the priority)**, and at a phone portrait size.
2. **All the states in §5.5.**
3. **The Settings sub-group (Surface B)** in place, in the Wallpapers card.
4. **The mode selector** worked out concretely — this is the control that carries the whole feature.
5. **Focus states** for D-pad, on every control.
6. **Answers to §9.**
7. Final Arabic strings if you can better them.
