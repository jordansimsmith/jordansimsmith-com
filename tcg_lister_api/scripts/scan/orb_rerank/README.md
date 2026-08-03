# ORB reranking spike

This self-contained spike crops artwork from the fixed fixture, retrieves a
perceptual-hash shortlist from the complete Scryfall image corpus, and reranks
that shortlist using ORB keypoints plus RANSAC homography inliers.

ORB descriptors, Hamming matching, Lowe-style ratio filtering, and geometric
verification are standard classical computer-vision techniques. The artwork
region and same-art printing limitation are MTG-specific. pHash is duplicated
inside this directory only as a scalable first-stage shortlist; no code is
imported from the pHash spike.

## Environment

```shell
uv sync --project tcg_lister_api/scripts/scan/orb_rerank
```

## Build the reference index

```shell
uv run --project tcg_lister_api/scripts/scan/orb_rerank \
  python tcg_lister_api/scripts/scan/orb_rerank/main.py build-index \
  tmp/tcg-scan/scryfall/artwork/small/index.jsonl \
  tmp/tcg-scan/orb-rerank/reference-index.jsonl
```

The index stores only perceptual hashes and local artwork paths. ORB descriptors
are calculated for the shortlist during prediction to keep the persistent index
small.

## Predict

```shell
uv run --project tcg_lister_api/scripts/scan/orb_rerank \
  python tcg_lister_api/scripts/scan/orb_rerank/main.py predict \
  tcg_lister_api/scripts/scan/dataset/labels.csv \
  tcg_lister_api/scripts/scan/dataset \
  tmp/tcg-scan/orb-rerank/reference-index.jsonl \
  tmp/tcg-scan/orb-rerank/predictions.jsonl \
  --shortlist 100 \
  --debug-crops tmp/tcg-scan/orb-rerank/crops
```

## Test

```shell
uv run --project tcg_lister_api/scripts/scan/orb_rerank \
  --group dev pytest tcg_lister_api/scripts/scan/orb_rerank/test_main.py
```
