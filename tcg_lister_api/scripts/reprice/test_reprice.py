import json
import signal
from datetime import datetime, timezone
from decimal import Decimal

import pytest

import reprice as reprice_module
from fetchtcg_client import (
    CardDetails,
    CompetitorListing,
    ListingUpsertResult,
    ManagedListing,
    RunSafetyStop,
)
from reprice import (
    ControlledTermination,
    Decision,
    MutationStatus,
    calculate_pricing,
    default_output_dir,
    parse_args,
    run_repricing,
)


SCRYFALL_ID = "9561b47c-b863-463a-8a10-56fede2cb42c"
GENERATED_AT = datetime(2026, 8, 5, 1, 0, tzinfo=timezone.utc)


def _managed(
    listing_id,
    *,
    fetch_card_id=None,
    condition="raw-nm",
    quantity=2,
    price="1.25",
):
    return ManagedListing(
        listing_id=listing_id,
        fetch_card_id=fetch_card_id or f"mtg_{listing_id}_c_tst_normal",
        scryfall_id=SCRYFALL_ID,
        set_id=100,
        finish="normal",
        condition=condition,
        remaining_quantity=quantity,
        listed_price_nzd=Decimal(price),
    )


def _card(listing, market_price="0.60"):
    return CardDetails(
        fetch_card_id=listing.fetch_card_id,
        scryfall_id=listing.scryfall_id,
        name=f"Card {listing.listing_id}",
        collector_number=str(listing.listing_id),
        set_id=listing.set_id,
        finish=listing.finish,
        market_price_nzd=Decimal(market_price),
    )


def _competitor(
    listing_id,
    price,
    *,
    condition="raw-nm",
    seller="seller",
    quantity=1,
):
    return CompetitorListing(
        listing_id=listing_id,
        condition=condition,
        seller_key=seller,
        remaining_quantity=quantity,
        listed_price_nzd=Decimal(price),
    )


class _FakeClient:
    def __init__(
        self,
        managed_listings,
        *,
        market_prices=None,
        competitors=None,
        failures=None,
    ):
        self.managed_listings = managed_listings
        self.market_prices = market_prices or {}
        self.competitors = competitors or {}
        self.failures = failures or {}
        self.events = []
        self.upsert_calls = []
        self.excluded_listing_ids = []
        self._request_count = 0

    @property
    def request_count(self):
        return self._request_count

    def _fail(self, operation, identity=None):
        error = self.failures.get((operation, identity), self.failures.get(operation))
        if error is not None:
            raise error

    def get_managed_listings(self):
        self.events.append(("managed", None))
        self._request_count += 1
        self._fail("managed")
        return list(self.managed_listings)

    def get_card_details(self, fetch_card_id):
        self.events.append(("card", fetch_card_id))
        self._request_count += 1
        self._fail("card", fetch_card_id)
        listing = next(
            value
            for value in self.managed_listings
            if value.fetch_card_id == fetch_card_id
        )
        return _card(
            listing,
            self.market_prices.get(fetch_card_id, "0.60"),
        )

    def get_competitor_listings(self, fetch_card_id, *, excluded_listing_ids=()):
        self.events.append(("competitors", fetch_card_id))
        self._request_count += 1
        self.excluded_listing_ids.append(frozenset(excluded_listing_ids))
        self._fail("competitors", fetch_card_id)
        return list(self.competitors.get(fetch_card_id, ()))

    def upsert_managed_listing(self, request, *, expected_listing_id=None):
        self.events.append(("upsert", request.fetch_card_id))
        self._request_count += 1
        self.upsert_calls.append((request, expected_listing_id))
        self._fail("upsert", request.fetch_card_id)
        return ListingUpsertResult(
            listing_id=expected_listing_id,
            remaining_quantity=request.quantity,
            condition=request.condition,
            listed_price_nzd=request.listed_price_nzd,
        )


@pytest.mark.parametrize(
    ("market_price", "expected"),
    [
        ("0.25", Decimal("0.75")),
        ("0.74", Decimal("0.75")),
        ("0.76", Decimal("1.00")),
        ("1.00", Decimal("1.00")),
        ("1.01", Decimal("1.25")),
        ("3.20", Decimal("3.25")),
    ],
)
def test_calculatePricingShouldApplyFloorAndQuarterIncrement(
    market_price,
    expected,
):
    # arrange
    listing = _managed(1)
    card = _card(listing, market_price)

    # act
    result = calculate_pricing(card, (), listing.condition)

    # assert
    assert result.decision == Decision.LIST
    assert result.target_price_nzd == expected


def test_calculatePricingShouldUseCumulativeTwoSellerFloor():
    # arrange
    listing = _managed(1)
    card = _card(listing, "1.40")
    competitors = [
        _competitor(10, "0.40", seller="alpha", quantity=3),
        _competitor(11, "0.76", seller="beta"),
    ]

    # act
    result = calculate_pricing(card, competitors, listing.condition)

    # assert
    assert result.decision == Decision.LIST
    assert result.supported_local_price_nzd == Decimal("0.76")
    assert result.target_price_nzd == Decimal("1.00")


def test_calculatePricingShouldIgnoreOneSellerAcrossCopiesAndTiers():
    # arrange
    listing = _managed(1)
    card = _card(listing, "1.01")
    competitors = [
        _competitor(10, "0.40", seller="alpha", quantity=3),
        _competitor(11, "0.50", seller="alpha"),
    ]

    # act
    result = calculate_pricing(card, competitors, listing.condition)

    # assert
    assert result.decision == Decision.LIST
    assert result.supported_local_price_nzd is None
    assert result.target_price_nzd == Decimal("1.25")


def test_calculatePricingShouldIncludeBetterConditionsInSupportedFloor():
    # arrange
    listing = _managed(1, condition="raw-lp")
    card = _card(listing, "1.40")
    competitors = [
        _competitor(10, "0.40", condition="raw-nm", seller="alpha"),
        _competitor(11, "0.76", condition="raw-m", seller="beta"),
        _competitor(12, "0.20", condition="raw-mp", seller="gamma"),
    ]

    # act
    result = calculate_pricing(card, competitors, listing.condition)

    # assert
    assert result.decision == Decision.LIST
    assert result.local_listing_count == 2
    assert result.supported_local_price_nzd == Decimal("0.76")
    assert result.better_condition_lowest_price_nzd == Decimal("0.40")
    assert result.target_price_nzd == Decimal("1.00")


def test_calculatePricingShouldIgnoreUnsupportedBetterConditionInsteadOfReview():
    # arrange
    listing = _managed(1, condition="raw-lp")
    card = _card(listing, "0.60")
    competitors = [
        _competitor(10, "0.74", condition="raw-nm", seller="alpha"),
        _competitor(11, "0.50", condition="raw-mp", seller="beta"),
    ]

    # act
    result = calculate_pricing(card, competitors, listing.condition)

    # assert
    assert result.decision == Decision.LIST
    assert result.supported_local_price_nzd is None
    assert result.better_condition_lowest_price_nzd == Decimal("0.74")
    assert result.target_price_nzd == Decimal("0.75")


@pytest.mark.parametrize(
    ("market_price", "competitors", "expected"),
    [
        ("0.2499", (), Decision.DISCARD),
        ("0.25", (_competitor(10, "0.50", condition="raw-lp"),), Decision.DISCARD),
        (
            "0.40",
            tuple(
                _competitor(index, "0.50", seller=f"seller-{index}")
                for index in range(10, 16)
            ),
            Decision.DISCARD,
        ),
        ("0.50", (), Decision.LIST),
    ],
)
def test_calculatePricingShouldApplySelectionBoundaries(
    market_price,
    competitors,
    expected,
):
    # arrange
    listing = _managed(1)
    card = _card(listing, market_price)

    # act
    result = calculate_pricing(card, competitors, listing.condition)

    # assert
    assert result.decision == expected


def test_parseArgsShouldValidateOffsetAndLimit():
    # arrange
    valid_arguments = ["--offset", "5", "--limit", "10", "--execute", "--verbose"]

    # act
    parsed = parse_args(valid_arguments)

    # assert
    assert parsed.offset == 5
    assert parsed.limit == 10
    assert parsed.execute is True
    assert parsed.verbose is True
    with pytest.raises(SystemExit):
        parse_args(["--offset", "-1"])
    with pytest.raises(SystemExit):
        parse_args(["--limit", "0"])


def test_defaultOutputDirShouldUseWorkspaceOutsideRunfiles(tmp_path, monkeypatch):
    # arrange
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))
    monkeypatch.chdir(tmp_path.parent)

    # act
    output = default_output_dir(GENERATED_AT)

    # assert
    assert output == tmp_path / "tmp/tcg-lister/reprice-20260805T010000Z"


def test_runRepricingShouldSortByListingIdBeforeOffsetAndLimit(tmp_path):
    # arrange
    listings = [_managed(30), _managed(10), _managed(20, price="0.75")]
    client = _FakeClient(
        listings,
        market_prices={listings[2].fetch_card_id: "0.76"},
    )
    messages = []

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        offset=1,
        limit=1,
        generated_at=GENERATED_AT,
        output=messages.append,
    )

    # assert
    assert [record.listing_id for record in run.records] == [20]
    assert run.next_offset == 2
    assert run.managed_listing_count == 3
    assert run.selected_listing_count == 1
    assert run.complete is True
    assert run.portfolio_complete is False
    assert client.events == [
        ("managed", None),
        ("card", listings[2].fetch_card_id),
        ("competitors", listings[2].fetch_card_id),
    ]
    assert client.excluded_listing_ids == [frozenset({10, 20, 30})]
    assert client.upsert_calls == []
    assert messages[-1] == "[resume] next offset: 2"
    assert any("--offset 2" in message for message in messages)


def test_runRepricingShouldExecuteRaisesAndLowersInline(tmp_path):
    # arrange
    high_target = _managed(1, price="0.75", quantity=4)
    low_target = _managed(3, price="1.50", quantity=5)
    client = _FakeClient(
        [low_target, high_target],
        market_prices={
            high_target.fetch_card_id: "0.76",
            low_target.fetch_card_id: "0.60",
        },
    )

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    assert [record.listing_id for record in run.records] == [1, 3]
    assert [record.mutation_status for record in run.records] == [
        MutationStatus.SUCCEEDED,
        MutationStatus.SUCCEEDED,
    ]
    assert [record.mutation_price_nzd for record in run.records] == [
        Decimal("1.00"),
        Decimal("0.75"),
    ]
    assert client.events == [
        ("managed", None),
        ("card", high_target.fetch_card_id),
        ("competitors", high_target.fetch_card_id),
        ("upsert", high_target.fetch_card_id),
        ("card", low_target.fetch_card_id),
        ("competitors", low_target.fetch_card_id),
        ("upsert", low_target.fetch_card_id),
    ]
    assert [
        (request.quantity, expected_id) for request, expected_id in client.upsert_calls
    ] == [(4, 1), (5, 3)]


def test_runRepricingShouldPlanChangesWithoutPosting(tmp_path):
    # arrange
    listing = _managed(1, price="1.25")
    client = _FakeClient([listing], market_prices={listing.fetch_card_id: "0.60"})

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    assert run.records[0].mutation_status == MutationStatus.PLANNED
    assert run.records[0].mutation_price_nzd == Decimal("0.75")
    assert client.upsert_calls == []


def test_runRepricingShouldPrintDecisionReasonsAndColorDirections(tmp_path):
    # arrange
    decrease = _managed(1, price="1.25")
    increase = _managed(2, price="0.75")
    unchanged = _managed(3, price="0.75")
    reviewed = _managed(4, condition="raw-lp", price="1.00")
    discarded = _managed(5, price="1.00")
    client = _FakeClient(
        [discarded, unchanged, reviewed, increase, decrease],
        market_prices={
            decrease.fetch_card_id: "0.60",
            increase.fetch_card_id: "0.76",
            unchanged.fetch_card_id: "0.60",
            reviewed.fetch_card_id: "NaN",
            discarded.fetch_card_id: "0.20",
        },
    )
    messages = []

    # act
    run_repricing(
        client,
        output_dir=tmp_path / "output",
        generated_at=GENERATED_AT,
        use_color=True,
        output=messages.append,
    )

    # assert
    listing_lines = [message for message in messages if "#" in message]
    assert len(listing_lines) == 5
    assert "\033[32mDECREASE\033[0m" in listing_lines[0]
    assert "\033[33mINCREASE\033[0m" in listing_lines[1]
    assert "\033[34mUNCHANGED\033[0m" in listing_lines[2]
    assert "\033[33mREVIEW\033[0m" in listing_lines[3]
    assert "\033[31mDISCARD\033[0m" in listing_lines[4]
    assert all(" — " in line for line in listing_lines)
    assert "price uses the NZ$0.60 market benchmark" in listing_lines[0]
    assert "valid NZD market price" in listing_lines[3]
    assert "market price is below NZ$0.25" in listing_lines[4]


def test_runRepricingShouldCompleteUnchangedReviewAndDiscardSkips(tmp_path):
    # arrange
    unchanged = _managed(1, price="0.75")
    reviewed = _managed(2, condition="raw-lp", price="1.00")
    discarded = _managed(3, price="1.00")
    client = _FakeClient(
        [discarded, reviewed, unchanged],
        market_prices={
            unchanged.fetch_card_id: "0.60",
            reviewed.fetch_card_id: "NaN",
            discarded.fetch_card_id: "0.20",
        },
    )

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    assert [record.mutation_status for record in run.records] == [
        MutationStatus.UNCHANGED,
        MutationStatus.SKIPPED,
        MutationStatus.SKIPPED,
    ]
    assert [record.decision for record in run.records] == [
        Decision.LIST,
        Decision.REVIEW,
        Decision.DISCARD,
    ]
    assert run.next_offset == 3
    assert client.upsert_calls == []


def test_runRepricingShouldSkipDuplicateOwnedIdentityWithoutMarketReads(tmp_path):
    # arrange
    first = _managed(1, fetch_card_id="mtg_duplicate", condition="raw-nm")
    second = _managed(2, fetch_card_id="mtg_duplicate", condition="raw-nm")
    client = _FakeClient([second, first])

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    assert [record.decision for record in run.records] == [
        Decision.REVIEW,
        Decision.REVIEW,
    ]
    assert all(
        record.mutation_status == MutationStatus.SKIPPED for record in run.records
    )
    assert client.events == [("managed", None)]
    assert run.next_offset == 2


def test_runRepricingShouldRetainCurrentOffsetWhenReadFails(tmp_path):
    # arrange
    first = _managed(1, price="0.75")
    second = _managed(2)
    failure = RunSafetyStop("Fetch returned HTTP 401; stopping the run")
    client = _FakeClient(
        [second, first],
        failures={("card", second.fetch_card_id): failure},
    )
    messages = []

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        generated_at=GENERATED_AT,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 1
    assert run.completed_listing_count == 1
    assert run.records[-1].listing_id == 2
    assert run.records[-1].mutation_status == MutationStatus.FAILED
    assert "401" in run.error
    assert messages[-1] == "[resume] next offset: 1"
    report = json.loads((tmp_path / "output/report.json").read_text())
    assert report["next_offset"] == 1
    assert report["completed_listing_count"] == 1


def test_runRepricingShouldRetryCurrentOffsetWhenMutationFails(tmp_path):
    # arrange
    listing = _managed(1, price="1.25")
    client = _FakeClient(
        [listing],
        failures={
            ("upsert", listing.fetch_card_id): RunSafetyStop(
                "Fetch returned HTTP 401; stopping the run"
            )
        },
    )

    messages = []

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        use_color=True,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 0
    assert run.completed_listing_count == 0
    assert run.records[0].mutation_status == MutationStatus.FAILED
    assert run.records[0].mutation_price_nzd == Decimal("0.75")
    failed_line = next(message for message in messages if "#" in message)
    assert "\033[31mFAILED\033[0m" in failed_line
    assert "price uses the NZ$0.60 market benchmark" in failed_line
    assert "Fetch returned HTTP 401" in failed_line


@pytest.mark.parametrize(
    "error",
    [
        KeyboardInterrupt(),
        ControlledTermination("received SIGTERM"),
        RuntimeError("unexpected failure"),
    ],
)
def test_runRepricingShouldCheckpointOffsetForControlledAndUnexpectedFailures(
    tmp_path,
    error,
):
    # arrange
    listing = _managed(1)
    client = _FakeClient(
        [listing],
        failures={("competitors", listing.fetch_card_id): error},
    )
    messages = []

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        offset=0,
        generated_at=GENERATED_AT,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 0
    assert messages[-1] == "[resume] next offset: 0"
    assert json.loads((tmp_path / "output/report.json").read_text())["next_offset"] == 0


def test_runRepricingShouldCheckpointInitialOffsetWhenInventoryFails(tmp_path):
    # arrange
    client = _FakeClient(
        [],
        failures={"managed": RunSafetyStop("Fetch returned HTTP 401")},
    )
    messages = []

    # act
    run = run_repricing(
        client,
        output_dir=tmp_path / "output",
        offset=50,
        generated_at=GENERATED_AT,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 50
    assert run.records == []
    assert messages[-1] == "[resume] next offset: 50"
    assert (tmp_path / "output/report.json").exists()


def test_runRepricingShouldWriteAtomicJsonAndCsvWithoutSellerNames(tmp_path):
    # arrange
    listing = _managed(1)
    client = _FakeClient(
        [listing],
        competitors={
            listing.fetch_card_id: [
                _competitor(10, "0.40", seller="private-seller"),
                _competitor(11, "0.76", seller="other-seller"),
            ]
        },
    )
    output_dir = tmp_path / "output"

    # act
    run_repricing(
        client,
        output_dir=output_dir,
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    report_text = (output_dir / "report.json").read_text()
    csv_text = (output_dir / "listings.csv").read_text()
    assert "private-seller" not in report_text
    assert "other-seller" not in report_text
    assert "private-seller" not in csv_text
    assert "other-seller" not in csv_text
    assert not (output_dir / "report.json.tmp").exists()
    assert not (output_dir / "listings.csv.tmp").exists()
    report = json.loads(report_text)
    assert report["schema_version"] == 1
    assert report["next_offset"] == 1
    assert report["records"][0]["price_ladder"]["0.40"]["seller_count"] == 1


def test_terminationSignalHandlerShouldRaiseControlledTermination():
    # arrange
    signum = signal.SIGTERM

    # act
    with pytest.raises(ControlledTermination, match="SIGTERM"):
        reprice_module._handle_termination_signal(signum, None)

    # assert
    assert signal.Signals(signum).name == "SIGTERM"
