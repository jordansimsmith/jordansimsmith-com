import argparse
import csv
import json
import os
import time
from pathlib import Path

import cv2
import collector_vision as cvg


def orient_portrait(image):
    if image.shape[1] > image.shape[0]:
        return cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
    return image


def choose_best_hits(searches):
    nonempty = [hits for hits in searches if hits]
    if not nonempty:
        return []
    return max(nonempty, key=lambda hits: hits[0][0])


def predict(
    labels,
    dataset_root,
    output,
    catalog_uri,
    cache_dir,
    provider,
    top_k,
    debug_crops=None,
):
    os.environ["COLLECTORVISION_CACHE_DIR"] = str(Path(cache_dir).resolve())
    catalog = cvg.Catalog.load(catalog_uri)
    detector = cvg.NeuralCornerDetector(provider=provider)

    with Path(labels).open(newline="") as labels_file:
        examples = list(csv.DictReader(labels_file))
    dataset_root = Path(dataset_root)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if debug_crops:
        debug_crops = Path(debug_crops)
        debug_crops.mkdir(parents=True, exist_ok=True)

    temporary_output = output.with_suffix(output.suffix + ".tmp")
    with temporary_output.open("w") as output_file:
        for completed, example in enumerate(examples, start=1):
            started = time.perf_counter()
            image_name = example["image"]
            try:
                image = cv2.imread(str(dataset_root / image_name))
                if image is None:
                    raise ValueError("OpenCV could not read image")
                image = orient_portrait(image)
                detection = detector.detect(image)
                if not detection.card_present:
                    record = {
                        "image": image_name,
                        "detected": False,
                        "latency_ms": (time.perf_counter() - started) * 1000,
                        "predictions": [],
                        "error": "CollectorVision did not detect a card",
                        "detection_confidence": float(detection.confidence),
                    }
                else:
                    crop = detection.dewarp(image)
                    if debug_crops:
                        crop.save(debug_crops / f"{Path(image_name).stem}.png")
                    crops = [crop, cvg.rotate_card_180(crop)]
                    embeddings = catalog.embedder.embed(crops)
                    searches = [
                        catalog.search(embedding, top_k=top_k)
                        for embedding in embeddings
                    ]
                    hits = choose_best_hits(searches)
                    record = {
                        "image": image_name,
                        "detected": True,
                        "latency_ms": (time.perf_counter() - started) * 1000,
                        "predictions": [
                            {
                                "scryfall_id": card_id,
                                "score": float(score),
                            }
                            for score, card_id in hits
                        ],
                        "detection_confidence": float(detection.confidence),
                    }
            except Exception as error:
                record = {
                    "image": image_name,
                    "detected": False,
                    "latency_ms": (time.perf_counter() - started) * 1000,
                    "predictions": [],
                    "error": str(error),
                }
            output_file.write(json.dumps(record, separators=(",", ":")) + "\n")
            print(f"Predicted {completed}/{len(examples)}: {image_name}")
    os.replace(temporary_output, output)


def main():
    parser = argparse.ArgumentParser(
        description="CollectorVision neural card-retrieval spike."
    )
    parser.add_argument("labels", type=Path)
    parser.add_argument("dataset_root", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--catalog",
        default="hf://HanClinto/milo/scryfall-mtg",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("tmp/tcg-scan/collectorvision/cache"),
    )
    parser.add_argument("--provider", choices=["auto", "cpu", "gpu"], default="cpu")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--debug-crops", type=Path)
    args = parser.parse_args()
    predict(
        args.labels,
        args.dataset_root,
        args.output,
        args.catalog,
        args.cache_dir,
        args.provider,
        args.top_k,
        args.debug_crops,
    )


if __name__ == "__main__":
    main()
