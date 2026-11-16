"""DynamoDB persistence for scan recognition checkpoints."""

from __future__ import annotations

import time
from dataclasses import dataclass


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


@dataclass(frozen=True)
class ScanSuggestion:
    external_id: str
    name: str
    score: float


class ScanRepository:
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
    def _suggestion_attribute(suggestion: ScanSuggestion) -> dict:
        return {
            "M": {
                "external_id": {"S": suggestion.external_id},
                "name": {"S": suggestion.name},
                "score": {"N": str(suggestion.score)},
            }
        }

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

    def find_scan_rows(self, user: str, scan_id: str) -> list[ScanRow]:
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

    def update_scan_row_result(
        self,
        row: ScanRow,
        *,
        status: str,
        needs_review: bool,
        suggestions: list[ScanSuggestion] | None,
    ) -> None:
        set_parts = ["#status = :status", "#needs_review = :needs_review"]
        values: dict = {
            ":status": {"S": status},
            ":needs_review": {"BOOL": needs_review},
        }
        names = {
            "#pk": "pk",
            "#status": "status",
            "#needs_review": "needs_review",
        }
        if suggestions is not None:
            set_parts.append("#suggestions = :suggestions")
            names["#suggestions"] = "suggestions"
            values[":suggestions"] = {
                "L": [
                    self._suggestion_attribute(suggestion) for suggestion in suggestions
                ]
            }
        expression = "SET " + ", ".join(set_parts)

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

    def update_scan_for_review(
        self, user: str, scan_id: str, catalog_version: int, error: str | None = None
    ) -> None:
        set_parts = [
            "#status = :reviewing",
            "#catalog_version = :catalog_version",
            "#updated_at = :updated_at",
        ]
        values = {
            ":reviewing": {"S": "reviewing"},
            ":identifying": {"S": "identifying"},
            ":catalog_version": {"N": str(catalog_version)},
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
        self._client.update_item(
            TableName=self._table_name,
            Key=self._scan_key(user, scan_id),
            UpdateExpression="SET " + ", ".join(set_parts),
            ConditionExpression="attribute_exists(#pk) AND #status = :identifying",
            ExpressionAttributeNames={"#pk": "pk", **names},
            ExpressionAttributeValues=values,
        )
