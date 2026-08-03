# Scan cards

These scripts provide a local, method-agnostic spike environment for identifying
Magic: The Gathering cards from photos and comparing recognition approaches
against a manually verified dataset.

## Workflow

1. Cache Scryfall metadata and the reference artwork needed by a predictor.
2. Run a predictor over every labelled image and write ranked predictions as
   JSON Lines.
3. Evaluate every predictor with the same exact-printing, card-identity,
   detection, ranking, and latency metrics.
4. Compare per-image errors before tuning any one method.

The evaluator is deliberately separate from predictors. A failed detection must
still produce one output record, which prevents methods from improving reported
accuracy by silently omitting difficult images.

## Independent spikes

Each spike owns its implementation, tests, Python environment, dependency lock,
and operating instructions. Spikes do not import code from one another:

- [`fixed_phash`](fixed_phash/README.md) performs fixture-specific artwork
  cropping and DCT perceptual-hash retrieval.
- [`rapidocr`](rapidocr/README.md) reads title and collector regions with local
  ONNX OCR models and fuzzy-matches local Scryfall metadata.
- [`orb_rerank`](orb_rerank/README.md) uses an internal pHash shortlist and
  reranks it with ORB keypoints plus geometric verification.
- [`collectorvision`](collectorvision/README.md) wraps CollectorVision's learned
  corner detector, perspective correction, image embedder, and MTG catalogue.

The common dataset, Scryfall cache format, and prediction/evaluation contract
are infrastructure rather than predictor code.

## Development benchmark

The 3 August 2026 run used all 51 fixture photos and 120,410 Scryfall face
images where a reference corpus was required:

- CollectorVision: 90.2% exact-printing top-1, 98.0% Oracle-card top-1, and
  313 ms median latency.
- RapidOCR: 45.1% exact-printing top-1, 62.7% Oracle-card top-1, and 2,613 ms
  median latency.
- ORB reranking with a 100-image pHash shortlist: 21.6% exact-printing top-1,
  29.4% Oracle-card top-1, and 3,235 ms median latency.
- Fixed artwork pHash: 2.0% exact-printing and Oracle-card top-1, with 155 ms
  median latency.

The ORB matcher reached 100% top-1 when the reference corpus was restricted to
the labelled cards, showing that its full-corpus bottleneck is pHash candidate
generation. Restricted-corpus figures are diagnostics, not valid accuracy
benchmarks.

Treat the 51 examples as a development set: fixture crop choices were inspected
while implementing the spikes. A held-out set with varied lighting, glare,
sleeves, rotations, and backgrounds is required for an unbiased estimate.

## Dataset

`dataset/labels.csv` contains 51 manually verified examples covering 50 unique
Scryfall printings. `dataset/images` contains the JPEG inputs. The unmodified
HEIC photos and original ManaBox export are retained locally under the ignored
`tmp/tcg-scan/dataset/raw` directory.

The current fixture is controlled: cards are centred in a consistent white
tray. Results therefore measure performance for this scanning setup, not
general handheld or tabletop photography.

## Scryfall cache

Download the compressed default-card metadata:

```shell
bazel run //tcg_lister_api:scan-sync-scryfall -- \
  tmp/tcg-scan/scryfall \
  --bulk-type default_cards
```

Download reference images for the full metadata corpus:

```shell
bazel run //tcg_lister_api:scan-sync-scryfall -- \
  tmp/tcg-scan/scryfall \
  --bulk-type default_cards \
  --download-artwork \
  --image-version small \
  --workers 8
```

Use `small` images for the first broad comparison and `normal` when a method
needs more detail. At the current catalogue size, expect approximately 1.5 GiB
for `small` images and more than 10 GiB for `normal`. Downloads are resumable
because existing non-empty image files are reused.

For a quick plumbing check, restrict artwork to the labelled cards:

```shell
bazel run //tcg_lister_api:scan-sync-scryfall -- \
  tmp/tcg-scan/scryfall \
  --bulk-type default_cards \
  --download-artwork \
  --image-version normal \
  --labels tcg_lister_api/scripts/scan/dataset/labels.csv
```

That restricted corpus is not a valid accuracy benchmark: it tells the
predictor the set of correct answers in advance.

Useful corpus choices are:

- `default_cards` plus full-card images for exact-printing retrieval.
- `unique_artwork` plus `art_crop` images for a smaller artwork-only search.
  Artwork-only results should primarily be judged by Oracle card identity
  because identical artwork cannot determine an exact printing.
- `all_cards` only when multilingual printing recognition is being measured.

## Prediction contract

Each predictor writes one JSON object per line and exactly one line per labelled
image:

```json
{
  "image": "images/IMG_0717.jpg",
  "detected": true,
  "latency_ms": 42.5,
  "predictions": [
    { "scryfall_id": "b1463082-62bb-4aa6-8763-b865ef1fdb68", "score": 0.94 },
    { "scryfall_id": "another-id", "score": 0.81 }
  ]
}
```

- `image` must equal the value in `labels.csv`.
- `detected` records whether the card boundary was found. It defaults to true
  when ranked predictions are present.
- `latency_ms` is total per-image predictor latency.
- `predictions` must be ordered best first and contain comparable scores within
  that predictor.
- A failed image uses an empty `predictions` array and may include an `error`
  string.

## Evaluation

Run the common evaluator:

```shell
bazel run //tcg_lister_api:scan-evaluate -- \
  tcg_lister_api/scripts/scan/dataset/labels.csv \
  /path/to/predictions.jsonl \
  --scryfall-metadata tmp/tcg-scan/scryfall/metadata/default_cards.jsonl.gz \
  --output tmp/tcg-scan/report.json
```

The report includes:

- Card-boundary detection rate.
- Exact Scryfall printing top-1, top-3, and top-5 accuracy.
- Oracle card top-1, top-3, and top-5 accuracy, which accepts another printing
  of the same card.
- Mean reciprocal rank.
- End-to-end accuracy over all inputs and conditional accuracy among detected
  inputs.
- Mean, median, and p95 latency.
- Per-image ranks, scores, errors, and expected identities.

## Practical recognition options

The implemented spikes and practical follow-up families are:

1. **Fixed-region template retrieval.** Crop the known card rectangle from this
   physical fixture and compare it with normalized Scryfall images using pixel
   correlation or structural similarity. This is fixture-specific rather than
   general computer vision, but it is a practical lower-complexity option.
2. **Perceptual hashes.** Dewarp the card or crop its artwork, calculate pHash,
   dHash, or wavelet hash, and rank references by Hamming distance. This is a
   classical image-retrieval baseline.
3. **Local visual features.** Match ORB, SIFT, or RootSIFT keypoints and use
   geometric consistency to reject accidental matches. This is standard
   computer vision and is more tolerant of partial glare and perspective than
   a global hash.
4. **OCR-only retrieval.** Read the title, then fuzzy-match card names from
   local Scryfall metadata. A second variant reads the MTG-specific collector
   number and set code from the bottom strip to identify the printing.
5. **CollectorVision.** Use its learned corner detector and MTG-specific
   embedding catalogue as a ready-made neural retrieval baseline.
6. **Generic learned embeddings.** Index Scryfall references with CLIP,
   SigLIP, or DINOv2 and use FAISS or another nearest-neighbour index. This
   tests whether a general visual representation is sufficient without
   card-specific training.
7. **Local vision-language model.** Prompt a local multimodal model to
   transcribe card identity. This is slower and less deterministic, but useful
   as an independent fallback comparison rather than the primary scanner.
8. **Hybrid cascade.** Use OCR to narrow candidates, artwork retrieval to rank
   them, and collector-number OCR to resolve same-art printings. This reflects
   common production retrieval architecture, but should follow the isolated
   spikes so each component's contribution remains measurable.

Card boundary detection followed by a perspective transform is standard
computer-vision practice. Scryfall candidate construction, artwork reuse,
collector-number parsing, split cards, and double-faced cards are MTG-specific
concerns. The fixed-region baseline is specific to this scanning fixture.

## Dataset expansion

Before tuning thresholds, add a development split containing repeated photos
with controlled changes in angle, distance, rotation, lighting, glare, sleeves,
and background. Keep every photo of one physical card in the same split. Add
same-art reprints, foils, older frames, borderless treatments, and double-faced
cards to make exact-printing limitations visible.
