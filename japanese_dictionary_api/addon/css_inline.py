"""Inline CSS rules onto ElementTree elements by matching selectors.

Replicates the effect of Yomitan's `CssStyleApplier.applyClassStyles`
combined with Jitendex's `styles.css`: walk an ET tree, match each
element against a pre-parsed rule list, and merge matching declarations
into the element's `style` attribute. After inlining, strip the
`class` attribute (matching Yomitan's `_normalizeHtml`).

Only the selector subset used by Jitendex and the Yomitan
structured-content stylesheet is supported: `tag`, `tag[attr="val"]`,
`.class`, bare `[attr="val"]`, descendant combinators, `>` child
combinators, `+` adjacent-sibling combinators, and `:first-child`.
`::before` / `::after` pseudo-elements are skipped (cannot be inlined).
"""

import re
import xml.etree.ElementTree as ET

from .css_scope import scope_css
from .jitendex_styles import JITENDEX_STYLES_CSS

_YOMITAN_CSS = """\
.gloss-link-external-icon {
    display: none;
}

.gloss-sc-table-container {
    display: block;
}

.gloss-sc-table {
    table-layout: auto;
    border-collapse: collapse;
}

.gloss-sc-thead,
.gloss-sc-tfoot,
.gloss-sc-th {
    font-weight: bold;
}

.gloss-sc-th,
.gloss-sc-td {
    border-style: solid;
    padding: 0.25em;
    vertical-align: top;
    border-width: 1px;
    border-color: currentColor;
}
"""

_FLAT_CSS = scope_css(_YOMITAN_CSS + "\n" + JITENDEX_STYLES_CSS, ".structured-content")

_PARSED_RULES = None


def _get_rules():
    global _PARSED_RULES
    if _PARSED_RULES is None:
        _PARSED_RULES = _parse_flat_css(_FLAT_CSS)
    return _PARSED_RULES


def inline_styles(root):
    """Apply CSS rules as inline styles on *root* and its descendants.

    Modifies the tree in place. Strips the `class` attribute from all
    elements after inlining (matching Yomitan's `_normalizeHtml`).
    """
    rules = _get_rules()
    index = _build_index(root)
    for selector_parts, declarations in rules:
        for el in _select(selector_parts, root, index):
            _merge_style(el, declarations)
    _strip_classes(root)


def _build_index(root):
    """Build parent/sibling lookups for the tree."""
    parent_map = {}
    children_map = {}
    for parent in root.iter():
        kids = list(parent)
        children_map[parent] = kids
        for child in kids:
            parent_map[child] = parent
    return {"parent": parent_map, "children": children_map}


def _strip_classes(root):
    for el in root.iter():
        if "class" in el.attrib:
            del el.attrib["class"]


def _merge_style(el, declarations):
    existing = el.get("style", "")
    if existing and not existing.endswith(";"):
        existing += ";"
    el.set("style", existing + declarations)


# --- CSS parsing ---

_RULE_RE = re.compile(r"([^{}]+)\{([^{}]+)\}")


def _parse_flat_css(css):
    """Parse flat (no nesting) CSS into [(selector_parts, declarations)]."""
    rules = []
    for match in _RULE_RE.finditer(css):
        selectors_str = match.group(1).strip()
        declarations = match.group(2).strip()
        if not declarations:
            continue
        # normalise declarations to a single line
        decl = " ".join(declarations.split())
        for selector in selectors_str.split(","):
            selector = selector.strip()
            if "::" in selector:
                continue
            parts = _parse_selector(selector)
            if parts is not None:
                rules.append((parts, decl))
    return rules


_SIMPLE_SEL_RE = re.compile(
    r"^([a-z][a-z0-9-]*)?"  # optional tag
    r"(\.[a-z][a-z0-9-]*)?"  # optional .class
    r'(\[[a-z][a-z0-9-]*="[^"]*"\])*'  # optional [attr="val"]
    r"(:first-child)?$",  # optional :first-child
    re.IGNORECASE,
)

_ATTR_RE = re.compile(r'\[([a-z][a-z0-9-]*)="([^"]*)"\]', re.IGNORECASE)


def _parse_selector(selector):
    """Parse a single selector into a list of (combinator, simple_sel) tuples.

    Returns None if the selector cannot be handled.
    """
    # remove the .structured-content scope prefix
    selector = re.sub(r"^\.structured-content\s*", "", selector).strip()
    if not selector:
        return None

    # tokenise by combinators, preserving > and +
    tokens = re.split(r"\s*(>|\+)\s*|\s+", selector)
    tokens = [t for t in tokens if t]

    parts = []
    combinator = " "
    for token in tokens:
        if token in (">", "+"):
            combinator = token
            continue
        parsed = _parse_simple_selector(token)
        if parsed is None:
            return None
        parts.append((combinator, parsed))
        combinator = " "
    return parts


def _parse_simple_selector(sel):
    """Parse a simple selector into a dict of matching criteria."""
    m = _SIMPLE_SEL_RE.match(sel)
    if not m:
        return None
    result = {}
    if m.group(1):
        result["tag"] = m.group(1)
    if m.group(2):
        result["class"] = m.group(2)[1:]  # strip leading dot
    if m.group(3):
        result["attrs"] = _ATTR_RE.findall(m.group(3))
    if m.group(4):
        result["first_child"] = True
    return result


# --- Selector matching ---


def _select(selector_parts, root, index):
    """Yield elements matching the parsed selector."""
    if not selector_parts:
        return
    # start with the first part (always descendant of root)
    _, first_simple = selector_parts[0]
    candidates = [el for el in root.iter() if _matches_simple(el, first_simple, index)]
    for combinator, simple in selector_parts[1:]:
        next_candidates = []
        for el in candidates:
            next_candidates.extend(_follow(el, combinator, simple, root, index))
        candidates = next_candidates
    yield from candidates


def _follow(context, combinator, simple, root, index):
    """From a matched context element, find elements matching the next part."""
    if combinator == " ":
        # descendant
        return [
            el
            for el in context.iter()
            if el is not context and _matches_simple(el, simple, index)
        ]
    elif combinator == ">":
        # direct child
        children = index["children"].get(context, [])
        return [el for el in children if _matches_simple(el, simple, index)]
    elif combinator == "+":
        # adjacent sibling
        parent = index["parent"].get(context)
        if parent is None:
            return []
        siblings = index["children"].get(parent, [])
        idx = None
        for i, s in enumerate(siblings):
            if s is context:
                idx = i
                break
        if idx is not None and idx + 1 < len(siblings):
            nxt = siblings[idx + 1]
            if _matches_simple(nxt, simple, index):
                return [nxt]
        return []
    return []


def _matches_simple(el, simple, index):
    """Check if an element matches a simple selector dict."""
    if "tag" in simple and el.tag != simple["tag"]:
        return False
    if "class" in simple:
        el_classes = (el.get("class") or "").split()
        if simple["class"] not in el_classes:
            return False
    if "attrs" in simple:
        for attr, val in simple["attrs"]:
            if el.get(attr) != val:
                return False
    if simple.get("first_child"):
        parent = index["parent"].get(el)
        if parent is not None:
            children = index["children"].get(parent, [])
            if not children or children[0] is not el:
                return False
    return True
