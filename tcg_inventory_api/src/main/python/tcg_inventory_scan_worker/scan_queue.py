"""SQS dispatch for scan recognition continuations."""

import json

SCAN_IDENTIFICATION_QUEUE_NAME = "tcg_inventory_scan_identification.fifo"


class ScanQueue:
    def __init__(self, client, queue_name: str = SCAN_IDENTIFICATION_QUEUE_NAME):
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
