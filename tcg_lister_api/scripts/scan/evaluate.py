import argparse
import csv
import gzip
import json
import math
import os
import statistics
from pathlib import Path


def _resolve_user_path(path):
    path = Path(path)
    workspace = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    if workspace and not path.is_absolute():
        return Path(workspace) / path
    return path


def load_labels(path):
    with Path(path).open(newline="") as labels_file:
        rows = list(csv.DictReader(labels_file))

    labels = []
    seen_images = set()
    for row_number, row in enumerate(rows, start=2):
        image = row.get("image", "").strip()
        scryfall_id = row.get("Scryfall ID", "").strip()
        if not image or not scryfall_id:
            raise ValueError(f"invalid label at CSV row {row_number}")
        if image in seen_images:
            raise ValueError(f"duplicate label for image {image}")
        seen_images.add(image)
        labels.append(
            {
                "image": image,
                "scryfall_id": scryfall_id,
                "name": row.get("Name", "").strip(),
            }
        )

    if not labels:
        raise ValueError("labels CSV contains no examples")
    return labels


def _open_text(path):
    path = Path(path)
    if path.suffix == ".gz":
        return gzip.open(path, "rt")
    return path.open()


def load_oracle_ids(path):
    card_to_oracle = {}
    with _open_text(path) as metadata_file:
        for line_number, line in enumerate(metadata_file, start=1):
            if not line.strip():
                continue
            try:
                card = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"invalid metadata JSON at line {line_number}"
                ) from error
            card_id = str(card.get("id", "")).strip()
            oracle_id = str(card.get("oracle_id", "")).strip()
            if card_id and oracle_id:
                card_to_oracle[card_id] = oracle_id
    if not card_to_oracle:
        raise ValueError("Scryfall metadata contains no card-to-oracle mappings")
    return card_to_oracle


def load_predictions(path):
    predictions = {}
    with Path(path).open() as predictions_file:
        for line_number, line in enumerate(predictions_file, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"invalid prediction JSON at line {line_number}"
                ) from error

            image = str(record.get("image", "")).strip()
            candidates = record.get("predictions")
            if not image or not isinstance(candidates, list):
                raise ValueError(f"invalid prediction at line {line_number}")
            if image in predictions:
                raise ValueError(f"duplicate prediction for image {image}")

            normalized_candidates = []
            for candidate_index, candidate in enumerate(candidates, start=1):
                if not isinstance(candidate, dict):
                    raise ValueError(
                        f"invalid candidate {candidate_index} at line {line_number}"
                    )
                scryfall_id = str(candidate.get("scryfall_id", "")).strip()
                score = candidate.get("score")
                if (
                    not scryfall_id
                    or isinstance(score, bool)
                    or not isinstance(score, (int, float))
                    or not math.isfinite(score)
                ):
                    raise ValueError(
                        f"invalid candidate {candidate_index} at line {line_number}"
                    )
                normalized_candidates.append(
                    {"scryfall_id": scryfall_id, "score": float(score)}
                )

            latency_ms = record.get("latency_ms")
            if latency_ms is not None and (
                isinstance(latency_ms, bool)
                or not isinstance(latency_ms, (int, float))
                or not math.isfinite(latency_ms)
                or latency_ms < 0
            ):
                raise ValueError(f"invalid latency at line {line_number}")

            detected = record.get("detected", bool(normalized_candidates))
            if not isinstance(detected, bool):
                raise ValueError(f"invalid detected value at line {line_number}")
            if not detected and normalized_candidates:
                raise ValueError(
                    f"prediction at line {line_number} has candidates but was not detected"
                )

            predictions[image] = {
                "image": image,
                "detected": detected,
                "latency_ms": float(latency_ms) if latency_ms is not None else None,
                "predictions": normalized_candidates,
                "error": record.get("error"),
            }

    if not predictions:
        raise ValueError("predictions JSONL contains no examples")
    return predictions


def _rank(items, expected):
    for index, item in enumerate(items, start=1):
        if item == expected:
            return index
    return None


def _rate(numerator, denominator):
    return numerator / denominator if denominator else None


def _percentile(values, percentile):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def _retrieval_metrics(examples, rank_field, top_ks, detected):
    metrics = {}
    for top_k in top_ks:
        hits = sum(
            example[rank_field] is not None and example[rank_field] <= top_k
            for example in examples
        )
        metrics[f"top_{top_k}_end_to_end"] = _rate(hits, len(examples))
        metrics[f"top_{top_k}_detected"] = _rate(hits, detected)
    metrics["mrr_end_to_end"] = statistics.fmean(
        1 / example[rank_field] if example[rank_field] else 0 for example in examples
    )
    return metrics


def evaluate_predictions(labels, predictions, card_to_oracle=None, top_ks=(1, 3, 5)):
    label_images = {label["image"] for label in labels}
    missing = sorted(label_images - predictions.keys())
    unknown = sorted(predictions.keys() - label_images)
    if missing:
        raise ValueError(f"missing predictions for {', '.join(missing)}")
    if unknown:
        raise ValueError(f"predictions contain unknown images: {', '.join(unknown)}")

    top_ks = tuple(sorted(set(top_ks)))
    if not top_ks or top_ks[0] < 1:
        raise ValueError("top-k values must be positive")

    if card_to_oracle:
        missing_oracles = sorted(
            label["scryfall_id"]
            for label in labels
            if label["scryfall_id"] not in card_to_oracle
        )
        if missing_oracles:
            raise ValueError(
                f"Scryfall metadata is missing labelled cards: {', '.join(missing_oracles)}"
            )

    examples = []
    for label in labels:
        prediction = predictions[label["image"]]
        candidate_ids = [
            candidate["scryfall_id"] for candidate in prediction["predictions"]
        ]
        exact_rank = _rank(candidate_ids, label["scryfall_id"])
        oracle_rank = None
        expected_oracle = None
        if card_to_oracle:
            expected_oracle = card_to_oracle[label["scryfall_id"]]
            candidate_oracles = [
                card_to_oracle.get(card_id) for card_id in candidate_ids
            ]
            oracle_rank = _rank(candidate_oracles, expected_oracle)

        examples.append(
            {
                "image": label["image"],
                "name": label["name"],
                "expected_scryfall_id": label["scryfall_id"],
                "expected_oracle_id": expected_oracle,
                "detected": prediction["detected"],
                "top_prediction": candidate_ids[0] if candidate_ids else None,
                "top_score": (
                    prediction["predictions"][0]["score"]
                    if prediction["predictions"]
                    else None
                ),
                "exact_rank": exact_rank,
                "oracle_rank": oracle_rank,
                "latency_ms": prediction["latency_ms"],
                "error": prediction["error"],
            }
        )

    detected = sum(example["detected"] for example in examples)
    latencies = [
        example["latency_ms"]
        for example in examples
        if example["latency_ms"] is not None
    ]
    summary = {
        "total": len(examples),
        "detected": detected,
        "detection_rate": _rate(detected, len(examples)),
        "exact_printing": _retrieval_metrics(examples, "exact_rank", top_ks, detected),
        "latency_ms": {
            "count": len(latencies),
            "mean": statistics.fmean(latencies) if latencies else None,
            "p50": _percentile(latencies, 0.5),
            "p95": _percentile(latencies, 0.95),
        },
    }
    if card_to_oracle:
        summary["oracle_card"] = _retrieval_metrics(
            examples, "oracle_rank", top_ks, detected
        )

    return {
        "schema_version": 1,
        "top_ks": list(top_ks),
        "summary": summary,
        "examples": examples,
    }


def _print_summary(report):
    summary = report["summary"]
    print(
        f"Detection: {summary['detected']}/{summary['total']} "
        f"({summary['detection_rate']:.1%})"
    )
    for metric_name in ["exact_printing", "oracle_card"]:
        if metric_name not in summary:
            continue
        label = metric_name.replace("_", " ").title()
        metrics = summary[metric_name]
        results = "  ".join(
            f"top-{top_k}: {metrics[f'top_{top_k}_end_to_end']:.1%}"
            for top_k in report["top_ks"]
        )
        print(f"{label}: {results}  MRR: {metrics['mrr_end_to_end']:.3f}")
    latency = summary["latency_ms"]
    if latency["count"]:
        print(
            f"Latency: mean {latency['mean']:.1f} ms  "
            f"p50 {latency['p50']:.1f} ms  p95 {latency['p95']:.1f} ms"
        )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Evaluate ranked MTG scan predictions against labelled photos."
    )
    parser.add_argument("labels", type=Path)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("--scryfall-metadata", type=Path)
    parser.add_argument("--top-k", type=int, nargs="+", default=[1, 3, 5])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    labels_path = _resolve_user_path(args.labels)
    predictions_path = _resolve_user_path(args.predictions)
    metadata_path = (
        _resolve_user_path(args.scryfall_metadata) if args.scryfall_metadata else None
    )
    output_path = _resolve_user_path(args.output) if args.output else None
    card_to_oracle = load_oracle_ids(metadata_path) if metadata_path else None
    report = evaluate_predictions(
        load_labels(labels_path),
        load_predictions(predictions_path),
        card_to_oracle,
        args.top_k,
    )
    if output_path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, indent=2) + "\n")
    _print_summary(report)
    return 0
