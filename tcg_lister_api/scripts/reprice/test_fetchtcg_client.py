from decimal import Decimal

import pytest
import requests

from fetchtcg_client import (
    CardDetails,
    CompetitorListing,
    FetchTcgClient,
    FetchTcgRequestError,
    ListingUpsertRequest,
    ListingUpsertResult,
    ManagedListing,
    RequestBudgetExceeded,
    RunSafetyStop,
)


SCRYFALL_ID = "9561b47c-b863-463a-8a10-56fede2cb42c"


class _FakeClock:
    def __init__(self):
        self.current = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.current

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.current += seconds


class _FakeCookies:
    def __init__(self):
        self.clear_count = 0

    def clear(self):
        self.clear_count += 1


class _FakeResponse:
    def __init__(self, status_code, payload, *, headers=None):
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}

    def json(self):
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class _FakeSession:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []
        self.headers = {
            "Authorization": "Bearer stale",
            "Cookie": "session=stale",
            "Existing": "kept",
        }
        self.cookies = _FakeCookies()
        self.trust_env = True

    def request(
        self,
        method,
        url,
        *,
        params,
        timeout,
        allow_redirects,
        headers,
        json,
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


def _managed_payload(
    listing_id,
    *,
    card_id=None,
    condition="raw-nm",
    quantity=2,
    price=1.25,
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "card": {
            "id": card_id or f"mtg_{listing_id}_c_tst_normal",
            "externalReferences": {"scryfallId": SCRYFALL_ID},
            "set": {"id": 100},
            "printFinish": {"name": "normal"},
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


def _search_payload(content, *, total_pages=1):
    return {
        "searchResults": {
            "content": content,
            "totalPages": total_pages,
        }
    }


def _card_payload(card_id="mtg_1_c_tst_normal", *, market_price=0.76):
    return {
        "id": card_id,
        "name": "Test Card",
        "cardCode": "1",
        "setId": 100,
        "printFinishName": "normal",
        "externalReferences": {"scryfallId": SCRYFALL_ID},
        "pricingData": {"NZ": {"tcgMarketPrice": market_price}},
    }


def _competitor_payload(
    listing_id,
    *,
    price=0.75,
    quantity=2,
    condition="raw-nm",
    seller="seller",
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "remainingQuantity": quantity,
        "status": status,
        "condition": condition,
        "sellerProfileName": seller,
        "listedPriceInRequestedCurrency": price,
        "requestedCurrency": "NZD",
        "listedCountry": "NZ",
    }


def _upsert_payload(
    *,
    listing_id=1,
    quantity=2,
    condition="raw-nm",
    price=0.75,
):
    return {
        "listingId": listing_id,
        "remainingQuantity": quantity,
        "condition": condition,
        "listedPrice": price,
        "listedCurrency": "NZD",
    }


def _client(responses, **overrides):
    session = _FakeSession(responses)
    clock = _FakeClock()
    client = FetchTcgClient(
        session=session,
        sleep=clock.sleep,
        monotonic=clock.monotonic,
        random_uniform=overrides.pop("random_uniform", lambda _start, end: end),
        token=overrides.pop("token", "test-token"),
        **overrides,
    )
    return client, session, clock


def test_getManagedListingsShouldPaginateAndAuthenticateEveryPage():
    # arrange
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _search_payload([_managed_payload(20)], total_pages=2),
            ),
            _FakeResponse(
                200,
                _search_payload([_managed_payload(10)], total_pages=2),
            ),
        ]
    )

    # act
    listings = client.get_managed_listings()

    # assert
    assert listings == [
        ManagedListing(
            listing_id=20,
            fetch_card_id="mtg_20_c_tst_normal",
            scryfall_id=SCRYFALL_ID,
            set_id=100,
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.25"),
        ),
        ManagedListing(
            listing_id=10,
            fetch_card_id="mtg_10_c_tst_normal",
            scryfall_id=SCRYFALL_ID,
            set_id=100,
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.25"),
        ),
    ]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )
    assert session.trust_env is False
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers


def test_getManagedListingsShouldRejectPaginationDriftAndDuplicates():
    # arrange
    drift_client, _, _ = _client(
        [
            _FakeResponse(200, _search_payload([], total_pages=2)),
            _FakeResponse(200, _search_payload([], total_pages=3)),
        ]
    )
    duplicate_client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _search_payload(
                    [_managed_payload(1), _managed_payload(1)],
                    total_pages=1,
                ),
            )
        ]
    )

    # act and assert
    with pytest.raises(FetchTcgRequestError, match="totalPages changed"):
        drift_client.get_managed_listings()
    with pytest.raises(FetchTcgRequestError, match="duplicate listing id"):
        duplicate_client.get_managed_listings()


def test_getCardDetailsShouldValidateIdentityWithoutAuthentication():
    # arrange
    client, session, _ = _client(
        [_FakeResponse(200, _card_payload("mtg_1_c_tst_normal"))]
    )

    # act
    card = client.get_card_details("mtg_1_c_tst_normal")

    # assert
    assert card == CardDetails(
        fetch_card_id="mtg_1_c_tst_normal",
        scryfall_id=SCRYFALL_ID,
        name="Test Card",
        collector_number="1",
        set_id=100,
        finish="normal",
        market_price_nzd=Decimal("0.76"),
    )
    assert session.calls[0]["headers"] is None


def test_getCardDetailsShouldRejectMismatchedCardIdentity():
    # arrange
    client, _, _ = _client([_FakeResponse(200, _card_payload("mtg_different_card"))])

    # act and assert
    with pytest.raises(FetchTcgRequestError, match="did not match"):
        client.get_card_details("mtg_1_c_tst_normal")


def test_getCompetitorListingsShouldPaginateExcludeOwnedAndStayPublic():
    # arrange
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _search_payload(
                    [
                        _competitor_payload(1, seller="owned"),
                        _competitor_payload(10, seller="Alpha"),
                    ],
                    total_pages=2,
                ),
            ),
            _FakeResponse(
                200,
                _search_payload(
                    [_competitor_payload(11, price=1.0, seller="Beta")],
                    total_pages=2,
                ),
            ),
        ]
    )

    # act
    listings = client.get_competitor_listings(
        "mtg_1_c_tst_normal",
        excluded_listing_ids={1},
    )

    # assert
    assert listings == [
        CompetitorListing(
            listing_id=10,
            condition="raw-nm",
            seller_key="alpha",
            remaining_quantity=2,
            listed_price_nzd=Decimal("0.75"),
        ),
        CompetitorListing(
            listing_id=11,
            condition="raw-nm",
            seller_key="beta",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.0"),
        ),
    ]
    assert [call["params"]["pageOffset"] for call in session.calls] == [0, 1]
    assert all(call["headers"] is None for call in session.calls)


def test_getCompetitorListingsShouldRejectPaginationDriftAndDuplicates():
    # arrange
    drift_client, _, _ = _client(
        [
            _FakeResponse(200, _search_payload([], total_pages=2)),
            _FakeResponse(200, _search_payload([], total_pages=3)),
        ]
    )
    duplicate_client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _search_payload(
                    [_competitor_payload(10), _competitor_payload(10)],
                ),
            )
        ]
    )

    # act and assert
    with pytest.raises(FetchTcgRequestError, match="totalPages changed"):
        drift_client.get_competitor_listings("mtg_1_c_tst_normal")
    with pytest.raises(FetchTcgRequestError, match="duplicate listing id"):
        duplicate_client.get_competitor_listings("mtg_1_c_tst_normal")


def test_upsertManagedListingShouldPreserveExpectedIdentityAndAuthenticate():
    # arrange
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _upsert_payload(
                    listing_id=1,
                    quantity=4,
                    condition="raw-lp",
                    price=1.25,
                ),
            )
        ]
    )
    request = ListingUpsertRequest(
        fetch_card_id="mtg_1_c_tst_normal",
        condition="raw-lp",
        quantity=4,
        listed_price_nzd=Decimal("1.25"),
    )

    # act
    result = client.upsert_managed_listing(request, expected_listing_id=1)

    # assert
    assert result == ListingUpsertResult(
        listing_id=1,
        remaining_quantity=4,
        condition="raw-lp",
        listed_price_nzd=Decimal("1.25"),
    )
    assert session.calls[0]["method"] == "POST"
    assert session.calls[0]["headers"] == {"Authorization": "Bearer test-token"}
    assert session.calls[0]["json"] == {
        "cardId": "mtg_1_c_tst_normal",
        "condition": "raw-lp",
        "listedPrice": 1.25,
        "listedCurrency": "NZD",
        "matchPriceEnabled": False,
        "quantity": 4,
        "details": "",
    }


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (_upsert_payload(listing_id=2), "listingId did not match"),
        (_upsert_payload(quantity=3), "remainingQuantity did not match"),
        (_upsert_payload(condition="raw-lp"), "condition did not match"),
        (_upsert_payload(price=1.0), "listedPrice did not match"),
    ],
)
def test_upsertManagedListingShouldRejectMismatchedResponse(payload, message):
    # arrange
    client, _, _ = _client([_FakeResponse(200, payload)])
    request = ListingUpsertRequest(
        fetch_card_id="mtg_1_c_tst_normal",
        condition="raw-nm",
        quantity=2,
        listed_price_nzd=Decimal("0.75"),
    )

    # act and assert
    with pytest.raises(FetchTcgRequestError, match=message):
        client.upsert_managed_listing(request, expected_listing_id=1)


@pytest.mark.parametrize("token", [None, "", "Bearer prefixed", "has space"])
def test_authenticatedRequestsShouldRejectMissingOrMalformedToken(token):
    # arrange
    client, session, _ = _client([], token=token)

    # act and assert
    with pytest.raises(RunSafetyStop, match="missing or malformed"):
        client.get_managed_listings()
    assert session.calls == []


def test_authFailureShouldStopWithoutExposingToken():
    # arrange
    token = "highly-secret-token"
    client, _, _ = _client(
        [_FakeResponse(401, {"message": token})],
        token=token,
    )

    # act
    with pytest.raises(RunSafetyStop) as error:
        client.get_managed_listings()

    # assert
    assert token not in str(error.value)
    assert "401" in str(error.value)


def test_requestBudgetShouldStopBeforeAdditionalRequest():
    # arrange
    client, session, _ = _client(
        [_FakeResponse(200, _card_payload())],
        max_requests=0,
    )

    # act and assert
    with pytest.raises(RequestBudgetExceeded, match="budget"):
        client.get_card_details("mtg_1_c_tst_normal")
    assert session.calls == []


def test_transientNetworkFailureShouldRetryWithRequestSpacing():
    # arrange
    client, session, clock = _client(
        [
            requests.ConnectionError("temporary"),
            _FakeResponse(200, _card_payload()),
        ],
        random_uniform=lambda start, _end: start,
    )

    # act
    card = client.get_card_details("mtg_1_c_tst_normal")

    # assert
    assert card.fetch_card_id == "mtg_1_c_tst_normal"
    assert len(session.calls) == 2
    assert clock.sleeps == [0.0, 1.0]
