# Pokémon bulk judge dataset codebook

This dataset evaluates the Pokémon bulk listing judge used by `auction_tracker_api`. Fixtures contain exactly the listing `url`, `title`, and scraped `description` available to production. Descriptions include Trade Me boilerplate and are truncated using the same 1000-character rule as `JsoupTradeMeClient`.

## Overall verdict

A listing passes only when all seven criteria pass. The judge is optimistic: a disqualifier fails only on positive evidence, and missing or ambiguous evidence passes. The dataset labels are based on the owner’s rulings from fixture text, not photos or outside research.

## Criteria

### pokemon_cards

The actual items are Pokémon Trading Card Game cards, not another game or accessories. Non-Pokémon examples fail; the remaining criteria are recorded as pass because they are inapplicable, matching the production prompt.

### bulk_scale

Singles, effectively itemized collections, and stated whole-lot totals below 500 fail, including exact counts, approximations, and lower bounds such as “200+,” “120+,” “over 340,” and “6x.” A “bulk” label does not override a stated total below 500. A stated total of 500 or more passes the count check, including “500+”; the other bulk-scale rules still apply. Counts for a subset of a larger uncounted pile or for shipping or packaging do not establish the lot total. Unstated quantities default to pass when framed as bulk, a large lot, or a collection clear-out. A bundle title with no count and no text establishing a large pile fails: the sparse bundle wording does not establish bulk scale.

The owner-provided listings `6162600268`, `6162497749`, and `6162761525` fail on their stated 200+, 120+, and 6x card totals. Existing listings `6143687098`, `6147566784`, and `6148917923` also fail on their stated 200, approximately 360, and over 340 card totals. The approximately 360-card binder retains its independent poor-condition failure.

The owner-labeled listings `6153519596` (“Pokemon Camorant bundle”), `6153488007` (“Pokemon Card Bundle $55”), and `6153501092` (“Scarlet & Violet Promo Bundle”) fail because their scraped titles and descriptions provide no card count or evidence of a large pile. Their `fixed_collection` labels pass because the text does not establish a repeatable or assembled product.

### accepted_language

English and Japanese pass. Other languages fail only when clearly the majority. Unclear Japanese/Chinese mixtures pass.

### not_basic_energy

Lots primarily made of Basic Energy fail, including full-art or premium versions. Ordinary mixed lots with some Energy pass.

### not_mega_evolution_era

The current excluded era is Mega Evolution, Phantasmal Flames, Ascended Heroes, Perfect Order, Chaos Rising, Pitch Black, and non-30th Mega Evolution promos. A majority from that era fails. Anything identified as 30th anniversary is exempt, including 30th Celebration, 30th Classic Collection, and related promos. Older XY-era Mega cards are not the current era.

### acceptable_condition

Mint, Near Mint, and Lightly Played pass. A majority explicitly Moderately Played, Heavily Played, Damaged, or worse fails. Unclear condition and generic Trade Me `Used` boilerplate pass.

### fixed_collection

One specific physical pile or seller-owned clear-out passes. Fail when the listing sells a consumer bundle format instead of identifying the actual pile in this auction. A generic count/mix promise such as “X500 mixed bulk bundle” with “in this bundle you will receive” language is a productized offer when no exact pile is identified. Per-bundle promises such as “no duplicates” or “guaranteed EX” are also evidence of a curated pack from stock when photos are representative or contents can vary. Random, repeatable, display-only, and per-order assembled bundles fail. The word “bundle” alone does not fail when the text anchors the auction to one exact physical pile. Store framing and combined shipping are not enough to fail.

The owner-labeled Trade Me listing `6150549502` is a fail: it offers an “X500 Mixed Bulk Bundle,” promises a 400 common/uncommon plus 100 holo/reverse holo mix, and says “In this bundle you will receive” without identifying one exact pile. The synthetic `s078` is a near-miss pass: “no duplicates” and an included EX card describe one exact photographed collection, not a repeatable pack.

## Dataset status

The corpus contains 32 owner-labeled live listings and 78 targeted synthetic label-gap fixtures. The real cases include the three sparse bundle listings and three below-500 count examples; other cases cover a linked mixed bundle, per-bundle “no duplicates” and “guaranteed EX” promises, and an exact-pile near miss with the same terms. The owner’s rulings also cover hard cases for Mega-era mixtures, 30th-anniversary exceptions, language ambiguity, Energy bundles, quantity defaults, and damaged collections. Earlier synthetic labels remain marked `owner review pending`; the seed-42 split is 21 train, 46 dev, and 43 test, with relist identities kept together.

The owner review sheet is `labels.json`: each real listing retains its supplied verdict and reason, while synthetic entries include a note naming the targeted criterion. Keep prompt versions immutable once a measured run has been recorded.

## Files

- `criteria.json`: ordered criteria read by the shared harness.
- `labels.json`: hand-authored per-criterion and overall labels.
- `splits.json`: fixed seed-42 train/dev/test membership; versioned prompts contain their chosen train examples directly. Production prompt v7 retains v6's five condensed train examples and adds the 500-card floor. V5 preserves the previous Java prompt's 11 examples, while v1-v4 contain the full 21-example train split used by the earlier eval harness.
- `<listing_id>.json`: immutable snapshots of production-shaped listing input.
