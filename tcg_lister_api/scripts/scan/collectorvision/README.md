# CollectorVision spike

This self-contained spike wraps CollectorVision's learned card-corner detector,
perspective correction, 128-dimensional image embedder, and prebuilt
approximately 108,000-card Scryfall catalogue. It tries both upright and
180-degree orientations and keeps the ranking with the strongest top score.

This is a card-specific learned retrieval system rather than an implementation
invented in this repository. CollectorVision is alpha software licensed under
AGPL-3.0-or-later; the dependency is pinned to an exact Git revision so this
spike remains reproducible.

## Environment

```shell
uv sync --project tcg_lister_api/scripts/scan/collectorvision
```

This directory owns its wrapper, tests, dependencies, and lock file. It does
not import code from another spike.

## Predict

```shell
uv run --project tcg_lister_api/scripts/scan/collectorvision \
  python tcg_lister_api/scripts/scan/collectorvision/main.py \
  tcg_lister_api/scripts/scan/dataset/labels.csv \
  tcg_lister_api/scripts/scan/dataset \
  tmp/tcg-scan/collectorvision/predictions.jsonl \
  --cache-dir tmp/tcg-scan/collectorvision/cache \
  --debug-crops tmp/tcg-scan/collectorvision/crops
```

The model and catalogue are downloaded once into the specified ignored cache.

## Test

```shell
uv run --project tcg_lister_api/scripts/scan/collectorvision \
  --group dev pytest tcg_lister_api/scripts/scan/collectorvision/test_main.py
```
