"""Tests for batched scan recognition."""

from __future__ import annotations

import io
import json

import boto3
import pytest
from botocore.exceptions import ClientError
from botocore.stub import Stubber
from PIL import Image

from tcg_inventory_scan_worker import worker as scan_worker
from tcg_inventory_scan_worker.scan_repository import (
    Scan,
    ScanRepository,
    ScanRow,
    ScanSuggestion,
)
from tcg_inventory_scan_worker.worker import (
    LambdaRuntime,
    MagicTheGatheringResolver,
    PokemonResolver,
    RecognitionWorker,
    SqsContinuationQueue,
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


class FakeScanRepository:
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
        self.suggestions: dict[int, list[ScanSuggestion]] = {}
        self.transitions: list[tuple[str, int, str | None]] = []

    def get_scan(self, user: str, scan_id: str) -> Scan | None:
        if self.scan is None:
            return None
        if user != self.scan.user or scan_id != self.scan.scan_id:
            return None
        return self.scan

    def find_scan_rows(self, user: str, scan_id: str) -> list[ScanRow]:
        if self.scan is None:
            return []
        assert (user, scan_id) == (self.scan.user, self.scan.scan_id)
        return list(self.rows)

    def update_scan_row_result(
        self,
        row: ScanRow,
        *,
        status: str,
        needs_review: bool,
        suggestions: list[ScanSuggestion] | None,
    ) -> None:
        assert self.scan is not None and self.scan.status == "identifying"
        index = next(
            index
            for index, current in enumerate(self.rows)
            if current.scan_position == row.scan_position
        )
        self.rows[index] = ScanRow(
            row.user, row.scan_id, row.scan_position, status, row.s3_key
        )
        self.results.append((row.scan_position, status))
        self.suggestions[row.scan_position] = suggestions or []

    def update_scan_for_review(
        self, user: str, scan_id: str, catalog_version: int, error: str | None = None
    ) -> None:
        assert self.scan is not None and self.scan.status == "identifying"
        self.transitions.append(("reviewing", catalog_version, error))
        self.scan = Scan(
            self.scan.user,
            self.scan.scan_id,
            "reviewing",
            self.scan.game,
            self.scan.finish,
        )


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
    def __init__(self, records=None, failure_on_call: int | None = None, version=40):
        self.embedder = FakeEmbedder()
        self.records = records if records is not None else [_record()]
        self.failure_on_call = failure_on_call
        self.search_calls = 0
        self.version = version

    def search_records(self, embedding, top_k: int):
        self.search_calls += 1
        if self.failure_on_call == self.search_calls:
            raise ValueError("recognizer failure")
        return self.records


class FakeDetector:
    def __init__(self, card_present: bool = True):
        self.card_present = card_present
        self.calls = 0

    def detect(self, image):
        assert image.shape == (2, 2, 3)
        self.calls += 1
        return self

    def dewarp(self, image):
        return Image.new("RGB", (2, 2), color=(10, 20, 30))


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
        assert QueueName == "tcg_inventory_scan_identification.fifo"
        return {"QueueUrl": "https://sqs.example/scan"}

    def send_message(self, **kwargs):
        self.messages.append(kwargs)


def _worker(store, images=None, catalog=None, detector=None, queue=None, loader=None):
    def load_recognition(game):
        if game != "mtg":
            raise ValueError(f"no recognition integration configured for game: {game}")
        loaded_catalog = loader(game) if loader else catalog or FakeCatalog()
        return MagicTheGatheringResolver(loaded_catalog, detector or FakeDetector())

    return RecognitionWorker(
        repository=store,
        images=images or FakeImages(),
        continuations=queue or FakeQueue(),
        recognition_loader=load_recognition,
    )


def test_processes_at_most_one_hundred_rows_and_enqueues_continuation():
    store = FakeScanRepository(_scan_rows(200))
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
    store = FakeScanRepository(_scan_rows(count))
    queue = FakeQueue()

    _worker(store, queue=queue).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert store.scan.status == "reviewing"
    assert queue.messages == []


def test_terminal_rows_are_not_reprocessed():
    rows = _scan_rows(3)
    rows[0] = ScanRow("jordan", "scan-1", 1, "suggested", rows[0].s3_key)
    store = FakeScanRepository(rows)
    catalog = FakeCatalog()

    _worker(store, catalog=catalog).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert catalog.embedder.calls == 4


def test_overlapping_workers_repeat_results_and_enqueue_same_continuation():
    store = FakeScanRepository(_scan_rows(101))
    queue = FakeQueue()
    original_rows = list(store.rows)
    original_find_scan_rows = store.find_scan_rows
    read_count = 0

    def find_scan_rows_with_stale_overlap(user: str, scan_id: str) -> list[ScanRow]:
        nonlocal read_count
        read_count += 1
        if read_count in (1, 3):
            return list(original_rows)
        return original_find_scan_rows(user, scan_id)

    store.find_scan_rows = find_scan_rows_with_stale_overlap
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

    store = FakeScanRepository(_scan_rows(101))

    with pytest.raises(RuntimeError, match="s3 unavailable"):
        _worker(store, images=FailingImages()).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert [row.status for row in store.rows[:50]] == ["suggested"] * 50
    assert [row.status for row in store.rows[50:]] == ["pending"] * 51

    _worker(store).handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert all(row.status == "suggested" for row in store.rows)
    assert store.scan.status == "reviewing"


@pytest.mark.parametrize(
    ("row_status", "operation", "error_code", "modeled_fields", "retry_scan"),
    [
        (
            "pending",
            "transact_write_items",
            "TransactionCanceledException",
            {"CancellationReasons": [{"Code": "ConditionalCheckFailed"}]},
            {},
        ),
        (
            "suggested",
            "update_item",
            "ConditionalCheckFailedException",
            None,
            {
                "Item": {
                    "status": {"S": "reviewing"},
                    "game": {"S": "mtg"},
                    "finish": {"S": "normal"},
                }
            },
        ),
    ],
)
def test_dynamodb_write_conflict_fails_the_event_and_retry_reads_current_state(
    row_status, operation, error_code, modeled_fields, retry_scan
):
    client = boto3.client(
        "dynamodb",
        region_name="us-east-1",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    queue = FakeQueue()
    worker = _worker(ScanRepository(client, "scans"), queue=queue)
    event = {"Records": [{"body": '{"user":"jordan","scan_id":"scan-1"}'}]}
    scan = {
        "Item": {
            "status": {"S": "identifying"},
            "game": {"S": "mtg"},
            "finish": {"S": "normal"},
        }
    }
    rows = {
        "Items": [
            {
                "scan_position": {"N": "1"},
                "status": {"S": row_status},
                "s3_key": {"S": "scan/1.jpg"},
            }
        ]
    }

    with Stubber(client) as stubber:
        for _ in range(1 if row_status == "pending" else 2):
            stubber.add_response("get_item", scan)
            stubber.add_response("query", rows)
        stubber.add_client_error(operation, error_code, modeled_fields=modeled_fields)
        stubber.add_response("get_item", retry_scan)

        with pytest.raises(ClientError) as error:
            worker.handle_event(event)
        assert error.value.response["Error"]["Code"] == error_code
        assert worker.handle_event(event) == {"processed_messages": 1}
        stubber.assert_no_pending_responses()

    assert queue.messages == []


def test_duplicate_delivery_after_completion_is_a_no_op():
    store = FakeScanRepository(_scan_rows(1))
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
    store = FakeScanRepository(rows)
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
    store = FakeScanRepository(_scan_rows(1))
    catalog = FakeCatalog(records)
    _worker(store, catalog=catalog).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "suggested"
    assert store.suggestions[1] == [
        ScanSuggestion("a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Forest", 0.83)
    ]
    assert catalog.search_calls == 2


def test_uses_higher_scoring_card_orientation():
    class OrientationCatalog(FakeCatalog):
        def search_records(self, embedding, top_k: int):
            self.search_calls += 1
            if self.search_calls == 1:
                return [_record(name="Wrong card") | {"score": 0.3}]
            return [_record(name="Correct card") | {"score": 0.9}]

    store = FakeScanRepository(_scan_rows(1))
    detector = FakeDetector()
    catalog = OrientationCatalog()

    _worker(store, catalog=catalog, detector=detector).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert detector.calls == 1
    assert store.suggestions[1][0].name == "Correct card"


def test_missing_card_becomes_manual_review_without_search():
    store = FakeScanRepository(_scan_rows(1))
    catalog = FakeCatalog()

    _worker(store, catalog=catalog, detector=FakeDetector(False)).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "needs_review"
    assert catalog.search_calls == 0


def test_treats_external_ids_as_opaque():
    store = FakeScanRepository(_scan_rows(1))
    _worker(store, catalog=FakeCatalog([_record("not-a-uuid")])).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.suggestions[1][0].external_id == "not-a-uuid"


def test_pokemon_suggestions_keep_rank_and_cap_unique_products():
    charizard = {
        "id": "226395",
        "name": "Charizard",
        "identifiers": {"tcgplayer_group": "2701"},
        "metadata": {"collector_number": "025/185", "set": "SWSH04: Vivid Voltage"},
        "score": 0.98,
    }
    records = [
        charizard,
        {**charizard, "face_index": 1, "score": 0.97},
        {"id": "124026", "name": "Charizard", "score": 0.96},
        {"id": "84485", "name": "Crobat (147)", "score": 0.94},
        {"id": "724537", "name": "Pichu (2002 E3 Promo)", "score": 0.93},
        {"id": "724538", "name": "Hoppip (2002 E3 Promo)", "score": 0.92},
        {"id": "264925", "name": "VSTAR Token", "score": 0.91},
    ]

    store = FakeScanRepository(_scan_rows(1), game="pokemon", finish="reverse_holofoil")
    resolver = PokemonResolver(FakeCatalog(records, version=60), FakeDetector())
    worker = RecognitionWorker(store, FakeImages(), FakeQueue(), lambda game: resolver)
    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert store.suggestions[1] == [
        ScanSuggestion("226395", "Charizard", 0.98),
        ScanSuggestion("124026", "Charizard", 0.96),
        ScanSuggestion("84485", "Crobat (147)", 0.94),
        ScanSuggestion("724537", "Pichu (2002 E3 Promo)", 0.93),
        ScanSuggestion("724538", "Hoppip (2002 E3 Promo)", 0.92),
    ]


def test_runtime_lazily_reuses_both_resolvers_and_records_their_versions(monkeypatch):
    catalogs = {
        "mtg": FakeCatalog(version=40),
        "pokemon": FakeCatalog([], version=60),
    }
    load_calls = []
    detector_calls = []
    detector = FakeDetector()

    def load(game, **options):
        load_calls.append((game, options))
        return catalogs[game]

    def create_detector(*, provider):
        detector_calls.append(provider)
        return detector

    monkeypatch.setenv(scan_worker.CACHE_ENV, "/offline-cache")
    monkeypatch.setattr(scan_worker.Catalog, "load", load)
    monkeypatch.setattr(scan_worker, "NeuralCornerDetector", create_detector)
    runtime = LambdaRuntime()
    assert load_calls == []
    assert detector_calls == []

    magic = FakeScanRepository(_scan_rows(1))
    RecognitionWorker(
        magic, FakeImages(), FakeQueue(), runtime._load_recognition
    ).handle_message({"user": "jordan", "scan_id": "scan-1"})
    assert [game for game, _ in load_calls] == ["mtg"]

    pokemon = FakeScanRepository(_scan_rows(1), game="pokemon")
    RecognitionWorker(
        pokemon, FakeImages(), FakeQueue(), runtime._load_recognition
    ).handle_message({"user": "jordan", "scan_id": "scan-1"})

    magic_resolver = runtime._load_recognition("mtg")
    pokemon_resolver = runtime._load_recognition("pokemon")
    assert magic_resolver is runtime._load_recognition("mtg")
    assert pokemon_resolver is runtime._load_recognition("pokemon")
    assert detector_calls == ["cpu"]
    assert detector.calls == 2
    common = {"family": "milo1", "cache_dir": "/offline-cache", "offline": True}
    assert load_calls == [
        ("mtg", {**common, "source": "scryfall", "version": 40}),
        ("pokemon", {**common, "source": "tcgplayer", "version": 60}),
    ]
    assert magic.transitions == [("reviewing", 40, None)]
    assert pokemon.transitions == [("reviewing", 60, None)]
    assert pokemon.results == [(1, "needs_review")]
    assert pokemon.suggestions[1] == []


def test_game_without_recognition_integration_fails_before_image_processing():
    store = FakeScanRepository(_scan_rows(1), game="unknown_game")
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

    assert runtime._resolvers == {}


def test_corrupt_image_failure_propagates_without_marking_row_finished():
    store = FakeScanRepository(_scan_rows(1))
    with pytest.raises(ValueError, match="unsupported image format"):
        _worker(store, images=FakeImages(b"not a jpeg")).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.rows[0].status == "pending"
    assert store.results == []


def test_truncated_jpeg_failure_propagates_without_marking_row_finished():
    store = FakeScanRepository(_scan_rows(1))
    with pytest.raises(ValueError, match="unable to decode image"):
        _worker(store, images=FakeImages(b"\xff\xd8\xff\xe0broken")).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.rows[0].status == "pending"
    assert store.results == []


def test_unsupported_image_failure_propagates_without_marking_row_finished():
    output = io.BytesIO()
    Image.new("RGB", (2, 2), color=(10, 20, 30)).save(output, format="PNG")
    store = FakeScanRepository(_scan_rows(1))

    with pytest.raises(ValueError, match="unsupported image format"):
        _worker(store, images=FakeImages(output.getvalue())).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.rows[0].status == "pending"
    assert store.results == []


def test_image_store_failure_propagates_without_marking_row_finished():
    class BrokenImages:
        def read(self, key: str) -> bytes:
            raise RuntimeError("s3 unavailable")

    store = FakeScanRepository(_scan_rows(1))

    with pytest.raises(RuntimeError, match="s3 unavailable"):
        _worker(store, images=BrokenImages()).handle_message(
            {"user": "jordan", "scan_id": "scan-1"}
        )

    assert store.rows[0].status == "pending"
    assert store.results == []


def test_empty_candidates_become_manual_review():
    store = FakeScanRepository(_scan_rows(1))
    _worker(store, catalog=FakeCatalog([])).handle_message(
        {"user": "jordan", "scan_id": "scan-1"}
    )

    assert store.rows[0].status == "needs_review"


def test_catalog_initialization_failure_propagates_without_marking_rows():
    store = FakeScanRepository(_scan_rows(2))

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
    store = FakeScanRepository(_scan_rows(1))
    store.scan = None
    worker = _worker(store)

    worker.handle_message({"user": "jordan", "scan_id": "scan-1"})

    assert store.results == []


def test_continuation_failure_leaves_pending_rows_for_recovery():
    store = FakeScanRepository(_scan_rows(101))
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
