# tools/ — dev-only test utilities (nothing here ships)

Nothing in this directory is part of the app build, CI, or deploy. It exists to test the
app's wallpaper-manifest feature locally.

| File | Purpose |
|---|---|
| `ManifestServer.java` (+ `.class`) | ~40-line `com.sun.net.httpserver` server on **port 8085** serving `/manifest.json` with a hardcoded 4-item wallpaper playlist (picsum images, a GIF, an MP4). Run it, then point the app's manual manifest URL (`wallpaper-manifest-url`) at `http://<host>:8085/manifest.json` to test remote-manifest mode without Supabase. |
| `CLOCK.xml` | Sample SharedPreferences export for the app's pref domain `"CLOCK"` — seeds wallpaper-enabled state, the localhost manifest URL above, cached playlist, clock position/size. Useful as a reference for pref key names or to push onto a test device. |
| `HashCalc.java` (+ `.class`) | Prints `Integer.toHexString(url.hashCode())` for a sample video URL — computes the app's video-cache filename (`cacheDir/wallpapers/vid_<hex>.bin`) so you can find/verify cached files. |
| `sample-5s.mp4` | Real ~2.8 MB test video (referenced by CLOCK.xml's cached playlist). |
| `ForBiggerBlazes.mp4` | ⚠️ 706-byte truncated stub — NOT playable. The real video is the Google-hosted URL in ManifestServer.java. |
| `pdfbox-app.jar` | Standalone Apache PDFBox CLI (~13 MB), used ad hoc for PDF/store-asset work. Unreferenced by any build. |

The `.class` files are committed compiled outputs of the adjacent `.java` files
(compile with plain `javac`, no dependencies).
