from decimal import Decimal

import pytest
import requests

import fetchtcg_client
from fetchtcg_client import (
    BROWSER_USER_AGENT,
    CardQuery,
    FetchTcgClient,
    FetchTcgHttpError,
    FetchTcgRequestError,
    IdentityResolutionError,
    ListingUpsertRequest,
    ListingUpsertResult,
    MAX_REQUESTS,
    ManagedListing,
    PriceTier,
    RequestBudgetExceeded,
    RunSafetyStop,
)


class _FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class _FakeResponse:
    def __init__(self, status_code, payload=None, headers=None):
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}

    def json(self):
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class _FakeCookies:
    def __init__(self):
        self.clear_count = 0

    def clear(self):
        self.clear_count += 1


class _FakeSession:
    def __init__(self, responses, *, headers=None):
        self.responses = iter(responses)
        self.headers = dict(headers or {})
        self.cookies = _FakeCookies()
        self.trust_env = True
        self.calls = []

    def request(
        self,
        method,
        url,
        params=None,
        timeout=None,
        allow_redirects=None,
        headers=None,
        json=None,
    ):
        self.calls.append(
            {
                "method": method,
                "url": url,
                "params": params,
                "timeout": timeout,
                "allow_redirects": allow_redirects,
                "headers": headers,
                "json": json,
            }
        )
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def _query(**overrides):
    values = {
        "name": "Spidersilk Net",
        "set_code": "dtk",
        "set_name": "Dragons of Tarkir",
        "collector_number": "244",
        "finish": "normal",
        "rarity": "common",
        "scryfall_id": "9561b47c-b863-463a-8a10-56fede2cb42c",
    }
    values.update(overrides)
    return CardQuery(**values)


def _card_payload(*, card_id="mtg_244_c_dtk_normal", total_listings=0, **overrides):
    payload = {
        "id": card_id,
        "name": "Spidersilk Net",
        "cardCode": "244",
        "setId": 2648,
        "printFinishName": "normal",
        "printVersionCode": "dtk",
        "externalReferences": {"scryfallId": "9561b47c-b863-463a-8a10-56fede2cb42c"},
        "pricingData": {"NZ": {"tcgMarketPrice": 0.4657553244}},
        "listingsData": {
            "NZ": {
                "raw-nm": {
                    "minListedPrice": 0.4 if total_listings else 0,
                    "avgListedPrice": 0.45 if total_listings else 0,
                    "currency": "NZD",
                    "totalListings": total_listings,
                    "condition": "raw-nm",
                }
            }
        },
    }
    payload.update(overrides)
    return payload


def _listing(
    price,
    quantity,
    *,
    listing_id=1,
    condition="raw-nm",
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "remainingQuantity": quantity,
        "status": status,
        "condition": condition,
        "listedPriceInRequestedCurrency": price,
        "requestedCurrency": "NZD",
        "listedCountry": "NZ",
    }


def _managed_listing(
    *,
    listing_id=123,
    card_id="mtg_cffmtg-143_R_EA_normal",
    scryfall_id="61844cbe-f4b3-45c6-bf4c-76542de4b195",
    set_id=4232,
    finish="normal",
    condition="raw-nm",
    quantity=2,
    price=5.39,
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "card": {
            "id": card_id,
            "externalReferences": {"scryfallId": scryfall_id},
            "set": {"id": set_id},
            "printFinish": {"name": finish},
        },
        "remainingQuantity": quantity,
        "status": status,
        "condition": condition,
        "listedPrice": price,
        "listedCurrency": "NZD",
        "listedPriceInRequestedCurrency": None,
        "requestedCurrency": None,
        "listedCountry": "NZ",
    }


def _upsert_response(
    *,
    listing_id=123,
    quantity=2,
    condition="raw-nm",
    price=1,
    currency="NZD",
):
    return {
        "listingId": listing_id,
        "remainingQuantity": quantity,
        "condition": condition,
        "listedPrice": price,
        "listedCurrency": currency,
        "matchPriceEnabled": False,
        "priceMatchField": None,
        "minimumPrice": None,
        "details": "",
    }


def _listings_payload(content, *, total_pages=1, page_number=0):
    return {
        "searchResults": {
            "content": content,
            "totalElements": len(content),
            "totalPages": total_pages,
            "number": page_number,
            "last": page_number + 1 >= total_pages,
        }
    }


def _client(responses, **overrides):
    session = _FakeSession(responses)
    clock = _FakeClock()
    random_uniform = overrides.pop("random_uniform", lambda _start, end: end)
    client = FetchTcgClient(
        session=session,
        sleep=clock.sleep,
        monotonic=clock.monotonic,
        random_uniform=random_uniform,
        **overrides,
    )
    return client, session, clock


def test_get_market_snapshot_uses_direct_id_and_builds_price_ladder():
    client, session, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=2)),
            _FakeResponse(
                200,
                _listings_payload(
                    [
                        _listing(0.4, 3),
                        _listing(0.49, 10),
                        _listing(0.2, 2, condition="raw-lp"),
                    ]
                ),
            ),
        ]
    )

    snapshot = client.get_market_snapshot(_query(), "raw-nm")

    assert snapshot.fetch_card_id == "mtg_244_c_dtk_normal"
    assert snapshot.market_price_nzd == Decimal("0.4657553244")
    assert snapshot.local_listing_count == 2
    assert snapshot.local_copy_count == 13
    assert snapshot.all_condition_local_copy_count == 15
    assert snapshot.lowest_local_price_nzd == Decimal("0.4")
    assert snapshot.price_ladder[Decimal("0.4")].copy_count == 3
    assert snapshot.price_ladder[Decimal("0.49")].listing_count == 1
    assert session.calls[0]["url"].endswith("/v3/cards/mtg_244_c_dtk_normal")


def test_get_market_snapshot_excludes_owned_listings_from_competition():
    client, _, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=2)),
            _FakeResponse(
                200,
                _listings_payload(
                    [
                        _listing(
                            0.26,
                            10,
                            listing_id=123,
                            condition="raw-lp",
                        ),
                        _listing(0.4, 2, listing_id=124),
                    ]
                ),
            ),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(),
        "raw-nm",
        excluded_listing_ids={123},
    )

    assert snapshot.local_listing_count == 1
    assert snapshot.local_copy_count == 2
    assert snapshot.all_condition_local_copy_count == 2
    assert snapshot.lowest_local_price_nzd == Decimal("0.4")
    assert snapshot.price_ladder == {
        Decimal("0.4"): PriceTier(listing_count=1, copy_count=2)
    }


def test_get_market_snapshot_reports_no_competition_when_all_stock_is_owned():
    client, _, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=1)),
            _FakeResponse(
                200,
                _listings_payload([_listing(0.26, 10, listing_id=123)]),
            ),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(),
        "raw-nm",
        excluded_listing_ids={123},
    )

    assert snapshot.local_listing_count == 0
    assert snapshot.local_copy_count == 0
    assert snapshot.all_condition_local_copy_count == 0
    assert snapshot.lowest_local_price_nzd is None
    assert snapshot.price_ladder == {}


def test_get_market_snapshot_falls_back_to_filter_and_search():
    client, session, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "fetch-specific-card-id"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(card_id="fetch-specific-card-id"),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(rarity="timeshifted"),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "fetch-specific-card-id"
    assert session.calls[1]["params"]["sets"] == 2648
    assert session.calls[1]["params"]["finishes"] == "normal"


@pytest.mark.parametrize(
    "override",
    [
        {"externalReferences": {"scryfallId": "11111111-1111-1111-1111-111111111111"}},
        {"externalReferences": []},
        {"id": None},
        {"cardCode": "245"},
        {"printFinishName": "foil"},
    ],
)
def test_get_market_snapshot_rejects_candidate_with_wrong_identity(override):
    wrong_card = _card_payload(**override)
    client, _, _ = _client(
        [
            _FakeResponse(200, wrong_card),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    with pytest.raises(IdentityResolutionError, match="resolve"):
        client.get_market_snapshot(_query(), "raw-nm")


def test_get_market_snapshot_distinguishes_standard_and_extended_art():
    standard_scryfall_id = "f21f9161-5945-40da-8da0-446f6a4a1c23"
    client, _, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [
                            {"id": "mtg_ffmtg-435_U_EA_normal"},
                            {"id": "mtg_ffmtg-45_U_standard_normal"},
                        ],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_ffmtg-435_U_EA_normal",
                    cardCode="435",
                    setId=4226,
                    printVersionCode="EA",
                    externalReferences={
                        "scryfallId": "f99f3a14-3a7c-4c1c-b747-c6b1144ef1f1"
                    },
                ),
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_ffmtg-45_U_standard_normal",
                    cardCode="45",
                    setId=4226,
                    printVersionCode="standard",
                    externalReferences={"scryfallId": standard_scryfall_id},
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Zack Fair",
            set_code="fin",
            set_name="Final Fantasy",
            collector_number="45",
            rarity="uncommon",
            scryfall_id=standard_scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_ffmtg-45_U_standard_normal"


def test_get_market_snapshot_resolves_treatment_specific_id_from_static_set():
    extended_art_scryfall_id = "61844cbe-f4b3-45c6-bf4c-76542de4b195"
    client, _, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [
                            {"id": "mtg_cffmtg-143_R_EA_normal"},
                            {"id": "mtg_cffmtg-38_R_standard_normal"},
                        ],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_cffmtg-143_R_EA_normal",
                    cardCode="143",
                    setId=4232,
                    printVersionCode="EA",
                    externalReferences={"scryfallId": extended_art_scryfall_id},
                ),
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_cffmtg-38_R_standard_normal",
                    cardCode="38",
                    setId=4232,
                    printVersionCode="standard",
                    externalReferences={
                        "scryfallId": "b3cc04a1-cebe-46ea-a80d-cb1340f71ad7"
                    },
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Lulu, Stern Guardian",
            set_code="fic",
            set_name="Final Fantasy Commander",
            collector_number="143",
            rarity="rare",
            scryfall_id=extended_art_scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_cffmtg-143_R_EA_normal"


def test_get_market_snapshot_ignores_collector_number_leading_zeros():
    scryfall_id = "71808155-db49-4027-a2fc-76f53475ac05"
    client, _, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "mtg_0228_c_otc_normal"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_0228_c_otc_normal",
                    cardCode="0228",
                    setId=3103,
                    externalReferences={"scryfallId": scryfall_id},
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Goblin Electromancer",
            set_code="otc",
            set_name="Outlaws of Thunder Junction Commander",
            collector_number="228",
            scryfall_id=scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_0228_c_otc_normal"


def test_get_market_snapshot_resolves_plst_moonmist_after_fetch_set_fallback(
    monkeypatch,
):
    scryfall_id = "1c5232ae-e2f9-41df-872f-e048fb7e4d08"
    monkeypatch.setitem(
        fetchtcg_client.FETCH_SET_MAPPINGS,
        "plst",
        ((3075, "Mystery Booster"), (3267, "The List")),
    )
    client, session, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {"searchResults": {"content": [], "totalPages": 1}},
            ),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "mtg_plst-195_C_normal"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_plst-195_C_normal",
                    cardCode="195",
                    setId=3267,
                    externalReferences={"scryfallId": scryfall_id},
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Moonmist",
            set_code="plst",
            set_name="The List",
            collector_number="ISD-195",
            rarity="common",
            scryfall_id=scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_plst-195_C_normal"
    assert [
        call["params"]["sets"]
        for call in session.calls
        if call["url"].endswith("/v3/cards")
    ] == [3075, 3267]


def test_get_market_snapshot_resolves_plst_mystery_booster_disenchant(monkeypatch):
    scryfall_id = "239932a9-13fc-4e94-8b05-9d58795a0bcf"
    monkeypatch.setitem(
        fetchtcg_client.FETCH_SET_MAPPINGS,
        "plst",
        ((3075, "Mystery Booster"), (3267, "The List")),
    )
    client, _, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "mtg_mb1-14_C_normal"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_mb1-14_C_normal",
                    cardCode="14",
                    setId=3075,
                    externalReferences={"scryfallId": scryfall_id},
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Disenchant",
            set_code="plst",
            set_name="The List",
            collector_number="M20-14",
            rarity="common",
            scryfall_id=scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_mb1-14_C_normal"
    assert snapshot.fetch_set_id == 3075


def test_identity_match_does_not_remove_collector_prefix_outside_plst():
    assert not FetchTcgClient._identity_matches(
        _card_payload(),
        _query(collector_number="DTK-244"),
        expected_set_ids=(2648,),
    )


def test_get_market_snapshot_searches_double_faced_card_by_front_name():
    scryfall_id = "2d6a2b68-5407-464e-a335-7866fd969c30"
    client, session, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "mtg_ffmtg-247_C_standard_normal"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="mtg_ffmtg-247_C_standard_normal",
                    cardCode="247",
                    setId=4226,
                    externalReferences={"scryfallId": scryfall_id},
                ),
            ),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(
            name="Ultimecia, Time Sorceress // Ultimecia, Omnipotent",
            set_code="fin",
            set_name="Final Fantasy",
            collector_number="247",
            rarity="uncommon",
            scryfall_id=scryfall_id,
        ),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "mtg_ffmtg-247_C_standard_normal"
    assert session.calls[1]["params"]["cardName"] == "Ultimecia, Time Sorceress"


def test_get_market_snapshot_rejects_candidate_from_wrong_fetch_set():
    client, _, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "wrong-set-card"}],
                        "totalPages": 1,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(card_id="wrong-set-card", setId=4226),
            ),
        ]
    )

    with pytest.raises(IdentityResolutionError, match="resolve"):
        client.get_market_snapshot(_query(rarity="timeshifted"), "raw-nm")


def test_get_market_snapshot_stops_before_request_for_unmapped_set():
    client, session, _ = _client([])

    with pytest.raises(RunSafetyStop, match="no static Fetch set mapping for ZZZ"):
        client.get_market_snapshot(
            _query(set_code="zzz", set_name="Unknown Set"),
            "raw-nm",
        )

    assert session.calls == []


def test_get_market_snapshot_paginates_static_set_candidates():
    client, session, _ = _client(
        [
            _FakeResponse(404, {"message": "not found"}),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "wrong-card"}],
                        "totalPages": 2,
                    }
                },
            ),
            _FakeResponse(
                200,
                _card_payload(
                    card_id="wrong-card",
                    externalReferences={
                        "scryfallId": "11111111-1111-1111-1111-111111111111"
                    },
                ),
            ),
            _FakeResponse(
                200,
                {
                    "searchResults": {
                        "content": [{"id": "exact-card"}],
                        "totalPages": 2,
                    }
                },
            ),
            _FakeResponse(200, _card_payload(card_id="exact-card")),
            _FakeResponse(200, _listings_payload([])),
        ]
    )

    snapshot = client.get_market_snapshot(
        _query(rarity="timeshifted"),
        "raw-nm",
    )

    assert snapshot.fetch_card_id == "exact-card"
    search_calls = [call for call in session.calls if call["url"].endswith("/v3/cards")]
    assert [call["params"]["pageOffset"] for call in search_calls] == [0, 1]


def test_get_market_snapshot_paginates_all_active_exact_condition_listings():
    client, session, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=3)),
            _FakeResponse(
                200,
                _listings_payload(
                    [_listing(1.0, 2), _listing(1.0, 4)],
                    total_pages=2,
                    page_number=0,
                ),
            ),
            _FakeResponse(
                200,
                _listings_payload(
                    [_listing(1.5, 1), _listing(0.5, 9, status="SOLD")],
                    total_pages=2,
                    page_number=1,
                ),
            ),
        ]
    )

    snapshot = client.get_market_snapshot(_query(), "raw-nm")

    assert snapshot.local_listing_count == 3
    assert snapshot.local_copy_count == 7
    assert snapshot.all_condition_local_copy_count == 7
    assert snapshot.price_ladder[Decimal("1.0")].listing_count == 2
    assert snapshot.price_ladder[Decimal("1.0")].copy_count == 6
    listing_calls = [
        call for call in session.calls if call["url"].endswith("/listings")
    ]
    assert [call["params"]["pageOffset"] for call in listing_calls] == [0, 1]


def test_client_sets_har_shaped_browser_user_agent():
    client, session, _ = _client([])

    assert client is not None
    assert session.headers["User-Agent"] == BROWSER_USER_AGENT
    assert "Chrome/150.0.0.0" in BROWSER_USER_AGENT


def test_client_disables_ambient_auth_and_clears_cookies():
    session = _FakeSession(
        [],
        headers={
            "Authorization": "Basic secret",
            "Cookie": "session=secret",
        },
    )
    FetchTcgClient(session=session)

    assert session.trust_env is False
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers
    assert session.cookies.clear_count == 1


def test_get_managed_listings_paginates_relevant_sets_with_explicit_auth():
    first = _managed_listing()
    second = _managed_listing(
        listing_id=124,
        card_id="mtg_ffmtg-45_U_standard_normal",
        scryfall_id="f21f9161-5945-40da-8da0-446f6a4a1c23",
        set_id=4226,
        quantity=3,
        price=0.48,
    )
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload([first], total_pages=2, page_number=0),
            ),
            _FakeResponse(
                200,
                _listings_payload([second], total_pages=2, page_number=1),
            ),
        ],
        token="test-token",
    )

    listings = client.get_managed_listings({"fic", "fin"})

    assert listings == [
        ManagedListing(
            listing_id=123,
            fetch_card_id="mtg_cffmtg-143_R_EA_normal",
            scryfall_id="61844cbe-f4b3-45c6-bf4c-76542de4b195",
            set_id=4232,
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("5.39"),
        ),
        ManagedListing(
            listing_id=124,
            fetch_card_id="mtg_ffmtg-45_U_standard_normal",
            scryfall_id="f21f9161-5945-40da-8da0-446f6a4a1c23",
            set_id=4226,
            finish="normal",
            condition="raw-nm",
            remaining_quantity=3,
            listed_price_nzd=Decimal("0.48"),
        ),
    ]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert session.calls[0]["params"]["sets"] == "4226,4232"
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )
    assert all(call["method"] == "GET" for call in session.calls)


def test_get_managed_listings_includes_split_plst_fetch_sets():
    client, session, _ = _client(
        [_FakeResponse(200, _listings_payload([]))],
        token="test-token",
    )

    client.get_managed_listings({"plst"})

    requested_set_ids = {
        int(set_id) for set_id in session.calls[0]["params"]["sets"].split(",")
    }
    assert {3075, 3267} <= requested_set_ids


def test_get_managed_listings_sends_auth_only_to_authenticated_endpoint():
    client, session, _ = _client(
        [
            _FakeResponse(200, _listings_payload([])),
            _FakeResponse(200, {"ok": True}),
        ],
        token="test-token",
    )

    client.get_managed_listings({"fic"})
    client._request_json("/public")

    assert session.calls[0]["headers"] == {"Authorization": "Bearer test-token"}
    assert session.calls[1]["headers"] is None


@pytest.mark.parametrize(
    "token", [None, "", "   ", "Bearer test-token", "token with spaces"]
)
def test_get_managed_listings_requires_raw_bearer_token(token):
    client, session, _ = _client([], token=token)

    with pytest.raises(RunSafetyStop, match="FETCHTCG_TOKEN"):
        client.get_managed_listings({"fic"})

    assert session.calls == []


def test_get_managed_listings_stops_before_request_for_unmapped_set():
    client, session, _ = _client([], token="test-token")

    with pytest.raises(RunSafetyStop, match="ZZZ"):
        client.get_managed_listings({"fic", "zzz"})

    assert session.calls == []


def test_get_managed_listings_ignores_inactive_listings():
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload(
                    [
                        _managed_listing(status="SOLD"),
                        _managed_listing(listing_id=124),
                    ]
                ),
            )
        ],
        token="test-token",
    )

    listings = client.get_managed_listings({"fic"})

    assert [listing.listing_id for listing in listings] == [124]


def test_get_managed_listings_rejects_non_positive_active_quantity():
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload([_managed_listing(quantity=0)]),
            )
        ],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="remainingQuantity"):
        client.get_managed_listings({"fic"})


def test_get_managed_listings_rejects_invalid_scryfall_id():
    listing = _managed_listing()
    listing["card"]["externalReferences"]["scryfallId"] = "not-a-uuid"
    client, _, _ = _client(
        [_FakeResponse(200, _listings_payload([listing]))],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="Scryfall id"):
        client.get_managed_listings({"fic"})


def test_get_managed_listings_rejects_non_nz_listing():
    listing = _managed_listing()
    listing["listedCountry"] = "AU"
    client, _, _ = _client(
        [_FakeResponse(200, _listings_payload([listing]))],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="listedCountry"):
        client.get_managed_listings({"fic"})


def test_get_managed_listings_uses_nzd_converted_price_when_needed():
    listing = _managed_listing(price=1.0)
    listing["listedCurrency"] = "USD"
    listing["requestedCurrency"] = "NZD"
    listing["listedPriceInRequestedCurrency"] = 1.74
    client, _, _ = _client(
        [_FakeResponse(200, _listings_payload([listing]))],
        token="test-token",
    )

    listings = client.get_managed_listings({"fic"})

    assert listings[0].listed_price_nzd == Decimal("1.74")


def test_get_managed_listings_rejects_too_many_pages():
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload([], total_pages=26),
            )
        ],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="exceeded 25 pages"):
        client.get_managed_listings({"fic"})


def test_managed_listing_auth_failure_does_not_expose_token(capsys):
    token = "secret-value-that-must-not-leak"
    client, _, _ = _client(
        [_FakeResponse(401)],
        token=token,
        verbose=True,
    )

    with pytest.raises(RunSafetyStop) as error:
        client.get_managed_listings({"fic"})

    output = capsys.readouterr()
    assert token not in str(error.value)
    assert token not in output.out
    assert token not in output.err


def test_upsert_managed_listing_posts_exact_authenticated_payload():
    client, session, _ = _client(
        [_FakeResponse(200, _upsert_response())],
        token="test-token",
    )

    result = client.upsert_managed_listing(
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-nm",
            quantity=2,
            listed_price_nzd=Decimal("1.00"),
        )
    )

    assert result == ListingUpsertResult(
        listing_id=123,
        remaining_quantity=2,
        condition="raw-nm",
        listed_price_nzd=Decimal("1"),
    )
    assert session.calls == [
        {
            "method": "POST",
            "url": "https://api.fetchtcg.com/v2/private/manage-listings",
            "params": None,
            "timeout": (5, 30),
            "allow_redirects": False,
            "headers": {"Authorization": "Bearer test-token"},
            "json": {
                "cardId": "mtg_345_u_cmr_normal",
                "condition": "raw-nm",
                "listedPrice": 1.0,
                "listedCurrency": "NZD",
                "matchPriceEnabled": False,
                "quantity": 2,
                "details": "",
            },
        }
    ]


@pytest.mark.parametrize(
    "upsert_request",
    [
        ListingUpsertRequest(
            fetch_card_id="",
            condition="raw-nm",
            quantity=1,
            listed_price_nzd=Decimal("1"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="near_mint",
            quantity=1,
            listed_price_nzd=Decimal("1"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-unknown",
            quantity=1,
            listed_price_nzd=Decimal("1"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-nm",
            quantity="1",
            listed_price_nzd=Decimal("1"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-nm",
            quantity=0,
            listed_price_nzd=Decimal("1"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-nm",
            quantity=1,
            listed_price_nzd=Decimal("NaN"),
        ),
        ListingUpsertRequest(
            fetch_card_id="mtg_345_u_cmr_normal",
            condition="raw-nm",
            quantity=1,
            listed_price_nzd=1.0,
        ),
    ],
)
def test_upsert_managed_listing_validates_request_before_network(
    upsert_request,
):
    client, session, _ = _client([], token="test-token")

    with pytest.raises(FetchTcgRequestError):
        client.upsert_managed_listing(upsert_request)

    assert session.calls == []


def test_upsert_managed_listing_rejects_non_positive_response_listing_id():
    client, _, _ = _client(
        [_FakeResponse(200, _upsert_response(listing_id=0))],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="listingId"):
        client.upsert_managed_listing(
            ListingUpsertRequest(
                fetch_card_id="mtg_345_u_cmr_normal",
                condition="raw-nm",
                quantity=2,
                listed_price_nzd=Decimal("1"),
            )
        )


def test_upsert_managed_listing_requires_existing_id_to_match_for_update():
    client, _, _ = _client(
        [_FakeResponse(200, _upsert_response(listing_id=124))],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match="listingId"):
        client.upsert_managed_listing(
            ListingUpsertRequest(
                fetch_card_id="mtg_345_u_cmr_normal",
                condition="raw-nm",
                quantity=2,
                listed_price_nzd=Decimal("1"),
            ),
            expected_listing_id=123,
        )


@pytest.mark.parametrize(
    ("response_overrides", "message"),
    [
        ({"quantity": 3}, "remainingQuantity"),
        ({"condition": "raw-lp"}, "condition"),
        ({"price": 2}, "listedPrice"),
        ({"currency": "AUD"}, "listedCurrency"),
    ],
)
def test_upsert_managed_listing_rejects_response_mismatch(response_overrides, message):
    client, _, _ = _client(
        [_FakeResponse(200, _upsert_response(**response_overrides))],
        token="test-token",
    )

    with pytest.raises(FetchTcgRequestError, match=message):
        client.upsert_managed_listing(
            ListingUpsertRequest(
                fetch_card_id="mtg_345_u_cmr_normal",
                condition="raw-nm",
                quantity=2,
                listed_price_nzd=Decimal("1"),
            )
        )


def test_upsert_managed_listing_retries_same_absolute_payload():
    client, session, _ = _client(
        [
            _FakeResponse(500, {}),
            _FakeResponse(200, _upsert_response()),
        ],
        token="test-token",
    )
    request = ListingUpsertRequest(
        fetch_card_id="mtg_345_u_cmr_normal",
        condition="raw-nm",
        quantity=2,
        listed_price_nzd=Decimal("1"),
    )

    client.upsert_managed_listing(request)

    assert [call["method"] for call in session.calls] == ["POST", "POST"]
    assert session.calls[0]["json"] == session.calls[1]["json"]


@pytest.mark.parametrize(
    ("path", "method"),
    [
        ("/public", "GET"),
        ("/v2/private/manage-listings", "GET"),
    ],
)
def test_request_rejects_unapproved_authenticated_method_and_path(path, method):
    client, session, _ = _client([], token="test-token")

    with pytest.raises(RunSafetyStop, match="authenticated request path"):
        client._request_json(
            path,
            authorization="Bearer test-token",
            method=method,
        )

    assert session.calls == []


@pytest.mark.parametrize(
    ("path", "method"),
    [
        ("/v1/manage-listings", "GET"),
        ("/v2/private/manage-listings", "POST"),
    ],
)
def test_request_rejects_missing_auth_for_private_method_and_path(path, method):
    client, session, _ = _client([], token="test-token")

    with pytest.raises(RunSafetyStop, match="missing authorization"):
        client._request_json(path, method=method)

    assert session.calls == []


def test_request_disables_redirects():
    client, session, _ = _client([_FakeResponse(302, {})])

    with pytest.raises(FetchTcgHttpError) as error:
        client._request_json("/test")

    assert error.value.status_code == 302
    assert session.calls[0]["allow_redirects"] is False


def test_two_successful_requests_use_random_interval_between_one_and_two_seconds():
    random_ranges = []

    def random_uniform(start, end):
        random_ranges.append((start, end))
        return 1.25

    client, _, clock = _client(
        [
            _FakeResponse(200, {"first": True}),
            _FakeResponse(200, {"second": True}),
        ],
        random_uniform=random_uniform,
    )

    client._request_json("/first")
    client._request_json("/second")

    assert random_ranges == [(1.0, 2.0)]
    assert clock.sleeps == [1.25]


def test_request_retries_transient_status_with_exponential_backoff():
    client, session, clock = _client(
        [
            _FakeResponse(503, {"message": "unavailable"}),
            _FakeResponse(200, {"ok": True}),
        ]
    )

    assert client._request_json("/test") == {"ok": True}
    assert len(session.calls) == 2
    assert clock.sleeps == [2.0]


def test_request_honors_retry_after_for_rate_limit():
    client, _, clock = _client(
        [
            _FakeResponse(429, {}, headers={"Retry-After": "7"}),
            _FakeResponse(200, {"ok": True}),
        ]
    )

    assert client._request_json("/test") == {"ok": True}
    assert clock.sleeps == [7.0]


def test_request_stops_when_retry_after_exceeds_safety_cap():
    client, _, clock = _client(
        [_FakeResponse(429, {}, headers={"Retry-After": "3600"})]
    )

    with pytest.raises(RunSafetyStop, match="Retry-After"):
        client._request_json("/test")

    assert clock.sleeps == []


def test_request_retries_transport_failure():
    client, _, clock = _client(
        [
            requests.Timeout("slow"),
            _FakeResponse(200, {"ok": True}),
        ]
    )

    assert client._request_json("/test") == {"ok": True}
    assert clock.sleeps == [2.0]


def test_request_retries_malformed_success_payload():
    client, _, clock = _client(
        [
            _FakeResponse(200, ["unexpected"]),
            _FakeResponse(200, {"ok": True}),
        ]
    )

    assert client._request_json("/test") == {"ok": True}
    assert clock.sleeps == [2.0]


def test_request_does_not_retry_not_found():
    client, session, _ = _client([_FakeResponse(404, {"message": "missing"})])

    with pytest.raises(FetchTcgHttpError) as error:
        client._request_json("/test")

    assert error.value.status_code == 404
    assert len(session.calls) == 1


def test_request_stops_run_on_authorization_failure():
    client, _, _ = _client([_FakeResponse(403, {"message": "forbidden"})])

    with pytest.raises(RunSafetyStop, match="403"):
        client._request_json("/test")


def test_request_stops_run_after_repeated_rate_limits():
    client, _, _ = _client(
        [
            _FakeResponse(429, {}),
            _FakeResponse(429, {}),
            _FakeResponse(429, {}),
        ]
    )

    with pytest.raises(RunSafetyStop, match="rate limits"):
        client._request_json("/test")


def test_request_budget_stops_run_before_excess_request():
    client, session, _ = _client(
        [_FakeResponse(200, {"ok": True})],
        max_requests=0,
    )

    with pytest.raises(RequestBudgetExceeded):
        client._request_json("/test")

    assert session.calls == []


def test_default_request_budget_supports_hundred_card_runs():
    assert MAX_REQUESTS == 1000


def test_get_market_snapshot_ignores_missing_condition_summary():
    card = _card_payload(listingsData={"NZ": {}})
    client, _, _ = _client(
        [
            _FakeResponse(200, card),
            _FakeResponse(200, _listings_payload([], total_pages=0)),
        ]
    )

    snapshot = client.get_market_snapshot(_query(), "raw-nm")

    assert snapshot.local_listing_count == 0
    assert snapshot.local_copy_count == 0


@pytest.mark.parametrize("summary_listing_count", [0, 1, 3])
def test_get_market_snapshot_trusts_detailed_listings_over_summary(
    summary_listing_count,
):
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _card_payload(total_listings=summary_listing_count),
            ),
            _FakeResponse(
                200,
                _listings_payload([_listing(0.5, 1), _listing(1.0, 1)]),
            ),
        ]
    )

    snapshot = client.get_market_snapshot(_query(), "raw-nm")

    assert snapshot.local_listing_count == 2
    assert snapshot.local_copy_count == 2


def test_get_market_snapshot_rejects_non_finite_listing_price():
    client, _, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=1)),
            _FakeResponse(200, _listings_payload([_listing("NaN", 1)])),
        ]
    )

    with pytest.raises(FetchTcgRequestError, match="listedPrice"):
        client.get_market_snapshot(_query(), "raw-nm")


def test_get_market_snapshot_rejects_fractional_remaining_quantity():
    client, _, _ = _client(
        [
            _FakeResponse(200, _card_payload(total_listings=1)),
            _FakeResponse(200, _listings_payload([_listing(0.4, 1.5)])),
        ]
    )

    with pytest.raises(FetchTcgRequestError, match="remainingQuantity"):
        client.get_market_snapshot(_query(), "raw-nm")
