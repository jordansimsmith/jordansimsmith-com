import io

import pytest
import requests

from mint_token import (
    CONNECT_TIMEOUT_SECONDS,
    FIREBASE_API_KEY,
    READ_TIMEOUT_SECONDS,
    TOKEN_URL,
    main,
)


class _FakeCookies:
    def __init__(self):
        self.clear_count = 0

    def clear(self):
        self.clear_count += 1


class _FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload
        self.json_calls = 0

    def json(self):
        self.json_calls += 1
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class _FakeSession:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.headers = {
            "Authorization": "Bearer ambient-secret",
            "Cookie": "session=ambient-secret",
            "Existing": "kept",
        }
        self.cookies = _FakeCookies()
        self.trust_env = True
        self.auth = ("ambient-user", "ambient-secret")
        self.calls = []

    def request(
        self,
        method,
        url,
        *,
        params,
        data,
        headers,
        timeout,
        allow_redirects,
    ):
        self.calls.append(
            {
                "method": method,
                "url": url,
                "params": params,
                "data": data,
                "headers": headers,
                "timeout": timeout,
                "allow_redirects": allow_redirects,
            }
        )
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


class _InteractiveOutput(io.StringIO):
    def isatty(self):
        return True


def _success_payload(**overrides):
    payload = {
        "id_token": "header.payload.signature",
        "token_type": "Bearer",
        "expires_in": "3600",
        "refresh_token": "possibly-returned-refresh-token",
    }
    payload.update(overrides)
    return payload


def _run(responses, *, refresh_token="test-refresh-token", stdout=None):
    session = _FakeSession(responses)
    stdout = io.StringIO() if stdout is None else stdout
    stderr = io.StringIO()

    exit_code = main(
        environ={"FETCHTCG_REFRESH_TOKEN": refresh_token},
        stdout=stdout,
        stderr=stderr,
        session=session,
    )

    return exit_code, stdout, stderr, session


def test_main_mints_token_with_exact_isolated_request():
    # arrange
    response = _FakeResponse(200, _success_payload())

    # act
    exit_code, stdout, stderr, session = _run([response])

    # assert
    assert exit_code == 0
    assert stdout.getvalue() == "header.payload.signature\n"
    assert stderr.getvalue() == ""
    assert session.calls == [
        {
            "method": "POST",
            "url": TOKEN_URL,
            "params": {"key": FIREBASE_API_KEY},
            "data": {
                "grant_type": "refresh_token",
                "refresh_token": "test-refresh-token",
            },
            "headers": {
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            "timeout": (CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
            "allow_redirects": False,
        }
    ]
    assert session.trust_env is False
    assert session.auth is None
    assert "Authorization" not in session.headers
    assert "Cookie" not in session.headers
    assert session.headers["Existing"] == "kept"
    assert session.cookies.clear_count == 3
    assert response.json_calls == 1


@pytest.mark.parametrize(
    "refresh_token",
    [None, "", "   ", "Bearer token", "token with spaces"],
)
def test_main_rejects_missing_or_malformed_refresh_token(refresh_token):
    # arrange
    secret = refresh_token or ""

    # act
    exit_code, stdout, stderr, session = _run([], refresh_token=refresh_token)

    # assert
    assert exit_code == 1
    assert stdout.getvalue() == ""
    assert "FETCHTCG_REFRESH_TOKEN is missing or malformed" in stderr.getvalue()
    assert not secret or secret not in stderr.getvalue()
    assert session.calls == []


def test_main_refuses_to_print_token_to_interactive_terminal():
    # arrange
    stdout = _InteractiveOutput()

    # act
    exit_code, stdout, stderr, session = _run([], stdout=stdout)

    # assert
    assert exit_code == 2
    assert stdout.getvalue() == ""
    assert "refusing to print" in stderr.getvalue()
    assert session.calls == []


def test_main_rejects_http_failure_without_parsing_or_exposing_secrets():
    # arrange
    refresh_token = "refresh-secret-that-must-not-leak"
    response = _FakeResponse(
        400,
        {
            "error": {
                "message": refresh_token,
            }
        },
    )

    # act
    exit_code, stdout, stderr, _ = _run(
        [response],
        refresh_token=refresh_token,
    )

    # assert
    assert exit_code == 1
    assert stdout.getvalue() == ""
    assert "token request was rejected" in stderr.getvalue()
    assert refresh_token not in stderr.getvalue()
    assert response.json_calls == 0


def test_main_rejects_network_failure_without_exposing_exception():
    # arrange
    refresh_token = "refresh-secret-that-must-not-leak"

    # act
    exit_code, stdout, stderr, session = _run(
        [requests.ConnectionError(refresh_token)],
        refresh_token=refresh_token,
    )

    # assert
    assert exit_code == 1
    assert stdout.getvalue() == ""
    assert "token request failed" in stderr.getvalue()
    assert refresh_token not in stderr.getvalue()
    assert session.cookies.clear_count == 3


@pytest.mark.parametrize(
    "payload",
    [
        [],
        {},
        _success_payload(id_token=None),
        _success_payload(id_token=""),
        _success_payload(id_token="not-a-jwt"),
        _success_payload(id_token="token with spaces"),
        _success_payload(token_type="bearer"),
        _success_payload(expires_in=None),
        _success_payload(expires_in=True),
        _success_payload(expires_in=3600),
        _success_payload(expires_in="not-a-number"),
        _success_payload(expires_in="0"),
        ValueError("response-secret-that-must-not-leak"),
    ],
)
def test_main_rejects_malformed_response_without_exposing_it(payload):
    # arrange
    response = _FakeResponse(200, payload)

    # act
    exit_code, stdout, stderr, _ = _run([response])

    # assert
    assert exit_code == 1
    assert stdout.getvalue() == ""
    assert "token response was invalid" in stderr.getvalue()
    assert "response-secret" not in stderr.getvalue()
