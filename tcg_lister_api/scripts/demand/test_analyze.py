import gzip
import io
import json
from decimal import Decimal

import pytest

from analyze import (
    ANCHOR_NAMES,
    aggregate_oracles,
    analyze_population,
    anchor_slug,
    build_inclusion_curve,
    classify_printing,
    demand_tier,
    fetch_anchor_inclusions,
    inclusion_for_rank,
    iter_bulk_cards,
    main,
    spearman_price_demand,
    sync_bulk_metadata,
)


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


def _card(**overrides):
    card = {
        "id": "printing-1",
        "oracle_id": "oracle-1",
        "name": "Test Card",
        "lang": "en",
        "digital": False,
        "games": ["paper"],
        "layout": "normal",
        "set_type": "expansion",
        "oversized": False,
        "legalities": {"commander": "legal"},
        "type_line": "Creature — Test",
        "prices": {"usd": "0.50", "usd_foil": None, "usd_etched": None},
        "edhrec_rank": 500,
    }
    card.update(overrides)
    return card


def _gzip_jsonl(records):
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb") as gzip_file:
        for record in records:
            gzip_file.write((json.dumps(record) + "\n").encode())
    return output.getvalue()


def _write_plain_jsonl(path, records):
    path.write_text("".join(json.dumps(record) + "\n" for record in records))


def testIterBulkCardsShouldStreamGzipAndPlainFiles(tmp_path):
    # arrange
    records = [_card(id="a"), _card(id="b")]
    gzip_path = tmp_path / "cards.jsonl.gz"
    gzip_path.write_bytes(_gzip_jsonl(records))
    plain_path = tmp_path / "cards.jsonl"
    plain_path.write_text(
        json.dumps(records[0]) + "\n\n" + json.dumps(records[1]) + "\n"
    )

    # act
    from_gzip = [card["id"] for card in iter_bulk_cards(gzip_path)]
    from_plain = [card["id"] for card in iter_bulk_cards(plain_path)]

    # assert
    assert from_gzip == ["a", "b"]
    assert from_plain == ["a", "b"]


def testIterBulkCardsShouldFailOnMalformedLine(tmp_path):
    # arrange
    path = tmp_path / "cards.jsonl"
    path.write_text(json.dumps(_card()) + "\nnot-json\n")

    # act / assert
    with pytest.raises(ValueError, match="line 2"):
        list(iter_bulk_cards(path))


def testClassifyPrintingShouldExcludeEveryDocumentedReason():
    # arrange
    cases = [
        ({"lang": "ja"}, "non_english"),
        ({"digital": True}, "digital"),
        ({"games": ["mtgo"]}, "non_paper"),
        ({"layout": "token"}, "layout"),
        ({"set_type": "memorabilia"}, "set_type"),
        ({"oversized": True}, "oversized"),
        ({"legalities": {"commander": "not_legal"}}, "not_commander_legal"),
        ({"type_line": "Basic Land — Island"}, "basic_land"),
        ({"oracle_id": None, "card_faces": []}, "missing_oracle_id"),
        ({"prices": {"usd": "not-a-price"}}, "invalid_price"),
    ]

    for overrides, expected_reason in cases:
        # act
        record, reason = classify_printing(_card(**overrides))

        # assert
        assert record is None, expected_reason
        assert reason == expected_reason


def testClassifyPrintingShouldSelectPricesWithDocumentedFallbacks():
    # act
    nonfoil, _ = classify_printing(
        _card(prices={"usd": "1.10", "usd_foil": "9.99", "usd_etched": "5.00"})
    )
    foil_only, _ = classify_printing(
        _card(prices={"usd": None, "usd_foil": "2.50", "usd_etched": None})
    )
    etched_only, _ = classify_printing(
        _card(prices={"usd": None, "usd_foil": None, "usd_etched": "3.75"})
    )
    unpriced, _ = classify_printing(
        _card(prices={"usd": None, "usd_foil": None, "usd_etched": None})
    )

    # assert
    assert (nonfoil.price_usd, nonfoil.price_source) == (Decimal("1.10"), "usd")
    assert (foil_only.price_usd, foil_only.price_source) == (
        Decimal("2.50"),
        "usd_foil",
    )
    assert (etched_only.price_usd, etched_only.price_source) == (
        Decimal("3.75"),
        "usd_etched",
    )
    assert unpriced.price_usd is None
    assert unpriced.price_source is None


def testClassifyPrintingShouldUseFaceOracleIdWhenTopLevelMissing():
    # arrange
    card = _card(oracle_id=None, card_faces=[{"oracle_id": "face-oracle"}])

    # act
    record, reason = classify_printing(card)

    # assert
    assert reason is None
    assert record.oracle_id == "face-oracle"


def testClassifyPrintingShouldTreatInvalidRankAsUnranked():
    for invalid_rank in ("5", 0, True, None):
        # act
        record, reason = classify_printing(_card(edhrec_rank=invalid_rank))

        # assert
        assert reason is None
        assert record.edhrec_rank is None


def testDemandTierShouldMapRankBoundaries():
    # act / assert
    assert demand_tier(1) == "1-100"
    assert demand_tier(100) == "1-100"
    assert demand_tier(101) == "101-1000"
    assert demand_tier(1000) == "101-1000"
    assert demand_tier(1001) == "1001-2000"
    assert demand_tier(2000) == "1001-2000"
    assert demand_tier(2001) == "2001-5000"
    assert demand_tier(5000) == "2001-5000"
    assert demand_tier(5001) == "5001-20000"
    assert demand_tier(20000) == "5001-20000"
    assert demand_tier(20001) == "20001+"
    assert demand_tier(None) == "unranked"


def testSpearmanShouldReturnSignedAlignmentBetweenPriceAndDemand():
    # arrange
    aligned = [
        (Decimal("4.00"), 1),
        (Decimal("3.00"), 2),
        (Decimal("2.00"), 3),
        (Decimal("1.00"), 4),
    ]
    opposed = [
        (Decimal("1.00"), 1),
        (Decimal("2.00"), 2),
        (Decimal("3.00"), 3),
        (Decimal("4.00"), 4),
    ]
    tied = [
        (Decimal("1.00"), 1),
        (Decimal("1.00"), 2),
        (Decimal("2.00"), 3),
    ]

    # act / assert
    assert spearman_price_demand(aligned) == pytest.approx(1.0)
    assert spearman_price_demand(opposed) == pytest.approx(-1.0)
    assert spearman_price_demand(tied) is not None
    assert spearman_price_demand(aligned[:1]) is None


def testInclusionCurveShouldInterpolateClampAndExtend():
    # arrange
    points = [(10, 0.1), (1000, 0.001)]

    # act / assert
    assert inclusion_for_rank(10, points) == pytest.approx(0.1)
    assert inclusion_for_rank(1000, points) == pytest.approx(0.001)
    assert inclusion_for_rank(100, points) == pytest.approx(0.01)
    assert inclusion_for_rank(1, points) == pytest.approx(0.1)
    assert inclusion_for_rank(10000, points) == pytest.approx(0.0001)
    assert inclusion_for_rank(10**9, points) >= 1e-6
    assert inclusion_for_rank(None, points) == 0.0


def testBuildInclusionCurveShouldFailWhenAnchorMissingFromBulk():
    # arrange
    anchor_ranks = {"Sol Ring": 1}
    anchor_inclusions = {
        "Sol Ring": (800_000, 1_000_000),
        "Murder": (20_000, 900_000),
    }

    # act / assert
    with pytest.raises(ValueError, match="Murder"):
        build_inclusion_curve(anchor_ranks, anchor_inclusions)


def testBuildInclusionCurveShouldNormalizeByLargestPotential():
    # arrange
    anchor_ranks = {"Sol Ring": 1, "Murder": 800}
    anchor_inclusions = {
        "Sol Ring": (800_000, 1_000_000),
        "Murder": (10_000, 900_000),
    }

    # act
    points, total_decks = build_inclusion_curve(anchor_ranks, anchor_inclusions)

    # assert
    assert total_decks == 1_000_000
    assert points == [(1, 0.8), (800, 0.01)]


def testAnalyzePopulationShouldWeightCaptureByPassingShare():
    # arrange
    records = [
        classify_printing(
            _card(
                id="cheap",
                oracle_id="oracle-split",
                edhrec_rank=900,
                prices={"usd": "0.15"},
            )
        )[0],
        classify_printing(
            _card(
                id="pricey",
                oracle_id="oracle-split",
                edhrec_rank=900,
                prices={"usd": "0.40"},
            )
        )[0],
    ]
    oracles = aggregate_oracles(records)
    curve_points = [(10, 0.1), (10000, 0.0001)]

    # act
    metrics = analyze_population(oracles, curve_points, Decimal("0.25"))

    # assert
    assert metrics["headline"]["demand_capture_share"] == pytest.approx(0.5)


def testAnalyzePopulationShouldPassPricesExactlyAtThreshold():
    # arrange
    records = [
        classify_printing(
            _card(oracle_id="oracle-exact", edhrec_rank=900, prices={"usd": "0.25"})
        )[0]
    ]
    oracles = aggregate_oracles(records)
    curve_points = [(10, 0.1), (10000, 0.0001)]

    # act
    metrics = analyze_population(oracles, curve_points, Decimal("0.25"))

    # assert
    assert metrics["headline"]["pass_share"] == pytest.approx(1.0)
    assert metrics["headline"]["demand_capture_share"] == pytest.approx(1.0)


def testAnalyzePopulationShouldReportTiersListsAndPrecision():
    # arrange
    records = [
        classify_printing(
            _card(
                id="staple-a",
                oracle_id="oracle-staple",
                name="Binned Staple",
                edhrec_rank=50,
                prices={"usd": "0.10"},
            )
        )[0],
        classify_printing(
            _card(
                id="staple-b",
                oracle_id="oracle-staple",
                name="Binned Staple",
                edhrec_rank=50,
                prices={"usd": "0.15"},
            )
        )[0],
        classify_printing(
            _card(
                id="junk",
                oracle_id="oracle-junk",
                name="Expensive Junk",
                edhrec_rank=30000,
                prices={"usd": "5.00"},
            )
        )[0],
        classify_printing(
            _card(
                id="normal",
                oracle_id="oracle-normal",
                name="Normal Card",
                edhrec_rank=900,
                prices={"usd": "0.40"},
            )
        )[0],
        classify_printing(
            _card(
                id="unranked",
                oracle_id="oracle-unranked",
                name="Unranked Card",
                edhrec_rank=None,
                prices={"usd": "0.30"},
            )
        )[0],
    ]
    oracles = aggregate_oracles(records)
    curve_points = [(10, 0.1), (10000, 0.0001)]

    # act
    metrics = analyze_population(oracles, curve_points, Decimal("0.25"))

    # assert
    tiers = {row["tier"]: row for row in metrics["tiers"]}
    assert tiers["1-100"]["oracle_cards"] == 1
    assert tiers["1-100"]["priced_printings"] == 2
    assert tiers["1-100"]["passing_printings"] == 0
    assert tiers["1-100"]["oracle_cheapest_passing"] == 0
    assert tiers["101-1000"]["passing_printings"] == 1
    assert tiers["20001+"]["passing_printings"] == 1
    assert tiers["unranked"]["oracle_cards"] == 1

    assert metrics["fully_binned_staples_count"] == 1
    staple = metrics["binned_staples"][0]
    assert staple["name"] == "Binned Staple"
    assert staple["rank"] == 50
    assert staple["min_price_usd"] == Decimal("0.10")
    assert staple["max_price_usd"] == Decimal("0.15")

    junk = metrics["expensive_low_demand"]
    assert junk[0]["name"] == "Expensive Junk"
    assert junk[0]["price_usd"] == Decimal("5.00")
    assert {item["name"] for item in junk} == {"Expensive Junk", "Unranked Card"}

    headline = metrics["headline"]
    assert headline["in_demand_recall_share"] == pytest.approx(1 / 3)
    assert headline["low_demand_keep_share"] == pytest.approx(2 / 3)


def testSyncBulkMetadataShouldDownloadAndReuseCurrentBulkFile(tmp_path):
    # arrange
    api_url = "https://api.scryfall.com/bulk-data/default_cards"
    download_url = "https://data.scryfall.io/default-cards.jsonl.gz"
    descriptor = {
        "type": "default_cards",
        "updated_at": "2026-08-08T21:13:58.746+00:00",
        "jsonl_download_uri": download_url,
    }
    content = _gzip_jsonl([_card()])
    first_session = FakeSession(
        {
            api_url: [FakeResponse(json_body=descriptor)],
            download_url: [FakeResponse(content=content)],
        }
    )

    # act
    bulk_path = sync_bulk_metadata(tmp_path, session=first_session)
    second_session = FakeSession({api_url: [FakeResponse(json_body=descriptor)]})
    cached_path = sync_bulk_metadata(tmp_path, session=second_session)

    # assert
    assert cached_path == bulk_path
    assert [call[0] for call in first_session.calls] == [api_url, download_url]
    assert [call[0] for call in second_session.calls] == [api_url]
    with gzip.open(bulk_path, "rt") as bulk_file:
        assert json.loads(bulk_file.readline())["oracle_id"] == "oracle-1"


def _anchor_responses(total_decks=1_000_000):
    responses = {}
    for index, name in enumerate(ANCHOR_NAMES):
        url = f"https://json.edhrec.com/pages/cards/{anchor_slug(name)}.json"
        body = {
            "container": {
                "json_dict": {
                    "card": {
                        "num_decks": total_decks // (2 + index * 3),
                        "potential_decks": total_decks,
                    }
                }
            }
        }
        responses[url] = [FakeResponse(json_body=body)]
    return responses


def testFetchAnchorInclusionsShouldParseAndPaceRequests():
    # arrange
    session = FakeSession(_anchor_responses())
    sleeps = []

    # act
    inclusions = fetch_anchor_inclusions(session=session, sleep=sleeps.append)

    # assert
    assert set(inclusions) == set(ANCHOR_NAMES)
    assert inclusions["Sol Ring"] == (500_000, 1_000_000)
    assert len(session.calls) == len(ANCHOR_NAMES)
    assert sleeps == [0.2] * (len(ANCHOR_NAMES) - 1)


def testFetchAnchorInclusionsShouldFailOnMalformedResponse():
    # arrange
    responses = _anchor_responses()
    first_url = (
        f"https://json.edhrec.com/pages/cards/{anchor_slug(ANCHOR_NAMES[0])}.json"
    )
    responses[first_url] = [
        FakeResponse(json_body={"container": {"json_dict": {"card": {}}}})
    ]
    session = FakeSession(responses)

    # act / assert
    with pytest.raises(ValueError, match="Sol Ring"):
        fetch_anchor_inclusions(session=session, sleep=lambda _seconds: None)


def _anchor_bulk_records():
    records = []
    for index, name in enumerate(ANCHOR_NAMES):
        records.append(
            _card(
                id=f"anchor-{index}",
                oracle_id=f"oracle-anchor-{index}",
                name=name,
                edhrec_rank=(index + 1) * 10,
                prices={"usd": "1.00"},
            )
        )
    return records


def testMainShouldWriteReportsAndPrintGauge(tmp_path, monkeypatch, capsys):
    # arrange
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))
    bulk_path = tmp_path / "bulk.jsonl"
    records = _anchor_bulk_records() + [
        _card(
            id="binned",
            oracle_id="oracle-binned",
            name="Binned Staple",
            edhrec_rank=55,
            prices={"usd": "0.12"},
        ),
        _card(
            id="junk",
            oracle_id="oracle-junk",
            name="Expensive Junk",
            edhrec_rank=30000,
            prices={"usd": "4.00"},
        ),
        _card(id="excluded", layout="token"),
    ]
    _write_plain_jsonl(bulk_path, records)
    session = FakeSession(_anchor_responses())

    # act
    exit_code = main(
        argv=["--bulk-file", str(bulk_path)],
        session=session,
        sleep=lambda _seconds: None,
    )

    # assert
    assert exit_code == 0
    run_dirs = sorted((tmp_path / "tmp" / "tcg-lister").glob("demand-*"))
    assert len(run_dirs) == 1
    report = json.loads((run_dirs[0] / "report.json").read_text())
    assert report["schema_version"] == 1
    assert report["population"]["bulk_records"] == len(records)
    assert report["population"]["excluded"]["layout"] == 1
    assert report["headline"]["fully_binned_staples_count"] == 1
    assert report["binned_staples"][0]["name"] == "Binned Staple"
    assert any(
        item["name"] == "Expensive Junk" for item in report["expensive_low_demand"]
    )
    tiers_lines = (run_dirs[0] / "tiers.csv").read_text().strip().splitlines()
    assert tiers_lines[0].startswith("tier,")
    assert len(tiers_lines) == 8
    output = capsys.readouterr().out
    assert "EDH demand proxy gauge" in output
    assert "Demand capture" in output


def testMainShouldFailWhenBulkFileMissing(tmp_path, monkeypatch, capsys):
    # arrange
    monkeypatch.setenv("BUILD_WORKSPACE_DIRECTORY", str(tmp_path))

    # act
    exit_code = main(argv=["--bulk-file", str(tmp_path / "missing.jsonl")])

    # assert
    assert exit_code == 1
    assert "demand analysis failed" in capsys.readouterr().err
