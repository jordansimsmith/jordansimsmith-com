import argparse
import csv
import gzip
import json
import os
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlparse

import requests


USER_AGENT = "jordansimsmith-com-tcg-scan/0.1"
API_HEADERS = {"Accept": "application/json", "User-Agent": USER_AGENT}
IMAGE_HEADERS = {"Accept": "image/*", "User-Agent": USER_AGENT}
BULK_TYPES = ("oracle_cards", "unique_artwork", "default_cards", "all_cards")
IMAGE_VERSIONS = ("small", "normal", "large", "png", "art_crop", "border_crop")


def _resolve_user_path(path):
    path = Path(path)
    workspace = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    if workspace and not path.is_absolute():
        return Path(workspace) / path
    return path


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(json.dumps(value, indent=2) + "\n")
    os.replace(temporary_path, path)


def _download_to_path(response, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=destination.name + ".", suffix=".part", dir=destination.parent
    )
    try:
        with os.fdopen(descriptor, "wb") as temporary_file:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    temporary_file.write(chunk)
        if Path(temporary_name).stat().st_size == 0:
            raise ValueError(f"downloaded empty file from {response}")
        os.replace(temporary_name, destination)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def _validate_jsonl_gzip(path):
    try:
        with gzip.open(path, "rt") as metadata_file:
            first_line = metadata_file.readline()
        if not first_line or not isinstance(json.loads(first_line), dict):
            raise ValueError("bulk file contains no card objects")
    except (gzip.BadGzipFile, json.JSONDecodeError, OSError) as error:
        raise ValueError(f"invalid Scryfall bulk file: {path}") from error


def sync_bulk_metadata(output_dir, bulk_type, session=None, force=False):
    if bulk_type not in BULK_TYPES:
        raise ValueError(f"unsupported Scryfall bulk type: {bulk_type}")

    output_dir = Path(output_dir)
    metadata_dir = output_dir / "metadata"
    metadata_path = metadata_dir / f"{bulk_type}.jsonl.gz"
    descriptor_path = metadata_dir / f"{bulk_type}.bulk.json"
    client = session or requests.Session()

    api_url = f"https://api.scryfall.com/bulk-data/{bulk_type}"
    response = client.get(api_url, headers=API_HEADERS, timeout=(5, 30))
    response.raise_for_status()
    descriptor = response.json()
    if not isinstance(descriptor, dict):
        raise ValueError(f"invalid Scryfall bulk descriptor for {bulk_type}")
    download_url = descriptor.get("jsonl_download_uri")
    if descriptor.get("type") != bulk_type or not download_url:
        raise ValueError(f"invalid Scryfall bulk descriptor for {bulk_type}")

    current_descriptor = None
    if descriptor_path.exists():
        try:
            current_descriptor = json.loads(descriptor_path.read_text())
        except json.JSONDecodeError:
            current_descriptor = None
    is_current = (
        not force
        and metadata_path.exists()
        and metadata_path.stat().st_size > 0
        and current_descriptor
        and current_descriptor.get("updated_at") == descriptor.get("updated_at")
    )
    if is_current:
        try:
            _validate_jsonl_gzip(metadata_path)
            return metadata_path
        except ValueError:
            pass

    download_response = client.get(
        download_url,
        headers={"Accept": "application/gzip", "User-Agent": USER_AGENT},
        stream=True,
        timeout=(10, 300),
    )
    download_response.raise_for_status()
    _download_to_path(download_response, metadata_path)
    _validate_jsonl_gzip(metadata_path)
    _write_json(descriptor_path, descriptor)
    return metadata_path


def load_label_card_ids(path):
    with Path(path).open(newline="") as labels_file:
        rows = csv.DictReader(labels_file)
        card_ids = []
        seen = set()
        for row_number, row in enumerate(rows, start=2):
            card_id = row.get("Scryfall ID", "").strip()
            if not card_id:
                raise ValueError(f"missing Scryfall ID at label row {row_number}")
            if card_id not in seen:
                seen.add(card_id)
                card_ids.append(card_id)
    if not card_ids:
        raise ValueError("labels CSV contains no Scryfall IDs")
    return card_ids


def _open_metadata(path):
    path = Path(path)
    if path.suffix == ".gz":
        return gzip.open(path, "rt")
    return path.open()


def _image_extension(uri):
    extension = Path(urlparse(uri).path).suffix.lower()
    if extension in {".jpg", ".jpeg", ".png", ".webp"}:
        return extension
    return ".jpg"


def _card_image_jobs(card, image_version):
    card_id = str(card.get("id", "")).strip()
    if not card_id:
        return []

    image_uris = card.get("image_uris")
    if isinstance(image_uris, dict) and image_uris.get(image_version):
        uri = image_uris[image_version]
        if not isinstance(uri, str):
            return []
        return [
            {
                "scryfall_id": card_id,
                "face": "front",
                "uri": uri,
                "filename": f"{card_id}{_image_extension(uri)}",
            }
        ]

    jobs = []
    for index, face in enumerate(card.get("card_faces") or []):
        face_image_uris = face.get("image_uris") or {}
        uri = face_image_uris.get(image_version)
        if not isinstance(uri, str) or not uri:
            continue
        face_name = (
            "front" if index == 0 else "back" if index == 1 else f"face-{index + 1}"
        )
        jobs.append(
            {
                "scryfall_id": card_id,
                "face": face_name,
                "uri": uri,
                "filename": f"{card_id}-{face_name}{_image_extension(uri)}",
            }
        )
    return jobs


def _download_artwork_job(job, images_dir, get):
    destination = images_dir / job["filename"]
    if destination.exists() and destination.stat().st_size > 0:
        return job, "cached", None
    try:
        response = get(
            job["uri"],
            headers=IMAGE_HEADERS,
            stream=True,
            timeout=(10, 120),
        )
        response.raise_for_status()
        _download_to_path(response, destination)
        return job, "downloaded", None
    except (OSError, requests.RequestException, ValueError) as error:
        return job, "failed", str(error)


def sync_artwork(
    metadata_path,
    output_dir,
    image_version,
    card_ids=None,
    limit=None,
    workers=8,
    session=None,
):
    if image_version not in IMAGE_VERSIONS:
        raise ValueError(f"unsupported Scryfall image version: {image_version}")
    if limit is not None and limit < 1:
        raise ValueError("limit must be positive")
    if workers < 1:
        raise ValueError("workers must be positive")

    requested_card_ids = set(card_ids) if card_ids else None
    matched_card_ids = set()
    jobs = []
    cards_considered = 0
    cards_without_images = 0
    with _open_metadata(metadata_path) as metadata_file:
        for line_number, line in enumerate(metadata_file, start=1):
            if not line.strip():
                continue
            try:
                card = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"invalid Scryfall metadata JSON at line {line_number}"
                ) from error
            card_id = str(card.get("id", "")).strip()
            if requested_card_ids is not None and card_id not in requested_card_ids:
                continue
            if limit is not None and cards_considered >= limit:
                break

            cards_considered += 1
            matched_card_ids.add(card_id)
            card_jobs = _card_image_jobs(card, image_version)
            if not card_jobs:
                cards_without_images += 1
            jobs.extend(card_jobs)

    artwork_dir = Path(output_dir) / "artwork" / image_version
    images_dir = artwork_dir / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    thread_state = threading.local()

    def get(url, **kwargs):
        if session:
            return session.get(url, **kwargs)
        if not hasattr(thread_state, "session"):
            thread_state.session = requests.Session()
        return thread_state.session.get(url, **kwargs)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        outcomes = list(
            executor.map(
                lambda job: _download_artwork_job(job, images_dir, get),
                jobs,
            )
        )

    index = []
    errors = []
    downloaded = 0
    cached = 0
    for job, status, error in outcomes:
        if status == "failed":
            errors.append(
                {
                    "scryfall_id": job["scryfall_id"],
                    "face": job["face"],
                    "error": error,
                }
            )
            continue
        if status == "downloaded":
            downloaded += 1
        else:
            cached += 1
        index.append(
            {
                "scryfall_id": job["scryfall_id"],
                "face": job["face"],
                "path": f"images/{job['filename']}",
            }
        )

    index_path = artwork_dir / "index.jsonl"
    temporary_index = index_path.with_suffix(".jsonl.tmp")
    temporary_index.write_text(
        "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in index)
    )
    os.replace(temporary_index, index_path)

    return {
        "cards_considered": cards_considered,
        "images_available": len(jobs),
        "downloaded": downloaded,
        "cached": cached,
        "failed": len(errors),
        "cards_without_images": cards_without_images,
        "requested_cards_missing": (
            sorted(requested_card_ids - matched_card_ids)
            if requested_card_ids is not None
            else []
        ),
        "index": str(index_path),
        "errors": errors,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Cache Scryfall bulk metadata and reference artwork locally."
    )
    parser.add_argument(
        "output_dir",
        type=Path,
        nargs="?",
        default=Path("tmp/tcg-scan/scryfall"),
    )
    parser.add_argument("--bulk-type", choices=BULK_TYPES, default="default_cards")
    parser.add_argument("--download-artwork", action="store_true")
    parser.add_argument("--image-version", choices=IMAGE_VERSIONS, default="normal")
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--force-metadata", action="store_true")
    args = parser.parse_args(argv)

    output_dir = _resolve_user_path(args.output_dir)
    labels_path = _resolve_user_path(args.labels) if args.labels else None
    metadata_path = sync_bulk_metadata(
        output_dir, args.bulk_type, force=args.force_metadata
    )
    print(f"Metadata: {metadata_path}")

    if not args.download_artwork:
        return 0

    card_ids = load_label_card_ids(labels_path) if labels_path else None
    result = sync_artwork(
        metadata_path,
        output_dir,
        args.image_version,
        card_ids=card_ids,
        limit=args.limit,
        workers=args.workers,
    )
    print(
        f"Artwork: {result['downloaded']} downloaded, {result['cached']} cached, "
        f"{result['failed']} failed ({result['images_available']} available)"
    )
    if result["requested_cards_missing"]:
        print(
            "Missing requested cards: " + ", ".join(result["requested_cards_missing"])
        )
    return 1 if result["failed"] or result["requested_cards_missing"] else 0
