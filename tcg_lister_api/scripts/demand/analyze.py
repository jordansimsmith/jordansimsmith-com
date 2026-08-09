import argparse
import csv
import gzip
import json
import math
import os
import statistics
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

import requests


USER_AGENT = "jordansimsmith-com-tcg-demand/0.1"
API_HEADERS = {"Accept": "application/json", "User-Agent": USER_AGENT}
BULK_TYPE = "default_cards"
BULK_DESCRIPTOR_URL = f"https://api.scryfall.com/bulk-data/{BULK_TYPE}"
EDHREC_CARD_URL = "https://json.edhrec.com/pages/cards/{slug}.json"

ANCHOR_NAMES = (
    "Sol Ring",
    "Arcane Signet",
    "Command Tower",
    "Swords to Plowshares",
    "Counterspell",
    "Cultivate",
    "Rhystic Study",
    "Swiftfoot Boots",
    "Blasphemous Act",
    "Beast Within",
    "Chaos Warp",
    "Murder",
    "Divination",
    "Cancel",
    "Mind Rot",
    "Lava Axe",
)

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
PRICE_KEYS = ("usd", "usd_foil", "usd_etched")

DEMAND_TIERS = (
    ("1-100", 1, 100),
    ("101-1000", 101, 1000),
    ("1001-2000", 1001, 2000),
    ("2001-5000", 2001, 5000),
    ("5001-20000", 5001, 20000),
    ("20001+", 20001, None),
)
UNRANKED_TIER = "unranked"
IN_DEMAND_MAX_RANK = 2000
LOW_DEMAND_MIN_RANK = 20000
SWEEP_THRESHOLDS = (
    Decimal("0.10"),
    Decimal("0.25"),
    Decimal("0.50"),
    Decimal("1.00"),
)
LIST_CAP = 20
INCLUSION_FLOOR = 1e-6
ANCHOR_REQUEST_INTERVAL_SECONDS = 0.2
REQUEST_TIMEOUT = (5, 30)
BULK_DOWNLOAD_TIMEOUT = (10, 300)
REPORT_SCHEMA_VERSION = 1

EXCLUSION_REASONS = (
    "non_english",
    "digital",
    "non_paper",
    "layout",
    "set_type",
    "oversized",
    "not_commander_legal",
    "basic_land",
    "missing_oracle_id",
    "invalid_price",
)


@dataclass(frozen=True)
class PrintingRecord:
    name: str
    oracle_id: str
    edhrec_rank: int | None
    price_usd: Decimal | None
    price_source: str | None


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


def classify_printing(card):
    if card.get("lang") != "en":
        return None, "non_english"
    if card.get("digital") is True:
        return None, "digital"
    if "paper" not in (card.get("games") or []):
        return None, "non_paper"
    if card.get("layout") in EXCLUDED_LAYOUTS:
        return None, "layout"
    if card.get("set_type") in EXCLUDED_SET_TYPES:
        return None, "set_type"
    if card.get("oversized") is True:
        return None, "oversized"
    if (card.get("legalities") or {}).get("commander") != "legal":
        return None, "not_commander_legal"
    if (card.get("type_line") or "").startswith("Basic Land"):
        return None, "basic_land"

    oracle_id = card.get("oracle_id")
    if not oracle_id:
        faces = card.get("card_faces") or []
        if faces and isinstance(faces[0], dict):
            oracle_id = faces[0].get("oracle_id")
    if not oracle_id:
        return None, "missing_oracle_id"

    prices = card.get("prices") or {}
    price = None
    price_source = None
    for key in PRICE_KEYS:
        value = prices.get(key)
        if value:
            try:
                price = Decimal(str(value))
            except InvalidOperation:
                return None, "invalid_price"
            if price < 0:
                return None, "invalid_price"
            price_source = key
            break

    rank = card.get("edhrec_rank")
    if isinstance(rank, bool) or not isinstance(rank, int) or rank < 1:
        rank = None

    record = PrintingRecord(
        name=str(card.get("name", "")).strip(),
        oracle_id=str(oracle_id),
        edhrec_rank=rank,
        price_usd=price,
        price_source=price_source,
    )
    return record, None


def demand_tier(rank):
    if rank is None:
        return UNRANKED_TIER
    for tier, low, high in DEMAND_TIERS:
        if rank >= low and (high is None or rank <= high):
            return tier
    raise ValueError(f"rank {rank} matched no demand tier")


def is_in_demand(rank):
    return rank is not None and rank <= IN_DEMAND_MAX_RANK


def is_low_demand(rank):
    return rank is None or rank > LOW_DEMAND_MIN_RANK


def _write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=path.parent
    )
    try:
        with os.fdopen(descriptor, "w") as temporary_file:
            json.dump(value, temporary_file, indent=2)
            temporary_file.write("\n")
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


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
        BULK_DESCRIPTOR_URL, headers=API_HEADERS, timeout=REQUEST_TIMEOUT
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


def anchor_slug(name):
    return name.lower().replace(" ", "-")


def fetch_anchor_inclusions(session=None, sleep=time.sleep, log=None):
    client = session or requests.Session()
    inclusions = {}
    for index, name in enumerate(ANCHOR_NAMES):
        if index:
            sleep(ANCHOR_REQUEST_INTERVAL_SECONDS)
        url = EDHREC_CARD_URL.format(slug=anchor_slug(name))
        if log:
            log(f"GET {url}")
        response = client.get(url, headers=API_HEADERS, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        payload = response.json()
        card = (
            payload.get("container", {}).get("json_dict", {}).get("card", {})
            if isinstance(payload, dict)
            else {}
        )
        num_decks = card.get("num_decks")
        potential_decks = card.get("potential_decks")
        valid = (
            isinstance(num_decks, int)
            and not isinstance(num_decks, bool)
            and num_decks > 0
            and isinstance(potential_decks, int)
            and not isinstance(potential_decks, bool)
            and potential_decks > 0
        )
        if not valid:
            raise ValueError(f"invalid EDHREC response for anchor: {name}")
        inclusions[name] = (num_decks, potential_decks)
    return inclusions


def build_inclusion_curve(anchor_ranks, anchor_inclusions):
    total_decks = max(potential for _, potential in anchor_inclusions.values())
    by_rank = {}
    for name, (num_decks, _potential) in anchor_inclusions.items():
        rank = anchor_ranks.get(name)
        if rank is None:
            raise ValueError(f"anchor card missing from bulk data: {name}")
        inclusion = num_decks / total_decks
        by_rank[rank] = max(by_rank.get(rank, 0.0), inclusion)
    points = sorted(by_rank.items())
    if len(points) < 2:
        raise ValueError("not enough distinct anchor points for the curve")
    return points, total_decks


def inclusion_for_rank(rank, points):
    if rank is None:
        return 0.0
    if rank <= points[0][0]:
        return points[0][1]
    log_rank = math.log(rank)
    for (rank_a, inclusion_a), (rank_b, inclusion_b) in zip(points, points[1:]):
        if rank <= rank_b:
            weight = (log_rank - math.log(rank_a)) / (
                math.log(rank_b) - math.log(rank_a)
            )
            log_inclusion = (1 - weight) * math.log(inclusion_a) + weight * math.log(
                inclusion_b
            )
            return max(math.exp(log_inclusion), INCLUSION_FLOOR)
    (rank_a, inclusion_a), (rank_b, inclusion_b) = points[-2], points[-1]
    slope = (math.log(inclusion_b) - math.log(inclusion_a)) / (
        math.log(rank_b) - math.log(rank_a)
    )
    log_inclusion = math.log(inclusion_b) + slope * (log_rank - math.log(rank_b))
    return max(math.exp(log_inclusion), INCLUSION_FLOOR)


def _average_ranks(values):
    order = sorted(range(len(values)), key=lambda index: values[index])
    ranks = [0.0] * len(values)
    start = 0
    while start < len(order):
        end = start
        while end + 1 < len(order) and values[order[end + 1]] == values[order[start]]:
            end += 1
        average = (start + end) / 2 + 1
        for position in range(start, end + 1):
            ranks[order[position]] = average
        start = end + 1
    return ranks


def spearman_price_demand(pairs):
    if len(pairs) < 2:
        return None
    prices = [float(price) for price, _rank in pairs]
    demand = [-rank for _price, rank in pairs]
    try:
        return statistics.correlation(_average_ranks(prices), _average_ranks(demand))
    except statistics.StatisticsError:
        return None


def aggregate_oracles(records):
    oracles = {}
    for record in records:
        entry = oracles.setdefault(
            record.oracle_id,
            {"name": record.name, "rank": None, "prices": [], "unpriced": 0},
        )
        if record.edhrec_rank is not None and (
            entry["rank"] is None or record.edhrec_rank < entry["rank"]
        ):
            entry["rank"] = record.edhrec_rank
        if record.price_usd is None:
            entry["unpriced"] += 1
        else:
            entry["prices"].append(record.price_usd)
    return oracles


def _passing_count(prices, threshold):
    return sum(1 for price in prices if price >= threshold)


def analyze_population(oracles, curve_points, headline_threshold):
    thresholds = sorted(set(SWEEP_THRESHOLDS) | {headline_threshold})

    tier_rows = []
    for tier in [name for name, _low, _high in DEMAND_TIERS] + [UNRANKED_TIER]:
        entries = [
            entry for entry in oracles.values() if demand_tier(entry["rank"]) == tier
        ]
        priced_entries = [entry for entry in entries if entry["prices"]]
        printings = sum(len(entry["prices"]) for entry in priced_entries)
        passing = sum(
            _passing_count(entry["prices"], headline_threshold)
            for entry in priced_entries
        )
        cheapest_passing = sum(
            1 for entry in priced_entries if min(entry["prices"]) >= headline_threshold
        )
        tier_rows.append(
            {
                "tier": tier,
                "oracle_cards": len(entries),
                "priced_printings": printings,
                "passing_printings": passing,
                "oracle_cards_priced": len(priced_entries),
                "oracle_cheapest_passing": cheapest_passing,
            }
        )

    pairs = [
        (price, entry["rank"])
        for entry in oracles.values()
        if entry["rank"] is not None
        for price in entry["prices"]
    ]
    correlation = spearman_price_demand(pairs)

    sweep = []
    for threshold in thresholds:
        priced_printings = 0
        passing_printings = 0
        in_demand_printings = 0
        in_demand_passing = 0
        low_demand_passing = 0
        total_mass = 0.0
        captured_mass = 0.0
        for entry in oracles.values():
            prices = entry["prices"]
            if not prices:
                continue
            passing = _passing_count(prices, threshold)
            priced_printings += len(prices)
            passing_printings += passing
            if is_in_demand(entry["rank"]):
                in_demand_printings += len(prices)
                in_demand_passing += passing
            if is_low_demand(entry["rank"]):
                low_demand_passing += passing
            if entry["rank"] is not None:
                inclusion = inclusion_for_rank(entry["rank"], curve_points)
                total_mass += inclusion
                captured_mass += inclusion * passing / len(prices)
        sweep.append(
            {
                "threshold_usd": threshold,
                "priced_printings": priced_printings,
                "passing_printings": passing_printings,
                "pass_share": passing_printings / priced_printings
                if priced_printings
                else None,
                "in_demand_recall_share": in_demand_passing / in_demand_printings
                if in_demand_printings
                else None,
                "low_demand_keep_share": low_demand_passing / passing_printings
                if passing_printings
                else None,
                "demand_capture_share": captured_mass / total_mass
                if total_mass
                else None,
            }
        )

    headline = next(row for row in sweep if row["threshold_usd"] == headline_threshold)

    binned_staples = sorted(
        (
            {
                "name": entry["name"],
                "rank": entry["rank"],
                "min_price_usd": min(entry["prices"]),
                "max_price_usd": max(entry["prices"]),
                "priced_printings": len(entry["prices"]),
            }
            for entry in oracles.values()
            if entry["prices"]
            and is_in_demand(entry["rank"])
            and _passing_count(entry["prices"], headline_threshold) == 0
        ),
        key=lambda item: item["rank"],
    )
    expensive_low_demand = sorted(
        (
            {"name": entry["name"], "rank": entry["rank"], "price_usd": price}
            for entry in oracles.values()
            if entry["prices"] and is_low_demand(entry["rank"])
            for price in entry["prices"]
            if price >= headline_threshold
        ),
        key=lambda item: item["price_usd"],
        reverse=True,
    )

    ranked_unpriced = sum(
        1
        for entry in oracles.values()
        if entry["rank"] is not None and not entry["prices"]
    )
    return {
        "tiers": tier_rows,
        "correlation_pairs": len(pairs),
        "spearman_price_vs_demand": correlation,
        "headline": headline,
        "fully_binned_staples_count": len(binned_staples),
        "binned_staples": binned_staples[:LIST_CAP],
        "expensive_low_demand": expensive_low_demand[:LIST_CAP],
        "sweep": sweep,
        "ranked_oracles_unpriced": ranked_unpriced,
    }


def _money(value):
    return None if value is None else f"{value:.2f}"


def _share(value):
    return None if value is None else f"{value:.4f}"


def build_report(
    *,
    generated_at,
    bulk_path,
    threshold,
    population_counts,
    metrics,
    anchor_inclusions,
    total_decks,
):
    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "generated_at": generated_at,
        "bulk_file": str(bulk_path),
        "threshold_usd": _money(threshold),
        "population": population_counts,
        "correlation": {
            "spearman_price_vs_demand": _share(metrics["spearman_price_vs_demand"]),
            "pairs": metrics["correlation_pairs"],
        },
        "tiers": [
            {
                **row,
                "printing_pass_share": _share(
                    row["passing_printings"] / row["priced_printings"]
                    if row["priced_printings"]
                    else None
                ),
                "oracle_cheapest_pass_share": _share(
                    row["oracle_cheapest_passing"] / row["oracle_cards_priced"]
                    if row["oracle_cards_priced"]
                    else None
                ),
            }
            for row in metrics["tiers"]
        ],
        "headline": {
            "threshold_usd": _money(threshold),
            "pass_share": _share(metrics["headline"]["pass_share"]),
            "in_demand_recall_share": _share(
                metrics["headline"]["in_demand_recall_share"]
            ),
            "low_demand_keep_share": _share(
                metrics["headline"]["low_demand_keep_share"]
            ),
            "demand_capture_share": _share(metrics["headline"]["demand_capture_share"]),
            "fully_binned_staples_count": metrics["fully_binned_staples_count"],
            "ranked_oracles_unpriced": metrics["ranked_oracles_unpriced"],
        },
        "threshold_sweep": [
            {
                "threshold_usd": _money(row["threshold_usd"]),
                "priced_printings": row["priced_printings"],
                "passing_printings": row["passing_printings"],
                "pass_share": _share(row["pass_share"]),
                "in_demand_recall_share": _share(row["in_demand_recall_share"]),
                "low_demand_keep_share": _share(row["low_demand_keep_share"]),
                "demand_capture_share": _share(row["demand_capture_share"]),
            }
            for row in metrics["sweep"]
        ],
        "binned_staples": [
            {
                "name": item["name"],
                "rank": item["rank"],
                "min_price_usd": _money(item["min_price_usd"]),
                "max_price_usd": _money(item["max_price_usd"]),
                "priced_printings": item["priced_printings"],
            }
            for item in metrics["binned_staples"]
        ],
        "expensive_low_demand": [
            {
                "name": item["name"],
                "rank": item["rank"],
                "price_usd": _money(item["price_usd"]),
            }
            for item in metrics["expensive_low_demand"]
        ],
        "anchors": {
            "total_decks": total_decks,
            "cards": [
                {
                    "name": name,
                    "num_decks": num_decks,
                    "potential_decks": potential_decks,
                }
                for name, (num_decks, potential_decks) in anchor_inclusions.items()
            ],
        },
    }


def write_reports(run_dir, report):
    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json_atomic(run_dir / "report.json", report)

    tiers_path = run_dir / "tiers.csv"
    descriptor, temporary_name = tempfile.mkstemp(
        prefix="tiers.csv.", suffix=".tmp", dir=run_dir
    )
    try:
        with os.fdopen(descriptor, "w", newline="") as temporary_file:
            writer = csv.writer(temporary_file)
            writer.writerow(
                [
                    "tier",
                    "oracle_cards",
                    "oracle_cards_priced",
                    "priced_printings",
                    "passing_printings",
                    "printing_pass_share",
                    "oracle_cheapest_passing",
                    "oracle_cheapest_pass_share",
                ]
            )
            for row in report["tiers"]:
                writer.writerow(
                    [
                        row["tier"],
                        row["oracle_cards"],
                        row["oracle_cards_priced"],
                        row["priced_printings"],
                        row["passing_printings"],
                        row["printing_pass_share"],
                        row["oracle_cheapest_passing"],
                        row["oracle_cheapest_pass_share"],
                    ]
                )
        os.replace(temporary_name, tiers_path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)
    return run_dir


def format_summary(report):
    headline = report["headline"]
    population = report["population"]
    lines = [
        f"EDH demand proxy gauge (keep threshold US${report['threshold_usd']})",
        (
            f"Population: {population['priced_printings']} priced printings across "
            f"{population['oracle_cards']} oracle cards "
            f"({population['bulk_records']} bulk records, "
            f"{population['excluded_total']} excluded, "
            f"{population['unpriced_printings']} unpriced)"
        ),
        (
            "Price-demand correlation (Spearman): "
            f"{report['correlation']['spearman_price_vs_demand']} "
            f"over {report['correlation']['pairs']} printings"
        ),
        (
            f"Demand capture: {_percent(headline['demand_capture_share'])} of EDH "
            f"demand mass passes; {_percent_complement(headline['demand_capture_share'])} binned"
        ),
        (
            f"In-demand recall (rank <= {IN_DEMAND_MAX_RANK}): "
            f"{_percent(headline['in_demand_recall_share'])} of printings; "
            f"{headline['fully_binned_staples_count']} staples fully binned"
        ),
        (
            "Low-demand share of kept printings: "
            f"{_percent(headline['low_demand_keep_share'])}"
        ),
        "Threshold sweep:",
    ]
    for row in report["threshold_sweep"]:
        lines.append(
            f"  US${row['threshold_usd']}: "
            f"capture {_percent(row['demand_capture_share'])}, "
            f"in-demand recall {_percent(row['in_demand_recall_share'])}, "
            f"low-demand keep {_percent(row['low_demand_keep_share'])}, "
            f"pass rate {_percent(row['pass_share'])}"
        )
    if report["binned_staples"]:
        lines.append("Top binned staples:")
        for item in report["binned_staples"][:10]:
            lines.append(
                f"  {item['name']} (rank {item['rank']}) "
                f"up to US${item['max_price_usd']}"
            )
    return "\n".join(lines)


def _percent(share):
    return "n/a" if share is None else f"{100 * float(share):.1f}%"


def _percent_complement(share):
    return "n/a" if share is None else f"{100 * (1 - float(share)):.1f}%"


def _positive_decimal(value):
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise argparse.ArgumentTypeError(f"invalid decimal: {value}") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("threshold must be positive")
    return parsed


def _positive_int(value):
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("limit must be positive")
    return parsed


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=(
            "Gauge how well the US$0.25 TCGplayer keep filter tracks EDH demand."
        )
    )
    parser.add_argument(
        "--bulk-file",
        type=Path,
        help="existing Scryfall default_cards JSONL file (.jsonl or .jsonl.gz)",
    )
    parser.add_argument(
        "--threshold-usd",
        type=_positive_decimal,
        default=Decimal("0.25"),
        help="headline keep threshold in USD",
    )
    parser.add_argument(
        "--limit",
        type=_positive_int,
        help="classify only the first N bulk records (smoke checks)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print request and streaming diagnostics",
    )
    return parser.parse_args(argv)


def run(argv=None, session=None, sleep=time.sleep):
    args = parse_args(argv)

    def log(message):
        if args.verbose:
            print(f"[demand] {message}")

    base = resolve_workspace_base()
    client = session or requests.Session()

    if args.bulk_file:
        bulk_path = args.bulk_file
        if not bulk_path.is_absolute():
            bulk_path = base / bulk_path
        if not bulk_path.exists():
            raise ValueError(f"bulk file does not exist: {bulk_path}")
    else:
        bulk_path = sync_bulk_metadata(
            base / "tmp" / "tcg-lister" / "demand-scryfall",
            session=client,
            log=log,
        )

    anchor_names = set(ANCHOR_NAMES)
    anchor_ranks = {}
    records = []
    exclusions = {reason: 0 for reason in EXCLUSION_REASONS}
    bulk_records = 0
    for card in iter_bulk_cards(bulk_path):
        if args.limit is not None and bulk_records >= args.limit:
            break
        bulk_records += 1
        record, reason = classify_printing(card)
        if record is None:
            exclusions[reason] += 1
            continue
        records.append(record)
        if record.name in anchor_names and record.edhrec_rank is not None:
            existing = anchor_ranks.get(record.name)
            if existing is None or record.edhrec_rank < existing:
                anchor_ranks[record.name] = record.edhrec_rank
    log(f"classified {bulk_records} bulk records, kept {len(records)} printings")

    oracles = aggregate_oracles(records)
    anchor_inclusions = fetch_anchor_inclusions(session=client, sleep=sleep, log=log)
    curve_points, total_decks = build_inclusion_curve(anchor_ranks, anchor_inclusions)
    metrics = analyze_population(oracles, curve_points, args.threshold_usd)

    unpriced_printings = sum(entry["unpriced"] for entry in oracles.values())
    population_counts = {
        "bulk_records": bulk_records,
        "population_printings": len(records),
        "priced_printings": sum(len(entry["prices"]) for entry in oracles.values()),
        "unpriced_printings": unpriced_printings,
        "oracle_cards": len(oracles),
        "ranked_oracle_cards": sum(
            1 for entry in oracles.values() if entry["rank"] is not None
        ),
        "excluded_total": sum(exclusions.values()),
        "excluded": exclusions,
    }

    generated_at = datetime.now(timezone.utc)
    report = build_report(
        generated_at=generated_at.isoformat(),
        bulk_path=bulk_path,
        threshold=args.threshold_usd,
        population_counts=population_counts,
        metrics=metrics,
        anchor_inclusions=anchor_inclusions,
        total_decks=total_decks,
    )
    run_dir = (
        base
        / "tmp"
        / "tcg-lister"
        / f"demand-{generated_at.strftime('%Y%m%dT%H%M%SZ')}"
    )
    write_reports(run_dir, report)
    print(format_summary(report))
    print(f"Reports: {run_dir}")
    return 0


def main(argv=None, session=None, sleep=time.sleep):
    try:
        return run(argv=argv, session=session, sleep=sleep)
    except (OSError, ValueError, requests.RequestException) as error:
        print(f"demand analysis failed: {error}", file=sys.stderr)
        return 1
