"""CollectorVision pins, cache metadata, downloads, and image macro."""

load("@rules_oci//oci:defs.bzl", "oci_image", "oci_load")
load("@rules_pkg//:pkg.bzl", "pkg_tar")

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

_CATALOG_DESCRIPTOR = {
    "game": "magic-the-gathering",
    "source": "scryfall",
    "profile": "default",
    "description": "English-first paper printings from Scryfall default cards.",
    "result_identifier": "scryfall_card",
    "recommended": True,
}

_EMBEDDING = {
    "model": "collectorvision@9d45a37ebfe40f22ece70507015645de134dc3ec:milo-1.0.0@sha256:{}".format(MILO_SHA256),
    "dimensions": 128,
    "dtype": "float16",
    "byte_order": "little",
    "layout": "row-major",
}

def _feed():
    return {
        "checked_at": CATALOG_SOURCE_UPDATED_AT,
        "families": {
            "milo1": {
                "embedding": _EMBEDDING,
                "catalogs": {
                    "scryfall/mtg": {
                        "public_name": "scryfall-mtg",
                        "descriptor": _CATALOG_DESCRIPTOR,
                        "current_version": CATALOG_VERSION,
                        "rows": CATALOG_ROWS,
                        "source_updated_at": CATALOG_SOURCE_UPDATED_AT,
                        "base": {
                            "version": CATALOG_VERSION,
                            "rows": CATALOG_ROWS,
                            "source_updated_at": CATALOG_SOURCE_UPDATED_AT,
                            "assets": {
                                "records": {
                                    "url": CATALOG_RECORDS_URL,
                                    "size": CATALOG_RECORDS_SIZE,
                                    "sha256": CATALOG_RECORDS_SHA256,
                                },
                                "embeddings": {
                                    "url": CATALOG_EMBEDDINGS_URL,
                                    "size": CATALOG_EMBEDDINGS_SIZE,
                                    "sha256": CATALOG_EMBEDDINGS_SHA256,
                                },
                            },
                        },
                        "updates": {},
                    },
                },
            },
        },
    }

def _snapshot():
    return {
        "schema": 2,
        "catalog_key": "milo1/scryfall/mtg",
        "family": "milo1",
        "version": CATALOG_VERSION,
        "rows": CATALOG_ROWS,
        "embedding": _EMBEDDING,
        "descriptor": _CATALOG_DESCRIPTOR,
        "metadata_loaded": True,
        "assets": {
            "records": {
                "filename": "records.jsonl.gz",
                "size": CATALOG_RECORDS_SIZE,
                "sha256": CATALOG_RECORDS_SHA256,
            },
            "embeddings": {
                "filename": "embeddings.f16.gz",
                "size": CATALOG_EMBEDDINGS_SIZE,
                "sha256": CATALOG_EMBEDDINGS_SHA256,
            },
        },
    }

def _cache_impl(ctx):
    feed = ctx.outputs.feed
    snapshot = ctx.outputs.snapshot
    ctx.actions.write(output = feed, content = json.encode(_feed()))
    ctx.actions.write(output = snapshot, content = json.encode(_snapshot()))
    return [DefaultInfo(files = depset([feed, snapshot]))]

collectorvision_cache = rule(
    implementation = _cache_impl,
    attrs = {
        "feed": attr.output(mandatory = True),
        "snapshot": attr.output(mandatory = True),
    },
)

def _download_impl(repository_ctx):
    repository_ctx.download(
        output = repository_ctx.attr.filename,
        sha256 = repository_ctx.attr.sha256,
        url = repository_ctx.attr.urls,
    )
    repository_ctx.file(
        "BUILD.bazel",
        "exports_files([\"{}\"])\n".format(repository_ctx.attr.filename),
    )

collectorvision_download = repository_rule(
    implementation = _download_impl,
    attrs = {
        "filename": attr.string(mandatory = True),
        "sha256": attr.string(mandatory = True),
        "urls": attr.string_list(mandatory = True),
    },
)

def _repositories(module_ctx):
    collectorvision_download(
        name = "collectorvision_catalog_records",
        filename = "records.jsonl.gz",
        sha256 = CATALOG_RECORDS_SHA256,
        urls = [CATALOG_RECORDS_URL],
    )
    collectorvision_download(
        name = "collectorvision_catalog_embeddings",
        filename = "embeddings.f16.gz",
        sha256 = CATALOG_EMBEDDINGS_SHA256,
        urls = [CATALOG_EMBEDDINGS_URL],
    )
    collectorvision_download(
        name = "collectorvision_milo",
        filename = "milo.onnx",
        sha256 = MILO_SHA256,
        urls = [MILO_URL],
    )
    collectorvision_download(
        name = "collectorvision_license",
        filename = "LICENSE",
        sha256 = COLLECTORVISION_LICENSE_SHA256,
        urls = [COLLECTORVISION_LICENSE_URL],
    )

collectorvision = module_extension(
    implementation = _repositories,
    tag_classes = {},
)

def collectorvision_image(name, base, handler, repo_tag):
    """Define the CollectorVision Lambda image and its minimal offline entrypoint."""
    cache_metadata = name + "-cache-metadata"
    cache_layer = name + "-cache"
    model = name + "-model"
    license_layer = name + "-license"
    dependencies = name + "-dependencies"
    application = name + "-application"

    collectorvision_cache(
        name = cache_metadata,
        feed = name + "-feed.json",
        snapshot = name + "-snapshot.json",
    )

    python_package_layers = []
    for package in [
        "boto3",
        "botocore",
        "collectorvision",
        "jmespath",
        "numpy",
        "opencv_python_headless",
        "pillow",
        "onnxruntime",
        "packaging",
        "flatbuffers",
        "coloredlogs",
        "humanfriendly",
        "protobuf",
        "python_dateutil",
        "s3transfer",
        "six",
        "sympy",
        "mpmath",
        "urllib3",
    ]:
        package_layer = name + "-" + package.replace("_", "-") + "-dependencies"
        pkg_tar(
            name = package_layer,
            srcs = ["@pypi//{}:extracted_whl_files".format(package)],
            package_dir = "/var/task",
            strip_prefix = "/external/rules_python++pip+pypi_312_{}/site-packages".format(package),
        )
        python_package_layers.append(":" + package_layer)

    pkg_tar(
        name = dependencies,
        deps = python_package_layers,
        package_dir = "/",
    )

    pkg_tar(
        name = application,
        files = {
            "src/main/python/tcg_inventory_scan_worker/__init__.py": "tcg_inventory_scan_worker/__init__.py",
            "src/main/python/tcg_inventory_scan_worker/worker.py": "tcg_inventory_scan_worker/worker.py",
        },
        package_dir = "/var/task",
    )

    pkg_tar(
        name = cache_layer,
        files = {
            ":" + name + "-feed.json": "catalog-v2/feed.json",
            ":" + name + "-snapshot.json": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-40/snapshot.json",
            "@collectorvision_catalog_records//:records.jsonl.gz": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-40/records.jsonl.gz",
            "@collectorvision_catalog_embeddings//:embeddings.f16.gz": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-40/embeddings.f16.gz",
        },
        package_dir = "/opt/collectorvision",
    )

    pkg_tar(
        name = model,
        files = {
            "@collectorvision_milo//:milo.onnx": "models/{}/model.onnx".format(MILO_SHA256),
        },
        package_dir = "/opt/collectorvision",
    )

    pkg_tar(
        name = license_layer,
        files = {
            "@collectorvision_license//:LICENSE": "usr/share/licenses/collectorvision/LICENSE",
        },
    )

    oci_image(
        name = name,
        base = base,
        cmd = [handler],
        env = {
            "COLLECTORVISION_CACHE": "/opt/collectorvision",
        },
        labels = {
            "org.opencontainers.image.licenses": "AGPL-3.0-or-later",
            "org.opencontainers.image.revision": COLLECTORVISION_COMMIT,
            "org.opencontainers.image.source": COLLECTORVISION_SOURCE_URL,
            "org.opencontainers.image.version": COLLECTORVISION_COMMIT,
            "org.jordansimsmith.collectorvision.catalog-version": str(CATALOG_VERSION),
            "org.jordansimsmith.collectorvision.model-size": str(MILO_SIZE),
            "org.jordansimsmith.collectorvision.model-sha256": MILO_SHA256,
        },
        tars = [
            ":" + dependencies,
            ":" + cache_layer,
            ":" + model,
            ":" + license_layer,
            ":" + application,
        ],
    )

    oci_load(
        name = name + "-load",
        image = ":" + name,
        repo_tags = [repo_tag],
        testonly = True,
    )
