"""Unit tests for the kana-form detector."""

from dataclasses import dataclass
from typing import Any

import pytest

from addon.kana_form import detect


@dataclass
class FakeBookmark:
    expression: str
    reading: str
    glossary_raw: Any = None


def _uk_headword(extra=None):
    content = [
        {
            "tag": "span",
            "title": "noun (common) (futsuumeishi)",
            "data": {"class": "tag", "code": "n", "content": "part-of-speech-info"},
            "content": "noun",
        },
        {
            "tag": "span",
            "title": "word usually written using kana alone",
            "data": {"class": "tag", "code": "uk", "content": "misc-info"},
            "content": "kana",
        },
    ]
    if extra:
        content.extend(extra)
    return {
        "tag": "div",
        "data": {"content": "sense-group"},
        "content": content,
    }


def _plain_headword():
    return {
        "tag": "div",
        "data": {"content": "sense-group"},
        "content": [
            {
                "tag": "span",
                "title": "noun (common) (futsuumeishi)",
                "data": {"class": "tag", "code": "n", "content": "part-of-speech-info"},
                "content": "noun",
            },
        ],
    }


@pytest.mark.parametrize(
    ("bookmark", "expected"),
    [
        # kana-only headword: expression == reading and no glossary marker
        pytest.param(
            FakeBookmark(
                expression="テレビ", reading="テレビ", glossary_raw=_plain_headword()
            ),
            True,
            id="kana_only_expression_equals_reading",
        ),
        # kanji + reading headword, no uk marker -> False
        pytest.param(
            FakeBookmark(
                expression="新橋", reading="しんばし", glossary_raw=_plain_headword()
            ),
            False,
            id="kanji_with_reading_no_marker",
        ),
        # rare kanji form with the uk marker present -> True
        pytest.param(
            FakeBookmark(
                expression="橡", reading="とちのき", glossary_raw=_uk_headword()
            ),
            True,
            id="rare_kanji_with_uk_marker",
        ),
        # uk marker takes precedence over the expression != reading fallback
        pytest.param(
            FakeBookmark(
                expression="海豚", reading="いるか", glossary_raw=_uk_headword()
            ),
            True,
            id="kanji_form_with_uk_marker_treated_as_kana",
        ),
        # no glossary, no marker -> fall back to expression == reading
        pytest.param(
            FakeBookmark(expression="カメラ", reading="カメラ", glossary_raw=None),
            True,
            id="missing_glossary_falls_back_to_string_equality",
        ),
        # fully missing data -> False
        pytest.param(
            FakeBookmark(expression="", reading="", glossary_raw=None),
            False,
            id="missing_everything_returns_false",
        ),
    ],
)
def test_detect_returns_expected_kana_form(bookmark, expected):
    assert detect(bookmark) is expected
