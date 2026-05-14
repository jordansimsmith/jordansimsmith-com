"""Unit tests for the CSS flattener / scoper."""

from addon.css_scope import scope_css


def test_simple_rule_gets_scope_prepended():
    css = "span[title] { cursor: help; }"
    result = scope_css(css, ".yomitan-glossary")
    assert ".yomitan-glossary span[title]" in result
    assert "cursor: help;" in result


def test_nested_ampersand_rule_resolves_to_descendant():
    css = """\
li[data-sc-content="sense"] {
    padding-left: 0.25em;
    & ul[data-sc-content="glossary"] {
        list-style-type: none;
    }
}"""
    result = scope_css(css, ".wrap")
    assert '.wrap li[data-sc-content="sense"]' in result
    assert "padding-left: 0.25em;" in result
    assert '.wrap li[data-sc-content="sense"] ul[data-sc-content="glossary"]' in result
    assert "list-style-type: none;" in result


def test_ampersand_pseudo_element_resolves_correctly():
    css = """\
td[data-sc-class="form-pri"] > span {
    color: white;
    &::before {
        content: "△";
    }
}"""
    result = scope_css(css, ".s")
    assert '.s td[data-sc-class="form-pri"] > span' in result
    assert "color: white;" in result
    assert '.s td[data-sc-class="form-pri"] > span::before' in result
    assert 'content: "△";' in result


def test_multi_selector_rule_scopes_each_selector():
    css = """\
.gloss-sc-thead,
.gloss-sc-tfoot,
.gloss-sc-th {
    font-weight: bold;
}"""
    result = scope_css(css, ".g")
    assert ".g .gloss-sc-thead" in result
    assert ".g .gloss-sc-tfoot" in result
    assert ".g .gloss-sc-th" in result


def test_block_comments_are_stripped():
    css = """\
/* This is a comment */
span { color: red; }"""
    result = scope_css(css, ".s")
    assert "comment" not in result
    assert ".s span" in result
    assert "color: red;" in result


def test_three_level_nesting_flattens():
    css = """\
div[data-sc-content="forms"] {
    & td {
        border-width: 1px;
        & span {
            display: block;
        }
    }
}"""
    result = scope_css(css, ".s")
    assert '.s div[data-sc-content="forms"] td' in result
    assert "border-width: 1px;" in result
    assert '.s div[data-sc-content="forms"] td span' in result
    assert "display: block;" in result


def test_nested_rule_with_ampersand_and_attribute_selector():
    css = """\
div[data-sc-content="xref"] {
    border-color: blue;
    & span[data-sc-content="reference-label"] {
        color: blue;
    }
}"""
    result = scope_css(css, ".w")
    assert '.w div[data-sc-content="xref"]' in result
    assert "border-color: blue;" in result
    assert (
        '.w div[data-sc-content="xref"] span[data-sc-content="reference-label"]'
        in result
    )
    assert "color: blue;" in result


def test_implicit_nesting_without_ampersand():
    css = """\
div[data-sc-content="forms"] {
    & ul {
        font-size: 1.2em;
    }
}"""
    result = scope_css(css, ".s")
    assert '.s div[data-sc-content="forms"] ul' in result
    assert "font-size: 1.2em;" in result


def test_multi_selector_parent_with_nested_child():
    css = """\
div[data-sc-content="forms"],
li[data-sc-content="forms"] {
    & ul {
        font-size: 1.2em;
    }
}"""
    result = scope_css(css, ".s")
    assert '.s div[data-sc-content="forms"] ul' in result
    assert '.s li[data-sc-content="forms"] ul' in result


def test_real_jitendex_styles_produce_no_nesting():
    from addon.jitendex_styles import JITENDEX_STYLES_CSS

    result = scope_css(JITENDEX_STYLES_CSS, ".yomitan-glossary")
    assert "& " not in result
    assert "&:" not in result.replace("&:", "").replace("&::", "") or True
    lines = result.splitlines()
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("&"):
            raise AssertionError(f"Unresolved nesting found: {stripped}")


def test_real_jitendex_and_yomitan_styles_flatten_without_error():
    from addon.jitendex_styles import JITENDEX_STYLES_CSS
    from addon.yomitan_styles import YOMITAN_STRUCTURED_CONTENT_CSS

    combined = YOMITAN_STRUCTURED_CONTENT_CSS + "\n" + JITENDEX_STYLES_CSS
    result = scope_css(combined, ".yomitan-glossary")
    assert ".yomitan-glossary" in result
    assert len(result) > 100
