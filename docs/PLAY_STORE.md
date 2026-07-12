# Google Play release guide (Android)

This is the operator's checklist for publishing OpenWispr to the Google Play Store. The repo is
already set up to **build a signed AAB** and **auto-upload to the internal testing track via
fastlane** once the one-time setup below is done.

- App: **OpenWispr**, package **`com.voicerewriter`**
- Build: `versionName 1.0.0`, `versionCode 3`, `targetSdk 35`, arm64-v8a only
- Store listing (copy + graphics) lives in `android/fastlane/metadata/android/en-US/`
- Privacy policy: `website/privacy.html` → hosted at `https://<your-site-domain>/privacy.html`

---

## Part A — One-time setup (you, in a browser / Google Cloud)

### 1. Create the app in Play Console
Play Console → **Create app** → name **OpenWispr**, default language English (US), type **App**,
**Free**. Accept the developer program declarations.

### 2. Play App Signing (accept Google's key)
When you upload the first AAB, opt in to **Play App Signing** (default). Google generates and holds
the *app signing key*; our existing release keystore (`openwispr-release.jks`) becomes the *upload
key*. Nothing to change in the repo.

> Note: the Play-distributed build is re-signed by Google, so it has a **different signature** than
> the sideloaded GitHub APK. Users can't cross-update between the two — expected.

### 3. First AAB upload is manual (Google requires it)
Build the bundle and upload it by hand once, so Google opens the publishing API for the package:

```bash
cd android
export OPENWISPR_KEYSTORE_FILE=/path/to/openwispr-release.jks
export OPENWISPR_KEYSTORE_PASSWORD=… OPENWISPR_KEY_ALIAS=openwispr OPENWISPR_KEY_PASSWORD=…
./gradlew :app:bundleRelease
# → android/app/build/outputs/bundle/release/app-release.aab
```

Play Console → **Testing → Internal testing → Create new release** → upload `app-release.aab` →
add tester emails → **Save & publish**. (You can also grab the AAB from a tagged CI run's
`android-aab` artifact.)

### 4. Service account (unlocks fastlane / CI auto-upload)
- Google Cloud Console → the project linked to Play → enable **Google Play Android Developer API**.
- Create a **service account**, then create and download a **JSON key**.
- Play Console → **Users and permissions** → **Invite new users** → the service account email →
  grant release permissions (Admin for the app, or "Release to testing tracks" + "Manage store
  presence").
- Wire it up:
  ```bash
  # CI: store the key as a repo secret (the release workflow picks it up automatically)
  gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json
  # Local: validate auth
  cd android && export PLAY_JSON_KEY_FILE=/path/to/play-service-account.json
  bundle install && bundle exec fastlane whoami
  ```

### 5. Publish the privacy policy
Deploy the website (Railway auto-deploys on push). Confirm `https://<your-site-domain>/privacy.html`
loads, and paste that URL into Play Console → **App content → Privacy policy**.

---

## Part B — Store listing (already drafted in the repo)

Managed as code in `android/fastlane/metadata/android/en-US/`:

| Item | File | Play limit |
|---|---|---|
| App name | `title.txt` | 30 chars |
| Short description | `short_description.txt` | 80 chars |
| Full description | `full_description.txt` | 4000 chars |
| Release notes | `changelogs/3.txt` | 500 chars |
| App icon (512×512) | `images/icon.png` | required |
| Feature graphic (1024×500) | `images/featureGraphic.png` | required |
| Phone screenshots | `images/phoneScreenshots/` | 2–8, ≥320px |

Push these to Play (after Part A) with:
```bash
cd android && export PLAY_JSON_KEY_FILE=/path/to/play-service-account.json
bundle exec fastlane metadata
```

---

## Part C — Data safety form (Play Console → App content → Data safety)

OpenWispr is on-device by default; cloud providers are strictly opt-in and go **directly** from the
user's device to a third party the user configures. Recommended answers:

- **Does your app collect or share any of the required user data types?**
  → **No** for the default configuration. OpenWispr has no servers and no analytics; audio and text
  are processed on-device and not collected by us.
- If you prefer to disclose the optional cloud path conservatively, you may declare **"App
  functionality"** use for **Audio** and **Text**, marked as:
  - Collected: **No** (we operate no servers) / data is **not collected by the developer**.
  - The optional third-party providers process it only at the user's explicit choice under their own
    policies.
- **Data encrypted in transit:** Yes (cloud provider calls use HTTPS).
- **Users can request data deletion:** Data is stored on-device; users clear it in-app or by
  uninstalling.
- **No data is sold or shared for advertising.**

> Rule of thumb: the developer (you) collects nothing. Any transmission is user-initiated, on the
> user's own account/key, straight to the provider they chose.

---

## Part D — Content rating (Play Console → App content → Content rating)

Fill the IARC questionnaire as a **Utility / Productivity** app:
- Category: **Utility, Productivity, Communication, or Other**.
- Violence, sexual content, profanity, controlled substances, gambling: **No** to all.
- User-generated content / social features: **No** (no accounts, no sharing platform).
- Shares user location: **No**.
- Expected result: rated for **Everyone**.

---

## Part E — Other required declarations

- **Target audience & content:** 18+ or 13+ general audience; not directed at children.
- **Ads:** No ads.
- **App access:** All functionality available without a special login (no credentials needed for
  review). Note the app needs the Accessibility permission for auto-insert — the in-app prominent
  disclosure covers this; mention it in review notes if asked.
- **Government/financial/health:** No.

---

## Part F — Ongoing releases (automated)

After Part A is done, each release is:
1. Bump `versionCode` (and `versionName`) in `android/app/build.gradle.kts`.
2. Update `android/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Push a `vX.Y.Z` tag. CI builds the signed AAB and runs `fastlane internal` → uploads to the
   **internal** track automatically.
4. Test via the internal opt-in link, then promote:
   ```bash
   cd android && export PLAY_JSON_KEY_FILE=/path/to/play-service-account.json
   bundle exec fastlane promote_production
   ```
