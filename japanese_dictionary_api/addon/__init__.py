try:
    from . import japanese_dictionary  # noqa: F401
except ImportError:
    # aqt is only available inside Anki desktop; keep the package importable
    # under pytest so the addon's pure-function modules can be unit-tested.
    pass
