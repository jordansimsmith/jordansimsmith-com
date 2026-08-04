from decimal import Decimal

import pytest
import requests

from fetchtcg_client import (
    BROWSER_USER_AGENT,
    CardDetails,
    CompetitorListing,
    FetchTcgClient,
    FetchTcgRequestError,
    MAX_REQUESTS,
    ManagedListing,
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


def _managed_payload(content, *, total_pages=1):
    return {"searchResults": {"totalPages": total_pages, "content": content}}


def _managed_listing(
    *,
    listing_id=123,
    card_id="mtg_244_c_dtk_normal",
    scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
    set_id=2648,
    finish="normal",
    condition="raw-nm",
    quantity=2,
    price=2,
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


def _card_payload(**overrides):
    payload = {
        "id": "mtg_244_c_dtk_normal",
        "name": "Spidersilk Net",
        "cardCode": "244",
        "setId": 2648,
        "printFinishName": "normal",
        "externalReferences": {"scryfallId": "9561b47c-b863-463a-8a10-56fede2cb42c"},
        "pricingData": {"NZ": {"tcgMarketPrice": 0.4657553244}},
    }
    payload.update(overrides)
    return payload


def _listings_payload(content, *, total_pages=1):
    return {"searchResults": {"totalPages": total_pages, "content": content}}


def _competitor_listing(
    price,
    quantity,
    *,
    listing_id=1,
    condition="raw-nm",
    seller="seller",
    status="ACTIVE",
    country="NZ",
    currency="NZD",
):
    return {
        "id": listing_id,
        "remainingQuantity": quantity,
        "status": status,
        "condition": condition,
        "sellerProfileName": seller,
        "listedPriceInRequestedCurrency": price,
        "requestedCurrency": currency,
        "listedCountry": country,
    }


def _client(responses, *, token="test-token", max_requests=MAX_REQUESTS, headers=None):
    clock = _FakeClock()
    session = _FakeSession(responses, headers=headers)
    client = FetchTcgClient(
        session=session,
        sleep=clock.sleep,
        monotonic=clock.monotonic,
        random_uniform=lambda minimum, maximum: minimum,
        token=token,
        max_requests=max_requests,
    )
    return client, session, clock


def test_get_managed_listings_paginates_all_mtg_nzd_without_set_filter():
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _managed_payload([_managed_listing()], total_pages=2),
            ),
            _FakeResponse(
                200,
                _managed_payload(
                    [
                        _managed_listing(
                            listing_id=124,
                            condition="raw-lp",
                            quantity=3,
                            price=1.75,
                        ),
                        _managed_listing(listing_id=125, status="SOLD"),
                    ],
                    total_pages=2,
                ),
            ),
        ]
    )

    listings = client.get_managed_listings()

    assert listings == [
        ManagedListing(
            listing_id=123,
            fetch_card_id="mtg_244_c_dtk_normal",
            scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
            set_id=2648,
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("2"),
        ),
        ManagedListing(
            listing_id=124,
            fetch_card_id="mtg_244_c_dtk_normal",
            scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
            set_id=2648,
            finish="normal",
            condition="raw-lp",
            remaining_quantity=3,
            listed_price_nzd=Decimal("1.75"),
        ),
    ]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert all("sets" not in call["params"] for call in session.calls)
    assert all(call["params"]["gameIds"] == "mtg" for call in session.calls)
    assert all(call["params"]["currencyCode"] == "NZD" for call in session.calls)
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )


def test_get_managed_listings_rejects_non_nz_listing():
    listing = _managed_listing()
    listing["listedCountry"] = "AU"
    client, _, _ = _client([_FakeResponse(200, _managed_payload([listing]))])

    with pytest.raises(FetchTcgRequestError, match="listedCountry"):
        client.get_managed_listings()


@pytest.mark.parametrize("token", [None, "", "Bearer token", "two words"])
def test_get_managed_listings_requires_raw_token(token):
    client, session, _ = _client([], token=token)

    with pytest.raises(RunSafetyStop, match="FETCHTCG_TOKEN"):
        client.get_managed_listings()

    assert session.calls == []


def test_get_card_details_parses_exact_identity_and_market_price():
    client, session, _ = _client([_FakeResponse(200, _card_payload())])

    details = client.get_card_details("mtg_244_c_dtk_normal")

    assert details == CardDetails(
        fetch_card_id="mtg_244_c_dtk_normal",
        scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
        name="Spidersilk Net",
        collector_number="244",
        set_id=2648,
        finish="normal",
        market_price_nzd=Decimal("0.4657553244"),
    )
    assert session.calls[0]["headers"] is None


def test_get_card_details_accepts_missing_market_price():
    client, _, _ = _client([_FakeResponse(200, _card_payload(pricingData=None))])

    assert client.get_card_details("mtg_244_c_dtk_normal").market_price_nzd is None


def test_get_card_details_rejects_mismatched_card_id():
    client, _, _ = _client([_FakeResponse(200, _card_payload(id="mtg_different_card"))])

    with pytest.raises(FetchTcgRequestError, match="did not match"):
        client.get_card_details("mtg_244_c_dtk_normal")


def test_get_competitor_listings_paginates_filters_and_excludes_owned_ids():
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload(
                    [
                        _competitor_listing(1.5, 2, listing_id=1, seller=" Alpha "),
                        _competitor_listing(
                            1.6, 3, listing_id=100, seller="Current user"
                        ),
                        _competitor_listing(1.7, 1, listing_id=2, status="SOLD"),
                    ],
                    total_pages=2,
                ),
            ),
            _FakeResponse(
                200,
                _listings_payload(
                    [
                        _competitor_listing(
                            1.75,
                            4,
                            listing_id=3,
                            condition="raw-lp",
                            seller="Beta",
                        ),
                        _competitor_listing(
                            1.8,
                            1,
                            listing_id=4,
                            country="AU",
                        ),
                    ],
                    total_pages=2,
                ),
            ),
        ]
    )

    listings = client.get_competitor_listings(
        "mtg_244_c_dtk_normal", excluded_listing_ids={100}
    )

    assert listings == [
        CompetitorListing(
            listing_id=1,
            condition="raw-nm",
            seller_key="alpha",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.5"),
        ),
        CompetitorListing(
            listing_id=3,
            condition="raw-lp",
            seller_key="beta",
            remaining_quantity=4,
            listed_price_nzd=Decimal("1.75"),
        ),
    ]
    assert [call["params"]["pageOffset"] for call in session.calls] == [0, 1]
    assert all(call["headers"] is None for call in session.calls)


def test_get_competitor_listings_rejects_invalid_excluded_id():
    client, session, _ = _client([])

    with pytest.raises(FetchTcgRequestError, match="excluded listing ids"):
        client.get_competitor_listings("mtg_244_c_dtk_normal", excluded_listing_ids={0})

    assert session.calls == []


@pytest.mark.parametrize("seller", [None, "", "   ", 123])
def test_get_competitor_listings_rejects_invalid_seller(seller):
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _listings_payload([_competitor_listing(1, 1, seller=seller)]),
            )
        ]
    )

    with pytest.raises(FetchTcgRequestError, match="seller profile name"):
        client.get_competitor_listings("mtg_244_c_dtk_normal")


def test_client_removes_ambient_auth_cookies_and_proxy_configuration():
    client, session, _ = _client(
        [],
        headers={
            "Authorization": "ambient-secret",
            "Cookie": "ambient-cookie",
            "Other": "safe",
        },
    )

    assert client is not None
    assert session.trust_env is False
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers
    assert session.headers["Other"] == "safe"
    assert session.headers["User-Agent"] == BROWSER_USER_AGENT
    assert session.cookies.clear_count == 1


def test_client_retries_transient_network_error_and_spaces_requests():
    client, session, clock = _client(
        [
            requests.ConnectionError("temporary"),
            _FakeResponse(200, _card_payload()),
        ]
    )

    client.get_card_details("mtg_244_c_dtk_normal")

    assert len(session.calls) == 2
    assert clock.sleeps == [0.0, 1.0]


def test_client_stops_after_repeated_rate_limits():
    client, _, _ = _client(
        [
            _FakeResponse(429, {}, {"Retry-After": "0"}),
            _FakeResponse(429, {}, {"Retry-After": "0"}),
            _FakeResponse(429, {}, {"Retry-After": "0"}),
        ]
    )

    with pytest.raises(RunSafetyStop, match="repeated rate limits"):
        client.get_card_details("mtg_244_c_dtk_normal")


def test_client_enforces_request_budget():
    client, _, _ = _client([_FakeResponse(200, _card_payload())], max_requests=0)

    with pytest.raises(RequestBudgetExceeded, match="request budget"):
        client.get_card_details("mtg_244_c_dtk_normal")


def test_client_has_read_only_request_guard():
    client, session, _ = _client([])

    with pytest.raises(RunSafetyStop, match="read-only"):
        client._request_json("/v1/manage-listings", method="POST")

    assert session.calls == []
