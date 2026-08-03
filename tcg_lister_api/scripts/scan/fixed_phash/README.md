# Fixed artwork pHash spike

This self-contained spike exploits the fixed white scanning fixture. It finds
the dark card rectangle inside a known region, crops the approximate artwork,
calculates a 64-bit perceptual hash, and ranks locally cached Scryfall
references by Hamming distance.

The fixed search region is specific to this photo setup. The DCT-based
perceptual hash and Hamming-distance retrieval are classical computer-vision
techniques. The normalized artwork crop is MTG-specific.

## Environment

```shell
uv sync --project tcg_lister_api/scripts/scan/fixed_phash
```

This directory owns its implementation, tests, dependencies, and lock file. It
does not import code from another spike.

## Build the reference index

```shell
uv run --project tcg_lister_api/scripts/scan/fixed_phash \
  python tcg_lister_api/scripts/scan/fixed_phash/main.py build-index \
  tmp/tcg-scan/scryfall/artwork/small/index.jsonl \
  tmp/tcg-scan/fixed-phash/reference-hashes.jsonl
```

## Predict

```shell
uv run --project tcg_lister_api/scripts/scan/fixed_phash \
  python tcg_lister_api/scripts/scan/fixed_phash/main.py predict \
  tcg_lister_api/scripts/scan/dataset/labels.csv \
  tcg_lister_api/scripts/scan/dataset \
  tmp/tcg-scan/fixed-phash/reference-hashes.jsonl \
  tmp/tcg-scan/fixed-phash/predictions.jsonl \
  --debug-crops tmp/tcg-scan/fixed-phash/crops
```

Use the shared evaluator only after this spike has produced predictions. A
reference index restricted to the 51 labelled cards is useful for plumbing but
is not a fair accuracy measurement.

## Test

```shell
uv run --project tcg_lister_api/scripts/scan/fixed_phash \
  --group dev pytest tcg_lister_api/scripts/scan/fixed_phash/test_main.py
```
