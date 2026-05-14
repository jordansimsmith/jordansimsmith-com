"""jpod101 audio URL builder + downloader for the addon's commit pipeline.

Mirrors `_getInfoJpod101` in Yomitan's `audio-downloader.js`: the `kanji`
parameter is omitted when the term is kana-form.

Two non-obvious behaviours that the Python port has to replicate:

- jpod101 redirects matched terms to `cdn.innovativelanguage.com` on
  CloudFront. CloudFront rejects the default `python-requests/X.Y.Z`
  User-Agent with a 403, so every download silently fails. We force a
  browser-style UA to avoid this.
- jpod101 returns 200 + `audio/mpeg` for terms that have no recording, but
  the body is a fixed "audio not available" stub. Yomitan filters this by
  SHA-256; we copy the digest verbatim from `audio-downloader.js`.
"""

import hashlib
import re
from urllib.parse import urlencode

import requests

JPOD101_BASE = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php"
REQUEST_TIMEOUT_SECONDS = 30
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)
JPOD101_INVALID_AUDIO_SHA256 = (
    "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"
)


def jpod101_url(expression, reading, kana_form):
    """Build the jpod101 lookup URL for `(expression, reading, kana_form)`."""
    params = []
    if not kana_form and expression:
        params.append(("kanji", expression))
    if reading:
        params.append(("kana", reading))
    return f"{JPOD101_BASE}?{urlencode(params)}"


def download(url):
    """Fetch `url` and return `(bytes, content_type)` on success, or `None`.

    Returns None on any error: network failure, non-2xx status, a response
    whose `Content-Type` is not `audio/*`, or the jpod101 "audio not
    available" stub (matched by SHA-256, mirroring Yomitan).
    """
    try:
        response = requests.get(
            url,
            timeout=REQUEST_TIMEOUT_SECONDS,
            headers={"User-Agent": USER_AGENT},
        )
    except requests.RequestException:
        return None
    if response.status_code != 200:
        return None
    content_type = response.headers.get("Content-Type", "")
    if not content_type.lower().startswith("audio/"):
        return None
    if hashlib.sha256(response.content).hexdigest() == JPOD101_INVALID_AUDIO_SHA256:
        return None
    return response.content, content_type


def media_filename(expression, reading, content_type):
    """Return a sanitised Anki media filename for this term's audio."""
    extension = _extension_for(content_type)
    expression_part = _sanitise(expression) or "term"
    reading_part = _sanitise(reading) or "reading"
    return f"jpod101_{expression_part}_{reading_part}.{extension}"


def _sanitise(value):
    if value is None:
        return ""
    cleaned = re.sub(r"[^\w\u3000-\u9fff\u30a0-\u30ff\u3040-\u309f-]+", "_", value)
    cleaned = cleaned.strip("._ ")
    return cleaned


def _extension_for(content_type):
    if not content_type:
        return "mp3"
    primary = content_type.split(";", 1)[0].strip().lower()
    return {
        "audio/mpeg": "mp3",
        "audio/mp3": "mp3",
        "audio/ogg": "ogg",
        "audio/wav": "wav",
        "audio/x-wav": "wav",
    }.get(primary, "mp3")
