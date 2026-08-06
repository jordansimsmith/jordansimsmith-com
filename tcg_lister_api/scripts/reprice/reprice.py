import argparse
import csv
import json
import os
import signal
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum
from pathlib import Path
from typing import Callable, Sequence

from fetchtcg_client import (
    CONDITION_QUALITY,
    FetchTcgClient,
    ListingUpsertRequest,
)


MINIMUM_LIST_PRICE_NZD = Decimal("0.75")
PRICE_INCREMENT_NZD = Decimal("0.25")
SUPPORTED_PRICE_SELLER_COUNT = 2
REPORT_SCHEMA_VERSION = 1
ANSI_RESET = "\033[0m"
OUTCOME_COLORS = {
    "DECREASE": "\033[32m",
    "UNCHANGED": "\033[34m",
    "INCREASE": "\033[33m",
    "REVIEW": "\033[33m",
    "FAILED": "\033[31m",
}
CSV_FIELDS = [
    "inventory_offset",
    "stable_position",
    "listing_id",
    "fetch_card_id",
    "scryfall_id",
    "name",
    "collector_number",
    "set_id",
    "finish",
    "condition",
    "remaining_quantity",
    "current_price_nzd",
    "decision",
    "decision_reason",
    "market_price_nzd",
    "supported_local_price_nzd",
    "better_condition_lowest_price_nzd",
    "target_price_nzd",
    "local_listing_count",
    "local_seller_count",
    "local_copy_count",
    "all_condition_local_seller_count",
    "all_condition_local_copy_count",
    "lowest_local_price_nzd",
    "price_ladder",
    "mutation_status",
    "mutation_price_nzd",
    "mutation_error",
]


class Decision(str, Enum):
    LIST = "LIST"
    DISCARD = "DISCARD"
    REVIEW = "REVIEW"


class MutationStatus(str, Enum):
    PLANNED = "PLANNED"
    SUCCEEDED = "SUCCEEDED"
    UNCHANGED = "UNCHANGED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"


class ControlledTermination(RuntimeError):
    pass


@dataclass(frozen=True)
class PriceTier:
    listing_count: int
    copy_count: int
    seller_keys: frozenset[str]

    @property
    def seller_count(self):
        return len(self.seller_keys)


@dataclass(frozen=True)
class PricingResult:
    decision: Decision
    decision_reason: str
    market_price_nzd: Decimal | None
    supported_local_price_nzd: Decimal | None
    better_condition_lowest_price_nzd: Decimal | None
    target_price_nzd: Decimal | None
    local_listing_count: int
    local_seller_count: int
    local_copy_count: int
    all_condition_local_seller_count: int
    all_condition_local_copy_count: int
    lowest_local_price_nzd: Decimal | None
    price_ladder: dict[Decimal, PriceTier]


@dataclass(frozen=True)
class RepriceRecord:
    inventory_offset: int
    stable_position: int
    listing_id: int
    fetch_card_id: str
    scryfall_id: str
    name: str | None
    collector_number: str | None
    set_id: int
    finish: str
    condition: str
    remaining_quantity: int
    current_price_nzd: Decimal
    decision: Decision
    decision_reason: str
    market_price_nzd: Decimal | None
    supported_local_price_nzd: Decimal | None
    better_condition_lowest_price_nzd: Decimal | None
    target_price_nzd: Decimal | None
    local_listing_count: int
    local_seller_count: int
    local_copy_count: int
    all_condition_local_seller_count: int
    all_condition_local_copy_count: int
    lowest_local_price_nzd: Decimal | None
    price_ladder: dict[Decimal, PriceTier]
    mutation_status: MutationStatus
    mutation_price_nzd: Decimal | None
    mutation_error: str | None


@dataclass
class RepriceRun:
    generated_at: datetime
    execution_mode: str
    requested_offset: int
    requested_limit: int | None
    records: list[RepriceRecord]
    managed_listing_count: int | None = None
    selected_listing_count: int = 0
    completed_listing_count: int = 0
    next_offset: int = 0
    request_count: int = 0
    complete: bool = False
    portfolio_complete: bool = False
    error: str | None = None


def parse_args(argv: Sequence[str] | None = None):
    parser = argparse.ArgumentParser(
        description="Exhaustively reprice active Fetch TCG listings."
    )
    parser.add_argument(
        "--offset",
        type=_nonnegative_int,
        default=0,
        help="skip N listings after numeric listing-id sorting",
    )
    parser.add_argument(
        "--limit",
        type=_positive_int,
        help="process at most N listings",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="apply price updates; default is dry-run",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print Fetch request diagnostics",
    )
    return parser.parse_args(argv)


def calculate_pricing(card, competitors, condition):
    mutable_ladder = {}
    all_condition_sellers = set()
    all_condition_copy_count = 0
    better_condition_lowest_price = None
    condition_quality = CONDITION_QUALITY.get(condition)
    if condition_quality is None:
        return _empty_pricing_result(
            card.market_price_nzd,
            "managed listing condition was missing or invalid",
        )

    for competitor in competitors:
        all_condition_sellers.add(competitor.seller_key)
        all_condition_copy_count += competitor.remaining_quantity
        competitor_quality = CONDITION_QUALITY.get(competitor.condition)
        if competitor_quality is None:
            return _empty_pricing_result(
                card.market_price_nzd,
                "competitor condition was missing or invalid",
            )
        if competitor_quality > condition_quality:
            better_condition_lowest_price = (
                competitor.listed_price_nzd
                if better_condition_lowest_price is None
                else min(
                    better_condition_lowest_price,
                    competitor.listed_price_nzd,
                )
            )
        if competitor_quality < condition_quality:
            continue
        tier = mutable_ladder.setdefault(
            competitor.listed_price_nzd,
            {
                "listing_count": 0,
                "copy_count": 0,
                "seller_keys": set(),
            },
        )
        tier["listing_count"] += 1
        tier["copy_count"] += competitor.remaining_quantity
        tier["seller_keys"].add(competitor.seller_key)

    price_ladder = {
        price: PriceTier(
            listing_count=values["listing_count"],
            copy_count=values["copy_count"],
            seller_keys=frozenset(values["seller_keys"]),
        )
        for price, values in mutable_ladder.items()
    }
    local_listing_count = sum(tier.listing_count for tier in price_ladder.values())
    local_sellers = {
        seller_key for tier in price_ladder.values() for seller_key in tier.seller_keys
    }
    local_copy_count = sum(tier.copy_count for tier in price_ladder.values())
    supported_local_price = _supported_local_price(price_ladder)
    market_price = card.market_price_nzd

    common = {
        "market_price_nzd": market_price,
        "supported_local_price_nzd": supported_local_price,
        "better_condition_lowest_price_nzd": (better_condition_lowest_price),
        "local_listing_count": local_listing_count,
        "local_seller_count": len(local_sellers),
        "local_copy_count": local_copy_count,
        "all_condition_local_seller_count": len(all_condition_sellers),
        "all_condition_local_copy_count": all_condition_copy_count,
        "lowest_local_price_nzd": (min(price_ladder) if price_ladder else None),
        "price_ladder": price_ladder,
    }
    if market_price is None or not market_price.is_finite() or market_price < 0:
        return PricingResult(
            decision=Decision.REVIEW,
            decision_reason="Fetch did not provide a valid NZD market price",
            target_price_nzd=None,
            **common,
        )
    if market_price < Decimal("0.25"):
        return PricingResult(
            decision=Decision.DISCARD,
            decision_reason=(
                "market price is below NZ$0.25; existing DISCARD inventory "
                "has an NZ$0.75 liquidation ceiling"
            ),
            target_price_nzd=MINIMUM_LIST_PRICE_NZD,
            **common,
        )
    if market_price <= Decimal("0.33") and all_condition_copy_count > 0:
        return PricingResult(
            decision=Decision.DISCARD,
            decision_reason=(
                "market price is at most NZ$0.33 and non-owned local stock "
                "exists in at least one condition; existing DISCARD inventory "
                "has an NZ$0.75 liquidation ceiling"
            ),
            target_price_nzd=MINIMUM_LIST_PRICE_NZD,
            **common,
        )
    if (
        market_price > Decimal("0.33")
        and market_price < Decimal("0.50")
        and len(all_condition_sellers) > 5
    ):
        return PricingResult(
            decision=Decision.DISCARD,
            decision_reason=(
                "market price is above NZ$0.33 and below NZ$0.50 with more "
                "than five distinct non-owned sellers across all conditions; "
                "existing DISCARD inventory has an NZ$0.75 liquidation ceiling"
            ),
            target_price_nzd=MINIMUM_LIST_PRICE_NZD,
            **common,
        )

    benchmark = (
        supported_local_price if supported_local_price is not None else market_price
    )
    target_price = max(
        MINIMUM_LIST_PRICE_NZD,
        (benchmark / PRICE_INCREMENT_NZD).to_integral_value(rounding=ROUND_HALF_UP)
        * PRICE_INCREMENT_NZD,
    )
    if market_price < Decimal("0.50"):
        if all_condition_copy_count == 0:
            reason = (
                "market price is at least NZ$0.25 and below NZ$0.50 with "
                "no non-owned local stock in any condition"
            )
        else:
            reason = (
                "market price is above NZ$0.33 and below NZ$0.50 with at "
                "most five distinct non-owned sellers across all conditions"
            )
    else:
        reason = "market price is at least NZ$0.50"
    if supported_local_price is None:
        reason += (
            f"; price uses the NZ${market_price:.2f} market benchmark, "
            "the nearest NZ$0.25 increment, and the NZ$0.75 floor"
        )
    else:
        reason += (
            f"; price uses the NZ${supported_local_price:.2f} two-seller "
            "supported same-or-better-condition floor, the nearest NZ$0.25 "
            "increment, and the NZ$0.75 floor"
        )
    return PricingResult(
        decision=Decision.LIST,
        decision_reason=reason,
        target_price_nzd=target_price,
        **common,
    )


def run_repricing(
    client,
    *,
    output_dir,
    offset=0,
    limit=None,
    execute=False,
    generated_at=None,
    use_color=False,
    output: Callable[[str], None] = print,
):
    generated_at = generated_at or datetime.now(timezone.utc)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    run = RepriceRun(
        generated_at=generated_at,
        execution_mode="execute" if execute else "dry_run",
        requested_offset=offset,
        requested_limit=limit,
        records=[],
        next_offset=offset,
    )

    try:
        _checkpoint(run, client, output_dir)
        try:
            managed_listings = sorted(
                client.get_managed_listings(),
                key=lambda listing: listing.listing_id,
            )
        except (KeyboardInterrupt, Exception) as error:
            run.error = _safe_error(error)
            return run

        run.managed_listing_count = len(managed_listings)
        selected = managed_listings[offset:]
        if limit is not None:
            selected = selected[:limit]
        run.selected_listing_count = len(selected)
        duplicate_identities = _duplicate_identities(managed_listings)
        owned_listing_ids = frozenset(
            listing.listing_id for listing in managed_listings
        )
        _checkpoint(run, client, output_dir)

        for inventory_offset, listing in enumerate(selected, start=offset):
            card = None
            pricing = None
            mutation_price = None
            try:
                identity = (
                    listing.fetch_card_id.casefold(),
                    listing.condition.casefold(),
                )
                if identity in duplicate_identities:
                    pricing = _empty_pricing_result(
                        None,
                        "multiple active managed listings share card and condition",
                    )
                    record = _record(
                        inventory_offset,
                        listing,
                        card,
                        pricing,
                        MutationStatus.SKIPPED,
                    )
                else:
                    card = client.get_card_details(listing.fetch_card_id)
                    identity_error = _identity_error(listing, card)
                    if identity_error is not None:
                        pricing = _empty_pricing_result(
                            card.market_price_nzd,
                            identity_error,
                        )
                        record = _record(
                            inventory_offset,
                            listing,
                            card,
                            pricing,
                            MutationStatus.SKIPPED,
                        )
                    else:
                        competitors = client.get_competitor_listings(
                            listing.fetch_card_id,
                            excluded_listing_ids=owned_listing_ids,
                        )
                        pricing = calculate_pricing(
                            card,
                            competitors,
                            listing.condition,
                        )
                        if pricing.decision == Decision.REVIEW:
                            mutation_status = MutationStatus.SKIPPED
                        elif (
                            pricing.decision == Decision.DISCARD
                            and pricing.target_price_nzd is not None
                            and listing.listed_price_nzd <= pricing.target_price_nzd
                        ):
                            mutation_status = MutationStatus.UNCHANGED
                        elif pricing.target_price_nzd == listing.listed_price_nzd:
                            mutation_status = MutationStatus.UNCHANGED
                        elif execute:
                            mutation_price = pricing.target_price_nzd
                            result = client.upsert_managed_listing(
                                ListingUpsertRequest(
                                    fetch_card_id=listing.fetch_card_id,
                                    condition=listing.condition,
                                    quantity=listing.remaining_quantity,
                                    listed_price_nzd=mutation_price,
                                ),
                                expected_listing_id=listing.listing_id,
                            )
                            mutation_price = result.listed_price_nzd
                            mutation_status = MutationStatus.SUCCEEDED
                        else:
                            mutation_price = pricing.target_price_nzd
                            mutation_status = MutationStatus.PLANNED
                        record = _record(
                            inventory_offset,
                            listing,
                            card,
                            pricing,
                            mutation_status,
                            mutation_price_nzd=mutation_price,
                        )
            except (KeyboardInterrupt, Exception) as error:
                message = _safe_error(error)
                failed_record = _record(
                    inventory_offset,
                    listing,
                    card,
                    pricing
                    or _empty_pricing_result(
                        card.market_price_nzd if card else None,
                        f"listing processing failed: {message}",
                    ),
                    MutationStatus.FAILED,
                    mutation_price_nzd=mutation_price,
                    mutation_error=message,
                )
                run.records.append(failed_record)
                run.error = message
                _checkpoint(run, client, output_dir)
                output(_format_record(failed_record, use_color=use_color))
                break

            run.records.append(record)
            run.completed_listing_count += 1
            run.next_offset = offset + run.completed_listing_count
            _checkpoint(run, client, output_dir)
            output(_format_record(record, use_color=use_color))
            output(f"[progress] next offset: {run.next_offset}")
        else:
            run.complete = True
            run.portfolio_complete = run.next_offset >= len(managed_listings)

        return run
    finally:
        try:
            _checkpoint(run, client, output_dir)
        finally:
            if not run.portfolio_complete:
                output(
                    "[resume] command: "
                    + _continuation_command(
                        run.next_offset,
                        limit,
                        execute=execute,
                    )
                )
            output(f"[resume] next offset: {run.next_offset}")


def write_reports(run, output_dir, *, updated_at=None):
    updated_at = updated_at or datetime.now(timezone.utc)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    record_payloads = [_record_payload(record) for record in run.records]
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "generated_at": run.generated_at.astimezone(timezone.utc).isoformat(),
        "updated_at": updated_at.astimezone(timezone.utc).isoformat(),
        "execution_mode": run.execution_mode,
        "requested_offset": run.requested_offset,
        "requested_limit": run.requested_limit,
        "managed_listing_count": run.managed_listing_count,
        "selected_listing_count": run.selected_listing_count,
        "completed_listing_count": run.completed_listing_count,
        "next_offset": run.next_offset,
        "request_count": run.request_count,
        "complete": run.complete,
        "portfolio_complete": run.portfolio_complete,
        "error": run.error,
        "records": record_payloads,
    }

    csv_path = output_dir / "listings.csv"
    csv_tmp_path = output_dir / "listings.csv.tmp"
    with csv_tmp_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for payload in record_payloads:
            row = dict(payload)
            for field in CSV_FIELDS:
                if row[field] is None:
                    row[field] = ""
            row["price_ladder"] = json.dumps(
                payload["price_ladder"],
                separators=(",", ":"),
                sort_keys=True,
            )
            writer.writerow(row)
    csv_tmp_path.replace(csv_path)

    _atomic_write_text(
        output_dir / "report.json",
        json.dumps(report, indent=2, sort_keys=True) + "\n",
    )
    return report


def default_output_dir(generated_at=None):
    generated_at = generated_at or datetime.now(timezone.utc)
    workspace = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    base = Path(workspace) if workspace else Path.cwd()
    timestamp = generated_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return base / "tmp" / "tcg-lister" / f"reprice-{timestamp}"


def main(argv: Sequence[str] | None = None):
    args = parse_args(argv)
    generated_at = datetime.now(timezone.utc)
    output_dir = default_output_dir(generated_at)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = FetchTcgClient(
        token=os.environ.get("FETCHTCG_TOKEN"),
        verbose=args.verbose,
    )
    previous_handlers = {}
    for signal_number in (signal.SIGINT, signal.SIGTERM):
        previous_handlers[signal_number] = signal.getsignal(signal_number)
        signal.signal(signal_number, _handle_termination_signal)
    try:
        run = run_repricing(
            client,
            output_dir=output_dir,
            offset=args.offset,
            limit=args.limit,
            execute=args.execute,
            generated_at=generated_at,
            use_color=sys.stdout.isatty(),
        )
    finally:
        for signal_number, previous_handler in previous_handlers.items():
            signal.signal(signal_number, previous_handler)

    print(_format_summary(run))
    print(f"Reports: {output_dir}")
    return 0 if run.complete else 1


def _supported_local_price(price_ladder):
    sellers = set()
    for price in sorted(price_ladder):
        sellers.update(price_ladder[price].seller_keys)
        if len(sellers) >= SUPPORTED_PRICE_SELLER_COUNT:
            return price
    return None


def _empty_pricing_result(market_price, reason):
    return PricingResult(
        decision=Decision.REVIEW,
        decision_reason=reason,
        market_price_nzd=market_price,
        supported_local_price_nzd=None,
        better_condition_lowest_price_nzd=None,
        target_price_nzd=None,
        local_listing_count=0,
        local_seller_count=0,
        local_copy_count=0,
        all_condition_local_seller_count=0,
        all_condition_local_copy_count=0,
        lowest_local_price_nzd=None,
        price_ladder={},
    )


def _identity_error(listing, card):
    if (
        card.fetch_card_id.casefold() != listing.fetch_card_id.casefold()
        or card.scryfall_id.casefold() != listing.scryfall_id.casefold()
        or card.set_id != listing.set_id
        or card.finish.casefold() != listing.finish.casefold()
    ):
        return "managed listing identity did not match card details"
    return None


def _duplicate_identities(managed_listings):
    counts = {}
    for listing in managed_listings:
        identity = (
            listing.fetch_card_id.casefold(),
            listing.condition.casefold(),
        )
        counts[identity] = counts.get(identity, 0) + 1
    return frozenset(identity for identity, count in counts.items() if count > 1)


def _record(
    inventory_offset,
    listing,
    card,
    pricing,
    mutation_status,
    *,
    mutation_price_nzd=None,
    mutation_error=None,
):
    return RepriceRecord(
        inventory_offset=inventory_offset,
        stable_position=inventory_offset + 1,
        listing_id=listing.listing_id,
        fetch_card_id=listing.fetch_card_id,
        scryfall_id=listing.scryfall_id,
        name=card.name if card else None,
        collector_number=card.collector_number if card else None,
        set_id=listing.set_id,
        finish=listing.finish,
        condition=listing.condition,
        remaining_quantity=listing.remaining_quantity,
        current_price_nzd=listing.listed_price_nzd,
        decision=pricing.decision,
        decision_reason=pricing.decision_reason,
        market_price_nzd=pricing.market_price_nzd,
        supported_local_price_nzd=pricing.supported_local_price_nzd,
        better_condition_lowest_price_nzd=(pricing.better_condition_lowest_price_nzd),
        target_price_nzd=pricing.target_price_nzd,
        local_listing_count=pricing.local_listing_count,
        local_seller_count=pricing.local_seller_count,
        local_copy_count=pricing.local_copy_count,
        all_condition_local_seller_count=(pricing.all_condition_local_seller_count),
        all_condition_local_copy_count=(pricing.all_condition_local_copy_count),
        lowest_local_price_nzd=pricing.lowest_local_price_nzd,
        price_ladder=pricing.price_ladder,
        mutation_status=mutation_status,
        mutation_price_nzd=mutation_price_nzd,
        mutation_error=mutation_error,
    )


def _record_payload(record):
    return {
        "inventory_offset": record.inventory_offset,
        "stable_position": record.stable_position,
        "listing_id": record.listing_id,
        "fetch_card_id": record.fetch_card_id,
        "scryfall_id": record.scryfall_id,
        "name": record.name,
        "collector_number": record.collector_number,
        "set_id": record.set_id,
        "finish": record.finish,
        "condition": record.condition,
        "remaining_quantity": record.remaining_quantity,
        "current_price_nzd": _decimal_string(
            record.current_price_nzd,
            two_decimal_places=True,
        ),
        "decision": record.decision.value,
        "decision_reason": record.decision_reason,
        "market_price_nzd": _decimal_string(record.market_price_nzd),
        "supported_local_price_nzd": _decimal_string(record.supported_local_price_nzd),
        "better_condition_lowest_price_nzd": _decimal_string(
            record.better_condition_lowest_price_nzd
        ),
        "target_price_nzd": _decimal_string(
            record.target_price_nzd,
            two_decimal_places=True,
        ),
        "local_listing_count": record.local_listing_count,
        "local_seller_count": record.local_seller_count,
        "local_copy_count": record.local_copy_count,
        "all_condition_local_seller_count": (record.all_condition_local_seller_count),
        "all_condition_local_copy_count": (record.all_condition_local_copy_count),
        "lowest_local_price_nzd": _decimal_string(record.lowest_local_price_nzd),
        "price_ladder": {
            f"{price:.2f}": {
                "listing_count": tier.listing_count,
                "seller_count": tier.seller_count,
                "copy_count": tier.copy_count,
            }
            for price, tier in sorted(record.price_ladder.items())
        },
        "mutation_status": record.mutation_status.value,
        "mutation_price_nzd": _decimal_string(
            record.mutation_price_nzd,
            two_decimal_places=True,
        ),
        "mutation_error": record.mutation_error,
    }


def _checkpoint(run, client, output_dir):
    run.request_count = client.request_count
    write_reports(run, output_dir)


def _atomic_write_text(path, content):
    path = Path(path)
    temporary = path.with_name(f"{path.name}.tmp")
    temporary.write_text(content)
    temporary.replace(path)


def _safe_error(error):
    message = str(error) or error.__class__.__name__
    token = os.environ.get("FETCHTCG_TOKEN")
    if token:
        message = message.replace(token, "[redacted]")
    return message


def _format_record(record, *, use_color=False):
    suppress_nonmutation_target = (
        record.decision == Decision.DISCARD
        and record.mutation_status == MutationStatus.UNCHANGED
        and record.target_price_nzd is not None
        and record.current_price_nzd < record.target_price_nzd
    )
    target = (
        f" -> NZ${record.target_price_nzd:.2f}"
        if record.target_price_nzd is not None and not suppress_nonmutation_target
        else ""
    )
    name = record.name or record.fetch_card_id
    outcome = _outcome(record)
    formatted_outcome = (
        f"{OUTCOME_COLORS[outcome]}{outcome}{ANSI_RESET}" if use_color else outcome
    )
    line = (
        f"[{record.stable_position}] #{record.listing_id} {name}: "
        f"NZ${record.current_price_nzd:.2f}{target} "
        f"{formatted_outcome} "
        f"({record.decision.value}/{record.mutation_status.value}) "
        f"— {record.decision_reason}"
    )
    if (
        record.mutation_error is not None
        and record.mutation_error not in record.decision_reason
    ):
        line += f" — {record.mutation_error}"
    return line


def _outcome(record):
    if record.mutation_status == MutationStatus.FAILED:
        return "FAILED"
    if record.decision == Decision.REVIEW:
        return "REVIEW"
    if (
        record.mutation_status == MutationStatus.UNCHANGED
        or record.target_price_nzd is None
        or record.target_price_nzd == record.current_price_nzd
    ):
        return "UNCHANGED"
    if record.target_price_nzd < record.current_price_nzd:
        return "DECREASE"
    return "INCREASE"


def _format_summary(run):
    return (
        f"[summary] completed {run.completed_listing_count}/"
        f"{run.selected_listing_count} selected listings; "
        f"next offset {run.next_offset}; "
        f"{'complete' if run.complete else 'incomplete'}"
    )


def _continuation_command(offset, limit, *, execute):
    arguments = [f"--offset {offset}"]
    if limit is not None:
        arguments.append(f"--limit {limit}")
    if execute:
        arguments.append("--execute")
    return "bazel run //tcg_lister_api:fetchtcg-reprice -- " + " ".join(arguments)


def _decimal_string(value, *, two_decimal_places=False):
    if value is None:
        return None
    if two_decimal_places:
        return f"{value:.2f}"
    return str(value)


def _handle_termination_signal(signum, _frame):
    signal_name = signal.Signals(signum).name
    raise ControlledTermination(f"received {signal_name}")


def _nonnegative_int(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be at least 0")
    return parsed


def _positive_int(value):
    parsed = _nonnegative_int(value)
    if parsed == 0:
        raise argparse.ArgumentTypeError("must be greater than 0")
    return parsed


if __name__ == "__main__":
    sys.exit(main())
