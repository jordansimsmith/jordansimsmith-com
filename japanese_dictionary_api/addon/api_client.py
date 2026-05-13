"""HTTP client for `japanese_dictionary_api`.

Mirrors `anki_backup_api/addon/anki_backup.py`'s `send_request` pattern.
Credentials and the optional base URL override are read from the user
shell on every call so swapping the env vars takes effect on the next
invocation without restarting Anki.
"""

import json
import os
import urllib.parse
from dataclasses import dataclass
from typing import Any, Optional

import requests

DEFAULT_API_URL = "https://api.japanese-dictionary.jordansimsmith.com"
REQUEST_TIMEOUT_SECONDS = 60


@dataclass(frozen=True)
class Bookmark:
    sequence: int
    created_at: int
    expression: str
    reading: str
    reading_romaji: str
    frequency_rank: Optional[int]
    pitch: Optional[int]
    glossary_raw: Any


def send_request(method, path, body=None):
    user = os.getenv("JAPANESE_DICTIONARY_USER")
    if not user:
        raise Exception("JAPANESE_DICTIONARY_USER is not set.")
    password = os.getenv("JAPANESE_DICTIONARY_PASSWORD")
    if not password:
        raise Exception("JAPANESE_DICTIONARY_PASSWORD is not set.")

    base = os.getenv("JAPANESE_DICTIONARY_API_URL") or DEFAULT_API_URL
    if not base.endswith("/"):
        base += "/"
    if path.startswith("/"):
        path = path[1:]
    url = urllib.parse.urljoin(base, path)

    headers = {
        "Accept": "application/json;charset=UTF-8",
    }
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json;charset=UTF-8"
        data = json.dumps(body).encode("utf-8")

    response = requests.request(
        method,
        url,
        data=data,
        headers=headers,
        auth=(user, password),
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    if response.status_code < 200 or response.status_code >= 300:
        raise Exception(
            f"{method} {path} failed with status {response.status_code}: {response.text}"
        )
    return response


def find_bookmarks_with_terms():
    """Call `GET /bookmarks?include=term` and parse the response into `Bookmark`s."""
    response = send_request("GET", "/bookmarks?include=term")
    payload = response.json()
    return [
        Bookmark(
            sequence=row["sequence"],
            created_at=row["created_at"],
            expression=row["expression"],
            reading=row["reading"],
            reading_romaji=row["reading_romaji"],
            frequency_rank=row.get("frequency_rank"),
            pitch=row.get("pitch"),
            glossary_raw=row.get("glossary_raw"),
        )
        for row in payload.get("bookmarks", [])
    ]


def delete_bookmark(sequence):
    """Call `DELETE /bookmarks/{sequence}`. Raises on non-2xx."""
    send_request("DELETE", f"/bookmarks/{sequence}")
