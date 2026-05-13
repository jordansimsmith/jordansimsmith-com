"""Modal import dialog for the addon.

The dialog displays one row per bookmark, with deck and note-type pickers
at the top and Cancel / commit buttons at the bottom. On commit, the
provided `on_commit(imports, drops, deck_id, model_id)` callback is fired
with the user's review state and the dialog closes; on cancel, the dialog
closes silently.
"""

from aqt import mw
from aqt.qt import (
    QComboBox,
    QDialog,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    Qt,
    QVBoxLayout,
)


def show_import_dialog(bookmarks, duplicate_sequences, config, on_commit):
    dialog = QDialog(mw)
    dialog.setWindowTitle("Import Japanese dictionary bookmarks")
    layout = QVBoxLayout(dialog)

    total = len(bookmarks)
    dupes = len(duplicate_sequences)
    layout.addWidget(QLabel(f"{total} bookmarks ({dupes} duplicates)"))

    pickers = QHBoxLayout()
    deck_combo = QComboBox()
    for entry in mw.col.decks.all_names_and_ids():
        deck_combo.addItem(entry.name, entry.id)
    _select_named(deck_combo, config.get("deck"))
    model_combo = QComboBox()
    for entry in mw.col.models.all_names_and_ids():
        model_combo.addItem(entry.name, entry.id)
    _select_named(model_combo, config.get("note_type"))
    pickers.addWidget(QLabel("Deck"))
    pickers.addWidget(deck_combo)
    pickers.addWidget(QLabel("Note type"))
    pickers.addWidget(model_combo)
    layout.addLayout(pickers)

    list_widget = QListWidget()
    for bookmark in bookmarks:
        is_dupe = bookmark.sequence in duplicate_sequences
        suffix = " [duplicate]" if is_dupe else ""
        freq = bookmark.frequency_rank if bookmark.frequency_rank is not None else "?"
        label = (
            f"{bookmark.expression or bookmark.reading}  "
            f"{bookmark.reading}  #{freq}{suffix}"
        )
        item = QListWidgetItem(label)
        item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
        item.setCheckState(
            Qt.CheckState.Unchecked if is_dupe else Qt.CheckState.Checked
        )
        item.setData(Qt.ItemDataRole.UserRole, bookmark)
        list_widget.addItem(item)
    layout.addWidget(list_widget)

    footer = QLabel("")
    footer.setWordWrap(True)
    layout.addWidget(footer)

    buttons = QHBoxLayout()
    cancel_button = QPushButton("Cancel")
    commit_button = QPushButton("Import")
    buttons.addStretch(1)
    buttons.addWidget(cancel_button)
    buttons.addWidget(commit_button)
    layout.addLayout(buttons)

    def refresh_footer():
        imports, drops = _count_checked(list_widget)
        commit_button.setText(
            f"Import {imports}" if drops == 0 else f"Import {imports} (drop {drops})"
        )
        if drops > 0:
            footer.setText(
                f"Importing {imports} will also drop {drops} unchecked "
                "bookmarks. Cancel to keep all bookmarks."
            )
            footer.setVisible(True)
        else:
            footer.setVisible(False)

    list_widget.itemChanged.connect(lambda _item: refresh_footer())
    refresh_footer()

    def handle_commit():
        imports, drops = _collect_decisions(list_widget)
        deck_id = deck_combo.currentData()
        model_id = model_combo.currentData()
        dialog.accept()
        on_commit(imports, drops, deck_id, model_id)

    commit_button.clicked.connect(handle_commit)
    cancel_button.clicked.connect(dialog.reject)

    dialog.resize(700, 500)
    dialog.exec()


def _select_named(combo, name):
    if not name:
        return
    for idx in range(combo.count()):
        if combo.itemText(idx) == name:
            combo.setCurrentIndex(idx)
            return


def _count_checked(list_widget):
    imports = 0
    drops = 0
    for idx in range(list_widget.count()):
        if list_widget.item(idx).checkState() == Qt.CheckState.Checked:
            imports += 1
        else:
            drops += 1
    return imports, drops


def _collect_decisions(list_widget):
    imports = []
    drops = []
    for idx in range(list_widget.count()):
        item = list_widget.item(idx)
        bookmark = item.data(Qt.ItemDataRole.UserRole)
        if item.checkState() == Qt.CheckState.Checked:
            imports.append(bookmark)
        else:
            drops.append(bookmark)
    return imports, drops
