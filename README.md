# TS Wallpapers

Android wallpapers/screensaver app (based on a fork of FsClock-Android) plus a
web-based wallpapers manager and a Supabase-backed self-update system.

> **Full system documentation:** see [AGENTS.md](AGENTS.md) — architecture, data flows,
> editing invariants, and known issues. Each major directory also has its own README.

## Repository layout

| Path                            | Description                                                        |
| ------------------------------- | ------------------------------------------------------------------ |
| `AGENTS.md`                     | System map: subsystems, data flows, invariants, known issues       |
| `source/`                       | Android app source (Gradle / Java) — the active working copy       |
| `wallpapers_manager.html`       | Admin web dashboard (wallpapers, devices, app updates) — deployed to Vercel |
| `wallpapers-manifest-example.json` | Example static-manifest format the app can also consume         |
| `supabase_app_update_setup.sql` | One-time Supabase SQL to enable in-app self-update                 |
| `Wallpapers/`                   | Wallpaper image assets                                             |
| `tools/`                        | Dev-only test utilities (local manifest server, samples)           |
| `shots/`                        | Historical dev screenshots                                         |
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

- `src/` (a stale upstream clone with its own `.git`) is excluded from this repo —
  the active app lives in `source/`.
- `source/README.upstream.md` is the original FsClock-Android README, kept for
  attribution; fork documentation lives in `source/README.md` and `AGENTS.md`.
- Build outputs (`build/`, `.gradle/`) are git-ignored.
