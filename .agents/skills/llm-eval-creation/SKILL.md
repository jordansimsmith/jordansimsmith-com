---
name: llm-eval-creation
description: Builds ground-truth eval frameworks for LLM features in this repo - binary criteria, a hand-labeled dataset with train/dev/test splits, versioned judge prompts, and a Bazel-run harness reporting per-criterion TPR/TNR and cost. Use when creating evals for an LLM judge or classifier, building a labeled or golden dataset, adding an eval harness, or comparing system prompts, models, or reasoning efforts for an LLM feature.
---

# LLM eval creation

Builds the evaluation framework for an LLM feature before (or alongside) the feature itself, so that changes to system prompts and base models can be compared on measured quality and cost instead of vibes.

The canonical worked example is `auction_tracker_api/evals/mtg_bulk/`: read its dataset codebook (`dataset/README.md`), the shared harness (`evals/run_eval.py`), and prompts (`prompts/`) before building a new eval, and follow their structure. A service's `evals/` directory holds one subdirectory per judge, all served by the one harness. The underlying principles this workflow relies on are distilled in [references/methodology.md](references/methodology.md) — read it before starting.

## Framing

LLM features in this repo are typically classifiers: given some input text, decide pass/fail against the owner's criteria. The repo owner is the sole user and domain expert, so "correct" is well-defined and they can hand-label examples themselves. That means **ground-truth evaluation**: candidates (model x prompt combinations) are scored directly against human labels. No LLM-judge calibration loop is needed — the thing under test is itself the judge, and the human labels are the yardstick.

Before anything else, pin down:

- **The input contract.** The eval must see exactly what production sees (same fields, same extraction, same truncation). Anything the production code will not have (price, images, seller history) is out of scope for the criteria.
- **What each error direction costs.** For a filter, a false fail silently drops good items (usually the worse error) and a false pass costs a wasted human look. This drives default rules and which metric to guard hardest.

## Workflow

Work through the steps in order. Each has a "done when" check.

### 1. Write the criteria as binary checks

Before any code, decompose the owner's intent into independent, per-criterion binary checks, each judgeable from the input contract alone. For each criterion write: what it measures, what pass vs fail looks like, the default when the listing gives no signal (disqualifier criteria default to pass), and 2-3 annotated real examples ("this fails because ..."). Never one blended holistic judgment. Record everything in the dataset codebook (`dataset/README.md`) — the codebook is the label spec and must be committed next to the labels — and list the criterion names in `dataset/criteria.json`, which the harness reads.

Done when: every criterion is independently checkable from input text alone, and the overall verdict rule (usually the AND of all criteria) is written down.

### 2. Snapshot the dataset immediately

Source data is often a wasting asset (auction listings expire, pages change). Scrape or export the raw examples into one JSON fixture per example, named by source id, with fields matching the production extraction exactly — same selectors, same whitespace handling, same truncation, same URL cleaning. Boilerplate the production code would include is signal, not noise; keep it.

Done when: fixtures are committed and re-running the eval never depends on the live source.

### 3. Hand-label everything, without LLM assistance

The owner labels every fixture on every criterion, from the fixture text only (not memory of the live page). Generate a labeling sheet (one block per example with the text and blank pass/fail lines plus a gut `overall` and free-text `notes`), have the owner fill it in, then parse into a structured `labels.json`:

```json
{
  "<id>": {
    "<criterion>": "pass|fail|null",
    "overall": "pass|fail",
    "notes": "optional"
  }
}
```

Use `null` where a criterion is inapplicable (e.g. downstream criteria when a gating criterion fails); the harness skips nulls when scoring. Validate that each gut `overall` matches the AND of the criteria — mismatches mean the criteria are wrong or incomplete. Dedup exact duplicates. Labeling doubles as the criteria stress-test: fold every point of friction from `notes` back into the codebook wording, and treat human labels as ground truth when they contradict earlier example annotations.

Done when: every remaining fixture is fully labeled, duplicates are removed, and per-criterion pass/fail counts are tallied.

### 4. Generate synthetics to fill label gaps

Only after real labeling, because the gaps are unknown until then and synthetics must embody the settled criteria wording. For each criterion that is thin on fails (or passes), author synthetic fixtures seeded from real examples' style — including realistic page boilerplate — each targeting one specific label, pre-labeled by construction. Mark them unambiguously (`"synthetic": true`, `synthetic://<id>` URL, `s<nnn>.json` filename). Include adversarial near-misses on both sides. The owner reviews every synthetic for label disagreement and for accidentally tripping a second criterion, which would poison the ground truth.

Done when: every criterion has enough of both labels to measure TPR and TNR (roughly 5+ fails minimum), and the owner has approved the synthetic labels.

### 5. Split train/dev/test

Split roughly 20/40/40 with a fixed seed, committed as `dataset/splits.json`. Train supplies few-shot prompt examples, dev is what prompts and models are iterated against, test is scored once at the end. Stratify so per-criterion fail counts and the synthetic fraction are balanced across splits, and pin near-duplicates (same seller, relisted items) to the same split so few-shot examples cannot leak into evaluation.

Done when: `splits.json` is committed with the seed and strategy recorded.

### 6. Build the harness

A single Python script per service, `evals/run_eval.py`, shared by all of the service's judges and exposed as a Bazel `py_binary` (see `run-eval` in `auction_tracker_api/BUILD.bazel`). When adding a judge to a service that already has a harness, reuse it — only add a judge subdirectory and, if needed, judge-specific flags.

- Dependencies go through the root pip lock: add to `requirements.in`, run `bazel run //:requirements.update`.
- Dataset and prompt files are `data` deps read from runfiles; run records are written to the source tree via `BUILD_WORKSPACE_DIRECTORY`. Per-judge `runs/` directories are already gitignored repo-wide (`**/evals/**/runs/`).
- Flags: `--judge` (required, the judge's subdirectory name), `--model` (required), `--split` (default dev), `--prompt` (default `prompts/v1.md`, relative to the judge directory), `--trials` (majority vote), `--limit`, `--reasoning-effort`, `--price-input`/`--price-output` (USD per 1M tokens).
- Criteria are read from the judge's `dataset/criteria.json`, never hardcoded.
- The judge call uses temperature 0 and structured JSON output: per criterion an object with `reasoning` and `result` ("pass"/"fail"). The overall verdict is derived in code as the AND of criteria, never asked of the model.
- Report per-criterion TPR and TNR (positive = fail/disqualified), never accuracy, with the plain-language framing printed on every run (e.g. "TPR = junk caught, TNR = keepers kept"), plus mean/p95 latency, token counts, and cost when prices are given.
- Print every disagreement with the judge's reasoning — this is the error-analysis feed.
- Save each run to a timestamped directory under `runs/` with `config.json` (model, effort, split, trials, prompt file, SHA-256 of the assembled prompt, labels, and splits), `results.json`, and `metrics.json`, so every number is reproducible.

Usage shape:

```bash
OPENAI_API_KEY=... bazel run //<service>_api:run-eval -- --judge <judge> --model <model> --split dev
```

Done when: a small `--limit` smoke run works end-to-end through `bazel run` and the run record lands in the source tree.

### 7. Write the judge prompt

`prompts/v1.md` distills the codebook into judge-facing language: role framing, the input's nature (including boilerplate), the criteria with fail signals and explicit default-pass rules, and the output JSON schema. Few-shot examples from the train split are appended at runtime by the harness, not baked into the file. Prompts are the experiment variable: never edit a version in place once measured — copy to `v2.md` and iterate there.

Done when: a baseline run on dev is recorded.

### 8. Iterate

In this order — fix the yardstick, then the biggest lever, then sweep:

1. **Adjudicate disputed labels first.** Read baseline disagreements; where the judge's reasoning suggests the label (or a synthetic's wording) is wrong, get the owner's ruling before optimizing toward wrong answers.
2. **Iterate the prompt on one model against dev** until remaining disagreements look like genuine judgment limits rather than ambiguous instructions. Encode codebook principles, not per-example patches.
3. **Sweep the frozen prompt over the model x reasoning-effort grid** on dev with `--trials 3` (small dev sets make single-run differences noise) and pricing flags, producing the cost/performance table.
4. **Optionally, one targeted prompt round for the cheapest near-viable model** — weaker models need more explicit instructions.
5. **Run the top candidates on test once.** That is the honest number for the production decision. Never hill-climb on test.

## Layout

```
<service>_api/evals/
  run_eval.py        # shared harness, exposed as bazel run //<service>_api:run-eval
  <judge>/           # one subdirectory per judge (e.g. mtg_bulk/, ram/)
    dataset/         # <id>.json fixtures, s<nnn>.json synthetics, criteria.json,
                     # labels.json, splits.json, README.md codebook
    prompts/         # v1.md, v2.md, ... (versioned, the thing under test)
    runs/            # gitignored run records
```

## Commit hygiene

Commit the reproducible core and nothing ephemeral:

- **Commit:** fixtures, `criteria.json`, `labels.json`, `splits.json`, the dataset `README.md` codebook, `prompts/*.md`, `run_eval.py`, the `py_binary` target, requirements changes. Treat the golden dataset like code — the hand labels are the most expensive artifact to recreate, and labels without their codebook rot.
- **Keep local:** `runs/` (gitignored; reproducible from pinned SHAs), labeling worksheets once parsed into `labels.json`, scrape/generation throwaway scripts, and session planning docs (`tmp/`).
