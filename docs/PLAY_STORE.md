# Google Play release guide (Android)

This is the operator's checklist for publishing OpenWispr to the Google Play Store. The repo is
already set up to **build a signed AAB** and **auto-upload to the internal testing track via
fastlane** once the one-time setup below is done.

- App: **OpenWispr**, package **`com.voicerewriter`**
- Build: `versionName 1.0.1`, `versionCode 4`, `targetSdk 36`, arm64-v8a only
- Store listing (copy + graphics) lives in `android/fastlane/metadata/android/en-US/`
- Graphics are generated from HTML — see `tools/store-assets/`, not a design tool
- Privacy policy: `website/privacy.html` → hosted at `https://openwispr.dev/privacy.html`

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
Deploy the website (Railway auto-deploys on push). Confirm `https://openwispr.dev/privacy.html`
loads, and paste that URL into Play Console → **App content → Privacy policy**.

---

## Part B — Store listing (already drafted in the repo)

Managed as code in `android/fastlane/metadata/android/en-US/`:

| Item | File | Play limit |
|---|---|---|
| App name | `title.txt` | 30 chars |
| Short description | `short_description.txt` | 80 chars |
| Full description | `full_description.txt` | 4000 chars |
| Release notes | `changelogs/<versionCode>.txt` | 500 chars |
| App icon (512×512) | `images/icon.png` | required |
| Feature graphic (1024×500) | `images/featureGraphic.png` | required |
| Phone screenshots | `images/phoneScreenshots/` | 2–8, ≥320px |

Screenshots and the feature graphic are **generated**, not hand-exported — edit the HTML in
`tools/store-assets/` and re-run `node tools/store-assets/render.mjs`. They render at
1080×1920 (9:16), which is what keeps the listing eligible for Play's promotional surfaces
(min 4 screenshots at 1080px+); see that directory's README.

Push the listing to Play (after Part A) either from the **Play store listing** GitHub
Action (`workflow_dispatch`, defaults to a dry run) or locally:
```bash
cd android && export PLAY_JSON_KEY_FILE=/path/to/play-service-account.json
bundle exec fastlane metadata validate_only:true   # dry run
bundle exec fastlane metadata                      # publish
```

> The listing is **only** pushed by that lane. A tagged release uploads the binary and its
> release notes, and deliberately leaves the listing copy and artwork alone.

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

> **The Play upload is gated on a secret.** In `release.yml` the fastlane steps are guarded by
> `env.PLAY_JSON != ''`, so if `PLAY_SERVICE_ACCOUNT_JSON` is not set the tagged build still
> produces the APK/AAB and the GitHub Release, and the Play upload is **skipped without
> failing**. A green run is therefore not proof anything reached Play — check that the
> "Upload AAB to Play internal track" and "Promote to production" steps actually ran. Add the
> secret via Part A step 4.

> **Play publishing lives in its own `play` job.** It is deliberately *not* a dependency of the
> GitHub Release job. On v1.1.0 the promote step failed and, because it was then a step inside the
> `android` job, it failed that whole job — so `release`'s `needs: [android]` was never satisfied
> and no GitHub Release was published at all. A Play-side hiccup cost the macOS DMG and the APK
> download too. The two channels now fail independently.

> **Promotion retries.** Play's edit API is read-after-write eventually consistent: an edit opened
> seconds after the internal commit can report `Track 'internal' doesn't have any releases` when
> the release is in fact there. The promote step retries with backoff rather than sleeping a fixed
> guess. If it exhausts its attempts, the internal upload has still succeeded — promote by hand
> rather than re-running the job, because re-running rebuilds and re-uploads the same
> `versionCode`, which Play rejects as a duplicate.

After Part A is done, each release is:
1. Bump `versionCode` (and `versionName`) in `android/app/build.gradle.kts`. Play rejects a
   duplicate `versionCode`, so this bump is mandatory even for a no-op rebuild. Keep
   `versionName`, the git tag, and macOS `MARKETING_VERSION` on the same number.
2. Update `android/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 chars max).
3. Push a `vX.Y.Z` tag. CI builds the signed AAB, runs `fastlane internal` → **internal** track,
   then `fastlane promote_production` → **production at a 20% staged rollout**.

**The tag reaches real users.** There is no manual gate between pushing it and a fifth of the
install base getting the build, so treat a tag as a ship decision, not a build trigger. Test on a
device *before* tagging.

Play has no rollback — the only recoveries are halting the rollout or shipping a higher
`versionCode`. Both need a *staged* release to act on, which is why the rollout is a fraction and
not `completed`. Watch the Play Console vitals, then raise the percentage there when it looks sane.

To ship at 100% in one go (deliberate, no halt path):
```bash
cd android && export PLAY_JSON_KEY_FILE=/path/to/play-service-account.json
bundle exec fastlane promote_production rollout:1.0
```
