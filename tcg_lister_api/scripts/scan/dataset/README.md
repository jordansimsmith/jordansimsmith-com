# Scan spike dataset

This dataset pairs 51 iPhone photos with a ManaBox CSV export from 3 August 2026.
It is intended for comparing local card-recognition spikes.

## Contents

- `tmp/tcg-scan/dataset/raw/images/*.HEIC`: unmodified source photos, stored
  outside this directory under the repository's ignored temporary data root.
- `tmp/tcg-scan/dataset/raw/manabox-scan-2026-08-03.csv`: unmodified ManaBox
  export, stored with the raw photos.
- `images/*.jpg`: JPEG conversions of the HEIC photos for libraries such as OpenCV.
- `labels.csv`: one row per JPEG with the corresponding ManaBox fields.

## Image-to-label order

The image and CSV orders are reversed. Images sort from `IMG_0717` through
`IMG_0767`, while their labels run from the bottom of the CSV to the top.
`labels.csv` records this mapping explicitly.

The mapping was visually checked at the beginning, middle, and end:

- `IMG_0717` is Shady Traveler // Stalking Predator, from CSV line 52.
- `IMG_0742` is Dauntless Onslaught, from CSV line 27.
- `IMG_0767` is Hateful Eidolon, from CSV line 2.

## Evaluation caveat

The ManaBox output was manually verified against all 51 physical cards. It is
the ground truth for this initial benchmark, although it was originally
produced by the scanner being studied.

The current photos use one controlled setup and contain 50 unique Scryfall IDs;
Giant Cockroach appears twice. Keep these examples as an initial evaluation set.
If thresholds or models are tuned, add separate development examples and keep
all photos of the same physical card in one split.
