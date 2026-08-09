import argparse
import csv
import email.utils
import json
import os
import random
import re
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Callable

import requests


BASE_URL = "https://api.fetchtcg.com"
SELLER_OFFERS_PATH = "/v2/private/market/offers/seller"
BROWSER_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/150.0.0.0 Safari/537.36"
)
PAGE_SIZE = 20
MAX_PAGES = 100
MAX_REQUESTS = 500
MIN_REQUEST_INTERVAL_SECONDS = 1.0
MAX_REQUEST_INTERVAL_SECONDS = 2.0
CONNECT_TIMEOUT_SECONDS = 5
READ_TIMEOUT_SECONDS = 30
MAX_ATTEMPTS = 5
MAX_RATE_LIMIT_RESPONSES = 3
MAX_BACKOFF_SECONDS = 60.0
MONEY_QUANTUM = Decimal("0.01")
LINE_ITEM_FIELDS = (
    "sale_id",
    "item_id",
    "listing_id",
    "fetch_card_id",
    "scryfall_id",
    "card_name",
    "set_id",
    "set_name",
    "set_code",
    "finish",
    "condition",
    "quantity",
    "line_total_nzd",
    "accepted_at",
)


class SalesTelemetryError(RuntimeError):
    pass


class FetchTcgHttpError(SalesTelemetryError):
    def __init__(self, status_code, path):
        super().__init__(f"Fetch returned HTTP {status_code} for {path}")
        self.status_code = status_code
        self.path = path


class RunSafetyStop(SalesTelemetryError):
    pass


class RequestBudgetExceeded(RunSafetyStop):
    pass


class FetchTcgSalesClient:
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

    def get_seller_offers(self):
        authorization = self._authorization_header()
        offers = []
        offer_ids = set()
        page = 0
        expected_total_pages = None
        expected_total_elements = None

        while True:
            payload = self._request_json(
                SELLER_OFFERS_PATH,
                params={"sort": "NEWEST", "size": PAGE_SIZE, "page": page},
                authorization=authorization,
            )
            content = _require_list(payload.get("content"), "seller offers content")
            total_pages = _required_nonnegative_int(
                payload.get("totalPages"), "seller offers totalPages"
            )
            total_elements = _required_nonnegative_int(
                payload.get("totalElements"), "seller offers totalElements"
            )
            response_page = _required_nonnegative_int(
                payload.get("number"), "seller offers number"
            )
            number_of_elements = _required_nonnegative_int(
                payload.get("numberOfElements"), "seller offers numberOfElements"
            )
            if response_page != page:
                raise SalesTelemetryError(
                    "seller offers response page did not match the requested page"
                )
            if number_of_elements != len(content):
                raise SalesTelemetryError(
                    "seller offers numberOfElements did not match content"
                )
            if total_pages > MAX_PAGES:
                raise RunSafetyStop(f"seller offer history exceeded {MAX_PAGES} pages")

            if expected_total_pages is None:
                expected_total_pages = total_pages
                expected_total_elements = total_elements
                if total_pages == 0 and (content or total_elements != 0):
                    raise SalesTelemetryError(
                        "seller offers empty pagination metadata was inconsistent"
                    )
            else:
                if total_pages != expected_total_pages:
                    raise SalesTelemetryError(
                        "seller offers totalPages changed during pagination"
                    )
                if total_elements != expected_total_elements:
                    raise SalesTelemetryError(
                        "seller offers totalElements changed during pagination"
                    )

            for value in content:
                offer = _require_mapping(value, "seller offer")
                offer_id = _required_positive_int(offer.get("id"), "seller offer id")
                if offer_id in offer_ids:
                    raise SalesTelemetryError(
                        "seller offers response contained a duplicate offer id"
                    )
                offer_ids.add(offer_id)
                offers.append(offer)

            page += 1
            if page >= total_pages:
                break

        if len(offers) != expected_total_elements:
            raise SalesTelemetryError(
                "seller offers result count did not match totalElements"
            )
        return offers

    def _request_json(
        self,
        path,
        *,
        params=None,
        authorization=None,
        method="GET",
    ):
        method = str(method).upper()
        if method != "GET":
            raise RunSafetyStop("sales client is read-only")
        if path != SELLER_OFFERS_PATH:
            raise RunSafetyStop("request path was not explicitly allowed")
        if authorization is None:
            raise RunSafetyStop("authenticated request was missing authorization")
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
                f"GET {path} attempt {attempt}/{MAX_ATTEMPTS} "
                f"request {self._request_count}/{self._max_requests}"
            )

            self._clear_session_cookies()
            try:
                response = self._session.request(
                    "GET",
                    f"{BASE_URL}{path}",
                    params=params,
                    timeout=(CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
                    allow_redirects=False,
                    headers={"Authorization": authorization},
                    json=None,
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
                return _require_mapping(payload, "seller offers response")
            except (SalesTelemetryError, ValueError) as error:
                last_error = error
                if attempt == MAX_ATTEMPTS:
                    break
                self._sleep_for_retry(attempt)

        if isinstance(last_error, SalesTelemetryError):
            raise last_error
        raise SalesTelemetryError(f"Fetch request failed for {path}") from last_error

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

    def _clear_session_cookies(self):
        cookies = getattr(self._session, "cookies", None)
        if cookies is not None and hasattr(cookies, "clear"):
            cookies.clear()

    def _log(self, message):
        if self._verbose:
            print(f"[fetch] {message}")


def build_report(offers, *, generated_at, request_count):
    generated_at = _require_aware_datetime(generated_at, "generated_at")
    status_counts = Counter()
    sales = []
    offer_ids = set()

    for value in offers:
        offer = _require_mapping(value, "seller offer")
        offer_id = _required_positive_int(offer.get("id"), "seller offer id")
        if offer_id in offer_ids:
            raise SalesTelemetryError("seller offers contained a duplicate offer id")
        offer_ids.add(offer_id)
        status = _required_string(offer.get("status"), "seller offer status")
        status_counts[status] += 1
        if status == "ACCEPTED":
            sales.append(_parse_accepted_sale(offer))

    sales.sort(
        key=lambda sale: (sale["accepted_at"], sale["sale_id"]),
        reverse=True,
    )
    merchandise_sales = sum(
        (Decimal(sale["merchandise_sales_nzd"]) for sale in sales),
        Decimal("0"),
    )
    shipping_charged = sum(
        (Decimal(sale["shipping_charged_nzd"]) for sale in sales),
        Decimal("0"),
    )
    gross_receipts = sum(
        (Decimal(sale["gross_receipts_nzd"]) for sale in sales),
        Decimal("0"),
    )
    fetch_fees = sum(
        (Decimal(sale["fetch_fees_nzd"]) for sale in sales),
        Decimal("0"),
    )
    net_payout = sum(
        (Decimal(sale["net_payout_nzd"]) for sale in sales),
        Decimal("0"),
    )
    accepted_at_values = [sale["accepted_at"] for sale in sales]
    average_order_value = (
        (gross_receipts / len(sales)).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP)
        if sales
        else Decimal("0")
    )

    return {
        "schema_version": 1,
        "generated_at": _format_datetime(generated_at),
        "source": {
            "endpoint": SELLER_OFFERS_PATH,
            "sort": "NEWEST",
            "page_size": PAGE_SIZE,
            "request_count": _required_nonnegative_int(request_count, "request_count"),
        },
        "summary": {
            "fetched_offer_count": len(offers),
            "accepted_sale_count": len(sales),
            "accepted_line_item_count": sum(sale["line_item_count"] for sale in sales),
            "accepted_card_quantity": sum(sale["card_quantity"] for sale in sales),
            "merchandise_sales_nzd": _format_money(merchandise_sales),
            "shipping_charged_nzd": _format_money(shipping_charged),
            "gross_receipts_nzd": _format_money(gross_receipts),
            "fetch_fees_nzd": _format_money(fetch_fees),
            "net_payout_nzd": _format_money(net_payout),
            "average_order_value_nzd": _format_money(average_order_value),
            "first_accepted_at": (
                min(accepted_at_values) if accepted_at_values else None
            ),
            "last_accepted_at": (
                max(accepted_at_values) if accepted_at_values else None
            ),
            "offer_status_counts": dict(sorted(status_counts.items())),
        },
        "sales": sales,
    }


def write_reports(report, *, workspace):
    report = _require_mapping(report, "sales report")
    generated_at = _parse_timestamp(report.get("generated_at"), "report generated_at")
    output_dir = (
        Path(workspace)
        / "tmp"
        / "tcg-lister"
        / f"sales-{generated_at.strftime('%Y%m%dT%H%M%SZ')}"
    )
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / "report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    with (output_dir / "line-items.csv").open(
        "w", newline="", encoding="utf-8"
    ) as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=LINE_ITEM_FIELDS)
        writer.writeheader()
        for sale in report.get("sales", []):
            for item in sale.get("items", []):
                writer.writerow({field: item[field] for field in LINE_ITEM_FIELDS})
    return output_dir


def _parse_accepted_sale(offer):
    sale_id = _required_positive_int(offer.get("id"), "accepted sale id")
    currency_code = _required_string(
        offer.get("currencyCode"), "accepted sale currency"
    )
    if currency_code != "NZD":
        raise SalesTelemetryError("accepted sale currency was not NZD")
    created_at = _format_datetime(
        _parse_timestamp(offer.get("createdDateTime"), "accepted sale createdDateTime")
    )
    accepted_at = _format_datetime(
        _parse_timestamp(offer.get("acceptedAt"), "accepted sale acceptedAt")
    )
    delivery_mode = _required_string(
        offer.get("deliveryMode"), "accepted sale deliveryMode"
    )
    shipping_status = _required_string(
        offer.get("shippingStatus"), "accepted sale shippingStatus"
    )
    items = [
        _parse_line_item(value, sale_id=sale_id, accepted_at=accepted_at)
        for value in _require_list(offer.get("items"), "accepted sale items")
    ]
    if not items:
        raise SalesTelemetryError("accepted sale items were empty")

    merchandise_sales = sum(
        (Decimal(item["line_total_nzd"]) for item in items),
        Decimal("0"),
    )
    shipping_value = offer.get("shippingPriceTotal")
    shipping_charged = (
        Decimal("0")
        if shipping_value is None
        else _required_money(shipping_value, "accepted sale shippingPriceTotal")
    )
    gross_receipts = _required_money(
        offer.get("totalOfferPrice"), "accepted sale totalOfferPrice"
    )
    fetch_fees = _required_money(
        offer.get("fetchTransactionFee"), "accepted sale fetchTransactionFee"
    )
    net_payout = _required_money(
        offer.get("finalPayoutAmount"), "accepted sale finalPayoutAmount"
    )
    if merchandise_sales + shipping_charged != gross_receipts:
        raise SalesTelemetryError(
            "accepted sale merchandise plus shipping did not equal gross receipts"
        )
    if gross_receipts - fetch_fees != net_payout:
        raise SalesTelemetryError(
            "accepted sale gross receipts minus Fetch fees did not equal net payout"
        )

    return {
        "sale_id": sale_id,
        "created_at": created_at,
        "accepted_at": accepted_at,
        "delivery_mode": delivery_mode,
        "shipping_status": shipping_status,
        "currency_code": currency_code,
        "line_item_count": len(items),
        "card_quantity": sum(item["quantity"] for item in items),
        "merchandise_sales_nzd": _format_money(merchandise_sales),
        "shipping_charged_nzd": _format_money(shipping_charged),
        "gross_receipts_nzd": _format_money(gross_receipts),
        "fetch_fees_nzd": _format_money(fetch_fees),
        "net_payout_nzd": _format_money(net_payout),
        "items": items,
    }


def _parse_line_item(value, *, sale_id, accepted_at):
    item = _require_mapping(value, "accepted sale line item")
    item_id = _required_positive_int(item.get("id"), "line item id")
    currency_code = _required_string(item.get("currencyCode"), "line item currency")
    if currency_code != "NZD":
        raise SalesTelemetryError("line item currency was not NZD")
    listing = _require_mapping(item.get("listing"), "line item listing")
    listing_id = _required_positive_int(listing.get("id"), "line item listing id")
    card = _require_mapping(listing.get("card"), "line item card")
    fetch_card_id = _required_string(card.get("id"), "line item Fetch card id")
    if not fetch_card_id.startswith("mtg_"):
        raise SalesTelemetryError("line item Fetch card id was missing or invalid")
    references = _require_mapping(
        card.get("externalReferences"), "line item card externalReferences"
    )
    scryfall_id = _required_uuid(references.get("scryfallId"), "line item Scryfall id")
    fetch_set = _require_mapping(card.get("set"), "line item card set")
    print_version = _require_mapping(
        card.get("printVersion"), "line item card printVersion"
    )
    print_finish = _require_mapping(
        card.get("printFinish"), "line item card printFinish"
    )

    return {
        "sale_id": sale_id,
        "item_id": item_id,
        "listing_id": listing_id,
        "fetch_card_id": fetch_card_id,
        "scryfall_id": scryfall_id,
        "card_name": _required_string(card.get("name"), "line item card name"),
        "set_id": _required_positive_int(fetch_set.get("id"), "line item set id"),
        "set_name": _required_string(
            fetch_set.get("displayName"), "line item set name"
        ),
        "set_code": _required_string(
            print_version.get("code"), "line item set code"
        ).casefold(),
        "finish": _required_string(
            print_finish.get("name"), "line item finish"
        ).casefold(),
        "condition": _required_string(listing.get("condition"), "line item condition"),
        "quantity": _required_positive_int(item.get("quantity"), "line item quantity"),
        "line_total_nzd": _format_money(
            _required_money(item.get("price"), "line item price")
        ),
        "accepted_at": accepted_at,
    }


def _parse_timestamp(value, label):
    if not isinstance(value, str) or not value.strip():
        raise SalesTelemetryError(f"{label} was missing or invalid")
    normalized = value.strip()
    if normalized.endswith("Z"):
        normalized = f"{normalized[:-1]}+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as error:
        raise SalesTelemetryError(f"{label} was missing or invalid") from error
    if parsed.tzinfo is None:
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return parsed.astimezone(timezone.utc)


def _format_datetime(value):
    value = _require_aware_datetime(value, "timestamp")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _require_aware_datetime(value, label):
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return value


def _required_money(value, label):
    if isinstance(value, bool):
        raise SalesTelemetryError(f"{label} was missing or invalid")
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise SalesTelemetryError(f"{label} was missing or invalid") from None
    if not parsed.is_finite() or parsed < 0:
        raise SalesTelemetryError(f"{label} was missing or invalid")
    if parsed != parsed.quantize(MONEY_QUANTUM):
        raise SalesTelemetryError(f"{label} had more than two decimal places")
    return parsed


def _format_money(value):
    return format(Decimal(value).quantize(MONEY_QUANTUM), ".2f")


def _required_uuid(value, label):
    value = _required_string(value, label)
    try:
        parsed = uuid.UUID(value)
    except ValueError as error:
        raise SalesTelemetryError(f"{label} was missing or invalid") from error
    return str(parsed)


def _required_string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return value.strip()


def _required_nonnegative_int(value, label):
    if isinstance(value, bool):
        raise SalesTelemetryError(f"{label} was missing or invalid")
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise SalesTelemetryError(f"{label} was missing or invalid") from None
    if isinstance(value, (float, Decimal)) and value != parsed:
        raise SalesTelemetryError(f"{label} was missing or invalid")
    if parsed < 0:
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return parsed


def _required_positive_int(value, label):
    parsed = _required_nonnegative_int(value, label)
    if parsed == 0:
        raise SalesTelemetryError(f"{label} must be positive")
    return parsed


def _require_mapping(value, label):
    if not isinstance(value, dict):
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return value


def _require_list(value, label):
    if not isinstance(value, list):
        raise SalesTelemetryError(f"{label} was missing or invalid")
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Export privacy-minimized Fetch TCG sales telemetry"
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print request and retry diagnostics",
    )
    args = parser.parse_args(argv)

    try:
        client = FetchTcgSalesClient(
            token=os.environ.get("FETCHTCG_TOKEN"),
            verbose=args.verbose,
        )
        offers = client.get_seller_offers()
        report = build_report(
            offers,
            generated_at=datetime.now(timezone.utc),
            request_count=client.request_count,
        )
        workspace = Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", Path.cwd()))
        output_dir = write_reports(report, workspace=workspace)
    except (SalesTelemetryError, OSError) as error:
        print(f"sales telemetry failed: {error}", file=sys.stderr)
        return 1

    summary = report["summary"]
    print(f"Sales telemetry written to {output_dir}")
    print(
        f"Offers: {summary['fetched_offer_count']} fetched, "
        f"{summary['accepted_sale_count']} accepted sales"
    )
    print(
        f"Volume: {summary['accepted_card_quantity']} cards across "
        f"{summary['accepted_line_item_count']} line items"
    )
    print(
        f"NZD: {summary['gross_receipts_nzd']} gross receipts, "
        f"{summary['fetch_fees_nzd']} Fetch fees, "
        f"{summary['net_payout_nzd']} net payout"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
