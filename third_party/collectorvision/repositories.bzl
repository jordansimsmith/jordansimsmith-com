"""Download the pinned CollectorVision assets."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
load(
    ":pins.bzl",
    "CATALOG_EMBEDDINGS_SHA256",
    "CATALOG_EMBEDDINGS_URL",
    "CATALOG_RECORDS_SHA256",
    "CATALOG_RECORDS_URL",
    "COLLECTORVISION_LICENSE_SHA256",
    "COLLECTORVISION_LICENSE_URL",
    "MILO_SHA256",
    "MILO_URL",
)

def _repositories(module_ctx):
    http_file(
        name = "collectorvision_catalog_records",
        downloaded_file_path = "records.jsonl.gz",
        sha256 = CATALOG_RECORDS_SHA256,
        urls = [CATALOG_RECORDS_URL],
    )
    http_file(
        name = "collectorvision_catalog_embeddings",
        downloaded_file_path = "embeddings.f16.gz",
        sha256 = CATALOG_EMBEDDINGS_SHA256,
        urls = [CATALOG_EMBEDDINGS_URL],
    )
    http_file(
        name = "collectorvision_milo",
        downloaded_file_path = "milo.onnx",
        sha256 = MILO_SHA256,
        urls = [MILO_URL],
    )
    http_file(
        name = "collectorvision_license",
        downloaded_file_path = "LICENSE",
        sha256 = COLLECTORVISION_LICENSE_SHA256,
        urls = [COLLECTORVISION_LICENSE_URL],
    )

collectorvision = module_extension(
    implementation = _repositories,
    tag_classes = {},
)
