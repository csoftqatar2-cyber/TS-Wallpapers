# .github/workflows/ — CI: build & publish APK updates

One workflow: [release.yml](release.yml). **Every push to `main` is a potential
production release** — understand the gate before pushing.

## What it does

1. **Gate:** greps `source/app/build.gradle` (comment lines stripped first) for
   `versionCode`/`versionName`, then reads the highest `version_code` from the Supabase
   `app_versions` table (anon key, read-only). Publishes **only if the gradle
   versionCode is strictly greater**; otherwise the run ends as a no-op.
2. Decodes GitHub secret `SIGNING_KEYSTORE_BASE64` to `source/thabthaba.jks`.
3. Builds `:app:assembleStandaloneRelease` (JDK 17, signing passwords injected as env
   vars — gradle's `local.properties`→env fallback picks them up).
4. Uploads the APK to the public Supabase `apk` storage bucket as
   `ts-wallpapers-<versionName>-<versionCode>.apk` (service_role key, `x-upsert: true`).
5. Deletes any existing `app_versions` row with the same version_code, then inserts a
   fresh row. **`changelog` = the last commit subject line** → shown to end users in
   the in-app update dialog. Write version-bump commit subjects accordingly.
6. Devices polling `app_versions` (`UpdateManager`) then offer the update in-app.

## Secrets (repo Settings → Actions)

`SIGNING_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
`SUPABASE_SERVICE_ROLE_KEY`. Provisioned by the untracked repo-root helper
`setup-ci-secrets.sh`. The `SUPABASE_URL` and anon key are hardcoded in the workflow
env block — the anon key is public by design.

## Hardcoded paths (update this workflow if any of these move)

- `source/app/build.gradle` (version grep)
- `source/thabthaba.jks` (keystore target)
- `source/` as working dir, `./gradlew`
- `source/app/build/outputs/apk/standalone/release/TS Wallpapers.apk` (upload source —
  this exact name comes from the output-rename block in `source/app/build.gradle`)

## Failure modes to check first

- "nothing to deliver" → versionCode wasn't bumped (or grep matched nothing after a
  build.gradle refactor — keep real versionCode/versionName on plain uncommented lines).
- Upload/insert HTTP errors → service_role secret missing/rotated, or Supabase policy changes.
