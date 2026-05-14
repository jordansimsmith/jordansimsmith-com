"""Add-on entry point.

Registers the Tools menu action and orchestrates the import flow as a
chain of background `QueryOp`s so the Anki UI never blocks:

1. Fetch bookmarks + run duplicate detection in the background.
2. Open the import dialog on the main thread.
3. On commit, run the audio download + `add_note` phase in the background.
4. Show a confirmation dialog summarising the import on the main thread,
   so the user can review what was added before any bookmarks are wiped.
5. On confirm, run the bookmark-delete phase in the background.

Splitting the commit at step 4 means a user who notices something off
(e.g. wrong deck, dropped a row by mistake) can keep the queue intact and
re-run the importer instead of losing the source bookmarks.
"""

from concurrent.futures import ThreadPoolExecutor, as_completed

from aqt import mw
from aqt.operations import QueryOp
from aqt.qt import QAction
from aqt.utils import askUser, tooltip

from . import api_client, audio, dialog, kana_form, note_builder


def log(message):
    print(f"[japanese-dictionary] {message}", flush=True)


def get_config():
    return mw.addonManager.getConfig(__name__) or {}


def on_menu_action_triggered():
    config = get_config()

    def background(col):
        bookmarks = api_client.find_bookmarks_with_terms()
        duplicate_sequences = _find_duplicates(col, bookmarks, config)
        return bookmarks, duplicate_sequences

    def on_success(result):
        bookmarks, duplicate_sequences = result
        if not bookmarks:
            tooltip("No Japanese dictionary bookmarks to import", parent=mw)
            return

        def on_commit(imports, drops, deck_id, model_id):
            _start_import(imports, drops, deck_id, model_id, config)

        dialog.show_import_dialog(bookmarks, duplicate_sequences, config, on_commit)

    def on_failure(error):
        log(f"fetch failed: {error}")
        tooltip(f"Failed to fetch bookmarks: {error}", parent=mw)

    QueryOp(parent=mw, op=background, success=on_success).with_progress(
        "Fetching Japanese dictionary bookmarks..."
    ).failure(on_failure).run_in_background()


def _find_duplicates(col, bookmarks, config):
    deck = config["deck"]
    duplicate_field = config["duplicate_field"]
    duplicates = set()
    for bookmark in bookmarks:
        word = bookmark.reading if kana_form.detect(bookmark) else bookmark.expression
        if note_builder.is_duplicate(col, deck, duplicate_field, word):
            duplicates.add(bookmark.sequence)
    return duplicates


def _start_import(imports, drops, deck_id, model_id, config):
    model = mw.col.models.get(model_id)
    if model is None:
        tooltip("Selected note type no longer exists", parent=mw)
        return

    def background(col):
        return _run_import(col, imports, drops, model, deck_id, config)

    def on_success(summary):
        _confirm_and_delete(summary, config)

    def on_failure(error):
        log(f"import failed: {error}")
        tooltip(f"Import failed: {error}", parent=mw)

    QueryOp(parent=mw, op=background, success=on_success).with_progress(
        "Importing Japanese dictionary bookmarks..."
    ).failure(on_failure).run_in_background()


def _run_import(col, imports, drops, model, deck_id, config):
    audio_files = _download_audio_files(col, imports)

    audio_field = config["field_mapping"]["Audio"]
    successful = []
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
            successful.append(bookmark.sequence)
        except Exception as error:
            log(f"add_note failed for {bookmark.sequence}: {error}")
            failed.append(bookmark.sequence)

    return {
        "successful_sequences": successful,
        "drop_sequences": [b.sequence for b in drops],
        "imported_without_audio": imported_without_audio,
        "failed": failed,
    }


def _confirm_and_delete(summary, config):
    sequences_to_clear = summary["successful_sequences"] + summary["drop_sequences"]
    if not sequences_to_clear:
        tooltip(_format_summary(summary, delete_failures=[]), parent=mw)
        return

    if not askUser(_format_confirmation(summary), parent=mw):
        tooltip(
            f"{_format_summary(summary, delete_failures=[])}; "
            f"{len(sequences_to_clear)} bookmarks kept on server",
            parent=mw,
        )
        return

    def background(_col):
        return _delete_bookmarks(sequences_to_clear)

    def on_success(delete_failures):
        tooltip(_format_summary(summary, delete_failures=delete_failures), parent=mw)

    def on_failure(error):
        log(f"bookmark delete phase failed: {error}")
        tooltip(f"Bookmark delete failed: {error}", parent=mw)

    QueryOp(parent=mw, op=background, success=on_success).with_progress(
        "Clearing imported bookmarks on the server..."
    ).failure(on_failure).run_in_background()


def _delete_bookmarks(sequences):
    delete_failures = []
    for sequence in sequences:
        try:
            api_client.delete_bookmark(sequence)
        except Exception as error:
            log(f"delete_bookmark failed for {sequence}: {error}")
            delete_failures.append(sequence)
    return delete_failures


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


def _format_confirmation(summary):
    imported = len(summary["successful_sequences"])
    dropped = len(summary["drop_sequences"])
    failed = len(summary["failed"])
    no_audio = summary["imported_without_audio"]

    lines = [f"Imported {imported} note(s) into Anki."]
    if no_audio:
        lines.append(f"  - {no_audio} imported without audio")
    if dropped:
        lines.append(f"Dropping {dropped} bookmark(s) you unchecked.")
    if failed:
        lines.append(f"{failed} note(s) failed to add and will be retried next time.")
    lines.append("")
    lines.append(
        f"Delete {imported + dropped} bookmark(s) from the server now?\n"
        "Choose No to keep them queued so you can re-run the importer."
    )
    return "\n".join(lines)


def _format_summary(summary, delete_failures):
    parts = [f"Imported {len(summary['successful_sequences'])}"]
    detail = []
    if summary["imported_without_audio"]:
        detail.append(f"{summary['imported_without_audio']} without audio")
    if summary["drop_sequences"]:
        detail.append(f"{len(summary['drop_sequences'])} dropped")
    if summary["failed"]:
        detail.append(f"{len(summary['failed'])} failed")
    if delete_failures:
        detail.append(f"{len(delete_failures)} delete failures")
    if detail:
        parts.append(f"({', '.join(detail)})")
    return " ".join(parts)


def register_menu_action():
    action = QAction("Import Japanese dictionary bookmarks", mw)
    action.triggered.connect(on_menu_action_triggered)
    mw.form.menuTools.addAction(action)
