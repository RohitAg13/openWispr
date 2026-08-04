// Shared design-system components for pSEO pages.
//
// Every color/font/radius/shadow value here is copied straight from website/index.html's inline
// styles (search that file for the same oklch(...) values) so generated pages are visually
// indistinguishable from the hand-written home page. Zero dependencies by design — plain
// template-literal string assembly, matching the zero-dependency ethos of website/server.js.
// This module never invents copy; every page-specific string comes from a data/*.json file.

const SITE_URL = 'https://openwispr.dev';
const GITHUB_URL = 'https://github.com/RohitAg13/openWispr';
const MACOS_DOWNLOAD_URL = 'https://github.com/RohitAg13/openWispr/releases';
const ANDROID_DOWNLOAD_URL = 'https://play.google.com/store/apps/details?id=com.voicerewriter';

const LOGO_SVG_PATH =
  '<path d="M67,29 A25,25 0 1 0 67,71" style="fill:none;stroke:currentColor;stroke-width:8;stroke-linecap:round;stroke-linejoin:round;"></path><path d="M69,50 C75,44 79,56 86,49" style="fill:none;stroke:currentColor;stroke-width:8;stroke-linecap:round;stroke-linejoin:round;"></path>';

const GITHUB_ICON_PATH =
  '<path d="M12 .5C5.7.5.5 5.7.5 12c0 5.1 3.3 9.4 7.9 10.9.6.1.8-.2.8-.5v-2c-3.2.7-3.9-1.4-3.9-1.4-.5-1.3-1.3-1.7-1.3-1.7-1.1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 .1.8 1.7 2.6 1.2.1-.7.4-1.2.7-1.5-2.6-.3-5.3-1.3-5.3-5.7 0-1.3.5-2.3 1.2-3.1-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.6.2 2.8.1 3.1.8.8 1.2 1.8 1.2 3.1 0 4.4-2.7 5.4-5.3 5.7.4.4.8 1.1.8 2.2v3.3c0 .3.2.6.8.5 4.6-1.5 7.9-5.8 7.9-10.9C23.5 5.7 18.3.5 12 .5z"/>';

const CHECK_SVG =
  '<svg viewBox="0 0 24 24" style="width:15px;height:15px;display:block;color:oklch(0.55 0.11 145);fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round;flex:none;"><polyline points="4 12 9.5 17.5 20 6.5"></polyline></svg>';

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** <head> boilerplate shared with index.html: fonts, icon, theme-color, canonical, Plausible. */
function headHtml({ title, description, canonicalPath, ogTitle, ogDescription }) {
  const canonical = `${SITE_URL}${canonicalPath}`;
  return `<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<meta name="description" content="${escapeHtml(description)}">
<meta property="og:title" content="${escapeHtml(ogTitle || title)}">
<meta property="og:description" content="${escapeHtml(ogDescription || description)}">
<meta property="og:type" content="website">
<meta property="og:url" content="${canonical}">
<link rel="canonical" href="${canonical}">
<meta name="theme-color" content="#f5f1e6">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='50' fill='%23d98a4a'/%3E%3Cpath d='M67,29 A25,25 0 1 0 67,71' fill='none' stroke='white' stroke-width='8' stroke-linecap='round'/%3E%3Cpath d='M69,50 C75,44 79,56 86,49' fill='none' stroke='white' stroke-width='8' stroke-linecap='round'/%3E%3C/svg%3E">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Mulish:ital,wght@0,400;0,500;0,600;0,700;0,800;1,400;1,500&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; }
  body { -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility; }
  a { text-decoration: none; color: inherit; }
  @keyframes ow-breathe { 0%,100% { transform: scale(1); } 50% { transform: scale(1.07); } }
  @media (max-width: 900px) { .ow-nav-links { display: none !important; } }
  @media (max-width: 860px) {
    .ow-stack { grid-template-columns: 1fr !important; }
    .ow-hero { padding: 44px 22px 56px !important; }
    h1 { font-size: 36px !important; }
    h2 { font-size: 28px !important; }
    .ow-cmp-row { grid-template-columns: 1.2fr 1fr 1fr !important; }
  }
  @media (max-width: 560px) {
    .ow-star { display: none !important; }
    h1 { font-size: 30px !important; }
  }
</style>
<script defer data-domain="openwispr.dev" src="https://plausible.rohitagarwal.dev/js/script.js"></script>`;
}

function navHtml() {
  return `<div style="position:sticky; top:0; z-index:50; padding:18px 24px 0; pointer-events:none;">
  <div style="max-width:1080px; margin:0 auto; pointer-events:auto; display:flex; align-items:center; justify-content:space-between; gap:18px; padding:9px 9px 9px 16px; border-radius:999px; background:oklch(0.99 0.008 84 / 0.7); backdrop-filter:blur(22px) saturate(1.5); -webkit-backdrop-filter:blur(22px) saturate(1.5); border:1px solid oklch(1 0 0 / 0.55); box-shadow:0 1px 0 oklch(1 0 0 / 0.6) inset, 0 14px 36px -16px oklch(0.45 0.08 35 / 0.45), 0 0 0 1px oklch(0.88 0.014 70 / 0.5);">
    <a href="/" style="display:flex; align-items:center; gap:11px; padding-right:6px;">
      <span style="position:relative; width:34px; height:34px; border-radius:50%; background:linear-gradient(140deg, oklch(0.84 0.11 76), oklch(0.7 0.13 42) 52%, oklch(0.6 0.12 18)); display:flex; align-items:center; justify-content:center; color:oklch(0.99 0.01 85); box-shadow:0 4px 12px -4px oklch(0.55 0.13 38 / 0.6); animation:ow-breathe 4.8s ease-in-out infinite;">
        <svg viewBox="0 0 100 100" style="width:56%; height:56%; display:block; color:inherit;">${LOGO_SVG_PATH}</svg>
      </span>
      <span style="font-size:18px; font-weight:700; letter-spacing:-0.02em; color:oklch(0.3 0.03 46);">OpenWispr</span>
    </a>
    <div class="ow-nav-links" style="display:flex; align-items:center; gap:2px;">
      <a href="/#privacy" style="font-size:14px; font-weight:600; color:oklch(0.46 0.03 50); padding:8px 12px; border-radius:11px;">Privacy</a>
      <a href="/#features" style="font-size:14px; font-weight:600; color:oklch(0.46 0.03 50); padding:8px 12px; border-radius:11px;">Features</a>
      <a href="/#compare" style="font-size:14px; font-weight:600; color:oklch(0.46 0.03 50); padding:8px 12px; border-radius:11px;">Compare</a>
      <a href="/#opensource" style="font-size:14px; font-weight:600; color:oklch(0.46 0.03 50); padding:8px 12px; border-radius:11px;">Open source</a>
    </div>
    <div style="display:flex; align-items:center; gap:9px;">
      <a href="${GITHUB_URL}" class="ow-star" style="display:inline-flex; align-items:center; gap:8px; padding:9px 13px; border-radius:999px; background:oklch(0.97 0.01 76); border:1px solid oklch(0.9 0.014 70);">
        <svg viewBox="0 0 24 24" style="width:16px; height:16px; fill:oklch(0.4 0.03 48);">${GITHUB_ICON_PATH}</svg>
        <span style="font-size:13px; font-weight:600; color:oklch(0.4 0.03 48);">Star</span>
      </a>
      <a href="/#download" style="display:inline-flex; align-items:center; gap:10px; font-size:14px; font-weight:600; color:oklch(0.99 0.01 85); background:linear-gradient(135deg, oklch(0.76 0.12 58), oklch(0.66 0.13 40)); padding:10px 18px; border-radius:999px; box-shadow:0 8px 20px -8px oklch(0.58 0.13 38 / 0.6);">Download</a>
    </div>
  </div>
</div>`;
}

/** Breadcrumb — helps both users and crawlers understand where a generated page sits. */
function breadcrumbHtml(items) {
  const parts = items
    .map((item, i) => {
      const isLast = i === items.length - 1;
      const label = escapeHtml(item.label);
      if (isLast) {
        return `<span style="color:oklch(0.4 0.03 48); font-weight:600;">${label}</span>`;
      }
      return `<a href="${item.href}" style="color:oklch(0.55 0.03 50);">${label}</a><span style="color:oklch(0.7 0.02 60); margin:0 8px;">/</span>`;
    })
    .join('');
  return `<div style="max-width:1080px; margin:0 auto; padding:22px 32px 0; font-size:13px; font-family:'IBM Plex Mono',monospace;">${parts}</div>`;
}

function heroHtml({ eyebrow, h1, subhead, ctaNote }) {
  return `<div style="position:relative; overflow:hidden;">
  <div style="position:absolute; top:-180px; left:50%; transform:translateX(-50%); width:1100px; height:560px; border-radius:50%; background:radial-gradient(closest-side, oklch(0.88 0.09 72 / 0.55), transparent); pointer-events:none;"></div>
  <div class="ow-hero" style="position:relative; max-width:820px; margin:0 auto; padding:50px 32px 60px; text-align:center;">
    <div style="display:inline-flex; align-items:center; gap:9px; padding:7px 14px; background:oklch(0.995 0.006 85); border:1px solid oklch(0.9 0.014 72); border-radius:30px; margin-bottom:26px;">
      <span style="width:8px; height:8px; border-radius:50%; background:linear-gradient(140deg, oklch(0.82 0.11 74), oklch(0.66 0.13 42));"></span>
      <span style="font-family:'IBM Plex Mono',monospace; font-size:11px; letter-spacing:0.1em; color:oklch(0.5 0.04 45); text-transform:uppercase;">${escapeHtml(eyebrow)}</span>
    </div>
    <h1 style="font-size:48px; line-height:1.08; font-weight:800; letter-spacing:-0.03em; color:oklch(0.28 0.035 42); margin:0 0 20px;">${h1}</h1>
    <p style="font-size:18px; line-height:1.6; color:oklch(0.46 0.03 50); margin:0 auto 30px; max-width:620px;">${subhead}</p>
    <div style="display:flex; align-items:center; justify-content:center; gap:13px; flex-wrap:wrap;">
      <a href="${MACOS_DOWNLOAD_URL}" style="display:inline-flex; align-items:center; gap:10px; font-size:15.5px; font-weight:600; color:oklch(0.985 0.012 82); background:oklch(0.66 0.13 40); padding:14px 22px; border-radius:12px; box-shadow:0 10px 24px -10px oklch(0.55 0.13 38 / 0.7);">Download for macOS</a>
      <a href="${ANDROID_DOWNLOAD_URL}" style="display:inline-flex; align-items:center; gap:10px; font-size:15.5px; font-weight:600; color:oklch(0.32 0.03 47); background:oklch(0.995 0.006 85); border:1px solid oklch(0.88 0.014 70); padding:14px 22px; border-radius:12px;">Get it on Google Play</a>
    </div>
    ${ctaNote ? `<div style="margin-top:20px; font-size:13px; color:oklch(0.55 0.025 52);">${ctaNote}</div>` : ''}
  </div>
</div>`;
}

/** Freeform prose section — h2 + array of paragraph strings (may contain inline HTML). */
function proseSectionHtml({ id, eyebrow, heading, paragraphs, bg }) {
  const paras = paragraphs
    .map((p) => `<p style="font-size:16.5px; line-height:1.7; color:oklch(0.42 0.03 48); margin:0 0 18px;">${p}</p>`)
    .join('\n');
  return `<div${id ? ` id="${id}"` : ''} style="background:${bg || 'oklch(0.972 0.014 78)'};">
  <div style="max-width:760px; margin:0 auto; padding:64px 32px;">
    ${eyebrow ? `<div style="font-family:'IBM Plex Mono',monospace; font-size:12px; letter-spacing:0.18em; color:oklch(0.66 0.13 40); text-transform:uppercase; margin-bottom:16px;">${escapeHtml(eyebrow)}</div>` : ''}
    ${heading ? `<h2 style="font-size:32px; line-height:1.14; font-weight:700; letter-spacing:-0.02em; margin:0 0 20px; color:oklch(0.3 0.03 45);">${heading}</h2>` : ''}
    ${paras}
  </div>
</div>`;
}

/**
 * Comparison table. `rows` is [{feature, us, them, usGood}] — usGood=false renders the OpenWispr
 * cell in neutral tone instead of the green "win" tone, for rows where we deliberately admit a
 * gap (see quality-bar rule #3 in pseo/README.md — comparisons must not be one-sided).
 */
function comparisonTableHtml({ competitorName, competitorNote, rows }) {
  const body = rows
    .map((r) => {
      const usColor = r.usGood === false ? 'oklch(0.42 0.03 48)' : 'oklch(0.46 0.1 145)';
      return `<div class="ow-cmp-row" style="display:grid; grid-template-columns:1.4fr 1fr 1fr; border-bottom:1px solid oklch(0.95 0.01 72);">
  <div style="padding:16px 24px; font-size:14.5px; font-weight:500; color:oklch(0.36 0.03 48);">${escapeHtml(r.feature)}</div>
  <div style="padding:16px 20px; border-left:1px solid oklch(0.95 0.01 72); text-align:center; background:oklch(0.985 0.012 74);"><span style="font-size:14px; font-weight:600; color:${usColor};">${escapeHtml(r.us)}</span></div>
  <div style="padding:16px 20px; border-left:1px solid oklch(0.95 0.01 72); text-align:center;"><span style="font-size:14px; color:oklch(0.58 0.025 50);">${escapeHtml(r.them)}</span></div>
</div>`;
    })
    .join('\n');
  return `<div style="background:oklch(0.995 0.006 85); border:1px solid oklch(0.91 0.012 72); border-radius:18px; overflow:hidden;">
  <div class="ow-cmp-row" style="display:grid; grid-template-columns:1.4fr 1fr 1fr;">
    <div style="padding:18px 24px; border-bottom:1px solid oklch(0.92 0.012 72);"></div>
    <div style="padding:18px 20px; border-bottom:1px solid oklch(0.92 0.012 72); border-left:1px solid oklch(0.93 0.01 72); text-align:center; display:flex; flex-direction:column; align-items:center; gap:8px; background:oklch(0.97 0.018 72);">
      <svg viewBox="0 0 100 100" style="width:22px; height:22px; display:block; color:oklch(0.66 0.13 40);">${LOGO_SVG_PATH}</svg>
      <span style="font-size:13.5px; font-weight:700; color:oklch(0.3 0.03 45);">OpenWispr</span>
    </div>
    <div style="padding:18px 20px; border-bottom:1px solid oklch(0.92 0.012 72); border-left:1px solid oklch(0.93 0.01 72); text-align:center; display:flex; flex-direction:column; align-items:center; gap:8px;">
      <span style="font-size:13.5px; font-weight:600; color:oklch(0.5 0.03 50);">${escapeHtml(competitorName)}${competitorNote ? `<br><span style="font-size:11px; font-weight:400; color:oklch(0.6 0.02 55);">${escapeHtml(competitorNote)}</span>` : ''}</span>
    </div>
  </div>
  ${body}
</div>`;
}

/** FAQ accordion — same data-faq/data-faq-answer/data-faq-icon pattern already in index.html. */
function faqSectionHtml(faqs) {
  const items = faqs
    .map(
      (f, i) => `<div data-faq="${i}" style="background:oklch(0.995 0.006 85); border:1px solid oklch(0.91 0.012 72); border-radius:14px; padding:20px 24px; cursor:pointer;">
  <div style="display:flex; align-items:center; justify-content:space-between; gap:16px;">
    <span style="font-size:16px; font-weight:600; color:oklch(0.3 0.03 45);">${escapeHtml(f.q)}</span>
    <span data-faq-icon style="font-size:22px; color:oklch(0.66 0.13 40); line-height:1; flex:none; transform:rotate(0deg); transition:transform .18s;">+</span>
  </div>
  <div data-faq-answer style="font-size:15px; line-height:1.65; color:oklch(0.5 0.03 50); margin-top:14px; display:none;">${f.a}</div>
</div>`
    )
    .join('\n');
  return `<div style="background:oklch(0.95 0.016 70); border-top:1px solid oklch(0.91 0.012 72);">
  <div style="max-width:760px; margin:0 auto; padding:70px 32px;">
    <div style="text-align:center; margin-bottom:38px;">
      <div style="font-family:'IBM Plex Mono',monospace; font-size:12px; letter-spacing:0.18em; color:oklch(0.66 0.13 40); text-transform:uppercase; margin-bottom:16px;">Questions</div>
      <h2 style="font-size:32px; line-height:1.14; font-weight:700; letter-spacing:-0.02em; margin:0; color:oklch(0.3 0.03 45);">Good questions, honest answers.</h2>
    </div>
    <div style="display:flex; flex-direction:column; gap:12px;">
      ${items}
    </div>
  </div>
</div>`;
}

/** Small "keep reading" block linking to sibling generated pages — avoids orphan pages. */
function relatedLinksHtml(links) {
  if (!links || links.length === 0) return '';
  const items = links
    .map(
      (l) => `<a href="${l.href}" style="display:block; background:oklch(0.995 0.006 85); border:1px solid oklch(0.91 0.012 72); border-radius:14px; padding:20px 22px;">
  <div style="font-size:11px; font-family:'IBM Plex Mono',monospace; letter-spacing:0.1em; color:oklch(0.66 0.13 40); text-transform:uppercase; margin-bottom:8px;">${escapeHtml(l.kicker || 'Read next')}</div>
  <div style="font-size:16px; font-weight:600; color:oklch(0.3 0.03 45);">${escapeHtml(l.title)}</div>
</a>`
    )
    .join('\n');
  return `<div style="background:oklch(0.972 0.014 78);">
  <div style="max-width:1000px; margin:0 auto; padding:20px 32px 70px; display:grid; grid-template-columns:repeat(auto-fit, minmax(240px, 1fr)); gap:16px;">
    ${items}
  </div>
</div>`;
}

function footerCtaHtml() {
  return `<div id="download" style="background:linear-gradient(160deg, oklch(0.34 0.06 30), oklch(0.24 0.045 24)); color:oklch(0.96 0.012 82);">
  <div style="max-width:820px; margin:0 auto; padding:80px 32px; text-align:center;">
    <div style="width:64px; height:64px; border-radius:50%; background:linear-gradient(140deg, oklch(0.82 0.11 74), oklch(0.71 0.13 42) 52%, oklch(0.59 0.12 17)); display:flex; align-items:center; justify-content:center; color:oklch(0.99 0.01 85); margin:0 auto 24px; box-shadow:0 16px 40px -12px oklch(0.5 0.13 38 / 0.8); animation:ow-breathe 4.6s ease-in-out infinite;">
      <svg viewBox="0 0 100 100" style="width:54%; height:54%; display:block; color:inherit;">${LOGO_SVG_PATH}</svg>
    </div>
    <h2 style="font-size:36px; line-height:1.1; font-weight:800; letter-spacing:-0.03em; margin:0 0 16px; color:oklch(0.98 0.012 82);">Start dictating privately.</h2>
    <p style="font-size:17px; line-height:1.6; color:oklch(0.84 0.03 70); margin:0 auto 30px; max-width:480px;">Open source and entirely on your device. Android from Google Play, macOS from GitHub — no account, no sign-up, just a download.</p>
    <div style="display:flex; align-items:center; justify-content:center; gap:13px; flex-wrap:wrap;">
      <a href="${MACOS_DOWNLOAD_URL}" style="display:inline-flex; align-items:center; gap:10px; font-size:16px; font-weight:600; color:oklch(0.3 0.04 35); background:oklch(0.98 0.012 82); padding:15px 26px; border-radius:13px;">Download for macOS</a>
      <a href="${ANDROID_DOWNLOAD_URL}" style="display:inline-flex; align-items:center; gap:10px; font-size:16px; font-weight:600; color:oklch(0.96 0.012 82); background:oklch(0.4 0.04 35 / 0.45); border:1px solid oklch(0.54 0.05 40); padding:15px 26px; border-radius:13px;">Get it on Google Play</a>
    </div>
  </div>
</div>`;
}

function footerHtml() {
  return `<div style="background:oklch(0.18 0.025 28); color:oklch(0.7 0.02 65);">
  <div style="max-width:1180px; margin:0 auto; padding:40px 32px; display:flex; align-items:center; justify-content:space-between; gap:20px; flex-wrap:wrap;">
    <div style="display:flex; align-items:center; gap:11px;">
      <svg viewBox="0 0 100 100" style="width:24px; height:24px; display:block; color:oklch(0.78 0.1 55);">${LOGO_SVG_PATH}</svg>
      <span style="font-size:16px; font-weight:600; color:oklch(0.92 0.012 80);">OpenWispr</span>
      <span style="font-size:13px; color:oklch(0.55 0.02 60); margin-left:8px;">Voice to text, on your device.</span>
    </div>
    <div style="display:flex; align-items:center; gap:26px; font-size:13.5px; flex-wrap:wrap;">
      <a href="${GITHUB_URL}" style="color:oklch(0.72 0.02 65);">GitHub</a>
      <a href="/privacy.html" style="color:oklch(0.72 0.02 65);">Privacy Policy</a>
      <a href="/#features" style="color:oklch(0.72 0.02 65);">Features</a>
      <a href="/compare/wispr-flow.html" style="color:oklch(0.72 0.02 65);">vs Wispr Flow</a>
      <a href="/compare/superwhisper.html" style="color:oklch(0.72 0.02 65);">vs superwhisper</a>
      <a href="/android/on-device-whisper-dictation-android.html" style="color:oklch(0.72 0.02 65);">On-device Android dictation</a>
      <span style="color:oklch(0.5 0.02 58);">MIT © 2026</span>
    </div>
  </div>
</div>`;
}

const FAQ_SCRIPT = `<script>
(function(){
  function qa(s){return Array.prototype.slice.call(document.querySelectorAll(s));}
  qa('[data-faq]').forEach(function(item){
    item.addEventListener('click', function(){
      var ans=item.querySelector('[data-faq-answer]'), icon=item.querySelector('[data-faq-icon]');
      var open = ans.style.display!=='none';
      ans.style.display = open?'none':'block';
      icon.style.transform = open?'rotate(0deg)':'rotate(45deg)';
    });
  });
})();
</script>`;

/** JSON-LD structured data: FAQPage (if faqs present) + a BreadcrumbList. */
function structuredDataHtml({ faqs, breadcrumbs, canonicalPath }) {
  const blocks = [];
  if (faqs && faqs.length) {
    blocks.push({
      '@context': 'https://schema.org',
      '@type': 'FAQPage',
      mainEntity: faqs.map((f) => ({
        '@type': 'Question',
        name: f.q,
        acceptedAnswer: { '@type': 'Answer', text: f.aPlain || f.a.replace(/<[^>]+>/g, '') },
      })),
    });
  }
  if (breadcrumbs && breadcrumbs.length) {
    blocks.push({
      '@context': 'https://schema.org',
      '@type': 'BreadcrumbList',
      itemListElement: breadcrumbs.map((b, i) => ({
        '@type': 'ListItem',
        position: i + 1,
        name: b.label,
        item: b.href.startsWith('http') ? b.href : `${SITE_URL}${b.href}`,
      })),
    });
  }
  if (blocks.length === 0) return '';
  return blocks
    .map((b) => `<script type="application/ld+json">${JSON.stringify(b)}</script>`)
    .join('\n');
}

/** Assembles a full HTML document from a body string + head metadata. */
function page({ title, description, canonicalPath, ogTitle, ogDescription, bodyHtml, faqs, breadcrumbs }) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
${headHtml({ title, description, canonicalPath, ogTitle, ogDescription })}
${structuredDataHtml({ faqs, breadcrumbs, canonicalPath })}
</head>
<body>
<div style="background:oklch(0.972 0.014 78); font-family:'Mulish',sans-serif; color:oklch(0.32 0.03 47); overflow-x:hidden;">
${navHtml()}
${bodyHtml}
${footerCtaHtml()}
${footerHtml()}
</div>
${FAQ_SCRIPT}
</body>
</html>`;
}

export {
  SITE_URL,
  GITHUB_URL,
  MACOS_DOWNLOAD_URL,
  ANDROID_DOWNLOAD_URL,
  CHECK_SVG,
  escapeHtml,
  breadcrumbHtml,
  heroHtml,
  proseSectionHtml,
  comparisonTableHtml,
  faqSectionHtml,
  relatedLinksHtml,
  page,
};
