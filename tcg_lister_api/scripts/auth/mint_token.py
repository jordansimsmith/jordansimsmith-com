import os
import re
import sys

import requests


TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
FIREBASE_API_KEY = "AIzaSyD7SVUprLrgU-bc0Oh756v17y5NKZNQBB8"
CONNECT_TIMEOUT_SECONDS = 5
READ_TIMEOUT_SECONDS = 30


class TokenMintError(RuntimeError):
    pass


def mint_token(refresh_token, *, session=None):
    if (
        not isinstance(refresh_token, str)
        or not refresh_token
        or re.search(r"\s", refresh_token)
    ):
        raise TokenMintError("FETCHTCG_REFRESH_TOKEN is missing or malformed")

    session = session or requests.Session()
    _harden_session(session)
    _clear_cookies(session)
    try:
        response = session.request(
            "POST",
            TOKEN_URL,
            params={"key": FIREBASE_API_KEY},
            data={
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
            },
            headers={
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            timeout=(CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
            allow_redirects=False,
        )
    except requests.RequestException as error:
        _clear_cookies(session)
        raise TokenMintError("Firebase token request failed") from error
    _clear_cookies(session)

    if response.status_code != 200:
        raise TokenMintError("Firebase token request was rejected")

    try:
        payload = response.json()
    except ValueError as error:
        raise TokenMintError("Firebase token response was invalid") from error

    if not isinstance(payload, dict):
        raise TokenMintError("Firebase token response was invalid")
    id_token = payload.get("id_token")
    if (
        not isinstance(id_token, str)
        or not id_token
        or re.search(r"\s", id_token)
        or id_token.count(".") != 2
        or payload.get("token_type") != "Bearer"
    ):
        raise TokenMintError("Firebase token response was invalid")
    expires_value = payload.get("expires_in")
    if not isinstance(expires_value, str) or not expires_value.isdecimal():
        raise TokenMintError("Firebase token response was invalid")
    expires_in = int(expires_value)
    if expires_in <= 0:
        raise TokenMintError("Firebase token response was invalid")
    return id_token


def main(*, environ=None, stdout=None, stderr=None, session=None):
    environ = os.environ if environ is None else environ
    stdout = sys.stdout if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr

    if stdout.isatty():
        print(
            "error: refusing to print a Fetch token to an interactive terminal; "
            "use command substitution",
            file=stderr,
        )
        return 2

    try:
        token = mint_token(
            environ.get("FETCHTCG_REFRESH_TOKEN"),
            session=session,
        )
    except TokenMintError as error:
        print(f"error: {error}", file=stderr)
        return 1

    stdout.write(f"{token}\n")
    return 0


def _harden_session(session):
    if hasattr(session, "trust_env"):
        session.trust_env = False
    if hasattr(session, "auth"):
        session.auth = None
    headers = getattr(session, "headers", None)
    if headers is not None:
        for header in list(headers):
            if header.casefold() in ("authorization", "cookie"):
                del headers[header]
    _clear_cookies(session)


def _clear_cookies(session):
    cookies = getattr(session, "cookies", None)
    if cookies is not None and hasattr(cookies, "clear"):
        cookies.clear()


if __name__ == "__main__":
    raise SystemExit(main())
