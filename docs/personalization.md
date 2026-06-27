# Personalization — "the more you use it, the better it gets"

OpenWispr adapts to you in four layers, cheapest → most powerful. L1–L3 are live and
fully on-device; L4 is the offline retraining path (data export ships in the app,
training lives in the separate `openwispr-finetune` repo).

Everything below is gated by the **Keep history** switch and never leaves the device
(except L4, which *you* explicitly export and move).

## L1 · Personal vocabulary  (live)

Mis-heard words/names → canonical spelling, applied right after STT and before cleanup
(`VocabCorrector.correct`), plus a glossary that biases Whisper's decoding
(`VocabCorrector.biasPrompt`).

Learned from:
- **Home → Teach** — stack several `When I say X → Write Y` rows in one pass.
- **Review sheet → Edit** — `VocabRepository.learnFromEdit` diffs the kept text against
  the pipeline output and remembers up to **5** position-aligned word fixes (name-like,
  conservative — big rewrites are ignored so the glossary stays clean).

The bias prompt is budget-capped (~Whisper 224-token limit), so it's **frequency-ranked**:
terms you actually had to correct (`learnedAliases`) come first, then manual entries, then
imported contacts. The more you correct a name, the more reliably it's heard next time.

## L2 · Correction corpus  (live)

`CorrectionCorpus` (`correction_corpus.jsonl`, ring-capped 500) records every accepted
dictation as `{ts, category, cleaned, final, edited}` — the text the pipeline produced vs.
what you kept. When they differ, you corrected something: the core training signal. This
is the shared substrate for L1 (analysis), L3 (retrieval) and L4 (export).

## L3 · Few-shot polish  (live)

When LLM polish is on at **Medium/Full**, the prompt is prepended with your 2 closest past
corrections (`CorrectionCorpus.similar` → token-Jaccard, boosted for same app-context and
actually-edited rows). The model sees how *you* like dictation cleaned and matches it —
no retraining. Skipped at **Light** (and when polish is Off) to keep latency/context small
on the tiny on-device model; examples are clipped to ~240 chars each.

**Retrieval is lexical, not dense RAG.** `CorrectionCorpus.score` ranks by token-set
Jaccard overlap with small additive boosts (+0.15 same category, +0.10 edited), gated at
`MIN_JACCARD = 0.2`. This is the RAG *shape* (retrieve-then-inject) with a sparse keyword
retriever — deliberately, because the corpus is tiny (≤500 short, literal snippets) so a
linear Jaccard scan is microseconds and needs no on-device embedding model. Trade-off: it's
blind to paraphrase (won't connect "gonna"→"going to" from a learned "wanna"→"want to").

> **Future work (not now):** swap to dense RAG if paraphrase misses show up in real use —
> bundle a small sentence-embedding model (ONNX/TFLite, ~20–90 MB), precompute a vector per
> sample at `record()` time, and replace the Jaccard core in `score()` with cosine similarity
> (keep the category/edited boosts as re-rankers). Gate on whether the model weight is worth it.

## L4 · Periodic re-fine-tune  (export live, training offline)

When the corpus is large enough, retrain a genuinely better cleanup model.

Export the corrected samples from the device: Settings → **Style memory** → **View** →
**Export** shares the JSONL (and writes it to the app's external files dir); **Clear all**
wipes the corpus. Training happens in the separate `openwispr-finetune` repo (never in this
one), and the resulting GGUF is published for the app to download.

`CorrectionCorpus.exportJsonl` emits only **edited** rows as `{context, input, output}`
(`context` = app category, `input` = pipeline output, `output` = what you kept). Verbatim
accepts are excluded — they'd just teach the model to echo itself.

> Privacy: the export contains your dictation text. It's written to the app's external
> files dir only on explicit broadcast, and the personal mined corpus must never be
> committed — keep it gitignored and out of this repo.

## Where each layer lives

| Layer | Code |
|---|---|
| L1 | `VocabRepository.kt` (`learnFromEdit`, `learnAlias`), `textproc/VocabCorrector.kt` (`correct`, `biasPrompt`) |
| L2 | `CorrectionCorpus.kt` (`record`), wired at the 3 accept points in `RewriteActivity.kt` |
| L3 | `CorrectionCorpus.similar`/`rank`/`score`, `RewriteActivity.fewShotBlock` + prompt assembly in `process()` |
| L4 | `CorrectionCorpus.exportJsonl` (in-app export); training in `openwispr-finetune` |
