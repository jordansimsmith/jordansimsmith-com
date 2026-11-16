"""Process an accepted scan identification message."""

import logging
from collections.abc import Callable
from dataclasses import dataclass

import cv2
import numpy as np

from .scan_identifier import ScanIdentifier
from .scan_image_store import ScanImageStore
from .scan_queue import ScanQueue
from .scan_repository import Scan, ScanRepository, ScanRow

LOGGER = logging.getLogger(__name__)
BATCH_SIZE = 100


@dataclass(frozen=True)
class RequestContext:
    user: str


@dataclass(frozen=True)
class ScanIdentificationMessage:
    user: str
    scan_id: str


class ScanIdentificationMessageHandler:
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

    def handle_message(
        self, ctx: RequestContext, message: ScanIdentificationMessage
    ) -> None:
        scan = self._repository.get_scan(ctx.user, message.scan_id)
        if scan is None or scan.status != "identifying":
            return
        identifier = self._identifier_loader(scan.game)

        rows = self._repository.find_scan_rows(ctx.user, message.scan_id)
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
