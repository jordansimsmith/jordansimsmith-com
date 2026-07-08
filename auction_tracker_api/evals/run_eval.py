"""Eval harness for the auction tracker LLM judge.

Runs a candidate (model x system prompt) over a dataset split and reports
per-criterion TPR/TNR (positive = fail, i.e. the listing is disqualified),
overall verdict metrics, latency, and token usage / cost.

Usage:
    OPENAI_API_KEY=... bazel run //auction_tracker_api:run-eval -- \
        --model MODEL [--split dev] [--prompt prompts/v1.md] [--trials 1]
        [--limit N] [--price-input $/1M] [--price-output $/1M]

Few-shot examples always come from the train split, regardless of the split
being evaluated. Results are written to auction_tracker_api/evals/runs/.
"""

import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import pathlib
import statistics
import time

from openai import OpenAI

# under bazel run, __file__ lives in the runfiles tree (dataset and prompts are
# data deps) and run records are written back to the source tree
HERE = pathlib.Path(__file__).resolve().parent
DATASET = HERE / "dataset"
RUNS_DIR = (
    pathlib.Path(os.environ["BUILD_WORKSPACE_DIRECTORY"])
    / "auction_tracker_api"
    / "evals"
    / "runs"
)
CRITERIA = [
    "mtg_cards",
    "bulk_scale",
    "not_basic_lands",
    "not_universes_beyond",
    "civilian_seller",
    "fixed_collection",
]


def load_dataset():
    labels = json.loads((DATASET / "labels.json").read_text())
    splits = json.loads((DATASET / "splits.json").read_text())
    fixtures = {}
    for listing_id in labels:
        fixtures[listing_id] = json.loads((DATASET / f"{listing_id}.json").read_text())
    return fixtures, labels, splits


def build_system_prompt(prompt_path, fixtures, labels, train_ids):
    prompt = prompt_path.read_text()
    lines = ["\n## Examples\n"]
    for listing_id in train_ids:
        f, lab = fixtures[listing_id], labels[listing_id]
        expected = {}
        for c in CRITERIA:
            # null labels (inapplicable) are shown as pass per the prompt's rule
            expected[c] = lab[c] or "pass"
        lines.append(f"Title: {f['title']}")
        lines.append(f"Description: {f['description']}")
        lines.append("Expected judgment: " + json.dumps(expected))
        if lab.get("notes"):
            lines.append(f"Note: {lab['notes']}")
        lines.append("")
    return prompt + "\n".join(lines)


def judge_once(client, model, system_prompt, fixture, reasoning_effort):
    user = (
        "Judge this listing. Respond with the JSON object described in your instructions.\n\n"
        f"Title: {fixture['title']}\n"
        f"Description: {fixture['description']}"
    )
    kwargs = dict(
        model=model,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user},
        ],
        response_format={"type": "json_object"},
    )
    if reasoning_effort:
        kwargs["reasoning_effort"] = reasoning_effort
    start = time.monotonic()
    try:
        response = client.chat.completions.create(temperature=0, **kwargs)
    except Exception as e:
        # some reasoning models reject explicit temperature
        if "temperature" not in str(e):
            raise
        response = client.chat.completions.create(**kwargs)
    latency = time.monotonic() - start

    parsed = json.loads(response.choices[0].message.content)
    results = {}
    for c in CRITERIA:
        value = parsed.get(c)
        if not isinstance(value, dict) or value.get("result") not in ("pass", "fail"):
            raise ValueError(f"malformed judgment for criterion '{c}': {value!r}")
        results[c] = {
            "result": value["result"],
            "reasoning": value.get("reasoning", ""),
        }
    usage = response.usage
    return {
        "criteria": results,
        "latency_s": latency,
        "input_tokens": usage.prompt_tokens,
        "output_tokens": usage.completion_tokens,
    }


def judge_listing(client, model, system_prompt, fixture, trials, reasoning_effort):
    attempts = [
        judge_once(client, model, system_prompt, fixture, reasoning_effort)
        for _ in range(trials)
    ]
    voted = {}
    for c in CRITERIA:
        fails = sum(1 for a in attempts if a["criteria"][c]["result"] == "fail")
        result = "fail" if fails * 2 > trials else "pass"
        reasoning = next(
            a["criteria"][c]["reasoning"]
            for a in attempts
            if a["criteria"][c]["result"] == result
        )
        voted[c] = {"result": result, "reasoning": reasoning, "fail_votes": fails}
    overall = "pass" if all(voted[c]["result"] == "pass" for c in CRITERIA) else "fail"
    return {
        "criteria": voted,
        "overall": overall,
        "latency_s": sum(a["latency_s"] for a in attempts),
        "input_tokens": sum(a["input_tokens"] for a in attempts),
        "output_tokens": sum(a["output_tokens"] for a in attempts),
        "trials": trials,
    }


def compute_metrics(records, labels):
    metrics = {}
    for key in CRITERIA + ["overall"]:
        tp = tn = fp = fn = skipped = 0
        for listing_id, rec in records.items():
            expected = labels[listing_id].get(key)
            if expected is None:
                skipped += 1
                continue
            got = rec["overall"] if key == "overall" else rec["criteria"][key]["result"]
            if expected == "fail" and got == "fail":
                tp += 1
            elif expected == "pass" and got == "pass":
                tn += 1
            elif expected == "pass" and got == "fail":
                fp += 1
            else:
                fn += 1
        metrics[key] = {
            "tpr": tp / (tp + fn) if tp + fn else None,
            "tnr": tn / (tn + fp) if tn + fp else None,
            "labeled_fail": tp + fn,
            "labeled_pass": tn + fp,
            "skipped_null": skipped,
            "confusion": {"tp": tp, "tn": tn, "fp": fp, "fn": fn},
        }
    return metrics


def fmt_rate(value):
    return "  n/a" if value is None else f"{value:5.0%}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument(
        "--split", default="dev", choices=["train", "dev", "test", "all"]
    )
    parser.add_argument("--prompt", default="prompts/v1.md")
    parser.add_argument(
        "--reasoning-effort",
        choices=["minimal", "low", "medium", "high"],
        help="reasoning effort for models that support it (omit for model default)",
    )
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--price-input", type=float, help="USD per 1M input tokens")
    parser.add_argument("--price-output", type=float, help="USD per 1M output tokens")
    args = parser.parse_args()

    fixtures, labels, splits = load_dataset()
    if args.split == "all":
        ids = splits["train"] + splits["dev"] + splits["test"]
    else:
        ids = splits[args.split]
    if args.limit:
        ids = ids[: args.limit]

    prompt_path = HERE / args.prompt
    system_prompt = build_system_prompt(prompt_path, fixtures, labels, splits["train"])

    client = OpenAI()
    records, errors = {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(
                judge_listing,
                client,
                args.model,
                system_prompt,
                fixtures[i],
                args.trials,
                args.reasoning_effort,
            ): i
            for i in ids
        }
        for future in concurrent.futures.as_completed(futures):
            listing_id = futures[future]
            try:
                records[listing_id] = future.result()
            except Exception as e:
                errors[listing_id] = str(e)
                print(f"ERROR {listing_id}: {e}")

    metrics = compute_metrics(records, labels)
    latencies = [r["latency_s"] / r["trials"] for r in records.values()]
    input_tokens = sum(r["input_tokens"] for r in records.values())
    output_tokens = sum(r["output_tokens"] for r in records.values())
    cost = None
    if args.price_input is not None and args.price_output is not None:
        cost = (
            input_tokens * args.price_input + output_tokens * args.price_output
        ) / 1e6

    timestamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    effort_tag = f"-{args.reasoning_effort}" if args.reasoning_effort else ""
    run_dir = RUNS_DIR / f"{timestamp}-{args.model}{effort_tag}-{args.split}"
    run_dir.mkdir(parents=True)
    config = {
        "model": args.model,
        "reasoning_effort": args.reasoning_effort,
        "split": args.split,
        "trials": args.trials,
        "limit": args.limit,
        "prompt_file": args.prompt,
        "prompt_sha256": hashlib.sha256(system_prompt.encode()).hexdigest(),
        "labels_sha256": hashlib.sha256(
            (DATASET / "labels.json").read_bytes()
        ).hexdigest(),
        "splits_sha256": hashlib.sha256(
            (DATASET / "splits.json").read_bytes()
        ).hexdigest(),
        "timestamp": timestamp,
        "errors": errors,
    }
    (run_dir / "config.json").write_text(json.dumps(config, indent=2) + "\n")
    (run_dir / "results.json").write_text(
        json.dumps(dict(sorted(records.items())), indent=2) + "\n"
    )
    summary = {
        "metrics": metrics,
        "examples": len(records),
        "errors": len(errors),
        "mean_latency_s": statistics.mean(latencies) if latencies else None,
        "p95_latency_s": (
            sorted(latencies)[max(0, round(0.95 * len(latencies)) - 1)]
            if latencies
            else None
        ),
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "cost_usd": cost,
        "cost_per_listing_usd": cost / len(records)
        if cost is not None and records
        else None,
    }
    (run_dir / "metrics.json").write_text(json.dumps(summary, indent=2) + "\n")

    print(
        f"\nmodel={args.model} effort={args.reasoning_effort or 'default'} split={args.split}"
        f" trials={args.trials} examples={len(records)} errors={len(errors)}"
    )
    print("TPR = junk caught (of labeled fails, % judged fail)")
    print("TNR = keepers kept (of labeled passes, % judged pass)")
    print(f"{'criterion':22} {'TPR':>5} {'TNR':>5}   (fail n / pass n)")
    for key in CRITERIA + ["overall"]:
        m = metrics[key]
        print(
            f"{key:22} {fmt_rate(m['tpr'])} {fmt_rate(m['tnr'])}   ({m['labeled_fail']} / {m['labeled_pass']})"
        )
    print(
        f"\nmean latency {summary['mean_latency_s']:.2f}s | p95 {summary['p95_latency_s']:.2f}s"
    )
    print(f"tokens: {input_tokens} in / {output_tokens} out", end="")
    if cost is not None:
        print(f" | cost ${cost:.4f} (${summary['cost_per_listing_usd']:.5f}/listing)")
    else:
        print(" | pass --price-input/--price-output for cost")

    # disagreements for error analysis
    disagreements = []
    for listing_id, rec in sorted(records.items()):
        for c in CRITERIA + ["overall"]:
            expected = labels[listing_id].get(c)
            got = rec["overall"] if c == "overall" else rec["criteria"][c]["result"]
            if expected is not None and got != expected:
                reason = "" if c == "overall" else rec["criteria"][c]["reasoning"]
                disagreements.append((listing_id, c, expected, got, reason))
    if disagreements:
        print(f"\ndisagreements ({len(disagreements)}):")
        for listing_id, c, expected, got, reason in disagreements:
            title = fixtures[listing_id]["title"][:50]
            print(f"  {listing_id} [{title}] {c}: labeled {expected}, judged {got}")
            if reason:
                print(f"    judge: {reason}")
    print(f"\nrun saved to {run_dir}")


if __name__ == "__main__":
    main()
