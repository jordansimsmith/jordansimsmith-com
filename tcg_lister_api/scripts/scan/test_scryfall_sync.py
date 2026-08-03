import csv
import gzip
import io
import json

from scryfall_sync import load_label_card_ids, sync_artwork, sync_bulk_metadata


class FakeResponse:
    def __init__(self, json_body=None, content=b""):
        self._json_body = json_body
        self.content = content

    def raise_for_status(self):
        return None

    def json(self):
        return self._json_body

    def iter_content(self, chunk_size):
        del chunk_size
        yield self.content


class FakeSession:
    def __init__(self, responses):
        self.responses = {url: list(items) for url, items in responses.items()}
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses[url].pop(0)


def _gzip_jsonl(records):
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb") as gzip_file:
        for record in records:
            gzip_file.write((json.dumps(record) + "\n").encode())
    return output.getvalue()


def testSyncBulkMetadataShouldDownloadAndReuseCurrentBulkFile(tmp_path):
    # arrange
    api_url = "https://api.scryfall.com/bulk-data/default_cards"
    download_url = "https://data.scryfall.io/default-cards.jsonl.gz"
    bulk = {
        "type": "default_cards",
        "updated_at": "2026-08-02T21:13:58.746+00:00",
        "jsonl_download_uri": download_url,
    }
    content = _gzip_jsonl([{"id": "id-a", "oracle_id": "oracle-a"}])
    first_session = FakeSession(
        {
            api_url: [FakeResponse(json_body=bulk)],
            download_url: [FakeResponse(content=content)],
        }
    )

    # act
    metadata_path = sync_bulk_metadata(tmp_path, "default_cards", session=first_session)
    second_session = FakeSession({api_url: [FakeResponse(json_body=bulk)]})
    cached_path = sync_bulk_metadata(tmp_path, "default_cards", session=second_session)

    # assert
    assert cached_path == metadata_path
    with gzip.open(metadata_path, "rt") as metadata_file:
        assert json.loads(metadata_file.readline())["id"] == "id-a"
    descriptor = json.loads(
        (tmp_path / "metadata" / "default_cards.bulk.json").read_text()
    )
    assert descriptor == bulk
    assert [call[0] for call in first_session.calls] == [api_url, download_url]
    assert [call[0] for call in second_session.calls] == [api_url]


def testSyncArtworkShouldDownloadSingleAndDoubleFacedCards(tmp_path):
    # arrange
    metadata_path = tmp_path / "cards.jsonl.gz"
    metadata_path.write_bytes(
        _gzip_jsonl(
            [
                {
                    "id": "id-a",
                    "image_uris": {"normal": "https://cards.scryfall.io/a.jpg"},
                },
                {
                    "id": "id-b",
                    "card_faces": [
                        {
                            "name": "Front",
                            "image_uris": {
                                "normal": "https://cards.scryfall.io/b-front.jpg"
                            },
                        },
                        {
                            "name": "Back",
                            "image_uris": {
                                "normal": "https://cards.scryfall.io/b-back.jpg"
                            },
                        },
                    ],
                },
                {"id": "id-c"},
            ]
        )
    )
    session = FakeSession(
        {
            "https://cards.scryfall.io/a.jpg": [FakeResponse(content=b"a-image")],
            "https://cards.scryfall.io/b-front.jpg": [
                FakeResponse(content=b"b-front-image")
            ],
            "https://cards.scryfall.io/b-back.jpg": [
                FakeResponse(content=b"b-back-image")
            ],
        }
    )

    # act
    result = sync_artwork(
        metadata_path,
        tmp_path,
        "normal",
        session=session,
        workers=1,
    )

    # assert
    assert result["cards_considered"] == 3
    assert result["images_available"] == 3
    assert result["downloaded"] == 3
    assert result["cached"] == 0
    assert result["cards_without_images"] == 1
    index_path = tmp_path / "artwork" / "normal" / "index.jsonl"
    index = [json.loads(line) for line in index_path.read_text().splitlines()]
    assert index == [
        {
            "scryfall_id": "id-a",
            "face": "front",
            "path": "images/id-a.jpg",
        },
        {
            "scryfall_id": "id-b",
            "face": "front",
            "path": "images/id-b-front.jpg",
        },
        {
            "scryfall_id": "id-b",
            "face": "back",
            "path": "images/id-b-back.jpg",
        },
    ]
    assert (tmp_path / "artwork" / "normal" / "images" / "id-a.jpg").read_bytes() == (
        b"a-image"
    )


def testLoadLabelCardIdsShouldReturnUniqueIdsInLabelOrder(tmp_path):
    # arrange
    labels_path = tmp_path / "labels.csv"
    with labels_path.open("w", newline="") as labels_file:
        writer = csv.DictWriter(labels_file, fieldnames=["Scryfall ID"])
        writer.writeheader()
        writer.writerows(
            [
                {"Scryfall ID": "id-b"},
                {"Scryfall ID": "id-a"},
                {"Scryfall ID": "id-b"},
            ]
        )

    # act
    card_ids = load_label_card_ids(labels_path)

    # assert
    assert card_ids == ["id-b", "id-a"]
