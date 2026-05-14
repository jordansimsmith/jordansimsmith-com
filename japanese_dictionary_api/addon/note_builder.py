"""Build an Anki Note in memory from a bookmark.

The Audio field is left blank here; the orchestration in `japanese_dictionary.py`
populates it (or leaves it empty when audio download fails) before calling
`mw.col.add_note`.
"""

from . import kana_form, pitch_graph, structured_content


def build_note(col, model, deck_id, bookmark, config):
    is_kana = kana_form.detect(bookmark)
    word = bookmark.reading if is_kana else bookmark.expression
    glossary_html, _ = structured_content.render_field(bookmark.glossary_raw)
    graph_svg = (
        pitch_graph.render(bookmark.reading, bookmark.pitch)
        if bookmark.pitch is not None
        else ""
    )

    fields = config["field_mapping"]
    note = col.new_note(model)
    note[fields["Word"]] = word
    note[fields["Reading"]] = bookmark.reading
    note[fields["Glossary"]] = glossary_html
    note[fields["Graph"]] = graph_svg
    note[fields["Audio"]] = ""
    note.tags = [config["tag"]]
    return note


def is_duplicate(col, deck_name, duplicate_field, value):
    if not value:
        return False
    escaped_deck = deck_name.replace('"', '\\"')
    escaped_value = value.replace('"', '\\"')
    query = f'deck:"{escaped_deck}" "{duplicate_field}:{escaped_value}"'
    return len(col.find_notes(query)) > 0
