"""CollectorVision cache metadata and Lambda image assembly."""

load("@rules_oci//oci:defs.bzl", "oci_image", "oci_load")
load("@rules_pkg//:pkg.bzl", "pkg_tar")
load("@rules_pkg//pkg:mappings.bzl", "REMOVE_BASE_DIRECTORY", "pkg_attributes", "pkg_files")
load("@rules_python//python:pip.bzl", "whl_filegroup")
load(
    "//third_party/collectorvision:pins.bzl",
    "CATALOG_EMBEDDINGS_SHA256",
    "CATALOG_EMBEDDINGS_SIZE",
    "CATALOG_EMBEDDINGS_URL",
    "CATALOG_RECORDS_SHA256",
    "CATALOG_RECORDS_SIZE",
    "CATALOG_RECORDS_URL",
    "CATALOG_ROWS",
    "CATALOG_SOURCE_UPDATED_AT",
    "CATALOG_VERSION",
    "COLLECTORVISION_COMMIT",
    "COLLECTORVISION_SOURCE_URL",
    "EMBEDDING",
    "MILO_SHA256",
    "MILO_SIZE",
)

_CATALOG_DESCRIPTOR = {
    "game": "magic-the-gathering",
    "source": "scryfall",
    "profile": "default",
    "description": "English-first paper printings from Scryfall default cards.",
    "result_identifier": "scryfall_card",
    "recommended": True,
}

def _feed():
    return {
        "checked_at": CATALOG_SOURCE_UPDATED_AT,
        "families": {
            "milo1": {
                "embedding": EMBEDDING,
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
        "embedding": EMBEDDING,
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

def collectorvision_image(name, base, handler, repo_tag, application_files):
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
        whl_filegroup(
            name = package_layer + "-wheel",
            whl = "@pypi//{}:whl".format(package),
        )
        pkg_files(
            name = package_layer + "-files",
            srcs = [":" + package_layer + "-wheel"],
            attributes = pkg_attributes(mode = "0555"),
            prefix = "var/task",
            renames = {":" + package_layer + "-wheel": REMOVE_BASE_DIRECTORY},
        )
        pkg_tar(
            name = package_layer,
            srcs = [":" + package_layer + "-files"],
        )
        python_package_layers.append(":" + package_layer)

    pkg_tar(
        name = dependencies,
        deps = python_package_layers,
        package_dir = "/",
    )

    pkg_tar(
        name = application,
        files = application_files,
        package_dir = "/var/task",
    )

    pkg_tar(
        name = cache_layer,
        files = {
            ":" + name + "-feed.json": "catalog-v2/feed.json",
            ":" + name + "-snapshot.json": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-{}/snapshot.json".format(CATALOG_VERSION),
            "@collectorvision_catalog_records//file": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-{}/records.jsonl.gz".format(CATALOG_VERSION),
            "@collectorvision_catalog_embeddings//file": "catalog-v2/snapshots/milo1--scryfall--mtg/metadata/version-{}/embeddings.f16.gz".format(CATALOG_VERSION),
        },
        package_dir = "/opt/collectorvision",
    )

    pkg_tar(
        name = model,
        files = {
            "@collectorvision_milo//file": "models/{}/model.onnx".format(MILO_SHA256),
        },
        package_dir = "/opt/collectorvision",
    )

    pkg_tar(
        name = license_layer,
        files = {
            "@collectorvision_license//file": "usr/share/licenses/collectorvision/LICENSE",
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
