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

One specific physical pile passes. Random, repeatable, display-only, or per-order assembled bundles fail. Store framing and combined shipping are not enough to fail.

## Dataset status

The corpus contains 25 owner-labeled live listings from the planning session and 75 targeted synthetic label-gap fixtures. The owner’s rulings intentionally include hard cases for Mega-era mixtures, 30th-anniversary exceptions, language ambiguity, Energy bundles, repeatable bundles, quantity defaults, and damaged collections. Synthetic labels are explicit constructions of one criterion at a time and are marked `owner review pending`; the owner should review the label sheet before using measured scores for a release decision. The fixed seed-42 split is 20 train, 40 dev, and 40 test, with relist identities kept together.

The owner review sheet is `labels.json`: each real listing retains its supplied verdict and reason, while synthetic entries include a note naming the targeted criterion. Keep prompt versions immutable once a measured run has been recorded.

## Files

- `criteria.json`: ordered criteria read by the shared harness.
- `labels.json`: hand-authored per-criterion and overall labels.
- `splits.json`: fixed seed-42 train/dev/test membership; train examples supply few-shot context.
- `<listing_id>.json`: immutable snapshots of production-shaped listing input.
