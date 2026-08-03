# RapidOCR spike

This self-contained spike detects the card inside the fixed fixture, runs local
RapidOCR models over the title and collector-information regions, fuzzy-matches
the title against local Scryfall metadata, and boosts printings whose collector
number and set code appear in the lower text.

Text detection and recognition with ONNX models is general OCR practice.
Cropping the MTG title bar and parsing collector fractions/set codes are
MTG-specific. The fixed card search region is specific to this fixture.

## Environment

```shell
uv sync --project tcg_lister_api/scripts/scan/rapidocr
```

RapidOCR and ONNX Runtime run locally. This directory owns its implementation,
tests, dependencies, and lock file and does not import code from another spike.

## Predict

```shell
uv run --project tcg_lister_api/scripts/scan/rapidocr \
  python tcg_lister_api/scripts/scan/rapidocr/main.py \
  tcg_lister_api/scripts/scan/dataset/labels.csv \
  tcg_lister_api/scripts/scan/dataset \
  tmp/tcg-scan/scryfall/metadata/default_cards.jsonl.gz \
  tmp/tcg-scan/rapidocr/predictions.jsonl \
  --debug-crops tmp/tcg-scan/rapidocr/crops
```

The spike emits both ranked Scryfall IDs and its raw title/bottom OCR strings.
This makes OCR failures distinguishable from fuzzy-matching or printing-rank
failures.

## Test

```shell
uv run --project tcg_lister_api/scripts/scan/rapidocr \
  --group dev pytest tcg_lister_api/scripts/scan/rapidocr/test_main.py
```
