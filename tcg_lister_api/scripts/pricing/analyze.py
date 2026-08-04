import argparse
import csv
import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from pathlib import Path
from statistics import median
from typing import Sequence

from fetchtcg_client import (
    FetchTcgClient,
    FetchTcgError,
    RunSafetyStop,
)


SCHEMA_VERSION = 1
MINIMUM_MATERIAL_GAP_NZD = Decimal("0.25")
MATERIAL_GAP_RATE = Decimal("0.05")
STRONG_SIGNAL_SHARE = Decimal("0.25")
LIMITED_SIGNAL_SHARE = Decimal("0.10")
ONE_NZD = Decimal("1")
CONDITION_QUALITY = {
    "raw-d": 1,
    "raw-hp": 2,
    "raw-mp": 3,
    "raw-lp": 4,
    "raw-nm": 5,
    "raw-m": 6,
}
PRICE_BANDS = (
    "under_nz_1",
    "nz_1",
    "nz_2",
    "nz_3",
    "nz_4_to_9_99",
    "nz_10_plus",
)
CSV_FIELDS = (
    "listing_id",
    "fetch_card_id",
    "scryfall_id",
    "name",
    "set_id",
    "collector_number",
    "finish",
    "condition",
    "remaining_quantity",
    "listed_price_nzd",
    "listed_value_nzd",
    "own_price_band",
    "market_price_nzd",
    "competitor_listing_count",
    "competitor_seller_count",
    "competitor_copy_count",
    "immediate_floor_nzd",
    "supported_floor_nzd",
    "cheaper_listing_count",
    "cheaper_seller_count",
    "cheaper_copy_count",
    "price_rank",
    "immediate_gap_nzd",
    "immediate_gap_percent",
    "supported_gap_nzd",
    "supported_gap_percent",
    "supported_price_ratio",
    "better_condition_lowest_price_nzd",
    "better_condition_cheaper",
    "status",
    "status_reason",
    "suggested_price_nzd",
    "suggested_price_below_nz_1",
    "potential_markdown_nzd",
    "analysis_error",
)


class PricingAnalysisError(RuntimeError):
    pass


class PricingStatus(str, Enum):
    OVERPRICED = "OVERPRICED"
    WATCH = "WATCH"
    COMPETITIVE = "COMPETITIVE"
    NO_COMPETITION = "NO_COMPETITION"
    REVIEW = "REVIEW"


class PricingSignal(str, Enum):
    STRONG = "STRONG"
    MIXED = "MIXED"
    LIMITED = "LIMITED"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"


PRICING_SIGNAL_COLORS = {
    PricingSignal.STRONG: "\033[31m",
    PricingSignal.MIXED: "\033[33m",
    PricingSignal.LIMITED: "\033[32m",
    PricingSignal.INSUFFICIENT_DATA: "\033[33m",
}
ANSI_RESET = "\033[0m"


@dataclass(frozen=True)
class PricingRecord:
    listing_id: int
    fetch_card_id: str
    scryfall_id: str
    name: str | None
    set_id: int
    collector_number: str | None
    finish: str
    condition: str
    remaining_quantity: int
    listed_price_nzd: Decimal
    listed_value_nzd: Decimal
    own_price_band: str
    market_price_nzd: Decimal | None
    competitor_listing_count: int
    competitor_seller_count: int
    competitor_copy_count: int
    immediate_floor_nzd: Decimal | None
    supported_floor_nzd: Decimal | None
    cheaper_listing_count: int
    cheaper_seller_count: int
    cheaper_copy_count: int
    price_rank: int | None
    immediate_gap_nzd: Decimal | None
    immediate_gap_percent: Decimal | None
    supported_gap_nzd: Decimal | None
    supported_gap_percent: Decimal | None
    supported_price_ratio: Decimal | None
    better_condition_lowest_price_nzd: Decimal | None
    better_condition_cheaper: bool
    status: PricingStatus
    status_reason: str
    suggested_price_nzd: Decimal | None
    suggested_price_below_nz_1: bool
    potential_markdown_nzd: Decimal
    analysis_error: str | None


@dataclass(frozen=True)
class Aggregate:
    listing_count: int
    copy_count: int
    listed_value_nzd: Decimal
    potential_markdown_nzd: Decimal


@dataclass(frozen=True)
class PortfolioSummary:
    total_listing_count: int
    total_copy_count: int
    total_listed_value_nzd: Decimal
    total_potential_markdown_nzd: Decimal
    analyzable_listing_count: int
    analyzable_copy_count: int
    analyzable_listed_value_nzd: Decimal
    median_supported_price_ratio: Decimal | None
    overpriced_listing_share: Decimal | None
    overpriced_copy_share: Decimal | None
    overpriced_value_share: Decimal | None
    pricing_signal: PricingSignal
    diagnosis: str
    statuses: dict[str, Aggregate]
    price_bands: dict[str, Aggregate]


@dataclass(frozen=True)
class AnalysisRun:
    generated_at: datetime
    managed_listing_count: int
    selected_listing_count: int
    request_count: int
    summary: PortfolioSummary
    records: tuple[PricingRecord, ...]

    def with_records(self, records):
        records = tuple(records)
        return AnalysisRun(
            generated_at=self.generated_at,
            managed_listing_count=self.managed_listing_count,
            selected_listing_count=len(records),
            request_count=self.request_count,
            summary=summarize_records(records),
            records=records,
        )


def parse_args(argv: Sequence[str] | None = None):
    parser = argparse.ArgumentParser(
        description="Analyze active Fetch TCG listing prices"
    )
    parser.add_argument(
        "--limit",
        type=_positive_int,
        help="analyze only the first N active managed listings",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print safe request and retry diagnostics",
    )
    return parser.parse_args(argv)


def analyze_listing(owned, details, competitors):
    _verify_owned_identity(owned, details)
    exact_competitors = sorted(
        (
            competitor
            for competitor in competitors
            if competitor.condition == owned.condition
        ),
        key=lambda competitor: (
            competitor.listed_price_nzd,
            competitor.listing_id,
        ),
    )
    competitor_sellers = {competitor.seller_key for competitor in exact_competitors}
    competitor_copy_count = sum(
        competitor.remaining_quantity for competitor in exact_competitors
    )
    immediate_floor = (
        exact_competitors[0].listed_price_nzd if exact_competitors else None
    )
    supported_floor = _supported_floor(exact_competitors)
    cheaper = [
        competitor
        for competitor in exact_competitors
        if competitor.listed_price_nzd < owned.listed_price_nzd
    ]
    cheaper_sellers = {competitor.seller_key for competitor in cheaper}
    immediate_gap = _gap(owned.listed_price_nzd, immediate_floor)
    supported_gap = _gap(owned.listed_price_nzd, supported_floor)
    better_condition_prices = [
        competitor.listed_price_nzd
        for competitor in competitors
        if CONDITION_QUALITY[competitor.condition] > CONDITION_QUALITY[owned.condition]
        and competitor.listed_price_nzd < owned.listed_price_nzd
    ]
    better_condition_lowest_price = (
        min(better_condition_prices) if better_condition_prices else None
    )

    suggested_price = None
    if supported_floor is not None and _is_material_gap(
        owned.listed_price_nzd, supported_floor
    ):
        status = PricingStatus.OVERPRICED
        status_reason = (
            "owned price materially exceeds a floor supported by at least "
            "two sellers or three copies"
        )
        suggested_price = supported_floor
    elif immediate_floor is not None and _is_material_gap(
        owned.listed_price_nzd, immediate_floor
    ):
        status = PricingStatus.WATCH
        if supported_floor is None:
            status_reason = (
                "owned price materially exceeds the immediate floor, but cheaper "
                "stock has not reached supported depth"
            )
        else:
            status_reason = (
                "owned price materially exceeds the immediate floor, but the gap "
                "to the supported floor is below the material threshold"
            )
    elif immediate_floor is not None:
        status = PricingStatus.COMPETITIVE
        status_reason = (
            "owned price is within the material-gap tolerance of current "
            "exact-condition competition"
        )
    else:
        status = PricingStatus.NO_COMPETITION
        status_reason = "no non-owned exact-condition New Zealand listing exists"

    listed_value = owned.listed_price_nzd * owned.remaining_quantity
    potential_markdown = (
        (owned.listed_price_nzd - suggested_price) * owned.remaining_quantity
        if suggested_price is not None
        else Decimal("0")
    )
    return PricingRecord(
        listing_id=owned.listing_id,
        fetch_card_id=owned.fetch_card_id,
        scryfall_id=owned.scryfall_id,
        name=details.name,
        set_id=owned.set_id,
        collector_number=details.collector_number,
        finish=owned.finish,
        condition=owned.condition,
        remaining_quantity=owned.remaining_quantity,
        listed_price_nzd=owned.listed_price_nzd,
        listed_value_nzd=listed_value,
        own_price_band=_price_band(owned.listed_price_nzd),
        market_price_nzd=details.market_price_nzd,
        competitor_listing_count=len(exact_competitors),
        competitor_seller_count=len(competitor_sellers),
        competitor_copy_count=competitor_copy_count,
        immediate_floor_nzd=immediate_floor,
        supported_floor_nzd=supported_floor,
        cheaper_listing_count=len(cheaper),
        cheaper_seller_count=len(cheaper_sellers),
        cheaper_copy_count=sum(competitor.remaining_quantity for competitor in cheaper),
        price_rank=1 + len(cheaper) if exact_competitors else None,
        immediate_gap_nzd=immediate_gap,
        immediate_gap_percent=_gap_percent(immediate_gap, immediate_floor),
        supported_gap_nzd=supported_gap,
        supported_gap_percent=_gap_percent(supported_gap, supported_floor),
        supported_price_ratio=_price_ratio(owned.listed_price_nzd, supported_floor),
        better_condition_lowest_price_nzd=better_condition_lowest_price,
        better_condition_cheaper=better_condition_lowest_price is not None,
        status=status,
        status_reason=status_reason,
        suggested_price_nzd=suggested_price,
        suggested_price_below_nz_1=(
            suggested_price is not None and suggested_price < ONE_NZD
        ),
        potential_markdown_nzd=potential_markdown,
        analysis_error=None,
    )


def analyze_all(client, *, limit=None):
    managed_listings = client.get_managed_listings()
    if limit is not None:
        if isinstance(limit, bool) or not isinstance(limit, int) or limit <= 0:
            raise ValueError("limit must be a positive integer")
        selected_listings = managed_listings[:limit]
    else:
        selected_listings = managed_listings
    excluded_listing_ids = {listing.listing_id for listing in managed_listings}
    evidence_cache = {}
    records = []

    for owned in selected_listings:
        if owned.fetch_card_id not in evidence_cache:
            try:
                details = client.get_card_details(owned.fetch_card_id)
                competitors = client.get_competitor_listings(
                    owned.fetch_card_id,
                    excluded_listing_ids=excluded_listing_ids,
                )
                evidence_cache[owned.fetch_card_id] = (
                    details,
                    tuple(competitors),
                    None,
                )
            except RunSafetyStop:
                raise
            except FetchTcgError as error:
                evidence_cache[owned.fetch_card_id] = (
                    None,
                    (),
                    str(error),
                )

        details, competitors, error = evidence_cache[owned.fetch_card_id]
        if error is not None:
            records.append(_review_record(owned, error))
            continue
        try:
            records.append(analyze_listing(owned, details, competitors))
        except PricingAnalysisError as analysis_error:
            records.append(_review_record(owned, str(analysis_error)))

    records = tuple(records)
    return AnalysisRun(
        generated_at=datetime.now(timezone.utc),
        managed_listing_count=len(managed_listings),
        selected_listing_count=len(selected_listings),
        request_count=client.request_count,
        summary=summarize_records(records),
        records=records,
    )


def summarize_records(records):
    records = tuple(records)
    analyzable = tuple(
        record for record in records if record.status != PricingStatus.REVIEW
    )
    overpriced = tuple(
        record for record in analyzable if record.status == PricingStatus.OVERPRICED
    )
    total = _aggregate(records)
    analyzable_total = _aggregate(analyzable)
    overpriced_total = _aggregate(overpriced)
    listing_share = _share(
        overpriced_total.listing_count, analyzable_total.listing_count
    )
    copy_share = _share(overpriced_total.copy_count, analyzable_total.copy_count)
    value_share = _share(
        overpriced_total.listed_value_nzd,
        analyzable_total.listed_value_nzd,
    )
    ratios = sorted(
        record.supported_price_ratio
        for record in analyzable
        if record.supported_price_ratio is not None
    )
    median_ratio = Decimal(str(median(ratios))) if ratios else None
    signal = _pricing_signal(listing_share, copy_share, value_share)
    return PortfolioSummary(
        total_listing_count=total.listing_count,
        total_copy_count=total.copy_count,
        total_listed_value_nzd=total.listed_value_nzd,
        total_potential_markdown_nzd=total.potential_markdown_nzd,
        analyzable_listing_count=analyzable_total.listing_count,
        analyzable_copy_count=analyzable_total.copy_count,
        analyzable_listed_value_nzd=analyzable_total.listed_value_nzd,
        median_supported_price_ratio=median_ratio,
        overpriced_listing_share=listing_share,
        overpriced_copy_share=copy_share,
        overpriced_value_share=value_share,
        pricing_signal=signal,
        diagnosis=_diagnosis(signal),
        statuses={
            status.value: _aggregate(
                record for record in records if record.status == status
            )
            for status in PricingStatus
        },
        price_bands={
            band: _aggregate(
                record for record in records if record.own_price_band == band
            )
            for band in PRICE_BANDS
        },
    )


def write_reports(run, output_dir):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "report.json"
    csv_path = output_dir / "listings.csv"
    payload = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": run.generated_at.isoformat(),
        "managed_listing_count": run.managed_listing_count,
        "selected_listing_count": run.selected_listing_count,
        "request_count": run.request_count,
        "summary": _summary_payload(run.summary),
        "listings": [_record_payload(record) for record in run.records],
    }
    json_path.write_text(json.dumps(payload, indent=2) + "\n")

    with csv_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for record in sorted(run.records, key=_priority_key):
            row = _record_payload(record)
            row["better_condition_cheaper"] = str(
                row["better_condition_cheaper"]
            ).lower()
            row["suggested_price_below_nz_1"] = str(
                row["suggested_price_below_nz_1"]
            ).lower()
            writer.writerow(row)
    return json_path, csv_path


def default_output_dir(*, started_at=None):
    started_at = started_at or datetime.now(timezone.utc)
    timestamp = started_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    workspace_directory = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    workspace_root = Path(workspace_directory) if workspace_directory else Path.cwd()
    return workspace_root / "tmp/tcg-lister" / f"pricing-{timestamp}"


def format_summary(run, output_dir, *, use_color=False):
    summary = run.summary
    signal_line = f"Pricing signal: {summary.pricing_signal.value}"
    if use_color:
        signal_line = (
            f"{PRICING_SIGNAL_COLORS[summary.pricing_signal]}{signal_line}{ANSI_RESET}"
        )
    lines = [
        signal_line,
        summary.diagnosis,
        (
            f"Listings: {summary.total_listing_count} "
            f"({summary.total_copy_count} copies, "
            f"NZ${summary.total_listed_value_nzd:.2f} listed value)"
        ),
        (
            f"Overpriced shares: listings {_format_share(summary.overpriced_listing_share)}, "
            f"copies {_format_share(summary.overpriced_copy_share)}, "
            f"value {_format_share(summary.overpriced_value_share)}"
        ),
        (
            "Potential markdown for high-confidence targets: "
            f"NZ${summary.total_potential_markdown_nzd:.2f}"
        ),
        "Statuses: "
        + ", ".join(
            f"{status} {aggregate.listing_count}/NZ${aggregate.listed_value_nzd:.2f}"
            for status, aggregate in summary.statuses.items()
        ),
        "Price bands: "
        + ", ".join(
            f"{band} {aggregate.listing_count}/NZ${aggregate.listed_value_nzd:.2f}"
            for band, aggregate in summary.price_bands.items()
        ),
    ]
    opportunities = [
        record
        for record in sorted(run.records, key=_priority_key)
        if record.status == PricingStatus.OVERPRICED
    ][:10]
    if opportunities:
        lines.append("Top repricing opportunities:")
        lines.extend(
            (
                f"  #{record.listing_id} {record.name or record.fetch_card_id}: "
                f"NZ${record.listed_price_nzd:.2f} -> "
                f"NZ${record.suggested_price_nzd:.2f}, "
                f"NZ${record.potential_markdown_nzd:.2f} total markdown"
            )
            for record in opportunities
        )
    else:
        lines.append("Top repricing opportunities: none")
    lines.append(f"Reports: {output_dir}")
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None):
    args = parse_args(argv)
    try:
        client = FetchTcgClient(
            token=os.environ.get("FETCHTCG_TOKEN"),
            verbose=args.verbose,
        )
        run = analyze_all(client, limit=args.limit)
        output_dir = default_output_dir(started_at=run.generated_at)
        write_reports(run, output_dir)
    except (FetchTcgError, OSError, ValueError) as error:
        print(f"pricing analysis failed: {error}", file=sys.stderr)
        return 1
    print(format_summary(run, output_dir, use_color=sys.stdout.isatty()))
    return 0


def _verify_owned_identity(owned, details):
    if owned.fetch_card_id != details.fetch_card_id:
        raise PricingAnalysisError("Fetch card identity did not match owned listing")
    if owned.scryfall_id.casefold() != details.scryfall_id.casefold():
        raise PricingAnalysisError("Scryfall identity did not match owned listing")
    if owned.set_id != details.set_id:
        raise PricingAnalysisError("Fetch set identity did not match owned listing")
    if owned.finish.casefold() != details.finish.casefold():
        raise PricingAnalysisError("finish did not match owned listing")
    if owned.condition not in CONDITION_QUALITY:
        raise PricingAnalysisError("owned listing condition was unsupported")


def _supported_floor(competitors):
    cumulative_sellers = set()
    cumulative_copies = 0
    prices = sorted({competitor.listed_price_nzd for competitor in competitors})
    for price in prices:
        price_tier = [
            competitor
            for competitor in competitors
            if competitor.listed_price_nzd == price
        ]
        cumulative_sellers.update(competitor.seller_key for competitor in price_tier)
        cumulative_copies += sum(
            competitor.remaining_quantity for competitor in price_tier
        )
        if len(cumulative_sellers) >= 2 or cumulative_copies >= 3:
            return price
    return None


def _material_gap(benchmark):
    return max(MINIMUM_MATERIAL_GAP_NZD, benchmark * MATERIAL_GAP_RATE)


def _is_material_gap(owned_price, benchmark):
    return owned_price - benchmark >= _material_gap(benchmark)


def _gap(owned_price, benchmark):
    return owned_price - benchmark if benchmark is not None else None


def _gap_percent(gap, benchmark):
    if gap is None or benchmark is None or benchmark == 0:
        return None
    return gap / benchmark * Decimal("100")


def _price_ratio(owned_price, benchmark):
    if benchmark is None or benchmark == 0:
        return None
    return owned_price / benchmark


def _price_band(price):
    if price < Decimal("1"):
        return "under_nz_1"
    if price < Decimal("2"):
        return "nz_1"
    if price < Decimal("3"):
        return "nz_2"
    if price < Decimal("4"):
        return "nz_3"
    if price < Decimal("10"):
        return "nz_4_to_9_99"
    return "nz_10_plus"


def _review_record(owned, error):
    return PricingRecord(
        listing_id=owned.listing_id,
        fetch_card_id=owned.fetch_card_id,
        scryfall_id=owned.scryfall_id,
        name=None,
        set_id=owned.set_id,
        collector_number=None,
        finish=owned.finish,
        condition=owned.condition,
        remaining_quantity=owned.remaining_quantity,
        listed_price_nzd=owned.listed_price_nzd,
        listed_value_nzd=(owned.listed_price_nzd * owned.remaining_quantity),
        own_price_band=_price_band(owned.listed_price_nzd),
        market_price_nzd=None,
        competitor_listing_count=0,
        competitor_seller_count=0,
        competitor_copy_count=0,
        immediate_floor_nzd=None,
        supported_floor_nzd=None,
        cheaper_listing_count=0,
        cheaper_seller_count=0,
        cheaper_copy_count=0,
        price_rank=None,
        immediate_gap_nzd=None,
        immediate_gap_percent=None,
        supported_gap_nzd=None,
        supported_gap_percent=None,
        supported_price_ratio=None,
        better_condition_lowest_price_nzd=None,
        better_condition_cheaper=False,
        status=PricingStatus.REVIEW,
        status_reason="pricing evidence could not be loaded or validated",
        suggested_price_nzd=None,
        suggested_price_below_nz_1=False,
        potential_markdown_nzd=Decimal("0"),
        analysis_error=error,
    )


def _aggregate(records):
    records = tuple(records)
    return Aggregate(
        listing_count=len(records),
        copy_count=sum(record.remaining_quantity for record in records),
        listed_value_nzd=sum(
            (record.listed_value_nzd for record in records), Decimal("0")
        ),
        potential_markdown_nzd=sum(
            (record.potential_markdown_nzd for record in records),
            Decimal("0"),
        ),
    )


def _share(numerator, denominator):
    if denominator == 0:
        return None
    return Decimal(numerator) / Decimal(denominator)


def _pricing_signal(listing_share, copy_share, value_share):
    shares = (listing_share, copy_share, value_share)
    if any(share is None for share in shares):
        return PricingSignal.INSUFFICIENT_DATA
    if sum(share >= STRONG_SIGNAL_SHARE for share in shares) >= 2:
        return PricingSignal.STRONG
    if all(share <= LIMITED_SIGNAL_SHARE for share in shares):
        return PricingSignal.LIMITED
    return PricingSignal.MIXED


def _diagnosis(signal):
    if signal == PricingSignal.STRONG:
        return (
            "Current competitor supply strongly supports pricing as a "
            "contributor to weak sales; this does not measure sales velocity "
            "or prove causation."
        )
    if signal == PricingSignal.LIMITED:
        return (
            "Current competitor supply does not strongly support pricing as "
            "the main cause of weak sales; range or demand is not proven "
            "instead because this does not measure sales velocity."
        )
    if signal == PricingSignal.MIXED:
        return (
            "Current competitor supply gives mixed evidence that pricing "
            "contributes to weak sales; this does not measure sales velocity "
            "or prove causation."
        )
    return (
        "No listing had complete competitor evidence, so pricing cannot be "
        "assessed; this does not measure sales velocity."
    )


def _record_payload(record):
    return {
        "listing_id": record.listing_id,
        "fetch_card_id": record.fetch_card_id,
        "scryfall_id": record.scryfall_id,
        "name": record.name,
        "set_id": record.set_id,
        "collector_number": record.collector_number,
        "finish": record.finish,
        "condition": record.condition,
        "remaining_quantity": record.remaining_quantity,
        "listed_price_nzd": _money(record.listed_price_nzd),
        "listed_value_nzd": _money(record.listed_value_nzd),
        "own_price_band": record.own_price_band,
        "market_price_nzd": _money(record.market_price_nzd),
        "competitor_listing_count": record.competitor_listing_count,
        "competitor_seller_count": record.competitor_seller_count,
        "competitor_copy_count": record.competitor_copy_count,
        "immediate_floor_nzd": _money(record.immediate_floor_nzd),
        "supported_floor_nzd": _money(record.supported_floor_nzd),
        "cheaper_listing_count": record.cheaper_listing_count,
        "cheaper_seller_count": record.cheaper_seller_count,
        "cheaper_copy_count": record.cheaper_copy_count,
        "price_rank": record.price_rank,
        "immediate_gap_nzd": _money(record.immediate_gap_nzd),
        "immediate_gap_percent": _ratio(record.immediate_gap_percent),
        "supported_gap_nzd": _money(record.supported_gap_nzd),
        "supported_gap_percent": _ratio(record.supported_gap_percent),
        "supported_price_ratio": _ratio(record.supported_price_ratio),
        "better_condition_lowest_price_nzd": _money(
            record.better_condition_lowest_price_nzd
        ),
        "better_condition_cheaper": record.better_condition_cheaper,
        "status": record.status.value,
        "status_reason": record.status_reason,
        "suggested_price_nzd": _money(record.suggested_price_nzd),
        "suggested_price_below_nz_1": record.suggested_price_below_nz_1,
        "potential_markdown_nzd": _money(record.potential_markdown_nzd),
        "analysis_error": record.analysis_error,
    }


def _summary_payload(summary):
    return {
        "total_listing_count": summary.total_listing_count,
        "total_copy_count": summary.total_copy_count,
        "total_listed_value_nzd": _money(summary.total_listed_value_nzd),
        "total_potential_markdown_nzd": _money(summary.total_potential_markdown_nzd),
        "analyzable_listing_count": summary.analyzable_listing_count,
        "analyzable_copy_count": summary.analyzable_copy_count,
        "analyzable_listed_value_nzd": _money(summary.analyzable_listed_value_nzd),
        "median_supported_price_ratio": _ratio(summary.median_supported_price_ratio),
        "overpriced_listing_share": _ratio(summary.overpriced_listing_share),
        "overpriced_copy_share": _ratio(summary.overpriced_copy_share),
        "overpriced_value_share": _ratio(summary.overpriced_value_share),
        "pricing_signal": summary.pricing_signal.value,
        "diagnosis": summary.diagnosis,
        "statuses": {
            key: _aggregate_payload(value) for key, value in summary.statuses.items()
        },
        "price_bands": {
            key: _aggregate_payload(value) for key, value in summary.price_bands.items()
        },
    }


def _aggregate_payload(aggregate):
    return {
        "listing_count": aggregate.listing_count,
        "copy_count": aggregate.copy_count,
        "listed_value_nzd": _money(aggregate.listed_value_nzd),
        "potential_markdown_nzd": _money(aggregate.potential_markdown_nzd),
    }


def _priority_key(record):
    priority = {
        PricingStatus.OVERPRICED: 0,
        PricingStatus.WATCH: 1,
        PricingStatus.REVIEW: 2,
        PricingStatus.NO_COMPETITION: 3,
        PricingStatus.COMPETITIVE: 4,
    }
    gap_percent = (
        record.supported_gap_percent
        if record.supported_gap_percent is not None
        else record.immediate_gap_percent
    )
    return (
        priority[record.status],
        -record.potential_markdown_nzd,
        -(gap_percent or Decimal("-1000000")),
        record.listing_id,
    )


def _money(value):
    return None if value is None else f"{value:.2f}"


def _ratio(value):
    return None if value is None else f"{value:.4f}"


def _format_share(value):
    return "n/a" if value is None else f"{value:.1%}"


def _positive_int(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed
