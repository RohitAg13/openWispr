# Shared prompt & tone contract

The dictation prompt, the fine-tune system prompt, the per-context tone fragments, and the
cleanup-level instructions are **mirrored across several places**. They must stay byte-for-byte
in sync — drift silently changes behavior. This file is the human
source of truth and the index of every mirror.

> No codegen today: each platform keeps its own literal copy. When you change any string
> here, change **every** location in the sync set below in the same commit.

## Sync set

| What | Authoritative location |
|---|---|
| `DICTATION_PROMPT` (cloud/generic polish) | `android/.../Defaults.kt` |
| `FINETUNE_SYSTEM` + `FINETUNE_TONE` (the fine-tune's trained prompt) | `android/.../RewriteEngine.kt` |
| Per-category tone fragments (`DEFAULT_TONE`) | `android/.../textproc/AppContext.kt` |
| Cleanup-level instructions (`PolishLevel` Off/Light/Medium/Full) | `android/.../Settings.kt` |
| Training system prompt + tone | `openwispr-finetune/common.py` (separate repo) |
| macOS port | `macos/` (must mirror when implemented) |

## App-context categories

The focused app maps to one of: `email`, `chat`, `social`, `notes`, `code`, `generic`
(`AppContext.categoryFor`). The fine-tune's tone keys and `DEFAULT_TONE` use this exact set.

## Reference text

`DICTATION_PROMPT` (the contract every generic/cloud polish path uses):

```
This text is a raw speech-to-text transcript. Convert it into clean written text:
remove filler words (um, uh, like, you know), false starts and self-corrections,
fix obvious transcription errors, and add natural punctuation, capitalization and
paragraph breaks. Apply spoken editing commands literally if the speaker gives them
(e.g. 'new line', 'new paragraph', 'scratch that', 'period', 'comma'). Do not add,
remove, or reorder any actual content or meaning. Keep the speaker's own wording and voice.
```

The fine-tune (`FINETUNE_SYSTEM`/`FINETUNE_TONE`) is trained on a *different*, fixed prompt
ending in `/no_think`; it is mirrored verbatim from `openwispr-finetune/common.py` and must
not be edited independently — see the comments in `RewriteEngine.kt`.
