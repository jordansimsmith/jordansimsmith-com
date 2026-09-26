"""SQS worker for offline CollectorVision scan recognition."""

from __future__ import annotations

import io
import json
import logging
import os
import time
from dataclasses import dataclass

import boto3
from PIL import Image
from collector_vision import Catalog

LOGGER = logging.getLogger(__name__)

TABLE_ENV = "SCAN_TABLE_NAME"
BUCKET_ENV = "SCAN_BUCKET_NAME"
CACHE_ENV = "COLLECTORVISION_CACHE"
SCAN_QUEUE_NAME = "tcg_inventory_scan_jobs.fifo"
CATALOG_VERSION = 40
BATCH_SIZE = 100
SEARCH_TOP_K = 25
MAX_SUGGESTIONS = 5


@dataclass(frozen=True)
class Scan:
    user: str
    scan_id: str
    status: str
    game: str
    finish: str


@dataclass(frozen=True)
class ScanRow:
    user: str
    scan_id: str
    scan_position: int
    status: str
    s3_key: str


class DynamoScanStore:
    """DynamoDB persistence for scan recognition checkpoints."""

    def __init__(self, client, table_name: str, now=None):
        self._client = client
        self._table_name = table_name
        self._now = now or (lambda: int(time.time()))

    @staticmethod
    def _scan_key(user: str, scan_id: str) -> dict[str, dict[str, str]]:
        return {"pk": {"S": f"USER#{user}"}, "sk": {"S": f"SCAN#{scan_id}"}}

    @staticmethod
    def _row_partition_key(user: str, scan_id: str) -> str:
        return f"USER#{user}#SCAN#{scan_id}"

    @classmethod
    def _row_key(cls, row: ScanRow) -> dict[str, dict[str, str]]:
        return {
            "pk": {"S": cls._row_partition_key(row.user, row.scan_id)},
            "sk": {"S": f"ROW#{row.scan_position:06d}"},
        }

    @staticmethod
    def _string(item: dict, field: str) -> str:
        return item[field]["S"]

    @staticmethod
    def _number(item: dict, field: str) -> int:
        return int(item[field]["N"])

    @staticmethod
    def _suggestion_attribute(suggestion: dict) -> dict:
        return {
            "M": {
                "external_source": {"S": suggestion["external_source"]},
                "external_id": {"S": suggestion["external_id"]},
                "name": {"S": suggestion["name"]},
                "score": {"N": str(suggestion["score"])},
            }
        }

    @staticmethod
    def _only_conditional_failures(error: Exception) -> bool:
        reasons = getattr(error, "response", {}).get("CancellationReasons", [])
        codes = [reason.get("Code") for reason in reasons]
        return (
            bool(codes)
            and "ConditionalCheckFailed" in codes
            and all(code in {"None", "ConditionalCheckFailed"} for code in codes)
        )

    def get_scan(self, user: str, scan_id: str) -> Scan | None:
        response = self._client.get_item(
            TableName=self._table_name,
            Key=self._scan_key(user, scan_id),
            ConsistentRead=True,
        )
        item = response.get("Item")
        if item is None:
            return None
        return Scan(
            user=user,
            scan_id=scan_id,
            status=self._string(item, "status"),
            game=self._string(item, "game"),
            finish=self._string(item, "finish"),
        )

    def get_rows(self, user: str, scan_id: str) -> list[ScanRow]:
        response = self._client.query(
            TableName=self._table_name,
            KeyConditionExpression="#pk = :pk AND begins_with(#sk, :row_prefix)",
            ExpressionAttributeNames={"#pk": "pk", "#sk": "sk"},
            ExpressionAttributeValues={
                ":pk": {"S": self._row_partition_key(user, scan_id)},
                ":row_prefix": {"S": "ROW#"},
            },
            ScanIndexForward=True,
            ConsistentRead=True,
        )
        rows = [
            ScanRow(
                user=user,
                scan_id=scan_id,
                scan_position=self._number(item, "scan_position"),
                status=self._string(item, "status"),
                s3_key=self._string(item, "s3_key"),
            )
            for item in response.get("Items", [])
        ]
        return sorted(rows, key=lambda row: row.scan_position)

    def write_result(
        self,
        row: ScanRow,
        *,
        status: str,
        needs_review: bool,
        suggestions: list[dict] | None,
        error: str | None,
    ) -> bool:
        set_parts = ["#status = :status", "#needs_review = :needs_review"]
        remove_parts: list[str] = []
        values: dict = {
            ":status": {"S": status},
            ":needs_review": {"BOOL": needs_review},
        }
        names = {
            "#pk": "pk",
            "#status": "status",
            "#needs_review": "needs_review",
            "#suggestions": "suggestions",
            "#error": "error",
        }
        if suggestions is None:
            remove_parts.append("#suggestions")
        else:
            set_parts.append("#suggestions = :suggestions")
            values[":suggestions"] = {
                "L": [
                    self._suggestion_attribute(suggestion) for suggestion in suggestions
                ]
            }
        if error is None:
            remove_parts.append("#error")
        else:
            set_parts.append("#error = :error")
            values[":error"] = {"S": error}
        expression = "SET " + ", ".join(set_parts)
        if remove_parts:
            expression += " REMOVE " + ", ".join(remove_parts)

        try:
            self._client.transact_write_items(
                TransactItems=[
                    {
                        "ConditionCheck": {
                            "TableName": self._table_name,
                            "Key": self._scan_key(row.user, row.scan_id),
                            "ConditionExpression": (
                                "attribute_exists(#pk) AND #status = :identifying"
                            ),
                            "ExpressionAttributeNames": {
                                "#pk": "pk",
                                "#status": "status",
                            },
                            "ExpressionAttributeValues": {
                                ":identifying": {"S": "identifying"},
                            },
                        }
                    },
                    {
                        "Update": {
                            "TableName": self._table_name,
                            "Key": self._row_key(row),
                            "UpdateExpression": expression,
                            "ConditionExpression": "attribute_exists(#pk)",
                            "ExpressionAttributeNames": names,
                            "ExpressionAttributeValues": {
                                **values,
                            },
                        }
                    },
                ],
            )
            return True
        except self._client.exceptions.TransactionCanceledException as error:
            if self._only_conditional_failures(error):
                return False
            raise

    def transition_reviewing(
        self, user: str, scan_id: str, error: str | None = None
    ) -> bool:
        set_parts = [
            "#status = :reviewing",
            "#catalog_version = :catalog_version",
            "#updated_at = :updated_at",
        ]
        values = {
            ":reviewing": {"S": "reviewing"},
            ":identifying": {"S": "identifying"},
            ":catalog_version": {"N": str(CATALOG_VERSION)},
            ":updated_at": {"N": str(self._now())},
        }
        names = {
            "#status": "status",
            "#catalog_version": "catalog_version",
            "#updated_at": "updated_at",
        }
        if error is not None:
            set_parts.append("#error = :error")
            names["#error"] = "error"
            values[":error"] = {"S": error}
        try:
            self._client.update_item(
                TableName=self._table_name,
                Key=self._scan_key(user, scan_id),
                UpdateExpression="SET " + ", ".join(set_parts),
                ConditionExpression="attribute_exists(#pk) AND #status = :identifying",
                ExpressionAttributeNames={"#pk": "pk", **names},
                ExpressionAttributeValues=values,
            )
            return True
        except self._client.exceptions.ConditionalCheckFailedException:
            return False


class S3ImageStore:
    def __init__(self, client, bucket: str):
        self._client = client
        self._bucket = bucket

    def read(self, key: str) -> bytes:
        return self._client.get_object(Bucket=self._bucket, Key=key)["Body"].read()


class SqsContinuationQueue:
    def __init__(self, client, queue_name: str = SCAN_QUEUE_NAME):
        self._client = client
        self._queue_name = queue_name
        self._queue_url: str | None = None

    def send(self, user: str, scan_id: str, next_pending_position: int) -> None:
        if self._queue_url is None:
            self._queue_url = self._client.get_queue_url(QueueName=self._queue_name)[
                "QueueUrl"
            ]
        self._client.send_message(
            QueueUrl=self._queue_url,
            MessageBody=json.dumps(
                {"user": user, "scan_id": scan_id},
                separators=(",", ":"),
            ),
            MessageGroupId=scan_id,
            MessageDeduplicationId=f"{scan_id}#{next_pending_position}",
        )


class RecognitionWorker:
    _FINISH_TO_CATALOG_FINISH = {
        "normal": "nonfoil",
        "foil": "foil",
        "etched": "etched",
    }

    def __init__(
        self,
        store,
        images,
        continuations,
        catalog_loader,
        logger: logging.Logger | None = None,
    ):
        self._store = store
        self._images = images
        self._continuations = continuations
        self._catalog_loader = catalog_loader
        self._logger = logger or LOGGER

    @classmethod
    def _eligible_suggestions(cls, records: list[dict], finish: str) -> list[dict]:
        required_finish = cls._FINISH_TO_CATALOG_FINISH[finish]
        suggestions: list[dict] = []
        seen: set[str] = set()
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
            if external_id in seen:
                continue
            seen.add(external_id)
            suggestions.append(
                {
                    "external_source": "scryfall",
                    "external_id": external_id,
                    "name": name,
                    "score": float(score),
                }
            )
            if len(suggestions) >= MAX_SUGGESTIONS:
                break
        return suggestions

    def handle_event(self, event: dict) -> dict[str, int]:
        records = event.get("Records", [])
        for record in records:
            self.handle_message(record["body"])
        return {"processed_messages": len(records)}

    def handle_message(self, body: str | dict) -> None:
        message = json.loads(body) if isinstance(body, str) else body
        user = message["user"]
        scan_id = message["scan_id"]
        scan = self._store.get_scan(user, scan_id)
        if scan is None or scan.status != "identifying":
            return
        catalog = self._catalog_loader(scan.game)

        rows = self._store.get_rows(user, scan_id)
        if not rows:
            raise RuntimeError("identifying scan has no rows")
        pending = [row for row in rows if row.status == "pending"]
        if not pending:
            self._reconcile(scan)
            return

        for row in pending[:BATCH_SIZE]:
            if not self._recognize_row(scan, row, catalog):
                return

        self._reconcile(scan)

    def _recognize_row(self, scan: Scan, row: ScanRow, catalog) -> bool:
        image_bytes = self._images.read(row.s3_key)
        try:
            with Image.open(io.BytesIO(image_bytes)) as image:
                if image.format != "JPEG":
                    raise ValueError("unsupported image format")
                image.load()
                rgb_image = image.convert("RGB")
            embedding = catalog.embedder.embed(rgb_image)
            candidates = self._eligible_suggestions(
                catalog.search_records(embedding, top_k=SEARCH_TOP_K), scan.finish
            )
            if not candidates:
                status = "needs_review"
                needs_review = True
                suggestions = None
                error = "no eligible recognition candidates"
            else:
                status = "suggested"
                needs_review = False
                suggestions = candidates
                error = None
        except Exception:
            self._logger.exception(
                "scan row recognition failed",
                extra={"scan_id": row.scan_id, "scan_position": row.scan_position},
            )
            status = "needs_review"
            needs_review = True
            suggestions = None
            error = "unable to decode or recognize image"
        return self._store.write_result(
            row,
            status=status,
            needs_review=needs_review,
            suggestions=suggestions,
            error=error,
        )

    def _reconcile(self, scan: Scan) -> None:
        current_scan = self._store.get_scan(scan.user, scan.scan_id)
        if current_scan is None or current_scan.status != "identifying":
            return

        rows = self._store.get_rows(scan.user, scan.scan_id)
        statuses = {row.status for row in rows}
        if "pending" in statuses:
            next_pending_position = min(
                row.scan_position for row in rows if row.status == "pending"
            )
            self._continuations.send(scan.user, scan.scan_id, next_pending_position)
            return
        if statuses <= {"suggested", "needs_review"}:
            self._store.transition_reviewing(scan.user, scan.scan_id)
            return
        raise RuntimeError("scan contains an unrecognized worker row status")


class LambdaRuntime:
    def __init__(self):
        self._worker: RecognitionWorker | None = None
        self._catalog = None

    def handle_event(self, event: dict) -> dict[str, int]:
        return self._get_worker().handle_event(event)

    def _load_catalog(self, game: str):
        catalog_loader = {"mtg": self._load_mtg_catalog}.get(game)
        if catalog_loader is None:
            raise ValueError(f"no recognition catalog configured for game: {game}")
        return catalog_loader()

    def _load_mtg_catalog(self):
        if self._catalog is None:
            self._catalog = Catalog.load(
                "mtg",
                source="scryfall",
                family="milo1",
                cache_dir=os.environ[CACHE_ENV],
                offline=True,
                version=CATALOG_VERSION,
            )
            _ = self._catalog.embedder
        return self._catalog

    def _get_worker(self) -> RecognitionWorker:
        if self._worker is None:
            table_name = os.environ[TABLE_ENV]
            bucket_name = os.environ[BUCKET_ENV]
            self._worker = RecognitionWorker(
                store=DynamoScanStore(boto3.client("dynamodb"), table_name),
                images=S3ImageStore(boto3.client("s3"), bucket_name),
                continuations=SqsContinuationQueue(boto3.client("sqs")),
                catalog_loader=self._load_catalog,
            )
        return self._worker


_RUNTIME = LambdaRuntime()


def lambda_handler(event: dict, context) -> dict[str, int]:
    del context
    return _RUNTIME.handle_event(event)
