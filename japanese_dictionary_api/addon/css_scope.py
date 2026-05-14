"""Flatten and scope CSS for embedding in Anki card fields.

Replicates what Yomitan's `addScopeToCssLegacy` in
`tmp/yomitan/ext/js/core/utilities.js` does via the browser's
`CSSStyleSheet.replaceSync()` API: resolve CSS nesting (`&` syntax)
into flat selectors and prepend a scope selector to every rule.

Anki's embedded Chromium does not reliably parse CSS nesting inside
`<style>` blocks in card fields, so the output must be fully flat.
"""

import re


def scope_css(css, scope_selector):
    """Flatten nested CSS and prepend *scope_selector* to every rule.

    Handles the subset of CSS nesting used by Jitendex's `styles.css`:
      - Nested rule blocks (with or without `&`)
      - `&::before` / `&::after` pseudo-elements
      - Multi-selector rules (comma-separated)
      - Block comments (`/* ... */`)

    Does not handle `@media`, `@keyframes`, or other at-rules (none
    appear in Jitendex or the Yomitan structured-content stylesheet).
    """
    css = _strip_comments(css)
    rules = _parse_rules(css)
    flat = _flatten_rules(rules, [scope_selector])
    return "\n".join(f"{selectors_str} {{\n{body}\n}}" for selectors_str, body in flat)


def _strip_comments(css):
    return re.sub(r"/\*.*?\*/", "", css, flags=re.DOTALL)


def _parse_rules(css):
    """Parse CSS into a list of (selectors_string, body_or_children).

    Each entry is either:
      - (selectors_str, declarations_str)  for a leaf rule
      - (selectors_str, [children])        for a rule with nested blocks

    When a block has both own declarations and nested child rules (e.g.
    `li { padding: 0.25em; & ul { ... } }`), the own declarations are
    emitted as a leaf entry *before* the entry with nested children.
    """
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
            children = _parse_rules(nested_content)
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
    """Extract property declarations that appear before the first nested block."""
    brace = block_content.find("{")
    if brace == -1:
        return block_content.strip()
    before = block_content[:brace]
    last_semi = before.rfind(";")
    if last_semi == -1:
        return ""
    return before[: last_semi + 1].strip()


def _strip_leading_declarations(block_content):
    """Remove property declarations that appear before the first nested block."""
    brace = block_content.find("{")
    if brace == -1:
        return ""
    before = block_content[:brace]
    last_semi = before.rfind(";")
    if last_semi == -1:
        return block_content
    # find the start of the selector after the last semicolon
    rest = block_content[last_semi + 1 :]
    return rest


def _extract_block(css, open_brace):
    """Return (content_between_braces, index_after_close_brace)."""
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
    """Recursively flatten nested rules into (selectors_str, body) pairs."""
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
    """Resolve a (possibly comma-separated) selector against parent selectors.

    If a child selector contains `&`, the `&` is replaced with each parent
    selector. Otherwise the child is appended to the parent with a space
    (implicit descendant combinator).
    """
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
    """Normalise declaration indentation to 4 spaces."""
    lines = []
    for line in body.splitlines():
        stripped = line.strip()
        if stripped:
            lines.append(f"    {stripped}")
    return "\n".join(lines)
