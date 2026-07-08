# Eval methodology

The principles behind the workflow in [SKILL.md](../SKILL.md). Distilled from standard applied-AI evaluation practice; self-contained on purpose.

## When an eval is worth building

Evals target errors you have observed (or can induce), not hypothetical ones. Their value scales with how much iteration the fix needs:

- Failure never observed: do error analysis first — look at real outputs before writing any eval.
- Observed, but fixable in one change: a cheap code assertion or just fixing it may beat building an eval.
- Observed and needs sustained iteration (hill-climbing prompts or models): high value; this is the case the full framework exists for.

Prefer objective code-based checks (schema validity, parseability, bounds, deterministic rules) wherever failure is objective. Reserve LLM calls for judgments that cannot be expressed as code — and even then, keep each one a narrow binary decision.

## Choosing the evaluation type

- **Ground truth evaluation**: the task has objectively correct answers that can be labeled in advance (classification, extraction). Score outputs directly against labels with classification metrics. No calibration needed — there is one correct answer per example.
- **LLM-as-judge with calibration**: quality is subjective with no single correct answer. The judge itself must then be validated against human scores before its numbers can be trusted; an uncalibrated judge is worse than useless because it gives false confidence.

A feature that is itself an LLM classifier, evaluated against the owner's hand labels, is the first case: the labels are the ground truth and no separate calibration loop exists.

## Dataset standards

- Target roughly 100 labeled examples to start; treat it as a living asset that grows as new failure modes appear.
- **Humans label, without LLM assistance.** Human annotation captures subtleties models miss and is what makes the dataset trustworthy. Label from exactly the text the system will see — labels based on outside information set an unlearnable standard.
- **Check class balance per criterion.** On imbalanced data a degenerate model looks great (a criterion with 5% fails is 95% "accurate" for a model that always passes). Ensure each binary check has enough examples of both labels; deliberately source or synthesize the thin side.
- **Label the first upstream failure**, not downstream cascade effects, and keep a gut overall verdict alongside per-criterion labels as a consistency check on the criteria themselves.
- **Treat the golden dataset like code**: versioned, reviewed, deduplicated, never silently edited. Every result is meaningless without the exact dataset version it ran against.
- The labeling spec (the codebook) is committed next to the labels. Labels without their codebook rot: the judgments stay but their semantics leak away, and future disagreements have no referee.

## Synthetic data

- Generate synthetics only after real data is labeled: the real labels reveal which gaps need filling, and labeling stress-tests the criteria wording that synthetics must embody.
- Seed generation from real examples' style and structure, then inject one targeted change per synthetic ("rewrite this real listing as X"). Zero-shot "generate 50 test cases" yields generic, repetitive inputs.
- Synthetics are pre-labeled by construction, but a human still reviews each one: a synthetic that accidentally trips a second criterion poisons the ground truth.
- Mark provenance permanently so real and synthetic examples are never confused.
- Include adversarial near-misses on both sides of each criterion, not just clear-cut gap fillers.

## Metric rules

- **Application-specific binary outcomes only.** No generic scores (helpfulness, faithfulness, BLEU-style metrics) and no 1-5 scales: scales are expensive to align, raters and models drift to the middle, and they smuggle in holistic scope. Binary pass/fail forces a decision and mirrors the real question ("is this good enough?").
- **Report TPR and TNR, never accuracy.** Convention: "positive" is the class being detected (the failure/disqualification), as in spam or fraud detection.
  - TPR = TP / (TP + FN): of labeled fails, the fraction the system also failed (failures caught).
  - TNR = TN / (TN + FP): of labeled passes, the fraction the system also passed (good items kept).
  - Accuracy hides one error direction behind the other on imbalanced data. Aim high on both (>90% as a reference point), and know which direction hurts more for the product.
  - The pass/fail wording collides awkwardly with positive/negative; make reports self-documenting with plain-language column framing.
- Score each criterion independently, and derive any overall verdict in code from the per-criterion results.

## Judge call standards

- Binary pass/fail per criterion, with a clear task and criterion in plain language.
- Structured JSON output with `reasoning` and `result` per criterion; reasoning must cite the input text that drove the judgment.
- Few-shot pass/fail examples drawn from the train split, assembled at runtime.
- Temperature 0; judges are still non-deterministic, so majority-vote over 3+ trials when comparing close candidates.
- Pin everything a result depends on — model version, prompt version, dataset version — in the run record, so any number can be reproduced and drift is detectable.

## Train/dev/test splits

Roughly 20/40/40 — different from ML training splits because nothing is trained; the data only informs the prompt:

- **Train (~20%)**: the pool few-shot examples are drawn from. The model sees these labels.
- **Dev (~40%)**: labels hidden from the model, used repeatedly by you to score candidates while iterating.
- **Test (~40%)**: labels hidden from the model and barely used by you — scored once, on final candidates, so the number is honest.

Every example is labeled; "held out" refers to what the labels are used for, not whether they exist. Keep near-duplicates in the same split to prevent leakage. Guard the test split: every mid-iteration peek erodes its value as the final check.

## Error analysis loop

The highest-ROI activity. The loop is: measure, read every disagreement, classify why, fix the right layer, re-measure.

- **Read every disagreement**, with the judge's reasoning. Don't just watch aggregate numbers move.
- **Specification failures before generalization failures.** A disagreement usually means ambiguous criteria wording before it means a bad model. Fix the codebook and prompt rubric first; only then compare models. Comparing models on a flawed prompt can invert the ranking.
- **Sometimes the label is wrong.** When the judge's reasoning mirrors hesitation the labeler recorded, adjudicate the label with the owner rather than tuning toward it. The codebook is the referee; without it the temptation is to quietly bend labels toward whatever the model says.
- **A perfect score is a warning, not a victory.** Saturated evals stop guiding improvement; add harder cases.
- Feed newly discovered failures back into the dataset routinely.

## Iteration ordering

Fix the measurement instrument first, then the variable with the largest effect, then sweep the grid:

1. Labels (adjudicate disputes — every run before the yardstick settles may need rerunning).
2. Prompt, on one fixed model against dev, until disagreements look genuine.
3. Model x reasoning-effort grid with the frozen prompt, multi-trial, with cost per example — reasoning effort is part of the model config, not a separate axis.
4. Optionally one prompt round for the cheapest near-viable model (weaker models need more explicit instructions).
5. Top candidates on test, once each.

On small dev sets (tens of examples), differences of one or two examples are noise; use majority voting and don't make strong claims from single runs.
