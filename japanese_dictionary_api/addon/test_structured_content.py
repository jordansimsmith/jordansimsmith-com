"""Unit tests for the structured-content port.

Fixtures mirror representative shapes from Yomitan/Jitendex glossary trees:
fully kana headwords (with the "uk" misc-info marker), kanji+reading
headwords without the marker, lists, ruby blocks, image nodes, internal /
external links, and inline-style propagation.
"""

import xml.etree.ElementTree as ET

from addon.jitendex_styles import JITENDEX_STYLES_CSS
from addon.structured_content import GLOSSARY_WRAPPER_CLASS, render, render_field
from addon.yomitan_styles import YOMITAN_STRUCTURED_CONTENT_CSS


def _parse(html):
    return ET.fromstring(html)


def test_render_wraps_output_in_structured_content_span():
    html, _ = render({"tag": "div", "content": "hi"})
    root = _parse(html)
    assert root.tag == "span"
    assert root.get("class") == "structured-content"


def test_render_string_node_emits_text():
    html, _ = render("hello")
    root = _parse(html)
    assert root.text == "hello"


def test_kana_form_marker_detected_for_uk_misc_info_span():
    headword = {
        "tag": "div",
        "data": {"content": "sense-group"},
        "content": [
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
        ],
    }
    _, kana_seen = render(headword)
    assert kana_seen is True


def test_kana_form_marker_not_set_for_plain_kanji_reading_headword():
    headword = {
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
    _, kana_seen = render(headword)
    assert kana_seen is False


def test_unordered_list_with_nested_items_renders_recursively():
    html, _ = render(
        {
            "tag": "ul",
            "data": {"content": "glossary"},
            "content": [
                {"tag": "li", "content": "to eat"},
                {"tag": "li", "content": "to consume"},
            ],
        }
    )
    root = _parse(html)
    ul = root.find("ul")
    assert ul is not None
    assert ul.get("class") == "gloss-sc-ul"
    items = ul.findall("li")
    assert [item.text for item in items] == ["to eat", "to consume"]
    assert all(item.get("class") == "gloss-sc-li" for item in items)


def test_ruby_block_renders_furigana_structure():
    html, _ = render(
        {
            "tag": "ruby",
            "content": [
                "新",
                {"tag": "rt", "content": "しん"},
            ],
        }
    )
    root = _parse(html)
    ruby = root.find("ruby")
    assert ruby is not None
    assert ruby.get("class") == "gloss-sc-ruby"
    assert ruby.text == "新"
    rt = ruby.find("rt")
    assert rt is not None
    assert rt.text == "しん"
    assert rt.get("class") == "gloss-sc-rt"


def test_image_node_renders_placeholder_with_description():
    html, _ = render(
        {
            "tag": "img",
            "path": "jitendex/graphics/heart.png",
            "description": "heart",
        }
    )
    root = _parse(html)
    span = root.find("span")
    assert span is not None
    assert span.get("class") == "gloss-image-placeholder"
    assert span.text == "[image: heart]"


def test_image_node_falls_back_to_path_when_description_missing():
    html, _ = render(
        {
            "tag": "img",
            "path": "jitendex/graphics/sakura.png",
        }
    )
    root = _parse(html)
    span = root.find("span")
    assert span is not None
    assert span.text == "[image: jitendex/graphics/sakura.png]"


def test_internal_link_rewrites_href_to_spa_search_route():
    html, _ = render({"tag": "a", "href": "?query=寺", "content": "寺"})
    root = _parse(html)
    anchor = root.find("a")
    assert anchor is not None
    assert (
        anchor.get("href")
        == "https://japanese-dictionary.jordansimsmith.com/search?query=寺"
    )
    assert anchor.get("data-external") == "false"
    assert anchor.get("target") is None


def test_external_link_keeps_href_and_marks_data_external_true():
    html, _ = render(
        {
            "tag": "a",
            "href": "https://www.edrdg.org/jmwsgi/entr.py?svc=jmdict&q=1316830",
            "content": "JMdict",
        }
    )
    root = _parse(html)
    anchor = root.find("a")
    assert anchor is not None
    assert (
        anchor.get("href")
        == "https://www.edrdg.org/jmwsgi/entr.py?svc=jmdict&q=1316830"
    )
    assert anchor.get("data-external") == "true"
    assert anchor.get("target") == "_blank"
    assert anchor.get("rel") == "noopener noreferrer"
    icon = anchor.find("span[@class='gloss-link-external-icon icon']")
    assert icon is not None
    assert icon.get("data-icon") == "external-link"


def test_inline_style_propagation_font_color_and_margin():
    html, _ = render(
        {
            "tag": "div",
            "style": {
                "fontWeight": "bold",
                "color": "red",
                "marginTop": 0.5,
                "marginLeft": "1em",
            },
            "content": "styled",
        }
    )
    root = _parse(html)
    div = root.find("div")
    assert div is not None
    style = div.get("style") or ""
    assert "font-weight:bold;" in style
    assert "color:red;" in style
    assert "margin-top:0.5em;" in style
    assert "margin-left:1em;" in style


def test_jitendex_structured_content_wrapper_is_transparent():
    html, _ = render(
        [
            {
                "type": "structured-content",
                "content": [
                    {"tag": "div", "content": "wrapped"},
                ],
            }
        ]
    )
    root = _parse(html)
    div = root.find("div")
    assert div is not None
    assert div.get("class") == "gloss-sc-div"
    assert div.text == "wrapped"


def test_data_attributes_carry_sc_prefix_and_kebab_case_key():
    html, _ = render(
        {
            "tag": "span",
            "data": {"class": "tag", "content": "part-of-speech-info"},
            "content": "noun",
        }
    )
    root = _parse(html)
    span = root.find("span")
    assert span is not None
    assert span.get("data-sc-class") == "tag"
    assert span.get("data-sc-content") == "part-of-speech-info"


def test_render_field_wraps_inner_span_in_yomitan_glossary_div():
    field, _ = render_field({"tag": "div", "content": "hi"})
    assert field.startswith(
        f'<div class="{GLOSSARY_WRAPPER_CLASS}" style="text-align: left;">'
    )
    assert field.endswith("</div>")
    assert '<span class="structured-content">' in field


def test_render_field_appends_flattened_scoped_stylesheet():
    field, _ = render_field({"tag": "div", "content": "hi"})
    assert "<style>" in field
    assert "</style></div>" in field
    assert f".{GLOSSARY_WRAPPER_CLASS} span[data-sc-class=" in field
    assert f".{GLOSSARY_WRAPPER_CLASS} div[data-sc-content=" in field


def test_render_field_includes_yomitan_rules_scoped():
    field, _ = render_field({"tag": "div", "content": "hi"})
    assert f".{GLOSSARY_WRAPPER_CLASS} .gloss-link-external-icon" in field
    assert f".{GLOSSARY_WRAPPER_CLASS} .gloss-sc-table" in field


def test_render_field_emits_yomitan_rules_before_jitendex_rules():
    field, _ = render_field({"tag": "div", "content": "hi"})
    yomitan_idx = field.index(f".{GLOSSARY_WRAPPER_CLASS} .gloss-link-external-icon")
    jitendex_idx = field.index(f".{GLOSSARY_WRAPPER_CLASS} span[data-sc-class=")
    assert yomitan_idx < jitendex_idx


def test_render_field_css_is_flat_with_no_nesting():
    field, _ = render_field({"tag": "div", "content": "hi"})
    style_start = field.index("<style>") + len("<style>")
    style_end = field.index("</style>")
    css = field[style_start:style_end]
    for line in css.splitlines():
        stripped = line.strip()
        if stripped.startswith("&"):
            raise AssertionError(f"Unresolved nesting: {stripped}")
    # no nested braces - every { should be followed by declarations then }
    # before another { appears (i.e., no rule blocks inside rule blocks)
    in_block = False
    for char in css:
        if char == "{":
            assert not in_block, "Nested braces found in flattened CSS"
            in_block = True
        elif char == "}":
            in_block = False


def test_render_field_propagates_kana_form_marker():
    headword = {
        "tag": "span",
        "data": {"class": "tag", "code": "uk", "content": "misc-info"},
        "content": "kana",
    }
    _, kana_seen = render_field(headword)
    assert kana_seen is True


def test_render_field_kana_marker_false_for_non_kana_entry():
    headword = {
        "tag": "span",
        "data": {"class": "tag", "code": "n", "content": "part-of-speech-info"},
        "content": "noun",
    }
    _, kana_seen = render_field(headword)
    assert kana_seen is False
