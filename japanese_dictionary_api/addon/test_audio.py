"""Unit tests for the audio module.

URL builder cases are table-driven. `download` is exercised via
`unittest.mock.patch` over `requests.get` covering happy path, 404,
timeout, and non-audio content type.
"""

from unittest.mock import patch

import pytest
import requests

from addon.audio import download, jpod101_url, media_filename


@pytest.mark.parametrize(
    ("expression", "reading", "kana_form", "expected"),
    [
        pytest.param(
            "新橋",
            "しんばし",
            False,
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E6%96%B0%E6%A9%8B&kana=%E3%81%97%E3%82%93%E3%81%B0%E3%81%97",
            id="kanji_and_reading_both_passed",
        ),
        pytest.param(
            "テレビ",
            "テレビ",
            True,
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kana=%E3%83%86%E3%83%AC%E3%83%93",
            id="kana_form_drops_kanji_parameter",
        ),
        pytest.param(
            "&=?",
            "abc",
            False,
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%26%3D%3F&kana=abc",
            id="special_characters_are_url_encoded",
        ),
    ],
)
def test_jpod101_url_builds_expected_url(expression, reading, kana_form, expected):
    assert jpod101_url(expression, reading, kana_form) == expected


@pytest.mark.parametrize(
    ("expression", "reading", "content_type", "expected"),
    [
        pytest.param("新橋", "しんばし", "audio/mpeg", "jpod101_新橋_しんばし.mp3"),
        pytest.param("テレビ", "テレビ", "audio/mpeg", "jpod101_テレビ_テレビ.mp3"),
        pytest.param("a/b", "c d", "audio/mpeg", "jpod101_a_b_c_d.mp3"),
        pytest.param(".hidden", "..dots", "audio/mpeg", "jpod101_hidden_dots.mp3"),
        pytest.param("新橋", "しんばし", "audio/ogg", "jpod101_新橋_しんばし.ogg"),
    ],
)
def test_media_filename_sanitises_input_and_picks_extension(
    expression, reading, content_type, expected
):
    assert media_filename(expression, reading, content_type) == expected


def test_media_filename_is_deterministic_for_identical_inputs():
    a = media_filename("新橋", "しんばし", "audio/mpeg")
    b = media_filename("新橋", "しんばし", "audio/mpeg")
    assert a == b


class _FakeResponse:
    def __init__(self, status_code, content, content_type):
        self.status_code = status_code
        self.content = content
        self.headers = {"Content-Type": content_type}


def test_download_happy_path_returns_bytes_and_content_type():
    with patch("addon.audio.requests.get") as get:
        get.return_value = _FakeResponse(200, b"\x00\x01\x02", "audio/mpeg")
        result = download("https://example.com/audio.mp3")
    assert result == (b"\x00\x01\x02", "audio/mpeg")


def test_download_returns_none_on_404():
    with patch("addon.audio.requests.get") as get:
        get.return_value = _FakeResponse(404, b"", "text/html")
        assert download("https://example.com/missing.mp3") is None


def test_download_returns_none_on_timeout():
    with patch("addon.audio.requests.get") as get:
        get.side_effect = requests.Timeout()
        assert download("https://example.com/slow.mp3") is None


def test_download_returns_none_on_non_audio_content_type():
    with patch("addon.audio.requests.get") as get:
        get.return_value = _FakeResponse(200, b"<html>", "text/html")
        assert download("https://example.com/notaudio.mp3") is None
