"""Shared CollectorVision scan identification."""

import os
from abc import ABC, abstractmethod
from collections.abc import Iterator
from typing import Self

import numpy as np
from collector_vision import Catalog, CatalogV2, NeuralCornerDetector, rotate_card_180

from .scan_repository import ScanSuggestion

CACHE_ENV = "COLLECTORVISION_CACHE"
SEARCH_TOP_K = 25
MAX_SUGGESTIONS = 5


class ScanIdentifier(ABC):
    game: str
    _source: str
    _pinned_version: int

    def __init__(self, catalog: CatalogV2, detector: NeuralCornerDetector):
        self._catalog = catalog
        self._detector = detector

    @classmethod
    def load(cls, detector: NeuralCornerDetector) -> Self:
        catalog = Catalog.load(
            cls.game,
            source=cls._source,
            family="milo1",
            cache_dir=os.environ[CACHE_ENV],
            offline=True,
            version=cls._pinned_version,
        )
        _ = catalog.embedder
        return cls(catalog, detector)

    @property
    def catalog_version(self) -> int:
        return self._catalog.version

    def find_suggestions(
        self, image_bgr: np.ndarray, finish: str
    ) -> list[ScanSuggestion]:
        detection = self._detector.detect(image_bgr)
        if not detection.card_present:
            return []

        card = detection.dewarp(image_bgr)
        searches = [
            self._catalog.search_records(
                self._catalog.embedder.embed(orientation), top_k=SEARCH_TOP_K
            )
            for orientation in (card, rotate_card_180(card))
        ]
        records = max(
            searches,
            key=lambda results: results[0]["score"] if results else float("-inf"),
        )

        suggestions: dict[str, ScanSuggestion] = {}
        for suggestion in self._filter_suggestions(records, finish):
            suggestions.setdefault(suggestion.external_id, suggestion)
            if len(suggestions) == MAX_SUGGESTIONS:
                break
        return list(suggestions.values())

    @abstractmethod
    def _filter_suggestions(
        self, records: list[dict], finish: str
    ) -> Iterator[ScanSuggestion]:
        raise NotImplementedError
