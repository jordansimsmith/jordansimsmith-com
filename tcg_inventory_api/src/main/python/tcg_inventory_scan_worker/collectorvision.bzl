"""CollectorVision cache metadata and Lambda image assembly."""

load("@rules_oci//oci:defs.bzl", "oci_image", "oci_load")
load("@rules_pkg//:pkg.bzl", "pkg_tar")
load("@rules_pkg//pkg:mappings.bzl", "REMOVE_BASE_DIRECTORY", "pkg_attributes", "pkg_files")
load("@rules_python//python:pip.bzl", "whl_filegroup")
load(
    "//third_party/collectorvision:pins.bzl",
    "CATALOGS",
    "COLLECTORVISION_COMMIT",
    "COLLECTORVISION_SOURCE_URL",
    "EMBEDDING",
    "MILO_SHA256",
    "MILO_SIZE",
)

def _feed_entry(catalog):
    assets = {}
    for asset_name, asset in catalog.assets.items():
        assets[asset_name] = {
            "url": asset.url,
            "size": asset.size,
            "sha256": asset.sha256,
        }
    return {
        "public_name": catalog.public_name,
        "descriptor": catalog.descriptor,
        "current_version": catalog.version,
        "rows": catalog.rows,
        "source_updated_at": catalog.source_updated_at,
        "base": {
            "version": catalog.version,
            "rows": catalog.rows,
            "source_updated_at": catalog.source_updated_at,
            "assets": assets,
        },
        "updates": {},
    }

def _feed():
    return {
        "checked_at": max([catalog.source_updated_at for catalog in CATALOGS.values()]),
        "families": {
            "milo1": {
                "embedding": EMBEDDING,
                "catalogs": {key: _feed_entry(catalog) for key, catalog in CATALOGS.items()},
            },
        },
    }

def _snapshot(key, catalog):
    assets = {}
    for asset_name, asset in catalog.assets.items():
        assets[asset_name] = {
            "filename": asset.filename,
            "size": asset.size,
            "sha256": asset.sha256,
        }
    return {
        "schema": 2,
        "catalog_key": "milo1/" + key,
        "family": "milo1",
        "version": catalog.version,
        "rows": catalog.rows,
        "embedding": EMBEDDING,
        "descriptor": catalog.descriptor,
        "metadata_loaded": True,
        "assets": assets,
    }

def _cache_impl(ctx):
    feed = ctx.outputs.feed
    magic_snapshot = ctx.outputs.magic_snapshot
    pokemon_snapshot = ctx.outputs.pokemon_snapshot
    pokemon_japan_snapshot = ctx.outputs.pokemon_japan_snapshot
    ctx.actions.write(output = feed, content = json.encode(_feed()))
    ctx.actions.write(output = magic_snapshot, content = json.encode(_snapshot("scryfall/mtg", CATALOGS["scryfall/mtg"])))
    ctx.actions.write(output = pokemon_snapshot, content = json.encode(_snapshot("tcgplayer/pokemon", CATALOGS["tcgplayer/pokemon"])))
    ctx.actions.write(output = pokemon_japan_snapshot, content = json.encode(_snapshot("tcgplayer/pokemon-japan", CATALOGS["tcgplayer/pokemon-japan"])))
    return [DefaultInfo(files = depset([feed, magic_snapshot, pokemon_snapshot, pokemon_japan_snapshot]))]

collectorvision_cache = rule(
    implementation = _cache_impl,
    attrs = {
        "feed": attr.output(mandatory = True),
        "magic_snapshot": attr.output(mandatory = True),
        "pokemon_snapshot": attr.output(mandatory = True),
        "pokemon_japan_snapshot": attr.output(mandatory = True),
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
        magic_snapshot = name + "-snapshot.json",
        pokemon_snapshot = name + "-pokemon-snapshot.json",
        pokemon_japan_snapshot = name + "-pokemon-japan-snapshot.json",
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

        # collectorvision builds a pure python wheel from the pinned source archive.
        wheel_repository = "pypi" if package == "collectorvision" else "pypi_linux_x86_64"
        whl_filegroup(
            name = package_layer + "-wheel",
            whl = "@{}//{}:whl".format(wheel_repository, package),
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

    cache_files = {":" + name + "-feed.json": "catalog-v2/feed.json"}
    snapshot_labels = {
        "scryfall/mtg": ":" + name + "-snapshot.json",
        "tcgplayer/pokemon": ":" + name + "-pokemon-snapshot.json",
        "tcgplayer/pokemon-japan": ":" + name + "-pokemon-japan-snapshot.json",
    }
    for key, catalog in CATALOGS.items():
        path = "catalog-v2/snapshots/milo1--{}/metadata/version-{}".format(key.replace("/", "--"), catalog.version)
        cache_files[snapshot_labels[key]] = path + "/snapshot.json"
        for asset in catalog.assets.values():
            cache_files["@{}//file".format(asset.repository)] = path + "/" + asset.filename

    pkg_tar(
        name = cache_layer,
        files = cache_files,
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
            "org.jordansimsmith.collectorvision.catalog-version": str(CATALOGS["scryfall/mtg"].version),
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
