from main import extract_print_hints, normalize_name, rank_cards


def testExtractPrintHintsShouldReadCollectorFractionAndSetCode():
    # arrange
    text = "291/277 MID • EN JOSHUA ALVARADO"

    # act
    collector_number, words = extract_print_hints(text)

    # assert
    assert collector_number == "291"
    assert "mid" in words


def testRankCardsShouldFuzzyMatchTitleAndPreferExactPrintingHints():
    # arrange
    cards_by_name = {
        normalize_name("Dauntless Onslaught"): [
            {
                "id": "wrong-printing",
                "name": "Dauntless Onslaught",
                "set": "ddo",
                "collector_number": "12",
                "released_at": "2020-01-01",
            },
            {
                "id": "correct-printing",
                "name": "Dauntless Onslaught",
                "set": "ths",
                "collector_number": "6",
                "released_at": "2013-09-27",
            },
        ],
        normalize_name("Dauntless Unity"): [
            {
                "id": "different-card",
                "name": "Dauntless Unity",
                "set": "dmu",
                "collector_number": "9",
                "released_at": "2022-09-09",
            }
        ],
    }

    # act
    ranked = rank_cards(
        ["Dauntless Onslaucht"],
        ["6/249 THS EN"],
        cards_by_name,
        top_k=3,
    )

    # assert
    assert ranked[0]["scryfall_id"] == "correct-printing"
    assert {candidate["scryfall_id"] for candidate in ranked} == {
        "correct-printing",
        "wrong-printing",
        "different-card",
    }


def testRankCardsShouldReturnNoCandidatesWithoutTitleText():
    # arrange
    cards_by_name = {
        normalize_name("Hateful Eidolon"): [
            {
                "id": "id-a",
                "name": "Hateful Eidolon",
                "set": "thb",
                "collector_number": "101",
                "released_at": "2020-01-24",
            }
        ]
    }

    # act
    ranked = rank_cards([], ["101/254 THB"], cards_by_name, top_k=5)

    # assert
    assert ranked == []
