# Releasing OpenWispr

Pushing a tag like `v0.1.0` runs [`.github/workflows/release.yml`](workflows/release.yml),
which builds the **Android APK** and **macOS DMG** and attaches them to a GitHub Release.

```bash
git tag v0.1.0
git push origin v0.1.0
```

(You can also run the workflow manually from the Actions tab via **Run workflow** — that builds
the artifacts for inspection without publishing a Release.)

## One-time setup: Android signing secrets

The release APK is signed with a keystore that **never lives in the repo** — CI reads it from
GitHub Actions secrets. Generate a keystore once and keep it (and its passwords) safe; if you
lose it, users can't install updates over an existing install.

```bash
keytool -genkeypair -v \
  -keystore openwispr-release.jks \
  -alias openwispr -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<STORE_PASSWORD>' -keypass '<KEY_PASSWORD>' \
  -dname "CN=OpenWispr"

# base64-encode it for the secret
base64 -i openwispr-release.jks | pbcopy   # macOS: now in your clipboard
```

Add four repository secrets (Settings → Secrets and variables → Actions, or `gh secret set`):

| Secret | Value |
|---|---|
| `OPENWISPR_KEYSTORE_BASE64` | the base64 of `openwispr-release.jks` |
| `OPENWISPR_KEYSTORE_PASSWORD` | `<STORE_PASSWORD>` |
| `OPENWISPR_KEY_ALIAS` | `openwispr` |
| `OPENWISPR_KEY_PASSWORD` | `<KEY_PASSWORD>` |

```bash
gh secret set OPENWISPR_KEYSTORE_BASE64    < <(base64 -i openwispr-release.jks)
gh secret set OPENWISPR_KEYSTORE_PASSWORD  --body '<STORE_PASSWORD>'
gh secret set OPENWISPR_KEY_ALIAS          --body 'openwispr'
gh secret set OPENWISPR_KEY_PASSWORD       --body '<KEY_PASSWORD>'
```

Locally, `./gradlew :app:assembleRelease` with these env vars unset produces an *unsigned*
release APK — that's fine; use `assembleDebug` for a runnable local build.

## macOS DMG: self-signed (not notarized)

CI builds the DMG **ad-hoc signed** (`SIGN_IDENTITY="-"`). It runs, but it isn't notarized, so
on another Mac Gatekeeper warns on first launch. The release notes tell users to right-click →
**Open** once, or run `xattr -dr com.apple.quarantine /Applications/OpenWispr.app`.

### Adding notarization later (optional, needs a paid Apple Developer account)

`macos/scripts/package-dmg.sh` already supports it via `DEVELOPER_ID_APP` + `NOTARY_PROFILE`.
To wire it into CI: import a "Developer ID Application" cert into a temporary keychain in the
macOS job, store a notarytool API key as secrets, and set those env vars on the package step.
No rework of the workflow's structure is needed.

## Version numbers

The release name comes from the **tag**. The in-app versions are
`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `macos/App/project.yml` and
`versionName` / `versionCode` in `android/app/build.gradle.kts` — bump those in a commit before
tagging so the binaries report the right version.
