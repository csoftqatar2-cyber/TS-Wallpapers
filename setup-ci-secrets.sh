#!/usr/bin/env bash
# ============================================================
# One-time setup: store the 5 GitHub Actions secrets needed by
# .github/workflows/release.yml so the push -> auto-update
# pipeline can build a SIGNED APK and publish it to Supabase.
#
# Run ONCE from the project root:   bash setup-ci-secrets.sh
#
# This script contains NO secrets itself — it reads the keystore
# and passwords from your local (git-ignored) files, and prompts
# you to paste the Supabase service_role key.
# ============================================================
set -e

REPO="csoftqatar2-cyber/TS-Wallpapers"
KEYSTORE="source/thabthaba.jks"
LOCALPROPS="source/local.properties"

echo "Setting GitHub Actions secrets on $REPO ..."

# --- sanity checks -----------------------------------------
command -v gh >/dev/null || { echo "❌ GitHub CLI (gh) not found. Install it or run: gh auth login"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "❌ Not logged in. Run: gh auth login"; exit 1; }
[ -f "$KEYSTORE" ]   || { echo "❌ $KEYSTORE not found (run from project root)."; exit 1; }
[ -f "$LOCALPROPS" ] || { echo "❌ $LOCALPROPS not found."; exit 1; }

# --- read keystore passwords from local.properties ----------
getprop() { grep -E "^$1=" "$LOCALPROPS" | head -1 | cut -d= -f2- | tr -d '\r'; }
KS_PASS="$(getprop KEYSTORE_PASSWORD)"
KEY_ALIAS="$(getprop KEY_ALIAS)"
KEY_PASS="$(getprop KEY_PASSWORD)"
[ -n "$KS_PASS" ] && [ -n "$KEY_ALIAS" ] && [ -n "$KEY_PASS" ] || {
  echo "❌ Could not read KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD from $LOCALPROPS"; exit 1; }

# --- 1..4: keystore + passwords ----------------------------
base64 -w0 "$KEYSTORE" | gh secret set SIGNING_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$KS_PASS"   | gh secret set KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set KEY_ALIAS -R "$REPO"
printf '%s' "$KEY_PASS"  | gh secret set KEY_PASSWORD -R "$REPO"
echo "✅ keystore + passwords set."

# --- 5: Supabase service_role (paste once) ------------------
echo ""
echo "Now paste your Supabase service_role key."
echo "(Supabase Dashboard -> Project Settings -> API -> service_role)"
read -r -s -p "service_role key: " SR_KEY
echo ""
[ -n "$SR_KEY" ] || { echo "❌ empty key, aborting."; exit 1; }
printf '%s' "$SR_KEY" | gh secret set SUPABASE_SERVICE_ROLE_KEY -R "$REPO"
echo "✅ SUPABASE_SERVICE_ROLE_KEY set."

echo ""
echo "🎉 Done. All 5 secrets are configured. Verifying:"
gh secret list -R "$REPO"
echo ""
echo "From now on: bump versionCode + versionName in source/app/build.gradle, then push. Users get the update automatically."
