#!/usr/bin/env node
/**
 * Renders the Play Store graphic assets from HTML.
 *
 *   node tools/store-assets/render.mjs
 *
 * Output (overwrites in place):
 *   android/fastlane/metadata/android/en-US/images/phoneScreenshots/*.png   1080x1920
 *   android/fastlane/metadata/android/en-US/images/featureGraphic.png       1024x500
 *
 * Play requires 24-bit PNG with no alpha, so every `.shot` / `.feature` element must
 * paint an opaque background (they do). 1080x1920 (9:16) is what keeps the listing
 * eligible for Play's promotional surfaces — see docs/PLAY_STORE.md.
 *
 * Needs playwright-core plus a Chromium from the playwright browser cache; point
 * CHROME_PATH at any Chromium/Chrome binary to override the auto-detection.
 */
import { chromium } from 'playwright-core';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const outDir = path.join(repo, 'android/fastlane/metadata/android/en-US/images');
const shotsDir = path.join(outDir, 'phoneScreenshots');

// Ordered exactly as they appear in the Play carousel. The first two do most of the
// converting, so lead with "what it does" before "why it's better".
const SHOTS = [
  ['s1', '1_talk_instead_of_typing.png'],
  ['s2', '2_dictate_into_any_app.png'],
  ['s3', '3_works_offline.png'],
  ['s4', '4_clean_text_out.png'],
  ['s5', '5_private_by_default.png'],
  ['s6', '6_free_open_source.png'],
];

function findChromium() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const cache = path.join(
    process.env.HOME,
    process.platform === 'darwin' ? 'Library/Caches/ms-playwright' : '.cache/ms-playwright',
  );
  if (!fs.existsSync(cache)) return undefined;
  const builds = fs
    .readdirSync(cache)
    .filter((d) => d.startsWith('chromium-'))
    .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
  for (const b of builds) {
    for (const rel of [
      'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
      'chrome-linux/chrome',
    ]) {
      const p = path.join(cache, b, rel);
      if (fs.existsSync(p)) return p;
    }
  }
  return undefined;
}

const run = async (file, targets, dir) => {
  const browser = await chromium.launch({ executablePath: findChromium() });
  const page = await browser.newPage({
    viewport: { width: 1200, height: 1000 },
    deviceScaleFactor: 1,
  });
  await page.goto('file://' + path.join(here, file));
  // Google Fonts arrive over the network — without this the shots render in a
  // fallback face and the caption line breaks land in the wrong place.
  await page.waitForFunction(() => document.fonts.status === 'loaded', null, { timeout: 30_000 });
  await page.waitForTimeout(400);

  fs.mkdirSync(dir, { recursive: true });
  for (const [id, name] of targets) {
    const el = await page.$('#' + id);
    if (!el) throw new Error(`missing element #${id} in ${file}`);
    const dest = path.join(dir, name);
    await el.screenshot({ path: dest, type: 'png', scale: 'css', omitBackground: false });
    const { width, height } = await el.boundingBox();
    console.log(`  ${name}  ${width}x${height}`);
  }
  await browser.close();
};

console.log('phone screenshots →', path.relative(repo, shotsDir));
// Old low-res set (480x980) is replaced wholesale; stale files would still be uploaded.
if (fs.existsSync(shotsDir)) {
  for (const f of fs.readdirSync(shotsDir)) fs.rmSync(path.join(shotsDir, f));
}
await run('screenshots.html', SHOTS, shotsDir);

console.log('feature graphic →', path.relative(repo, outDir));
await run('feature-graphic.html', [['fg', 'featureGraphic.png']], outDir);

console.log('done.');
