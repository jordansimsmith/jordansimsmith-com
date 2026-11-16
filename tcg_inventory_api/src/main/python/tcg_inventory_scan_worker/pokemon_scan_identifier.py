"""Scan identification for Pokémon."""

from collections.abc import Iterator

from .scan_identifier import ScanIdentifier
from .scan_repository import ScanSuggestion


class PokemonScanIdentifier(ScanIdentifier):
    game = "pokemon"
    _source = "tcgplayer"
    _pinned_version = 60

    def _filter_suggestions(
        self, records: list[dict], finish: str
    ) -> Iterator[ScanSuggestion]:
        for record in records:
            yield ScanSuggestion(record["id"], record["name"], float(record["score"]))
