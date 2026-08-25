from decimal import Decimal

import pytest
import requests

from fetchtcg_client import (
    BROWSER_USER_AGENT,
    FetchTcgClient,
    FetchTcgHttpError,
    FetchTcgRequestError,
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
    def __init__(
        self, status_code, payload=None, *, headers=None, text=None, content=None
    ):
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}
        if payload is None:
            self.text = "" if text is None else text
            self.content = b"" if content is None else content
        else:
            self.text = "{}" if text is None else text
            self.content = b"{}" if content is None else content

    def json(self):
        if self._payload is None:
            raise ValueError("empty body")
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
    name="Test Card",
    collector_number="1",
    set_name="Test Set",
    condition="raw-nm",
    quantity=2,
    price=1.25,
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "card": {
            "id": card_id or f"mtg_{listing_id}_c_tst_normal",
            "name": name,
            "cardCode": collector_number,
            "externalReferences": {"scryfallId": SCRYFALL_ID},
            "set": {"id": 100, "displayName": set_name},
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
                _search_payload([_managed_payload(20, name="Alpha")], total_pages=2),
            ),
            _FakeResponse(
                200,
                _search_payload([_managed_payload(10, name="Beta")], total_pages=2),
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
            name="Alpha",
            collector_number="1",
            set_id=100,
            set_name="Test Set",
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.25"),
        ),
        ManagedListing(
            listing_id=10,
            fetch_card_id="mtg_10_c_tst_normal",
            scryfall_id=SCRYFALL_ID,
            name="Beta",
            collector_number="1",
            set_id=100,
            set_name="Test Set",
            finish="normal",
            condition="raw-nm",
            remaining_quantity=2,
            listed_price_nzd=Decimal("1.25"),
        ),
    ]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert all(call["params"]["gameIds"] == "mtg" for call in session.calls)
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )
    assert session.trust_env is False
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers
    assert session.headers["User-Agent"] == BROWSER_USER_AGENT


def test_getManagedListingsShouldSkipInactiveAndRejectPaginationDriftAndDuplicates():
    # arrange
    skip_client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _search_payload(
                    [
                        _managed_payload(1),
                        _managed_payload(2, status="SOLD"),
                    ]
                ),
            )
        ]
    )
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
    assert [listing.listing_id for listing in skip_client.get_managed_listings()] == [1]
    with pytest.raises(FetchTcgRequestError, match="totalPages changed"):
        drift_client.get_managed_listings()
    with pytest.raises(FetchTcgRequestError, match="duplicate listing id"):
        duplicate_client.get_managed_listings()


def test_deleteManagedListingShouldSendEmptyAuthenticatedDelete():
    # arrange
    client, session, _ = _client([_FakeResponse(200)])

    # act
    client.delete_managed_listing(1112665)

    # assert
    assert session.calls == [
        {
            "method": "DELETE",
            "url": "https://api.fetchtcg.com/v1/manage-listings/1112665",
            "params": None,
            "timeout": (5, 30),
            "allow_redirects": False,
            "headers": {"Authorization": "Bearer test-token"},
            "json": None,
        }
    ]


def test_deleteManagedListingShouldAcceptEmptyTwoHundredWithoutParsingJson():
    # arrange
    response = _FakeResponse(204)
    client, _, _ = _client([response])

    # act
    result = client.delete_managed_listing(1)

    # assert
    assert result is None


def test_deleteManagedListingShouldRejectNonEmptySuccessBody():
    # arrange
    client, _, _ = _client([_FakeResponse(200, text="deleted", content=b"deleted")])

    # act and assert
    with pytest.raises(FetchTcgRequestError, match="not empty"):
        client.delete_managed_listing(1)


@pytest.mark.parametrize("listing_id", [0, -1, True, "abc", None])
def test_deleteManagedListingShouldRejectInvalidListingId(listing_id):
    # arrange
    client, session, _ = _client([])

    # act and assert
    with pytest.raises(FetchTcgRequestError):
        client.delete_managed_listing(listing_id)
    assert session.calls == []


def test_requestShouldRejectUnauthenticatedOrDisallowedMutationPaths():
    # arrange
    client, session, _ = _client([])

    # act and assert
    with pytest.raises(RunSafetyStop, match="missing authorization"):
        client._request("/v1/manage-listings", method="GET")
    with pytest.raises(RunSafetyStop, match="explicitly allowed"):
        client._request(
            "/v3/cards/example",
            method="GET",
            authorization="Bearer test-token",
        )
    with pytest.raises(RunSafetyStop, match="explicitly allowed"):
        client._request(
            "/v1/manage-listings/1",
            method="POST",
            authorization="Bearer test-token",
        )
    with pytest.raises(RunSafetyStop, match="explicitly allowed"):
        client._request(
            "/v1/manage-listings/not-a-number",
            method="DELETE",
            authorization="Bearer test-token",
        )
    with pytest.raises(RunSafetyStop, match="explicitly allowed"):
        client._request(
            "/v2/private/manage-listings",
            method="DELETE",
            authorization="Bearer test-token",
        )
    assert session.calls == []


@pytest.mark.parametrize("token", [None, "", "Bearer prefixed", "has space"])
def test_authenticatedRequestsShouldRejectMissingOrMalformedToken(token):
    # arrange
    client, session, _ = _client([], token=token)

    # act and assert
    with pytest.raises(RunSafetyStop, match="missing or malformed"):
        client.get_managed_listings()
    with pytest.raises(RunSafetyStop, match="missing or malformed"):
        client.delete_managed_listing(1)
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
        client.delete_managed_listing(1)

    # assert
    assert token not in str(error.value)
    assert "401" in str(error.value)


def test_nonSuccessStatusShouldFailClosed():
    # arrange
    client, _, _ = _client([_FakeResponse(404)])

    # act and assert
    with pytest.raises(FetchTcgHttpError, match="404"):
        client.delete_managed_listing(1)


def test_requestBudgetShouldStopBeforeAdditionalRequest():
    # arrange
    client, session, _ = _client([_FakeResponse(200)], max_requests=0)

    # act and assert
    with pytest.raises(RequestBudgetExceeded, match="budget"):
        client.delete_managed_listing(1)
    assert session.calls == []


def test_transientNetworkFailureShouldRetryWithRequestSpacing():
    # arrange
    client, session, clock = _client(
        [
            requests.ConnectionError("temporary"),
            _FakeResponse(200),
        ],
        random_uniform=lambda start, _end: start,
    )

    # act
    client.delete_managed_listing(1)

    # assert
    assert len(session.calls) == 2
    assert clock.sleeps == [0.0, 1.0]
