"""Flatten, scope, and inline CSS rules onto ElementTree elements.

Combines two steps that Yomitan performs separately:

1. **Flatten & scope** (Yomitan's `addScopeToCssLegacy` in
   `tmp/yomitan/ext/js/core/utilities.js`): resolve CSS `&` nesting
   into flat selectors and prepend a scope selector to every rule.

2. **Inline** (Yomitan's `CssStyleApplier.applyClassStyles` in
   `tmp/yomitan/ext/js/dom/css-style-applier.js`): walk an ET tree,
   match each element against the flat rule list, and merge matching
   declarations into the element's `style` attribute. Strip the `class`
   attribute afterward (matching Yomitan's `_normalizeHtml`).

Styles are inlined rather than shipped in a `<style>` block because
the Animecards card template's cycling script replaces
`#glossary-field` innerHTML with individual `<li>` contents, destroying
any `<style>` block but preserving inline styles on elements within
each `<li>`.

Only the selector subset used by Jitendex and the Yomitan
structured-content stylesheet is supported: `tag`, `tag[attr="val"]`,
`[attr]` (presence), `.class`, descendant combinators, `>` child
combinators, `+` adjacent-sibling combinators, and `:first-child`.
`::before` / `::after` pseudo-elements are skipped (cannot be inlined).
"""

import re

from .jitendex_styles import JITENDEX_STYLES_CSS

# ── Yomitan structured-content-style.json (relevant subset) ──────────

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

# ── Flatten & scope ──────────────────────────────────────────────────


def _scope_css(css, scope_selector):
    css = _strip_comments(css)
    rules = _parse_css_blocks(css)
    flat = _flatten_rules(rules, [scope_selector])
    return "\n".join(f"{sel} {{\n{body}\n}}" for sel, body in flat)


def _strip_comments(css):
    return re.sub(r"/\*.*?\*/", "", css, flags=re.DOTALL)


def _parse_css_blocks(css):
    rules = []
    i = 0
    n = len(css)
    while i < n:
        while i < n and css[i] in " \t\r\n":
            i += 1
        if i >= n:
            break

        brace = css.find("{", i)
        if brace == -1:
            break

        selector = css[i:brace].strip()
        if not selector:
            i = brace + 1
            continue

        block_content, end = _extract_block(css, brace)
        i = end

        if "{" in block_content:
            own_decls = _extract_leading_declarations(block_content)
            nested_content = _strip_leading_declarations(block_content)
            children = _parse_css_blocks(nested_content)
            if own_decls:
                rules.append((selector, own_decls))
            if children:
                rules.append((selector, children))
        else:
            body = block_content.strip()
            if body:
                rules.append((selector, body))
    return rules


def _extract_leading_declarations(block_content):
    brace = block_content.find("{")
    if brace == -1:
        return block_content.strip()
    before = block_content[:brace]
    last_semi = before.rfind(";")
    if last_semi == -1:
        return ""
    return before[: last_semi + 1].strip()


def _strip_leading_declarations(block_content):
    brace = block_content.find("{")
    if brace == -1:
        return ""
    before = block_content[:brace]
    last_semi = before.rfind(";")
    if last_semi == -1:
        return block_content
    return block_content[last_semi + 1 :]


def _extract_block(css, open_brace):
    depth = 1
    i = open_brace + 1
    n = len(css)
    while i < n and depth > 0:
        if css[i] == "{":
            depth += 1
        elif css[i] == "}":
            depth -= 1
        i += 1
    return css[open_brace + 1 : i - 1], i


def _flatten_rules(rules, parent_selectors):
    flat = []
    for selector_str, body in rules:
        resolved = _resolve_selectors(selector_str, parent_selectors)
        if isinstance(body, list):
            flat.extend(_flatten_rules(body, resolved))
        else:
            if body:
                joined = ",\n".join(resolved)
                flat.append((joined, _indent_body(body)))
    return flat


def _resolve_selectors(selector_str, parent_selectors):
    child_selectors = [s.strip() for s in selector_str.split(",")]
    resolved = []
    for parent in parent_selectors:
        for child in child_selectors:
            if "&" in child:
                resolved.append(child.replace("&", parent))
            else:
                resolved.append(f"{parent} {child}")
    return resolved


def _indent_body(body):
    lines = []
    for line in body.splitlines():
        stripped = line.strip()
        if stripped:
            lines.append(f"    {stripped}")
    return "\n".join(lines)


# ── Pre-compute flat rules at module load ────────────────────────────

_FLAT_CSS = _scope_css(_YOMITAN_CSS + "\n" + JITENDEX_STYLES_CSS, ".structured-content")

_PARSED_RULES = None


def _get_rules():
    global _PARSED_RULES
    if _PARSED_RULES is None:
        _PARSED_RULES = _parse_flat_css(_FLAT_CSS)
    return _PARSED_RULES


# ── Public API ───────────────────────────────────────────────────────


def inline_styles(root):
    """Apply CSS rules as inline styles on *root* and its descendants.

    Rules are applied in specificity order (least-specific first) so that
    more-specific selectors override less-specific ones via last-write-wins
    on the concatenated `style` attribute. Specificity is approximated by
    the number of simple-selector parts in the parsed selector chain.

    Modifies the tree in place. Strips the `class` attribute from all
    elements after inlining (matching Yomitan's `_normalizeHtml`).
    """
    rules = _get_rules()
    rules_sorted = sorted(rules, key=lambda r: len(r[0]))
    index = _build_index(root)
    for selector_parts, declarations in rules_sorted:
        for el in _select(selector_parts, root, index):
            _merge_style(el, declarations)
    _strip_classes(root)


# ── DOM helpers ──────────────────────────────────────────────────────


def _build_index(root):
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


# ── Flat CSS parsing ────────────────────────────────────────────────


def _parse_flat_css(css):
    rules = []
    for selectors_str, body in _parse_css_blocks(css):
        if isinstance(body, list):
            continue
        declarations = body.strip()
        if not declarations:
            continue
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
    r"^([a-z][a-z0-9-]*)?"
    r"(\.[a-z][a-z0-9-]*)?"
    r'(\[[a-z][a-z0-9-]*(?:="[^"]*")?\])*'
    r"(:first-child)?$",
    re.IGNORECASE,
)

_ATTR_RE = re.compile(r'\[([a-z][a-z0-9-]*)(?:="([^"]*)")?\]', re.IGNORECASE)


def _parse_selector(selector):
    selector = re.sub(r"^\.structured-content\s*", "", selector).strip()
    if not selector:
        return None

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
    m = _SIMPLE_SEL_RE.match(sel)
    if not m:
        return None
    result = {}
    if m.group(1):
        result["tag"] = m.group(1)
    if m.group(2):
        result["class"] = m.group(2)[1:]
    if m.group(3):
        attrs = []
        for attr_name, attr_val in _ATTR_RE.findall(m.group(3)):
            attrs.append((attr_name, attr_val if attr_val else None))
        result["attrs"] = attrs
    if m.group(4):
        result["first_child"] = True
    return result


# ── Selector matching ───────────────────────────────────────────────


def _select(selector_parts, root, index):
    if not selector_parts:
        return
    _, first_simple = selector_parts[0]
    candidates = [el for el in root.iter() if _matches_simple(el, first_simple, index)]
    for combinator, simple in selector_parts[1:]:
        next_candidates = []
        for el in candidates:
            next_candidates.extend(_follow(el, combinator, simple, root, index))
        candidates = next_candidates
    yield from candidates


def _follow(context, combinator, simple, root, index):
    if combinator == " ":
        return [
            el
            for el in context.iter()
            if el is not context and _matches_simple(el, simple, index)
        ]
    elif combinator == ">":
        children = index["children"].get(context, [])
        return [el for el in children if _matches_simple(el, simple, index)]
    elif combinator == "+":
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
    if "tag" in simple and el.tag != simple["tag"]:
        return False
    if "class" in simple:
        el_classes = (el.get("class") or "").split()
        if simple["class"] not in el_classes:
            return False
    if "attrs" in simple:
        for attr, val in simple["attrs"]:
            if val is None:
                if el.get(attr) is None:
                    return False
            else:
                if el.get(attr) != val:
                    return False
    if simple.get("first_child"):
        parent = index["parent"].get(el)
        if parent is not None:
            children = index["children"].get(parent, [])
            if not children or children[0] is not el:
                return False
    return True
