import csv
import json
from decimal import Decimal

import pytest

from analyze import (
    PricingAnalysisError,
    PricingSignal,
    PricingStatus,
    analyze_all,
    analyze_listing,
    default_output_dir,
    format_summary,
    summarize_records,
    write_reports,
)
from fetchtcg_client import (
    CardDetails,
    CompetitorListing,
    FetchTcgRequestError,
    ManagedListing,
)


def _owned(**overrides):
    values = {
        "listing_id": 100,
        "fetch_card_id": "mtg_244_c_dtk_normal",
        "scryfall_id": "9561b47c-b863-463a-8a10-56fede2cb42c",
        "set_id": 2648,
        "finish": "normal",
        "condition": "raw-nm",
        "remaining_quantity": 2,
        "listed_price_nzd": Decimal("2.00"),
    }
    values.update(overrides)
    return ManagedListing(**values)


def _details(**overrides):
    values = {
        "fetch_card_id": "mtg_244_c_dtk_normal",
        "scryfall_id": "9561b47c-b863-463a-8a10-56fede2cb42c",
        "name": "Spidersilk Net",
        "collector_number": "244",
        "set_id": 2648,
        "finish": "normal",
        "market_price_nzd": Decimal("0.47"),
    }
    values.update(overrides)
    return CardDetails(**values)


def _competitor(
    price,
    quantity=1,
    *,
    listing_id=1,
    seller="seller",
    condition="raw-nm",
):
    return CompetitorListing(
        listing_id=listing_id,
        condition=condition,
        seller_key=seller,
        remaining_quantity=quantity,
        listed_price_nzd=Decimal(str(price)),
    )


class _FakeClient:
    def __init__(
        self,
        managed,
        *,
        details=None,
        competitors=None,
        detail_errors=None,
        competitor_errors=None,
    ):
        self.managed = managed
        self.details = details or {}
        self.competitors = competitors or {}
        self.detail_errors = detail_errors or {}
        self.competitor_errors = competitor_errors or {}
        self.detail_calls = []
        self.competitor_calls = []
        self.excluded_ids = []
        self.request_count = 7

    def get_managed_listings(self):
        return self.managed

    def get_card_details(self, card_id):
        self.detail_calls.append(card_id)
        if card_id in self.detail_errors:
            raise self.detail_errors[card_id]
        return self.details[card_id]

    def get_competitor_listings(self, card_id, *, excluded_listing_ids):
        self.competitor_calls.append(card_id)
        self.excluded_ids.append(frozenset(excluded_listing_ids))
        if card_id in self.competitor_errors:
            raise self.competitor_errors[card_id]
        return self.competitors[card_id]


def test_analyze_listing_uses_second_seller_to_establish_supported_floor():
    record = analyze_listing(
        _owned(),
        _details(),
        [
            _competitor(1.40, listing_id=1, seller="alpha"),
            _competitor(1.50, listing_id=2, seller="beta"),
        ],
    )

    assert record.status == PricingStatus.OVERPRICED
    assert record.immediate_floor_nzd == Decimal("1.40")
    assert record.supported_floor_nzd == Decimal("1.50")
    assert record.suggested_price_nzd == Decimal("1.50")
    assert record.cheaper_seller_count == 2
    assert record.cheaper_copy_count == 2
    assert record.potential_markdown_nzd == Decimal("1.00")


def test_analyze_listing_uses_three_copies_to_establish_supported_floor():
    record = analyze_listing(
        _owned(listed_price_nzd=Decimal("3.00")),
        _details(),
        [_competitor(2.50, quantity=3, seller="alpha")],
    )

    assert record.status == PricingStatus.OVERPRICED
    assert record.supported_floor_nzd == Decimal("2.50")
    assert record.suggested_price_nzd == Decimal("2.50")


def test_analyze_listing_watches_material_one_off_cheaper_stock():
    record = analyze_listing(
        _owned(),
        _details(),
        [_competitor(1.50)],
    )

    assert record.status == PricingStatus.WATCH
    assert record.supported_floor_nzd is None
    assert record.suggested_price_nzd is None


def test_analyze_listing_explains_watch_when_supported_gap_is_not_material():
    record = analyze_listing(
        _owned(listed_price_nzd=Decimal("1.00")),
        _details(),
        [
            _competitor(0.30, listing_id=1, seller="alpha"),
            _competitor(0.76, listing_id=2, seller="beta"),
        ],
    )

    assert record.status == PricingStatus.WATCH
    assert record.supported_floor_nzd == Decimal("0.76")
    assert (
        record.status_reason
        == "owned price materially exceeds the immediate floor, but the gap "
        "to the supported floor is below the material threshold"
    )


def test_analyze_listing_keeps_ten_cent_low_value_gap_competitive():
    record = analyze_listing(
        _owned(
            listed_price_nzd=Decimal("1.00"),
            remaining_quantity=1,
        ),
        _details(),
        [
            _competitor(0.90, listing_id=1, seller="alpha"),
            _competitor(0.90, listing_id=2, seller="beta"),
        ],
    )

    assert record.status == PricingStatus.COMPETITIVE
    assert record.supported_gap_nzd == Decimal("0.10")
    assert record.suggested_price_nzd is None


def test_analyze_listing_treats_exact_twenty_five_cent_gap_as_material():
    record = analyze_listing(
        _owned(listed_price_nzd=Decimal("1.00")),
        _details(),
        [
            _competitor(0.75, listing_id=1, seller="alpha"),
            _competitor(0.75, listing_id=2, seller="beta"),
        ],
    )

    assert record.status == PricingStatus.OVERPRICED
    assert record.suggested_price_nzd == Decimal("0.75")
    assert record.suggested_price_below_nz_1 is True


def test_analyze_listing_applies_five_percent_threshold_to_high_value_card():
    competitive = analyze_listing(
        _owned(listed_price_nzd=Decimal("20.94")),
        _details(),
        [
            _competitor(20, listing_id=1, seller="alpha"),
            _competitor(20, listing_id=2, seller="beta"),
        ],
    )
    overpriced = analyze_listing(
        _owned(listed_price_nzd=Decimal("21.00")),
        _details(),
        [
            _competitor(20, listing_id=1, seller="alpha"),
            _competitor(20, listing_id=2, seller="beta"),
        ],
    )

    assert competitive.status == PricingStatus.COMPETITIVE
    assert overpriced.status == PricingStatus.OVERPRICED


def test_analyze_listing_reports_no_exact_condition_competition():
    record = analyze_listing(
        _owned(condition="raw-lp"),
        _details(),
        [_competitor(1.50, condition="raw-nm")],
    )

    assert record.status == PricingStatus.NO_COMPETITION
    assert record.competitor_listing_count == 0
    assert record.better_condition_cheaper is True
    assert record.better_condition_lowest_price_nzd == Decimal("1.50")


def test_analyze_listing_rejects_mismatched_owned_identity():
    with pytest.raises(PricingAnalysisError, match="Scryfall"):
        analyze_listing(
            _owned(),
            _details(scryfall_id="61844cbe-f4b3-45c6-bf4c-76542de4b195"),
            [],
        )


def test_analyze_all_loads_each_card_once_and_excludes_every_owned_id():
    first = _owned(listing_id=100)
    second = _owned(listing_id=101, condition="raw-lp")
    omitted = _owned(
        listing_id=102,
        fetch_card_id="mtg_other_card",
        scryfall_id="61844cbe-f4b3-45c6-bf4c-76542de4b195",
    )
    client = _FakeClient(
        [first, second, omitted],
        details={first.fetch_card_id: _details()},
        competitors={
            first.fetch_card_id: [
                _competitor(1.50, listing_id=102, seller="current user")
            ]
        },
    )

    run = analyze_all(client, limit=2)

    assert len(run.records) == 2
    assert client.detail_calls == [first.fetch_card_id]
    assert client.competitor_calls == [first.fetch_card_id]
    assert client.excluded_ids == [frozenset({100, 101, 102})]
    assert run.managed_listing_count == 3
    assert run.selected_listing_count == 2


def test_analyze_all_turns_card_failure_into_review_for_each_matching_listing():
    first = _owned(listing_id=100)
    second = _owned(listing_id=101)
    error = FetchTcgRequestError("card payload was invalid")
    client = _FakeClient(
        [first, second],
        detail_errors={first.fetch_card_id: error},
    )

    run = analyze_all(client)

    assert [record.status for record in run.records] == [
        PricingStatus.REVIEW,
        PricingStatus.REVIEW,
    ]
    assert client.detail_calls == [first.fetch_card_id]
    assert client.competitor_calls == []
    assert all(
        record.analysis_error == "card payload was invalid" for record in run.records
    )


def test_summarize_records_builds_status_price_band_and_strong_signal():
    records = [
        analyze_listing(
            _owned(
                listing_id=100,
                listed_price_nzd=Decimal("1.00"),
                remaining_quantity=2,
            ),
            _details(),
            [
                _competitor(0.75, listing_id=1, seller="alpha"),
                _competitor(0.75, listing_id=2, seller="beta"),
            ],
        ),
        analyze_listing(
            _owned(
                listing_id=101,
                listed_price_nzd=Decimal("2.00"),
                remaining_quantity=2,
            ),
            _details(),
            [
                _competitor(1.50, listing_id=3, seller="alpha"),
                _competitor(1.50, listing_id=4, seller="beta"),
            ],
        ),
        analyze_listing(
            _owned(
                listing_id=102,
                listed_price_nzd=Decimal("3.00"),
                remaining_quantity=1,
            ),
            _details(),
            [
                _competitor(3, listing_id=5, seller="alpha"),
                _competitor(3, listing_id=6, seller="beta"),
            ],
        ),
    ]

    summary = summarize_records(records)

    assert summary.pricing_signal == PricingSignal.STRONG
    assert summary.overpriced_listing_share == Decimal("2") / Decimal("3")
    assert summary.overpriced_copy_share == Decimal("4") / Decimal("5")
    assert summary.statuses["OVERPRICED"].listing_count == 2
    assert summary.price_bands["nz_1"].listed_value_nzd == Decimal("2.00")
    assert summary.price_bands["nz_2"].potential_markdown_nzd == Decimal("1.00")
    assert "does not measure sales velocity" in summary.diagnosis


def test_summarize_records_emits_limited_and_insufficient_signals():
    competitive = analyze_listing(
        _owned(),
        _details(),
        [
            _competitor(2, listing_id=1, seller="alpha"),
            _competitor(2, listing_id=2, seller="beta"),
        ],
    )

    limited = summarize_records([competitive])
    insufficient = summarize_records(
        analyze_all(
            _FakeClient(
                [_owned()],
                detail_errors={"mtg_244_c_dtk_normal": FetchTcgRequestError("invalid")},
            )
        ).records
    )

    assert limited.pricing_signal == PricingSignal.LIMITED
    assert insufficient.pricing_signal == PricingSignal.INSUFFICIENT_DATA


def test_write_reports_serializes_json_and_priority_sorted_csv(tmp_path):
    overpriced = analyze_listing(
        _owned(listing_id=200),
        _details(),
        [
            _competitor(1.50, listing_id=1, seller="alpha"),
            _competitor(1.50, listing_id=2, seller="beta"),
        ],
    )
    competitive = analyze_listing(
        _owned(listing_id=100),
        _details(),
        [
            _competitor(2, listing_id=3, seller="alpha"),
            _competitor(2, listing_id=4, seller="beta"),
        ],
    )
    client = _FakeClient([], details={}, competitors={})
    run = analyze_all(client)
    run = run.with_records([competitive, overpriced])

    json_path, csv_path = write_reports(run, tmp_path)

    report = json.loads(json_path.read_text())
    with csv_path.open(newline="") as file:
        rows = list(csv.DictReader(file))
    assert report["schema_version"] == 1
    assert report["listings"][0]["listing_id"] == 100
    assert report["listings"][1]["suggested_price_nzd"] == "1.50"
    assert [int(row["listing_id"]) for row in rows] == [200, 100]
    assert rows[0]["potential_markdown_nzd"] == "1.00"


def test_default_output_dir_uses_bazel_workspace_and_utc_timestamp(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))

    output = default_output_dir()

    assert output.parent == tmp_path / "tmp/tcg-lister"
    assert output.name.startswith("pricing-")


def test_format_summary_includes_statuses_bands_and_top_opportunity():
    overpriced = analyze_listing(
        _owned(listing_id=200),
        _details(),
        [
            _competitor(1.50, listing_id=1, seller="alpha"),
            _competitor(1.50, listing_id=2, seller="beta"),
        ],
    )
    run = analyze_all(_FakeClient([])).with_records([overpriced])

    output = format_summary(run, "tmp/report", use_color=True)

    assert output.startswith("\033[31mPricing signal: STRONG\033[0m")
    assert "Statuses: OVERPRICED 1/NZ$4.00" in output
    assert "Price bands:" in output
    assert "#200 Spidersilk Net: NZ$2.00 -> NZ$1.50" in output
