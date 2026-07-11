# source/app/src/ — source sets (flavor system)

Gradle merges `main/` with exactly one flavor directory per build. The flavor dimension
is `appstore`; flavors differ **only** in two classes (+ one manifest addition):

| Source set | `FeatureCheck` behavior | `SettingsActivity` behavior |
|---|---|---|
| `main/` | `BaseFeatureCheck` — holds `unlockedSettings`, persisted as pref `purchased-settings` | `BaseSettingsActivity` — the whole 1600-line settings screen |
| `standalone/` | **unconditionally unlocked** (`unlockedSettings = true`). This is the flavor sold to customers | no billing; manual unlock box accepts any 6-digit code starting `7078` (client-side only, `SettingsActivity.java:83`) |
| `google/` | queries Play Billing for SKU `"settings"` | Play purchase flow + hidden long-press manual unlock via HTTP `R.string.unlock_api` |
| `amazon/` | marks ready only (no query) | Amazon IAP (`PurchasingService`) unlock flow; depends on the `com.amazon.device.iap.ResponseReceiver` declared in the **main** manifest |

Rules for agents:
- Every flavor must compile: if you change the signature of `BaseFeatureCheck` or
  `BaseSettingsActivity`, update **all three** flavor subclasses in the same change.
- `google/AndroidManifest.xml` adds only the `com.android.vending.BILLING` permission.
- The shipped/production flavor is `standalone` (`assembleStandaloneRelease` — what CI
  builds and what the self-update system distributes). Google/Amazon flavors are legacy
  upstream store channels, kept compiling but not currently published by this repo's CI.
