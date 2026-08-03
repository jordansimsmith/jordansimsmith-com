import csv
import gzip
import json

import pytest

from evaluate import (
    evaluate_predictions,
    load_labels,
    load_oracle_ids,
    load_predictions,
)


def testEvaluatePredictionsShouldReportEndToEndAndConditionalMetrics(tmp_path):
    # arrange
    labels_path = tmp_path / "labels.csv"
    with labels_path.open("w", newline="") as labels_file:
        writer = csv.DictWriter(
            labels_file, fieldnames=["image", "Scryfall ID", "Name"]
        )
        writer.writeheader()
        writer.writerows(
            [
                {"image": "images/a.jpg", "Scryfall ID": "id-a", "Name": "A"},
                {"image": "images/b.jpg", "Scryfall ID": "id-b", "Name": "B"},
                {"image": "images/c.jpg", "Scryfall ID": "id-c", "Name": "C"},
            ]
        )

    predictions_path = tmp_path / "predictions.jsonl"
    predictions_path.write_text(
        "\n".join(
            [
                json.dumps(
                    {
                        "image": "images/a.jpg",
                        "latency_ms": 10,
                        "predictions": [
                            {"scryfall_id": "id-a", "score": 0.9},
                            {"scryfall_id": "id-x", "score": 0.1},
                        ],
                    }
                ),
                json.dumps(
                    {
                        "image": "images/b.jpg",
                        "latency_ms": 20,
                        "predictions": [
                            {"scryfall_id": "id-x", "score": 0.8},
                            {"scryfall_id": "id-b", "score": 0.7},
                        ],
                    }
                ),
                json.dumps(
                    {
                        "image": "images/c.jpg",
                        "latency_ms": 30,
                        "predictions": [],
                        "error": "card not detected",
                    }
                ),
            ]
        )
        + "\n"
    )

    metadata_path = tmp_path / "cards.jsonl.gz"
    with gzip.open(metadata_path, "wt") as metadata_file:
        for card in [
            {"id": "id-a", "oracle_id": "oracle-a"},
            {"id": "id-b", "oracle_id": "oracle-b"},
            {"id": "id-c", "oracle_id": "oracle-c"},
            {"id": "id-x", "oracle_id": "oracle-b"},
        ]:
            metadata_file.write(json.dumps(card) + "\n")

    # act
    report = evaluate_predictions(
        load_labels(labels_path),
        load_predictions(predictions_path),
        load_oracle_ids(metadata_path),
        top_ks=(1, 2),
    )

    # assert
    assert report["summary"] == {
        "total": 3,
        "detected": 2,
        "detection_rate": pytest.approx(2 / 3),
        "exact_printing": {
            "top_1_end_to_end": pytest.approx(1 / 3),
            "top_1_detected": pytest.approx(1 / 2),
            "top_2_end_to_end": pytest.approx(2 / 3),
            "top_2_detected": pytest.approx(1),
            "mrr_end_to_end": pytest.approx(0.5),
        },
        "oracle_card": {
            "top_1_end_to_end": pytest.approx(2 / 3),
            "top_1_detected": pytest.approx(1),
            "top_2_end_to_end": pytest.approx(2 / 3),
            "top_2_detected": pytest.approx(1),
            "mrr_end_to_end": pytest.approx(2 / 3),
        },
        "latency_ms": {
            "count": 3,
            "mean": pytest.approx(20),
            "p50": pytest.approx(20),
            "p95": pytest.approx(30),
        },
    }
    assert report["examples"][0]["exact_rank"] == 1
    assert report["examples"][1]["exact_rank"] == 2
    assert report["examples"][1]["oracle_rank"] == 1
    assert report["examples"][2]["detected"] is False


def testLoadPredictionsShouldRejectMissingAndDuplicateImages(tmp_path):
    # arrange
    predictions_path = tmp_path / "predictions.jsonl"
    predictions_path.write_text(
        "\n".join(
            [
                json.dumps({"image": "images/a.jpg", "predictions": []}),
                json.dumps({"image": "images/a.jpg", "predictions": []}),
            ]
        )
        + "\n"
    )

    # act
    with pytest.raises(ValueError, match="duplicate prediction"):
        load_predictions(predictions_path)

    # assert
    labels = [
        {"image": "images/a.jpg", "scryfall_id": "id-a", "name": "A"},
        {"image": "images/b.jpg", "scryfall_id": "id-b", "name": "B"},
    ]
    with pytest.raises(ValueError, match="missing predictions"):
        evaluate_predictions(
            labels,
            {"images/a.jpg": {"image": "images/a.jpg", "predictions": []}},
        )
