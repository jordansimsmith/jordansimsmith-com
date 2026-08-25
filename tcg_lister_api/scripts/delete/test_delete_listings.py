import json
import signal
from datetime import datetime, timezone
from decimal import Decimal

import pytest

import delete_listings as delete_module
from fetchtcg_client import ManagedListing, RunSafetyStop
from delete_listings import (
    ControlledTermination,
    MutationStatus,
    default_output_dir,
    parse_args,
    run_deletes,
)


SCRYFALL_ID = "9561b47c-b863-463a-8a10-56fede2cb42c"
GENERATED_AT = datetime(2026, 8, 25, 4, 0, tzinfo=timezone.utc)


def _managed(
    listing_id,
    *,
    name=None,
    condition="raw-nm",
    quantity=2,
    price="1.25",
):
    return ManagedListing(
        listing_id=listing_id,
        fetch_card_id=f"mtg_{listing_id}_c_tst_normal",
        scryfall_id=SCRYFALL_ID,
        name=name or f"Card {listing_id}",
        collector_number=str(listing_id),
        set_id=100,
        set_name="Test Set",
        finish="normal",
        condition=condition,
        remaining_quantity=quantity,
        listed_price_nzd=Decimal(price),
    )


class _FakeClient:
    def __init__(self, managed_listings, *, failures=None):
        self.managed_listings = managed_listings
        self.failures = failures or {}
        self.events = []
        self.delete_calls = []
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

    def delete_managed_listing(self, listing_id):
        self.events.append(("delete", listing_id))
        self._request_count += 1
        self.delete_calls.append(listing_id)
        self._fail("delete", listing_id)


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
    assert output == tmp_path / "tmp/tcg-lister/delete-20260825T040000Z"


def test_runDeletesShouldSortByListingIdBeforeOffsetAndLimit(tmp_path):
    # arrange
    listings = [_managed(30), _managed(10), _managed(20)]
    client = _FakeClient(listings)
    messages = []

    # act
    run = run_deletes(
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
    assert client.events == [("managed", None)]
    assert client.delete_calls == []
    assert messages[-1] == "[resume] next offset: 2"
    assert any("--offset 2" in message for message in messages)


def test_runDeletesShouldPlanWithoutDeleting(tmp_path):
    # arrange
    listing = _managed(1)
    client = _FakeClient([listing])

    # act
    run = run_deletes(
        client,
        output_dir=tmp_path / "output",
        generated_at=GENERATED_AT,
        output=lambda _: None,
    )

    # assert
    assert run.execution_mode == "dry_run"
    assert run.records[0].mutation_status == MutationStatus.PLANNED
    assert run.complete is True
    assert run.portfolio_complete is True
    assert client.delete_calls == []


def test_runDeletesShouldDeleteOneListingAtATime(tmp_path):
    # arrange
    first = _managed(1, name="Alpha", quantity=4, price="0.75")
    second = _managed(3, name="Beta", quantity=5, price="1.50")
    client = _FakeClient([second, first])

    # act
    run = run_deletes(
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
    assert client.events == [
        ("managed", None),
        ("delete", 1),
        ("delete", 3),
    ]
    assert client.delete_calls == [1, 3]
    assert run.next_offset == 2
    assert run.complete is True


def test_runDeletesShouldRetainCurrentOffsetWhenDeleteFails(tmp_path):
    # arrange
    first = _managed(1)
    second = _managed(2)
    client = _FakeClient(
        [second, first],
        failures={
            ("delete", 2): RunSafetyStop("Fetch returned HTTP 401; stopping the run")
        },
    )
    messages = []

    # act
    run = run_deletes(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        use_color=True,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 1
    assert run.completed_listing_count == 1
    assert run.records[-1].listing_id == 2
    assert run.records[-1].mutation_status == MutationStatus.FAILED
    assert "401" in run.error
    failed_line = next(
        message for message in messages if "#2" in message and "FAILED" in message
    )
    assert "\033[31mFAILED\033[0m" in failed_line
    assert messages[-1] == "[resume] next offset: 1"
    report = json.loads((tmp_path / "output/report.json").read_text())
    assert report["next_offset"] == 1
    assert report["completed_listing_count"] == 1
    assert client.delete_calls == [1, 2]


@pytest.mark.parametrize(
    "error",
    [
        KeyboardInterrupt(),
        ControlledTermination("received SIGTERM"),
        RuntimeError("unexpected failure"),
    ],
)
def test_runDeletesShouldCheckpointOffsetForControlledAndUnexpectedFailures(
    tmp_path,
    error,
):
    # arrange
    listing = _managed(1)
    client = _FakeClient(
        [listing],
        failures={("delete", 1): error},
    )
    messages = []

    # act
    run = run_deletes(
        client,
        output_dir=tmp_path / "output",
        execute=True,
        generated_at=GENERATED_AT,
        output=messages.append,
    )

    # assert
    assert run.complete is False
    assert run.next_offset == 0
    assert messages[-1] == "[resume] next offset: 0"
    assert json.loads((tmp_path / "output/report.json").read_text())["next_offset"] == 0


def test_runDeletesShouldCheckpointInitialOffsetWhenInventoryFails(tmp_path):
    # arrange
    client = _FakeClient(
        [],
        failures={"managed": RunSafetyStop("Fetch returned HTTP 401")},
    )
    messages = []

    # act
    run = run_deletes(
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
    assert client.delete_calls == []


def test_runDeletesShouldPrintColoredStatusesAndWriteReportsWithoutToken(
    tmp_path, monkeypatch
):
    # arrange
    monkeypatch.setenv("FETCHTCG_TOKEN", "highly-secret-token")
    listing = _managed(1, name="Alpha")
    client = _FakeClient([listing])
    messages = []

    # act
    run_deletes(
        client,
        output_dir=tmp_path / "output",
        generated_at=GENERATED_AT,
        use_color=True,
        output=messages.append,
    )

    # assert
    listing_line = next(message for message in messages if "#" in message)
    assert "\033[33mPLANNED\033[0m" in listing_line
    assert "Alpha" in listing_line
    assert "raw-nm x2 NZ$1.25" in listing_line
    report_text = (tmp_path / "output/report.json").read_text()
    csv_text = (tmp_path / "output/listings.csv").read_text()
    assert "highly-secret-token" not in report_text
    assert "highly-secret-token" not in csv_text
    assert not (tmp_path / "output/report.json.tmp").exists()
    assert not (tmp_path / "output/listings.csv.tmp").exists()
    report = json.loads(report_text)
    assert report["schema_version"] == 1
    assert report["execution_mode"] == "dry_run"
    assert report["next_offset"] == 1
    assert report["records"][0]["listing_id"] == 1
    assert report["records"][0]["listed_price_nzd"] == "1.25"


def test_terminationSignalHandlerShouldRaiseControlledTermination():
    # arrange
    signum = signal.SIGTERM

    # act
    with pytest.raises(ControlledTermination, match="SIGTERM"):
        delete_module._handle_termination_signal(signum, None)

    # assert
    assert signal.Signals(signum).name == "SIGTERM"
