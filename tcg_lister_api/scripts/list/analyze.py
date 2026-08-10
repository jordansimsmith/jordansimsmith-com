import argparse
import csv
import json
import os
import re
import sys
import uuid
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum
from pathlib import Path
from typing import Callable, Sequence

from fetchtcg_client import (
    CardQuery,
    FetchTcgClient,
    FetchTcgError,
    ListingUpsertRequest,
    MarketSnapshot,
    PriceTier,
    RunSafetyStop,
)


REQUIRED_COLUMNS = {
    "Name",
    "Set code",
    "Set name",
    "Collector number",
    "Foil",
    "Rarity",
    "Quantity",
    "Scryfall ID",
    "Misprint",
    "Altered",
    "Condition",
    "Language",
}

CONDITION_CODES = {
    "mint": "raw-m",
    "near_mint": "raw-nm",
    "excellent": "raw-lp",
    "good": "raw-mp",
    "light_played": "raw-hp",
    "played": "raw-hp",
    "poor": "raw-d",
}

SUPPORTED_FINISHES = {"normal", "foil", "etched"}
MINIMUM_LIST_PRICE_NZD = Decimal("0.25")
PRICE_INCREMENT_NZD = Decimal("0.05")
UNDERCUT_TICK_RATE = Decimal("0.025")
DUMP_GUARD_RATE = Decimal("0.8")
SOLE_SOURCE_PREMIUM_RATE = Decimal("1.15")
SOLE_SOURCE_PREMIUM_MINIMUM_MARKET_NZD = Decimal("2.00")
SUPPORTED_PRICE_SELLER_COUNT = 2
MINIMUM_PRICE_REDUCTION_NZD = Decimal("0.25")
MATERIAL_PRICE_REDUCTION_RATE = Decimal("0.05")

CSV_FIELDS = [
    "stack_position",
    "source_csv_row",
    "quantity_index",
    "name",
    "set_code",
    "collector_number",
    "finish",
    "condition",
    "scryfall_id",
    "fetch_card_id",
    "decision",
    "decision_reason",
    "market_price_nzd",
    "supported_local_price_nzd",
    "better_condition_lowest_price_nzd",
    "suggested_price_nzd",
    "local_listing_count",
    "local_copy_count",
    "all_condition_local_seller_count",
    "all_condition_local_copy_count",
    "lowest_local_price_nzd",
    "price_ladder",
    "listing_action",
    "listing_action_reason",
    "existing_listing_count",
    "existing_copy_count",
    "existing_listings",
    "fetch_set_id",
    "mutation_key",
    "mutation_status",
    "mutation_listing_id",
    "mutation_quantity",
    "mutation_price_nzd",
    "mutation_error",
]


class ManaBoxInputError(ValueError):
    pass


class Decision(str, Enum):
    LIST = "LIST"
    DISCARD = "DISCARD"
    REVIEW = "REVIEW"


class ListingAction(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"
    NONE = "NONE"
    REVIEW = "REVIEW"


class MutationStatus(str, Enum):
    PLANNED = "PLANNED"
    POSTED = "POSTED"
    SUCCEEDED = "SUCCEEDED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"


DECISION_COLORS = {
    Decision.LIST: "\033[32m",
    Decision.DISCARD: "\033[31m",
    Decision.REVIEW: "\033[33m",
}
SUMMARY_COLOR = "\033[34m"
ANSI_RESET = "\033[0m"


@dataclass(frozen=True)
class ManaBoxRow:
    source_csv_row: int
    name: str
    set_code: str
    set_name: str
    collector_number: str
    finish: str
    rarity: str
    quantity: int
    scryfall_id: str
    misprint: bool
    altered: bool
    condition: str
    language: str


@dataclass(frozen=True)
class PhysicalCard:
    stack_position: int
    stack_size: int
    source_csv_row: int
    quantity_index: int
    name: str
    set_code: str
    set_name: str
    collector_number: str
    finish: str
    rarity: str
    scryfall_id: str
    misprint: bool
    altered: bool
    condition: str
    language: str

    def cache_key(self):
        condition_key = CONDITION_CODES.get(
            self.condition.casefold(), self.condition.casefold()
        )
        language_key = (
            "en" if self.language.casefold() in ("en", "english") else self.language
        )
        return (
            self.scryfall_id.casefold(),
            self.set_code.casefold(),
            self.collector_number.casefold(),
            self.finish.casefold(),
            condition_key,
            language_key,
            self.misprint,
            self.altered,
        )

    def query(self):
        return CardQuery(
            name=self.name,
            set_code=self.set_code,
            set_name=self.set_name,
            collector_number=self.collector_number,
            finish=self.finish,
            rarity=self.rarity,
            scryfall_id=self.scryfall_id,
        )


@dataclass(frozen=True)
class DecisionResult:
    decision: Decision
    decision_reason: str
    suggested_price_nzd: Decimal | None
    supported_local_price_nzd: Decimal | None = None


@dataclass(frozen=True)
class CachedAnalysis:
    decision: Decision
    decision_reason: str
    fetch_card_id: str | None = None
    fetch_set_id: int | None = None
    market_price_nzd: Decimal | None = None
    supported_local_price_nzd: Decimal | None = None
    better_condition_lowest_price_nzd: Decimal | None = None
    suggested_price_nzd: Decimal | None = None
    local_listing_count: int = 0
    local_copy_count: int = 0
    all_condition_local_seller_count: int = 0
    all_condition_local_copy_count: int = 0
    lowest_local_price_nzd: Decimal | None = None
    price_ladder: dict[Decimal, PriceTier] | None = None


@dataclass(frozen=True)
class InventoryListing:
    listing_id: int | None
    fetch_card_id: str
    scryfall_id: str
    set_id: int
    finish: str
    condition: str
    remaining_quantity: int
    listed_price_nzd: Decimal
    simulated: bool


class _OwnedInventory:
    def __init__(self, managed_listings):
        self._index = {}
        for listing in managed_listings:
            inventory_listing = InventoryListing(
                listing_id=listing.listing_id,
                fetch_card_id=listing.fetch_card_id,
                scryfall_id=listing.scryfall_id,
                set_id=listing.set_id,
                finish=listing.finish,
                condition=listing.condition,
                remaining_quantity=listing.remaining_quantity,
                listed_price_nzd=listing.listed_price_nzd,
                simulated=False,
            )
            self._index.setdefault(self._key(inventory_listing), []).append(
                inventory_listing
            )

    def listing_ids(self):
        return frozenset(
            listing.listing_id
            for listings in self._index.values()
            for listing in listings
            if listing.listing_id is not None
        )

    def candidates(self, fetch_card_id, condition):
        return tuple(
            self._index.get(
                (fetch_card_id.casefold(), condition.casefold()),
                (),
            )
        )

    def apply(self, listing):
        self._index[self._key(listing)] = [listing]

    @staticmethod
    def _key(listing):
        return (
            listing.fetch_card_id.casefold(),
            listing.condition.casefold(),
        )


@dataclass(frozen=True)
class AnalysisRecord:
    stack_position: int
    source_csv_row: int
    quantity_index: int
    name: str
    set_code: str
    collector_number: str
    finish: str
    condition: str
    scryfall_id: str
    fetch_card_id: str | None
    fetch_set_id: int | None
    decision: Decision
    decision_reason: str
    market_price_nzd: Decimal | None
    supported_local_price_nzd: Decimal | None
    better_condition_lowest_price_nzd: Decimal | None
    suggested_price_nzd: Decimal | None
    local_listing_count: int
    local_copy_count: int
    all_condition_local_seller_count: int
    all_condition_local_copy_count: int
    lowest_local_price_nzd: Decimal | None
    price_ladder: dict[Decimal, PriceTier]
    listing_action: ListingAction
    listing_action_reason: str
    existing_listings: tuple[InventoryListing, ...]
    mutation_key: str | None = None
    mutation_status: MutationStatus = MutationStatus.SKIPPED
    mutation_listing_id: int | None = None
    mutation_quantity: int | None = None
    mutation_price_nzd: Decimal | None = None
    mutation_error: str | None = None


@dataclass(frozen=True)
class AnalysisRun:
    records: list[AnalysisRecord]
    complete: bool
    error: str | None = None
    execution_mode: str = "dry_run"
    execution_complete: bool = True
    execution_error: str | None = None


def parse_args(argv: Sequence[str] | None = None):
    parser = argparse.ArgumentParser(
        description="Analyze a reversed ManaBox scan against Fetch TCG."
    )
    parser.add_argument("input_csv", type=Path)
    parser.add_argument(
        "--offset",
        type=_nonnegative_int,
        default=0,
        help="skip the first N physical cards",
    )
    parser.add_argument(
        "--limit",
        type=_positive_int,
        help="analyze only the first N physical cards",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="show request, retry, and cache diagnostics",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="execute planned Fetch listing mutations",
    )
    return parser.parse_args(argv)


def load_physical_cards(input_path, limit=None, offset=0):
    input_path = Path(input_path)
    if limit is not None and limit <= 0:
        raise ManaBoxInputError("limit must be positive")
    if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
        raise ManaBoxInputError("offset must be a non-negative integer")
    if not input_path.is_file():
        raise ManaBoxInputError(f"input CSV does not exist: {input_path}")

    try:
        with input_path.open(encoding="utf-8-sig", newline="") as file:
            reader = csv.DictReader(file)
            fieldnames = set(reader.fieldnames or [])
            missing_columns = sorted(REQUIRED_COLUMNS - fieldnames)
            if missing_columns:
                raise ManaBoxInputError(
                    "input CSV is missing columns: " + ", ".join(missing_columns)
                )
            rows = [
                _parse_manabox_row(row, source_csv_row)
                for source_csv_row, row in enumerate(reader, start=2)
            ]
    except OSError as error:
        raise ManaBoxInputError(f"could not read input CSV: {error}") from error

    if not rows:
        raise ManaBoxInputError("input CSV contains no cards")

    stack_size = sum(row.quantity for row in rows)
    if offset >= stack_size:
        raise ManaBoxInputError(
            "offset must be smaller than the expanded physical card count "
            f"of {stack_size}"
        )

    cards = []
    stack_position = 0
    for row in reversed(rows):
        for quantity_index in range(1, row.quantity + 1):
            stack_position += 1
            if stack_position <= offset:
                continue
            cards.append(
                PhysicalCard(
                    stack_position=stack_position,
                    stack_size=stack_size,
                    source_csv_row=row.source_csv_row,
                    quantity_index=quantity_index,
                    name=row.name,
                    set_code=row.set_code,
                    set_name=row.set_name,
                    collector_number=row.collector_number,
                    finish=row.finish,
                    rarity=row.rarity,
                    scryfall_id=row.scryfall_id,
                    misprint=row.misprint,
                    altered=row.altered,
                    condition=row.condition,
                    language=row.language,
                )
            )
            if limit is not None and len(cards) >= limit:
                return cards
    return cards


def decide(snapshot):
    market_price = snapshot.market_price_nzd
    if market_price is None or not market_price.is_finite() or market_price < 0:
        return DecisionResult(
            decision=Decision.REVIEW,
            decision_reason="Fetch did not provide a valid NZD market price",
            suggested_price_nzd=None,
        )
    if market_price < Decimal("0.25"):
        return DecisionResult(
            decision=Decision.DISCARD,
            decision_reason="market price is below NZ$0.25",
            suggested_price_nzd=None,
        )

    supported_local_price = _supported_local_price(snapshot.price_ladder)
    lowest_local_price = min(snapshot.price_ladder) if snapshot.price_ladder else None
    benchmark, benchmark_reason = _select_benchmark(
        market_price, lowest_local_price, supported_local_price
    )
    suggested_price = max(MINIMUM_LIST_PRICE_NZD, _round_to_increment(benchmark))
    reason = "market price is at least NZ$0.25; " + benchmark_reason
    return DecisionResult(
        decision=Decision.LIST,
        decision_reason=reason,
        suggested_price_nzd=suggested_price,
        supported_local_price_nzd=supported_local_price,
    )


def _supported_local_price(price_ladder):
    sellers = set()
    for price in sorted(price_ladder):
        sellers.update(price_ladder[price].seller_keys)
        if len(sellers) >= SUPPORTED_PRICE_SELLER_COUNT:
            return price
    return None


def _round_to_increment(value):
    return (value / PRICE_INCREMENT_NZD).to_integral_value(
        rounding=ROUND_HALF_UP
    ) * PRICE_INCREMENT_NZD


def _undercut_tick(reference_price):
    return max(
        PRICE_INCREMENT_NZD, _round_to_increment(reference_price * UNDERCUT_TICK_RATE)
    )


def _select_benchmark(market_price, lowest_local_price, supported_local_price):
    if lowest_local_price is not None and (
        lowest_local_price >= DUMP_GUARD_RATE * market_price
    ):
        tick = _undercut_tick(lowest_local_price)
        return lowest_local_price - tick, (
            f"price sits one NZ${tick:.2f} tick under the "
            f"NZ${lowest_local_price:.2f} lowest same-or-better-condition "
            "rival, on the nearest NZ$0.05 increment with an NZ$0.25 floor"
        )
    if supported_local_price is not None:
        return supported_local_price, (
            f"the NZ${lowest_local_price:.2f} lowest rival is below 80% of "
            f"the NZ${market_price:.2f} market price, so price matches the "
            f"NZ${supported_local_price:.2f} two-seller supported floor, on "
            "the nearest NZ$0.05 increment with an NZ$0.25 floor"
        )
    if lowest_local_price is None:
        if market_price >= SOLE_SOURCE_PREMIUM_MINIMUM_MARKET_NZD:
            return market_price * SOLE_SOURCE_PREMIUM_RATE, (
                "no same-or-better-condition rival exists, so price carries "
                f"a 15% sole-source premium on the NZ${market_price:.2f} "
                "market price, on the nearest NZ$0.05 increment with an "
                "NZ$0.25 floor"
            )
        return market_price, (
            "no same-or-better-condition rival exists, so price uses the "
            f"NZ${market_price:.2f} market benchmark, on the nearest NZ$0.05 "
            "increment with an NZ$0.25 floor"
        )
    return market_price, (
        f"the NZ${lowest_local_price:.2f} lowest rival is below 80% of the "
        f"NZ${market_price:.2f} market price without two-seller support, so "
        f"price uses the NZ${market_price:.2f} market benchmark, on the "
        "nearest NZ$0.05 increment with an NZ$0.25 floor"
    )


def analyze_cards(
    cards,
    client,
    *,
    managed_listings=(),
    execute=False,
    set_codes=None,
    verbose=False,
    use_color=False,
    output: Callable[[str], None] = print,
):
    cache = {}
    inventory = _OwnedInventory(managed_listings)
    records = []
    expected_listings = {}
    posted_record_indexes = {}
    total = cards[0].stack_size if cards else 0
    complete = True
    run_error = None
    execution_error = None

    for card in cards:
        key = card.cache_key()
        cached = cache.get(key)
        stop_after_card = False
        if cached is not None:
            if verbose:
                output(f"[cache hit] stack position {card.stack_position}: {card.name}")
        else:
            support_error = _support_error(card)
            if support_error:
                cached = CachedAnalysis(
                    decision=Decision.REVIEW,
                    decision_reason=support_error,
                    price_ladder={},
                )
            else:
                try:
                    snapshot = client.get_market_snapshot(
                        card.query(),
                        CONDITION_CODES[card.condition.casefold()],
                        excluded_listing_ids=inventory.listing_ids(),
                    )
                    result = decide(snapshot)
                    cached = CachedAnalysis(
                        decision=result.decision,
                        decision_reason=result.decision_reason,
                        fetch_card_id=snapshot.fetch_card_id,
                        fetch_set_id=snapshot.fetch_set_id,
                        market_price_nzd=snapshot.market_price_nzd,
                        supported_local_price_nzd=(result.supported_local_price_nzd),
                        better_condition_lowest_price_nzd=(
                            snapshot.better_condition_lowest_price_nzd
                        ),
                        suggested_price_nzd=result.suggested_price_nzd,
                        local_listing_count=snapshot.local_listing_count,
                        local_copy_count=snapshot.local_copy_count,
                        all_condition_local_seller_count=(
                            snapshot.all_condition_local_seller_count
                        ),
                        all_condition_local_copy_count=(
                            snapshot.all_condition_local_copy_count
                        ),
                        lowest_local_price_nzd=snapshot.lowest_local_price_nzd,
                        price_ladder=snapshot.price_ladder,
                    )
                except RunSafetyStop as error:
                    cached = CachedAnalysis(
                        decision=Decision.REVIEW,
                        decision_reason=f"run stopped: {error}",
                        price_ladder={},
                    )
                    complete = False
                    run_error = str(error)
                    stop_after_card = True
                except FetchTcgError as error:
                    cached = CachedAnalysis(
                        decision=Decision.REVIEW,
                        decision_reason=str(error),
                        price_ladder={},
                    )
            cache[key] = cached

        (
            listing_action,
            listing_action_reason,
            existing_listings,
        ) = _listing_preview(card, cached, inventory)
        record = _record(
            card,
            cached,
            listing_action,
            listing_action_reason,
            existing_listings,
        )

        if listing_action in (ListingAction.CREATE, ListingAction.UPDATE):
            condition = CONDITION_CODES.get(card.condition.casefold())
            mutation_error = None
            existing_listing = None
            quantity = None
            listed_price = None
            expected_listing_id = None
            if (
                not cached.fetch_card_id
                or cached.fetch_set_id is None
                or isinstance(cached.fetch_set_id, bool)
                or not isinstance(cached.fetch_set_id, int)
                or cached.fetch_set_id <= 0
                or condition is None
            ):
                mutation_error = "actionable record identity was incomplete"
            elif listing_action == ListingAction.CREATE:
                quantity = 1
                listed_price = cached.suggested_price_nzd
                expected_listing_id = None
                if existing_listings:
                    mutation_error = "create action had an existing exact listing"
            else:
                existing_listing = (
                    existing_listings[0] if len(existing_listings) == 1 else None
                )
                if existing_listing is None:
                    quantity = 0
                    listed_price = None
                    expected_listing_id = None
                    mutation_error = (
                        "update action did not have one exact existing listing"
                    )
                else:
                    quantity = existing_listing.remaining_quantity + 1
                    listed_price = _price_for_existing_listing(
                        existing_listing.listed_price_nzd,
                        cached.suggested_price_nzd,
                    )
                    expected_listing_id = existing_listing.listing_id
                    if execute and expected_listing_id is None:
                        mutation_error = "execute update did not have a real listing id"
                    elif expected_listing_id is not None and expected_listing_id <= 0:
                        mutation_error = "existing mutation listing id was invalid"
                    elif existing_listing.remaining_quantity <= 0:
                        mutation_error = "existing mutation quantity was invalid"

            if (
                not isinstance(listed_price, Decimal)
                or not listed_price.is_finite()
                or listed_price < 0
            ):
                mutation_error = mutation_error or "mutation price was invalid"

            mutation_key = (
                f"{cached.fetch_card_id.casefold()}|{condition.casefold()}"
                if cached.fetch_card_id and condition
                else None
            )
            if mutation_error is not None:
                record = replace(
                    record,
                    mutation_key=mutation_key,
                    mutation_status=MutationStatus.FAILED,
                    mutation_listing_id=expected_listing_id,
                    mutation_quantity=quantity,
                    mutation_price_nzd=listed_price,
                    mutation_error=mutation_error,
                )
                execution_error = execution_error or mutation_error
                complete = False
                stop_after_card = True
            elif execute:
                try:
                    result = client.upsert_managed_listing(
                        ListingUpsertRequest(
                            fetch_card_id=cached.fetch_card_id,
                            condition=condition,
                            quantity=quantity,
                            listed_price_nzd=listed_price,
                        ),
                        expected_listing_id=expected_listing_id,
                    )
                except FetchTcgError as error:
                    message = str(error)
                    record = replace(
                        record,
                        mutation_key=mutation_key,
                        mutation_status=MutationStatus.FAILED,
                        mutation_listing_id=expected_listing_id,
                        mutation_quantity=quantity,
                        mutation_price_nzd=listed_price,
                        mutation_error=message,
                    )
                    execution_error = execution_error or message
                    complete = False
                    stop_after_card = True
                else:
                    updated_listing = InventoryListing(
                        listing_id=result.listing_id,
                        fetch_card_id=cached.fetch_card_id,
                        scryfall_id=card.scryfall_id,
                        set_id=cached.fetch_set_id,
                        finish=card.finish,
                        condition=condition,
                        remaining_quantity=result.remaining_quantity,
                        listed_price_nzd=result.listed_price_nzd,
                        simulated=False,
                    )
                    inventory.apply(updated_listing)
                    expected_listings[mutation_key] = updated_listing
                    record = replace(
                        record,
                        mutation_key=mutation_key,
                        mutation_status=MutationStatus.POSTED,
                        mutation_listing_id=result.listing_id,
                        mutation_quantity=result.remaining_quantity,
                        mutation_price_nzd=result.listed_price_nzd,
                    )
            else:
                updated_listing = InventoryListing(
                    listing_id=(
                        existing_listing.listing_id
                        if existing_listing is not None
                        else None
                    ),
                    fetch_card_id=cached.fetch_card_id,
                    scryfall_id=card.scryfall_id,
                    set_id=cached.fetch_set_id,
                    finish=card.finish,
                    condition=condition,
                    remaining_quantity=quantity,
                    listed_price_nzd=listed_price,
                    simulated=True,
                )
                inventory.apply(updated_listing)
                record = replace(
                    record,
                    mutation_key=mutation_key,
                    mutation_status=MutationStatus.PLANNED,
                    mutation_listing_id=updated_listing.listing_id,
                    mutation_quantity=quantity,
                    mutation_price_nzd=listed_price,
                )

        records.append(record)
        if record.mutation_status == MutationStatus.POSTED:
            posted_record_indexes.setdefault(record.mutation_key, []).append(
                len(records) - 1
            )
        output(_format_console_record(record, total, use_color=use_color))
        if stop_after_card:
            break

    if posted_record_indexes:
        try:
            fresh_listings = client.get_managed_listings(
                set_codes or {card.set_code for card in cards}
            )
        except FetchTcgError as error:
            message = f"could not verify mutations: {error}"
            execution_error = execution_error or message
            for indexes in posted_record_indexes.values():
                for index in indexes:
                    records[index] = replace(
                        records[index],
                        mutation_status=MutationStatus.FAILED,
                        mutation_error=message,
                    )
        else:
            for mutation_key, indexes in posted_record_indexes.items():
                verification_error = _verify_inventory_listing(
                    expected_listings[mutation_key],
                    fresh_listings,
                )
                status = (
                    MutationStatus.SUCCEEDED
                    if verification_error is None
                    else MutationStatus.FAILED
                )
                execution_error = execution_error or verification_error
                for index in indexes:
                    records[index] = replace(
                        records[index],
                        mutation_status=status,
                        mutation_error=verification_error,
                    )
                output(f"[verify] {mutation_key} {status.value}")

    execution_complete = complete and all(
        record.mutation_status
        in (
            MutationStatus.PLANNED,
            MutationStatus.SUCCEEDED,
            MutationStatus.SKIPPED,
        )
        for record in records
    )
    return AnalysisRun(
        records=records,
        complete=complete,
        error=run_error,
        execution_mode="execute" if execute else "dry_run",
        execution_complete=execution_complete,
        execution_error=execution_error,
    )


def _verify_inventory_listing(expected, managed_listings):
    candidates = [
        listing
        for listing in managed_listings
        if listing.fetch_card_id.casefold() == expected.fetch_card_id.casefold()
        and listing.condition.casefold() == expected.condition.casefold()
    ]
    if len(candidates) != 1:
        return "mutation verification did not find one exact managed listing"
    listing = candidates[0]
    if (
        listing.scryfall_id.casefold() != expected.scryfall_id.casefold()
        or listing.set_id != expected.set_id
        or listing.finish.casefold() != expected.finish.casefold()
    ):
        return "mutation verification identity did not match"
    if listing.listing_id != expected.listing_id:
        return "mutation verification listing ID did not match"
    if listing.remaining_quantity != expected.remaining_quantity:
        return "mutation verification quantity did not match"
    if listing.listed_price_nzd != expected.listed_price_nzd:
        return "mutation verification price did not match"
    return None


def write_reports(
    run,
    input_path,
    output_dir,
    *,
    generated_at=None,
):
    generated_at = generated_at or datetime.now(timezone.utc)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    card_payloads = [_record_payload(record) for record in run.records]
    report = {
        "schema_version": 7,
        "input_path": Path(input_path).name,
        "generated_at": generated_at.astimezone(timezone.utc).isoformat(),
        "complete": run.complete,
        "error": run.error,
        "execution_mode": run.execution_mode,
        "execution_complete": run.execution_complete,
        "execution_error": run.execution_error,
        "processed_card_count": len(run.records),
        "cards": card_payloads,
    }
    (output_dir / "report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n"
    )

    with (output_dir / "stack.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for payload in card_payloads:
            csv_payload = dict(payload)
            for field in (
                "fetch_card_id",
                "fetch_set_id",
                "market_price_nzd",
                "supported_local_price_nzd",
                "better_condition_lowest_price_nzd",
                "suggested_price_nzd",
                "lowest_local_price_nzd",
                "mutation_key",
                "mutation_listing_id",
                "mutation_quantity",
                "mutation_price_nzd",
                "mutation_error",
            ):
                if csv_payload[field] is None:
                    csv_payload[field] = ""
            csv_payload["price_ladder"] = json.dumps(
                payload["price_ladder"],
                separators=(",", ":"),
                sort_keys=True,
            )
            csv_payload["existing_listings"] = json.dumps(
                payload["existing_listings"],
                separators=(",", ":"),
                sort_keys=True,
            )
            writer.writerow(csv_payload)


def default_output_dir(input_path, *, started_at=None):
    started_at = started_at or datetime.now(timezone.utc)
    workspace = Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", Path.cwd()))
    input_stem = re.sub(r"[^A-Za-z0-9_-]+", "-", Path(input_path).stem).strip("-")
    input_stem = input_stem or "scan"
    timestamp = started_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    return workspace / "tmp" / "tcg-lister" / f"{input_stem}-{timestamp}"


def main(argv: Sequence[str] | None = None):
    args = parse_args(argv)
    try:
        cards = load_physical_cards(
            args.input_csv,
            limit=args.limit,
            offset=args.offset,
        )
    except ManaBoxInputError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    client = FetchTcgClient(
        token=os.environ.get("FETCHTCG_TOKEN"),
        verbose=args.verbose,
    )
    try:
        managed_listings = client.get_managed_listings(
            {card.set_code for card in cards}
        )
    except FetchTcgError as error:
        print(f"error: could not read managed listings: {error}", file=sys.stderr)
        return 1
    run = analyze_cards(
        cards,
        client,
        managed_listings=managed_listings,
        execute=args.execute,
        set_codes={card.set_code for card in cards},
        verbose=args.verbose,
        use_color=sys.stdout.isatty(),
    )
    output_dir = default_output_dir(args.input_csv)
    try:
        write_reports(run, args.input_csv, output_dir)
    except OSError as error:
        print(f"error: could not write reports: {error}", file=sys.stderr)
        return 2

    print(f"wrote reports to {output_dir}")
    print(_format_run_summary(run, use_color=sys.stdout.isatty()))
    return 0 if run.complete and run.execution_complete else 1


def _parse_manabox_row(row, source_csv_row):
    def required(column):
        value = (row.get(column) or "").strip()
        if not value:
            raise ManaBoxInputError(f"row {source_csv_row}: {column} must not be empty")
        return value

    try:
        quantity = int(required("Quantity"))
    except ValueError as error:
        raise ManaBoxInputError(
            f"row {source_csv_row}: Quantity must be an integer"
        ) from error
    if quantity <= 0:
        raise ManaBoxInputError(f"row {source_csv_row}: Quantity must be positive")

    scryfall_id = required("Scryfall ID")
    try:
        uuid.UUID(scryfall_id)
    except ValueError as error:
        raise ManaBoxInputError(
            f"row {source_csv_row}: Scryfall ID must be a UUID"
        ) from error

    return ManaBoxRow(
        source_csv_row=source_csv_row,
        name=required("Name"),
        set_code=required("Set code").casefold(),
        set_name=required("Set name"),
        collector_number=required("Collector number"),
        finish=required("Foil").casefold(),
        rarity=required("Rarity").casefold(),
        quantity=quantity,
        scryfall_id=scryfall_id.casefold(),
        misprint=_parse_bool(required("Misprint"), source_csv_row, "Misprint"),
        altered=_parse_bool(required("Altered"), source_csv_row, "Altered"),
        condition=required("Condition").casefold(),
        language=required("Language").casefold(),
    )


def _parse_bool(value, source_csv_row, column):
    normalized = value.casefold()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise ManaBoxInputError(f"row {source_csv_row}: {column} must be true or false")


def _support_error(card):
    if card.language not in ("en", "english"):
        return f"unsupported language: {card.language}"
    if card.misprint:
        return "misprinted cards require manual review"
    if card.altered:
        return "altered cards require manual review"
    if card.finish not in SUPPORTED_FINISHES:
        return f"unsupported finish: {card.finish}"
    if card.condition not in CONDITION_CODES:
        return f"unsupported condition: {card.condition}"
    return None


def _listing_preview(card, cached, inventory):
    condition_code = CONDITION_CODES.get(card.condition.casefold())
    candidates = (
        inventory.candidates(cached.fetch_card_id, condition_code)
        if cached.fetch_card_id and condition_code
        else ()
    )
    exact_listings = tuple(
        sorted(
            (
                listing
                for listing in candidates
                if listing.scryfall_id.casefold() == card.scryfall_id.casefold()
                and listing.set_id == cached.fetch_set_id
                and listing.finish.casefold() == card.finish.casefold()
            ),
            key=lambda listing: (
                listing.listing_id is None,
                listing.listing_id or 0,
            ),
        )
    )

    if len(exact_listings) != len(candidates):
        return (
            ListingAction.REVIEW,
            "managed listing identity did not match the resolved card",
            exact_listings,
        )
    if len(exact_listings) > 1:
        return (
            ListingAction.REVIEW,
            "multiple exact active managed listings exist",
            exact_listings,
        )
    if cached.decision == Decision.REVIEW:
        return (
            ListingAction.REVIEW,
            "pricing decision requires review",
            exact_listings,
        )
    if cached.decision == Decision.DISCARD:
        return (
            ListingAction.NONE,
            "pricing decision is DISCARD",
            exact_listings,
        )
    if exact_listings:
        existing_listing = exact_listings[0]
        update_price = _price_for_existing_listing(
            existing_listing.listed_price_nzd,
            cached.suggested_price_nzd,
        )
        reason = (
            "one exact simulated managed listing exists"
            if existing_listing.simulated
            else "one exact active managed listing exists"
        )
        if update_price < existing_listing.listed_price_nzd:
            reason += (
                f"; price will decrease from NZ${existing_listing.listed_price_nzd:.2f} "
                f"to NZ${update_price:.2f}"
            )
        else:
            reason += f"; existing NZ${existing_listing.listed_price_nzd:.2f} price is preserved"
        return (
            ListingAction.UPDATE,
            reason,
            exact_listings,
        )
    return (
        ListingAction.CREATE,
        "no exact active managed listing exists",
        exact_listings,
    )


def _price_for_existing_listing(existing_price, suggested_price):
    if suggested_price is None or suggested_price >= existing_price:
        return existing_price
    reduction = existing_price - suggested_price
    material_reduction = max(
        MINIMUM_PRICE_REDUCTION_NZD,
        suggested_price * MATERIAL_PRICE_REDUCTION_RATE,
    )
    if reduction >= material_reduction:
        return suggested_price
    return existing_price


def _record(
    card,
    cached,
    listing_action,
    listing_action_reason,
    existing_listings,
):
    return AnalysisRecord(
        stack_position=card.stack_position,
        source_csv_row=card.source_csv_row,
        quantity_index=card.quantity_index,
        name=card.name,
        set_code=card.set_code,
        collector_number=card.collector_number,
        finish=card.finish,
        condition=card.condition,
        scryfall_id=card.scryfall_id,
        fetch_card_id=cached.fetch_card_id,
        fetch_set_id=cached.fetch_set_id,
        decision=cached.decision,
        decision_reason=cached.decision_reason,
        market_price_nzd=cached.market_price_nzd,
        supported_local_price_nzd=cached.supported_local_price_nzd,
        better_condition_lowest_price_nzd=(cached.better_condition_lowest_price_nzd),
        suggested_price_nzd=cached.suggested_price_nzd,
        local_listing_count=cached.local_listing_count,
        local_copy_count=cached.local_copy_count,
        all_condition_local_seller_count=(cached.all_condition_local_seller_count),
        all_condition_local_copy_count=(cached.all_condition_local_copy_count),
        lowest_local_price_nzd=cached.lowest_local_price_nzd,
        price_ladder=cached.price_ladder or {},
        listing_action=listing_action,
        listing_action_reason=listing_action_reason,
        existing_listings=existing_listings,
    )


def _record_payload(record):
    existing_listings = [
        {
            "listing_id": listing.listing_id,
            "remaining_quantity": listing.remaining_quantity,
            "listed_price_nzd": f"{listing.listed_price_nzd:.2f}",
            "simulated": listing.simulated,
        }
        for listing in record.existing_listings
    ]
    return {
        "stack_position": record.stack_position,
        "source_csv_row": record.source_csv_row,
        "quantity_index": record.quantity_index,
        "name": record.name,
        "set_code": record.set_code,
        "collector_number": record.collector_number,
        "finish": record.finish,
        "condition": record.condition,
        "scryfall_id": record.scryfall_id,
        "fetch_card_id": record.fetch_card_id,
        "fetch_set_id": record.fetch_set_id,
        "decision": record.decision.value,
        "decision_reason": record.decision_reason,
        "market_price_nzd": _decimal_string(record.market_price_nzd),
        "supported_local_price_nzd": _decimal_string(record.supported_local_price_nzd),
        "better_condition_lowest_price_nzd": _decimal_string(
            record.better_condition_lowest_price_nzd
        ),
        "suggested_price_nzd": _decimal_string(
            record.suggested_price_nzd, two_decimal_places=True
        ),
        "local_listing_count": record.local_listing_count,
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
        "listing_action": record.listing_action.value,
        "listing_action_reason": record.listing_action_reason,
        "existing_listing_count": len(existing_listings),
        "existing_copy_count": sum(
            listing.remaining_quantity for listing in record.existing_listings
        ),
        "existing_listings": existing_listings,
        "mutation_key": record.mutation_key,
        "mutation_status": record.mutation_status.value,
        "mutation_listing_id": record.mutation_listing_id,
        "mutation_quantity": record.mutation_quantity,
        "mutation_price_nzd": _decimal_string(
            record.mutation_price_nzd, two_decimal_places=True
        ),
        "mutation_error": record.mutation_error,
    }


def _format_console_record(record, total, *, use_color=False):
    parts = [
        f"[{record.stack_position}/{total}]",
        record.name,
        (
            f"[{record.set_code.upper()} {record.collector_number} "
            f"{record.finish} {record.condition}]"
        ),
        record.decision.value,
        f"— {record.decision_reason}",
    ]
    if record.market_price_nzd is not None:
        parts.append(f"(market NZ${record.market_price_nzd:.2f}")
        if record.supported_local_price_nzd is not None:
            parts.append(f"supported floor NZ${record.supported_local_price_nzd:.2f}")
        if record.better_condition_lowest_price_nzd is not None:
            parts.append(
                f"better condition NZ${record.better_condition_lowest_price_nzd:.2f}"
            )
        if record.suggested_price_nzd is not None:
            parts.append(f"suggested NZ${record.suggested_price_nzd:.2f}")
        parts.append(
            f"local {record.local_listing_count} listings/"
            f"{record.local_copy_count} copies in condition, "
            f"{record.all_condition_local_seller_count} sellers/"
            f"{record.all_condition_local_copy_count} copies across all conditions)"
        )
    parts.append(f"| {record.listing_action.value} — {record.listing_action_reason}")
    if len(record.existing_listings) == 1:
        listing = record.existing_listings[0]
        identifier = (
            "simulated" if listing.listing_id is None else f"#{listing.listing_id}"
        )
        parts.append(
            f"(existing {identifier} "
            f"qty {listing.remaining_quantity} "
            f"at NZ${listing.listed_price_nzd:.2f})"
        )
    elif record.existing_listings:
        parts.append(
            f"({len(record.existing_listings)} existing listings/"
            f"{sum(listing.remaining_quantity for listing in record.existing_listings)} "
            "copies)"
        )
    if record.mutation_status != MutationStatus.SKIPPED:
        transition = f"| {record.mutation_status.value}"
        if record.mutation_quantity is not None:
            transition += f" qty {record.mutation_quantity}"
        if record.mutation_price_nzd is not None:
            transition += f" at NZ${record.mutation_price_nzd:.2f}"
        parts.append(transition)
        if record.mutation_error:
            parts.append(f"— {record.mutation_error}")
    line = " ".join(parts)
    if use_color:
        return f"{DECISION_COLORS[record.decision]}{line}{ANSI_RESET}"
    return line


def _format_run_summary(run, *, use_color=False):
    if run.execution_mode == "execute":
        included_status = MutationStatus.SUCCEEDED
        action = "listed"
        new_card_action = "created"
    else:
        included_status = MutationStatus.PLANNED
        action = "planned for listing"
        new_card_action = "planned"
    included_records = [
        record for record in run.records if record.mutation_status == included_status
    ]
    unique_new_card_count = len(
        {
            record.name.casefold()
            for record in included_records
            if record.listing_action == ListingAction.CREATE
        }
    )
    total_value = sum(
        (record.mutation_price_nzd or Decimal("0") for record in included_records),
        Decimal("0"),
    )
    card_label = "card" if len(included_records) == 1 else "cards"
    new_card_label = "card" if unique_new_card_count == 1 else "cards"
    line = (
        f"[summary] {len(included_records)} {card_label} {action} — "
        f"{unique_new_card_count} unique new {new_card_label} {new_card_action} — "
        f"total value NZ${total_value:.2f}"
    )
    if use_color:
        return f"{SUMMARY_COLOR}{line}{ANSI_RESET}"
    return line


def _decimal_string(value, *, two_decimal_places=False):
    if value is None:
        return None
    if two_decimal_places:
        return f"{value:.2f}"
    return str(value)


def _positive_int(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def _nonnegative_int(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be non-negative")
    return parsed
