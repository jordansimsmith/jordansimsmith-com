import pytest
import requests

from fetchtcg_client import (
    BROWSER_USER_AGENT,
    FetchTcgClient,
    FetchTcgRequestError,
    ManagedListing,
    RequestBudgetExceeded,
    RunSafetyStop,
)


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class FakeResponse:
    def __init__(self, status_code, payload=None, headers=None):
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}

    def json(self):
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class FakeCookies:
    def __init__(self):
        self.clear_count = 0

    def clear(self):
        self.clear_count += 1


class FakeSession:
    def __init__(self, responses, *, headers=None):
        self.responses = iter(responses)
        self.headers = dict(headers or {})
        self.cookies = FakeCookies()
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


def _payload(content, *, total_pages=1):
    return {"searchResults": {"totalPages": total_pages, "content": content}}


def _listing(
    *,
    listing_id=123,
    scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
    quantity=2,
    status="ACTIVE",
):
    return {
        "id": listing_id,
        "card": {"externalReferences": {"scryfallId": scryfall_id}},
        "remainingQuantity": quantity,
        "status": status,
        "listedCountry": "NZ",
        "requestedCurrency": "NZD",
    }


def _client(responses, *, token="test-token", max_requests=5000, headers=None):
    clock = FakeClock()
    session = FakeSession(responses, headers=headers)
    client = FetchTcgClient(
        session=session,
        sleep=clock.sleep,
        monotonic=clock.monotonic,
        random_uniform=lambda minimum, maximum: minimum,
        token=token,
        max_requests=max_requests,
    )
    return client, session, clock


def testGetManagedListingsShouldPaginateAndReturnOnlyActiveListings():
    # arrange
    client, session, _clock = _client(
        [
            FakeResponse(200, _payload([_listing()], total_pages=2)),
            FakeResponse(
                200,
                _payload(
                    [
                        _listing(
                            listing_id=124,
                            scryfall_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                            quantity=3,
                        ),
                        _listing(listing_id=125, status="SOLD"),
                    ],
                    total_pages=2,
                ),
            ),
        ]
    )

    # act
    listings = client.get_managed_listings()

    # assert
    assert listings == [
        ManagedListing(
            listing_id=123,
            scryfall_id="9561b47c-b863-463a-8a10-56fede2cb42c",
            remaining_quantity=2,
        ),
        ManagedListing(
            listing_id=124,
            scryfall_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            remaining_quantity=3,
        ),
    ]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert all("sets" not in call["params"] for call in session.calls)
    assert all(call["params"]["gameIds"] == "mtg" for call in session.calls)
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )


@pytest.mark.parametrize("token", [None, "", "Bearer token", "two words"])
def testGetManagedListingsShouldRequireRawToken(token):
    # arrange
    client, session, _clock = _client([], token=token)

    # act / assert
    with pytest.raises(RunSafetyStop, match="FETCHTCG_TOKEN"):
        client.get_managed_listings()
    assert session.calls == []


def testGetManagedListingsShouldRejectDuplicateListingId():
    # arrange
    client, _session, _clock = _client(
        [FakeResponse(200, _payload([_listing(), _listing()]))]
    )

    # act / assert
    with pytest.raises(FetchTcgRequestError, match="duplicate listing id"):
        client.get_managed_listings()


def testGetManagedListingsShouldRejectPaginationChanges():
    # arrange
    client, _session, _clock = _client(
        [
            FakeResponse(200, _payload([_listing()], total_pages=2)),
            FakeResponse(
                200,
                _payload([_listing(listing_id=124)], total_pages=3),
            ),
        ]
    )

    # act / assert
    with pytest.raises(FetchTcgRequestError, match="totalPages changed"):
        client.get_managed_listings()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("listedCountry", "AU"),
        ("requestedCurrency", "AUD"),
        ("remainingQuantity", 0),
        ("remainingQuantity", True),
    ],
)
def testGetManagedListingsShouldRejectInvalidOwnedState(field, value):
    # arrange
    listing = _listing()
    listing[field] = value
    client, _session, _clock = _client([FakeResponse(200, _payload([listing]))])

    # act / assert
    with pytest.raises(FetchTcgRequestError):
        client.get_managed_listings()


def testClientShouldRemoveAmbientAuthCookiesAndProxyConfiguration():
    # arrange / act
    client, session, _clock = _client(
        [],
        headers={
            "Authorization": "ambient-secret",
            "Cookie": "ambient-cookie",
            "Other": "safe",
        },
    )

    # assert
    assert client is not None
    assert session.trust_env is False
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers
    assert session.headers["Other"] == "safe"
    assert session.headers["User-Agent"] == BROWSER_USER_AGENT
    assert session.cookies.clear_count == 1


def testClientShouldRetryTransientNetworkErrorAndSpaceRequests():
    # arrange
    client, session, clock = _client(
        [
            requests.ConnectionError("temporary"),
            FakeResponse(200, _payload([_listing()])),
        ]
    )

    # act
    client.get_managed_listings()

    # assert
    assert len(session.calls) == 2
    assert clock.sleeps == [0.0, 1.0]


def testClientShouldEnforceRequestBudget():
    # arrange
    client, _session, _clock = _client(
        [FakeResponse(200, _payload([_listing()]))],
        max_requests=0,
    )

    # act / assert
    with pytest.raises(RequestBudgetExceeded, match="request budget"):
        client.get_managed_listings()


def testClientShouldGuardReadOnlyAuthenticatedPath():
    # arrange
    client, session, _clock = _client([])

    # act / assert
    with pytest.raises(RunSafetyStop, match="read-only"):
        client._request_json("/v1/manage-listings", method="POST")
    with pytest.raises(RunSafetyStop, match="explicitly allowed"):
        client._request_json("/v3/cards/example", authorization="Bearer token")
    assert session.calls == []
