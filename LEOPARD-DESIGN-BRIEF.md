# Leopard Mode — Design Brief (TS Wallpapers / ذبذبة خلفيات)

> **Scope:** design a **new operating mode** for the app, codenamed **Leopard**. It is unlocked by a
> checkbox at activation time and, when on, **replaces the app's entire purpose**: instead of the app
> *displaying* wallpapers on its own fullscreen screen, it becomes a **picker** that hands the chosen
> image/video to **Android itself** as the system wallpaper. The app then no longer needs to be running.
>
> What you are designing: **the picker screen** (the heart of this brief), the **activation checkbox**,
> its **Settings counterpart**, and the **states** around Android's system wallpaper hand-off.
>
> **Note on text:** all UI strings are given verbatim in Arabic (the app is Arabic-first and RTL) with
> an English translation in parentheses. The Arabic is what ships — use it to judge line length and
> wrapping. Strings marked *(proposed)* are not final; suggest better ones if you can.

---

## 0. Read this first — corrections to the existing briefs

Two other briefs exist at the repo root: `SETTINGS-DESIGN-BRIEF.md` and `LOCAL-IMPORT-DESIGN-BRIEF.md`.
They are useful for house style, **but this brief overrides them on three points**:

1. **This product is for cars only.** Those briefs mention phones, tablets, TV and Fire TV as targets.
   For Leopard: **there is no phone layout, no portrait, no TV.** The device is a **wide car head-unit**
   screen and nothing else. Any design that assumes otherwise is rejected.
2. **The activation screen is not gold.** `SETTINGS-DESIGN-BRIEF.md` lists gold `#E6BE6A` as the
   "activation dialog" color. That is wrong. The activation overlay is **teal `#00FFCC`** on a
   `#E6000000` scrim (verified in `view_fsclock.xml:232-307`). Gold `#E6BE6A` appears **only** in the
   QR upload web page (`UploadServer.java:175`), which is a different surface entirely.
3. **The activation "dialog" is not a dialog.** It is a full-screen `LinearLayout` overlay baked into
   the main clock view, shown by toggling visibility. That constrains where the new checkbox can go.

---

## 1. Product context

- **App:** TS Wallpapers (ذبذبة خلفيات), brand **THABTHABA STORE**. Package `store.thabthaba.clock`.
- **Device:** an **Android car head-unit**, installed in the dash by a technician at a shop.
- **The screen is wide and short.** The governing size is **FSE = 1920×720 ≈ 2.67:1**. Other common
  head-unit sizes: 1280×480 (2.67:1), 1024×600 (1.71:1). **Design for the wide band.**
- **Input:** touch, **and also D-pad** (many head units have a rotary/steering-wheel controller).
  Every interactive element needs a strong **focus** state — focus, not hover.
- **Language:** Arabic-first, **RTL** (`supportsRtl=true`).
- **Users:** the **installer technician** at the shop (sets it up once, may do 40 cars a week), and
  the **car owner** (will touch it rarely, and is sitting in a car).
- **Hardware is weak.** Cheap SoC, little GPU budget, and often a slow or absent data connection.

---

## 2. What Leopard is, and why

### Today (without Leopard)
The app owns the screen. `FullscreenActivity` runs, and inside it a custom view draws the wallpaper
plus a **clock overlay** (analog and/or digital), **date**, and **weather**. It cycles through a
playlist on a timer. **If the app is not open, there is no wallpaper.**

### With Leopard on
The app **stops displaying anything**. It hands the chosen file to Android's own wallpaper system
(`WallpaperService` / `WallpaperManager`). Android then draws it as the **device wallpaper** —
behind the launcher, the icons, everything — **whether or not our app is running**.

So Leopard converts the product from a **wallpaper player** into a **wallpaper picker**.

> **This is the mental model to design around:** in Leopard the app is a tool you open, use for
> fifteen seconds, and close. It is not a place you look at. Everything about the picker should
> respect that — it is a **transactional** screen, not a lean-back one.

### The direct consequence
The clock, the weather, the date, the auto-switch timer, and the position-adjust gesture **have no
meaning in Leopard** — Android is drawing the wallpaper, not us, and we cannot draw on top of it.
**See §8 — this is the biggest product question in this brief.**

---

## 3. How Leopard gets turned on

### 3.1 At activation (the primary path)

The technician enters a serial to activate the device. The activation overlay
(`view_fsclock.xml:232-307`) is a full-screen `LinearLayout`, `#E6000000` scrim, content top-centered
with 72dp top padding. It currently contains, in order:

| Element | Verbatim string | Style |
|---|---|---|
| Title | `تفعيل البرنامج` *(Activate the app)* | `#00FFCC`, 32sp, bold |
| Subtitle | `يرجى إدخال الرقم التسلسلي لتفعيل البرنامج:` *(Please enter the serial number to activate)* | white |
| Serial field | hint `******` | 260dp wide, `numberPassword` |
| **`checkBoxFse`** | **`FSE`** | white text 16sp, `buttonTint="#00FFCC"` |
| Activate button | `تفعيل الآن` *(Activate now)* | bg `#00FFCC`, black text, 260dp |
| Status line | *(errors)* | `#FF4444`, initially gone |
| Device ID | *(the hardware id the shop needs to issue a serial)* | below the card |

**You are adding a second checkbox, `Leopard`, next to `FSE`.**

Notes you need:
- **Every string in this overlay is hardcoded**, not a string resource, and **not translated** —
  including the bare token `FSE`. There is no helper text explaining what `FSE` even means. If you
  want `Leopard` to have an explanation, say so; it is cheap to add.
- **`FSE` and `Leopard` are almost certainly mutually exclusive.** FSE forces our app's *window* to a
  fixed 1920×720; Leopard means our app has no display window at all. **Our lean: exclusive** — two
  radio-style choices, or a 3-way選 (`عادي` / `FSE` / `Leopard`). Push back if you see it differently,
  but the design must not let both be checked without answering what that means.
- Two checkboxes side by side in a vertical stack of centered 260dp controls will look accidental.
  This part of the overlay needs actual design, not a second `<CheckBox>` glued on.

### 3.2 In Settings (the secondary path)

FSE also has a checkbox in Settings — `checkBoxFseSettings`, labelled **`FSE (1920×720)`**, sitting
under the `@string/general` section header next to `checkBoxKeepScreenOn`
(`activity_settings.xml:243`). Leopard needs the same, for the technician who forgot to tick it.

> **Existing defect, do not copy it:** the two FSE checkboxes are labelled **differently** (`FSE` in
> activation vs `FSE (1920×720)` in Settings) and can silently disagree. Leopard's two checkboxes must
> use **one identical label** and stay in sync.

---

## 4. The Leopard picker — **this is the heart of the brief**

When Leopard is on, opening the app leads to a wallpaper picker. The requested structure:

| Position | Control | Opens |
|---|---|---|
| **Screen left** | a **circle** with a **cloud** icon ☁ | the **Supabase** library — a grid of the images/videos synced to this car (§5) |
| **Screen right** | a **circle** with a **local files** icon | the head-unit's own storage, via the Android system file picker (§6) |
| **Below** | button `تعيين الخلفية` *(Set as wallpaper)* | applies the current selection (§7) |

### RTL warning — read carefully
The app is RTL. **"Left" and "right" above are literal visual positions on the glass**, not
`start`/`end`. Cloud is on the **left half of the physical screen**. **Our lean: they stay pinned to
those physical sides regardless of layout direction** — but this is exactly the kind of thing that
silently mirrors when a `LinearLayout` inherits RTL, so **state the intent explicitly in your spec.**

### The layout problem
The screen is **2.67:1 — extremely wide and short**. Two big circles and a grid and a primary button
must coexist in a 720px-tall band. A grid of wallpaper thumbnails on a 2.67:1 screen wants to be a
**horizontal filmstrip**, not a square grid — but a filmstrip of ~2.67:1 thumbnails gets you very
few per row. **Resolving this is the main design problem of this brief.**

Consider: are the two circles a permanent **source switcher** that stays on screen next to the grid?
Or a **landing choice** that disappears once you pick a source? (The second is what the described
flow implies, but the first is more recoverable.) Make the call and defend it.

### States you must design
| State | Notes |
|---|---|
| **Landing** | Before a source is chosen. Two circles. What fills the rest of this very wide screen? |
| **Cloud — loading** | Slow connection is the norm in a car. |
| **Cloud — empty** | The car has been activated but no wallpapers assigned to it yet. Common on a fresh install. |
| **Cloud — network failure** | Offline entirely. Note there is a **local cache** — see §5. |
| **Cloud — populated, nothing selected** | |
| **Cloud — an item selected** | How is selection shown? See §10.3. |
| **Local — returned from the system picker with a file** | |
| **Applying** | Especially a large video: it must be decoded/copied. This is visibly slow on this hardware. |
| **Applied — image** | Silent success. What confirms it? The app can't show the result — Android owns the screen now. |
| **Applied — video, first time ever** | A **system screen** appears. See §7. |
| **Not activated** | The activation overlay covers everything. Existing behavior. |
| **Device doesn't support live wallpaper** | See §9. |
| **D-pad focus** | Every element, including the two circles and every grid cell. |

---

## 5. The cloud source

### What the data actually is
Wallpapers come from Supabase via a `SECURITY DEFINER` RPC, `get_wallpapers`, called over plain HTTP
(`WallpaperRepo.java:208`). It returns **only two columns**:

```
url   text
type  text     -- 'image' | 'video'
```

**That is the entire data model.** Design accordingly:

- **No thumbnails exist on the server.** A grid cell must load the **full-size original** and downscale
  it locally (via Glide, which is already in the project). On a weak SoC over a weak connection, a grid
  of 40 full-size images is genuinely slow. **Your grid design must survive slow, staggered loading** —
  and should probably not try to show 40 cells at once.
- **No names, no titles, no categories, no dates, no tags.** There is nothing to label a cell with.
  It is a grid of bare pictures. If your design needs metadata, say so explicitly and treat it as a
  **schema change** (a real cost — it touches the DB, the RPC, and the admin upload tool).
- **Videos have no poster frame.** A `type: 'video'` row is a URL to an MP4 and nothing else.
  Extracting a first frame on this hardware is expensive. See §10.4.
- **There is a local cache.** The last successful response is cached (`wallpaper-cache-json`), and
  **videos are pre-downloaded** to `cacheDir/wallpapers/vid_<hex>.bin`. So an offline car may still
  have a usable library. Your "network failure" state should distinguish **"offline, showing cached"**
  from **"offline, nothing cached"**.
- **A per-car hide list exists** (`wallpaper-hidden-urls`, local only) — the owner can hide wallpapers
  they don't want in the rotation. **Open question: does the Leopard picker respect it?** In the old
  mode "hidden" meant "skip in the slideshow". In Leopard there is no slideshow, so the concept may
  not transfer. Our lean: **ignore the hide list in Leopard** — but say what you think.

---

## 6. The local source

There is **already a working system file picker** in the app (`BaseSettingsActivity.java:1296`):
`ACTION_OPEN_DOCUMENT`, multi-select on, filtered to `image/*` + `video/*`.

- **The picker itself is an Android system screen. You are not designing it.** It looks how the head
  unit's ROM makes it look, and we cannot change that. Assume it is ugly and inconsistent with us.
- **What you *are* designing is the seam:** the user taps our circle, the screen is replaced by a
  system UI we don't control, and then they land back in our app. **Where do they land, and how do
  they know their file arrived?** Today's equivalent flow dumps the user back on Settings with **no
  confirmation of any kind** — that is a defect, don't reproduce it.
- **Multi-select is on in the existing code.** In Leopard, only **one** file can be the wallpaper.
  Either turn multi-select off, or design what happens when 10 files come back. **Our lean: single
  select in Leopard** — it matches the mental model and removes a whole class of states.

---

## 7. The hand-off to Android — **the hard constraint**

This section is a platform reality, not a preference. **The design must absorb it.**

The requirement was "apply to home screen and lock screen together, without asking the user."
**Android permits that for still images, and forbids it for video.**

### Still image → fully silent ✅
`WallpaperManager.setBitmap(bmp, null, true, FLAG_SYSTEM | FLAG_LOCK)` sets home **and** lock in one
call, immediately, with **no system screen and no question**. Exactly what was asked for.

### Video / GIF → one unavoidable system screen ⚠️
A moving wallpaper must be a **live wallpaper**, and Android **will not** let an app install itself as
the live wallpaper silently. It **must** launch `ACTION_CHANGE_LIVE_WALLPAPER`, which opens the
**system's own preview screen** where the user presses **the system's own "Set wallpaper" button**.
There is no API to skip this. It is a security boundary.

**But it happens only once.** After our wallpaper service is the active live wallpaper, it stays
active. It watches for changes to the stored selection and **reloads itself silently**. So:

> **First video ever → system screen. Every video after that → instant and silent.**

**Also:** a live wallpaper generally **cannot be applied to the lock screen** on most Android versions.
So a video covers the **home screen only**, while an image covers **both**. This asymmetry is real and
the design should not promise otherwise.

### What you must design
1. The **silent image path** — the success case with no UI to hang feedback on. The app has just made
   itself invisible and irrelevant. What does the user see? (A toast? A state change on the button?
   Does the app close itself?)
2. **The first video.** The user presses `تعيين الخلفية` and is thrown into an unstyled system screen
   with an English button. **This is the worst moment in the whole feature.** Do we warn them first?
   Do we explain what to press? Do we say "this only happens once"?
3. **The honest promise.** If the copy says "home and lock", it is a lie for videos. Find copy that is
   true for both cases without being a paragraph.

---

## 8. What happens to the app's existing features — **the biggest product question**

In Leopard, Android draws the wallpaper. **We cannot draw on top of it.** So:

| Feature | In Leopard | Why |
|---|---|---|
| Analog clock | **Gone** | We have no surface to draw on |
| Digital clock | **Gone** | Same |
| Date | **Gone** | Same |
| Weather (+ GPS city) | **Gone** | Same |
| Clock layout / colors / designs | **Gone** | Nothing to lay out |
| Auto-switch timer (`wallpaper-auto-switch-interval`, 30s–1h) | **Gone** | There is no slideshow; the system wallpaper is one static choice |
| Long-press position adjust + per-image focal point | **Gone** | That gesture lives on our fullscreen view, which no longer runs |
| Screensaver / daydream mode | **Gone** | Same reason |

**That is most of the Settings screen.** The Settings screen is currently two columns of cards —
General, Analog clock, Wallpapers, Clock layout, Digital clock, Background, About — and Leopard
**invalidates the majority of them**.

**Decide and defend:**
- Do those settings **disappear** when Leopard is on? (Clean, but the screen becomes a near-empty
  husk, and the technician may think the app broke.)
- Do they **grey out** with an explanation? (Honest and teachable, but 40+ disabled rows is grim, and
  `SETTINGS-DESIGN-BRIEF.md` flags "everything visible but disabled" as an **existing defect being
  fixed**, so repeating it here is going backwards.)
- Does Settings become a **different, much smaller screen** in Leopard?

**This is the single most important thing to get right after the picker itself.** Leopard isn't a
setting — it's a different product. The Settings screen should probably admit that.

---

## 9. Technical constraints — hard

These will kill a design that ignores them.

- **Java + XML only.** No Jetpack Compose. No Kotlin. No Fragments. Views are `LinearLayout` /
  `FrameLayout` / `ScrollView` / `RecyclerView` / `ImageView` / `CheckBox` / `Button` / Material
  `CardView`, plus custom `View` subclasses. *(The reference implementation we're porting is written
  in Kotlin; it will be translated to Java. That is our problem, not yours — but it means no
  Kotlin-only UI toolkit is available.)*
- **minSdk 21** (Android 5.0). Nothing may *require* API > 21.
  - **GIF caveat:** animated GIF playback as a wallpaper needs `AnimatedImageDrawable`, **API 28+**.
    On older head units **a GIF is a still image** (first frame only). Design must tolerate a GIF
    silently not animating on some cars. Do we tell the user? Our lean: yes, quietly.
- **Not every head unit supports live wallpaper at all.** Some cheap ROMs ship without the live
  wallpaper picker. This must be a **runtime check** with a real designed state:
  **"this device does not support Leopard"** — checkbox disabled, with an explanation. It cannot be
  a crash and it cannot be a silent no-op.
- **Video playback** is `MediaPlayer` rendering straight onto the wallpaper surface: **muted**,
  **looping**, center-cropped. Wallpaper video is always silent — that is a platform norm, not a
  choice. Don't design a volume control.
- **Weak hardware.** Prefer flat fills. Avoid stacked translucency, blurs, large animated surfaces,
  and long cross-fades. A grid of live-decoding thumbnails is already near the budget.

### Palette actually in the code

| Token | Value | Where it is really used |
|---|---|---|
| **Teal** | `#00FFCC` | **The activation overlay** — title, checkbox tint, the Activate button |
| Scrim | `#E6000000` | The activation overlay background |
| Error red | `#FF4444` | Activation status line |
| Orange | `#ff9a3d` | Settings section headers, borderless buttons |
| Light grey | `#ECEFF1` | Settings screen background |
| Cards | White, 18dp corners, 3dp elevation, 18dp padding | Settings cards |
| Gold | `#E6BE6A` | **Only** the QR upload web page — *not* the activation screen |

The app has **two clashing identities**: a black-and-teal activation overlay vs. a white-and-grey
Settings screen. `SETTINGS-DESIGN-BRIEF.md` names this as a problem to solve. The Leopard picker is a
full-screen surface showing photographs, which argues for **dark** chrome. **Say which side you pick
and why** — and whether Leopard is a chance to settle the identity question for the whole app.

---

## 10. Open questions — please answer these in your proposal

1. **FSE + Leopard together — allowed?** *(Our lean: no, mutually exclusive. See §3.1.)*
2. **Does Leopard skip straight to the picker on launch,** or is there an intermediate screen? Remember
   the app has nothing else to show in Leopard — there is no wallpaper screen behind it anymore.
3. **How is the currently-set wallpaper indicated in the grid?** The user opens the picker and one of
   these 40 images is already live on their dash. Do we mark it? *(We can know: the chosen URI is
   stored. But note that after a factory wallpaper change by the user, our record may be stale.)*
4. **Video cells in the grid.** A generic placeholder + a video badge (cheap, honest, ugly)? Or extract
   a first frame (expensive on this SoC, and it needs the whole file downloaded first)? *(Our lean:
   placeholder + badge, given the hardware.)*
5. **The right-hand circle's icon.** The original request said "a phone icon" — but **this product has
   no phone in it anywhere**. That circle opens the **head-unit's own local storage**. A phone icon
   would mislead the technician. Alternatives: a folder, an SD card, an HDD, a "device" glyph.
   **Propose one and justify it.** Also: do the two circles need text labels beneath them, or is the
   iconography enough for a technician who has never seen this screen? *(Our lean: they need labels —
   `الخلفيات المتاحة` *(Available wallpapers)* / `من الجهاز` *(From the device)*, both proposed.)*
6. **§8 — hide, disable, or replace** the settings that Leopard invalidates?
7. **Does the picker respect the per-car hide list?** *(§5. Our lean: no.)*
8. **What does the app do after a wallpaper is successfully set?** It has just made itself redundant.
   Stay open on the picker? Close? Show a confirmation and then close?

---

## 11. Deliverables

1. **The picker screen at 1920×720** — the governing size, the priority. Plus **1280×480** and
   **1024×600** so the design isn't pinned to one aspect. **No portrait. No phone. No TV.**
2. **All the states in §4.**
3. **The activation overlay** with the `Leopard` checkbox designed in properly (§3.1) — including how
   it and `FSE` relate visually.
4. **The Settings counterpart** (§3.2), and your answer to §8 shown as a layout.
5. **The video hand-off moment** (§7.2) — the warning/explanation before the system screen.
6. **D-pad focus states** on every control, including the two circles and grid cells.
7. **Answers to §10.**
8. Final Arabic strings if you can better the *(proposed)* ones.

---

## Appendix — reference implementation

A minimal, complete, working version of the Leopard mechanism exists as a standalone Android project
at `ts - engine/MiniWallpaper/`. It is **three files** and is what we are porting:

- `MainActivity.kt` — opens the system picker, takes a **persistable** URI permission (so the choice
  survives a reboot), stores the `content://` URI string in `SharedPreferences`, and launches
  `ACTION_CHANGE_LIVE_WALLPAPER`.
- `MediaWallpaperService.kt` — a `WallpaperService` that reads that URI, branches on MIME type
  (image → `Bitmap` on the surface `Canvas`; GIF → `AnimatedImageDrawable`; video → `MediaPlayer` on
  the surface), draws it center-crop, pauses when hidden to save battery, and **reloads itself
  automatically** when the stored URI changes.
- `res/xml/wallpaper.xml` + manifest entry — registers the service as a live wallpaper the system can
  offer.

**It has no UI worth looking at** — two buttons and an `ImageView`. It is included only to show that
the mechanism is proven and to make the constraints in §7 concrete. **Design the picker from this
brief, not from that app.**
