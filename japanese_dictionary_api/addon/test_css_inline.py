"""Unit tests for the CSS inliner."""

import xml.etree.ElementTree as ET

from addon.css_inline import inline_styles


def _make_tree(html):
    return ET.fromstring(html)


def _inline(html):
    root = _make_tree(html)
    inline_styles(root)
    return ET.tostring(root, encoding="unicode", short_empty_elements=False)


def test_simple_attribute_selector_inlines_style():
    html = '<span class="structured-content"><span data-sc-content="part-of-speech-info">noun</span></span>'
    result = _inline(html)
    assert "background-color:" in result


def test_tag_and_attribute_selector():
    html = '<span class="structured-content"><span data-sc-class="tag" data-sc-content="part-of-speech-info" title="noun">noun</span></span>'
    result = _inline(html)
    assert "border-radius:" in result
    assert "font-weight:" in result
    assert "cursor:" in result


def test_multiple_rules_merge_on_same_element():
    html = '<span class="structured-content"><span data-sc-class="tag" data-sc-content="part-of-speech-info" title="noun">noun</span></span>'
    result = _inline(html)
    style = _extract_style(result, "span", "data-sc-content", "part-of-speech-info")
    assert "border-radius:" in style
    assert "background-color:" in style
    assert "cursor:" in style


def test_descendant_combinator():
    html = (
        '<span class="structured-content">'
        '<li data-sc-content="sense">'
        '<ul data-sc-content="glossary"><li>eat</li></ul>'
        "</li></span>"
    )
    result = _inline(html)
    assert "list-style-type: none;" in result


def test_child_combinator():
    html = (
        '<span class="structured-content">'
        '<td data-sc-class="form-pri"><span data-test="inner">x</span></td>'
        "</span>"
    )
    result = _inline(html)
    inner_span_style = _extract_style(result, "span", "data-test", "inner")
    assert inner_span_style is not None
    assert "background:" in inner_span_style or "color:" in inner_span_style


def test_adjacent_sibling_combinator():
    html = (
        '<span class="structured-content">'
        '<ul><li data-sc-content="sense-group">first</li>'
        '<li data-sc-content="sense-group">second</li></ul>'
        "</span>"
    )
    result = _inline(html)
    assert "margin-top: 0.5em;" in result


def test_first_child_pseudo_class():
    html = (
        '<span class="structured-content">'
        '<ul><li data-sc-content="sense-group">first</li>'
        '<li data-sc-content="sense-group">second</li></ul>'
        "</span>"
    )
    result = _inline(html)
    root = ET.fromstring(result)
    first_li = root.find("./ul/li")
    second_li = list(root.find("./ul"))[1]
    first_style = first_li.get("style", "")
    second_style = second_li.get("style", "")
    assert "margin-top: 0.1em;" in first_style
    assert "margin-top: 0.1em;" not in second_style


def test_pseudo_element_selectors_are_skipped():
    html = (
        '<span class="structured-content">'
        '<td data-sc-class="form-pri"><span>x</span></td>'
        "</span>"
    )
    result = _inline(html)
    assert "::before" not in result
    assert "content:" not in result


def test_class_selector_matches_gloss_link_external_icon():
    html = (
        '<span class="structured-content">'
        '<span class="gloss-link-external-icon icon">x</span>'
        "</span>"
    )
    result = _inline(html)
    assert "display: none;" in result or "display:none;" in result


def test_classes_are_stripped_after_inlining():
    html = (
        '<span class="structured-content">'
        '<span class="gloss-sc-span" data-sc-content="misc-info">kana</span>'
        "</span>"
    )
    result = _inline(html)
    assert 'class="' not in result
    assert "data-sc-content" in result


def test_existing_inline_styles_are_preserved():
    html = (
        '<span class="structured-content">'
        '<li data-sc-content="sense" style="list-style-type:&quot;①&quot;;">'
        "definition</li></span>"
    )
    result = _inline(html)
    assert "①" in result
    assert "padding-left:" in result


def test_table_elements_get_yomitan_styles():
    html = (
        '<span class="structured-content">'
        '<div class="gloss-sc-table-container">'
        '<table class="gloss-sc-table">'
        '<tr><th class="gloss-sc-th">h</th>'
        '<td class="gloss-sc-td">d</td></tr>'
        "</table></div></span>"
    )
    result = _inline(html)
    assert "border-collapse:" in result
    assert "font-weight:" in result
    assert "border-style:" in result


def test_full_jitendex_pos_tag_structure():
    html = (
        '<span class="structured-content">'
        '<ul class="gloss-sc-ul" data-sc-content="sense-groups" lang="ja">'
        '<li class="gloss-sc-li" data-sc-content="sense-group">'
        '<span class="gloss-sc-span" data-sc-class="tag" data-sc-code="n" '
        'data-sc-content="part-of-speech-info" '
        'title="noun (common) (futsuumeishi)">noun</span>'
        "</li></ul></span>"
    )
    result = _inline(html)
    assert ' class="gloss-sc' not in result
    assert ' class="structured-content"' not in result
    pos_style = _extract_style(result, "span", "data-sc-content", "part-of-speech-info")
    assert "background-color:" in pos_style
    assert "color:" in pos_style
    assert "font-weight:" in pos_style
    assert "border-radius:" in pos_style
    assert "padding:" in pos_style


def _extract_style(html, tag, attr_name, attr_val):
    root = ET.fromstring(html)
    for el in root.iter(tag):
        if attr_name is None or el.get(attr_name) == attr_val:
            return el.get("style", "")
    return None
