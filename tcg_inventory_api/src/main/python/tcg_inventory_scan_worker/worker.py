"""SQS worker for offline CollectorVision scan recognition."""

from __future__ import annotations

import json
import logging
import os
from abc import ABC, abstractmethod
from collections.abc import Callable, Iterator
from typing import Self

import boto3
import cv2
import numpy as np
from collector_vision import Catalog, CatalogV2, NeuralCornerDetector, rotate_card_180

from .scan_image_store import ScanImageStore
from .scan_queue import ScanQueue
from .scan_repository import Scan, ScanRepository, ScanRow, ScanSuggestion

LOGGER = logging.getLogger(__name__)

TABLE_ENV = "SCAN_TABLE_NAME"
BUCKET_ENV = "SCAN_BUCKET_NAME"
CACHE_ENV = "COLLECTORVISION_CACHE"
BATCH_SIZE = 100
SEARCH_TOP_K = 25
MAX_SUGGESTIONS = 5


class RecognitionResolver(ABC):
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


class MagicTheGatheringResolver(RecognitionResolver):
    game = "mtg"
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


class PokemonResolver(RecognitionResolver):
    game = "pokemon"
    _source = "tcgplayer"
    _pinned_version = 60

    def _filter_suggestions(
        self, records: list[dict], finish: str
    ) -> Iterator[ScanSuggestion]:
        for record in records:
            yield ScanSuggestion(record["id"], record["name"], float(record["score"]))


class RecognitionWorker:
    def __init__(
        self,
        repository: ScanRepository,
        image_store: ScanImageStore,
        scan_queue: ScanQueue,
        recognition_loader: Callable[[str], RecognitionResolver],
        logger: logging.Logger | None = None,
    ):
        self._repository = repository
        self._image_store = image_store
        self._scan_queue = scan_queue
        self._recognition_loader = recognition_loader
        self._logger = logger or LOGGER

    def handle_event(self, event: dict) -> dict[str, int]:
        records = event.get("Records", [])
        for record in records:
            self.handle_message(record["body"])
        return {"processed_messages": len(records)}

    def handle_message(self, body: str | dict) -> None:
        message = json.loads(body) if isinstance(body, str) else body
        user = message["user"]
        scan_id = message["scan_id"]
        scan = self._repository.get_scan(user, scan_id)
        if scan is None or scan.status != "identifying":
            return
        resolver = self._recognition_loader(scan.game)

        rows = self._repository.find_scan_rows(user, scan_id)
        if not rows:
            raise RuntimeError("identifying scan has no rows")
        pending = [row for row in rows if row.status == "pending"]
        if not pending:
            self._reconcile(scan, resolver.catalog_version)
            return

        for row in pending[:BATCH_SIZE]:
            self._recognize_row(scan, row, resolver)

        self._reconcile(scan, resolver.catalog_version)

    def _recognize_row(
        self,
        scan: Scan,
        row: ScanRow,
        resolver: RecognitionResolver,
    ) -> None:
        image_bytes = self._image_store.get_image(row.s3_key)
        try:
            if not image_bytes.startswith(b"\xff\xd8\xff"):
                raise ValueError("unsupported image format")
            image_bgr = cv2.imdecode(
                np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR
            )
            if image_bgr is None:
                raise ValueError("unable to decode image")
            candidates = resolver.find_suggestions(image_bgr, scan.finish)
        except Exception:
            self._logger.exception(
                "scan row recognition failed",
                extra={"scan_id": row.scan_id, "scan_position": row.scan_position},
            )
            raise
        if not candidates:
            status = "needs_review"
            needs_review = True
            suggestions = None
        else:
            status = "suggested"
            needs_review = False
            suggestions = candidates
        self._repository.update_scan_row_result(
            row,
            status=status,
            needs_review=needs_review,
            suggestions=suggestions,
        )

    def _reconcile(self, scan: Scan, catalog_version: int) -> None:
        current_scan = self._repository.get_scan(scan.user, scan.scan_id)
        if current_scan is None or current_scan.status != "identifying":
            return

        rows = self._repository.find_scan_rows(scan.user, scan.scan_id)
        statuses = {row.status for row in rows}
        if "pending" in statuses:
            next_pending_position = min(
                row.scan_position for row in rows if row.status == "pending"
            )
            self._scan_queue.send(scan.user, scan.scan_id, next_pending_position)
            return
        if statuses <= {"suggested", "needs_review"}:
            self._repository.update_scan_for_review(
                scan.user, scan.scan_id, catalog_version
            )
            return
        raise RuntimeError("scan contains an unrecognized worker row status")


class LambdaRuntime:
    def __init__(self):
        self._worker: RecognitionWorker | None = None
        self._resolvers: dict[str, RecognitionResolver] = {}
        self._detector = None

    def handle_event(self, event: dict) -> dict[str, int]:
        return self._get_worker().handle_event(event)

    def _load_recognition(self, game: str) -> RecognitionResolver:
        if game in self._resolvers:
            return self._resolvers[game]

        resolver_type = {
            MagicTheGatheringResolver.game: MagicTheGatheringResolver,
            PokemonResolver.game: PokemonResolver,
        }.get(game)
        if resolver_type is None:
            raise ValueError(f"no recognition integration configured for game: {game}")

        if self._detector is None:
            self._detector = NeuralCornerDetector(provider="cpu")

        resolver = resolver_type.load(self._detector)
        self._resolvers[game] = resolver
        return resolver

    def _get_worker(self) -> RecognitionWorker:
        if self._worker is None:
            table_name = os.environ[TABLE_ENV]
            bucket_name = os.environ[BUCKET_ENV]
            self._worker = RecognitionWorker(
                repository=ScanRepository(boto3.client("dynamodb"), table_name),
                image_store=ScanImageStore(boto3.client("s3"), bucket_name),
                scan_queue=ScanQueue(boto3.client("sqs")),
                recognition_loader=self._load_recognition,
            )
        return self._worker


_RUNTIME = LambdaRuntime()


def lambda_handler(event: dict, context) -> dict[str, int]:
    del context
    return _RUNTIME.handle_event(event)
