"""Scan identification for Magic: The Gathering."""

from collections.abc import Iterator

from .scan_identifier import ScanIdentifier
from .scan_repository import ScanSuggestion


class MagicTheGatheringScanIdentifier(ScanIdentifier):
    game = "mtg"
    _catalog_game = "mtg"
    _source = "scryfall"
    _pinned_version = 40
    _FINISHES = {"normal": "nonfoil", "foil": "foil", "etched": "etched"}

    def _filter_suggestions(
        self, records: list[dict], finish: str
    ) -> Iterator[ScanSuggestion]:
        required_finish = self._FINISHES[finish]
        for record in records:
            identifiers = record.get("identifiers") or {}
            external_id = identifiers.get("scryfall_card") or record.get("id")
            metadata = record.get("metadata") or {}
            if metadata.get("lang") != "en":
                continue
            if required_finish not in set(record.get("finishes") or ()):
                continue
            name = record.get("name")
            score = record.get("score")
            if not isinstance(external_id, str) or not external_id.strip():
                continue
            if not isinstance(name, str) or not name:
                continue
            if not isinstance(score, (float, int)) or isinstance(score, bool):
                continue
            yield ScanSuggestion(external_id, name, float(score))
