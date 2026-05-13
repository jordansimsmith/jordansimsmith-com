"""Unit tests for the note builder.

Uses fake `col` and `model` doubles to avoid needing the Anki runtime.
Validates field-mapping correctness, the kana/kanji Word branch, and the
duplicate-check query shape.
"""

from dataclasses import dataclass
from typing import Any, Optional

from addon.note_builder import build_note, is_duplicate

_DEFAULT_CONFIG = {
    "field_mapping": {
        "Word": "Word",
        "Reading": "Reading",
        "Glossary": "Glossary",
        "Audio": "Audio",
        "Graph": "Graph",
    },
    "tag": "japanese_dictionary",
}


@dataclass
class FakeBookmark:
    sequence: int
    expression: str
    reading: str
    pitch: Optional[int] = None
    glossary_raw: Any = None


class FakeNote(dict):
    def __init__(self):
        super().__init__()
        self.tags = []


class FakeCollection:
    def __init__(self, dupes=None):
        self.dupes = dupes or {}
        self.last_query = None

    def new_note(self, model):
        return FakeNote()

    def find_notes(self, query):
        self.last_query = query
        return self.dupes.get(query, [])


def test_build_note_uses_expression_when_term_is_not_kana_form():
    col = FakeCollection()
    bookmark = FakeBookmark(
        sequence=1316830,
        expression="新橋",
        reading="しんばし",
        pitch=0,
        glossary_raw={"tag": "div", "content": "Shinbashi"},
    )

    note = build_note(
        col, model=object(), deck_id=1, bookmark=bookmark, config=_DEFAULT_CONFIG
    )

    assert note["Word"] == "新橋"
    assert note["Reading"] == "しんばし"
    assert "Shinbashi" in note["Glossary"]
    assert note["Audio"] == ""
    assert note["Graph"]
    assert note.tags == ["japanese_dictionary"]


def test_build_note_uses_reading_when_uk_marker_present():
    bookmark = FakeBookmark(
        sequence=1922090,
        expression="橡",
        reading="とちのき",
        glossary_raw={
            "tag": "div",
            "content": [
                {
                    "tag": "span",
                    "data": {"class": "tag", "code": "uk", "content": "misc-info"},
                    "content": "kana",
                }
            ],
        },
    )

    note = build_note(FakeCollection(), object(), 1, bookmark, _DEFAULT_CONFIG)

    assert note["Word"] == "とちのき"


def test_build_note_leaves_graph_empty_when_pitch_missing():
    bookmark = FakeBookmark(
        sequence=1,
        expression="葉節点",
        reading="はせってん",
        pitch=None,
        glossary_raw={"tag": "div", "content": "leaf node"},
    )

    note = build_note(FakeCollection(), object(), 1, bookmark, _DEFAULT_CONFIG)

    assert note["Graph"] == ""


def test_is_duplicate_returns_true_when_collection_returns_matches():
    expected_query = 'deck:"Mining" "Word:新橋"'
    col = FakeCollection(dupes={expected_query: [42]})
    assert is_duplicate(col, "Mining", "Word", "新橋") is True
    assert col.last_query == expected_query


def test_is_duplicate_returns_false_for_empty_value():
    col = FakeCollection()
    assert is_duplicate(col, "Mining", "Word", "") is False
    assert col.last_query is None
