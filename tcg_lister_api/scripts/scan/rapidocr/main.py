import argparse
import csv
import gzip
import json
import os
import re
import time
import unicodedata
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from rapidfuzz import fuzz, process
from rapidocr import RapidOCR


CARD_SIZE = (900, 1260)
COLLECTOR_FRACTION = re.compile(r"\b(\d{1,4}[a-z]?)\s*[/|]\s*\d{1,4}\b", re.I)


def normalize_name(value):
    front_name = value.split("//", 1)[0]
    ascii_name = (
        unicodedata.normalize("NFKD", front_name).encode("ascii", "ignore").decode()
    )
    return " ".join(re.findall(r"[a-z0-9]+", ascii_name.lower()))


def normalize_collector_number(value):
    match = re.fullmatch(r"0*(\d+)([a-z]?)", value.strip(), re.I)
    if not match:
        return value.strip().lower()
    return f"{int(match.group(1))}{match.group(2).lower()}"


def extract_print_hints(text):
    fraction = COLLECTOR_FRACTION.search(text)
    collector_number = (
        normalize_collector_number(fraction.group(1)) if fraction else None
    )
    words = set(re.findall(r"\b[a-z0-9]{2,8}\b", text.lower()))
    return collector_number, words


def load_cards(metadata_path):
    cards_by_name = {}
    opener = gzip.open if Path(metadata_path).suffix == ".gz" else open
    with opener(metadata_path, "rt") as metadata_file:
        for line in metadata_file:
            if not line.strip():
                continue
            card = json.loads(line)
            card_id = card.get("id")
            name = card.get("name")
            if not card_id or not name:
                continue
            normalized_name = normalize_name(name)
            cards_by_name.setdefault(normalized_name, []).append(
                {
                    "id": card_id,
                    "name": name,
                    "set": str(card.get("set", "")).lower(),
                    "collector_number": normalize_collector_number(
                        str(card.get("collector_number", ""))
                    ),
                    "released_at": str(card.get("released_at", "")),
                }
            )
    if not cards_by_name:
        raise ValueError("Scryfall metadata contains no named cards")
    return cards_by_name


def rank_cards(title_texts, bottom_texts, cards_by_name, top_k):
    names = list(cards_by_name)
    matched_names = {}
    for title_text in title_texts:
        normalized_title = normalize_name(title_text)
        if not normalized_title:
            continue
        for matched_name, name_score, _ in process.extract(
            normalized_title,
            names,
            scorer=fuzz.WRatio,
            limit=5,
        ):
            matched_names[matched_name] = max(
                matched_names.get(matched_name, 0), name_score
            )
    if not matched_names:
        return []

    collector_number, bottom_words = extract_print_hints(" ".join(bottom_texts))
    ranked = []
    seen_ids = set()
    for matched_name, name_score in matched_names.items():
        for card in cards_by_name[matched_name]:
            if card["id"] in seen_ids:
                continue
            seen_ids.add(card["id"])
            collector_match = (
                collector_number is not None
                and collector_number == card["collector_number"]
            )
            set_match = bool(card["set"] and card["set"] in bottom_words)
            score = name_score / 100
            if collector_match:
                score += 0.2
            if set_match:
                score += 0.25
            if collector_match and set_match:
                score += 0.25
            ranked.append(
                {
                    "scryfall_id": card["id"],
                    "score": score,
                    "released_at": card["released_at"],
                }
            )

    ranked.sort(
        key=lambda candidate: (
            candidate["score"],
            candidate["released_at"],
            candidate["scryfall_id"],
        ),
        reverse=True,
    )
    return ranked[:top_k]


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
    return search.crop(bounds).resize(CARD_SIZE, Image.Resampling.LANCZOS)


def _ocr_text(engine, image):
    result = engine(np.asarray(image))
    if result is None or not result.txts:
        return []
    return [
        text
        for text, score in zip(result.txts, result.scores)
        if text.strip() and score >= 0.3
    ]


def recognize_text(engine, card):
    width, height = card.size
    title_region = card.crop(
        (
            round(width * 0.035),
            round(height * 0.025),
            round(width * 0.965),
            round(height * 0.155),
        )
    )
    bottom_region = card.crop(
        (
            round(width * 0.025),
            round(height * 0.875),
            round(width * 0.975),
            round(height * 0.995),
        )
    )
    title_texts = _ocr_text(engine, title_region)
    if not title_texts:
        title_texts = _ocr_text(engine, card)
    bottom_texts = _ocr_text(engine, bottom_region)
    return title_texts, bottom_texts, title_region, bottom_region


def predict(
    labels,
    dataset_root,
    metadata,
    output,
    top_k,
    debug_crops=None,
):
    with Path(labels).open(newline="") as labels_file:
        examples = list(csv.DictReader(labels_file))
    cards_by_name = load_cards(metadata)
    engine = RapidOCR()
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
                title_texts, bottom_texts, title_region, bottom_region = recognize_text(
                    engine, card
                )
                if debug_crops:
                    title_region.save(
                        debug_crops / f"{Path(image_name).stem}-title.jpg"
                    )
                    bottom_region.save(
                        debug_crops / f"{Path(image_name).stem}-bottom.jpg"
                    )
                ranked = rank_cards(
                    title_texts,
                    bottom_texts,
                    cards_by_name,
                    top_k,
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
                    "ocr_title": title_texts,
                    "ocr_bottom": bottom_texts,
                }
                if not ranked:
                    record["error"] = "OCR produced no card candidates"
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
        description="RapidOCR title and printing-text retrieval spike."
    )
    parser.add_argument("labels", type=Path)
    parser.add_argument("dataset_root", type=Path)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--debug-crops", type=Path)
    args = parser.parse_args()
    predict(
        args.labels,
        args.dataset_root,
        args.metadata,
        args.output,
        args.top_k,
        args.debug_crops,
    )


if __name__ == "__main__":
    main()
