import gzip
import io
import json
from datetime import datetime, timezone

import pytest

from coverage import (
    COVERAGE_CUTOFFS,
    analyze_inventory,
    build_catalog,
    build_report,
    format_summary,
    iter_bulk_cards,
    run,
    sync_bulk_metadata,
    write_reports,
)
from fetchtcg_client import ManagedListing


class FakeResponse:
    def __init__(self, json_body=None, content=b""):
        self._json_body = json_body
        self.content = content

    def raise_for_status(self):
        return None

    def json(self):
        return self._json_body

    def iter_content(self, chunk_size):
        del chunk_size
        yield self.content


class FakeSession:
    def __init__(self, responses):
        self.responses = {url: list(items) for url, items in responses.items()}
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses[url].pop(0)


class FakeFetchClient:
    def __init__(self, listings, request_count=1):
        self.listings = listings
        self.request_count = request_count

    def get_managed_listings(self):
        return self.listings


def _card(
    *,
    printing_id="00000000-0000-0000-0000-000000000001",
    oracle_id="oracle-1",
    name="Test Card",
    rank=100,
    **overrides,
):
    card = {
        "id": printing_id,
        "oracle_id": oracle_id,
        "name": name,
        "lang": "en",
        "digital": False,
        "games": ["paper"],
        "layout": "normal",
        "set_type": "expansion",
        "oversized": False,
        "legalities": {"commander": "legal"},
        "type_line": "Creature — Test",
        "edhrec_rank": rank,
    }
    card.update(overrides)
    return card


def _listing(
    listing_id,
    scryfall_id,
    *,
    quantity=1,
):
    return ManagedListing(
        listing_id=listing_id,
        scryfall_id=scryfall_id,
        remaining_quantity=quantity,
    )


def _gzip_jsonl(records):
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb") as gzip_file:
        for record in records:
            gzip_file.write((json.dumps(record) + "\n").encode())
    return output.getvalue()


def testIterBulkCardsShouldStreamGzipAndPlainFiles(tmp_path):
    # arrange
    records = [_card(name="One"), _card(name="Two")]
    gzip_path = tmp_path / "cards.jsonl.gz"
    gzip_path.write_bytes(_gzip_jsonl(records))
    plain_path = tmp_path / "cards.jsonl"
    plain_path.write_text(
        json.dumps(records[0]) + "\n\n" + json.dumps(records[1]) + "\n"
    )

    # act
    gzip_names = [card["name"] for card in iter_bulk_cards(gzip_path)]
    plain_names = [card["name"] for card in iter_bulk_cards(plain_path)]

    # assert
    assert gzip_names == ["One", "Two"]
    assert plain_names == ["One", "Two"]


def testIterBulkCardsShouldFailOnMalformedLine(tmp_path):
    # arrange
    path = tmp_path / "cards.jsonl"
    path.write_text(json.dumps(_card()) + "\nnot-json\n")

    # act / assert
    with pytest.raises(ValueError, match="line 2"):
        list(iter_bulk_cards(path))


def testBuildCatalogShouldDeduplicateOracleAndRetainEveryPrintingIdentity():
    # arrange
    cards = [
        _card(
            printing_id="00000000-0000-0000-0000-000000000001",
            oracle_id="oracle-a",
            name="Card A",
            rank=250,
        ),
        _card(
            printing_id="00000000-0000-0000-0000-000000000002",
            oracle_id="oracle-a",
            name="Card A",
            rank=200,
            lang="ja",
        ),
        _card(
            printing_id="00000000-0000-0000-0000-000000000003",
            oracle_id="oracle-a",
            name="Card A",
            rank=225,
        ),
    ]

    # act
    catalog = build_catalog(cards)

    # assert
    assert catalog.printing_to_oracle == {
        "00000000-0000-0000-0000-000000000001": "oracle-a",
        "00000000-0000-0000-0000-000000000002": "oracle-a",
        "00000000-0000-0000-0000-000000000003": "oracle-a",
    }
    assert catalog.oracles["oracle-a"].rank == 225
    assert catalog.oracles["oracle-a"].name == "Card A"
    assert catalog.bulk_record_count == 3
    assert catalog.population_printing_count == 2


@pytest.mark.parametrize(
    "overrides",
    [
        {"lang": "ja"},
        {"digital": True},
        {"games": ["mtgo"]},
        {"layout": "token"},
        {"set_type": "memorabilia"},
        {"oversized": True},
        {"legalities": {"commander": "not_legal"}},
        {"type_line": "Basic Land — Island"},
    ],
)
def testBuildCatalogShouldExcludeDocumentedPopulationRecords(overrides):
    # act
    catalog = build_catalog([_card(**overrides)])

    # assert
    assert catalog.oracles == {}
    assert len(catalog.printing_to_oracle) == 1


def testBuildCatalogShouldRejectConflictingPrintingIdentity():
    # arrange
    cards = [
        _card(oracle_id="oracle-a"),
        _card(oracle_id="oracle-b"),
    ]

    # act / assert
    with pytest.raises(ValueError, match="multiple oracle"):
        build_catalog(cards)


def testAnalyzeInventoryShouldComputeCumulativeCoverageAndInventoryCounts():
    # arrange
    ranks = (1, 100, 101, 1000, 1001, 2000, 5000, 20000, 20001, None)
    cards = [
        _card(
            printing_id=f"00000000-0000-0000-0000-{index:012d}",
            oracle_id=f"oracle-{index}",
            name=f"Card {index}",
            rank=rank,
        )
        for index, rank in enumerate(ranks, start=1)
    ]
    cards.append(
        _card(
            printing_id="00000000-0000-0000-0000-000000000011",
            oracle_id="oracle-outside",
            name="Outside",
            rank=50,
            legalities={"commander": "not_legal"},
        )
    )
    catalog = build_catalog(cards)
    listings = [
        _listing(1, "00000000-0000-0000-0000-000000000001", quantity=2),
        _listing(2, "00000000-0000-0000-0000-000000000001", quantity=3),
        _listing(3, "00000000-0000-0000-0000-000000000002"),
        _listing(4, "00000000-0000-0000-0000-000000000003"),
        _listing(5, "00000000-0000-0000-0000-000000000005"),
        _listing(6, "00000000-0000-0000-0000-000000000010"),
        _listing(7, "00000000-0000-0000-0000-000000000011"),
        _listing(8, "ffffffff-ffff-ffff-ffff-ffffffffffff", quantity=4),
    ]

    # act
    analysis = analyze_inventory(listings, catalog)

    # assert
    assert [row["cutoff"] for row in analysis.coverage] == list(COVERAGE_CUTOFFS)
    assert [
        (
            row["ranked_card_count"],
            row["inventory_card_count"],
            row["missing_card_count"],
        )
        for row in analysis.coverage
    ] == [
        (2, 2, 0),
        (4, 3, 1),
        (6, 4, 2),
        (7, 4, 3),
        (8, 4, 4),
    ]
    assert analysis.coverage[1]["coverage_share"] == pytest.approx(0.75)
    assert analysis.inventory == {
        "managed_listing_count": 8,
        "physical_copy_count": 14,
        "unique_printing_count": 7,
        "resolved_unique_oracle_count": 6,
        "ranked_unique_oracle_count": 4,
        "unranked_unique_oracle_count": 1,
        "out_of_population_unique_oracle_count": 1,
        "unmatched_listing_count": 1,
        "unmatched_copy_count": 4,
        "unmatched_unique_printing_count": 1,
    }
    first = analysis.inventory_cards[0]
    assert first["name"] == "Card 1"
    assert first["listing_count"] == 2
    assert first["physical_copy_count"] == 5
    assert first["unique_printing_count"] == 1
    assert analysis.unmatched_scryfall_ids == ("ffffffff-ffff-ffff-ffff-ffffffffffff",)
    assert [card["rank"] for card in analysis.missing_cards] == [
        1000,
        2000,
        5000,
        20000,
    ]


def testAnalyzeInventoryShouldResolveNonEnglishPrintingToEligibleOracle():
    # arrange
    english_id = "00000000-0000-0000-0000-000000000001"
    japanese_id = "00000000-0000-0000-0000-000000000002"
    catalog = build_catalog(
        [
            _card(
                printing_id=english_id,
                oracle_id="oracle-a",
                name="Card A",
                rank=10,
            ),
            _card(
                printing_id=japanese_id,
                oracle_id="oracle-a",
                name="カードA",
                rank=10,
                lang="ja",
            ),
        ]
    )

    # act
    analysis = analyze_inventory([_listing(1, japanese_id)], catalog)

    # assert
    assert analysis.coverage[0]["inventory_card_count"] == 1
    assert analysis.inventory_cards[0]["name"] == "Card A"
    assert analysis.inventory_cards[0]["population_status"] == "ranked"


def testBuildReportAndWriteReportsShouldSerializeStableArtifacts(tmp_path):
    # arrange
    bulk_path = tmp_path / "cards.jsonl"
    bulk_path.write_text("")
    catalog = build_catalog(
        [
            _card(
                printing_id="00000000-0000-0000-0000-000000000001",
                oracle_id="oracle-a",
                name="Card A",
                rank=50,
            ),
            _card(
                printing_id="00000000-0000-0000-0000-000000000002",
                oracle_id="oracle-b",
                name="Card B",
                rank=75,
            ),
        ]
    )
    analysis = analyze_inventory(
        [_listing(1, "00000000-0000-0000-0000-000000000001")],
        catalog,
    )

    # act
    report = build_report(
        generated_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
        bulk_path=bulk_path,
        catalog=catalog,
        analysis=analysis,
        request_count=1,
    )
    write_reports(tmp_path / "report", report)

    # assert
    assert report["schema_version"] == 1
    assert report["coverage"][0]["coverage_share"] == "0.5000"
    assert json.loads((tmp_path / "report" / "report.json").read_text()) == report
    assert (
        (tmp_path / "report" / "coverage.csv")
        .read_text()
        .splitlines()[1]
        .endswith(",0.5000")
    )
    assert "Card A" in (tmp_path / "report" / "inventory_cards.csv").read_text()
    assert "Card B" in (tmp_path / "report" / "missing_cards.csv").read_text()
    assert "Top 100" in format_summary(report)
    assert "50.0%" in format_summary(report)


def testRunShouldAnalyzeLocalBulkAndWriteTimestampedReports(
    tmp_path, monkeypatch, capsys
):
    # arrange
    bulk_path = tmp_path / "cards.jsonl"
    bulk_path.write_text(
        json.dumps(
            _card(
                printing_id="00000000-0000-0000-0000-000000000001",
                oracle_id="oracle-a",
                name="Card A",
                rank=50,
            )
        )
        + "\n"
    )
    client = FakeFetchClient([_listing(1, "00000000-0000-0000-0000-000000000001")])
    generated_at = datetime(2026, 8, 10, 1, 2, 3, tzinfo=timezone.utc)
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))

    # act
    exit_code = run(
        ["--bulk-file", "cards.jsonl"],
        fetch_client=client,
        now=generated_at,
    )

    # assert
    report_dir = tmp_path / "tmp" / "tcg-lister" / "inventory-20260810T010203Z"
    assert exit_code == 0
    assert (report_dir / "report.json").exists()
    assert (
        json.loads((report_dir / "report.json").read_text())["coverage"][0][
            "coverage_share"
        ]
        == "1.0000"
    )
    assert "Top 100" in capsys.readouterr().out


def testSyncBulkMetadataShouldReuseMatchingValidatedCache(tmp_path):
    # arrange
    descriptor_url = "https://api.scryfall.com/bulk-data/default_cards"
    download_url = "https://data.scryfall.io/default.jsonl.gz"
    descriptor = {
        "type": "default_cards",
        "updated_at": "2026-08-10T00:00:00Z",
        "jsonl_download_uri": download_url,
    }
    bulk_path = tmp_path / "default_cards.jsonl.gz"
    bulk_path.write_bytes(_gzip_jsonl([_card()]))
    (tmp_path / "default_cards.bulk.json").write_text(json.dumps(descriptor))
    session = FakeSession({descriptor_url: [FakeResponse(descriptor)]})

    # act
    result = sync_bulk_metadata(tmp_path, session=session)

    # assert
    assert result == bulk_path
    assert [url for url, _kwargs in session.calls] == [descriptor_url]


def testSyncBulkMetadataShouldDownloadChangedDescriptor(tmp_path):
    # arrange
    descriptor_url = "https://api.scryfall.com/bulk-data/default_cards"
    download_url = "https://data.scryfall.io/default.jsonl.gz"
    descriptor = {
        "type": "default_cards",
        "updated_at": "2026-08-10T00:00:00Z",
        "jsonl_download_uri": download_url,
    }
    content = _gzip_jsonl([_card()])
    session = FakeSession(
        {
            descriptor_url: [FakeResponse(descriptor)],
            download_url: [FakeResponse(content=content)],
        }
    )

    # act
    result = sync_bulk_metadata(tmp_path, session=session)

    # assert
    assert result.read_bytes() == content
    assert json.loads((tmp_path / "default_cards.bulk.json").read_text()) == descriptor
