# LLM judge dataset codebook

Labeled dataset for evaluating an LLM judge that filters Trade Me auction listings of Magic: The Gathering bulk lots. The judge decides whether a listing is worth a human look; price and undervaluation are out of scope because the judge only sees a listing's `url`, `title`, and `description`.

## Files

- `<listing_id>.json`: fixture scraped from a real Trade Me listing. Fields `url`, `title`, `description` match what `JsoupTradeMeClient.parseItemPage` extracts (same selectors, whitespace collapsing, 1000-character truncation, query params stripped). Descriptions include listing-page boilerplate (condition, shipping, payment); that boilerplate is signal, not noise.
- `s<nnn>.json`: synthetic fixture (`"synthetic": true`, url `synthetic://<id>`) authored in the same style to fill label gaps, pre-labeled by construction and human-reviewed.
- `criteria.json`: ordered list of the criterion names below, read by the eval harness.
- `labels.json`: one entry per fixture with a `pass`/`fail` label per criterion, an `overall` verdict, and optional `notes`. When `mtg_cards` is `fail`, the other five criteria are `null` (inapplicable) and are skipped when scoring.
- `splits.json`: train/dev/test membership (roughly 20/40/40, seed 42), stratified so per-criterion fail counts and the synthetic fraction are balanced. Near-duplicate listings from the same seller share a split to avoid few-shot leakage. Train supplies few-shot prompt examples; dev is for iterating on prompts and models; test is reserved for final candidate comparison.

## Overall verdict

A listing passes only if all six criteria pass. Rarity mix (commons through mythics) is never by itself a reason to fail.

## Criteria

### mtg_cards

The items are Magic: The Gathering cards. Fails for other games (Pokemon, Yu-Gi-Oh, Flesh and Blood, video games) or non-card products (sleeves, accessories), even when the listing mentions MTG prominently.

### bulk_scale

The lot is genuinely bulk: a large pile whose contents are substantially unenumerated. Fails for single cards (including cards named like bulk, e.g. "Bulk Up"), lots whose contents are essentially fully itemized, and lots that are too small — tens of cards fail even when unenumerated. The bar is roughly "more cards than one could reasonably list individually": hundreds or more, or container scale (shoebox, ice cream container, sold by weight). Naming a few highlights of an otherwise unenumerated lot does not fail.

### not_basic_lands

The lot is not primarily basic lands (Plains, Island, Swamp, Mountain, Forest). Premium or full-art basics are still basics. Unqualified "lands" is assumed to mean basic lands; a lands lot passes only when described as nonbasic.

### not_universes_beyond

The lot is not primarily Universes Beyond crossover sets (Marvel, TMNT, Doctor Who, Fallout, etc.). Lord of the Rings (LTR) is explicitly allowed. Some crossover cards mixed into a mostly regular lot pass; assume not Universes Beyond unless the listing indicates it.

### civilian_seller

The cards come from a private individual's collection, not a card retailer or wholesaler's sifted inventory. Judged on the cards' provenance, not the seller's selling style: generic power-seller boilerplate (auction T&Cs, warranty text, courier policies) around a one-off personal lot passes. Card-business machinery fails: card-database stock photos ("Card image from scryfall.com"), in-cart order thresholds, productized bulk lines, explicit sifting or wholesale framing. Rarity-guarantee repacking ("Minimum 1 Rare guaranteed") also fails, even from a hobbyist: composing lots by rarity implies the seller sorts and knows cards. A business that is not a card business (games store, general second-hand dealer) selling a one-off MTG lot passes. Lean optimistic; no signal passes.

### fixed_collection

The listing is one specific physical collection, not an abstract quantity assembled per sale ("Multiple of these available", "made up fresh for each order"). Explicit repeatability language is not required: per-sale composition also shows in display-only photo disclaimers, "exact cards will vary", and curated "carefully assembled" product framing with guaranteed contents breakdowns. A listing anchored to one specific pile ("one box only", "contents as photographed") passes even alongside a stock-photo disclaimer. No signal passes.

## Labeling conventions

- Labels were hand-authored by Jordan (the sole user and domain expert) from fixture text only, without LLM assistance. Synthetic fixtures are labeled by construction and were human-reviewed.
- Disqualifier criteria (`not_universes_beyond`, `civilian_seller`, `fixed_collection`) require positive evidence to fail; absence of signal is a pass.
- `overall` is the labeler's gut verdict recorded before deriving from criteria; it is consistent with the AND of the six criteria across the dataset and that consistency should be preserved when labels change.
