# Wallpapers/ — wallpaper image assets (content, not code)

A local stash of wallpaper images (JPG/PNG). **Nothing consumes this directory
programmatically**: it is not read by the Android build, not deployed to Vercel
(excluded via `.vercelignore`), and not referenced by CI.

Distribution path for these images: an admin uploads them manually through the
wallpapers manager dashboard (`wallpapers_manager.html`), which routes them through the
`admin-upload` Edge Function into the Supabase `wallpapers` storage bucket with a random
UUID filename, and inserts a `wallpapers` table row (global or per-device). Devices then
receive them via the `get_wallpapers` RPC.

For agents: treat this as an asset inbox/archive. Safe to add images; do not assume
adding a file here has any runtime effect. The similar-looking git-ignored
`MAC-304AC4239A25/` directory at the repo root is a raw photo dump for one specific
device (named after its hardware id) — same idea, local-only.
