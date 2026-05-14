"""Addon bootstrap: register the Tools menu action when running in Anki.

Isolating the aqt seam in `init()` keeps the package importable outside
Anki (e.g. under pytest), avoiding the need for a top-level
`except ImportError` that would mask unrelated import failures in
production code paths.
"""


def init():
    try:
        from aqt import gui_hooks
    except ImportError:
        return

    from . import anki_backup

    gui_hooks.main_window_did_init.append(anki_backup.register_menu_action)
