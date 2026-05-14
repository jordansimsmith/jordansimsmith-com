"""Bundled Yomitan `structured-content-style.json` (relevant subset).

Yomitan's `_structuredContentStyleApplier` (see
`tmp/yomitan/ext/js/templates/anki-template-renderer.js:43` and
`tmp/yomitan/ext/js/dom/css-style-applier.js:78`) inlines this stylesheet
onto each element via `applyClassStyles` before serialising to Anki. Our
addon takes the wrap-once `<style>` block route instead, so we ship the
same rules embedded in the field alongside the Jitendex bundle.

We bundle only the rules that fire on HTML our addon actually emits:
the external-link-icon hider (Jitendex external attribution links) and
the structured-content table chrome (Jitendex forms tables). The
`.gloss-image-*` family in upstream `structured-content-style.json`
is intentionally omitted — `_emit_image` in `structured_content.py`
renders image nodes as `<span class="gloss-image-placeholder">[image: …]
</span>` text fallbacks rather than the `.gloss-image-container` /
`.gloss-image-link` DOM Yomitan would build, so those rules have
nothing to attach to.

Source: `ext/data/structured-content-style.json` from Yomitan upstream
(`tmp/yomitan/ext/data/structured-content-style.json`), distributed under
GPL-3.0. Update by re-extracting the matching subset when Yomitan adds
or changes a relevant rule.
"""

YOMITAN_STRUCTURED_CONTENT_CSS = """\
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
