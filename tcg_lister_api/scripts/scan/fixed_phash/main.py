import argparse
import csv
import json
import math
import os
import time
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps


CARD_SIZE = (146, 204)
HASH_SIZE = 8
DCT_SIZE = 32


def crop_fixture_card(image):
    image = ImageOps.exif_transpose(image).convert("RGB")
    if image.width > image.height:
        image = image.transpose(Image.Transpose.ROTATE_270)

    width, height = image.size
    search_box = (
        round(width * 0.15),
        round(height * 0.36),
        round(width * 0.85),
        round(height * 0.995),
    )
    search = image.crop(search_box)
    grayscale = ImageOps.grayscale(search)
    dark_pixels = grayscale.point(lambda value: 255 if value < 145 else 0)
    bounds = dark_pixels.getbbox()
    if not bounds:
        raise ValueError("no dark card boundary found in fixture region")

    left, top, right, bottom = bounds
    detected_width = right - left
    detected_height = bottom - top
    aspect_ratio = detected_width / detected_height
    if not 0.55 <= aspect_ratio <= 0.85:
        raise ValueError(
            f"detected fixture crop has unexpected aspect ratio {aspect_ratio:.3f}"
        )

    padding_x = round(detected_width * 0.01)
    padding_y = round(detected_height * 0.01)
    card_box = (
        max(0, left - padding_x),
        max(0, top - padding_y),
        min(search.width, right + padding_x),
        min(search.height, bottom + padding_y),
    )
    return search.crop(card_box).resize(CARD_SIZE, Image.Resampling.LANCZOS)


def prepare_reference(image):
    return (
        ImageOps.exif_transpose(image)
        .convert("RGB")
        .resize(CARD_SIZE, Image.Resampling.LANCZOS)
    )


def crop_artwork(card):
    width, height = card.size
    return card.crop(
        (
            round(width * 0.07),
            round(height * 0.14),
            round(width * 0.93),
            round(height * 0.56),
        )
    )


@lru_cache(maxsize=1)
def _dct_matrix():
    coordinates = np.arange(DCT_SIZE)
    frequencies = coordinates[:, None]
    matrix = np.cos(math.pi * (2 * coordinates + 1) * frequencies / (2 * DCT_SIZE))
    matrix[0] *= math.sqrt(1 / DCT_SIZE)
    matrix[1:] *= math.sqrt(2 / DCT_SIZE)
    return matrix


def perceptual_hash(image):
    grayscale = ImageOps.grayscale(image).resize(
        (DCT_SIZE, DCT_SIZE), Image.Resampling.LANCZOS
    )
    pixels = np.asarray(grayscale, dtype=np.float64)
    matrix = _dct_matrix()
    transformed = matrix @ pixels @ matrix.T
    low_frequencies = transformed[:HASH_SIZE, :HASH_SIZE]
    median = np.median(low_frequencies.flatten()[1:])
    bits = low_frequencies > median

    value = 0
    for bit in bits.flatten():
        value = (value << 1) | int(bit)
    return value


def rank_candidates(query_hash, references, top_k):
    best_by_card = {}
    for reference in references:
        distance = (query_hash ^ reference["hash"]).bit_count()
        current = best_by_card.get(reference["scryfall_id"])
        if current is None or distance < current:
            best_by_card[reference["scryfall_id"]] = distance

    ranked = sorted(best_by_card.items(), key=lambda item: (item[1], item[0]))[:top_k]
    return [
        {
            "scryfall_id": scryfall_id,
            "distance": distance,
            "score": 1 - distance / (HASH_SIZE * HASH_SIZE),
        }
        for scryfall_id, distance in ranked
    ]


def _hash_reference(record, index_directory):
    image_path = index_directory / record["path"]
    with Image.open(image_path) as image:
        image_hash = perceptual_hash(crop_artwork(prepare_reference(image)))
    return {
        "scryfall_id": record["scryfall_id"],
        "face": record.get("face", "front"),
        "hash": f"{image_hash:016x}",
    }


def build_index(reference_index, output, workers):
    reference_index = Path(reference_index)
    records = [
        json.loads(line)
        for line in reference_index.read_text().splitlines()
        if line.strip()
    ]
    if not records:
        raise ValueError("reference artwork index is empty")

    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output.with_suffix(output.suffix + ".tmp")
    with (
        ThreadPoolExecutor(max_workers=workers) as executor,
        temporary_output.open("w") as output_file,
    ):
        for completed, result in enumerate(
            executor.map(
                lambda record: _hash_reference(record, reference_index.parent),
                records,
            ),
            start=1,
        ):
            output_file.write(json.dumps(result, separators=(",", ":")) + "\n")
            if completed % 1000 == 0 or completed == len(records):
                print(f"Indexed {completed}/{len(records)} references")
    os.replace(temporary_output, output)


def load_hash_index(path):
    references = []
    with Path(path).open() as index_file:
        for line_number, line in enumerate(index_file, start=1):
            if not line.strip():
                continue
            record = json.loads(line)
            try:
                references.append(
                    {
                        "scryfall_id": record["scryfall_id"],
                        "hash": int(record["hash"], 16),
                    }
                )
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"invalid hash index line {line_number}") from error
    if not references:
        raise ValueError("hash index is empty")
    return references


def predict(labels, dataset_root, hash_index, output, top_k, debug_crops=None):
    with Path(labels).open(newline="") as labels_file:
        examples = list(csv.DictReader(labels_file))
    references = load_hash_index(hash_index)
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
                with Image.open(dataset_root / image_name) as image:
                    crop = crop_fixture_card(image)
                if debug_crops:
                    crop.save(debug_crops / f"{Path(image_name).stem}.jpg")
                ranked = rank_candidates(
                    perceptual_hash(crop_artwork(crop)), references, top_k
                )
                record = {
                    "image": image_name,
                    "detected": True,
                    "latency_ms": (time.perf_counter() - started) * 1000,
                    "predictions": [
                        {
                            "scryfall_id": candidate["scryfall_id"],
                            "score": candidate["score"],
                        }
                        for candidate in ranked
                    ],
                }
            except (OSError, ValueError) as error:
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
        description="Fixture-specific full-card perceptual-hash retrieval spike."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    index_parser = subparsers.add_parser("build-index")
    index_parser.add_argument("reference_index", type=Path)
    index_parser.add_argument("output", type=Path)
    index_parser.add_argument("--workers", type=int, default=8)

    predict_parser = subparsers.add_parser("predict")
    predict_parser.add_argument("labels", type=Path)
    predict_parser.add_argument("dataset_root", type=Path)
    predict_parser.add_argument("hash_index", type=Path)
    predict_parser.add_argument("output", type=Path)
    predict_parser.add_argument("--top-k", type=int, default=5)
    predict_parser.add_argument("--debug-crops", type=Path)

    args = parser.parse_args()
    if args.command == "build-index":
        build_index(args.reference_index, args.output, args.workers)
    else:
        predict(
            args.labels,
            args.dataset_root,
            args.hash_index,
            args.output,
            args.top_k,
            args.debug_crops,
        )


if __name__ == "__main__":
    main()
