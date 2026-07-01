# TS Wallpapers

Android wallpapers/screensaver app (based on a fork of FsClock-Android) plus a
web-based wallpapers manager and a Supabase-backed self-update system.

## Repository layout

| Path                            | Description                                                        |
| ------------------------------- | ------------------------------------------------------------------ |
| `source/`                       | Android app source (Gradle / Java) — the active working copy       |
| `wallpapers_manager.html`       | Standalone web tool to manage the wallpapers manifest              |
| `wallpapers-manifest-example.json` | Example manifest format consumed by the app                     |
| `supabase_app_update_setup.sql` | One-time Supabase SQL to enable in-app self-update                 |
| `Wallpapers/`                   | Wallpaper image assets                                             |
| `tools/`                        | Helper utilities (manifest server, hashing, samples)              |
| `shots/`                        | Screenshots                                                        |
| `دليل-ذبذبة-ستور.md`            | Store publishing guide (Arabic)                                    |

## Building the app

```bash
cd source
./gradlew assembleRelease
```

### Required local secrets (NOT in the repo)

These files are intentionally git-ignored and must exist locally to build a
signed release:

- `source/local.properties` — SDK path + signing credentials:
  ```properties
  sdk.dir=/path/to/Android/Sdk
  KEYSTORE_PASSWORD=...
  KEY_ALIAS=...
  KEY_PASSWORD=...
  ```
- `source/thabthaba.jks` — the release signing keystore (keep it backed up
  securely; losing it means you can't update the published app).

## Supabase / self-update

`.mcp.json` and Supabase access tokens are git-ignored. Run
`supabase_app_update_setup.sql` once in the Supabase SQL Editor, then publish
new versions into the `app_versions` table.

## Notes

- `src/` (a stale upstream clone with its own `.git`) is excluded from this repo.
- Build outputs (`build/`, `.gradle/`) are git-ignored.
