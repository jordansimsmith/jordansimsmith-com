import csv
import json
from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal

import pytest

import analyze as analyze_module
from analyze import (
    Decision,
    ListingAction,
    ManaBoxInputError,
    MutationStatus,
    analyze_cards,
    decide,
    load_physical_cards,
    main,
    parse_args,
    write_reports,
)
from fetchtcg_client import (
    FetchTcgRequestError,
    ListingUpsertResult,
    ManagedListing,
    MarketSnapshot,
    PriceTier,
    RunSafetyStop,
)


CSV_HEADER = (
    "Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,ManaBox ID,"
    "Scryfall ID,Purchase price,Misprint,Altered,Condition,Language,"
    "Purchase price currency,Added\n"
)


def _csv_row(
    name,
    set_code,
    set_name,
    collector_number,
    quantity,
    scryfall_id,
    *,
    finish="normal",
    condition="near_mint",
    language="en",
    misprint="false",
    altered="false",
):
    return (
        f"{name},{set_code},{set_name},{collector_number},{finish},common,"
        f"{quantity},1,{scryfall_id},0.27,{misprint},{altered},{condition},"
        f"{language},USD,2026-07-28T06:14:37Z\n"
    )


def _write_scan(path):
    path.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
        )
        + _csv_row(
            "Inventor's Goggles",
            "ddu",
            "Duel Decks: Elves vs. Inventors",
            "55",
            1,
            "8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
        )
        + _csv_row(
            "Inventor's Goggles",
            "ddu",
            "Duel Decks: Elves vs. Inventors",
            "55",
            2,
            "8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
        )
    )


def _snapshot(
    market_price,
    listing_count=0,
    *,
    copy_count=None,
    all_condition_seller_count=None,
    all_condition_copy_count=None,
    fetch_card_id="mtg_244_c_dtk_normal",
    fetch_set_id=2648,
):
    copy_count = (3 if listing_count else 0) if copy_count is None else copy_count
    all_condition_seller_count = (
        listing_count
        if all_condition_seller_count is None
        else all_condition_seller_count
    )
    all_condition_copy_count = (
        copy_count if all_condition_copy_count is None else all_condition_copy_count
    )
    ladder = (
        {
            Decimal("0.40"): PriceTier(
                listing_count=listing_count,
                copy_count=copy_count,
            )
        }
        if listing_count
        else {}
    )
    return MarketSnapshot(
        fetch_card_id=fetch_card_id,
        fetch_set_id=fetch_set_id,
        market_price_nzd=Decimal(market_price),
        local_listing_count=listing_count,
        local_copy_count=copy_count,
        all_condition_local_seller_count=all_condition_seller_count,
        all_condition_local_copy_count=all_condition_copy_count,
        lowest_local_price_nzd=Decimal("0.40") if listing_count else None,
        price_ladder=ladder,
    )


def _managed_listing(
    *,
    listing_id=123,
    fetch_card_id="mtg_244_c_dtk_normal",
    scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
    set_id=2648,
    finish="normal",
    condition="raw-nm",
    quantity=2,
    price="1.25",
):
    return ManagedListing(
        listing_id=listing_id,
        fetch_card_id=fetch_card_id,
        scryfall_id=scryfall_id,
        set_id=set_id,
        finish=finish,
        condition=condition,
        remaining_quantity=quantity,
        listed_price_nzd=Decimal(price),
    )


def _write_single_scan(path):
    path.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
        )
    )


def test_load_physical_cards_reverses_rows_and_expands_quantities(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)

    cards = load_physical_cards(scan)

    assert [card.stack_position for card in cards] == [1, 2, 3, 4]
    assert [card.source_csv_row for card in cards] == [4, 4, 3, 2]
    assert [card.quantity_index for card in cards] == [1, 2, 1, 1]
    assert [card.name for card in cards] == [
        "Inventor's Goggles",
        "Inventor's Goggles",
        "Inventor's Goggles",
        "Spidersilk Net",
    ]


def test_load_physical_cards_applies_limit_after_expansion(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)

    cards = load_physical_cards(scan, limit=2)

    assert len(cards) == 2
    assert [card.stack_position for card in cards] == [1, 2]
    assert [card.quantity_index for card in cards] == [1, 2]


def test_load_physical_cards_applies_offset_before_limit(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)

    within_quantity = load_physical_cards(scan, offset=1, limit=1)
    cards = load_physical_cards(scan, offset=2, limit=1)

    assert within_quantity[0].stack_position == 2
    assert within_quantity[0].source_csv_row == 4
    assert within_quantity[0].quantity_index == 2
    assert len(cards) == 1
    assert cards[0].stack_position == 3
    assert cards[0].stack_size == 4
    assert cards[0].source_csv_row == 3
    assert cards[0].quantity_index == 1


def test_load_physical_cards_offset_preserves_full_console_position(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    messages = []

    analyze_cards(
        load_physical_cards(scan, offset=3),
        _FakeClient([_snapshot("0.60")]),
        output=messages.append,
    )

    assert messages[0].startswith("[4/4]")


@pytest.mark.parametrize(
    ("offset", "message"),
    [
        (-1, "non-negative"),
        (4, "smaller than"),
    ],
)
def test_load_physical_cards_rejects_invalid_offset(tmp_path, offset, message):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)

    with pytest.raises(ManaBoxInputError, match=message):
        load_physical_cards(scan, offset=offset)


@pytest.mark.parametrize(
    (
        "market_price",
        "listing_count",
        "copy_count",
        "all_condition_seller_count",
        "all_condition_copy_count",
        "expected",
    ),
    [
        ("0.2499", 0, 0, 0, 0, Decision.DISCARD),
        ("0.25", 0, 0, 0, 0, Decision.LIST),
        ("0.25", 0, 0, 1, 1, Decision.DISCARD),
        ("0.33", 0, 0, 0, 0, Decision.LIST),
        ("0.33", 0, 0, 1, 1, Decision.DISCARD),
        ("0.3301", 9, 100, 5, 100, Decision.LIST),
        ("0.3301", 0, 0, 6, 6, Decision.DISCARD),
        ("0.4999", 2, 100, 5, 100, Decision.LIST),
        ("0.4999", 0, 0, 6, 6, Decision.DISCARD),
        ("0.50", 0, 0, 100, 100, Decision.LIST),
    ],
)
def test_decide_applies_price_and_stock_boundaries(
    market_price,
    listing_count,
    copy_count,
    all_condition_seller_count,
    all_condition_copy_count,
    expected,
):
    assert (
        decide(
            _snapshot(
                market_price,
                listing_count,
                copy_count=copy_count,
                all_condition_seller_count=all_condition_seller_count,
                all_condition_copy_count=all_condition_copy_count,
            )
        ).decision
        == expected
    )


@pytest.mark.parametrize(
    ("market_price", "expected"),
    [
        ("0.25", Decimal("1")),
        ("1.00", Decimal("1")),
        ("1.01", Decimal("2")),
    ],
)
def test_decide_rounds_list_prices_up_to_whole_dollars(market_price, expected):
    result = decide(_snapshot(market_price))

    assert result.decision == Decision.LIST
    assert result.suggested_price_nzd == expected


@pytest.mark.parametrize("market_price", ["NaN", "Infinity", "-0.01"])
def test_decide_reviews_invalid_market_prices(market_price):
    result = decide(_snapshot(market_price))

    assert result.decision == Decision.REVIEW


class _FakeClient:
    def __init__(self, snapshots):
        self.snapshots = iter(snapshots)
        self.calls = []

    def get_market_snapshot(self, query, condition_code, *, excluded_listing_ids=()):
        self.calls.append((query, condition_code, frozenset(excluded_listing_ids)))
        value = next(self.snapshots)
        if isinstance(value, Exception):
            raise value
        return value


class _InlineClient(_FakeClient):
    def __init__(
        self,
        snapshots,
        *,
        upsert_results=(),
        managed_listings=(),
    ):
        super().__init__(snapshots)
        self.upsert_results = iter(upsert_results)
        self.managed_listings = managed_listings
        self.upsert_calls = []
        self.managed_listing_calls = []
        self.events = []

    def get_market_snapshot(self, query, condition_code, *, excluded_listing_ids=()):
        self.events.append(("market", query.name))
        return super().get_market_snapshot(
            query,
            condition_code,
            excluded_listing_ids=excluded_listing_ids,
        )

    def upsert_managed_listing(self, request, *, expected_listing_id=None):
        self.events.append(("upsert", request.fetch_card_id, request.quantity))
        self.upsert_calls.append((request, expected_listing_id))
        result = next(self.upsert_results)
        if isinstance(result, Exception):
            raise result
        return result

    def get_managed_listings(self, set_codes):
        self.events.append(("verify", frozenset(set_codes)))
        self.managed_listing_calls.append(set(set_codes))
        if isinstance(self.managed_listings, Exception):
            raise self.managed_listings
        return self.managed_listings


def test_analyze_cards_caches_duplicate_analysis_without_collapsing_output(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    cards = load_physical_cards(scan, limit=3)
    client = _FakeClient([_snapshot("0.60")])
    messages = []

    run = analyze_cards(cards, client, verbose=True, output=messages.append)

    assert run.complete is True
    assert len(client.calls) == 1
    assert len(run.records) == 3
    assert [record.stack_position for record in run.records] == [1, 2, 3]
    assert all(record.decision == Decision.LIST for record in run.records)
    assert sum("cache hit" in message for message in messages) == 2


def test_analyze_cards_simulates_duplicate_mutations_inline(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    cards = load_physical_cards(scan, limit=3)
    client = _FakeClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            )
        ]
    )

    run = analyze_cards(cards, client, output=lambda _: None)

    assert [record.listing_action for record in run.records] == [
        ListingAction.CREATE,
        ListingAction.UPDATE,
        ListingAction.UPDATE,
    ]
    assert [record.mutation_status for record in run.records] == [
        MutationStatus.PLANNED,
        MutationStatus.PLANNED,
        MutationStatus.PLANNED,
    ]
    assert [record.mutation_quantity for record in run.records] == [1, 2, 3]
    assert len(client.calls) == 1


def test_analyze_cards_previews_create_without_exact_managed_listing(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[],
        output=lambda _: None,
    )

    assert run.records[0].listing_action == ListingAction.CREATE
    assert run.records[0].existing_listings == ()


def test_analyze_cards_previews_update_with_one_exact_managed_listing(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[_managed_listing()],
        output=lambda _: None,
    )

    record = run.records[0]
    assert record.listing_action == ListingAction.UPDATE
    assert record.existing_listings[0].listing_id == 123
    assert record.existing_listings[0].simulated is False


def test_analyze_cards_ignores_owned_stock_for_pricing_decision(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    client = _FakeClient([_snapshot("0.26")])

    run = analyze_cards(
        load_physical_cards(scan),
        client,
        managed_listings=[_managed_listing(listing_id=123, quantity=10)],
        output=lambda _: None,
    )

    record = run.records[0]
    assert client.calls[0][2] == frozenset({123})
    assert record.local_listing_count == 0
    assert record.decision == Decision.LIST
    assert record.listing_action == ListingAction.UPDATE


def test_analyze_cards_previews_none_for_discard_but_reports_listing(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.10")]),
        managed_listings=[_managed_listing()],
        output=lambda _: None,
    )

    record = run.records[0]
    assert record.decision == Decision.DISCARD
    assert record.listing_action == ListingAction.NONE
    assert record.existing_listings[0].listing_id == 123
    assert record.existing_listings[0].simulated is False


def test_analyze_cards_reviews_multiple_exact_managed_listings(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[
            _managed_listing(listing_id=123),
            _managed_listing(listing_id=124),
        ],
        output=lambda _: None,
    )

    record = run.records[0]
    assert record.decision == Decision.LIST
    assert record.listing_action == ListingAction.REVIEW
    assert "multiple" in record.listing_action_reason


def test_analyze_cards_ignores_listing_with_different_condition(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[_managed_listing(condition="raw-lp")],
        output=lambda _: None,
    )

    assert run.records[0].listing_action == ListingAction.CREATE


@pytest.mark.parametrize(
    ("manabox_condition", "fetch_condition"),
    [
        ("mint", "raw-m"),
        ("near_mint", "raw-nm"),
        ("excellent", "raw-lp"),
        ("good", "raw-mp"),
        ("light_played", "raw-hp"),
        ("played", "raw-hp"),
        ("poor", "raw-d"),
    ],
)
def test_analyze_cards_translates_manabox_conditions(
    tmp_path,
    manabox_condition,
    fetch_condition,
):
    scan = tmp_path / "scan.csv"
    scan.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
            condition=manabox_condition,
        )
    )
    client = _FakeClient([_snapshot("0.60")])

    run = analyze_cards(
        load_physical_cards(scan),
        client,
        output=lambda _: None,
    )

    assert client.calls[0][1] == fetch_condition
    assert run.records[0].condition == manabox_condition
    assert run.records[0].mutation_key.endswith(f"|{fetch_condition}")


@pytest.mark.parametrize(
    "listing",
    [
        _managed_listing(scryfall_id="11111111-1111-1111-1111-111111111111"),
        _managed_listing(set_id=9999),
        _managed_listing(finish="foil"),
    ],
)
def test_analyze_cards_reviews_managed_listing_identity_mismatch(tmp_path, listing):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[listing],
        output=lambda _: None,
    )

    record = run.records[0]
    assert record.listing_action == ListingAction.REVIEW
    assert "identity" in record.listing_action_reason


def test_inline_dry_run_transitions_duplicates_in_stack_order(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    run = analyze_cards(
        load_physical_cards(scan, limit=3),
        _FakeClient(
            [
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_55_c_ddu_normal",
                    fetch_set_id=2782,
                )
            ]
        ),
        output=lambda _: None,
    )
    assert run.execution_mode == "dry_run"
    assert run.execution_complete is True
    assert {record.mutation_status for record in run.records} == {
        MutationStatus.PLANNED
    }
    assert {record.mutation_key for record in run.records} == {
        "mtg_55_c_ddu_normal|raw-nm"
    }
    assert [record.mutation_quantity for record in run.records] == [1, 2, 3]
    assert {record.mutation_price_nzd for record in run.records} == {Decimal("1")}
    assert run.records[1].existing_listings[0].simulated is True
    assert run.records[1].existing_listings[0].listing_id is None


def test_format_run_summary_totals_physical_cards_and_per_card_values(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    run = analyze_cards(
        load_physical_cards(scan, limit=3),
        _FakeClient(
            [
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_55_c_ddu_normal",
                    fetch_set_id=2782,
                )
            ]
        ),
        output=lambda _: None,
    )

    assert analyze_module._format_run_summary(run, use_color=True) == (
        "\033[34m[summary] 3 cards planned for listing — total value NZ$3.00\033[0m"
    )

    execute_run = replace(
        run,
        records=[
            replace(
                run.records[0],
                mutation_status=MutationStatus.SUCCEEDED,
                mutation_price_nzd=Decimal("1.25"),
            ),
            replace(
                run.records[1],
                mutation_status=MutationStatus.FAILED,
                mutation_price_nzd=Decimal("2"),
            ),
            replace(
                run.records[2],
                mutation_status=MutationStatus.SUCCEEDED,
                mutation_price_nzd=Decimal("0.75"),
            ),
        ],
        execution_mode="execute",
    )

    assert analyze_module._format_run_summary(execute_run) == (
        "[summary] 2 cards listed — total value NZ$2.00"
    )


def test_inline_execute_posts_each_card_before_analyzing_next_variant(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    client = _InlineClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            ),
            _snapshot("0.60"),
        ],
        upsert_results=[
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=2,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=3,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            ListingUpsertResult(
                listing_id=600,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
        ],
        managed_listings=[
            _managed_listing(
                listing_id=500,
                fetch_card_id="mtg_55_c_ddu_normal",
                scryfall_id="8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
                set_id=2782,
                quantity=3,
                price="1",
            ),
            _managed_listing(listing_id=600, quantity=1, price="1"),
        ],
    )

    run = analyze_cards(
        load_physical_cards(scan),
        client,
        execute=True,
        set_codes={"ddu", "dtk"},
        output=lambda _: None,
    )

    assert client.events == [
        ("market", "Inventor's Goggles"),
        ("upsert", "mtg_55_c_ddu_normal", 1),
        ("upsert", "mtg_55_c_ddu_normal", 2),
        ("upsert", "mtg_55_c_ddu_normal", 3),
        ("market", "Spidersilk Net"),
        ("upsert", "mtg_244_c_dtk_normal", 1),
        ("verify", frozenset({"ddu", "dtk"})),
    ]
    assert client.calls[0][2] == frozenset()
    assert client.calls[1][2] == frozenset({500})
    assert [record.listing_action for record in run.records] == [
        ListingAction.CREATE,
        ListingAction.UPDATE,
        ListingAction.UPDATE,
        ListingAction.CREATE,
    ]
    assert [record.mutation_quantity for record in run.records] == [1, 2, 3, 1]
    assert all(
        record.mutation_status == MutationStatus.SUCCEEDED for record in run.records
    )


def test_inline_dry_run_keeps_set_finish_and_condition_variants_separate(
    tmp_path,
):
    scan = tmp_path / "scan.csv"
    scan.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
        )
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
            finish="foil",
        )
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
            condition="light_played",
        )
        + _csv_row(
            "Inventor's Goggles",
            "ddu",
            "Duel Decks: Elves vs. Inventors",
            "55",
            1,
            "8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
        )
    )
    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient(
            [
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_55_c_ddu_normal",
                    fetch_set_id=2782,
                ),
                _snapshot("0.60"),
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_244_c_dtk_foil",
                ),
                _snapshot("0.60"),
            ]
        ),
        output=lambda _: None,
    )

    assert {record.mutation_key for record in run.records} == {
        "mtg_55_c_ddu_normal|raw-nm",
        "mtg_244_c_dtk_normal|raw-hp",
        "mtg_244_c_dtk_foil|raw-nm",
        "mtg_244_c_dtk_normal|raw-nm",
    }
    assert all(
        record.mutation_status == MutationStatus.PLANNED for record in run.records
    )


def test_inline_processing_reviews_later_identity_collision(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient(
            [
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_collision",
                    fetch_set_id=2782,
                ),
                _snapshot(
                    "0.60",
                    fetch_card_id="mtg_collision",
                    fetch_set_id=2648,
                ),
            ]
        ),
        output=lambda _: None,
    )
    assert run.records[-1].listing_action == ListingAction.REVIEW
    assert run.records[-1].mutation_status == MutationStatus.SKIPPED
    assert "identity" in run.records[-1].listing_action_reason


def test_inline_execute_updates_quantity_and_preserves_existing_price(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    existing = _managed_listing(quantity=2, price="1.25")
    client = _InlineClient(
        [_snapshot("0.60")],
        upsert_results=[
            ListingUpsertResult(
                listing_id=123,
                remaining_quantity=3,
                condition="raw-nm",
                listed_price_nzd=Decimal("1.25"),
            )
        ],
        managed_listings=[_managed_listing(quantity=3, price="1.25")],
    )

    result = analyze_cards(
        load_physical_cards(scan),
        client,
        managed_listings=[existing],
        execute=True,
        set_codes={"dtk"},
        output=lambda _: None,
    )

    request, expected_listing_id = client.upsert_calls[0]
    assert request.fetch_card_id == "mtg_244_c_dtk_normal"
    assert request.condition == "raw-nm"
    assert request.quantity == 3
    assert request.listed_price_nzd == Decimal("1.25")
    assert expected_listing_id == 123
    assert result.records[0].mutation_status == MutationStatus.SUCCEEDED
    assert result.records[0].mutation_listing_id == 123
    assert result.execution_complete is True
    assert client.managed_listing_calls == [{"dtk"}]


def test_inline_execute_creates_different_condition_without_updating_existing(
    tmp_path,
):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    other_condition = _managed_listing(condition="raw-lp")
    created = _managed_listing(
        listing_id=999,
        condition="raw-nm",
        quantity=1,
        price="1",
    )
    client = _InlineClient(
        [_snapshot("0.60")],
        upsert_results=[
            ListingUpsertResult(
                listing_id=999,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            )
        ],
        managed_listings=[other_condition, created],
    )

    result = analyze_cards(
        load_physical_cards(scan),
        client,
        managed_listings=[other_condition],
        execute=True,
        set_codes={"dtk"},
        output=lambda _: None,
    )

    request, expected_listing_id = client.upsert_calls[0]
    assert request.condition == "raw-nm"
    assert request.quantity == 1
    assert expected_listing_id is None
    assert result.records[0].mutation_listing_id == 999
    assert result.records[0].mutation_status == MutationStatus.SUCCEEDED


def test_inline_execute_fails_when_final_identity_verification_differs(
    tmp_path,
):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    client = _InlineClient(
        [_snapshot("0.60")],
        upsert_results=[
            ListingUpsertResult(
                listing_id=999,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            )
        ],
        managed_listings=[
            _managed_listing(
                listing_id=999,
                set_id=9999,
                quantity=1,
                price="1",
            )
        ],
    )

    result = analyze_cards(
        load_physical_cards(scan),
        client,
        execute=True,
        set_codes={"dtk"},
        output=lambda _: None,
    )

    assert result.records[0].mutation_status == MutationStatus.FAILED
    assert "identity" in result.records[0].mutation_error
    assert result.execution_complete is False


def test_inline_execute_marks_all_posts_failed_when_final_refresh_fails(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    client = _InlineClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            )
        ],
        upsert_results=[
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=2,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
        ],
        managed_listings=FetchTcgRequestError("refresh failed"),
    )

    result = analyze_cards(
        load_physical_cards(scan, limit=2),
        client,
        execute=True,
        set_codes={"ddu"},
        output=lambda _: None,
    )

    assert [record.mutation_status for record in result.records] == [
        MutationStatus.FAILED,
        MutationStatus.FAILED,
    ]
    assert all(
        "could not verify mutations: refresh failed" == record.mutation_error
        for record in result.records
    )
    assert result.execution_error == "could not verify mutations: refresh failed"
    assert client.managed_listing_calls == [{"ddu"}]


def test_inline_execute_applies_final_state_failure_to_all_matching_posts(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    client = _InlineClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            )
        ],
        upsert_results=[
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=2,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
        ],
        managed_listings=[
            _managed_listing(
                listing_id=500,
                fetch_card_id="mtg_55_c_ddu_normal",
                scryfall_id="8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
                set_id=2782,
                quantity=1,
                price="1",
            )
        ],
    )

    result = analyze_cards(
        load_physical_cards(scan, limit=2),
        client,
        execute=True,
        set_codes={"ddu"},
        output=lambda _: None,
    )

    assert [record.mutation_status for record in result.records] == [
        MutationStatus.FAILED,
        MutationStatus.FAILED,
    ]
    assert all(
        record.mutation_error == "mutation verification quantity did not match"
        for record in result.records
    )


def test_inline_execute_stops_before_analyzing_next_card_after_write_failure(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    client = _InlineClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            )
        ],
        upsert_results=[FetchTcgRequestError("write failed")],
    )

    result = analyze_cards(
        load_physical_cards(scan),
        client,
        execute=True,
        set_codes={"ddu", "dtk"},
        output=lambda _: None,
    )

    assert len(client.upsert_calls) == 1
    assert client.managed_listing_calls == []
    assert len(result.records) == 1
    assert result.records[0].mutation_status == MutationStatus.FAILED
    assert result.complete is False
    assert result.execution_complete is False
    assert result.execution_error == "write failed"


def test_inline_execute_verifies_prior_success_after_later_write_failure(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    client = _InlineClient(
        [
            _snapshot(
                "0.60",
                fetch_card_id="mtg_55_c_ddu_normal",
                fetch_set_id=2782,
            )
        ],
        upsert_results=[
            ListingUpsertResult(
                listing_id=500,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            ),
            FetchTcgRequestError("second write failed"),
        ],
        managed_listings=[
            _managed_listing(
                listing_id=500,
                fetch_card_id="mtg_55_c_ddu_normal",
                scryfall_id="8fc62d61-2bc0-4b84-a0e9-5a01fcd4ef92",
                set_id=2782,
                quantity=1,
                price="1",
            )
        ],
    )

    result = analyze_cards(
        load_physical_cards(scan),
        client,
        execute=True,
        set_codes={"ddu", "dtk"},
        output=lambda _: None,
    )

    assert len(result.records) == 2
    assert result.records[0].mutation_status == MutationStatus.SUCCEEDED
    assert result.records[1].mutation_status == MutationStatus.FAILED
    assert result.execution_error == "second write failed"
    assert client.managed_listing_calls == [{"ddu", "dtk"}]


@pytest.mark.parametrize(
    ("result", "expected_color", "expected_action"),
    [
        (_snapshot("0.60"), "\033[32m", "CREATE"),
        (_snapshot("0.10"), "\033[31m", "NONE"),
        (FetchTcgRequestError("failed"), "\033[33m", "REVIEW"),
    ],
)
def test_analyze_cards_colors_terminal_decisions(
    tmp_path, result, expected_color, expected_action
):
    scan = tmp_path / "scan.csv"
    scan.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
        )
    )
    messages = []

    analyze_cards(
        load_physical_cards(scan),
        _FakeClient([result]),
        use_color=True,
        output=messages.append,
    )

    assert messages[-1].startswith(expected_color)
    assert messages[-1].endswith("\033[0m")
    assert "[DTK 244 normal near_mint]" in messages[-1]
    assert f"| {expected_action} —" in messages[-1]


def test_analyze_cards_caches_manabox_conditions_with_same_fetch_mapping(
    tmp_path,
):
    scan = tmp_path / "scan.csv"
    card = (
        "Spidersilk Net",
        "dtk",
        "Dragons of Tarkir",
        "244",
        1,
        "9561b47c-b863-463a-8a10-56fede2cb42c",
    )
    scan.write_text(
        CSV_HEADER
        + _csv_row(*card, condition="light_played", language="en")
        + _csv_row(*card, condition="played", language="english")
    )
    client = _FakeClient([_snapshot("0.60")])

    run = analyze_cards(
        load_physical_cards(scan),
        client,
        output=lambda _: None,
    )

    assert run.complete is True
    assert len(run.records) == 2
    assert len(client.calls) == 1
    assert {record.mutation_key for record in run.records} == {
        "mtg_244_c_dtk_normal|raw-hp"
    }


def test_analyze_cards_marks_unsupported_card_for_review_without_request(tmp_path):
    scan = tmp_path / "scan.csv"
    scan.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            1,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
            language="ja",
        )
    )
    client = _FakeClient([])

    run = analyze_cards(load_physical_cards(scan), client, output=lambda _: None)

    assert client.calls == []
    assert run.records[0].decision == Decision.REVIEW
    assert run.records[0].listing_action == ListingAction.REVIEW
    assert "language" in run.records[0].decision_reason


def test_analyze_cards_stops_safely_and_keeps_partial_records(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    cards = load_physical_cards(scan)
    client = _FakeClient([_snapshot("0.60"), RunSafetyStop("access forbidden")])

    run = analyze_cards(cards, client, output=lambda _: None)

    assert run.complete is False
    assert run.error == "access forbidden"
    assert len(run.records) == 4
    assert run.records[-1].decision == Decision.REVIEW
    assert run.records[-1].listing_action == ListingAction.REVIEW


def test_write_reports_preserves_one_record_per_physical_card(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_scan(scan)
    cards = load_physical_cards(scan, limit=3)
    run = analyze_cards(
        cards,
        _FakeClient([_snapshot("0.60")]),
        output=lambda _: None,
    )
    output_dir = tmp_path / "output"

    write_reports(
        run,
        scan,
        output_dir,
        generated_at=datetime(2026, 7, 28, 10, 0, tzinfo=timezone.utc),
    )

    report = json.loads((output_dir / "report.json").read_text())
    assert [card["stack_position"] for card in report["cards"]] == [1, 2, 3]
    assert report["input_path"] == "scan.csv"
    assert report["schema_version"] == 6
    assert report["execution_mode"] == "dry_run"
    assert report["execution_complete"] is True
    assert [card["listing_action"] for card in report["cards"]] == [
        "CREATE",
        "UPDATE",
        "UPDATE",
    ]
    assert all(card["mutation_status"] == "PLANNED" for card in report["cards"])
    assert [card["mutation_quantity"] for card in report["cards"]] == [1, 2, 3]
    assert report["cards"][0]["existing_listings"] == []
    assert report["cards"][0]["all_condition_local_seller_count"] == 0
    assert report["cards"][0]["all_condition_local_copy_count"] == 0
    assert report["cards"][1]["existing_listings"][0]["simulated"] is True

    with (output_dir / "stack.csv").open(newline="") as file:
        rows = list(csv.DictReader(file))
    assert [row["stack_position"] for row in rows] == ["1", "2", "3"]
    assert "mutation_key" in rows[0]
    assert "mutation_group_key" not in rows[0]
    assert json.loads(rows[0]["existing_listings"]) == []
    assert json.loads(rows[1]["existing_listings"])[0]["simulated"] is True


def test_write_reports_includes_existing_listing_details(tmp_path):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    run = analyze_cards(
        load_physical_cards(scan),
        _FakeClient([_snapshot("0.60")]),
        managed_listings=[_managed_listing()],
        output=lambda _: None,
    )
    output_dir = tmp_path / "output"

    write_reports(run, scan, output_dir)

    report = json.loads((output_dir / "report.json").read_text())
    card = report["cards"][0]
    assert card["listing_action"] == "UPDATE"
    assert card["existing_listing_count"] == 1
    assert card["existing_copy_count"] == 2
    assert card["existing_listings"] == [
        {
            "listing_id": 123,
            "remaining_quantity": 2,
            "listed_price_nzd": "1.25",
            "simulated": False,
        }
    ]
    assert card["mutation_status"] == "PLANNED"
    assert card["mutation_listing_id"] == 123
    assert card["mutation_quantity"] == 3
    assert card["mutation_price_nzd"] == "1.25"

    with (output_dir / "stack.csv").open(newline="") as file:
        row = next(csv.DictReader(file))
    assert json.loads(row["existing_listings"]) == card["existing_listings"]


def test_parse_args_exposes_input_offset_limit_verbose_and_execute():
    args = parse_args(
        [
            "scan.csv",
            "--offset",
            "6",
            "--limit",
            "3",
            "--verbose",
            "--execute",
        ]
    )

    assert str(args.input_csv) == "scan.csv"
    assert args.offset == 6
    assert args.limit == 3
    assert args.verbose is True
    assert args.execute is True
    defaults = parse_args(["scan.csv"])
    assert defaults.offset == 0
    assert defaults.execute is False


def test_main_passes_offset_and_limit_to_loader(monkeypatch):
    captured = {}

    def load_cards(input_path, limit=None, offset=0):
        captured.update(
            input_path=input_path,
            limit=limit,
            offset=offset,
        )
        raise ManaBoxInputError("stop after capture")

    monkeypatch.setattr(analyze_module, "load_physical_cards", load_cards)

    assert main(["scan.csv", "--offset", "6", "--limit", "3"]) == 2
    assert captured == {
        "input_path": analyze_module.Path("scan.csv"),
        "limit": 3,
        "offset": 6,
    }


@pytest.mark.parametrize(
    ("arguments", "expected_upsert_count"),
    [
        ([], 0),
        (["--execute"], 1),
    ],
)
def test_main_only_mutates_with_execute_flag(
    tmp_path,
    monkeypatch,
    capsys,
    arguments,
    expected_upsert_count,
):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)

    class MainClient:
        instance = None

        def __init__(self, **_):
            MainClient.instance = self
            self.upsert_calls = []
            self.managed_listing_reads = 0

        def get_managed_listings(self, _):
            self.managed_listing_reads += 1
            if self.managed_listing_reads == 1:
                return []
            return [
                _managed_listing(
                    listing_id=999,
                    quantity=1,
                    price="1",
                )
            ]

        def get_market_snapshot(self, *_, **__):
            return _snapshot("0.60")

        def upsert_managed_listing(self, request, *, expected_listing_id=None):
            self.upsert_calls.append((request, expected_listing_id))
            return ListingUpsertResult(
                listing_id=999,
                remaining_quantity=1,
                condition="raw-nm",
                listed_price_nzd=Decimal("1"),
            )

    monkeypatch.setattr(analyze_module, "FetchTcgClient", MainClient)
    monkeypatch.setenv("FETCHTCG_TOKEN", "test-token")
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))

    assert main([str(scan), *arguments]) == 0
    assert len(MainClient.instance.upsert_calls) == expected_upsert_count
    assert MainClient.instance.managed_listing_reads == (
        2 if expected_upsert_count else 1
    )
    summary = capsys.readouterr().out.splitlines()[-1]
    if expected_upsert_count:
        assert summary == "[summary] 1 card listed — total value NZ$1.00"
    else:
        assert summary == ("[summary] 1 card planned for listing — total value NZ$1.00")


@pytest.mark.parametrize("token", [None, "", "Bearer already-prefixed"])
def test_main_fails_before_analysis_for_missing_or_malformed_token(
    tmp_path, monkeypatch, token
):
    scan = tmp_path / "scan.csv"
    _write_single_scan(scan)
    monkeypatch.setenv("FETCHTCG_AUTHORIZATION", "Bearer legacy-token")
    if token is None:
        monkeypatch.delenv("FETCHTCG_TOKEN", raising=False)
    else:
        monkeypatch.setenv("FETCHTCG_TOKEN", token)

    assert main([str(scan), "--limit", "1"]) == 1


def test_load_physical_cards_rejects_non_positive_quantity(tmp_path):
    scan = tmp_path / "scan.csv"
    scan.write_text(
        CSV_HEADER
        + _csv_row(
            "Spidersilk Net",
            "dtk",
            "Dragons of Tarkir",
            "244",
            0,
            "9561b47c-b863-463a-8a10-56fede2cb42c",
        )
    )

    with pytest.raises(ManaBoxInputError, match="Quantity"):
        load_physical_cards(scan)
