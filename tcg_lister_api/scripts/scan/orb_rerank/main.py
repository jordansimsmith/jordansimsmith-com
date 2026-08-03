import argparse
import csv
import json
import math
import os
import time
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps


CARD_SIZE = (600, 840)
ARTWORK_SIZE = (480, 240)
HASH_SIZE = 8
DCT_SIZE = 32


def crop_fixture_card(image):
    image = ImageOps.exif_transpose(image).convert("RGB")
    if image.width > image.height:
        image = image.transpose(Image.Transpose.ROTATE_270)

    width, height = image.size
    search = image.crop(
        (
            round(width * 0.15),
            round(height * 0.36),
            round(width * 0.85),
            round(height * 0.995),
        )
    )
    grayscale = ImageOps.grayscale(search)
    dark_pixels = grayscale.point(lambda value: 255 if value < 145 else 0)
    bounds = dark_pixels.getbbox()
    if not bounds:
        raise ValueError("no dark card boundary found in fixture region")

    left, top, right, bottom = bounds
    aspect_ratio = (right - left) / (bottom - top)
    if not 0.55 <= aspect_ratio <= 0.85:
        raise ValueError(
            f"detected fixture crop has unexpected aspect ratio {aspect_ratio:.3f}"
        )
    return search.crop(bounds).resize(CARD_SIZE, Image.Resampling.LANCZOS)


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

    value = 0
    for bit in (low_frequencies > median).flatten():
        value = (value << 1) | int(bit)
    return value


def phash_shortlist(query_hash, references, limit):
    return sorted(
        references,
        key=lambda reference: (
            (query_hash ^ reference["hash"]).bit_count(),
            reference["scryfall_id"],
        ),
    )[:limit]


def _orb_features(image):
    if isinstance(image, Image.Image):
        image = np.asarray(ImageOps.grayscale(image).resize(ARTWORK_SIZE))
    if image.shape[::-1] != ARTWORK_SIZE:
        image = cv2.resize(image, ARTWORK_SIZE, interpolation=cv2.INTER_AREA)
    image = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(image)
    detector = cv2.ORB_create(
        nfeatures=500,
        scaleFactor=1.2,
        nlevels=8,
        edgeThreshold=8,
        patchSize=15,
        fastThreshold=7,
    )
    return detector.detectAndCompute(image, None)


def _match_features(query_features, candidate_features):
    query_keypoints, query_descriptors = query_features
    candidate_keypoints, candidate_descriptors = candidate_features
    if query_descriptors is None or candidate_descriptors is None:
        return 0.0
    if len(query_descriptors) < 2 or len(candidate_descriptors) < 2:
        return 0.0

    matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
    matches = matcher.knnMatch(query_descriptors, candidate_descriptors, k=2)
    good = [
        first for first, second in matches if first.distance < 0.75 * second.distance
    ]
    if len(good) < 4:
        return float(len(good))

    query_points = np.float32(
        [query_keypoints[match.queryIdx].pt for match in good]
    ).reshape(-1, 1, 2)
    candidate_points = np.float32(
        [candidate_keypoints[match.trainIdx].pt for match in good]
    ).reshape(-1, 1, 2)
    _, inlier_mask = cv2.findHomography(query_points, candidate_points, cv2.RANSAC, 4.0)
    inliers = int(inlier_mask.sum()) if inlier_mask is not None else 0
    return inliers + len(good) / 100


def orb_match_score(query, candidate):
    return _match_features(_orb_features(query), _orb_features(candidate))


def _index_reference(record, index_directory):
    image_path = (index_directory / record["path"]).resolve()
    with Image.open(image_path) as image:
        artwork = crop_artwork(prepare_reference(image))
        image_hash = perceptual_hash(artwork)
    return {
        "scryfall_id": record["scryfall_id"],
        "face": record.get("face", "front"),
        "path": str(image_path),
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
                lambda record: _index_reference(record, reference_index.parent),
                records,
            ),
            start=1,
        ):
            output_file.write(json.dumps(result, separators=(",", ":")) + "\n")
            if completed % 1000 == 0 or completed == len(records):
                print(f"Indexed {completed}/{len(records)} references")
    os.replace(temporary_output, output)


def load_index(path):
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
                        "path": record["path"],
                        "hash": int(record["hash"], 16),
                    }
                )
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(
                    f"invalid reference index line {line_number}"
                ) from error
    if not references:
        raise ValueError("reference index is empty")
    return references


def rank_with_orb(query_artwork, references, shortlist_size, top_k):
    query_hash = perceptual_hash(query_artwork)
    shortlisted = phash_shortlist(query_hash, references, shortlist_size)
    query_features = _orb_features(query_artwork)
    best_by_card = {}
    for reference in shortlisted:
        with Image.open(reference["path"]) as image:
            candidate_artwork = crop_artwork(prepare_reference(image))
        orb_score = _match_features(query_features, _orb_features(candidate_artwork))
        phash_distance = (query_hash ^ reference["hash"]).bit_count()
        combined_score = orb_score + (1 - phash_distance / 64) / 1000
        current = best_by_card.get(reference["scryfall_id"])
        if current is None or combined_score > current:
            best_by_card[reference["scryfall_id"]] = combined_score

    ranked = sorted(
        best_by_card.items(),
        key=lambda item: (item[1], item[0]),
        reverse=True,
    )[:top_k]
    return [
        {"scryfall_id": scryfall_id, "score": score} for scryfall_id, score in ranked
    ]


def predict(
    labels,
    dataset_root,
    reference_index,
    output,
    shortlist_size,
    top_k,
    debug_crops=None,
):
    with Path(labels).open(newline="") as labels_file:
        examples = list(csv.DictReader(labels_file))
    references = load_index(reference_index)
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
                    card = crop_fixture_card(image)
                artwork = crop_artwork(card)
                if debug_crops:
                    artwork.save(debug_crops / f"{Path(image_name).stem}.jpg")
                ranked = rank_with_orb(
                    artwork,
                    references,
                    shortlist_size,
                    top_k,
                )
                record = {
                    "image": image_name,
                    "detected": True,
                    "latency_ms": (time.perf_counter() - started) * 1000,
                    "predictions": ranked,
                }
            except (OSError, ValueError, cv2.error) as error:
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
        description="pHash-shortlisted ORB artwork matching spike."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    index_parser = subparsers.add_parser("build-index")
    index_parser.add_argument("reference_index", type=Path)
    index_parser.add_argument("output", type=Path)
    index_parser.add_argument("--workers", type=int, default=8)

    predict_parser = subparsers.add_parser("predict")
    predict_parser.add_argument("labels", type=Path)
    predict_parser.add_argument("dataset_root", type=Path)
    predict_parser.add_argument("reference_index", type=Path)
    predict_parser.add_argument("output", type=Path)
    predict_parser.add_argument("--shortlist", type=int, default=100)
    predict_parser.add_argument("--top-k", type=int, default=5)
    predict_parser.add_argument("--debug-crops", type=Path)

    args = parser.parse_args()
    if args.command == "build-index":
        build_index(args.reference_index, args.output, args.workers)
    else:
        predict(
            args.labels,
            args.dataset_root,
            args.reference_index,
            args.output,
            args.shortlist,
            args.top_k,
            args.debug_crops,
        )


if __name__ == "__main__":
    main()
