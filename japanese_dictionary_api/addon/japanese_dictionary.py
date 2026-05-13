"""Add-on entry point.

Registers the Tools menu action, fetches bookmarks on click, opens the
import dialog, and runs the three-phase commit pipeline in the
background.
"""

from concurrent.futures import ThreadPoolExecutor, as_completed

from aqt import gui_hooks, mw
from aqt.operations import QueryOp
from aqt.qt import QAction
from aqt.utils import tooltip

from addon import api_client, audio, dialog, kana_form, note_builder

DEFAULT_CONFIG = {
    "deck": "Mining",
    "note_type": "Animecards",
    "field_mapping": {
        "Word": "Word",
        "Reading": "Reading",
        "Glossary": "Glossary",
        "Audio": "Audio",
        "Graph": "Graph",
    },
    "tag": "japanese_dictionary",
    "duplicate_field": "Word",
}


def log(message):
    print(f"[japanese-dictionary] {message}", flush=True)


def get_config():
    config = mw.addonManager.getConfig(__name__) or {}
    merged = dict(DEFAULT_CONFIG)
    merged.update(config)
    field_mapping = dict(DEFAULT_CONFIG["field_mapping"])
    field_mapping.update(config.get("field_mapping", {}))
    merged["field_mapping"] = field_mapping
    return merged


def on_menu_action_triggered():
    config = get_config()

    try:
        bookmarks = api_client.find_bookmarks_with_terms()
    except Exception as e:
        tooltip(f"Failed to fetch bookmarks: {e}", parent=mw)
        return

    if not bookmarks:
        tooltip("No Japanese dictionary bookmarks to import", parent=mw)
        return

    duplicate_sequences = _find_duplicates(bookmarks, config)

    def on_commit(imports, drops, deck_id, model_id):
        _start_commit(imports, drops, deck_id, model_id, config)

    dialog.show_import_dialog(bookmarks, duplicate_sequences, config, on_commit)


def _find_duplicates(bookmarks, config):
    deck = config["deck"]
    duplicate_field = config["duplicate_field"]
    duplicates = set()
    for bookmark in bookmarks:
        word = bookmark.reading if kana_form.detect(bookmark) else bookmark.expression
        if note_builder.is_duplicate(mw.col, deck, duplicate_field, word):
            duplicates.add(bookmark.sequence)
    return duplicates


def _start_commit(imports, drops, deck_id, model_id, config):
    model = mw.col.models.get(model_id)
    if model is None:
        tooltip("Selected note type no longer exists", parent=mw)
        return

    def background(col):
        return _run_commit(col, imports, drops, model, deck_id, config)

    def on_success(summary):
        tooltip(_format_summary(summary), parent=mw)

    def on_failure(error):
        log(f"import failed: {error}")
        tooltip(f"Import failed: {error}", parent=mw)

    QueryOp(parent=mw, op=background, success=on_success).with_progress(
        "Importing Japanese dictionary bookmarks..."
    ).failure(on_failure).run_in_background()


def _run_commit(col, imports, drops, model, deck_id, config):
    audio_files = _download_audio_files(col, imports)

    audio_field = config["field_mapping"]["Audio"]
    successful = set()
    imported_without_audio = 0
    failed = []
    for bookmark in imports:
        try:
            note = note_builder.build_note(col, model, deck_id, bookmark, config)
            filename = audio_files.get(bookmark.sequence)
            if filename:
                note[audio_field] = f"[sound:{filename}]"
            else:
                imported_without_audio += 1
            col.add_note(note, deck_id)
            successful.add(bookmark.sequence)
        except Exception as error:
            log(f"add_note failed for {bookmark.sequence}: {error}")
            failed.append(bookmark.sequence)

    delete_failures = []
    sequences_to_clear = list(successful) + [b.sequence for b in drops]
    for sequence in sequences_to_clear:
        try:
            api_client.delete_bookmark(sequence)
        except Exception as error:
            log(f"delete_bookmark failed for {sequence}: {error}")
            delete_failures.append(sequence)

    return {
        "imported": len(successful),
        "imported_without_audio": imported_without_audio,
        "dropped": len(drops),
        "failed": failed,
        "delete_failures": delete_failures,
    }


def _download_audio_files(col, imports):
    audio_files = {}
    if not imports:
        return audio_files

    with ThreadPoolExecutor() as executor:
        futures = {
            executor.submit(
                audio.download,
                audio.jpod101_url(b.expression, b.reading, kana_form.detect(b)),
            ): b
            for b in imports
        }
        for future in as_completed(futures):
            bookmark = futures[future]
            try:
                result = future.result()
            except Exception as error:
                log(f"audio download failed for {bookmark.sequence}: {error}")
                audio_files[bookmark.sequence] = None
                continue
            if result is None:
                audio_files[bookmark.sequence] = None
                continue
            data, content_type = result
            filename = audio.media_filename(
                bookmark.expression, bookmark.reading, content_type
            )
            col.media.write_data(filename, data)
            audio_files[bookmark.sequence] = filename
    return audio_files


def _format_summary(summary):
    parts = [f"Imported {summary['imported']}"]
    detail = []
    if summary["imported_without_audio"]:
        detail.append(f"{summary['imported_without_audio']} without audio")
    if summary["dropped"]:
        detail.append(f"{summary['dropped']} dropped")
    if summary["failed"]:
        detail.append(f"{len(summary['failed'])} failed")
    if summary["delete_failures"]:
        detail.append(f"{len(summary['delete_failures'])} delete failures")
    if detail:
        parts.append(f"({', '.join(detail)})")
    return " ".join(parts)


def register_menu_action():
    action = QAction("Import Japanese dictionary bookmarks", mw)
    action.triggered.connect(on_menu_action_triggered)
    mw.form.menuTools.addAction(action)


gui_hooks.main_window_did_init.append(register_menu_action)
