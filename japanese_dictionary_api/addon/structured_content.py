"""Python port of Yomitan's structured-content-generator.js.

Tag dispatch mirrors `_createStructuredContentGenericElement` in
`tmp/yomitan/ext/js/display/structured-content-generator.js`. Class names
(`gloss-sc-<tag>`) and the inline-style property whitelist are copied from
Yomitan so the emitted HTML renders correctly inside the user's existing
Animecards card template.

`render` builds the structured-content tree; `render_field` then runs
`css_inline.inline_styles` over it to apply the bundled Yomitan
`structured-content-style.json` and Jitendex `styles.css` rules as
per-element `style` attributes, and strips the `gloss-sc-*` class
attributes — matching what Yomitan's `CssStyleApplier.applyClassStyles`
and `_normalizeHtml` do before serialising to Anki. Styles are inlined
rather than shipped in a `<style>` block because the Animecards card
template's cycling script replaces `#glossary-field` innerHTML with
individual `<li>` contents, destroying any `<style>` block but
preserving inline styles on elements within each `<li>`.

Unknown / untagged elements (notably Jitendex's top-level
`{"type": "structured-content", "content": [...]}` wrapper, which has no
`tag` key) are handled as transparent passthroughs — we recurse into the
element's `content` field. Yomitan's switch-default returns null and drops
the element; we diverge here because the addon receives raw Jitendex
`glossary_raw` arrays that always carry the wrapper at the top.
"""

import xml.etree.ElementTree as ET

from .css_inline import inline_styles

SPA_BASE_URL = "https://japanese-dictionary.jordansimsmith.com/search"
GLOSSARY_WRAPPER_CLASS = "yomitan-glossary"

_NO_STYLE_TAGS = {"br", "ruby", "rt", "rp"}
_TABLE_GROUP_TAGS = {"thead", "tbody", "tfoot", "tr"}
_TABLE_CELL_TAGS = {"th", "td"}
_SIMPLE_STYLED_TAGS = {"div", "span", "ol", "ul", "li", "details", "summary"}

# string-valued style fields from Yomitan's `_setStructuredContentElementStyle`:
# (camelCase JSON key, kebab-case CSS property name).
_STYLE_STRING_FIELDS = [
    ("fontStyle", "font-style"),
    ("fontWeight", "font-weight"),
    ("fontSize", "font-size"),
    ("color", "color"),
    ("background", "background"),
    ("backgroundColor", "background-color"),
    ("verticalAlign", "vertical-align"),
    ("textAlign", "text-align"),
    ("textEmphasis", "text-emphasis"),
    ("textShadow", "text-shadow"),
    ("textDecorationStyle", "text-decoration-style"),
    ("textDecorationColor", "text-decoration-color"),
    ("borderColor", "border-color"),
    ("borderStyle", "border-style"),
    ("borderRadius", "border-radius"),
    ("borderWidth", "border-width"),
    ("clipPath", "clip-path"),
    ("margin", "margin"),
    ("padding", "padding"),
    ("paddingTop", "padding-top"),
    ("paddingLeft", "padding-left"),
    ("paddingRight", "padding-right"),
    ("paddingBottom", "padding-bottom"),
    ("wordBreak", "word-break"),
    ("whiteSpace", "white-space"),
    ("cursor", "cursor"),
    ("listStyleType", "list-style-type"),
]

# margin fields accepting either a number (interpreted as em) or a string.
_STYLE_NUMBER_FIELDS = [
    ("marginTop", "margin-top"),
    ("marginLeft", "margin-left"),
    ("marginRight", "margin-right"),
    ("marginBottom", "margin-bottom"),
]


def render(content):
    """Render a Yomitan structured-content tree to an HTML string.

    Returns `(html, kana_form_marker_seen)`. The HTML is a single
    `<span class="structured-content">` element — no glossary wrapper, no
    embedded stylesheet. Use `render_field` for the full Anki field
    payload. The boolean is True iff a Jitendex "word usually written
    using kana alone" misc-info span was encountered during the walk (see
    `_check_kana_marker` for the selector).
    """
    state = {"kana_form_marker_seen": False}
    root = ET.Element("span")
    root.set("class", "structured-content")
    _walk(root, content, state)
    return ET.tostring(root, encoding="unicode"), state["kana_form_marker_seen"]


def render_field(content):
    """Render a Yomitan structured-content tree as an Anki Glossary field.

    Builds the structured-content tree, inlines bundled CSS rules as
    `style` attributes on each matching element, strips `class`
    attributes, and wraps the result in
    `<div class="yomitan-glossary" style="text-align: left;">`.

    Returns `(field_html, kana_form_marker_seen)`.
    """
    state = {"kana_form_marker_seen": False}
    root = ET.Element("span")
    root.set("class", "structured-content")
    _walk(root, content, state)
    inline_styles(root)
    html = ET.tostring(root, encoding="unicode", short_empty_elements=False)
    field_html = (
        f'<div class="{GLOSSARY_WRAPPER_CLASS}" style="text-align: left;">{html}</div>'
    )
    return field_html, state["kana_form_marker_seen"]


def _walk(parent, node, state):
    if node is None:
        return
    if isinstance(node, str):
        if node:
            _append_text(parent, node)
        return
    if isinstance(node, list):
        for child in node:
            _walk(parent, child, state)
        return
    if not isinstance(node, dict):
        return
    _check_kana_marker(node, state)
    _emit_element(parent, node, state)


def _check_kana_marker(node, state):
    """Detect Jitendex's "usually written using kana alone" misc-info marker.

    Sample element from a `term.jsonl` row for the kana-form word `橡`:

        {"tag": "span",
         "title": "word usually written using kana alone",
         "data": {"class": "tag", "code": "uk", "content": "misc-info"},
         "content": "kana"}
    """
    if state["kana_form_marker_seen"]:
        return
    if node.get("tag") != "span":
        return
    data = node.get("data")
    if not isinstance(data, dict):
        return
    if data.get("code") == "uk" and data.get("content") == "misc-info":
        state["kana_form_marker_seen"] = True


def _emit_element(parent, node, state):
    tag = node.get("tag")
    if tag == "br":
        ET.SubElement(parent, "br").set("class", "gloss-sc-br")
    elif tag in _NO_STYLE_TAGS:
        _emit_basic(parent, tag, node, state, has_style=False)
    elif tag == "table":
        _emit_table(parent, node, state)
    elif tag in _TABLE_GROUP_TAGS:
        _emit_basic(parent, tag, node, state, has_style=False)
    elif tag in _TABLE_CELL_TAGS:
        _emit_table_cell(parent, tag, node, state)
    elif tag in _SIMPLE_STYLED_TAGS:
        _emit_basic(parent, tag, node, state, has_style=True)
    elif tag == "img":
        _emit_image(parent, node)
    elif tag == "a":
        _emit_link(parent, node, state)
    else:
        _walk(parent, node.get("content"), state)


def _emit_basic(parent, tag, node, state, has_style):
    el = ET.SubElement(parent, tag)
    el.set("class", f"gloss-sc-{tag}")
    _apply_dataset(el, node)
    if has_style:
        style = node.get("style")
        if isinstance(style, dict):
            _apply_style(el, style)
        title = node.get("title")
        if isinstance(title, str):
            el.set("title", title)
        if node.get("open") is True:
            el.set("open", "")
    lang = node.get("lang")
    if isinstance(lang, str):
        el.set("lang", lang)
    _walk(el, node.get("content"), state)


def _emit_table(parent, node, state):
    container = ET.SubElement(parent, "div")
    container.set("class", "gloss-sc-table-container")
    table = ET.SubElement(container, "table")
    table.set("class", "gloss-sc-table")
    _apply_dataset(table, node)
    _walk(table, node.get("content"), state)


def _emit_table_cell(parent, tag, node, state):
    cell = ET.SubElement(parent, tag)
    cell.set("class", f"gloss-sc-{tag}")
    _apply_dataset(cell, node)
    col_span = node.get("colSpan")
    if isinstance(col_span, int) and not isinstance(col_span, bool):
        cell.set("colspan", str(col_span))
    row_span = node.get("rowSpan")
    if isinstance(row_span, int) and not isinstance(row_span, bool):
        cell.set("rowspan", str(row_span))
    style = node.get("style")
    if isinstance(style, dict):
        _apply_style(cell, style)
    title = node.get("title")
    if isinstance(title, str):
        cell.set("title", title)
    _walk(cell, node.get("content"), state)


def _emit_image(parent, node):
    placeholder = ET.SubElement(parent, "span")
    placeholder.set("class", "gloss-image-placeholder")
    placeholder.text = f"[image: {_image_description(node)}]"


def _image_description(node):
    description = node.get("description")
    if isinstance(description, str) and description:
        return description
    title = node.get("title")
    if isinstance(title, str) and title:
        return title
    path = node.get("path")
    if isinstance(path, str) and path:
        return path
    return "image"


def _emit_link(parent, node, state):
    raw_href = node.get("href")
    href = raw_href if isinstance(raw_href, str) else ""
    is_internal = href.startswith("?")
    anchor = ET.SubElement(parent, "a")
    anchor.set("class", "gloss-link")
    anchor.set("data-external", "false" if is_internal else "true")
    if is_internal:
        anchor.set("href", f"{SPA_BASE_URL}{href}")
    else:
        anchor.set("href", href)
        anchor.set("target", "_blank")
        anchor.set("rel", "noopener noreferrer")
    text = ET.SubElement(anchor, "span")
    text.set("class", "gloss-link-text")
    _walk(text, node.get("content"), state)
    if not is_internal:
        icon = ET.SubElement(anchor, "span")
        icon.set("class", "gloss-link-external-icon icon")
        icon.set("data-icon", "external-link")


def _apply_dataset(el, node):
    data = node.get("data")
    if not isinstance(data, dict):
        return
    for key, value in data.items():
        if not key:
            continue
        el.set(f"data-sc-{_camel_to_kebab(key)}", str(value))


def _camel_to_kebab(s):
    out = []
    for i, ch in enumerate(s):
        if i > 0 and ch.isupper():
            out.append("-")
        out.append(ch.lower())
    return "".join(out)


def _apply_style(el, style):
    pieces = []
    for src, dst in _STYLE_STRING_FIELDS:
        value = style.get(src)
        if isinstance(value, str):
            pieces.append(f"{dst}:{value};")
    for src, dst in _STYLE_NUMBER_FIELDS:
        value = style.get(src)
        if isinstance(value, bool):
            continue
        if isinstance(value, (int, float)):
            pieces.append(f"{dst}:{value}em;")
        elif isinstance(value, str):
            pieces.append(f"{dst}:{value};")
    text_decoration = style.get("textDecorationLine")
    if isinstance(text_decoration, str):
        pieces.append(f"text-decoration:{text_decoration};")
    elif isinstance(text_decoration, list):
        pieces.append(f"text-decoration:{' '.join(text_decoration)};")
    if pieces:
        el.set("style", "".join(pieces))


def _append_text(parent, text):
    children = list(parent)
    if children:
        last = children[-1]
        last.tail = (last.tail or "") + text
    else:
        parent.text = (parent.text or "") + text
