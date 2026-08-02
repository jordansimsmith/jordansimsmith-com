import ast

import pytest

from fetch_set_mapping import FETCH_SET_MAPPINGS
from generate_fetch_set_mapping import (
    _apply_additional_set_mappings,
    _normalized_name,
    _render_mapping,
)


@pytest.mark.parametrize(
    "set_code",
    [
        "2xm",
        "aer",
        "arb",
        "blb",
        "ddu",
        "drc",
        "dsc",
        "dsk",
        "dtk",
        "eoe",
        "fdn",
        "fic",
        "fin",
        "m21",
        "msc",
        "otc",
        "rvr",
        "soa",
        "tdc",
        "tla",
        "woc",
    ],
)
def test_mapping_covers_example_scan_sets(set_code):
    assert FETCH_SET_MAPPINGS[set_code]


def test_mapping_covers_fetch_catalog_sets_with_scryfall_codes():
    assert len(FETCH_SET_MAPPINGS) >= 750


def test_mapping_covers_plst_fetch_catalog_split():
    assert (3075, "Mystery Booster") in FETCH_SET_MAPPINGS["plst"]
    assert (3267, "The List") in FETCH_SET_MAPPINGS["plst"]


def test_additional_set_mappings_preserve_shared_fetch_set_aliases():
    mapping = {"pf24": {3075: "Mystery Booster"}}

    _apply_additional_set_mappings(
        mapping,
        {3075: "Mystery Booster"},
    )

    assert mapping["pf24"] == {3075: "Mystery Booster"}
    assert mapping["plst"] == {3075: "Mystery Booster"}


def test_normalized_name_ignores_case_punctuation_and_word_order():
    assert _normalized_name("Final Fantasy Commander") == _normalized_name(
        "Commander: FINAL FANTASY"
    )


def test_normalized_name_ignores_magic_brand_prefix():
    assert _normalized_name("Magic: The Gathering—FINAL FANTASY") == _normalized_name(
        "FINAL FANTASY"
    )


def test_render_mapping_orders_codes_and_fetch_ids():
    rendered = _render_mapping(
        {
            "fin": {
                4228: "FINAL FANTASY: Through the Ages",
                4226: "FINAL FANTASY",
            },
            "fic": {4232: "Commander: FINAL FANTASY"},
        }
    )

    mapping = ast.literal_eval(rendered.removeprefix("FETCH_SET_MAPPINGS = "))

    assert list(mapping) == ["fic", "fin"]
    assert mapping["fin"] == (
        (4226, "FINAL FANTASY"),
        (4228, "FINAL FANTASY: Through the Ages"),
    )
