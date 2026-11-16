"""Download the pinned CollectorVision assets."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
load(
    ":pins.bzl",
    "CATALOGS",
    "COLLECTORVISION_LICENSE_SHA256",
    "COLLECTORVISION_LICENSE_URL",
    "MILO_SHA256",
    "MILO_URL",
)

def _repositories(module_ctx):
    for catalog in CATALOGS.values():
        for asset in catalog.assets.values():
            http_file(
                name = asset.repository,
                downloaded_file_path = asset.filename,
                sha256 = asset.sha256,
                urls = [asset.url],
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
