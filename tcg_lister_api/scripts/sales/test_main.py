import csv
import json
from datetime import datetime, timezone
from decimal import Decimal

import pytest
import requests

from main import (
    BROWSER_USER_AGENT,
    FetchTcgSalesClient,
    RequestBudgetExceeded,
    RunSafetyStop,
    SalesTelemetryError,
    build_report,
    write_reports,
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


def _page(content, *, page=0, total_pages=1, total_elements=None):
    if total_elements is None:
        total_elements = len(content)
    return {
        "content": content,
        "totalPages": total_pages,
        "totalElements": total_elements,
        "number": page,
        "numberOfElements": len(content),
    }


def _item(
    *,
    item_id=377749,
    listing_id=985306,
    card_id="mtg_137_c_znr_normal",
    scryfall_id="492d77e5-acc6-41b8-8930-f39d69234919",
    name="Cleansing Wildfire",
    set_id=3388,
    set_name="Zendikar Rising",
    set_code="znr",
    finish="normal",
    condition="raw-nm",
    quantity=2,
    line_total="4.00",
    currency="NZD",
):
    return {
        "id": item_id,
        "listing": {
            "id": listing_id,
            "card": {
                "id": card_id,
                "name": name,
                "externalReferences": {"scryfallId": scryfall_id},
                "set": {"id": set_id, "displayName": set_name},
                "printVersion": {"code": set_code},
                "printFinish": {"name": finish},
            },
            "condition": condition,
        },
        "price": line_total,
        "currencyCode": currency,
        "quantity": quantity,
    }


def _accepted_offer(
    *,
    offer_id=79505,
    items=None,
    merchandise_total=Decimal("6.00"),
    shipping="7.50",
    gross="13.50",
    fee="0.54",
    payout="12.96",
):
    if items is None:
        items = [
            _item(),
            _item(
                item_id=377750,
                listing_id=1005956,
                card_id="mtg_95_c_vow_normal",
                scryfall_id="dd03651e-ada0-41dc-8722-0eba476943e3",
                name="Blood Fountain",
                set_id=2833,
                set_name="Innistrad: Crimson Vow",
                set_code="vow",
                quantity=2,
                line_total=str(merchandise_total - Decimal("4.00")),
            ),
        ]
    return {
        "id": offer_id,
        "items": items,
        "totalOfferPrice": gross,
        "shippingPriceTotal": shipping,
        "currencyCode": "NZD",
        "createdDateTime": "2026-08-01T01:33:26.663+0000",
        "status": "ACCEPTED",
        "acceptedAt": "2026-08-01T03:20:19.279+0000",
        "deliveryMode": "DELIVERY",
        "shippingStatus": "SHIPPED_PENDING_CONFIRMATION",
        "fetchTransactionFee": fee,
        "finalPayoutAmount": payout,
        "buyerName": "Private Buyer",
        "buyerRegionAddress": {
            "line1": "Private street",
            "latitude": -36.7,
            "longitude": 174.7,
        },
        "paymentInstructions": "Private payment instructions",
        "shippingTrackingNumber": "PRIVATE-TRACKING",
        "actions": [{"data": {"bankAccountNumber": "PRIVATE-BANK"}}],
    }


def _rejected_offer(offer_id=80473):
    return {
        "id": offer_id,
        "status": "REJECTED",
        "buyerName": "Rejected Private Buyer",
        "buyerRegionAddress": {"line1": "Rejected private street"},
    }


def _client(responses, *, token="test-token", headers=None, max_requests=500):
    clock = _FakeClock()
    session = _FakeSession(responses, headers=headers)
    client = FetchTcgSalesClient(
        session=session,
        sleep=clock.sleep,
        monotonic=clock.monotonic,
        random_uniform=lambda minimum, maximum: minimum,
        token=token,
        max_requests=max_requests,
    )
    return client, session, clock


def test_get_seller_offers_paginates_complete_newest_first_history():
    client, session, _ = _client(
        [
            _FakeResponse(
                200,
                _page(
                    [_accepted_offer()],
                    page=0,
                    total_pages=2,
                    total_elements=2,
                ),
            ),
            _FakeResponse(
                200,
                _page(
                    [_rejected_offer()],
                    page=1,
                    total_pages=2,
                    total_elements=2,
                ),
            ),
        ]
    )

    offers = client.get_seller_offers()

    assert [offer["id"] for offer in offers] == [79505, 80473]
    assert [call["params"]["page"] for call in session.calls] == [0, 1]
    assert all(call["params"]["sort"] == "NEWEST" for call in session.calls)
    assert all(call["params"]["size"] == 20 for call in session.calls)
    assert all(
        call["headers"] == {"Authorization": "Bearer test-token"}
        for call in session.calls
    )


def test_build_report_filters_accepted_sales_and_excludes_private_fields():
    report = build_report(
        [_accepted_offer(), _rejected_offer()],
        generated_at=datetime(2026, 8, 10, 0, 0, tzinfo=timezone.utc),
        request_count=1,
    )

    assert report["schema_version"] == 1
    assert report["summary"] == {
        "fetched_offer_count": 2,
        "accepted_sale_count": 1,
        "accepted_line_item_count": 2,
        "accepted_card_quantity": 4,
        "merchandise_sales_nzd": "6.00",
        "shipping_charged_nzd": "7.50",
        "gross_receipts_nzd": "13.50",
        "fetch_fees_nzd": "0.54",
        "net_payout_nzd": "12.96",
        "average_order_value_nzd": "13.50",
        "first_accepted_at": "2026-08-01T03:20:19.279000Z",
        "last_accepted_at": "2026-08-01T03:20:19.279000Z",
        "offer_status_counts": {"ACCEPTED": 1, "REJECTED": 1},
    }
    assert report["sales"][0]["items"][0] == {
        "sale_id": 79505,
        "item_id": 377749,
        "listing_id": 985306,
        "fetch_card_id": "mtg_137_c_znr_normal",
        "scryfall_id": "492d77e5-acc6-41b8-8930-f39d69234919",
        "card_name": "Cleansing Wildfire",
        "set_id": 3388,
        "set_name": "Zendikar Rising",
        "set_code": "znr",
        "finish": "normal",
        "condition": "raw-nm",
        "quantity": 2,
        "line_total_nzd": "4.00",
        "accepted_at": "2026-08-01T03:20:19.279000Z",
    }

    serialized = json.dumps(report)
    for private_value in (
        "Private Buyer",
        "Private street",
        "Private payment instructions",
        "PRIVATE-TRACKING",
        "PRIVATE-BANK",
        "Rejected Private Buyer",
        "Rejected private street",
    ):
        assert private_value not in serialized
    for private_key in (
        "buyerName",
        "buyerRegionAddress",
        "paymentInstructions",
        "shippingTrackingNumber",
        "actions",
    ):
        assert private_key not in serialized


def test_build_report_rejects_inconsistent_financial_totals():
    offer = _accepted_offer(gross="13.51", payout="12.97")

    with pytest.raises(SalesTelemetryError, match="merchandise plus shipping"):
        build_report(
            [offer],
            generated_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
            request_count=1,
        )


def test_build_report_rejects_non_nzd_line_item():
    offer = _accepted_offer(items=[_item(currency="AUD", line_total="6.00")])

    with pytest.raises(SalesTelemetryError, match="line item currency"):
        build_report(
            [offer],
            generated_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
            request_count=1,
        )


def test_get_seller_offers_rejects_duplicate_offer_id_across_pages():
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _page(
                    [_rejected_offer()],
                    page=0,
                    total_pages=2,
                    total_elements=2,
                ),
            ),
            _FakeResponse(
                200,
                _page(
                    [_rejected_offer()],
                    page=1,
                    total_pages=2,
                    total_elements=2,
                ),
            ),
        ]
    )

    with pytest.raises(SalesTelemetryError, match="duplicate offer id"):
        client.get_seller_offers()


def test_get_seller_offers_rejects_changed_pagination():
    client, _, _ = _client(
        [
            _FakeResponse(
                200,
                _page(
                    [_rejected_offer()],
                    page=0,
                    total_pages=2,
                    total_elements=2,
                ),
            ),
            _FakeResponse(
                200,
                _page(
                    [_rejected_offer(80474)],
                    page=1,
                    total_pages=3,
                    total_elements=2,
                ),
            ),
        ]
    )

    with pytest.raises(SalesTelemetryError, match="totalPages changed"):
        client.get_seller_offers()


@pytest.mark.parametrize("token", [None, "", "Bearer token", "two words"])
def test_get_seller_offers_requires_raw_token(token):
    client, session, _ = _client([], token=token)

    with pytest.raises(RunSafetyStop, match="FETCHTCG_TOKEN"):
        client.get_seller_offers()

    assert session.calls == []


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
            _FakeResponse(200, _page([])),
        ]
    )

    assert client.get_seller_offers() == []
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
        client.get_seller_offers()


def test_client_enforces_request_budget():
    client, session, _ = _client([], max_requests=0)

    with pytest.raises(RequestBudgetExceeded, match="request budget"):
        client.get_seller_offers()

    assert session.calls == []


def test_client_rejects_malformed_page_element_count():
    payload = _page([_rejected_offer()])
    payload["numberOfElements"] = 2
    client, _, _ = _client([_FakeResponse(200, payload)])

    with pytest.raises(SalesTelemetryError, match="numberOfElements"):
        client.get_seller_offers()


def test_client_has_read_only_endpoint_guard():
    client, session, _ = _client([])

    with pytest.raises(RunSafetyStop, match="read-only"):
        client._request_json("/v2/private/market/offers/seller", method="POST")
    with pytest.raises(RunSafetyStop, match="not explicitly allowed"):
        client._request_json("/v1/private/profile")

    assert session.calls == []


def test_write_reports_writes_json_and_flat_line_item_csv(tmp_path):
    report = build_report(
        [_accepted_offer(), _rejected_offer()],
        generated_at=datetime(2026, 8, 10, 0, 0, tzinfo=timezone.utc),
        request_count=1,
    )

    output_dir = write_reports(report, workspace=tmp_path)

    assert output_dir == tmp_path / "tmp/tcg-lister/sales-20260810T000000Z"
    assert json.loads((output_dir / "report.json").read_text()) == report
    with (output_dir / "line-items.csv").open(newline="") as csv_file:
        rows = list(csv.DictReader(csv_file))
    assert [row["card_name"] for row in rows] == [
        "Cleansing Wildfire",
        "Blood Fountain",
    ]
    assert rows[0]["line_total_nzd"] == "4.00"
    assert "buyer_name" not in rows[0]
