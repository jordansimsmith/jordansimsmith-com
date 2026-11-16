"""SQS worker for offline CollectorVision scan recognition."""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Callable

import boto3
import cv2
import numpy as np
from collector_vision import NeuralCornerDetector

from .magic_the_gathering_scan_identifier import MagicTheGatheringScanIdentifier
from .pokemon_scan_identifier import PokemonScanIdentifier
from .scan_identifier import ScanIdentifier
from .scan_image_store import ScanImageStore
from .scan_queue import ScanQueue
from .scan_repository import Scan, ScanRepository, ScanRow

LOGGER = logging.getLogger(__name__)

TABLE_ENV = "SCAN_TABLE_NAME"
BUCKET_ENV = "SCAN_BUCKET_NAME"
BATCH_SIZE = 100


class RecognitionWorker:
    def __init__(
        self,
        repository: ScanRepository,
        image_store: ScanImageStore,
        scan_queue: ScanQueue,
        identifier_loader: Callable[[str], ScanIdentifier],
        logger: logging.Logger | None = None,
    ):
        self._repository = repository
        self._image_store = image_store
        self._scan_queue = scan_queue
        self._identifier_loader = identifier_loader
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
        identifier = self._identifier_loader(scan.game)

        rows = self._repository.find_scan_rows(user, scan_id)
        if not rows:
            raise RuntimeError("identifying scan has no rows")
        pending = [row for row in rows if row.status == "pending"]
        if not pending:
            self._reconcile(scan, identifier.catalog_version)
            return

        for row in pending[:BATCH_SIZE]:
            self._identify_row(scan, row, identifier)

        self._reconcile(scan, identifier.catalog_version)

    def _identify_row(
        self,
        scan: Scan,
        row: ScanRow,
        identifier: ScanIdentifier,
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
            candidates = identifier.find_suggestions(image_bgr, scan.finish)
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
        self._identifiers: dict[str, ScanIdentifier] = {}
        self._detector = None

    def handle_event(self, event: dict) -> dict[str, int]:
        return self._get_worker().handle_event(event)

    def _load_identifier(self, game: str) -> ScanIdentifier:
        if game in self._identifiers:
            return self._identifiers[game]

        identifier_type = {
            MagicTheGatheringScanIdentifier.game: MagicTheGatheringScanIdentifier,
            PokemonScanIdentifier.game: PokemonScanIdentifier,
        }.get(game)
        if identifier_type is None:
            raise ValueError(f"no recognition integration configured for game: {game}")

        if self._detector is None:
            self._detector = NeuralCornerDetector(provider="cpu")

        identifier = identifier_type.load(self._detector)
        self._identifiers[game] = identifier
        return identifier

    def _get_worker(self) -> RecognitionWorker:
        if self._worker is None:
            table_name = os.environ[TABLE_ENV]
            bucket_name = os.environ[BUCKET_ENV]
            self._worker = RecognitionWorker(
                repository=ScanRepository(boto3.client("dynamodb"), table_name),
                image_store=ScanImageStore(boto3.client("s3"), bucket_name),
                scan_queue=ScanQueue(boto3.client("sqs")),
                identifier_loader=self._load_identifier,
            )
        return self._worker


_RUNTIME = LambdaRuntime()


def lambda_handler(event: dict, context) -> dict[str, int]:
    del context
    return _RUNTIME.handle_event(event)
