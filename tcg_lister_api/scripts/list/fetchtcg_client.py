import email.utils
import random
import re
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Callable
from urllib.parse import quote

import requests

from fetch_set_mapping import FETCH_SET_MAPPINGS


BASE_URL = "https://api.fetchtcg.com"
BROWSER_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/150.0.0.0 Safari/537.36"
)
MIN_REQUEST_INTERVAL_SECONDS = 1.0
MAX_REQUEST_INTERVAL_SECONDS = 2.0
CONNECT_TIMEOUT_SECONDS = 5
READ_TIMEOUT_SECONDS = 30
MAX_ATTEMPTS = 5
MAX_REQUESTS = 1000
MAX_RATE_LIMIT_RESPONSES = 3
MAX_BACKOFF_SECONDS = 60.0
MAX_CARD_SEARCH_PAGES = 5
MAX_LISTING_PAGES = 25
MAX_MANAGED_LISTING_PAGES = 25
LISTING_PAGE_SIZE = 20
MANAGED_LISTING_PAGE_SIZE = 20
MANAGED_LISTINGS_PATH = "/v1/manage-listings"
MANAGED_LISTING_UPSERT_PATH = "/v2/private/manage-listings"
AUTHENTICATED_REQUESTS = frozenset(
    (
        ("GET", MANAGED_LISTINGS_PATH),
        ("POST", MANAGED_LISTING_UPSERT_PATH),
    )
)
CONDITION_QUALITY = {
    "raw-d": 0,
    "raw-hp": 1,
    "raw-mp": 2,
    "raw-lp": 3,
    "raw-nm": 4,
    "raw-m": 5,
}
MANAGED_LISTING_CONDITIONS = frozenset(CONDITION_QUALITY)

RARITY_CODES = {
    "common": "c",
    "uncommon": "u",
    "rare": "r",
    "mythic": "m",
    "mythic_rare": "m",
    "special": "s",
    "bonus": "b",
    "timeshifted": "t",
}


class FetchTcgError(RuntimeError):
    pass


class FetchTcgHttpError(FetchTcgError):
    def __init__(self, status_code, path):
        super().__init__(f"Fetch returned HTTP {status_code} for {path}")
        self.status_code = status_code
        self.path = path


class FetchTcgRequestError(FetchTcgError):
    pass


class IdentityResolutionError(FetchTcgError):
    pass


class RunSafetyStop(FetchTcgError):
    pass


class RequestBudgetExceeded(RunSafetyStop):
    pass


@dataclass(frozen=True)
class CardQuery:
    name: str
    set_code: str
    set_name: str
    collector_number: str
    finish: str
    rarity: str
    scryfall_id: str


@dataclass(frozen=True)
class PriceTier:
    listing_count: int
    copy_count: int
    seller_keys: frozenset[str] = frozenset()

    @property
    def seller_count(self):
        return len(self.seller_keys)


@dataclass(frozen=True)
class MarketSnapshot:
    fetch_card_id: str
    fetch_set_id: int
    market_price_nzd: Decimal | None
    local_listing_count: int
    local_copy_count: int
    all_condition_local_seller_count: int
    all_condition_local_copy_count: int
    lowest_local_price_nzd: Decimal | None
    price_ladder: dict[Decimal, PriceTier]
    better_condition_lowest_price_nzd: Decimal | None = None


@dataclass(frozen=True)
class ManagedListing:
    listing_id: int
    fetch_card_id: str
    scryfall_id: str
    set_id: int
    finish: str
    condition: str
    remaining_quantity: int
    listed_price_nzd: Decimal


@dataclass(frozen=True)
class ListingUpsertRequest:
    fetch_card_id: str
    condition: str
    quantity: int
    listed_price_nzd: Decimal


@dataclass(frozen=True)
class ListingUpsertResult:
    listing_id: int
    remaining_quantity: int
    condition: str
    listed_price_nzd: Decimal


class FetchTcgClient:
    def __init__(
        self,
        *,
        session=None,
        sleep: Callable[[float], None] = time.sleep,
        monotonic: Callable[[], float] = time.monotonic,
        random_uniform: Callable[[float, float], float] = random.uniform,
        token=None,
        verbose=False,
        max_requests=MAX_REQUESTS,
    ):
        self._session = session or requests.Session()
        self._sleep = sleep
        self._monotonic = monotonic
        self._random_uniform = random_uniform
        self._token = token
        self._verbose = verbose
        self._max_requests = max_requests
        self._request_count = 0
        self._rate_limit_responses = 0
        self._last_request_started = None
        if hasattr(self._session, "trust_env"):
            self._session.trust_env = False
        for header in list(self._session.headers):
            if header.casefold() in ("authorization", "cookie"):
                del self._session.headers[header]
        self._session.headers.update(
            {
                "Accept": "application/json, text/plain, */*",
                "Accept-Language": "en-GB,en;q=0.5",
                "Origin": "https://www.fetchtcg.com",
                "Referer": "https://www.fetchtcg.com/",
                "User-Agent": BROWSER_USER_AGENT,
                "X-App-Version": "unknown",
            }
        )
        self._clear_session_cookies()

    def get_managed_listings(self, set_codes):
        authorization = self._authorization_header()
        fetch_set_ids = set()
        missing_set_codes = []
        for set_code in sorted({str(code).casefold() for code in set_codes}):
            mappings = FETCH_SET_MAPPINGS.get(set_code)
            if not mappings:
                missing_set_codes.append(set_code.upper())
                continue
            fetch_set_ids.update(fetch_id for fetch_id, _ in mappings)
        if missing_set_codes:
            raise RunSafetyStop(
                "no static Fetch set mapping for " + ", ".join(missing_set_codes)
            )
        if not fetch_set_ids:
            return []

        listings = []
        page = 0
        total_pages = 1
        while page < total_pages:
            payload = self._request_json(
                MANAGED_LISTINGS_PATH,
                params={
                    "page": page,
                    "size": MANAGED_LISTING_PAGE_SIZE,
                    "sort": "listed_at,DESC",
                    "gameIds": "mtg",
                    "sets": ",".join(str(set_id) for set_id in sorted(fetch_set_ids)),
                    "currencyCode": "NZD",
                },
                authorization=authorization,
            )
            results = self._require_mapping(
                payload.get("searchResults"), "managed listing searchResults"
            )
            if page == 0:
                total_pages = self._required_nonnegative_int(
                    results.get("totalPages"),
                    "managed listing searchResults.totalPages",
                )
                if total_pages > MAX_MANAGED_LISTING_PAGES:
                    raise FetchTcgRequestError(
                        "managed listing result exceeded "
                        f"{MAX_MANAGED_LISTING_PAGES} pages"
                    )

            for value in self._require_list(
                results.get("content"), "managed listing searchResults.content"
            ):
                listing = self._require_mapping(value, "managed listing")
                status = listing.get("status")
                if not isinstance(status, str):
                    raise FetchTcgRequestError(
                        "managed listing status was missing or invalid"
                    )
                if status != "ACTIVE":
                    continue

                listing_id = self._required_nonnegative_int(
                    listing.get("id"), "managed listing id"
                )
                if listing_id == 0:
                    raise FetchTcgRequestError("managed listing id must be positive")
                card = self._require_mapping(
                    listing.get("card"), "managed listing card"
                )
                fetch_card_id = card.get("id")
                if not isinstance(fetch_card_id, str) or not fetch_card_id:
                    raise FetchTcgRequestError(
                        "managed listing card id was missing or invalid"
                    )
                references = self._require_mapping(
                    card.get("externalReferences"),
                    "managed listing card externalReferences",
                )
                scryfall_id = references.get("scryfallId")
                if not isinstance(scryfall_id, str) or not scryfall_id:
                    raise FetchTcgRequestError(
                        "managed listing Scryfall id was missing or invalid"
                    )
                try:
                    uuid.UUID(scryfall_id)
                except ValueError as error:
                    raise FetchTcgRequestError(
                        "managed listing Scryfall id was missing or invalid"
                    ) from error
                fetch_set = self._require_mapping(
                    card.get("set"), "managed listing card set"
                )
                set_id = self._required_nonnegative_int(
                    fetch_set.get("id"), "managed listing card set id"
                )
                if set_id not in fetch_set_ids:
                    raise FetchTcgRequestError(
                        "managed listing card set was outside requested sets"
                    )
                print_finish = self._require_mapping(
                    card.get("printFinish"),
                    "managed listing card printFinish",
                )
                finish = print_finish.get("name")
                if not isinstance(finish, str) or not finish:
                    raise FetchTcgRequestError(
                        "managed listing finish was missing or invalid"
                    )
                condition = listing.get("condition")
                if not isinstance(condition, str) or not condition:
                    raise FetchTcgRequestError(
                        "managed listing condition was missing or invalid"
                    )
                remaining_quantity = self._required_nonnegative_int(
                    listing.get("remainingQuantity"),
                    "managed listing remainingQuantity",
                )
                if remaining_quantity == 0:
                    raise FetchTcgRequestError(
                        "managed listing remainingQuantity must be positive"
                    )
                if listing.get("listedCountry") != "NZ":
                    raise FetchTcgRequestError(
                        "managed listing listedCountry was not NZ"
                    )
                requested_currency = listing.get("requestedCurrency")
                if requested_currency not in (None, "NZD"):
                    raise FetchTcgRequestError(
                        "managed listing requestedCurrency was invalid"
                    )
                converted_price = listing.get("listedPriceInRequestedCurrency")
                if requested_currency == "NZD" and converted_price is not None:
                    listed_price = self._required_nonnegative_decimal(
                        converted_price,
                        "managed listing listedPriceInRequestedCurrency",
                    )
                elif listing.get("listedCurrency") == "NZD":
                    listed_price = self._required_nonnegative_decimal(
                        listing.get("listedPrice"),
                        "managed listing listedPrice",
                    )
                else:
                    raise FetchTcgRequestError(
                        "managed listing did not provide an NZD price"
                    )
                listings.append(
                    ManagedListing(
                        listing_id=listing_id,
                        fetch_card_id=fetch_card_id,
                        scryfall_id=scryfall_id.casefold(),
                        set_id=set_id,
                        finish=finish.casefold(),
                        condition=condition,
                        remaining_quantity=remaining_quantity,
                        listed_price_nzd=listed_price,
                    )
                )
            page += 1
        return listings

    def upsert_managed_listing(self, request, *, expected_listing_id=None):
        if not isinstance(request, ListingUpsertRequest):
            raise FetchTcgRequestError("listing upsert request was missing or invalid")
        if not isinstance(
            request.fetch_card_id, str
        ) or not request.fetch_card_id.startswith("mtg_"):
            raise FetchTcgRequestError(
                "listing upsert Fetch card id was missing or invalid"
            )
        if request.condition not in MANAGED_LISTING_CONDITIONS:
            raise FetchTcgRequestError(
                "listing upsert condition was missing or invalid"
            )
        if isinstance(request.quantity, bool) or not isinstance(request.quantity, int):
            raise FetchTcgRequestError("listing upsert quantity was missing or invalid")
        quantity = self._required_nonnegative_int(
            request.quantity, "listing upsert quantity"
        )
        if quantity == 0:
            raise FetchTcgRequestError("listing upsert quantity must be positive")
        if not isinstance(request.listed_price_nzd, Decimal):
            raise FetchTcgRequestError(
                "listing upsert listed price was missing or invalid"
            )
        listed_price = self._required_nonnegative_decimal(
            request.listed_price_nzd, "listing upsert listed price"
        )
        if expected_listing_id is not None:
            expected_listing_id = self._required_nonnegative_int(
                expected_listing_id, "expected listingId"
            )
            if expected_listing_id == 0:
                raise FetchTcgRequestError("expected listingId must be positive")

        payload = self._request_json(
            MANAGED_LISTING_UPSERT_PATH,
            method="POST",
            json_body={
                "cardId": request.fetch_card_id,
                "condition": request.condition,
                "listedPrice": float(listed_price),
                "listedCurrency": "NZD",
                "matchPriceEnabled": False,
                "quantity": quantity,
                "details": "",
            },
            authorization=self._authorization_header(),
        )
        listing_id = self._required_nonnegative_int(
            payload.get("listingId"), "listing upsert response listingId"
        )
        if listing_id == 0:
            raise FetchTcgRequestError(
                "listing upsert response listingId must be positive"
            )
        if expected_listing_id is not None and listing_id != expected_listing_id:
            raise FetchTcgRequestError(
                "listing upsert response listingId did not match existing listing"
            )
        remaining_quantity = self._required_nonnegative_int(
            payload.get("remainingQuantity"),
            "listing upsert response remainingQuantity",
        )
        if remaining_quantity != quantity:
            raise FetchTcgRequestError(
                "listing upsert response remainingQuantity did not match request"
            )
        if payload.get("condition") != request.condition:
            raise FetchTcgRequestError(
                "listing upsert response condition did not match request"
            )
        if payload.get("listedCurrency") != "NZD":
            raise FetchTcgRequestError(
                "listing upsert response listedCurrency was not NZD"
            )
        response_price = self._required_nonnegative_decimal(
            payload.get("listedPrice"),
            "listing upsert response listedPrice",
        )
        if response_price != listed_price:
            raise FetchTcgRequestError(
                "listing upsert response listedPrice did not match request"
            )
        return ListingUpsertResult(
            listing_id=listing_id,
            remaining_quantity=remaining_quantity,
            condition=request.condition,
            listed_price_nzd=response_price,
        )

    def get_market_snapshot(
        self,
        query,
        condition_code,
        *,
        excluded_listing_ids=(),
    ):
        if condition_code not in CONDITION_QUALITY:
            raise FetchTcgRequestError("condition code was missing or invalid")
        excluded_listing_ids = frozenset(excluded_listing_ids)
        if any(
            isinstance(listing_id, bool)
            or not isinstance(listing_id, int)
            or listing_id <= 0
            for listing_id in excluded_listing_ids
        ):
            raise FetchTcgRequestError("excluded listing ids were missing or invalid")
        card = self._resolve_card(query)
        card_id = str(card["id"])
        pricing_data = card.get("pricingData")
        if pricing_data is None:
            market_price = None
        else:
            pricing_data = self._require_mapping(pricing_data, "card pricingData")
            nz_pricing = self._require_mapping(
                pricing_data.get("NZ"), "card pricingData.NZ"
            )
            market_price = self._decimal_or_none(nz_pricing.get("tcgMarketPrice"))

        (
            listing_count,
            copy_count,
            all_condition_seller_count,
            all_condition_copy_count,
            price_ladder,
            better_condition_lowest_price,
        ) = self._get_listings(
            card_id,
            condition_code,
            excluded_listing_ids=excluded_listing_ids,
        )
        return MarketSnapshot(
            fetch_card_id=card_id,
            fetch_set_id=self._required_nonnegative_int(
                card.get("setId"), "card setId"
            ),
            market_price_nzd=market_price,
            local_listing_count=listing_count,
            local_copy_count=copy_count,
            all_condition_local_seller_count=all_condition_seller_count,
            all_condition_local_copy_count=all_condition_copy_count,
            lowest_local_price_nzd=min(price_ladder) if price_ladder else None,
            price_ladder=price_ladder,
            better_condition_lowest_price_nzd=better_condition_lowest_price,
        )

    def _resolve_card(self, query):
        set_mappings = FETCH_SET_MAPPINGS.get(query.set_code.casefold())
        if not set_mappings:
            raise RunSafetyStop(
                f"no static Fetch set mapping for {query.set_code.upper()}"
            )
        expected_set_ids = tuple(fetch_id for fetch_id, _ in set_mappings)
        search_name = query.name.partition("//")[0].strip()

        direct_id = self._direct_card_id(query)
        if direct_id:
            try:
                card = self._request_json(f"/v3/cards/{quote(direct_id, safe='_-.')}")
                if self._identity_matches(
                    card,
                    query,
                    expected_set_ids=expected_set_ids,
                ):
                    return card
                self._log(f"direct card id {direct_id} did not match ManaBox identity")
            except FetchTcgHttpError as error:
                if error.status_code != 404:
                    raise
                self._log(f"direct card id {direct_id} was not found")

        matches = []
        for expected_set_id in expected_set_ids:
            page_offset = 0
            while True:
                search = self._request_json(
                    "/v3/cards",
                    params={
                        "pageSize": 48,
                        "pageOffset": page_offset,
                        "sort": "DATE_DESC",
                        "cardName": search_name,
                        "gameIds": "mtg",
                        "sets": expected_set_id,
                        "finishes": query.finish,
                    },
                )
                search_results = self._require_mapping(
                    search.get("searchResults"), "card searchResults"
                )
                total_pages = self._required_nonnegative_int(
                    search_results.get("totalPages"),
                    "card searchResults.totalPages",
                )
                if total_pages > MAX_CARD_SEARCH_PAGES:
                    raise IdentityResolutionError(
                        f"Fetch returned too many candidates for {query.name}"
                    )

                for candidate in self._require_list(
                    search_results.get("content"),
                    "card searchResults.content",
                ):
                    candidate = self._require_mapping(
                        candidate, "card search candidate"
                    )
                    candidate_id = candidate.get("id")
                    if not candidate_id:
                        continue
                    try:
                        card = self._request_json(
                            f"/v3/cards/{quote(str(candidate_id), safe='_-.')}"
                        )
                    except FetchTcgHttpError as error:
                        if error.status_code == 404:
                            continue
                        raise
                    if self._identity_matches(
                        card,
                        query,
                        expected_set_ids=expected_set_ids,
                    ):
                        matches.append(card)

                page_offset += 1
                if page_offset >= total_pages:
                    break
            if matches:
                break

        if len(matches) != 1:
            raise IdentityResolutionError(
                f"could not resolve one exact Fetch card for {query.name}"
            )
        return matches[0]

    def _get_listings(
        self,
        card_id,
        condition_code,
        *,
        excluded_listing_ids,
    ):
        listing_count = 0
        copy_count = 0
        all_condition_sellers = set()
        all_condition_copy_count = 0
        mutable_ladder = {}
        better_condition_lowest_price = None
        requested_condition_quality = CONDITION_QUALITY[condition_code]
        page_offset = 0
        total_pages = 1

        while page_offset < total_pages:
            payload = self._request_json(
                f"/v3/cards/{quote(card_id, safe='_-.')}/listings",
                params={
                    "countryCode": "NZ",
                    "currencyCode": "NZD",
                    "pageSize": LISTING_PAGE_SIZE,
                    "pageOffset": page_offset,
                    "sort": "PRICE_ASC",
                },
            )
            results = self._require_mapping(
                payload.get("searchResults"), "listing searchResults"
            )
            if page_offset == 0:
                total_pages = self._required_nonnegative_int(
                    results.get("totalPages"), "listing searchResults.totalPages"
                )
                if total_pages > MAX_LISTING_PAGES:
                    raise FetchTcgRequestError(
                        f"listing result exceeded {MAX_LISTING_PAGES} pages"
                    )

            for listing in self._require_list(
                results.get("content"), "listing searchResults.content"
            ):
                listing = self._require_mapping(listing, "listing")
                status = listing.get("status")
                if not isinstance(status, str):
                    raise FetchTcgRequestError("listing status was missing or invalid")
                if status != "ACTIVE":
                    continue
                condition = listing.get("condition")
                if not isinstance(condition, str):
                    raise FetchTcgRequestError(
                        "active listing condition was missing or invalid"
                    )
                if condition not in CONDITION_QUALITY:
                    raise FetchTcgRequestError(
                        "active listing condition was missing or invalid"
                    )
                if listing.get("listedCountry") not in (None, "NZ"):
                    continue
                if listing.get("requestedCurrency") not in (None, "NZD"):
                    continue
                listing_id = self._required_nonnegative_int(
                    listing.get("id"), "listing id"
                )
                if listing_id == 0:
                    raise FetchTcgRequestError("active listing id must be positive")
                if listing_id in excluded_listing_ids:
                    continue

                seller_profile_name = listing.get("sellerProfileName")
                if (
                    not isinstance(seller_profile_name, str)
                    or not seller_profile_name.strip()
                ):
                    raise FetchTcgRequestError(
                        "active listing seller profile name was missing or invalid"
                    )
                price = self._required_nonnegative_decimal(
                    listing.get("listedPriceInRequestedCurrency"),
                    "listing listedPriceInRequestedCurrency",
                )
                quantity = self._required_nonnegative_int(
                    listing.get("remainingQuantity"),
                    "listing remainingQuantity",
                )
                if quantity == 0:
                    raise FetchTcgRequestError(
                        "active listing remainingQuantity must be positive"
                    )

                seller_key = seller_profile_name.strip().casefold()
                all_condition_sellers.add(seller_key)
                all_condition_copy_count += quantity
                if CONDITION_QUALITY[condition] > requested_condition_quality:
                    better_condition_lowest_price = (
                        price
                        if better_condition_lowest_price is None
                        else min(better_condition_lowest_price, price)
                    )
                if CONDITION_QUALITY[condition] < requested_condition_quality:
                    continue

                listing_count += 1
                copy_count += quantity
                tier = mutable_ladder.setdefault(
                    price,
                    {
                        "listing_count": 0,
                        "copy_count": 0,
                        "seller_keys": set(),
                    },
                )
                tier["listing_count"] += 1
                tier["copy_count"] += quantity
                tier["seller_keys"].add(seller_key)

            page_offset += 1

        price_ladder = {
            price: PriceTier(
                listing_count=counts["listing_count"],
                copy_count=counts["copy_count"],
                seller_keys=frozenset(counts["seller_keys"]),
            )
            for price, counts in mutable_ladder.items()
        }
        return (
            listing_count,
            copy_count,
            len(all_condition_sellers),
            all_condition_copy_count,
            price_ladder,
            better_condition_lowest_price,
        )

    def _request_json(
        self,
        path,
        *,
        params=None,
        authorization=None,
        method="GET",
        json_body=None,
    ):
        method = str(method).upper()
        if authorization is not None and (method, path) not in AUTHENTICATED_REQUESTS:
            raise RunSafetyStop("authenticated request path was not explicitly allowed")
        if (method, path) in AUTHENTICATED_REQUESTS and authorization is None:
            raise RunSafetyStop("authenticated request was missing authorization")
        if method != "GET" and not (
            method == "POST" and path == MANAGED_LISTING_UPSERT_PATH
        ):
            raise RunSafetyStop("HTTP mutation path was not explicitly allowed")
        if method == "GET" and json_body is not None:
            raise FetchTcgRequestError("GET request must not include a JSON body")
        last_error = None

        for attempt in range(1, MAX_ATTEMPTS + 1):
            if self._request_count >= self._max_requests:
                raise RequestBudgetExceeded(
                    f"request budget of {self._max_requests} exhausted"
                )
            self._wait_for_request_slot()

            self._request_count += 1
            self._last_request_started = self._monotonic()
            self._log(
                f"{method} {path} attempt {attempt}/{MAX_ATTEMPTS} "
                f"request {self._request_count}/{self._max_requests}"
            )

            self._clear_session_cookies()
            try:
                response = self._session.request(
                    method,
                    f"{BASE_URL}{path}",
                    params=params,
                    timeout=(CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
                    allow_redirects=False,
                    headers=(
                        {"Authorization": authorization}
                        if authorization is not None
                        else None
                    ),
                    json=json_body,
                )
            except requests.RequestException as error:
                self._clear_session_cookies()
                last_error = error
                if attempt == MAX_ATTEMPTS:
                    break
                self._sleep_for_retry(attempt)
                continue
            self._clear_session_cookies()

            if response.status_code in (401, 403):
                raise RunSafetyStop(
                    f"Fetch returned HTTP {response.status_code}; stopping the run"
                )

            if response.status_code == 429:
                self._rate_limit_responses += 1
                if self._rate_limit_responses >= MAX_RATE_LIMIT_RESPONSES:
                    raise RunSafetyStop(
                        "Fetch returned repeated rate limits; stopping the run"
                    )
                if attempt == MAX_ATTEMPTS:
                    break
                self._sleep_for_retry(
                    attempt,
                    retry_after=response.headers.get("Retry-After"),
                )
                continue

            if response.status_code in (408, 425) or 500 <= response.status_code < 600:
                last_error = FetchTcgHttpError(response.status_code, path)
                if attempt == MAX_ATTEMPTS:
                    break
                self._sleep_for_retry(attempt)
                continue

            if not 200 <= response.status_code < 300:
                raise FetchTcgHttpError(response.status_code, path)

            try:
                payload = response.json()
                if not isinstance(payload, dict):
                    raise ValueError("expected a JSON object")
                return payload
            except ValueError as error:
                last_error = error
                if attempt == MAX_ATTEMPTS:
                    break
                self._sleep_for_retry(attempt)

        if isinstance(last_error, FetchTcgError):
            raise last_error
        raise FetchTcgRequestError(f"Fetch request failed for {path}") from last_error

    def _authorization_header(self):
        if (
            not isinstance(self._token, str)
            or not self._token
            or re.search(r"\s", self._token)
        ):
            raise RunSafetyStop("FETCHTCG_TOKEN is missing or malformed")
        return f"Bearer {self._token}"

    def _wait_for_request_slot(self):
        if self._last_request_started is None:
            return
        elapsed = self._monotonic() - self._last_request_started
        request_interval = self._random_uniform(
            MIN_REQUEST_INTERVAL_SECONDS, MAX_REQUEST_INTERVAL_SECONDS
        )
        remaining = request_interval - elapsed
        if remaining > 0:
            self._log(f"waiting {remaining:.2f}s for request interval")
            self._sleep(remaining)

    def _sleep_for_retry(self, attempt, *, retry_after=None):
        backoff = min(MAX_BACKOFF_SECONDS, float(2**attempt))
        delay = self._random_uniform(0.0, backoff)
        parsed_retry_after = self._parse_retry_after(retry_after)
        if parsed_retry_after is not None:
            if parsed_retry_after > MAX_BACKOFF_SECONDS:
                raise RunSafetyStop(
                    "Fetch requested a Retry-After longer than the safety cap"
                )
            delay = max(delay, parsed_retry_after)
        self._log(f"retrying after {delay:.2f}s")
        self._sleep(delay)

    @staticmethod
    def _parse_retry_after(value):
        if not value:
            return None
        try:
            return max(0.0, float(value))
        except ValueError:
            try:
                retry_at = email.utils.parsedate_to_datetime(value)
            except (TypeError, ValueError):
                return None
            if retry_at.tzinfo is None:
                retry_at = retry_at.replace(tzinfo=timezone.utc)
            return max(
                0.0,
                (retry_at - datetime.now(timezone.utc)).total_seconds(),
            )

    @staticmethod
    def _direct_card_id(query):
        rarity_code = RARITY_CODES.get(query.rarity.casefold())
        if not rarity_code:
            return None
        return "_".join(
            [
                "mtg",
                query.collector_number,
                rarity_code,
                query.set_code.casefold(),
                query.finish.casefold(),
            ]
        )

    @staticmethod
    def _identity_matches(card, query, *, expected_set_ids=None):
        references = card.get("externalReferences")
        if not card.get("id") or not isinstance(references, dict):
            return False
        strip_set_prefix = query.set_code.casefold() == "plst"
        return (
            str(references.get("scryfallId", "")).casefold()
            == query.scryfall_id.casefold()
            and FetchTcgClient._normalize_collector_number(
                card.get("cardCode"),
                strip_set_prefix=strip_set_prefix,
            )
            == FetchTcgClient._normalize_collector_number(
                query.collector_number,
                strip_set_prefix=strip_set_prefix,
            )
            and str(card.get("printFinishName", "")).casefold()
            == query.finish.casefold()
            and (expected_set_ids is None or card.get("setId") in expected_set_ids)
        )

    @staticmethod
    def _normalize_collector_number(value, *, strip_set_prefix=False):
        normalized = str(value).casefold()
        if strip_set_prefix:
            numeric_suffix = re.search(r"(?:^|-)(\d+)$", normalized)
            if numeric_suffix:
                normalized = numeric_suffix.group(1)
        return re.sub(r"^0+(?=\d)", "", normalized)

    @staticmethod
    def _decimal_or_none(value):
        if value is None:
            return None
        try:
            parsed = Decimal(str(value))
        except (InvalidOperation, ValueError):
            return None
        if not parsed.is_finite() or parsed < 0:
            return None
        return parsed

    @staticmethod
    def _required_nonnegative_decimal(value, label):
        parsed = FetchTcgClient._decimal_or_none(value)
        if parsed is None:
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        return parsed

    @staticmethod
    def _required_nonnegative_int(value, label):
        if isinstance(value, bool):
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            raise FetchTcgRequestError(f"{label} was missing or invalid") from None
        if isinstance(value, (float, Decimal)) and value != parsed:
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        if parsed < 0:
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        return parsed

    @staticmethod
    def _require_mapping(value, label):
        if not isinstance(value, dict):
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        return value

    @staticmethod
    def _require_list(value, label):
        if not isinstance(value, list):
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        return value

    def _clear_session_cookies(self):
        cookies = getattr(self._session, "cookies", None)
        if cookies is not None and hasattr(cookies, "clear"):
            cookies.clear()

    def _log(self, message):
        if self._verbose:
            print(f"[fetch] {message}")
