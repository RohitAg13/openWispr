# Store assets generator

Renders the Google Play graphics from HTML so they can be regenerated and diffed like code,
instead of being re-exported by hand from a design tool.

```bash
npm --prefix tools/store-assets install   # playwright-core only, ~1 MB
node tools/store-assets/render.mjs
```

Writes, overwriting in place:

| Output | Size | Source |
|---|---|---|
| `android/fastlane/metadata/android/en-US/images/phoneScreenshots/*.png` | 1080×1920 | `screenshots.html` |
| `android/fastlane/metadata/android/en-US/images/featureGraphic.png` | 1024×500 | `feature-graphic.html` |

The renderer needs a Chromium binary. It picks the newest one from the Playwright browser
cache (`~/Library/Caches/ms-playwright` on macOS); set `CHROME_PATH` to point somewhere else.

## Why 1080×1920

Play accepts screenshots as small as 320px, but only listings with **at least 4 screenshots
at 1080px+ in 16:9 / 9:16** are eligible for its promotional and featured surfaces. The six
shots here clear that bar. Play also requires 24-bit PNG with **no alpha channel** — every
`.shot` element paints an opaque background so the exported PNG has none.

## Editing

`screenshots.html` lays out one `.shot` per screenshot: a caption block over a device frame.
The phone UI inside each frame is authored at a 393px-wide viewport (Pixel-class) and scaled
up by the `.ui` transform.

Two things to keep in mind when changing it:

- **`.ui` height must stay `screen height ÷ scale`** (currently `1354 ÷ 1.715 = 789`).
  Get this wrong and anything anchored with `bottom: 0` — the nav bar, the chat input, the
  "Stop & insert" button — renders below the visible area and silently disappears.
- **Colours come from `android/app/src/main/java/com/voicerewriter/ui/Theme.kt`** and the
  strings are the app's real ones. Play requires screenshots to represent the actual app, so
  keep mockups in step with the UI when it changes. Third-party app names are deliberately
  generic ("Messages", "Email") rather than real brands.

After editing, re-run the renderer and push the result with the **Play store listing**
workflow (`.github/workflows/store-metadata.yml`) or `bundle exec fastlane metadata` locally.
