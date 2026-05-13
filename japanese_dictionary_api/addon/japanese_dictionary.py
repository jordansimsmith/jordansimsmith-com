from aqt import gui_hooks, mw
from aqt.qt import QAction
from aqt.utils import tooltip


def on_menu_action_triggered():
    tooltip("Not yet implemented", parent=mw)


def register_menu_action():
    action = QAction("Import Japanese dictionary bookmarks", mw)
    action.triggered.connect(on_menu_action_triggered)
    mw.form.menuTools.addAction(action)


gui_hooks.main_window_did_init.append(register_menu_action)
