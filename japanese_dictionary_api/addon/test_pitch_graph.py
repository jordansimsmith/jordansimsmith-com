"""Unit tests for the pitch-graph port.

Test cases mirror the SPA's `PitchGraph.test.tsx` plus parametrised tables
for the morae splitter (the small ya/yu/yo merge with the preceding char
and the stand-alone status of ん, っ and the long-vowel mark).
"""

import xml.etree.ElementTree as ET

import pytest

from addon.pitch_graph import is_mora_pitch_high, morae, render

_SVG_NS = "{http://www.w3.org/2000/svg}"


def _parse(svg):
    return ET.fromstring(svg)


@pytest.mark.parametrize(
    ("reading", "expected"),
    [
        ("たべる", ["た", "べ", "る"]),
        ("としょかん", ["と", "しょ", "か", "ん"]),
        ("じゅう", ["じゅ", "う"]),
        ("きょう", ["きょ", "う"]),
        ("ジュース", ["ジュ", "ー", "ス"]),
        ("がっこう", ["が", "っ", "こ", "う"]),
        ("しんぶん", ["し", "ん", "ぶ", "ん"]),
        ("コーヒー", ["コ", "ー", "ヒ", "ー"]),
        ("", []),
        ("ょう", ["ょ", "う"]),
    ],
)
def test_morae_splits_reading_correctly(reading, expected):
    assert morae(reading) == expected


@pytest.mark.parametrize(
    ("mora_index", "pitch", "expected"),
    [
        (0, 0, False),
        (1, 0, True),
        (3, 0, True),
        (0, 1, True),
        (1, 1, False),
        (0, 2, False),
        (1, 2, True),
        (2, 2, False),
    ],
)
def test_is_mora_pitch_high_matches_yomitan_rules(mora_index, pitch, expected):
    assert is_mora_pitch_high(mora_index, pitch) is expected


def test_render_returns_well_formed_svg_with_pronunciation_graph_class():
    svg_text = render("しんぶん", 0)
    svg = _parse(svg_text)
    assert svg.tag == f"{_SVG_NS}svg"
    assert svg.get("class") == "pronunciation-graph"


def test_render_with_empty_reading_emits_valid_svg_without_paths():
    svg_text = render("", 0)
    svg = _parse(svg_text)
    assert svg.get("viewBox") == "0 0 50 100"
    assert svg.findall(f"{_SVG_NS}circle") == []


@pytest.mark.parametrize(
    ("reading", "pitch", "moraepattern"),
    [
        ("しんぶん", 0, ["low", "high", "high", "high"]),
        ("くる", 1, ["high", "low"]),
        ("こころ", 2, ["low", "high", "low"]),
        ("はし", 2, ["low", "high"]),
    ],
)
def test_render_dot_layout_matches_pitch_pattern(reading, pitch, moraepattern):
    svg_text = render(reading, pitch)
    svg = _parse(svg_text)
    circles = svg.findall(f"{_SVG_NS}circle")
    actual = []
    seen_cx = set()
    for circle in circles:
        cx = circle.get("cx")
        if cx in seen_cx:
            # the downstep marker emits two circles at the same x; ignore the inner one
            continue
        seen_cx.add(cx)
        cy = circle.get("cy")
        actual.append("high" if cy == "25" else "low")
    assert actual == moraepattern


def test_render_emits_a_downstep_when_the_pitch_drops_on_the_next_mora():
    svg_text = render("こころ", 2)
    svg = _parse(svg_text)
    downsteps = [
        c
        for c in svg.findall(f"{_SVG_NS}circle")
        if c.get("class") == "pronunciation-graph-dot-downstep2"
    ]
    assert len(downsteps) == 1


def test_render_emits_a_triangle_for_the_trailing_particle():
    svg_text = render("はし", 2)
    svg = _parse(svg_text)
    triangle = next(
        p
        for p in svg.findall(f"{_SVG_NS}path")
        if p.get("class") == "pronunciation-graph-triangle"
    )
    assert "translate(" in triangle.get("transform")


def test_render_output_is_well_formed_xml():
    svg_text = render("しんぶん", 0)
    # parsing must not raise
    _parse(svg_text)
