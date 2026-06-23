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

## L4 · Periodic re-fine-tune  (export live, training offline)

When the corpus is large enough, retrain a genuinely better cleanup model.

Export the corrected samples from the device — two ways:
- **In-app:** Settings → **Style memory** → **View** → **Export** (shares the JSONL and
  also writes it to the app's external files dir). **Clear all** wipes the corpus.
- **adb** (for scripting):

```bash
# 1. Export the corrected samples from the device
adb shell am broadcast -a com.voicerewriter.EXPORT_CORPUS -n com.voicerewriter/.EvalDumpReceiver
adb pull /sdcard/Android/data/com.voicerewriter/files/corpus/finetune.jsonl

# 2. Train in the SEPARATE repo (never in this one), then re-gate before shipping
#    openwispr-finetune: prepare_data.py merges this with the base dataset → Unsloth LoRA → GGUF
#    eval/run_eval.py --gate finetuned:<gguf> --judge   # must PASS before the app points at it
```

`CorrectionCorpus.exportJsonl` emits only **edited** rows as `{context, input, output}`
(`context` = app category, `input` = pipeline output, `output` = what you kept). Verbatim
accepts are excluded — they'd just teach the model to echo itself.

> Privacy: the export contains your dictation text. It's written to the app's external
> files dir only on explicit broadcast, and the personal mined corpus must never be
> committed (same rule as the Wispr data: gitignored, separate repo).

## Where each layer lives

| Layer | Code |
|---|---|
| L1 | `VocabRepository.kt` (`learnFromEdit`, `learnAlias`), `textproc/VocabCorrector.kt` (`correct`, `biasPrompt`) |
| L2 | `CorrectionCorpus.kt` (`record`), wired at the 3 accept points in `RewriteActivity.kt` |
| L3 | `CorrectionCorpus.similar`/`rank`/`score`, `RewriteActivity.fewShotBlock` + prompt assembly in `process()` |
| L4 | `CorrectionCorpus.exportJsonl`, `EvalDumpReceiver` `EXPORT_CORPUS` action; training in `openwispr-finetune` |
