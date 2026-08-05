# OpenWispr pSEO — architecture & plan

Programmatic SEO for the marketing site, built as a **static-HTML generator**, not a framework.
Output is plain `.html` files checked into `website/` and served by the existing zero-dependency
`server.js` — nothing about the deployed site changes. This doc covers why these page types, how
the generator works, how it adapts the AutoMata/SellBikes.in pSEO playbook to a stack with no
build step, and the quality bar every generated page is held to.

## Why this shape, not something else

The prior pSEO project this borrows from (`~/Documents/Personal/AutoMata/astro-news/docs/ai-seo-handover/`)
runs on Astro with GSC-driven analyzers, an LLM planner, and auto-merging weekly crons. None of
that infrastructure exists here yet — OpenWispr's site has no Search Console history, no
`data/experiments/ledger.jsonl`, no analyzers. Copying the harness wholesale on day zero would be
exactly the failure mode `05-what-doesnt.md` warns about ("empty ledger makes the recency filter
toothless", "don't enable the weekly harness with <60d of GSC data"). So this is deliberately
**Phase 0**: the content-generation half of the playbook (data file → template → static page),
built by hand for the first batch, with the harness/analyzer half stubbed and documented for when
GSC access exists (see the punch list below).

The one lesson taken wholesale from `04-what-works.md` / `05-what-doesnt.md`: **pSEO only earns
its keep when the page answers a query with real demand and a genuine fact difference — not when
it's a template stamped N times.** SellBikes.in's guides averaged ~1 click each; their pSEO city
pages outperformed because the URL template mapped to a *real* per-query slice (brand × city) with
distinct, factual content per cell. OpenWispr has no such combinatorial data set (no per-city
pricing, no per-model specs at scale) — so this plan explicitly rejects mass-templating a fake
grid (e.g. "OpenWispr vs Wispr Flow for {city}" or "{100 professions} dictation app") and instead
ships a **small number of pages, each hand-populated with facts from `research/`**, sized to grow
only as new genuinely-differentiated angles appear.

## Batch 2 — page-volume-first for early weeks (explicit decision log)

After reviewing the first 4 pages, the user made a deliberate strategic call: **for the first few
weeks of a brand-new site, prioritize a larger number of pages** as a crawl-budget / early-indexing
strategy — get more of the site's genuinely defensible surface area in front of Google sooner,
rather than drip-feeding one page at a time as GSC data trickles in. This is accepted as a real
strategic tradeoff, not a reversal of the spam-policy discipline above: **the "no fake grid" rule
from Batch 1 still holds completely.** What changed is the *pace* of building out genuine breadth
that already existed in `research/` but hadn't been written up yet — not the standard for what
counts as a genuine page.

Concretely, Batch 2 adds 9 more comparison pages and 3 more long-tail/use-case pages — 12 new
pages, all individually cleared through the same checklist in "Spam-policy / quality bar" below,
none template-stamped. The primary-source thread (`research/01-primary-source.md`) that started
this whole research effort names 14 alternatives; the five multi-mention products (FluidVoice,
Handy, Amical, Muesli, Spokenly) are real, demand-validated peers, not a list assembled to hit a
page count. Every one of the 9 new comparisons is justified individually in "Comparison pages,
batch 2" below with the specific research doc + fact backing it. The accepted tradeoff, stated
plainly: **more maintenance surface** — 11 comparison pages and 5 long-tail pages now need
re-verification whenever a competitor's pricing or feature set changes, not 2 and 2. That cost is
accepted in exchange for indexing more of the site's real, sourced content sooner.

## Page types chosen, and why

### 1. Comparison pages (`/compare/{slug}.html`)

`OpenWispr vs Wispr Flow`, `OpenWispr vs superwhisper`. These target head-to-head competitor
queries, which the pain-point research (`research/04-pain-points.md`) shows have real organic
demand — the HN/Reddit threads cited there ("Free alternative to Wispr Flow…", "local-first
dictation you can trust") get 100s of points specifically on the free/open-source framing, not
privacy. A comparison page is the natural landing spot for that search intent.

Chose **Wispr Flow** and **superwhisper** for the first two, not the full competitor set in
`research/`, because:
- Both are the best-documented competitors in `research/02-competitors-desktop.md` — every claim
  used is sourced to a dated primary source (their own docs, changelog, status page), not an
  "unverified" flag.
- **Deliberately excluded: OpenWhispr** (`research/03-competitors-indie.md` "Brand risk 1"). One
  extra silent letter, same MIT license, same positioning, a funded competitor with 10x the
  stars. A comparison page against a name this close to our own would confuse search results
  (readers landing on `/compare/openwhispr` genuinely unsure which product they're on) and reads
  as bad faith. Not worth the ambiguity for the traffic.
  - Also excluded initially: Amical, because their Android on-device-vs-cloud architecture was
    flagged "unverified" in research. **Re-checked for Batch 2** against
    `research/08-open-questions.md` #3 — still genuinely unresolved (would need an APK teardown or
    a direct statement from Amical), so Batch 2's Amical page does not resolve the question by
    fiat. Instead it builds the comparison **around** the open question: every other fact about
    Amical (MIT license, platform status, context-aware formatting, stars) is sourced normally, and
    the on-device claim is explicitly hedged as "undisclosed," with an explicit note that this page
    does not claim OpenWispr is "the only" on-device Android option because of it. That is a
    materially different move from asserting Amical is cloud-based, which the research does not
    support either.

### Comparison pages, batch 2 (9 new)

Justified individually, each against the specific research fact set that clears the quality bar
below. All nine appear in `research/01-primary-source.md`'s "Alternatives named" table (the
primary-source LinkedIn thread), which is independent evidence of real organic demand for each,
not a list assembled to hit a page count:

- **Spokenly** (`compare/spokenly.html`) — `research/02-competitors-desktop.md`: broadest
  desktop+iOS platform coverage, free-forever local models, an explicit "Local Only Mode" hard
  privacy switch, and an explicit vendor statement about *why* they aren't open source. No Android.
- **VoiceInk** (`compare/voiceink.html`) — `research/02-competitors-desktop.md`: "the closest
  analogue to OpenWispr" per the research itself, GPL-3.0, whisper.cpp/Parakeet on-device, and
  documented as "the best failure-recovery UX found anywhere" in this research — a real gap
  OpenWispr doesn't hide. Apple Silicon only, one-time paid unlock.
- **Handy** (`compare/handy.html`) — `research/03-competitors-indie.md`: the volume leader
  (27,709 ★), MIT, 100% local at every tier, explicitly accessibility-positioned — and, per the
  same doc, "no mobile app at all," which is the clean, honest differentiator this page is built
  around.
- **FluidVoice** (`compare/fluidvoice.html`) — `research/03-competitors-indie.md`: the
  most-mentioned alternative in the primary-source thread (3 mentions), a genuinely novel
  custom-trained local cleanup model (Fluid-1), relicensed Apache-2.0→GPL-3.0 in Feb 2026. macOS
  15+ only, no Android shipped or waitlisted.
- **FreeFlow** (`compare/freeflow.html`) — `research/03-competitors-indie.md`: MIT, but the
  research's own README grep found it defaults to a cloud backend (Groq), not local — a real,
  sourced architectural contrast with OpenWispr's no-cloud-path stance, stated honestly rather than
  used to imply FreeFlow is dishonest (their own docs disclose the tradeoff too).
- **Muesli** (`compare/muesli.html`) — `research/03-competitors-indie.md`: local meeting
  transcription bundled with dictation, and "the clearest [model table] in the field" per the
  research — a real, sourced strength named directly. Apple Silicon macOS only.
- **Typeless** (`compare/typeless.html`) — `research/03-competitors-indie.md`: widest raw platform
  reach (mac/Win/iOS/Android) with a real Android IME keyboard, but closed source, $12–30/mo, and
  the research's own finding that multiple independent (competitor-adjacent, so hedged
  accordingly) reviews report cloud-only processing despite marketing that reads as local.
- **Raycast Dictation** (`compare/raycast.html`) — `research/02-competitors-desktop.md`: named in
  the primary-source thread as where a user switched *away* from Wispr Flow to. Cloud-only, no
  named model/subprocessor, free during an unpriced beta, no Android.
- **Amical** (`compare/amical.html`) — `research/03-competitors-indie.md` + `08-open-questions.md`
  #3: the most direct Android competitor and genuinely MIT/on-Google-Play, built specifically
  around the honest "undisclosed" framing described above rather than skipped outright.

**Not built, and why** (still excluded after the Batch 2 pass):
- **Srota** — only a single mention in the primary-source thread, and the research itself
  (`research/03-competitors-indie.md` "Brand risk 2") frames it mainly as a tagline/positioning
  collision with a one-person, unincorporated project, not a feature-comparable product. Building
  a comparison page here would mirror the same bad-faith-appearance problem that excluded
  OpenWhispr, at a much smaller scale of relevance. Declined.
- **OpenWhispr** — brand risk, unchanged from Batch 1's reasoning above.
- **Cactus** — not an end-user dictation app (an inference runtime/SDK); nothing to compare
  head-to-head against a dictation product. Also flagged `NOASSERTION` license in research, adding
  a second reason to leave it alone.
- **heyclicky** — `research/03-competitors-indie.md` says outright "not really a competitor"
  (a screen-aware assistant with dictation as one line item). Declined on the research's own say-so.

### 2. Long-tail / use-case pages

`/android/{slug}.html` for Android-specific guides, `/use-cases/{slug}.html` for angles that apply
to both platforms or target a specific audience (introduced in Batch 2, see below).

**Batch 1:** `On-device Whisper dictation for Android` and `Free, open-source voice dictation app`.
These target the two structural gaps the research keeps surfacing:
- `research/03-competitors-indie.md`: "Android has two competitors, and neither clearly claims
  free + open-source + genuinely on-device." That's a specific, defensible long-tail claim
  ("one of the few genuinely on-device, open-source dictation apps on Android" — phrased as "one
  of the few," not "the only," because Amical's architecture is unverified).
- `research/04-pain-points.md`: the free/open-source framing draws 20–100x the engagement of the
  privacy framing in organic discussion (143–591 points vs 2–8). So the long-tail pages lead with
  free + offline + no-account, matching `research/07-recommendations.md`'s explicit positioning
  guidance ("Lead with free and offline. Privacy is the closer, not the opener").

**Batch 2 (3 new, under `/use-cases/`):**
- **One free, open-source app for macOS and Android** (`use-cases/mac-and-android-dictation.html`)
  — `research/02` + `03`: the desktop cluster (Handy, FluidVoice, VoiceInk, Muesli) ships no
  Android at all; the Android cluster (Typeless, Amical) is closed-source/cloud-uncertain. OpenWispr
  is the one project genuinely straddling both with the same license and architecture — a real,
  structural claim, not a keyword swap of an existing page.
- **Multilingual dictation without unwanted translation**
  (`use-cases/multilingual-dictation.html`) — `research/04-pain-points.md` #8, "two flanks nobody
  is contesting": two separate, dated, organic user reports of Wispr Flow spontaneously
  translating instead of transcribing. Written with explicit hedging — it makes a structural
  argument about OpenWispr's architecture (no network round-trip to swap models mid-session), not
  an untested claim that OpenWispr has been benchmarked against this exact failure mode.
- **Dictation software with no server to send your keystrokes to**
  (`use-cases/dictation-it-wont-block.html`) — `research/04-pain-points.md` #6: a dated, organic
  report of a real corporate IT department banning Wispr Flow outright over keyboard/screen access
  concerns, plus Wispr's own documented Context Awareness data-collection scope. A distinct
  audience (IT/security reviewers) and a distinct argument (architecture vs. policy) from the
  existing free/open-source and on-device pages — not a rewrite of either.

**Declined for Batch 2, and why** (so the user knows what's still missing rather than assuming
coverage):
- **Generic "on-device Mac dictation" page.** `research/03-competitors-indie.md`'s own bottom
  line: "macOS is saturated… 'Another free on-device Mac dictation app' is a losing frame." Building
  this would contradict the research's own explicit recommendation, so it wasn't built. The
  cross-platform use-case page above is the differentiated version of this angle that *is*
  defensible.
- **"OpenWispr never loses your dictation" / crash-recovery angle.** This is real demand
  (`research/04-pain-points.md` #2, `research/07-recommendations.md`'s reliability pattern) and
  would be a strong page — but `research/07-recommendations.md`'s own competitive table lists
  OpenWispr as "No" on audio-kept / row-on-failure / retry-UI / crash-recovery **today**, and the
  fix is tracked as in-progress on a separate branch (`never-lose-audio`), not merged into what
  this worktree builds from. Claiming this feature now would misrepresent the current app. Declined
  until the fix ships; the existing comparison pages already hedge this honestly ("actively in
  development") rather than pretending otherwise.
- **Accessibility (VoiceOver/TalkBack) angle.** `research/07-recommendations.md` flags this as a
  real open flank nobody is contesting, and Handy proves the frame works — but no research doc
  documents OpenWispr's own current VoiceOver/TalkBack support level to make a truthful,
  specific claim about it. Declined for lack of a sourceable claim about OpenWispr itself, not
  for lack of demand.
- **Coding/terminal dictation workflow angle.** No research doc quantifies organic demand for this
  specific query shape (Wispr's Context Awareness sending code variable names is a privacy point,
  not a demand signal for a dedicated "dictation while coding" page). Declined for insufficient
  sourced demand.
- **Windows on-device dictation page.** OpenWispr's Windows build is marked "in progress," not
  shipped — a page targeting a platform-specific query for a platform that doesn't exist yet would
  be premature. Declined until Windows ships.

### 3. Journal pages (`/journal/{slug}.html`) — batch 3, new content type

**What these are, and why the URL/type is separate from `/use-cases/`.** "How I use OpenWispr in
my workflow" pieces — first-person narrative pages that walk through a real usage pattern and
surface specific app features along the way, rather than reading as a spec sheet. Given a new
top-level type (`journal`, not folded into `longtail`) and its own `/journal/` path, distinct from
`/use-cases/` and `/android/`, for one deliberate reason: this content makes a first-person voice
claim ("we/us", not "you might find") that no other page type on this site makes, and it needed a
disclosure guarantee (see below) that shouldn't silently apply to, or be silently skipped by,
other longtail-shaped pages. Keeping it a distinct `type` in `build.mjs`'s `RENDERERS` map means a
future contributor can't add a testimonial-shaped page under `longtail` by accident and skip the
disclosure step.

**The non-negotiable constraint this batch was built under: no fabricated persona.** A "workflow"
content type is an obvious place to slip into writing a fake customer testimonial — "Sarah, a
product manager, says…" — which is exactly the kind of invented-claim content the quality bar
above already rejects for comparison pages, just in a different shape. So every journal page is
framed as **the OpenWispr project's own** first-person account (how the people building OpenWispr
use it themselves, e.g. to write their own commit messages), not a fictional user, company, or job
title. This is enforced structurally, not just by editorial care: `render.mjs`'s `journalNoteHtml()`
renders a fixed disclosure strip — "This is a first-person account from the people building
OpenWispr... not a customer testimonial, and not a fictional user" — immediately under the
breadcrumb on every journal page, before any narrative content, with identical wording each time
(a data file cannot opt out of it or rephrase it away).

**Every feature claim traces to the actual Android/macOS source, not a marketing assumption.**
Verified directly against code before writing, not assumed from `docs/personalization.md` alone:

- **Personal vocabulary / auto-learning** — `VocabRepository.kt`'s `learnFromEdit` (up to 5
  position-aligned corrections per edit, deliberately conservative), and
  `textproc/VocabCorrector.kt`'s fuzzy matching (Soundex phonetic equality + edit-distance score,
  gated more strictly for fuzzy/learned matches than exact ones) plus its `biasPrompt` that feeds
  frequency-ranked corrected terms back into the on-device STT decoder.
- **Tone-by-app** — `AppToneRepository.kt` + `textproc/AppContext.kt`: a `Category` enum
  (generic/code/email/chat/social/notes) with per-category default LLM-polish tone fragments
  (professional for email, casual for chat/social; empty for code and notes). Important nuance
  actually reflected in the copy: `Category.CODE`'s tone fragment is empty because code/terminal
  text is instead handled by a *separate, deterministic* path (`textproc/CodeContext.kt`), not by
  an LLM tone override — the journal copy states this distinction rather than flattening it into
  "code mode gets a terse tone."
- **Code/terminal-aware formatting** — `textproc/CodeContext.kt`: dedicated code editors are
  always code-mode; terminals are decided *by the content of that utterance* (word count ≤10,
  starts with a known command verb/path/CLI flag), because the file's own doc comment records
  that ~70% of real terminal dictation turned out to be natural-language AI-agent prompts, not
  shell commands. The journal piece uses this exact reasoning, not an invented one.
- **Never-lose-audio / write-ahead store + retry** — `PendingAudio.kt`'s class doc (the RAM-only
  failure this was built to prevent), its write-ahead-to-`filesDir`-before-first-attempt
  guarantee, `inFlight` as in-memory-only "running" state, and `unfinished()`/`expired()` retention
  rules; `RewriteActivity.kt`'s retry path, which explicitly prefers a different STT engine on
  retry since a transcription failure is often deterministic. This feature was flagged **declined,
  not yet shipped** in Batch 2's punch list (tracked then on the `never-lose-audio` branch); it has
  since merged into `main` (commit `c4a2df7`, "Never lose the audio when a dictation fails (#25)"),
  so it's now a truthful claim about the shipped app rather than the misrepresentation Batch 2
  correctly avoided.
- **Personalization layers L1–L4** — `docs/personalization.md` (the map used to scope the second
  article), cross-checked against `CorrectionCorpus.kt` (ring-capped 500-entry on-device log of
  `{cleaned, final, edited}`), the few-shot retrieval in `CorrectionCorpus.similar`/`score` (token-
  Jaccard, `MIN_JACCARD = 0.2`, boosted for same app-context/edited rows — explicitly *not* dense
  RAG, and the copy says so), `Settings.kt`'s `PolishLevel` enum (`OFF`/`LIGHT`/`MEDIUM`/`FULL`,
  few-shot examples only injected at Medium/Full), and `CorrectionCorpus.exportJsonl` for the L4
  export path (explicit user action, training happens in the separate `openwispr-finetune` repo,
  never automatic upload).
- **On-device STT (Parakeet default, Whisper alternative)** — `OnDeviceStt.kt`: `"local"`
  provider's default model resolves to Parakeet (`ParakeetModelManager.MODEL_ID`); Whisper
  (tiny/base/small, `WhisperModelManager`) is the alternative on-device engine. Matches
  `llms.txt`'s existing "NVIDIA Parakeet-TDT (default) or Whisper" framing, not a new claim.
- **macOS parity, checked, not assumed** — before writing "we" without hedging, confirmed the
  Android features named above have real macOS equivalents in the same repo:
  `macos/OpenWisprCore/Sources/OpenWisprCore/VocabCorrector.swift`, `CorrectionCorpus.swift`, and
  `AppContext.swift`, plus `macos/App/Sources/PendingAudioStore.swift` and `StyleMemoryView.swift`.
  The journal copy speaks about the app in general rather than calling out one platform's file
  names, which is accurate given this parity — it would not have been accurate to write "we" if
  the feature only existed on one platform.

**Declined for lack of verification — named explicitly so nothing here is silently assumed:**
- **A specific quantified claim about how much time dictation actually saves per commit/PR.** No
  file in the repo or `research/` measures this; the articles describe the workflow and the
  mechanism, not a fabricated time-savings number.
- **IDE plugin / editor extension integration.** No such integration exists in the Android or
  macOS source — `CodeContext.kt`'s "dedicated code editors" list is a *package-name detection*
  list (the app runs alongside those apps as any Android/macOS IME/accessibility-style dictation
  tool would), not a plugin architecture. The articles do not claim one.
  Fair reading was verified: `CodeContext.kt` recognizes editor package names to switch
  normalization mode; it is not itself a VS Code/JetBrains extension.
- **A specific named example of a real commit message this repo shipped via dictation.** True in
  substance (a fair amount of this project's own commit history was dictated) but no commit is
  tagged or logged as dictated vs. typed, so no specific commit SHA is cited as evidence — the
  claim is kept at the level the record actually supports.
- **Windows.** Same reasoning as Batch 2: not shipped, so no journal-page claim assumes it.

Two articles shipped this batch, both new pages (no existing page renamed or restructured):
1. `journal/dictating-code-and-commits.json` → `/journal/dictating-code-and-commits.html` — a
   coding/technical workflow piece (commit messages, PR descriptions), grounding personal
   dictionary, tone-by-app/code-aware formatting, and never-lose-audio.
2. `journal/why-we-never-lose-your-audio.json` → `/journal/why-we-never-lose-your-audio.html` — a
   reflective piece on why the write-ahead audio store and engine-switching retry were built the
   way they were, then walking through personalization layers L1–L4 as the same principle applied
   one layer up.

Both are linked from `index.html`'s `#compare` section (a new "From the project journal:" row,
additive, placed after the existing "Guides:" row so it doesn't disturb prior links), from each
other via `relatedLinks`, and from `llms.txt`'s new "Journal" section — so neither is an orphan
page, consistent with the internal-linking rule in the quality bar below.

### What's explicitly NOT built (spam-policy discipline)

Google's Scaled Content Abuse policy (cited directly in the AutoMata handover,
`05-what-doesnt.md` #1) targets exactly the pattern this plan avoids: publishing pages against a
template with no distinct value per page. Concretely, **not building**:
- A per-city or per-profession grid ("dictation for lawyers", "dictation for nurses", ×50) — no
  factual basis per cell exists in `research/`, so every page would be interchangeable filler. This
  holds even under the Batch 2 volume-first decision above — volume came from real breadth of
  angles already in `research/`, never from templating one angle across a fake variable.
- A comparison page for every name that appears anywhere in `research/` regardless of source
  quality — Srota, OpenWhispr, Cactus, and heyclicky were all considered for Batch 2 and declined
  for specific, stated reasons above; a comparison page making claims from an explicitly
  `⚠️ Unverified` research section would be asserting things the research itself doesn't stand
  behind.
- Auto-generated FAQ blocks with LLM-invented "common questions" not grounded in the pain-point
  research. Every FAQ on every generated page below traces to a specific claim in `research/`.

The rule this plan holds itself to, adapted from `04-what-works.md`'s SellBikes.in lesson: **a
new page must answer a query with demand AND say something the existing pages don't** — not just
exist to grow a sitemap count.

## How the generator works

```
website/pseo/
├── README.md              # this file
├── data/                  # one JSON file per page — the ONLY place facts/copy live
│   ├── compare-wispr-flow.json          # batch 1
│   ├── compare-superwhisper.json        # batch 1
│   ├── android-on-device-whisper-dictation.json          # batch 1
│   ├── android-free-open-source-voice-dictation.json     # batch 1
│   ├── compare-spokenly.json            # batch 2
│   ├── compare-voiceink.json            # batch 2
│   ├── compare-handy.json               # batch 2
│   ├── compare-fluidvoice.json          # batch 2
│   ├── compare-freeflow.json            # batch 2
│   ├── compare-muesli.json              # batch 2
│   ├── compare-typeless.json            # batch 2
│   ├── compare-raycast.json             # batch 2
│   ├── compare-amical.json              # batch 2
│   ├── usecase-mac-and-android-dictation.json    # batch 2
│   ├── usecase-multilingual-dictation.json       # batch 2
│   ├── usecase-dictation-it-wont-block.json      # batch 2
│   ├── journal-dictating-code-and-commits.json          # batch 3
│   └── journal-why-we-never-lose-your-audio.json        # batch 3
├── lib/
│   └── render.mjs          # shared design-system components (nav, footer, FAQ, table, CTA,
│                            # journalNoteHtml — batch 3's disclosure strip)
│                            # — copy-pasted/adapted from index.html's inline styles so
│                            # generated pages are visually indistinguishable from hand-written ones
└── build.mjs                # reads data/*.json, dispatches on "type" (comparison/longtail/journal
                              # as of batch 3), writes static .html files into website/compare/,
                              # website/android/, website/use-cases/, and website/journal/, plus
                              # sitemap.xml/robots.txt — generically, from each data file's own
                              # outputPath/canonicalPath, so batch 3 needed one new "type" branch
                              # in build.mjs (renderJournalPage) but no changes to existing renderers
```

18 data files → 18 generated pages as of Batch 3 (4 from Batch 1, 12 from Batch 2, 2 from Batch 3),
plus the 2 hand-written pages (`index.html`, `privacy.html`) = **20 pages live on the site.**

Run it with:

```bash
cd website/pseo
node build.mjs
```

Zero npm dependencies — `build.mjs` and `render.mjs` use only `node:fs`, `node:path`, and
template literals. This matches the constraint that the *deployed* site stays
zero-dependency; there's no need for a dev-only dependency here either since the templating is
simple string assembly, not a real templating language.

**Data-driven, not hand-edited HTML.** Every fact-bearing string (competitor pricing, a quoted
review, a feature-parity claim) lives in the JSON data file with a `source` field pointing at the
`research/*.md` file and section it came from. `render.mjs` never invents copy — it only lays out
what the data file provides. This makes two things possible later: (1) a human/LLM content pass
can regenerate `data/*.json` from a fresh research pull without touching template code, and (2)
the harness described in the punch list below can eventually validate that every claim shipped
still has a `source` field before allowing a merge, mirroring the AutoMata harness's schema-level
safety gates (`docs/ai-seo-handover/03-harness-architecture.md`, `types.ts`).

**Design-system fidelity.** `render.mjs`'s color tokens, font stack, border-radius, and shadow
values are copied directly from `website/index.html`'s inline `oklch(...)` values (warm
cream/orange palette, Mulish + IBM Plex Mono, pill-shaped nav, card-with-1px-border pattern) so
generated pages render as native site pages, not a bolted-on template. The comparison table reuses
the exact grid/row structure from index.html's `#compare` section; the FAQ accordion reuses the
same `data-faq` / `data-faq-answer` / `data-faq-icon` JS pattern already shipping in
`index.html`'s inline `<script>` block.

**Output paths and internal linking.** Pages are flat `.html` files (`/compare/wispr-flow.html`,
not `/compare/wispr-flow/`) because `server.js` does not perform directory-index resolution below
`/` — it only special-cases the root path. Every generated page links back to `/` and to 2–3
sibling generated pages via its own `relatedLinks` (rendered as a "keep reading" footer block), and
`index.html`'s `#compare` section now links to all 11 comparison pages plus a new "Guides:" row
linking to all 5 long-tail/use-case pages (Batch 2 added `/use-cases/{slug}.html` links there
specifically because those pages have no other natural inbound link the way `/android/` guides get
picked up by comparison-page `relatedLinks`). This keeps every generated page reachable by an
internal link, which matters because the AutoMata orphan-pages analyzer
(`docs/ai-seo-handover/02-analyzers.md` #3) found that a page with zero inbound internal links gets
essentially no crawl-authority signal regardless of content quality.

**Sitemap.** `build.mjs` also (re)writes `website/sitemap.xml` covering `/`, `/privacy.html`, and
every generated page, plus a `website/robots.txt` pointing at it. Neither existed before this
change. This is required infrastructure for the orphan-pages analyzer in the punch list, and for
IndexNow once wired.

## Spam-policy / quality bar (explicit, not implied)

Every generated page must satisfy all of the following before it ships. This is the "quality gate"
the AutoMata build has as an automated score threshold (`weekly-seo-improvement.yml`, revert if
score < 70); here it's a manual checklist because there's no analyzer yet to automate it:

1. **Traces to research.** Every factual claim about a competitor has a `source` pointer into
   `research/*.md`. No claim sourced only from an `⚠️ Unverified` line in that research ships as a
   stated fact — it's either omitted or explicitly hedged in the copy.
2. **Not a template stamp.** The page says something the other generated pages don't. If two pages
   would be >60% identical prose with find-replaced nouns, don't ship the second one — that's
   exactly the "scaled content abuse" pattern.
3. **Genuinely useful if a competitor's own user landed on it.** The comparison pages state where
   OpenWispr is *worse* or *unproven* (e.g., 0 GitHub stars vs Wispr Flow's user base, no iOS,
   younger project) — not just a one-sided pitch. Research-backed criticism of OpenWispr itself
   (from `research/07-recommendations.md`'s "OpenWispr (today)" row showing "No" on audio
   retention/retry) is not hidden.
4. **One canonical URL per topic**, `<link rel="canonical">` self-referencing, no near-duplicate
   slugs for the same query.
5. **Internally linked** from at least one other page (see above) — no orphans.

## Punch list — what's stubbed pending API keys / infra

None of this blocks the pages already shipped; it's the natural next phase once the user adds
keys, following `docs/ai-seo-handover/01-setup-checklist.md`'s bring-up order.

- **Google Search Console.** No property is verified yet for `openwispr.dev`. Needed before any
  of the analyzers below can run. ~15 min per the handover checklist (DNS TXT verification).
- **GSC service account + `scripts/lib/gsc-client.ts`.** Not copied over — no credentials to test
  it against yet. When ready, copy from
  `~/Documents/Personal/AutoMata/astro-news/scripts/lib/gsc-client.ts` verbatim per
  `docs/ai-seo-handover/07-reusable-code.md`.
- **`ctr-audit.ts` / `topic-opportunities.ts` analyzers.** Meaningless with zero GSC history.
  Per the setup checklist, don't even attempt the CTR audit below ~60 days of impressions data —
  copy these over once that data exists, not before.
- **Topic-opportunities-driven page ideas.** The next batch of long-tail pages should ideally come
  from `topic-opportunities.ts` output (queries OpenWispr already ranks pos 10–80 for with no
  dedicated page) rather than more hand-guessing from `research/`. Requires GSC first.
- **LLM-assisted content generation at scale.** This first batch was written by hand from
  `research/` sources for factual precision. A `content-writer`-style provider abstraction
  (`docs/ai-seo-handover/07-reusable-code.md`'s `lib/providers/{anthropic,google,openai,base}.ts`)
  could scale a *second* batch once GSC identifies real query demand — but per `05-what-doesnt.md`
  lesson #1, do not scale page count ahead of measured demand. Needs `ANTHROPIC_API_KEY` and/or
  `GOOGLE_AI_API_KEY`; wire as env vars, never hardcoded.
- **IndexNow.** No key generated, no `scripts/indexnow-ping.ts`. Trivial to add later
  (`openssl rand -hex 16`, drop `public/{key}.txt` — here that's `website/{key}.txt`, since
  `server.js` serves the whole `website/` dir as the root) — but pointless to wire before there
  are enough pages shipping regularly to justify a ping script. Follow `/seo-indexnow-wire`.
  Explicitly: **do not reuse any key from the AutoMata/SellBikes.in repo** — it's a distinct
  ownership signature per host.
  Note: IndexNow only covers Bing/Yandex/Brave/Seznam — Google needs GSC's own crawl regardless.
- **The weekly autonomous harness (`scripts/agent/*`, title-rewrite action).** Not applicable yet
  — there's no CTR audit to feed it and, per `seo-harness-deploy`'s own precondition, it shouldn't
  be deployed before 4+ successful *manual* CTR-improvement cycles have shipped once GSC data
  exists. Revisit after the first 60–90 days of traffic.
- **`data/experiments/ledger.jsonl`.** Not created — nothing has shipped that needs the recency
  filter yet. When the harness is eventually stood up, backfill one row per page in this pSEO
  batch (`action_type: "pseo-page-add"`, `commit_sha` from git log) so day-one recency filtering
  isn't toothless (`05-what-doesnt.md` #3).
- **Batch 2 status (this pass).** Amical, VoiceInk, and Handy — the three candidates this list
  previously deferred — are now built; see "Comparison pages, batch 2" above for how each cleared
  the bar (Amical via honest hedging of the one open question, not by resolving it). The remaining
  deferred items are listed there too, under "Not built, and why" and "Declined for Batch 2."
- **Next comparison-page candidates for a future batch**, still not built: none of the currently
  known competitors clear the bar without a research update. A next pass should first re-run
  `research/08-open-questions.md` #1–3 (Android demand sizing, the Gboard comparison, and
  Amical's on-device status) rather than reach for more competitor names — per `05-what-doesnt.md`
  lesson #1, breadth should keep coming from genuine research findings, not from working down an
  ever-longer competitor list once the well-documented ones are exhausted.
- **Batch 3+ volume decision needs revisiting once GSC exists.** The user's page-volume-first call
  in "Batch 2" above was explicitly scoped to "the first few weeks" of a brand-new site with no
  Search Console history. Once GSC data exists, prefer `topic-opportunities.ts`-driven page ideas
  (see above) over more hand-guessed breadth from `research/` — the volume-first mode was a
  cold-start strategy, not the intended steady state.
- **Batch 3 status (this pass).** Added the `journal` page type and its first 2 pages — see
  "Journal pages (`/journal/{slug}.html`) — batch 3" above for the full grounding writeup. Notably,
  this pass also confirmed the never-lose-audio feature, declined for a page claim in Batch 2's
  punch list because it was still in-progress on a branch, has since merged to `main` (commit
  `c4a2df7`) — so it's now fair game for future comparison-page and use-case-page updates too, not
  just the journal pages built this round. A logical next step for a future batch: revisit the
  Batch 2 comparison pages that hedged "actively in development" on audio retention/retry and
  update that language now that it has shipped — not done in this pass because it's out of scope
  for "add a new content type," but flagged here so it isn't lost.
- **Journal page type — future candidates, not built this round.** Only 2 journal pages shipped,
  matching the task's ask. Other workflow angles that would need their own research/verification
  pass before writing (not built now, listed so nothing is silently assumed covered): a
  macOS-specific workflow piece (e.g. using the menu-bar app during a long writing session); an
  accessibility-angle journal piece (declined for the same reason as Batch 2's accessibility
  use-case page — no sourced claim yet about OpenWispr's own VoiceOver/TalkBack support level).
