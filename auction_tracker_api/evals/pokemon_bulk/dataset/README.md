# Pokémon bulk judge dataset codebook

This dataset evaluates the Pokémon bulk listing judge used by `auction_tracker_api`. Fixtures contain exactly the listing `url`, `title`, and scraped `description` available to production. Descriptions include Trade Me boilerplate and are truncated using the same 1000-character rule as `JsoupTradeMeClient`.

## Overall verdict

A listing passes only when all seven criteria pass. The judge is optimistic: a disqualifier fails only on positive evidence, and missing or ambiguous evidence passes. The dataset labels are based on the owner’s rulings from fixture text, not photos or outside research.

## Criteria

### pokemon_cards

The actual items are Pokémon Trading Card Game cards, not another game or accessories. Non-Pokémon examples fail; the remaining criteria are recorded as pass because they are inapplicable, matching the production prompt.

### bulk_scale

Singles, effectively itemized collections, and explicit quantities of 100 or fewer fail. Explicit quantities of 200 or more pass. Unstated quantities and ambiguous 101–199-card lots default to pass when framed as bulk, a lot, or a collection clear-out.

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

The corpus contains 26 owner-labeled live listings and 78 targeted synthetic label-gap fixtures. The new cases cover the linked mixed bundle, per-bundle “no duplicates” and “guaranteed EX” promises, and an exact-pile near miss with the same terms. The owner’s rulings also cover hard cases for Mega-era mixtures, 30th-anniversary exceptions, language ambiguity, Energy bundles, quantity defaults, and damaged collections. Earlier synthetic labels remain marked `owner review pending`; the new labels encode the owner’s explicit rule that repackaged bundle promises fail while claims about a clearly identified physical pile do not. The seed-42 split is 21 train, 41 dev, and 42 test, with relist identities kept together.

The owner review sheet is `labels.json`: each real listing retains its supplied verdict and reason, while synthetic entries include a note naming the targeted criterion. Keep prompt versions immutable once a measured run has been recorded.

## Files

- `criteria.json`: ordered criteria read by the shared harness.
- `labels.json`: hand-authored per-criterion and overall labels.
- `splits.json`: fixed seed-42 train/dev/test membership; train examples supply few-shot context.
- `<listing_id>.json`: immutable snapshots of production-shaped listing input.
