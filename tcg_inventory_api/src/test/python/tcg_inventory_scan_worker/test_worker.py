"""Tests for batched scan recognition."""

from __future__ import annotations

import io
import json

import pytest
from PIL import Image

from tcg_inventory_scan_worker.worker import (
    LambdaRuntime,
    RecognitionProfile,
    RecognitionWorker,
    Scan,
    ScanRow,
    SqsContinuationQueue,
    _magic_eligible_suggestions,
)


def _jpeg() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (2, 2), color=(10, 20, 30)).save(output, format="JPEG")
    return output.getvalue()


def _scan_rows(count: int, status: str = "pending") -> list[ScanRow]:
    return [
        ScanRow("jordan", "scan-1", position, status, f"scan/{position}.jpg")
        for position in range(1, count + 1)
    ]


def _record(
    card_id: str = "a9738cda-adb1-47fb-9f4c-ecd930228c4d",
    name: str = "Forest",
    finish: str = "nonfoil",
) -> dict:
    return {
        "id": card_id,
        "name": name,
        "identifiers": {"scryfall_card": card_id},
        "metadata": {"lang": "en"},
        "finishes": [finish],
        "score": 0.83,
    }


class FakeStore:
    def __init__(
        self,
        rows: list[ScanRow],
        status: str = "identifying",
        finish: str = "normal",
        game: str = "mtg",
    ):
        self.scan = Scan("jordan", "scan-1", status, game, finish)
        self.rows = rows
        self.results: list[tuple[int, str]] = []
        self.suggestions: dict[int, list[dict]] = {}
        self.transitions: list[tuple[str, str | None]] = []

    def get_scan(self, user: str, scan_id: str) -> Scan | None:
        if self.scan is None:
            return None
        if user != self.scan.user or scan_id != self.scan.scan_id:
            return None
        return self.scan

    def get_rows(self, user: str, scan_id: str) -> list[ScanRow]:
        if self.scan is None:
            return []
        assert (user, scan_id) == (self.scan.user, self.scan.scan_id)
        return list(self.rows)

    def write_result(
        self,
        row: ScanRow,
        *,
        status: str,
        needs_review: bool,
        suggestions: list[dict] | None,
        error: str | None,
    ) -> bool:
        if self.scan is None or self.scan.status != "identifying":
            return False
        index = next(
            (
                index
                for index, current in enumerate(self.rows)
                if current.scan_position == row.scan_position
            ),
            None,
        )
        if index is None:
            return False
        self.rows[index] = ScanRow(
            row.user, row.scan_id, row.scan_position, status, row.s3_key
        )
        self.results.append((row.scan_position, status))
        self.suggestions[row.scan_position] = suggestions or []
        return True

    def transition_reviewing(
        self, user: str, scan_id: str, error: str | None = None
    ) -> bool:
        if self.scan is None or self.scan.status != "identifying":
            return False
        self.transitions.append(("reviewing", error))
        self.scan = Scan(
            self.scan.user,
            self.scan.scan_id,
            "reviewing",
            self.scan.game,
            self.scan.finish,
        )
        return True


class FakeImages:
    def __init__(self, content: bytes = _jpeg()):
        self.content = content
        self.read_keys: list[str] = []

    def read(self, key: str) -> bytes:
        self.read_keys.append(key)
        return self.content


class FakeEmbedder:
    def __init__(self):
        self.calls = 0

    def embed(self, image):
        self.calls += 1
        return image


class FakeCatalog:
    def __init__(self, records=None, failure_on_call: int | None = None):
        self.embedder = FakeEmbedder()
        self.records = records if records is not None else [_record()]
        self.failure_on_call = failure_on_call
        self.search_calls = 0

    def search_records(self, embedding, top_k: int):
        self.search_calls += 1
        if self.failure_on_call == self.search_calls:
            raise ValueError("recognizer failure")
        return self.records


class FakeQueue:
    def __init__(self, failure: Exception | None = None):
        self.messages: list[dict] = []
        self.failure = failure

    def send(self, user: str, scan_id: str, next_pending_position: int) -> None:
        if self.failure is not None:
            raise self.failure
        self.messages.append(
            {
                "user": user,
                "scan_id": scan_id,
                "next_pending_position": next_pending_position,
            }
        )


class FakeSqsClient:
    def __init__(self):
        self.queue_url_calls = 0
        self.messages: list[dict] = []

    def get_queue_url(self, QueueName: str):
        self.queue_url_calls += 1
        assert QueueName == "tcg_inventory_scan_jobs.fifo"
        return {"QueueUrl": "https://sqs.example/scan"}

    def send_message(self, **kwargs):
        self.messages.append(kwargs)


def _worker(store, images=None, catalog=None, queue=None, loader=None):
    def load_recognition(game):
        if game != "mtg":
            raise ValueError(f"no recognition integration configured for game: {game}")
        loaded_catalog = loader(game) if loader else catalog or FakeCatalog()
        return RecognitionProfile(loaded_catalog, _magic_eligible_suggestions)

    return RecognitionWorker(
        store=store,
        images=images or FakeImages(),
        continuations=queue or FakeQueue(),
        recognition_loader=load_recognition,
    )


def test_processes_at_most_one_hundred_rows_and_enqueues_continuation():
    store = FakeStore(_scan_rows(200))
    queue = FakeQueue()
    worker = _worker(store, queue=queue)

    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert sum(status == "suggested" for _, status in store.results) == 100
    assert [row.status for row in store.rows[100:]] == ["pending"] * 100
    assert queue.messages == [
        {"user": "jordan", "scan_id": "scan-1", "next_pending_position": 101}
    ]
    assert store.scan.status == "identifying"

    worker.handle_message(json.dumps(queue.messages[0]))

    assert all(row.status == "suggested" for row in store.rows)
    assert store.scan.status == "reviewing"
    assert len(queue.messages) == 1


@pytest.mark.parametrize("count", [1, 100])
def test_finishes_without_continuation_when_batch_is_complete(count):
    store = FakeStore(_scan_rows(count))
    queue = FakeQueue()

    _worker(store, queue=queue).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert store.scan.status == "reviewing"
    assert queue.messages == []


def test_terminal_rows_are_not_reprocessed():
    rows = _scan_rows(3)
    rows[0] = ScanRow("jordan", "scan-1", 1, "suggested", rows[0].s3_key)
    store = FakeStore(rows)
    catalog = FakeCatalog()

    _worker(store, catalog=catalog).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert catalog.embedder.calls == 2


def test_overlapping_workers_repeat_results_and_enqueue_same_continuation():
    store = FakeStore(_scan_rows(101))
    queue = FakeQueue()
    original_rows = list(store.rows)
    original_get_rows = store.get_rows
    read_count = 0

    def get_rows_with_stale_overlap(user: str, scan_id: str) -> list[ScanRow]:
        nonlocal read_count
        read_count += 1
        if read_count in (1, 3):
            return list(original_rows)
        return original_get_rows(user, scan_id)

    store.get_rows = get_rows_with_stale_overlap
    worker = _worker(store, queue=queue)

    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})
    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert len(store.results) == 200
    assert queue.messages == [
        {"user": "jordan", "scan_id": "scan-1", "next_pending_position": 101},
        {"user": "jordan", "scan_id": "scan-1", "next_pending_position": 101},
    ]
    assert all(row.status == "suggested" for row in store.rows[:100])
    assert store.rows[100].status == "pending"
    assert store.scan.status == "identifying"


def test_partial_batch_retry_leaves_written_rows_terminal():
    class FailingImages(FakeImages):
        def __init__(self):
            super().__init__()
            self.calls = 0

        def read(self, key: str) -> bytes:
            self.calls += 1
            if self.calls == 51:
                raise RuntimeError("s3 unavailable")
            return super().read(key)

    store = FakeStore(_scan_rows(101))

    with pytest.raises(RuntimeError, match="s3 unavailable"):
        _worker(store, images=FailingImages()).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert [row.status for row in store.rows[:50]] == ["suggested"] * 50
    assert [row.status for row in store.rows[50:]] == ["pending"] * 51

    _worker(store).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert all(row.status == "suggested" for row in store.rows)
    assert store.scan.status == "reviewing"


def test_deleted_scan_during_inference_does_not_write_or_enqueue():
    store = FakeStore(_scan_rows(1))

    class DeletingImages(FakeImages):
        def read(self, key: str) -> bytes:
            store.scan = None
            return super().read(key)

    queue = FakeQueue()
    _worker(store, images=DeletingImages(), queue=queue).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.results == []
    assert store.rows[0].status == "pending"
    assert queue.messages == []


def test_duplicate_delivery_after_completion_is_a_no_op():
    store = FakeStore(_scan_rows(1))
    worker = _worker(store)

    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})
    result_count = len(store.results)
    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert len(store.results) == result_count
    assert store.scan.status == "reviewing"


def test_continuation_message_uses_next_pending_position():
    rows = _scan_rows(104)
    rows[0] = ScanRow("jordan", "scan-1", 1, "suggested", rows[0].s3_key)
    rows[1] = ScanRow("jordan", "scan-1", 2, "needs_review", rows[1].s3_key)
    store = FakeStore(rows)
    queue = FakeQueue()

    _worker(store, queue=queue).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert queue.messages == [
        {"user": "jordan", "scan_id": "scan-1", "next_pending_position": 103}
    ]


def test_filters_language_and_finish_and_deduplicates_faces():
    french_record = _record(
        "c6f20488-891b-4d8e-a8fd-aa253dd5991b", "French Forest", "nonfoil"
    )
    french_record["metadata"]["lang"] = "fr"
    records = [
        _record("a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Forest", "nonfoil"),
        _record("a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Forest", "nonfoil"),
        _record("4ced112a-e775-4f97-97b3-74877e9dce12", "Foil Forest", "foil"),
        french_record,
    ]
    store = FakeStore(_scan_rows(1))
    catalog = FakeCatalog(records)
    _worker(store, catalog=catalog).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "suggested"
    assert store.suggestions[1] == [
        {
            "external_source": "scryfall",
            "external_id": "a9738cda-adb1-47fb-9f4c-ecd930228c4d",
            "name": "Forest",
            "score": 0.83,
        }
    ]
    assert catalog.search_calls == 1


def test_treats_external_ids_as_opaque():
    suggestions = _magic_eligible_suggestions([_record("not-a-uuid")], "normal")

    assert suggestions[0]["external_id"] == "not-a-uuid"


def test_game_without_recognition_integration_fails_before_image_processing():
    store = FakeStore(_scan_rows(1), game="unknown_game")
    images = FakeImages()
    catalog_loader_calls = []

    def load_catalog(game):
        catalog_loader_calls.append(game)
        raise ValueError(f"no recognition integration configured for game: {game}")

    with pytest.raises(
        ValueError, match="no recognition integration configured for game: unknown_game"
    ):
        worker = _worker(store, images=images, loader=load_catalog)
        worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert catalog_loader_calls == []
    assert images.read_keys == []
    assert store.results == []


def test_runtime_fails_before_catalog_loading_when_game_has_no_catalog():
    runtime = LambdaRuntime()

    with pytest.raises(
        ValueError, match="no recognition integration configured for game: unknown_game"
    ):
        runtime._load_recognition("unknown_game")

    assert runtime._catalog is None


def test_corrupt_image_becomes_manual_review():
    store = FakeStore(_scan_rows(1))
    _worker(store, images=FakeImages(b"not a jpeg")).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "needs_review"
    assert store.results == [(1, "needs_review")]


def test_unsupported_image_format_becomes_manual_review():
    output = io.BytesIO()
    Image.new("RGB", (2, 2), color=(10, 20, 30)).save(output, format="PNG")
    store = FakeStore(_scan_rows(1))

    _worker(store, images=FakeImages(output.getvalue())).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "needs_review"
    assert store.results == [(1, "needs_review")]


def test_image_store_failure_propagates_without_marking_row_finished():
    class BrokenImages:
        def read(self, key: str) -> bytes:
            raise RuntimeError("s3 unavailable")

    store = FakeStore(_scan_rows(1))

    with pytest.raises(RuntimeError, match="s3 unavailable"):
        _worker(store, images=BrokenImages()).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.rows[0].status == "pending"
    assert store.results == []


def test_empty_candidates_become_manual_review():
    store = FakeStore(_scan_rows(1))
    _worker(store, catalog=FakeCatalog([])).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "needs_review"


def test_catalog_initialization_failure_propagates_without_marking_rows():
    store = FakeStore(_scan_rows(2))

    with pytest.raises(RuntimeError, match="offline catalog failed"):
        _worker(
            store,
            loader=lambda game: (_ for _ in ()).throw(
                RuntimeError("offline catalog failed")
            ),
        ).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert [row.status for row in store.rows] == ["pending", "pending"]
    assert store.scan.status == "identifying"
    assert store.transitions == []


def test_deleted_scan_is_ignored():
    store = FakeStore(_scan_rows(1))
    store.scan = None
    worker = _worker(store)

    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert store.results == []


def test_continuation_failure_leaves_pending_rows_for_recovery():
    store = FakeStore(_scan_rows(101))
    queue = FakeQueue(RuntimeError("queue unavailable"))

    with pytest.raises(RuntimeError, match="queue unavailable"):
        _worker(store, queue=queue).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.scan.status == "identifying"
    assert store.rows[-1].status == "pending"


def test_continuation_queue_resolves_url_once_and_preserves_message_shape():
    client = FakeSqsClient()
    queue = SqsContinuationQueue(client)

    queue.send("jordan", "scan-1", 101)
    queue.send("jordan", "scan-1", 101)

    assert client.queue_url_calls == 1
    assert client.messages == [
        {
            "QueueUrl": "https://sqs.example/scan",
            "MessageBody": '{"user":"jordan","scan_id":"scan-1"}',
            "MessageGroupId": "scan-1",
            "MessageDeduplicationId": "scan-1#101",
        },
        {
            "QueueUrl": "https://sqs.example/scan",
            "MessageBody": '{"user":"jordan","scan_id":"scan-1"}',
            "MessageGroupId": "scan-1",
            "MessageDeduplicationId": "scan-1#101",
        },
    ]
