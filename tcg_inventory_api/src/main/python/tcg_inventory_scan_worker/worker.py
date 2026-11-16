"""SQS worker for offline CollectorVision scan recognition."""

from __future__ import annotations

import json
import os

import boto3
from collector_vision import NeuralCornerDetector

from .magic_the_gathering_scan_identifier import MagicTheGatheringScanIdentifier
from .pokemon_scan_identifier import PokemonScanIdentifier
from .scan_identification_message_handler import (
    RequestContext,
    ScanIdentificationMessage,
    ScanIdentificationMessageHandler,
)
from .scan_identifier import ScanIdentifier
from .scan_image_store import ScanImageStore
from .scan_queue import ScanQueue
from .scan_repository import ScanRepository

TABLE_ENV = "SCAN_TABLE_NAME"
BUCKET_ENV = "SCAN_BUCKET_NAME"


class LambdaRuntime:
    def __init__(self):
        self._message_handler: ScanIdentificationMessageHandler | None = None
        self._identifiers: dict[str, ScanIdentifier] = {}
        self._detector = None

    def handle_event(self, event: dict) -> dict[str, int]:
        records = event.get("Records", [])
        if len(records) != 1:
            raise ValueError(f"expected one SQS record, got {len(records)}")

        body = records[0]["body"]
        payload = json.loads(body) if isinstance(body, str) else body
        message = ScanIdentificationMessage(
            user=payload["user"], scan_id=payload["scan_id"]
        )
        self._get_message_handler().handle_message(
            RequestContext(message.user), message
        )
        return {"processed_messages": 1}

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

    def _get_message_handler(self) -> ScanIdentificationMessageHandler:
        if self._message_handler is None:
            table_name = os.environ[TABLE_ENV]
            bucket_name = os.environ[BUCKET_ENV]
            self._message_handler = ScanIdentificationMessageHandler(
                repository=ScanRepository(boto3.client("dynamodb"), table_name),
                image_store=ScanImageStore(boto3.client("s3"), bucket_name),
                scan_queue=ScanQueue(boto3.client("sqs")),
                identifier_loader=self._load_identifier,
            )
        return self._message_handler


_RUNTIME = LambdaRuntime()


def lambda_handler(event: dict, context) -> dict[str, int]:
    del context
    return _RUNTIME.handle_event(event)
