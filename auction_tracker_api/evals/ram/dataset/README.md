# RAM judge dataset codebook

Labeled dataset for evaluating an LLM judge that filters Trade Me auction listings of DDR4 RAM. The owner is hunting a second kit identical to one they already run — G.Skill Trident Z (plain or RGB) DDR4, 2x16GB, 3200MHz, CL16-18-18-38 — so matching sticks can run together reliably. The judge decides whether a listing is worth a human look; price and undervaluation are out of scope because the judge only sees a listing's `url`, `title`, and `description`.

## Files

- `<listing_id>.json`: fixture scraped from a real Trade Me listing. Fields `url`, `title`, `description` match what `JsoupTradeMeClient.parseItemPage` extracts (same selectors, whitespace collapsing, 1000-character truncation, query params stripped). Descriptions include listing-page boilerplate (condition, shipping, payment); that boilerplate is signal, not noise.
- `s<nnn>.json`: synthetic fixture (`"synthetic": true`, url `synthetic://<id>`) authored in the same style to fill label gaps, pre-labeled by construction and human-reviewed.
- `criteria.json`: ordered list of the criterion names below, read by the eval harness.
- `labels.json`: one entry per fixture with a `pass`/`fail` label per criterion, an `overall` verdict, and optional `notes`.
- `splits.json`: train/dev/test membership (roughly 20/40/40), stratified so per-criterion fail counts and the synthetic fraction are balanced. Near-duplicate listings from the same seller share a split to avoid few-shot leakage. Train supplies few-shot prompt examples; dev is for iterating on prompts and models; test is reserved for final candidate comparison.

## Overall verdict

A listing passes only if all six criteria pass. Seller type never matters: store, SKU-style retailer, and private listings are judged on the sticks alone. Condition (new vs used) and cosmetic wear are never by themselves a reason to fail.

## Criteria

### trident_z_family

The RAM is G.Skill Trident Z, plain or RGB. Fails for Trident Z Neo, Trident Z Royal, Trident Z5 (the DDR5 family), any other G.Skill line (Ripjaws, Aegis, Flare X, Sniper, Fortis), and any other brand (Corsair, Kingston, TeamGroup, ADATA, Samsung, SK Hynix...). Model numbers are strong signal: `F4-...GTZ`/`GTZR`/`GTZRX`/similar are plain/RGB Trident Z and pass; `GTZN`\* (Neo) and Royal codes fail. "Trident Z RGB (For AMD)" / `GTZRX` is the regular RGB line binned for AMD compatibility, not Neo, and passes (owner ruling 2026-07-12). "Trident Z" naming at DDR5-era speeds (4800MHz+) means the Trident Z5 family that sellers abbreviate, and fails (owner ruling 2026-07-12). When the listing names no brand or model at all, or says only "G.Skill" with no line, default to pass — photos can settle it — but a listing positively identified as laptop SODIMM or ECC/server memory fails: Trident Z does not exist in those form factors (owner ruling 2026-07-12).

Examples: `6021068148` passes (Trident Z RGB, F4-3200C16D-32GTZRX). `5808021713` fails (Trident Z Neo). `6019323734` fails (Trident Z Royal). `6024942755` fails (Kingston Fury Beast).

### ddr4

The RAM is DDR4. DDR3 and DDR5 fail. DDR5 is often signalled by family names (Trident Z5, Ripjaws S5/M5, Flare X5) or speeds of 4800MHz and above even when "DDR5" is not spelled out. Unstated generation defaults to pass.

Examples: `6020830194` fails (Trident Z5 RGB DDR5 6000). `6028559003` fails (RipjawsX DDR3 1866). `6021068148` passes (DDR4-3200).

### kit_2x16gb

The listing is a matched kit of exactly 2x16GB (32GB total): two identical 16GB sticks sold together. Fails for other configurations even at the same total capacity (4x8GB, 1x32GB), other totals (16GB, 64GB), single sticks, and mixed lots combining different brands, models, or sizes. A factory kit or two clearly identical sticks passes; "matched" does not require the words "matched kit". Quantity is nearly always stated for RAM; fail when the configuration is genuinely indeterminable.

Examples: `5905364194` passes (2x16GB kit). `5719656155` fails (4x8GB, despite 32GB total). `6023676047` fails (1x32GB single stick). `6025547161` fails (mixed lot: Kingston 2x16GB plus SK Hynix 2x8GB).

### speed_3200

The kit is 3200MHz (DDR4-3200 / PC4-25600). Both slower (2400, 2666, 2800, 3000) and faster (3466, 3600, 4000+) fail — the goal is sticks identical to an existing 3200MHz kit, not the fastest kit. When no speed is stated anywhere, default to pass.

Examples: `6021068148` passes (DDR4-3200). `5905364194` fails (2800MHz). `5808021713` fails (3600MT/s).

### timings_cl16

The kit's timings are CL16-18-18-38 (the stock timing of the target kit). Other timings fail, including tighter ones (CL14 B-die is a different kit, not a bonus). A bare "CL16" with no secondary timings passes — 16-18-18-38 is the standard CL16 bin at 3200MHz — and so does a partial "16-18-18". A `C16` in the model code (e.g. `F4-3200C16D-32GTZR`) counts as CL16 evidence and passes. Timings are often unstated; no timing information defaults to pass.

Examples: `6021068148` passes (CL16 on a F4-3200C16D model). `6022401837` fails (CL17). `6023647468` fails (CL11-13-13, also DDR3).

### desktop_udimm

The sticks are desktop UDIMMs. Fails for laptop SODIMMs, ECC registered/load-reduced server memory (RDIMM/LRDIMM), and ECC UDIMMs. SODIMM signals: "SODIMM", "laptop RAM/memory". Server signals: "ECC", "Registered", "RDIMM", "LRDIMM", "server RAM", rank markings like "2Rx4". Unstated form factor defaults to pass (desktop UDIMM is the overwhelming default for performance kits).

Examples: `6022204522` fails (SODIMM laptop RAM). `6025671623` fails (ECC RDIMM server RAM). `6021068148` passes (desktop DIMM).

## Labeling conventions

- Labels were hand-authored by Jordan (the sole user and domain expert) from fixture text only, without LLM assistance. Synthetic fixtures are labeled by construction and were human-reviewed.
- Real fixtures follow a first-upstream-failure convention: once a criterion clearly disqualifies a listing, downstream criteria the labeler did not evaluate are `null` and the harness skips them when scoring. Synthetic fixtures are labeled on every criterion (they are constructed, so every label is known).
- Attribute criteria with optimistic defaults (`speed_3200`, `timings_cl16`, `desktop_udimm`, and brandless listings under `trident_z_family`) require positive evidence to fail; absence of signal is a pass. `kit_2x16gb` is the exception: configuration is expected to be stated, and an indeterminable configuration fails.
- Bundles (RAM plus other components in one listing) are judged on the RAM component alone.
- `overall` is the labeler's gut verdict recorded before deriving from criteria; it must stay consistent with the AND of the six criteria.
