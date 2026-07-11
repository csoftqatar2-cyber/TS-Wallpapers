# fastlane/ — store-listing metadata (⚠️ currently stale)

Standard fastlane/Triple-T supply layout:
`metadata/android/<locale>/{title.txt, short_description.txt, full_description.txt}`,
plus `en-US/images/` (icon, feature graphic, 5 phone screenshots).

Locales present: `de-DE, en-US, es-ES, fr-FR, it-IT, iw-IL, nl-NL, ru-RU`.

**Everything here still describes the upstream FsClock clock app** ("Clock Screensaver &
Widget"), not TS Wallpapers — no mention of wallpapers, activation, or Arabic branding.
Nothing in this repo's CI consumes these files; they are inherited from upstream's
F-Droid/Play pipeline and are currently unused dead weight.

For agents:
- Do not treat these texts as an accurate feature description of the app. The accurate
  description is in the repo-root `AGENTS.md` and the Arabic guide `دليل-ذبذبة-ستور.md`.
- If the app is ever published to a store, every locale file here needs rewriting first.
- If you update the description in one locale, keep the title/short/full triple
  consistent within that locale; other locales may keep old text (fastlane treats each
  locale independently).
