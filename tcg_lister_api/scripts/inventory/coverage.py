import argparse
import csv
import gzip
import json
import os
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import requests

from fetchtcg_client import FetchTcgClient, FetchTcgError


USER_AGENT = "jordansimsmith-com-tcg-inventory/0.1"
API_HEADERS = {"Accept": "application/json", "User-Agent": USER_AGENT}
BULK_TYPE = "default_cards"
BULK_DESCRIPTOR_URL = f"https://api.scryfall.com/bulk-data/{BULK_TYPE}"
REQUEST_TIMEOUT = (5, 30)
BULK_DOWNLOAD_TIMEOUT = (10, 300)
REPORT_SCHEMA_VERSION = 1
COVERAGE_CUTOFFS = (100, 1000, 2000, 5000, 20000)
EXCLUDED_LAYOUTS = frozenset(
    {
        "token",
        "double_faced_token",
        "emblem",
        "art_series",
        "augment",
        "host",
        "planar",
        "scheme",
        "vanguard",
    }
)
EXCLUDED_SET_TYPES = frozenset({"token", "memorabilia"})


@dataclass(frozen=True)
class OracleCard:
    oracle_id: str
    name: str
    rank: int | None


@dataclass(frozen=True)
class Catalog:
    printing_to_oracle: dict[str, str]
    oracle_names: dict[str, str]
    oracles: dict[str, OracleCard]
    bulk_record_count: int
    population_printing_count: int


@dataclass(frozen=True)
class InventoryAnalysis:
    inventory: dict
    coverage: tuple[dict, ...]
    inventory_cards: tuple[dict, ...]
    missing_cards: tuple[dict, ...]
    unmatched_scryfall_ids: tuple[str, ...]


def resolve_workspace_base():
    workspace = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    return Path(workspace) if workspace else Path.cwd()


def iter_bulk_cards(path):
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else Path.open
    with opener(path, "rt") as bulk_file:
        for line_number, line in enumerate(bulk_file, start=1):
            if not line.strip():
                continue
            try:
                card = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid bulk JSON at line {line_number}") from error
            if not isinstance(card, dict):
                raise ValueError(f"invalid bulk record at line {line_number}")
            yield card


def _oracle_id(card):
    oracle_id = card.get("oracle_id")
    if not oracle_id:
        faces = card.get("card_faces") or []
        if faces and isinstance(faces[0], dict):
            oracle_id = faces[0].get("oracle_id")
    if not isinstance(oracle_id, str) or not oracle_id.strip():
        return None
    return oracle_id.strip().casefold()


def _valid_rank(value):
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        return None
    return value


def _is_population_printing(card):
    return (
        card.get("lang") == "en"
        and card.get("digital") is not True
        and "paper" in (card.get("games") or [])
        and card.get("layout") not in EXCLUDED_LAYOUTS
        and card.get("set_type") not in EXCLUDED_SET_TYPES
        and card.get("oversized") is not True
        and (card.get("legalities") or {}).get("commander") == "legal"
        and not (card.get("type_line") or "").startswith("Basic Land")
    )


def build_catalog(cards):
    printing_to_oracle = {}
    oracle_names = {}
    oracles = {}
    bulk_record_count = 0
    population_printing_count = 0

    for card in cards:
        if not isinstance(card, dict):
            raise ValueError("bulk record was not an object")
        bulk_record_count += 1
        oracle_id = _oracle_id(card)
        name_value = card.get("name")
        name = name_value.strip() if isinstance(name_value, str) else ""
        printing_id_value = card.get("id")
        printing_id = (
            printing_id_value.strip().casefold()
            if isinstance(printing_id_value, str) and printing_id_value.strip()
            else None
        )

        if oracle_id is not None:
            if name:
                oracle_names.setdefault(oracle_id, name)
            if printing_id is not None:
                existing_oracle_id = printing_to_oracle.get(printing_id)
                if existing_oracle_id is not None and existing_oracle_id != oracle_id:
                    raise ValueError(
                        f"Scryfall printing {printing_id} mapped to multiple oracle IDs"
                    )
                printing_to_oracle[printing_id] = oracle_id

        if oracle_id is None or not name or not _is_population_printing(card):
            continue

        population_printing_count += 1
        rank = _valid_rank(card.get("edhrec_rank"))
        existing = oracles.get(oracle_id)
        if existing is None:
            oracles[oracle_id] = OracleCard(
                oracle_id=oracle_id,
                name=name,
                rank=rank,
            )
            oracle_names[oracle_id] = name
        elif rank is not None and (existing.rank is None or rank < existing.rank):
            oracles[oracle_id] = OracleCard(
                oracle_id=oracle_id,
                name=existing.name,
                rank=rank,
            )

    return Catalog(
        printing_to_oracle=printing_to_oracle,
        oracle_names=oracle_names,
        oracles=oracles,
        bulk_record_count=bulk_record_count,
        population_printing_count=population_printing_count,
    )


def _card_sort_key(card):
    rank = card["rank"]
    return (
        rank is None,
        rank if rank is not None else 0,
        card["name"].casefold(),
        card["oracle_id"],
    )


def analyze_inventory(listings, catalog):
    oracle_rollups = {}
    unmatched_ids = set()
    unmatched_listing_count = 0
    unmatched_copy_count = 0
    unique_printing_ids = set()
    physical_copy_count = 0

    for listing in listings:
        scryfall_id = listing.scryfall_id.casefold()
        unique_printing_ids.add(scryfall_id)
        physical_copy_count += listing.remaining_quantity
        oracle_id = catalog.printing_to_oracle.get(scryfall_id)
        if oracle_id is None:
            unmatched_ids.add(scryfall_id)
            unmatched_listing_count += 1
            unmatched_copy_count += listing.remaining_quantity
            continue
        rollup = oracle_rollups.setdefault(
            oracle_id,
            {
                "listing_count": 0,
                "physical_copy_count": 0,
                "printing_ids": set(),
            },
        )
        rollup["listing_count"] += 1
        rollup["physical_copy_count"] += listing.remaining_quantity
        rollup["printing_ids"].add(scryfall_id)

    inventory_cards = []
    ranked_inventory_ids = set()
    unranked_count = 0
    outside_population_count = 0
    for oracle_id, rollup in oracle_rollups.items():
        oracle = catalog.oracles.get(oracle_id)
        if oracle is None:
            population_status = "outside_population"
            rank = None
            outside_population_count += 1
            name = catalog.oracle_names.get(oracle_id, oracle_id)
        elif oracle.rank is None:
            population_status = "unranked"
            rank = None
            unranked_count += 1
            name = oracle.name
        else:
            population_status = "ranked"
            rank = oracle.rank
            ranked_inventory_ids.add(oracle_id)
            name = oracle.name
        inventory_cards.append(
            {
                "oracle_id": oracle_id,
                "name": name,
                "rank": rank,
                "population_status": population_status,
                "unique_printing_count": len(rollup["printing_ids"]),
                "listing_count": rollup["listing_count"],
                "physical_copy_count": rollup["physical_copy_count"],
            }
        )
    inventory_cards.sort(key=_card_sort_key)

    ranked_oracles = sorted(
        (oracle for oracle in catalog.oracles.values() if oracle.rank is not None),
        key=lambda oracle: (oracle.rank, oracle.name.casefold(), oracle.oracle_id),
    )
    coverage = []
    for cutoff in COVERAGE_CUTOFFS:
        bracket = [oracle for oracle in ranked_oracles if oracle.rank <= cutoff]
        covered = sum(
            1 for oracle in bracket if oracle.oracle_id in ranked_inventory_ids
        )
        denominator = len(bracket)
        coverage.append(
            {
                "cutoff": cutoff,
                "ranked_card_count": denominator,
                "inventory_card_count": covered,
                "missing_card_count": denominator - covered,
                "coverage_share": covered / denominator if denominator else None,
            }
        )

    maximum_cutoff = max(COVERAGE_CUTOFFS)
    missing_cards = tuple(
        {
            "oracle_id": oracle.oracle_id,
            "name": oracle.name,
            "rank": oracle.rank,
        }
        for oracle in ranked_oracles
        if oracle.rank <= maximum_cutoff
        and oracle.oracle_id not in ranked_inventory_ids
    )
    inventory = {
        "managed_listing_count": len(listings),
        "physical_copy_count": physical_copy_count,
        "unique_printing_count": len(unique_printing_ids),
        "resolved_unique_oracle_count": len(oracle_rollups),
        "ranked_unique_oracle_count": len(ranked_inventory_ids),
        "unranked_unique_oracle_count": unranked_count,
        "out_of_population_unique_oracle_count": outside_population_count,
        "unmatched_listing_count": unmatched_listing_count,
        "unmatched_copy_count": unmatched_copy_count,
        "unmatched_unique_printing_count": len(unmatched_ids),
    }
    return InventoryAnalysis(
        inventory=inventory,
        coverage=tuple(coverage),
        inventory_cards=tuple(inventory_cards),
        missing_cards=missing_cards,
        unmatched_scryfall_ids=tuple(sorted(unmatched_ids)),
    )


def _share(value):
    return None if value is None else f"{value:.4f}"


def build_report(
    *,
    generated_at,
    bulk_path,
    catalog,
    analysis,
    request_count,
):
    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "generated_at": generated_at.isoformat(),
        "bulk_file": str(bulk_path),
        "fetch_request_count": request_count,
        "catalog": {
            "bulk_record_count": catalog.bulk_record_count,
            "population_printing_count": catalog.population_printing_count,
            "population_oracle_count": len(catalog.oracles),
            "ranked_oracle_count": sum(
                1 for oracle in catalog.oracles.values() if oracle.rank is not None
            ),
        },
        "inventory": analysis.inventory,
        "coverage": [
            {**row, "coverage_share": _share(row["coverage_share"])}
            for row in analysis.coverage
        ],
        "inventory_cards": list(analysis.inventory_cards),
        "missing_cards": list(analysis.missing_cards),
        "unmatched_scryfall_ids": list(analysis.unmatched_scryfall_ids),
    }


def _write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        with os.fdopen(descriptor, "w") as temporary_file:
            json.dump(value, temporary_file, indent=2)
            temporary_file.write("\n")
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def _write_csv_atomic(path, fieldnames, rows):
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", newline="") as temporary_file:
            writer = csv.DictWriter(temporary_file, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def write_reports(run_dir, report):
    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json_atomic(run_dir / "report.json", report)
    _write_csv_atomic(
        run_dir / "coverage.csv",
        (
            "cutoff",
            "ranked_card_count",
            "inventory_card_count",
            "missing_card_count",
            "coverage_share",
        ),
        report["coverage"],
    )
    _write_csv_atomic(
        run_dir / "inventory_cards.csv",
        (
            "oracle_id",
            "name",
            "rank",
            "population_status",
            "unique_printing_count",
            "listing_count",
            "physical_copy_count",
        ),
        report["inventory_cards"],
    )
    _write_csv_atomic(
        run_dir / "missing_cards.csv",
        ("oracle_id", "name", "rank"),
        report["missing_cards"],
    )
    return run_dir


def format_summary(report):
    inventory = report["inventory"]
    lines = [
        "EDH inventory coverage",
        (
            f"Inventory: {inventory['managed_listing_count']} active listings, "
            f"{inventory['physical_copy_count']} copies, "
            f"{inventory['resolved_unique_oracle_count']} resolved unique cards"
        ),
        "Bracket   Ranked  Owned  Missing  Coverage",
    ]
    for row in report["coverage"]:
        share = (
            "n/a"
            if row["coverage_share"] is None
            else f"{100 * float(row['coverage_share']):.1f}%"
        )
        lines.append(
            f"Top {row['cutoff']:<5} "
            f"{row['ranked_card_count']:>6}  "
            f"{row['inventory_card_count']:>5}  "
            f"{row['missing_card_count']:>7}  "
            f"{share:>8}"
        )
    lines.append(
        "Other inventory: "
        f"{inventory['unranked_unique_oracle_count']} unranked, "
        f"{inventory['out_of_population_unique_oracle_count']} outside population, "
        f"{inventory['unmatched_listing_count']} unmatched listings"
    )
    return "\n".join(lines)


def _download_to_path(response, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=destination.name + ".",
        suffix=".part",
        dir=destination.parent,
    )
    try:
        with os.fdopen(descriptor, "wb") as temporary_file:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    temporary_file.write(chunk)
        if Path(temporary_name).stat().st_size == 0:
            raise ValueError("downloaded empty bulk file")
        os.replace(temporary_name, destination)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def _validate_bulk_file(path):
    try:
        with gzip.open(path, "rt") as bulk_file:
            first_line = bulk_file.readline()
        if not first_line or not isinstance(json.loads(first_line), dict):
            raise ValueError("bulk file contains no card objects")
    except (gzip.BadGzipFile, json.JSONDecodeError, OSError) as error:
        raise ValueError(f"invalid Scryfall bulk file: {path}") from error


def sync_bulk_metadata(cache_dir, session=None, log=None):
    cache_dir = Path(cache_dir)
    bulk_path = cache_dir / f"{BULK_TYPE}.jsonl.gz"
    descriptor_path = cache_dir / f"{BULK_TYPE}.bulk.json"
    client = session or requests.Session()

    response = client.get(
        BULK_DESCRIPTOR_URL,
        headers=API_HEADERS,
        timeout=REQUEST_TIMEOUT,
    )
    response.raise_for_status()
    descriptor = response.json()
    if not isinstance(descriptor, dict) or descriptor.get("type") != BULK_TYPE:
        raise ValueError("invalid Scryfall bulk descriptor")
    download_url = descriptor.get("jsonl_download_uri")
    if not download_url:
        raise ValueError("invalid Scryfall bulk descriptor")

    current_descriptor = None
    if descriptor_path.exists():
        try:
            current_descriptor = json.loads(descriptor_path.read_text())
        except json.JSONDecodeError:
            current_descriptor = None
    is_current = (
        bulk_path.exists()
        and bulk_path.stat().st_size > 0
        and current_descriptor
        and current_descriptor.get("updated_at") == descriptor.get("updated_at")
    )
    if is_current:
        try:
            _validate_bulk_file(bulk_path)
            if log:
                log(f"bulk cache is current: {bulk_path}")
            return bulk_path
        except ValueError:
            pass

    if log:
        log(f"downloading {BULK_TYPE} bulk data")
    download_response = client.get(
        download_url,
        headers={"Accept": "application/gzip", "User-Agent": USER_AGENT},
        stream=True,
        timeout=BULK_DOWNLOAD_TIMEOUT,
    )
    download_response.raise_for_status()
    _download_to_path(download_response, bulk_path)
    _validate_bulk_file(bulk_path)
    _write_json_atomic(descriptor_path, descriptor)
    return bulk_path


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Measure active Fetch inventory coverage of ranked EDH cards."
    )
    parser.add_argument(
        "--bulk-file",
        type=Path,
        help="existing Scryfall default_cards JSONL file (.jsonl or .jsonl.gz)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print safe request, cache, and classification diagnostics",
    )
    return parser.parse_args(argv)


def run(
    argv=None,
    *,
    fetch_client=None,
    scryfall_session=None,
    now=None,
):
    args = parse_args(argv)

    def log(message):
        if args.verbose:
            print(f"[inventory] {message}")

    base = resolve_workspace_base()
    client = fetch_client or FetchTcgClient(
        token=os.environ.get("FETCHTCG_TOKEN"),
        verbose=args.verbose,
    )
    listings = client.get_managed_listings()
    log(f"loaded {len(listings)} active managed listings")

    if args.bulk_file:
        bulk_path = args.bulk_file
        if not bulk_path.is_absolute():
            bulk_path = base / bulk_path
        if not bulk_path.exists():
            raise ValueError(f"bulk file does not exist: {bulk_path}")
    else:
        bulk_path = sync_bulk_metadata(
            base / "tmp" / "tcg-lister" / "inventory-scryfall",
            session=scryfall_session,
            log=log,
        )

    catalog = build_catalog(iter_bulk_cards(bulk_path))
    log(
        f"classified {catalog.bulk_record_count} bulk records into "
        f"{len(catalog.oracles)} oracle cards"
    )
    analysis = analyze_inventory(listings, catalog)
    generated_at = now or datetime.now(timezone.utc)
    report = build_report(
        generated_at=generated_at,
        bulk_path=bulk_path,
        catalog=catalog,
        analysis=analysis,
        request_count=client.request_count,
    )
    run_dir = (
        base
        / "tmp"
        / "tcg-lister"
        / f"inventory-{generated_at.strftime('%Y%m%dT%H%M%SZ')}"
    )
    write_reports(run_dir, report)
    print(format_summary(report))
    print(f"Reports: {run_dir}")
    return 0


def main(argv=None):
    try:
        return run(argv)
    except (FetchTcgError, OSError, ValueError, requests.RequestException) as error:
        print(f"inventory coverage failed: {error}", file=sys.stderr)
        return 1
