"""Client-side `kana_form` detector for a bookmark.

Returns True when the term is "usually written in kana alone", which drives
the `Word = reading` branch in the note builder and the kana-only branch in
the jpod101 URL.
"""

from addon import structured_content


def detect(bookmark):
    """Return True iff `bookmark` should be treated as a kana-form headword.

    Detection strategy (in order):
      1. Walk `glossary_raw` once via `structured_content.render`; if the
         Jitendex "uk" misc-info marker is encountered, the term is kana-form.
      2. Otherwise, fall back to `expression == reading` — kana-only
         headwords (e.g. テレビ, カメラ) where there are no kanji forms at
         all.

    Both inputs may be missing or None; in that case we return False.
    """
    glossary = bookmark.glossary_raw if bookmark is not None else None
    if glossary is not None:
        _, marker_seen = structured_content.render(glossary)
        if marker_seen:
            return True
    expression = getattr(bookmark, "expression", None)
    reading = getattr(bookmark, "reading", None)
    if expression and reading and expression == reading:
        return True
    return False
