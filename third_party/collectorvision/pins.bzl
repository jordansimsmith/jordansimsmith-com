"""Pinned CollectorVision source, catalog, and model metadata."""

COLLECTORVISION_COMMIT = "2a122d00d25c8d112a90e47bf235a021e0c53b0c"
COLLECTORVISION_SOURCE_URL = "https://github.com/HanClinto/CollectorVision"
COLLECTORVISION_LICENSE_URL = "https://raw.githubusercontent.com/HanClinto/CollectorVision/{}/LICENSE".format(COLLECTORVISION_COMMIT)
COLLECTORVISION_LICENSE_SHA256 = "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0"

CATALOG_VERSION = 40
CATALOG_ROWS = 113031
CATALOG_SOURCE_UPDATED_AT = "2026-09-21T09:05:30.034Z"
CATALOG_RECORDS_URL = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/scryfall-mtg/version/40/base/records.jsonl.gz"
CATALOG_RECORDS_SIZE = 9043401
CATALOG_RECORDS_SHA256 = "cd566e2d103c532c9e79644e152496a53494a9b04b3cd1a43c5baf3e8589bff9"
CATALOG_EMBEDDINGS_URL = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/scryfall-mtg/version/40/base/embeddings.f16.gz"
CATALOG_EMBEDDINGS_SIZE = 26451329
CATALOG_EMBEDDINGS_SHA256 = "bbe2eab30e2db5d1c2811c2429cecb9435a6d537319bd82c521733407becdd40"

MILO_URL = "https://raw.githubusercontent.com/HanClinto/CollectorVision/{}/collector_vision/weights/milo.onnx".format(COLLECTORVISION_COMMIT)
MILO_SHA256 = "bd13d8d60383c69da04dce261f32e93fdaeaa8fd618fbc991e7385f71b3d45df"
MILO_SIZE = 5191100

EMBEDDING = {
    "model": "collectorvision@9d45a37ebfe40f22ece70507015645de134dc3ec:milo-1.0.0@sha256:{}".format(MILO_SHA256),
    "dimensions": 128,
    "dtype": "float16",
    "byte_order": "little",
    "layout": "row-major",
}
