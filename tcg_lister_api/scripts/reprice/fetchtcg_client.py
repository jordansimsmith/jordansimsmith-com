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
MAX_REQUESTS = 5000
MAX_RATE_LIMIT_RESPONSES = 3
MAX_BACKOFF_SECONDS = 60.0
MAX_LISTING_PAGES = 25
MAX_MANAGED_LISTING_PAGES = 100
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


class FetchTcgError(RuntimeError):
    pass


class FetchTcgHttpError(FetchTcgError):
    def __init__(self, status_code, path):
        super().__init__(f"Fetch returned HTTP {status_code} for {path}")
        self.status_code = status_code
        self.path = path


class FetchTcgRequestError(FetchTcgError):
    pass


class RunSafetyStop(FetchTcgError):
    pass


class RequestBudgetExceeded(RunSafetyStop):
    pass


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
class CardDetails:
    fetch_card_id: str
    scryfall_id: str
    name: str
    collector_number: str
    set_id: int
    finish: str
    market_price_nzd: Decimal | None


@dataclass(frozen=True)
class CompetitorListing:
    listing_id: int
    condition: str
    seller_key: str
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

    @property
    def request_count(self):
        return self._request_count

    def get_managed_listings(self):
        authorization = self._authorization_header()
        listings = []
        listing_ids = set()
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
                    "currencyCode": "NZD",
                },
                authorization=authorization,
            )
            results = self._require_mapping(
                payload.get("searchResults"), "managed listing searchResults"
            )
            response_total_pages = self._required_nonnegative_int(
                results.get("totalPages"),
                "managed listing searchResults.totalPages",
            )
            if page == 0:
                total_pages = response_total_pages
                if total_pages > MAX_MANAGED_LISTING_PAGES:
                    raise FetchTcgRequestError(
                        "managed listing result exceeded "
                        f"{MAX_MANAGED_LISTING_PAGES} pages"
                    )
            elif response_total_pages != total_pages:
                raise FetchTcgRequestError(
                    "managed listing totalPages changed during pagination"
                )

            for value in self._require_list(
                results.get("content"), "managed listing searchResults.content"
            ):
                listing = self._parse_managed_listing(value)
                if listing is None:
                    continue
                if listing.listing_id in listing_ids:
                    raise FetchTcgRequestError(
                        "managed listing response contained a duplicate listing id"
                    )
                listing_ids.add(listing.listing_id)
                listings.append(listing)
            page += 1
        return listings

    def get_card_details(self, fetch_card_id):
        fetch_card_id = self._required_card_id(fetch_card_id)
        payload = self._request_json(f"/v3/cards/{quote(fetch_card_id, safe='_-.')}")
        response_card_id = self._required_card_id(payload.get("id"))
        if response_card_id != fetch_card_id:
            raise FetchTcgRequestError(
                "card detail id did not match requested Fetch card id"
            )
        references = self._require_mapping(
            payload.get("externalReferences"), "card externalReferences"
        )
        scryfall_id = self._required_scryfall_id(
            references.get("scryfallId"), "card Scryfall id"
        )
        name = self._required_string(payload.get("name"), "card name")
        collector_number = self._required_string(
            payload.get("cardCode"), "card collector number"
        )
        set_id = self._required_positive_int(payload.get("setId"), "card setId")
        finish = self._required_string(
            payload.get("printFinishName"), "card printFinishName"
        ).casefold()

        pricing_data = payload.get("pricingData")
        if pricing_data is None:
            market_price = None
        else:
            pricing_data = self._require_mapping(pricing_data, "card pricingData")
            nz_pricing = self._require_mapping(
                pricing_data.get("NZ"), "card pricingData.NZ"
            )
            market_price = self._decimal_or_none(nz_pricing.get("tcgMarketPrice"))

        return CardDetails(
            fetch_card_id=fetch_card_id,
            scryfall_id=scryfall_id,
            name=name,
            collector_number=collector_number,
            set_id=set_id,
            finish=finish,
            market_price_nzd=market_price,
        )

    def get_competitor_listings(
        self,
        fetch_card_id,
        *,
        excluded_listing_ids=(),
    ):
        fetch_card_id = self._required_card_id(fetch_card_id)
        excluded_listing_ids = frozenset(excluded_listing_ids)
        if any(
            isinstance(listing_id, bool)
            or not isinstance(listing_id, int)
            or listing_id <= 0
            for listing_id in excluded_listing_ids
        ):
            raise FetchTcgRequestError("excluded listing ids were missing or invalid")

        listings = []
        listing_ids = set()
        page_offset = 0
        total_pages = 1
        while page_offset < total_pages:
            payload = self._request_json(
                f"/v3/cards/{quote(fetch_card_id, safe='_-.')}/listings",
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
            response_total_pages = self._required_nonnegative_int(
                results.get("totalPages"), "listing searchResults.totalPages"
            )
            if page_offset == 0:
                total_pages = response_total_pages
                if total_pages > MAX_LISTING_PAGES:
                    raise FetchTcgRequestError(
                        f"listing result exceeded {MAX_LISTING_PAGES} pages"
                    )
            elif response_total_pages != total_pages:
                raise FetchTcgRequestError(
                    "listing totalPages changed during pagination"
                )

            for value in self._require_list(
                results.get("content"), "listing searchResults.content"
            ):
                listing = self._parse_competitor_listing(value)
                if listing is None or listing.listing_id in excluded_listing_ids:
                    continue
                if listing.listing_id in listing_ids:
                    raise FetchTcgRequestError(
                        "listing response contained a duplicate listing id"
                    )
                listing_ids.add(listing.listing_id)
                listings.append(listing)
            page_offset += 1
        return listings

    def upsert_managed_listing(self, request, *, expected_listing_id=None):
        if not isinstance(request, ListingUpsertRequest):
            raise FetchTcgRequestError("listing upsert request was missing or invalid")
        fetch_card_id = self._required_card_id(request.fetch_card_id)
        if request.condition not in MANAGED_LISTING_CONDITIONS:
            raise FetchTcgRequestError(
                "listing upsert condition was missing or invalid"
            )
        if isinstance(request.quantity, bool) or not isinstance(request.quantity, int):
            raise FetchTcgRequestError("listing upsert quantity was missing or invalid")
        quantity = self._required_positive_int(
            request.quantity, "listing upsert quantity"
        )
        if not isinstance(request.listed_price_nzd, Decimal):
            raise FetchTcgRequestError(
                "listing upsert listed price was missing or invalid"
            )
        listed_price = self._required_nonnegative_decimal(
            request.listed_price_nzd, "listing upsert listed price"
        )
        if expected_listing_id is not None:
            expected_listing_id = self._required_positive_int(
                expected_listing_id, "expected listingId"
            )

        payload = self._request_json(
            MANAGED_LISTING_UPSERT_PATH,
            method="POST",
            json_body={
                "cardId": fetch_card_id,
                "condition": request.condition,
                "listedPrice": float(listed_price),
                "listedCurrency": "NZD",
                "matchPriceEnabled": False,
                "quantity": quantity,
                "details": "",
            },
            authorization=self._authorization_header(),
        )
        listing_id = self._required_positive_int(
            payload.get("listingId"), "listing upsert response listingId"
        )
        if expected_listing_id is not None and listing_id != expected_listing_id:
            raise FetchTcgRequestError(
                "listing upsert response listingId did not match existing listing"
            )
        remaining_quantity = self._required_positive_int(
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

    def _parse_managed_listing(self, value):
        listing = self._require_mapping(value, "managed listing")
        status = self._required_string(listing.get("status"), "managed listing status")
        if status != "ACTIVE":
            return None
        listing_id = self._required_positive_int(
            listing.get("id"), "managed listing id"
        )
        card = self._require_mapping(listing.get("card"), "managed listing card")
        fetch_card_id = self._required_card_id(card.get("id"))
        references = self._require_mapping(
            card.get("externalReferences"),
            "managed listing card externalReferences",
        )
        scryfall_id = self._required_scryfall_id(
            references.get("scryfallId"), "managed listing Scryfall id"
        )
        fetch_set = self._require_mapping(card.get("set"), "managed listing card set")
        set_id = self._required_positive_int(
            fetch_set.get("id"), "managed listing card set id"
        )
        print_finish = self._require_mapping(
            card.get("printFinish"), "managed listing card printFinish"
        )
        finish = self._required_string(
            print_finish.get("name"), "managed listing finish"
        ).casefold()
        condition = self._required_string(
            listing.get("condition"), "managed listing condition"
        )
        if condition not in MANAGED_LISTING_CONDITIONS:
            raise FetchTcgRequestError(
                "managed listing condition was missing or invalid"
            )
        remaining_quantity = self._required_positive_int(
            listing.get("remainingQuantity"),
            "managed listing remainingQuantity",
        )
        if listing.get("listedCountry") != "NZ":
            raise FetchTcgRequestError("managed listing listedCountry was not NZ")
        requested_currency = listing.get("requestedCurrency")
        if requested_currency not in (None, "NZD"):
            raise FetchTcgRequestError("managed listing requestedCurrency was invalid")
        converted_price = listing.get("listedPriceInRequestedCurrency")
        if requested_currency == "NZD" and converted_price is not None:
            listed_price = self._required_nonnegative_decimal(
                converted_price,
                "managed listing listedPriceInRequestedCurrency",
            )
        elif listing.get("listedCurrency") == "NZD":
            listed_price = self._required_nonnegative_decimal(
                listing.get("listedPrice"), "managed listing listedPrice"
            )
        else:
            raise FetchTcgRequestError("managed listing did not provide an NZD price")
        return ManagedListing(
            listing_id=listing_id,
            fetch_card_id=fetch_card_id,
            scryfall_id=scryfall_id,
            set_id=set_id,
            finish=finish,
            condition=condition,
            remaining_quantity=remaining_quantity,
            listed_price_nzd=listed_price,
        )

    def _parse_competitor_listing(self, value):
        listing = self._require_mapping(value, "listing")
        status = self._required_string(listing.get("status"), "listing status")
        if status != "ACTIVE":
            return None
        if listing.get("listedCountry") not in (None, "NZ"):
            return None
        if listing.get("requestedCurrency") not in (None, "NZD"):
            return None
        listing_id = self._required_positive_int(listing.get("id"), "listing id")
        condition = self._required_string(
            listing.get("condition"), "active listing condition"
        )
        if condition not in MANAGED_LISTING_CONDITIONS:
            raise FetchTcgRequestError(
                "active listing condition was missing or invalid"
            )
        seller_profile_name = self._required_string(
            listing.get("sellerProfileName"),
            "active listing seller profile name",
        )
        price = self._required_nonnegative_decimal(
            listing.get("listedPriceInRequestedCurrency"),
            "listing listedPriceInRequestedCurrency",
        )
        quantity = self._required_positive_int(
            listing.get("remainingQuantity"), "listing remainingQuantity"
        )
        return CompetitorListing(
            listing_id=listing_id,
            condition=condition,
            seller_key=seller_profile_name.casefold(),
            remaining_quantity=quantity,
            listed_price_nzd=price,
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
        if (
            authorization is not None
            and (
                method,
                path,
            )
            not in AUTHENTICATED_REQUESTS
        ):
            raise RunSafetyStop("authenticated request path was not explicitly allowed")
        if (
            method,
            path,
        ) in AUTHENTICATED_REQUESTS and authorization is None:
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
            MIN_REQUEST_INTERVAL_SECONDS,
            MAX_REQUEST_INTERVAL_SECONDS,
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
    def _required_card_id(value):
        if not isinstance(value, str) or not value.startswith("mtg_"):
            raise FetchTcgRequestError("Fetch card id was missing or invalid")
        return value

    @staticmethod
    def _required_scryfall_id(value, label):
        if not isinstance(value, str) or not value:
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        try:
            uuid.UUID(value)
        except ValueError as error:
            raise FetchTcgRequestError(f"{label} was missing or invalid") from error
        return value.casefold()

    @staticmethod
    def _required_string(value, label):
        if not isinstance(value, str) or not value.strip():
            raise FetchTcgRequestError(f"{label} was missing or invalid")
        return value.strip()

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
    def _required_positive_int(value, label):
        parsed = FetchTcgClient._required_nonnegative_int(value, label)
        if parsed == 0:
            raise FetchTcgRequestError(f"{label} must be positive")
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
