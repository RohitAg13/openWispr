// Markdown mirrors of every generated page.
//
// Each `.html` page also gets a `.md` twin at the same path (`/compare/handy.html` →
// `/compare/handy.md`), rendered from the same data file by the same build. Two reasons, both
// concrete:
//
// 1. **How machines read a site now.** An assistant answering "is there a free on-device
//    dictation app for Android" fetches pages and throws away the chrome. Serving the prose
//    directly means the answer it quotes is the one we wrote, not whatever survived its HTML
//    stripper. A competitor doing this at scale (yaps.ai, `research/11-yaps.md`) is how a
//    single research pass read ~8,500 of their pages in four requests.
// 2. **It costs nothing to keep true.** The mirror is generated from `data/*.json`, the same
//    source the HTML comes from, so the two cannot drift. A hand-maintained `.md` copy would
//    drift by the second edit.
//
// These are mirrors, not separate pages: each one carries a `canonical` front-matter field
// pointing at its `.html` twin, and `build.mjs` deliberately keeps them out of `sitemap.xml`.
// They are an alternate representation for anything that prefers plain text, not a second URL
// competing for the same query.
//
// Same constraint as render.mjs: zero dependencies, and it never invents copy — everything
// here comes from the data file.

const SITE_URL = 'https://openwispr.dev';

/**
 * Inline HTML → Markdown. The data files carry a small, known set of inline tags in their
 * prose (`<a>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<code>`, `<br>`), because those strings are
 * written to be dropped into `render.mjs`'s templates. Convert what has a Markdown equivalent,
 * drop the rest, and unescape entities last so a literal `&lt;` in the copy survives.
 */
function inlineToMarkdown(html) {
  return String(html)
    .replace(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi, (_, href, text) => `[${inlineToMarkdown(text)}](${href})`)
    .replace(/<(strong|b)\b[^>]*>([\s\S]*?)<\/\1>/gi, (_, __, text) => `**${text}**`)
    .replace(/<(em|i)\b[^>]*>([\s\S]*?)<\/\1>/gi, (_, __, text) => `*${text}*`)
    .replace(/<code\b[^>]*>([\s\S]*?)<\/code>/gi, (_, text) => `\`${text}\``)
    // A <br> is a real line break; the newlines and indentation in hand-written HTML source
    // are not. Park the former behind a sentinel, collapse the rest of the whitespace, then
    // restore it — otherwise a mirror of a wrapped <p> keeps its source indentation.
    .replace(/<br\s*\/?>/gi, '\u0000BR\u0000')
    .replace(/<\/?[a-z][^>]*>/gi, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/\u0000BR\u0000\s*/g, '\n')
    .trim();
}

/** YAML front matter. Values are quoted and inner quotes escaped — titles contain colons. */
function frontMatter(fields) {
  const lines = Object.entries(fields)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}: "${String(v).replace(/"/g, '\\"')}"`);
  return `---\n${lines.join('\n')}\n---\n`;
}

/** A pipe table, escaping any pipe inside a cell so the columns survive. */
function table(headers, rows) {
  const cell = (v) => inlineToMarkdown(v).replace(/\|/g, '\\|').replace(/\n/g, ' ');
  const head = `| ${headers.map(cell).join(' | ')} |`;
  const rule = `| ${headers.map(() => '---').join(' | ')} |`;
  const body = rows.map((r) => `| ${r.map(cell).join(' | ')} |`).join('\n');
  return `${head}\n${rule}\n${body}`;
}

function sectionMd({ heading, eyebrow, paragraphs }) {
  const head = heading ? `## ${inlineToMarkdown(heading)}\n\n` : '';
  const kicker = eyebrow && heading ? `*${inlineToMarkdown(eyebrow)}*\n\n` : '';
  return `${kicker}${head}${paragraphs.map(inlineToMarkdown).join('\n\n')}`;
}

/**
 * Render one data file as Markdown. Handles all three page types (`comparison`, `longtail`,
 * `journal`) from their shared shape rather than branching per type, with two type-specific
 * additions: the comparison table, and the journal disclosure — which is reproduced verbatim
 * from render.mjs's `journalNoteHtml()` for the same reason it exists there, that a
 * first-person page must never reach a reader without it.
 */
function pageMarkdown(data) {
  const parts = [];

  parts.push(
    frontMatter({
      title: data.title,
      description: data.description,
      canonical: `${SITE_URL}${data.canonicalPath}`,
      language: 'en',
    })
  );

  parts.push(`# ${inlineToMarkdown(data.h1)}`);
  parts.push(inlineToMarkdown(data.subhead));

  if (data.type === 'journal') {
    parts.push(
      '> **From the project.** This is a first-person account from the people building ' +
        'OpenWispr, about how we use our own app day to day — not a customer testimonial, ' +
        'and not a fictional user. Every feature named links to the source that implements it.'
    );
  }

  if (data.intro) parts.push(sectionMd(data.intro));

  if (data.comparisonTable) {
    const { competitorName, competitorNote, rows } = data.comparisonTable;
    const them = competitorNote ? `${competitorName} (${competitorNote})` : competitorName;
    parts.push(`## OpenWispr vs ${inlineToMarkdown(competitorName)}, feature by feature`);
    parts.push(table(['', 'OpenWispr', them], rows.map((r) => [r.feature, r.us, r.them])));
    parts.push(
      "Sourced from the competitor's own documentation, changelog, and public statements. " +
        'Figures can change — check the linked sources for the latest.'
    );
  }

  for (const section of data.sections) parts.push(sectionMd(section));

  if (data.faqs && data.faqs.length) {
    parts.push('## Questions');
    for (const f of data.faqs) {
      parts.push(`**${inlineToMarkdown(f.q)}**\n\n${inlineToMarkdown(f.aPlain || f.a)}`);
    }
  }

  if (data.relatedLinks && data.relatedLinks.length) {
    parts.push('## Read next');
    parts.push(
      data.relatedLinks.map((l) => `- [${inlineToMarkdown(l.title)}](${l.href})`).join('\n')
    );
  }

  parts.push(
    `---\n\nOpenWispr is free and MIT-licensed: [source on GitHub](https://github.com/RohitAg13/openWispr), ` +
      `[Android on Google Play](https://play.google.com/store/apps/details?id=com.voicerewriter), ` +
      `[macOS from Releases](https://github.com/RohitAg13/openWispr/releases). ` +
      `This page is the Markdown mirror of ${SITE_URL}${data.canonicalPath}.`
  );

  return `${parts.join('\n\n')}\n`;
}

/**
 * Mirror for a hand-written page (`privacy.html`) rather than a data file.
 *
 * These pages have no JSON behind them, so the mirror is extracted from their markup. That is
 * only safe because the markup is ours and semantic — `<h1>/<h2>/<p>/<ul><li>` inside a single
 * `.wrap`, with the nav and footer clearly delimited. It is deliberately *not* a general HTML
 * converter: it throws when it finds nothing, so a redesign that breaks the assumption fails
 * the build instead of silently publishing an empty mirror of the privacy policy.
 *
 * The home page is not mirrored this way — it is a visual landing page whose text is mostly
 * widget labels, and `llms.txt` is already its plain-text form for machines.
 */
function staticPageMarkdown(html, { title, description, canonicalPath }) {
  const body = html
    .replace(/[\s\S]*?<div class="wrap">/i, '')
    .replace(/<header[\s\S]*?<\/header>/gi, '')
    .replace(/<footer[\s\S]*?<\/footer>/gi, '')
    .replace(/<svg[\s\S]*?<\/svg>/gi, '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '');

  const blocks = [];
  // One pass over the block-level elements in document order. Anything not matched here (the
  // eyebrow div, layout wrappers) carries no prose and is dropped on purpose.
  const re = /<(h1|h2|h3|p|li)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let m;
  while ((m = re.exec(body)) !== null) {
    const tag = m[1].toLowerCase();
    const text = inlineToMarkdown(m[2]);
    if (!text) continue;
    if (tag === 'h1') blocks.push(`# ${text}`);
    else if (tag === 'h2') blocks.push(`## ${text}`);
    else if (tag === 'h3') blocks.push(`### ${text}`);
    else if (tag === 'li') blocks.push(`- ${text.replace(/\n+/g, ' ')}`);
    else blocks.push(text);
  }
  if (blocks.length === 0) {
    throw new Error(`staticPageMarkdown(${canonicalPath}): found no prose — has the markup changed?`);
  }

  // Consecutive list items are one list, so they must not be separated by a blank line.
  let out = '';
  blocks.forEach((b, i) => {
    const prev = blocks[i - 1];
    const tight = b.startsWith('- ') && prev && prev.startsWith('- ');
    out += (i === 0 ? '' : tight ? '\n' : '\n\n') + b;
  });

  return (
    frontMatter({ title, description, canonical: `${SITE_URL}${canonicalPath}`, language: 'en' }) +
    `\n${out}\n\n---\n\nMarkdown mirror of ${SITE_URL}${canonicalPath}.\n`
  );
}

/** `compare/handy.html` → `compare/handy.md`. */
function markdownPathFor(outputPath) {
  return outputPath.replace(/\.html$/, '.md');
}

export { pageMarkdown, staticPageMarkdown, markdownPathFor, inlineToMarkdown, SITE_URL };
