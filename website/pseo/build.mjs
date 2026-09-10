#!/usr/bin/env node
// pSEO static-page generator. Zero npm dependencies (node:fs / node:path / template literals
// only) — see pseo/README.md for the full architecture writeup. Run with:
//
//   cd website/pseo && node build.mjs
//
// Reads every website/pseo/data/*.json file, dispatches on its "type" field, renders a full
// HTML document with lib/render.mjs, and writes it to the path the data file specifies (relative
// to website/). Also (re)writes website/sitemap.xml and website/robots.txt covering every output
// page plus the existing hand-written pages.

import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pageMarkdown, staticPageMarkdown, markdownPathFor } from './lib/markdown.mjs';
import {
  SITE_URL,
  breadcrumbHtml,
  heroHtml,
  proseSectionHtml,
  comparisonTableHtml,
  faqSectionHtml,
  journalNoteHtml,
  relatedLinksHtml,
  docsHeaderHtml,
  docsSectionHtml,
  docsLayoutHtml,
  page,
} from './lib/render.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, 'data');
const WEBSITE_ROOT = join(__dirname, '..');

function loadDataFiles() {
  return readdirSync(DATA_DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => ({ file: f, data: JSON.parse(readFileSync(join(DATA_DIR, f), 'utf8')) }));
}

function renderComparisonPage(data) {
  const body = `${heroHtml({
    eyebrow: data.eyebrow,
    h1: data.h1,
    subhead: data.subhead,
    ctaNote: data.ctaNote,
  })}
${breadcrumbHtml(data.breadcrumbs)}
${proseSectionHtml({ eyebrow: data.intro.eyebrow, heading: data.intro.heading, paragraphs: data.intro.paragraphs })}
<div style="background:oklch(0.95 0.016 70); border-top:1px solid oklch(0.91 0.012 72); border-bottom:1px solid oklch(0.91 0.012 72);">
  <div style="max-width:1000px; margin:0 auto; padding:60px 32px;">
    ${comparisonTableHtml(data.comparisonTable)}
    <div style="text-align:center; font-size:12.5px; color:oklch(0.58 0.025 52); margin-top:16px;">Sourced from the competitor's own documentation, changelog, and public statements as cited below. Figures can change — see linked sources for the latest.</div>
  </div>
</div>
${data.sections.map((s) => proseSectionHtml(s)).join('\n')}
${faqSectionHtml(data.faqs)}
${relatedLinksHtml(data.relatedLinks)}`;
  return page({
    title: data.title,
    description: data.description,
    canonicalPath: data.canonicalPath,
    ogTitle: data.ogTitle,
    ogDescription: data.ogDescription,
    bodyHtml: body,
    faqs: data.faqs,
    breadcrumbs: data.breadcrumbs,
  });
}

function renderLongtailPage(data) {
  const body = `${heroHtml({
    eyebrow: data.eyebrow,
    h1: data.h1,
    subhead: data.subhead,
    ctaNote: data.ctaNote,
  })}
${breadcrumbHtml(data.breadcrumbs)}
${data.sections.map((s) => proseSectionHtml(s)).join('\n')}
${faqSectionHtml(data.faqs)}
${relatedLinksHtml(data.relatedLinks)}`;
  return page({
    title: data.title,
    description: data.description,
    canonicalPath: data.canonicalPath,
    ogTitle: data.ogTitle,
    ogDescription: data.ogDescription,
    bodyHtml: body,
    faqs: data.faqs,
    breadcrumbs: data.breadcrumbs,
  });
}

/**
 * "How we use OpenWispr" journal pages (`/journal/{slug}.html`) — first-person project accounts,
 * not testimonials. Structurally identical to a longtail page (hero → sections → FAQ → related)
 * plus one addition: journalNoteHtml() renders a disclosure strip right under the breadcrumb,
 * before any narrative content, stating plainly this is the project's own account. See
 * pseo/README.md "Journal pages" for why this is a distinct type rather than reusing "longtail"
 * outright — the disclosure requirement is specific to first-person narrative content and
 * shouldn't accidentally apply to (or be skipped on) other longtail pages.
 */
function renderJournalPage(data) {
  const body = `${heroHtml({
    eyebrow: data.eyebrow,
    h1: data.h1,
    subhead: data.subhead,
    ctaNote: data.ctaNote,
  })}
${breadcrumbHtml(data.breadcrumbs)}
${journalNoteHtml()}
${data.sections.map((s) => proseSectionHtml(s)).join('\n')}
${faqSectionHtml(data.faqs)}
${relatedLinksHtml(data.relatedLinks)}`;
  return page({
    title: data.title,
    description: data.description,
    canonicalPath: data.canonicalPath,
    ogTitle: data.ogTitle,
    ogDescription: data.ogDescription,
    bodyHtml: body,
    faqs: data.faqs,
    breadcrumbs: data.breadcrumbs,
  });
}

/**
 * Documentation pages (`/docs/{slug}.html`) — reference material about the shipped app, as
 * opposed to the comparison/long-tail pages, which argue a position for a search query.
 *
 * Two structural differences from every other type, both deliberate:
 *
 * 1. **Sidebar instead of a download hero.** Someone reading the settings reference has already
 *    installed the app; putting two download buttons above the fold is the wrong furniture. The
 *    sidebar is what a docs reader actually wants, and it comes from `docsNav()` below rather
 *    than from the data files, so every docs page links to every other one automatically.
 * 2. **Sections may carry `bullets` and a `table`.** The settings reference and the model
 *    catalogue are tabular facts; rendering them as prose would make them longer and harder to
 *    check against the app. `lib/markdown.mjs` mirrors both, so the `.md` twin keeps the shape.
 *
 * No standalone FAQ page: `faqSectionHtml` already puts a FAQ block (and `FAQPage` structured
 * data) on the bottom of whichever docs page owns the question, which is where someone reading
 * about, say, permissions actually wants the permissions questions answered.
 */
function renderDocsPage(data, { docsNav }) {
  const navItems = docsNav.map((n) => ({ ...n, current: n.href === data.canonicalPath }));
  const body = `${docsHeaderHtml({ eyebrow: data.eyebrow, h1: data.h1, subhead: data.subhead })}
${breadcrumbHtml(data.breadcrumbs)}
${docsLayoutHtml({ navItems, sectionsHtml: data.sections.map((s) => docsSectionHtml(s)).join('\n') })}
${faqSectionHtml(data.faqs)}
${relatedLinksHtml(data.relatedLinks)}`;
  return page({
    title: data.title,
    description: data.description,
    canonicalPath: data.canonicalPath,
    ogTitle: data.ogTitle,
    ogDescription: data.ogDescription,
    bodyHtml: body,
    faqs: data.faqs,
    breadcrumbs: data.breadcrumbs,
  });
}

const RENDERERS = {
  comparison: renderComparisonPage,
  longtail: renderLongtailPage,
  journal: renderJournalPage,
  docs: renderDocsPage,
};

/**
 * The docs sidebar, derived from the docs data files rather than hand-listed here. A new docs
 * page appears in the nav of every other docs page as soon as its data file lands — which is
 * the mechanical version of the internal-linking rule in the quality bar, instead of a list
 * someone has to remember to update.
 */
function docsNav(entries) {
  return entries
    .filter(({ data }) => data.type === 'docs')
    .sort((a, b) => (a.data.navOrder ?? 999) - (b.data.navOrder ?? 999) || a.file.localeCompare(b.file))
    .map(({ file, data }) => {
      if (!data.navLabel) throw new Error(`${file}: docs pages need a navLabel for the sidebar`);
      return { label: data.navLabel, href: data.canonicalPath };
    });
}

function build() {
  const entries = loadDataFiles();
  const written = [];
  const ctx = { docsNav: docsNav(entries) };

  for (const { file, data } of entries) {
    const renderer = RENDERERS[data.type];
    if (!renderer) {
      throw new Error(`${file}: unknown page type "${data.type}" — add a renderer in build.mjs`);
    }
    if (!data.outputPath || !data.canonicalPath) {
      throw new Error(`${file}: missing outputPath or canonicalPath`);
    }
    const html = renderer(data, ctx);
    const outPath = join(WEBSITE_ROOT, data.outputPath);
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, html, 'utf8');

    // Markdown mirror of the same data — same prose, no chrome. See lib/markdown.mjs for why.
    // Written beside the .html and deliberately absent from the sitemap: it's an alternate
    // representation of that URL, not a second page competing with it.
    const mdPath = markdownPathFor(data.outputPath);
    writeFileSync(join(WEBSITE_ROOT, mdPath), pageMarkdown(data), 'utf8');

    written.push({ canonicalPath: data.canonicalPath, outputPath: data.outputPath });
    console.log(`  wrote ${data.outputPath} + ${mdPath}`);
  }

  writeStaticMirrors();
  writeSitemap(written);
  writeRobots();

  console.log(`\nBuilt ${written.length} pSEO page(s), each with a .md mirror.`);
}

/**
 * Markdown mirrors for the two hand-written pages. Only `privacy.html` qualifies: it is prose
 * with semantic markup and it is the page most likely to be quoted back at us, so a plain-text
 * form that cannot drift from the real policy is worth having. `index.html` is a visual landing
 * page whose text is mostly widget labels — `llms.txt` is its machine-readable form instead.
 */
function writeStaticMirrors() {
  const html = readFileSync(join(WEBSITE_ROOT, 'privacy.html'), 'utf8');
  const md = staticPageMarkdown(html, {
    title: 'Privacy Policy — OpenWispr',
    description:
      'What OpenWispr processes on your device, what an optional cloud provider would see, ' +
      'and what the project never receives.',
    canonicalPath: '/privacy.html',
  });
  writeFileSync(join(WEBSITE_ROOT, 'privacy.md'), md, 'utf8');
  console.log('  wrote privacy.md');
}

/**
 * Sitemap. Only `.html` URLs go in it — the `.md` mirrors are an alternate representation of
 * the same content, and submitting both would be asking Google to pick between two copies of
 * every page. Assistants find the mirrors through llms.txt and the `.md` convention instead.
 */
function writeSitemap(generatedPages) {
  const staticPages = ['/', '/privacy.html'];
  const urls = [...staticPages, ...generatedPages.map((p) => p.canonicalPath)];
  const today = new Date().toISOString().slice(0, 10);
  const body = urls
    .map(
      (u) => `  <url>
    <loc>${SITE_URL}${u}</loc>
    <lastmod>${today}</lastmod>
  </url>`
    )
    .join('\n');
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${body}
</urlset>
`;
  writeFileSync(join(WEBSITE_ROOT, 'sitemap.xml'), xml, 'utf8');
  console.log('  wrote sitemap.xml');
}

function writeRobots() {
  const robots = `User-agent: *
Allow: /

Sitemap: ${SITE_URL}/sitemap.xml
`;
  writeFileSync(join(WEBSITE_ROOT, 'robots.txt'), robots, 'utf8');
  console.log('  wrote robots.txt');
}

build();
