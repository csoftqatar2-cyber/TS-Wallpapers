---
name: ts-wallpapers-release
description: >
  Ship a new TS Wallpapers version to the fleet — bump the version, write the Arabic
  commit subject that becomes the user-visible changelog, push, and verify CI actually
  published. Encodes the traps that silently break a release: a docs commit pushed after
  the bump stealing the changelog, republish on a build that already reached cars, and
  re-signing with a different key wiping activation. Use whenever the task is "انشر",
  "خليه يوصل للناس", "release", "publish", "ship a version", or "bump the version".
version: '1.0'
---

# TS Wallpapers release

Publishing is one action: **push a versionCode bump to `main`**. CI does everything else.
That is also why it is dangerous — the push IS the release to ~530 cars in the field.

## The release, step by step

### 1. Bump both versions

[`source/app/build.gradle:19-20`](../../../source/app/build.gradle) — the only source of truth.

```gradle
versionCode 167      // +1, always. Globally monotonic, must stay above 113.
versionName "6.1"    // +0.1 per release
```

Never reuse or lower a `versionCode`. Android refuses to install over an equal-or-lower
one, so a mistake here strands every car that already took that code — permanently.

### 2. Write the commit subject in Arabic — it is the changelog

CI copies `git log -1 --pretty=%s` into `app_versions.changelog`, which is what the
customer reads in the in-app update prompt. Match the house style from the history:

```
v6.1/167: الفيديو بقى يشتغل من أول مرة في وضع الليوبارد
```

Describe **what the driver will notice**, not what changed in the code. No file names,
no class names, no English.

### 3. ⚠️ The bump must be the LAST commit in the push

CI reads the subject of the pushed HEAD, not of the commit that changed `build.gradle`.
Pushing `[v6.1 bump] → [توثيق: ...]` together publishes v6.1 with **"توثيق: ..."** as the
changelog every customer sees. If a docs/cleanup commit is needed, push it *before* the
bump, or in a separate later push (which publishes nothing, since versionCode didn't move).

### 4. Push and watch the gate

```bash
git push origin main
```

CI (`.github/workflows/release.yml`) then:
1. **Gate** — publishes only if this versionCode > the highest row in `app_versions`.
   Not higher → `⏭️ nothing to deliver`, job ends green. A green run is *not* proof of a release.
2. Builds `:app:assembleStandaloneRelease` signed with `thabthaba.jks` (from GitHub secret).
3. Uploads to Cloudflare R2 as `apk/ts-wallpapers-<versionName>-<versionCode>.apk`.
4. Inserts the `app_versions` row → cars on older codes get the in-app update badge.

```bash
gh run watch
```

### 5. Verify it actually landed

Read the run summary, or confirm the row exists (Supabase MCP `execute_sql`):

```sql
select version_code, version_name, changelog, apk_url
  from public.app_versions order by version_code desc limit 3;
```

### 6. Optional: refresh the tracked APK at the repo root

`TS Wallpapers.apk` at the root is a **local** build artifact — a gradle `doLast` copies it
after a local release build. **CI never updates it.** To keep it in sync, build locally and
commit it on its own (precedent: `5f9c1d5`):

```bash
cd source && ./gradlew :app:assembleStandaloneRelease
```

This is cosmetic. Skip it if the local build fights the OneDrive path (see caveats).

## Traps

- **Do not bump versionCode on a work-in-progress commit.** Any push to `main` carrying a
  higher versionCode is a release. Park unfinished work on a branch.
- **`republish=true` (manual `workflow_dispatch` only)** replaces the APK under the *same*
  versionCode. Only safe if **zero** cars installed it — Android compares version codes, so
  a car that already took the old copy stays on it forever. When in doubt, bump instead.
- **Never re-sign with a different key.** `SecurePrefs` HMAC is derived from the signing
  cert; a new key invalidates activation on every installed device at once.
- **Only the `standalone` flavor ships.** `google`/`amazon` exist but CI never builds them.
- The changelog string is escaped by CI with a plain `sed 's/"/\\"/g'` — avoid double
  quotes in the commit subject.

## Caveats

- The repo path (`TS WALLPAPERS`) contains spaces, which historically breaks local Gradle
  builds (AGENTS.md §7.8). Local builds do succeed in practice — but if gradle fails oddly,
  that is the first suspect, and CI is always the reliable path.
- Secrets required by CI live only as GitHub Actions secrets (`SIGNING_KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SUPABASE_SERVICE_ROLE_KEY`,
  `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`). `setup-ci-secrets.sh` (untracked) pushes them.

## Related

- `ts-backend-rpc-change` — if the release depends on a backend change, apply the backend
  **first**; old APKs must keep working against the new backend.
- `ts-device-triage` — verifying a specific car actually received the update.
