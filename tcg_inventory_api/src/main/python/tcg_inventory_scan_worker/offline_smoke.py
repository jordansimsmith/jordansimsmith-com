"""Minimal offline CollectorVision image check."""

import json
import os

import onnxruntime

from collector_vision import Catalog, __version__


def lambda_handler(event, context):
    del event, context
    catalog = Catalog.load(
        "mtg",
        source="scryfall",
        family="milo1",
        cache_dir=os.environ["COLLECTORVISION_CACHE"],
        offline=True,
        version=40,
    )
    if "CPUExecutionProvider" not in onnxruntime.get_available_providers():
        raise RuntimeError("ONNX Runtime does not provide CPUExecutionProvider")
    embedder = catalog.embedder
    return {
        "collectorvision_version": __version__,
        "catalog_version": catalog.version,
        "catalog_rows": len(catalog),
        "embedder": type(embedder).__name__,
        "onnxruntime_version": onnxruntime.__version__,
        "onnxruntime_providers": onnxruntime.get_available_providers(),
    }


def main():
    print(json.dumps(lambda_handler({}, None), sort_keys=True))


if __name__ == "__main__":
    main()
