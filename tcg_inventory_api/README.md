# TCG inventory API

The TCG inventory API service is the source of truth for a physical Magic: The Gathering card inventory stored under chaos sorting, projecting in-stock units to FetchTCG listings and mirroring FetchTCG order activity back as reservations, pulls, and releases.

## Overview

- **Service type**: backend API (`tcg_inventory_api`)
- **Interface**: REST over HTTPS plus an SQS-driven job consumer
- **Runtime**: AWS Lambda (Java 21) behind API Gateway REST; one job Lambda consuming SQS
- **Primary storage**: DynamoDB table `tcg_inventory` with `gsi1` and `gsi2`; S3 bucket `api.tcg-inventory.jordansimsmith.com` (service object store; listing photos under `users/<user>/photos/`)
- **Auth model**: API Gateway custom REQUEST authorizer provided by the shared `auth_api` service (see `auth_api/README.md`)
- **External integration**: FetchTCG website API (unofficial), Firebase token exchange
- **Primary consumer**: `tcg_inventory_web`

## User stories

- As a card seller, I want to import a ManaBox scan, review each card's keep/discard appraisal in stack order, and confirm keepers into inventory with assigned storage locations, so that intake requires no physical sorting.
- As a card seller, I want FetchTCG listing quantities to always equal my in-stock unit counts, so that I never oversell or list phantom stock.
- As a card seller, I want cards appraised at NZ$20 or more to require photos captured during import review and projected onto their FetchTCG listings, so that high-value listings show the actual card the buyer receives.
- As a card seller, I want accepted offers to reserve the exact physical units and paid orders to produce a location-ordered pull sheet, so that fulfilment is one forward pass through my boxes.
- As a card seller, I want to see whether an accepted offer's price differs from my listed price, so that I can tell a lowball or over-ask from a buy-at-list sale.
- As a card seller, I want every inventory mutation audited with before/after state, so that digital counts never silently drift from the physical boxes.
- As a card seller, I want a regenerated overview report of value, movement, and composition, so that I can appreciate the overall state of the inventory without browsing SKU by SKU.
- As a cautious FetchTCG user, I want conservative sequential API traffic and fail-closed credential handling, so that automation risk stays minimal.

## Features and scope boundaries

### In scope

- Authenticated CRUD for imports: upload a ManaBox CSV, appraise rows asynchronously, review appraisal decisions, confirm keepers into inventory, delete unwanted imports before confirm.
- Appraisal per row: FetchTCG identity resolution for the CSV-identified printing (candidates verified against the row's Scryfall ID, cached on the SKU after first sight), keep filter, and suggested policy price.
- Inventory browse: SKU search and detail with unit lists and derived locations.
- Listing photos: up to 5 JPEG photos per keep row, captured during import review (raw-body upload, stored durably in S3, served via short-lived presigned URLs); confirm is blocked while any keep row appraised at NZ$20+ has no photos; photos freeze onto units at confirm and are immutable afterwards.
- Manual audited adjustments: remove a unit; change a unit's condition (moving it between SKUs).
- Publish job (single two-phase job): order phase ingests FetchTCG seller offers (reserve, pick-ready, defensive void release), then publish phase drains dirty SKUs to FetchTCG — create and update as absolute listing quantities projecting the first in-stock unit's photos as the listing images, delete the listing when the in-stock count reaches zero.
- Pull sheets for paid orders, sorted by unit sequence number; order confirm marks pulled units sold.
- Offer vs listed price: each ingested offer line stores the FetchTCG listing's `listedPrice` at ingest time; `GET /orders` returns item and listed subtotals (shipping excluded) and `GET /orders/{order_id}` returns per-line offered and listed prices.
- Reports: an async report job aggregates the entire inventory into a stored dashboard snapshot (headline totals, monthly revenue, weekly intake vs sales, top sets, price buckets, top hits, aging bands); `GET /reports` serves the latest snapshot with staleness metadata and generation status.
- Per-user FetchTCG refresh-token storage with masked reads; fresh one-hour bearer minted per job run.
- Append-only audit log written transactionally with every mutation.

### Out of scope

- Repricing existing listings (a separate repricing process owns price maintenance; this service prices new listings only).
- Marketplaces other than FetchTCG; games other than Magic: The Gathering; non-English cards (they become review rows).
- Camera or scanner-based intake, image recognition, and scan verification UIs.
- Post-confirm photo editing (photos freeze at confirm; remove and re-import is the retake path) and FetchTCG buyer photo requests.
- Cost/purchase-price tracking and profit reporting (reports cover revenue and valuation only), bulk lots, master sets, POS, buylist.
- Background/scheduled polling of FetchTCG (all jobs are manually triggered).
- Deleting SKU records (they are permanent once created) or offer negotiation (accept/counter/reject happens on FetchTCG).

## Architecture

```mermaid
flowchart TD
  web[tcg_inventory_web SPA] -->|HTTPS Basic| apigw[API Gateway REST]
  apigw --> authz[auth_api authorizer]
  apigw --> http[HTTP handlers]
  http -->|read/write| ddb[(DynamoDB: tcg_inventory)]
  http -->|send job + continuation messages| sqs[SQS: tcg_inventory_jobs.fifo]
  sqs -->|batch size 1, max concurrency 1| jobs[Job consumer Lambda: appraise / publish / report]
  jobs --> ddb
  jobs -->|continuation| sqs
  jobs -->|mint bearer| firebase[Firebase token endpoint]
  jobs -->|sequential 1-2s| fetchtcg[FetchTCG API]
  http -->|put/get secret| secrets[Secrets Manager: tcg_inventory]
  jobs --> secrets
  http -->|put/get/presign photos| s3[(S3: api.tcg-inventory)]
  jobs -->|get photo bytes| s3
```

### Primary workflow

```mermaid
sequenceDiagram
  participant U as user
  participant W as tcg_inventory_web
  participant A as tcg_inventory_api
  participant Q as SQS
  participant J as job consumer
  participant F as FetchTCG

  U->>W: upload ManaBox CSV
  W->>A: POST /imports
  A->>Q: enqueue appraise job
  A-->>W: import_id + job_id
  J->>F: resolve identity + market appraisal per row
  J-->>A: rows keep/discard/review (job item progress)
  U->>W: review rows, remove discards and review cards from stack
  W->>A: POST /imports/{import_id}/confirm
  A->>A: allocate sequence numbers, append units, mark SKUs dirty, audit
  A-->>W: placement instructions
  U->>W: trigger publish
  W->>A: POST /publish
  A->>Q: enqueue publish job
  J->>F: order phase: ingest seller offers (reserve/release)
  J->>F: publish phase: absolute quantity upserts for dirty SKUs
```

## Main technical decisions

- Inventory is the source of truth; FetchTCG listings are an absolute projection: listing quantity = count of `in_stock` units per SKU. Re-importing already-listed cards converges to a no-op, and FetchTCG's own decrement at offer acceptance converges without a write.
- Dirty-marker outbox for the projection: every mutation transaction sets a plain boolean `dirty` on affected SKU records. Only mutation transactions can set the flag, which makes every FetchTCG write traceable to an audited inventory event; blind reconciliation never changes quantities. Coalescing is inherent because the projection is absolute.
- Stock counts are never stored: SKU detail derives `in_stock`/`reserved`/`sold` counts from the unit items in its own partition query. SKU browse returns only identity fields (no counts, no unit fan-out) — users click through to the detail page for counts. With no denormalized aggregate there is nothing to drift or verify. Every mutation transaction bumps a plain `version` number on the affected SKU (`ADD version :1`); the publish phase recounts unit items for its absolute write and clears `dirty` conditionally on the version being unchanged since the recount, so a mutation landing mid-publish fails the clear and the SKU stays dirty for the next run.
- SQS FIFO work queue with continuation messages: messages carry only `{user, job_id, job_type}`; the job item's `continuation` is authoritative. The queue is FIFO with one message group per user because the group is what serializes the consumer to concurrency 1 (Lambda event source mappings cannot set maximum concurrency below 2 on standard queues), serializing all FetchTCG traffic and all inventory-mutating jobs (no job lease needed). Each slice does bounded work, checkpoints, and re-enqueues.
- Slice messages for one job are byte-identical, so content-based deduplication is disabled and every send sets an explicit `MessageDeduplicationId` of `<job_id>#<continuation>`: distinct slices are never deduplicated, duplicate re-sends of the same slice within the 5-minute dedup window are suppressed, and a send missing a dedup ID fails loudly instead of silently swallowing a continuation.
- Duplicate SQS delivery is expected and absorbed: slices read the job item fresh, DynamoDB effects are conditionally guarded, FetchTCG effects are absolute upserts keyed by `cardId` + condition.
- One publish job with two ordered phases (order phase before publish phase) structurally prevents relisting stock committed to a pending offer.
- Units are append-only with a globally monotonic `sequence_number` allocated by an atomic counter; storage blocks and locations are pure derivations of it. Sold and removed units leave gaps; nothing is renumbered or reshuffled.
- The offer lifecycle is modeled with reservations: acceptance reserves forward-most in-stock units, payment makes the order pickable, non-payment voids and releases. Confirming a pull sets no dirty flag — the units left the projection at reservation and FetchTCG already decremented at acceptance.
- SKU identity is the deterministic composite `scryfall_id#finish#condition` — computable offline from a ManaBox row with no lookup. SKU records cache the resolved `fetchtcg_card_id` and are never deleted.
- Conditions use the 5-level TCGplayer-style scale; ManaBox's 7 values collapse at import and FetchTCG codes are a boundary translation. NM is the default when no condition is provided.
- FetchTCG traffic is sequential with 1–2 s random request spacing, bounded retries, an endpoint allowlist, and fail-closed bearer handling. Every job run mints a fresh one-hour bearer from the stored refresh token and persists a rotated refresh token when Firebase returns one.
- Reports are a stored snapshot, not live aggregation: a `report` job pages all SKU records via `gsi2` (projection ALL), derives every figure from unit and order items, and overwrites a singleton report item stamped with the latest audit ULID captured at generation start. `GET /reports` computes staleness (comparing the latest audit ULID against the snapshot's as-of audit ULID, plus a 24-hour backstop) without touching inventory partitions. Stock counts stay unstored; the report is a disposable projection regenerated on demand.
- Identity resolution is verified, never ranked: appraisal searches FetchTCG by set, front-face name, and finish, then accepts the first candidate whose `externalReferences.scryfallId` equals the row's Scryfall ID. Search relevance ranking is never trusted, so multiple printings of one name in a set (borderless, showcase, extended art) cannot collapse onto the wrong card; a row with no verified candidate becomes `review`.
- The static Scryfall→FetchTCG set mapping is a generated, checked-in artifact; unmapped sets stop appraisal into `review` rather than guessing. The generator maps each FetchTCG set to every distinct Scryfall code found by sampling unique card names from both the newest and oldest ends of that set, so reprint printings filed under an older FetchTCG set (for example MH1 and MH2 Timeshifts under Modern Horizons) still resolve.
- Photos are per-unit, captured on keep rows during import review (the only moment cards are in hand) and immutable after confirm — no unit-level photo mutations exist, so no photo-driven dirty flags or audit events. The import gate is NZ$20 against FetchTCG's NZ$50 client-side rule: the margin makes a sub-gate card later drifting past $50 negligible, and FetchTCG's API never rejects photo-less listings anyway (verified live — it defaults the front image to the stock card image), so a photo-less NZ$50+ upsert logs a warning and proceeds rather than blocking publish.
- Listing images are an absolute projection of the first (lowest sequence) in-stock unit's photos — the exact card the next buyer receives, since reservation allocates forward-most first. Every upsert sends full image state (verified live: an omitted `frontImage` resets to the stock image; a present `additionalImages` replaces the set). Each photo uploads to FetchTCG once ever; the returned URL is cached on the unit's photo entry and remains valid across listing deletion and recreation.
- Photo upload uses a raw `image/jpeg` request body through the API (the CSV import precedent) rather than presigned S3 uploads: client-side re-encoding keeps bodies far below Lambda's payload ceiling, so presign choreography buys nothing at photo sizes.

## Domain glossary

- **Printing**: a specific card printing identified by Scryfall ID (set-specific; encodes name, set, collector number, language).
- **Finish**: `normal` | `foil` | `etched`.
- **Condition**: `NM` | `LP` | `MP` | `HP` | `DMG`. ManaBox import mapping: mint→NM, near_mint→NM, excellent→LP, good→MP, light_played→HP, played→HP, poor→DMG. FetchTCG listing mapping: NM→`raw-nm`, LP→`raw-lp`, MP→`raw-mp`, HP→`raw-hp`, DMG→`raw-d` (`raw-m` is never listed).
- **SKU**: printing + finish + condition; the sellable identity. One FetchTCG listing per SKU. Permanent once created.
- **Unit**: one physical card. Status lifecycle: `in_stock` → `reserved` → `sold`; `reserved` → `in_stock` on void; `in_stock` → `removed` by adjustment.
- **Photo**: a JPEG of a specific physical card (max 5 per row/unit, in upload order; the first uploaded is the listing front image), captured on a keep row during review, stored durably in S3, frozen onto the unit at confirm.
- **Sequence number**: globally monotonic integer per unit, assigned at import confirm; the canonical physical position.
- **Block**: `floor(sequence_number / 100)`, labeled `A0` … `A99`, `B0` … (letter advances every 100 blocks). Labels are logical and append-only; a block physically lives wherever its labeled divider sits.
- **Location**: display form `<block>-<offset>` with zero-based offset = `sequence_number % 100` (4242 → `A42-42`). Derived, never stored. Offsets are placement order; pulls leave gaps but preserve relative order, guaranteeing single-forward-pass pulls.
- **Import**: one ManaBox CSV ingest session — uploaded, appraised, reviewed, confirmed once, then done. Status: `appraising` → `review` → `confirming` → `confirmed`. An unconfirmed import can be deleted outright (import and rows removed); confirmed imports are permanent because units reference them for provenance.
- **Import row**: one candidate physical card within an import (CSV rows are quantity-expanded, so one row = one card at one stack position). A `keep` row becomes exactly one unit at confirm and records its assigned sequence number; `discard` and `review` rows never become units. Rows carry appraisal and review state and die with their import; units are permanent inventory.
- **Appraise**: the job that adds what the CSV cannot contain — FetchTCG identity resolution and market appraisal (keep filter + suggested policy price).
- **Publish**: the job that projects inventory to FetchTCG; order phase (ingest offers) then publish phase (drain dirty SKUs).
- **Order**: an accepted FetchTCG offer. State: `awaiting_payment` → `to_pick` → `fulfilled`, or `awaiting_payment` → `voided`. An order created with an unmapped listing or insufficient stock enters `flagged` (requires manual review). Each line stores the offered line total (`price`) and the per-unit listing price captured at ingest (`listed_price`).
- **Pull sheet**: pick list for a paid order, sorted by sequence number, forward-most duplicate first.
- **Dirty**: boolean on a SKU meaning its FetchTCG listing may not reflect current in-stock count; set only inside mutation transactions.
- **Report**: the singleton stored dashboard snapshot (totals, trends, composition figures) produced by the report job; overwritten in place, no history. Stale when any audited mutation postdates its as-of audit ULID or it is older than 24 hours.

## Integration contracts

### External systems

- **FetchTCG website API**: sequential HTTPS JSON requests to `https://api.fetchtcg.com` with a browser-compatible user agent. Public reads (card details `GET /v3/cards/{card_id}` — identity verification via `externalReferences.scryfallId`, market pricing via `pricingData` — card search `GET /v3/cards`, active listings `GET /v3/cards/{card_id}/listings`) are unauthenticated. Authenticated calls attach `Authorization: Bearer <token>` only to the seller offers list (`GET /v2/private/market/offers/seller`), managed-listings read (`GET /v1/manage-listings`), the listing image upload (`POST /v2/private/manage-listings/uploadListingImage`, multipart `file`, JPEG/PNG only, server re-encodes; returns a durable account-scoped `imageUrl` reusable across listing lifecycles), the listing upsert (`POST /v2/private/manage-listings`, absolute quantity, price, and image state keyed by `cardId` + condition — `frontImage` is a string that resets to the stock card image when omitted; `additionalImages` is `[{"label": null, "url": "..."}]` and replaces the set when present, `[]` clearing it), and the listing delete (`DELETE /v1/manage-listings/{listing_id}`, no body, 200 with empty body; a 404 means the listing is already gone and is treated as success — used to delist a SKU whose in-stock count reaches zero). Transient failures retry with bounded backoff; 401/403 stops the job. FetchTCG does not publish these endpoints as a supported API and its terms prohibit unpermitted automation; conservative pacing reduces load but the policy risk stays with the user.
- **Firebase token exchange**: each job run exchanges the stored refresh token at Firebase's fixed HTTPS token endpoint for a one-hour bearer. A replacement refresh token in the response is persisted back to the secret. The refresh token is never sent to FetchTCG.
- **Offer state mapping** (from the seller offers list): an offer first seen with `status = ACCEPTED` creates an order and reserves units, provided its `acceptedAt` is strictly after the user's `track_orders_after` setting (when set). Offers accepted at or before that instant are silently skipped on every run and never create order records. If `acceptedAt` is null or unparseable on an `ACCEPTED` offer, the offer is fail-closed skipped with a warning log. `currentAction` past payment confirmation — exactly `SEND_PICKUP_ADDRESS` (pickup), `SEND_TRACKING_CODE` (delivery), `SEND_REVIEW`, or `AWAIT_REVIEW`, the complete post-payment set observed in captured FetchTCG traffic — marks the order `to_pick`; actions at or before payment confirmation (`AWAITING_DELIVERY_MODE`, `AWAITING_SHIPPING_ADDRESS`, `SEND_PAYMENT_INSTRUCTIONS`, `AWAITING_PAYMENT`, and `CONFIRM_PAYMENT_RECEIVED`, where the buyer claims payment the seller has not yet confirmed) leave it `awaiting_payment`. When an offer cannot resolve all its listing lines to known SKUs or has insufficient in-stock units, the order is created with status `flagged` (no units are reserved for unmapped lines). Each mapped line persists `items[].price` (offered line total) and `items[].listing.listedPrice` (per-unit asking price at ingest — FetchTCG's current listing price at fetch time, not a snapshot from offer creation). Buyer names, addresses, payment instructions, and tracking details are never persisted.
- **Scryfall API**: consumed only by the set-mapping generator (public set catalog and card records); normal runs never call Scryfall.

## API contracts

### Conventions

- Base URL: `https://api.tcg-inventory.jordansimsmith.com`
- Auth: `Authorization: Basic <base64(user:password)>` on every endpoint
- Request and response fields use `snake_case`; no path version segment
- Non-2xx responses use `{"message": "error details"}`
- `PATCH` is used for partial updates of resources with independent fields: each field present in the body is applied, absent fields are unchanged, and an empty body returns 400
- The photo upload endpoint accepts a raw binary body (`Content-Type: image/jpeg`, 4 MB max) instead of JSON; photo mutations respond `204` and clients re-read `GET /imports/{import_id}` for the updated `photos` list and `needs_photos`
- Async work is observed through the affected resource, not a generic jobs API: appraisal progress and errors ride on the import (`GET /imports/{import_id}`), publish progress and errors on `GET /publish` (current-or-latest run), and report generation progress and errors on `GET /reports` (latest snapshot plus current-or-latest generation). Job items exist in storage only as internal continuation state.
- Failure `error` values are short human-readable summaries: authentication failures instruct replacing the refresh token, and other failures store the root-cause exception message truncated to 300 characters. Full stack traces (including upstream FetchTCG response bodies) go to the Lambda logs only.
- Verb convention: edits that record client-owned data use `PUT` on the resource; domain actions that cause server-side cascades (confirm, publish) are `POST` sub-resource actions with transition-specific contracts

### Endpoint summary

| Method   | Path                                                     | Purpose                                                                                |
| -------- | -------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `POST`   | `/imports`                                               | upload a ManaBox CSV; starts the appraise job                                          |
| `GET`    | `/imports`                                               | list imports newest-first (continuation paging)                                        |
| `GET`    | `/imports/{import_id}`                                   | import status, progress, and rows                                                      |
| `PUT`    | `/imports/{import_id}/rows/{position}`                   | update a row's condition before confirm                                                |
| `DELETE` | `/imports/{import_id}/rows/{position}`                   | delete a misidentified row before confirm                                              |
| `POST`   | `/imports/{import_id}/rows/{position}/photos`            | add a photo to a keep row (raw JPEG body)                                              |
| `DELETE` | `/imports/{import_id}/rows/{position}/photos/{photo_id}` | remove a row photo before confirm                                                      |
| `POST`   | `/imports/{import_id}/confirm`                           | append keeper units; returns placement instructions and total suggested price          |
| `DELETE` | `/imports/{import_id}`                                   | delete an unconfirmed import and its rows                                              |
| `GET`    | `/skus`                                                  | browse/search SKUs (prefix search, continuation paging)                                |
| `GET`    | `/skus/{sku_id}`                                         | SKU detail including its units                                                         |
| `DELETE` | `/skus/{sku_id}/units/{sequence_number}`                 | remove a unit (optional `reason` query param)                                          |
| `PUT`    | `/skus/{sku_id}/units/{sequence_number}`                 | update a unit's condition (moves it to another SKU; response returns the new `sku_id`) |
| `GET`    | `/orders`                                                | list orders newest-first with item and listed subtotals                                |
| `GET`    | `/orders/{order_id}`                                     | order detail: offer lines (offered vs listed), allocated units, pull locations         |
| `POST`   | `/orders/{order_id}/confirm`                             | confirm the pull; marks allocated units sold                                           |
| `POST`   | `/publish`                                               | start a publish run; responds 202 and is idempotent while one is queued/running        |
| `GET`    | `/publish`                                               | current-or-latest publish run: status, progress, error, pending dirty count            |
| `POST`   | `/reports`                                               | start a report generation; responds 202 and is idempotent while one is queued/running  |
| `GET`    | `/reports`                                               | latest report snapshot with staleness and generation status; 404 before first run      |
| `GET`    | `/settings`                                              | settings view: credential presence, last-updated, track orders after                   |
| `PATCH`  | `/settings`                                              | partial update: optional refresh token + optional track orders after                   |

### Example request and response

`GET /imports`

Query parameters: optional `continuation` (opaque token from a previous page) and `limit` (default 20). Response is `{ "imports": [...], "next_continuation": "<token>" | null }` newest-first; `next_continuation` is null on the last page.

`POST /imports/{import_id}/confirm`

Response `200` (each placement instruction carries the card names at its boundary locations, taken from the keeper rows assigned to that range; `total_suggested_price` is the sum of those keepers' suggested listing prices):

```json
{
  "import_id": "01JEXAMPLEULID0000000000",
  "status": "confirmed",
  "unit_count": 87,
  "total_suggested_price": "342.50",
  "first_sequence_number": 4200,
  "last_sequence_number": 4286,
  "placement_instructions": [
    {
      "block": "A42",
      "from_location": "A42-0",
      "to_location": "A42-86",
      "from_name": "Llanowar Elves",
      "to_name": "Sol Ring",
      "unit_count": 87
    }
  ]
}
```

Representative failures:

- `409`: `{"message":"import is not in review status"}` (double confirm, or confirm during appraisal)
- `409`: `{"message":"2 rows need photos before confirm"}` (keep rows appraised at NZ$20+ still photo-less)
- `404`: `{"message":"Not Found"}` (unknown import in user scope)

`POST /imports/{import_id}/rows/{position}/photos` (body: raw JPEG bytes)

Response `204` (both photo mutations; no body). Updated `photos` and `needs_photos` are observed on `GET /imports/{import_id}`. Photos order by upload and the first is the listing front image — removing one promotes the next, so reordering is delete + re-upload. `url` on GET is a 15-minute presigned S3 GET.

Representative failures: `409` unless the import is in review; `400` for a non-keep row, a non-JPEG body, a body over 4 MB, or a sixth photo. Rows in `GET /imports/{import_id}` carry `photos` (`[{photo_id, url}]`, `[]` when none) and `needs_photos` (keep, appraised at NZ$20+, no photos yet).

`GET /skus/{sku_id}`

Response `200` (units sorted ascending by sequence number; locations and the `*_count` fields are derived server-side from unit items, never stored; unit `photos` are read-only — photo management exists only on review rows):

```json
{
  "sku_id": "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "scryfall_id": "f0a51425-d796-48b8-b68c-bc21fb465c81",
  "name": "Elvish Aberration",
  "set_code": "a25",
  "set_name": "Masters 25",
  "collector_number": "167",
  "finish": "normal",
  "condition": "NM",
  "in_stock_count": 2,
  "reserved_count": 1,
  "sold_count": 0,
  "units": [
    {
      "sequence_number": 1204,
      "location": "A12-4",
      "status": "reserved",
      "photos": []
    },
    {
      "sequence_number": 4242,
      "location": "A42-42",
      "status": "in_stock",
      "photos": [
        {
          "photo_id": "01JEXAMPLEPHOTOULID00000",
          "url": "https://s3.ap-southeast-2.amazonaws.com/api.tcg-inventory.jordansimsmith.com/users/jordan/photos/01JEXAMPLEPHOTOULID00000.jpg?X-Amz-Expires=900&..."
        }
      ]
    },
    {
      "sequence_number": 4250,
      "location": "A42-50",
      "status": "in_stock",
      "photos": []
    }
  ]
}
```

Adjustment responses: `DELETE /skus/{sku_id}/units/{sequence_number}` responds `200` with the updated SKU detail (same shape as `GET /skus/{sku_id}`); `PUT /skus/{sku_id}/units/{sequence_number}` with body `{"condition": "LP"}` responds `200` with `{"sku_id": "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#LP"}`.

`GET /orders/{order_id}`

Response `200` (the `units` list, sorted by sequence number, is the pull sheet when the order is `to_pick`; `lines` are offer lines in payload order; `price` is the offered line total and `listed_price` is the per-unit asking price captured at ingest, or `null` on orders ingested before this field existed):

```json
{
  "order_id": "83663",
  "state": "to_pick",
  "accepted_at": 1765420932,
  "delivery_mode": "PICKUP",
  "total_price": "3.33",
  "lines": [
    {
      "name": "Hellkite Tyrant",
      "set_code": "gtc",
      "collector_number": "75",
      "finish": "normal",
      "condition": "NM",
      "quantity": 1,
      "price": "3.33",
      "listed_price": "3.50"
    }
  ],
  "units": [
    {
      "sequence_number": 1204,
      "location": "A12-4",
      "name": "Hellkite Tyrant",
      "set_code": "gtc",
      "collector_number": "75",
      "finish": "normal",
      "condition": "NM"
    }
  ]
}
```

`GET /orders` summaries add `items_total_price` (sum of line offered totals; excludes shipping) and `listed_total_price` (sum of `listed_price × quantity`). `listed_total_price` is `null` when any line lacks a listed baseline. `total_price` remains the FetchTCG offer total and may include shipping.

- `409` on `POST /orders/{order_id}/confirm`: `{"message":"order is not ready to pick"}` when the order is not `to_pick`.

`GET /reports`

Response `200` (arrays shown with one representative entry; empty buckets and bands are still emitted so charts render stable axes; money values are NZD decimal strings):

```json
{
  "generated_at": 1765420800,
  "stale": false,
  "generation": {
    "status": "succeeded",
    "error": null,
    "started_at": 1765420700,
    "finished_at": 1765420800
  },
  "report": {
    "totals": {
      "inventory_value": "2894.35",
      "in_stock_units": 9412,
      "sku_count": 6120,
      "reserved_units": 14,
      "sold_units": 862,
      "revenue_to_date": "1204.50",
      "unpriced_units": 3
    },
    "revenue_by_month": [
      { "month": "2026-07", "revenue": "180.20", "order_count": 12 }
    ],
    "intake_vs_sales_by_week": [
      { "week_start": "2026-07-06", "added_units": 240, "sold_units": 31 }
    ],
    "top_sets": [
      { "set_code": "a25", "set_name": "Masters 25", "in_stock_units": 812 }
    ],
    "price_buckets": [{ "label": "$0.25-$0.50", "in_stock_units": 5120 }],
    "top_hits": [
      {
        "sku_id": "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
        "name": "Ragavan, Nimble Pilferer",
        "set_code": "mh2",
        "collector_number": "138",
        "finish": "normal",
        "condition": "NM",
        "price": "95.00",
        "in_stock_units": 1
      }
    ],
    "aging_bands": [{ "label": "0-30 days", "in_stock_units": 1200 }]
  }
}
```

- `generation` reflects the current-or-latest `report` job (`queued` | `running` | `succeeded` | `failed`, with `error` populated on failure); a run in flight rides alongside the previous snapshot.
- `404` with `{"message":"Not Found"}` before the first generation ever.

### Pricing policy (new listings)

Computed during appraisal for each keep row; the suggested price is stored on the row, copied to the SKU at confirm, and the publish phase lists at the SKU's stored suggested price. All values NZD.

```text
keep filter: market price >= 0.25 (applied at appraisal; below → discard)

tick = max(0.05, round_nearest_half_up(2.5% * lowest_rival, 0.05))

if lowest same-or-better-condition rival exists and lowest_rival >= 80% * market:
    benchmark = lowest_rival - tick
elif two-seller supported same-or-better-condition floor exists:
    benchmark = supported_floor
elif no same-or-better-condition rival exists and market >= 2.00:
    benchmark = market * 1.15
else:
    benchmark = market

price = max(0.25, round_nearest_half_up(benchmark, 0.05))
```

- Market price source: FetchTCG `pricingData.NZ.tcgMarketPrice`.
- Rival evidence: active New Zealand listings for the exact card in the SKU's condition or strictly better, excluding the authenticated account's own listings. Condition quality order: `raw-d < raw-hp < raw-mp < raw-lp < raw-nm < raw-m`.
- Two-seller supported floor: the first ascending same-or-better-condition price at which at least two distinct sellers are cumulatively available.
- Deep-discount guard: a lowest rival below 80% of market is not undercut.
- Sole-source premium: 15% over market when no rival exists and market ≥ NZ$2.00.

## Data and storage contracts

### DynamoDB model

- **Table**: `tcg_inventory`, keys `pk`/`sk`, PAY_PER_REQUEST.
- **`gsi1`** (dirty index): `gsi1pk` = `USER#<user>#DIRTY` (dirty) or `USER#<user>#CLEAN` (published), `gsi1sk = SKU#<sku_id>` (set once at SKU creation, never changed). Querying `gsi1pk = USER#<user>#DIRTY` returns exactly the dirty set. The publish phase flips `gsi1pk` to `CLEAN`; mutations flip it back to `DIRTY`. Unit items carry no GSI attributes; units are always addressed through their SKU partition (a global units-by-sequence index is deliberately absent until a flow needs one, for example block views or consolidation).
- **`gsi2`**: SKU browse (`gsi2pk = USER#<user>#SKUS`, `gsi2sk = NAME#<normalized name>#<sku_id>`), supporting alphabetical listing and `begins_with` prefix search.
- `sku_id` is `<scryfall_id>#<finish>#<condition>`. A SKU record and its unit items share a partition so one query serves detail, recount, and allocation.

| Item             | pk                            | sk                             | Notable attributes                                                                                                                                                                                                               |
| ---------------- | ----------------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SKU              | `USER#<u>#SKU#<sku_id>`       | `SKU`                          | scryfall_id, finish, condition, name, set_code, set_name, collector_number, fetchtcg_card_id, fetchtcg_set_id, `version`, `dirty`, `fetchtcg_listing_id`, `last_published_quantity`, `last_published_price`, `last_published_at` |
| Unit             | `USER#<u>#SKU#<sku_id>`       | `UNIT#<sequence_number>`       | sequence_number, status, import_id, order_id (when reserved/sold), timestamps, `photos` (ordered `{photo_id, fetchtcg_url once uploaded}` list)                                                                                  |
| Import           | `USER#<u>`                    | `IMPORT#<ulid>`                | filename, status, row counts, error (when the appraise job fails), timestamps                                                                                                                                                    |
| Import row       | `USER#<u>#IMPORT#<import_id>` | `ROW#<stack position, padded>` | raw CSV fields, resolved identity, decision + reason, appraisal evidence (market price, rival evidence, suggested price), assigned sequence_number, `photos` (ordered `{photo_id}` list)                                         |
| Order            | `USER#<u>`                    | `ORDER#<fetchtcg_offer_id>`    | state, FetchTCG status/currentAction snapshot, accepted_at, delivery_mode, financial totals (no buyer PII), embedded lines `[{sku_id, fetchtcg_listing_id, quantity, price, listed_price, allocated sequence_numbers}]`          |
| Audit entry      | `USER#<u>#AUDIT`              | `<ulid>`                       | event_type (`import_confirm`, `adjustment`, `reserve`, `release`, `sell`, `publish`), affected sku_ids / unit sequence_numbers / order_id / import_id, before/after summary                                                      |
| Job              | `USER#<u>`                    | `JOB#<ulid>`                   | internal continuation state, never an API resource: type (`appraise` \| `publish` \| `report`), status (`queued` \| `running` \| `succeeded` \| `failed`), continuation, progress counters, error                                |
| Sequence counter | `USER#<u>`                    | `COUNTER#SEQUENCE`             | `next_sequence_number`                                                                                                                                                                                                           |
| Settings         | `USER#<u>`                    | `SETTINGS`                     | credential metadata (set-at timestamp only), `track_orders_after` (epoch seconds)                                                                                                                                                |
| Report           | `USER#<u>`                    | `REPORT`                       | singleton snapshot: `report` (JSON string in the API's `report` shape), `as_of_audit_ulid` (the latest audit ULID at generation start), `updated_at` (generation instant)                                                        |

### Representative records

```json
{
  "pk": "USER#jordan#SKU#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "sk": "SKU",
  "sku_id": "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "scryfall_id": "f0a51425-d796-48b8-b68c-bc21fb465c81",
  "finish": "normal",
  "condition": "NM",
  "name": "Elvish Aberration",
  "set_code": "a25",
  "collector_number": "167",
  "fetchtcg_card_id": "mtg_167_c_a25_normal",
  "fetchtcg_set_id": 78,
  "version": 7,
  "dirty": true,
  "gsi1pk": "USER#jordan#DIRTY",
  "gsi1sk": "SKU#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "gsi2pk": "USER#jordan#SKUS",
  "gsi2sk": "NAME#elvish aberration#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "fetchtcg_listing_id": 975737,
  "last_published_quantity": 3,
  "last_published_price": "0.30",
  "last_published_at": 1765420800
}
```

```json
{
  "pk": "USER#jordan#SKU#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM",
  "sk": "UNIT#0000004242",
  "sequence_number": 4242,
  "status": "in_stock",
  "import_id": "01JEXAMPLEULID0000000000",
  "photos": [
    {
      "photo_id": "01JEXAMPLEPHOTOULID00000",
      "fetchtcg_url": "https://listing-img.fetchtcg.com/aBcDeFgH0123456789/listing/5314d615-8f24-4eb9-8613-de544566c2c2.jpg"
    }
  ]
}
```

### Transaction shapes

All mutations are `TransactWriteItems` including their audit entry; every mutation bumps the affected SKU's `version` with `ADD version :1` and sets `gsi1pk` to the dirty value. Order-lifecycle mutations that scale with order size (reserve, sell) split their writes into ordered transactions of at most 100 items (the `TransactWriteItems` cap), with the order write and the audit entry riding in the final transaction so the order item's state marks the mutation complete.

- **Import confirm**: conditional status flip `review → confirming` (single confirmer), one `UpdateItem ADD next_sequence_number :n` allocating the range, sequence numbers recorded on rows (skipped on retry if present), then chunked per-SKU transactions — conditional unit puts (carrying each row's `photos` list verbatim) + SKU dirty/version updates — where a replayed chunk fails its unit-exists condition and no-ops atomically; final flip `confirming → confirmed`. Confirm rejects with 409 while any keep row appraised at NZ$20+ has zero photos.
- **Reserve**: per SKU, a dirty + version update followed by that SKU's unit `in_stock → reserved` transitions, chunked at 100 items in that order (any landed unit flip implies its SKU dirty landed in the same or an earlier chunk); the conditional order put keyed by FetchTCG offer id and the reserve audit land in the final chunk. Allocation reclaims units already `reserved` with the same offer id (a prior run that died before the order put), so retries converge without re-writing them.
- **Release (void)**: order `awaiting_payment → voided` + units `reserved → in_stock` + dirty + version + audit.
- **Sell (confirm pull)**: per SKU, a version bump followed by that SKU's unit `reserved → sold` transitions, chunked at 100 items; the conditional order `to_pick → fulfilled` update and the sell audit land in the final chunk. Unit transitions tolerate already-`sold` units so a partially applied confirm converges on retry. No dirty flag — reserved units already left the projection and FetchTCG decremented at acceptance.
- **Remove / condition edit**: conditional unit transitions with dirty and version updates; condition edit is one transaction across two SKU partitions (delete + re-put the unit item with the same sequence number and `photos` list, both SKUs dirtied).
- **Publish clear**: set `dirty = false`, set `gsi1pk` to clean value, update the listing snapshot — conditional on `dirty = true AND version = :captured` (the version read before the recount). A delist clears the listing snapshot (`fetchtcg_listing_id` and published values removed); a later restock creates a fresh listing.

## Behavioral invariants and time semantics

- CSV rows are quantity-expanded; CSV row order is physical bottom-up (ManaBox stacks last-scanned-on-top). Review presents top-of-stack first (reverse CSV order); confirm assigns sequence numbers bottom-up (raw CSV order), so the reviewed stack slots into the box in one motion with placement order equal to location order.
- Sequence numbers are unique per user: allocation is an atomic counter `ADD` (disjoint ranges by construction), the confirming-status gate prevents double allocation for one import, and unit keys embed the sequence number so within-SKU duplicates are unwritable.
- Discarded and review rows never create units; only `keep` rows are confirmed. Appraisal decisions are final for an import: review cards are set aside physically and return through a later import once their cause is fixed.
- Import deletion is allowed only while `review` (409 otherwise) and removes the import and all its rows.
- English-only intake: non-English rows become `review`; unmapped sets and unresolvable identities become `review` rather than guesses.
- The FetchTCG listing projection counts only `in_stock` units. Reserved and sold units are excluded. Upward and downward corrections, including delisting at zero, occur only for SKUs dirtied by an audited mutation.
- A delist whose listing FetchTCG has already removed (for example an untracked offer consumed the last copy, or the seller deleted it on the site) converges: the delete's 404 is treated as already-delisted, the snapshot clears, and the run continues.
- Stock counts are derived from unit items at read time and never stored. Every mutation transaction bumps the SKU `version`; the publish clear is conditional on the version being unchanged since the recount, so a mutation landing mid-publish leaves the SKU dirty.
- The order phase always completes before the publish phase within a run: it runs on the run's first slice, then listing slices drain the dirty set ~100 SKUs at a time, checkpointing the cumulative count as the continuation.
- Only FetchTCG offers with `acceptedAt` strictly after the user's `track_orders_after` setting create order records and reservations. The cutoff comparison uses epoch-seconds instants; the advance loop for existing orders is unfiltered (orders already tracked cannot be orphaned by a date change).
- Order line `listed_price` is captured once at ingest from the offer payload and never rewritten. Orders ingested before this field existed deserialize it as null; `listed_total_price` is then omitted. `items[].price` is a line total; `listedPrice` is per-unit. `total_price` includes shipping and is not compared against listed value.
- Confirming a pull writes nothing to FetchTCG. Voiding an order releases units and dirties SKUs; the restored quantity reaches FetchTCG on the next publish run unless the seller already relisted on FetchTCG, in which case the projection converges as a no-op.
- SKU records are never deleted; a zero-count SKU keeps its record, is delisted on FetchTCG, and is reused on restock.
- Duplicate SQS deliveries, replayed job slices, and re-processed offers converge: job slices read the job item's continuation fresh, order creation is conditional on the offer id, unit transitions are conditional on current status, publish writes are absolute.
- Reserve and sell chunk their writes at DynamoDB's 100-item transaction cap with the order write and audit last, so the order item's state marks completion. A run that dies mid-reserve leaves the offer untracked and its units `reserved` with the offer's id; the next run reclaims exactly those units (forward-most first, no re-write) and finishes the order. A partially applied pull confirm leaves the order `to_pick` and retrying converges. If a mid-reserve failure is never retried and the offer stops appearing `ACCEPTED`, the reclaimed-but-untracked units stay `reserved` until manually adjusted.
- A re-enqueueing slice must strictly advance the continuation (the deduplication id `<job_id>#<continuation>` only distinguishes slices when it does); the consumer fails the job loudly rather than re-enqueue a non-advancing slice.
- At most one publish run is queued or running per user: `POST /publish` creates the job conditionally, responds 202 either way, and starts nothing new while one is already active; progress is observed via `GET /publish`.
- Job failures surface on the affected resource: an appraise failure sets `error` on its import; a publish failure appears in `GET /publish`. Recovery is user-initiated (fix the cause — typically the credential — and re-trigger; for a failed appraise, delete the import and re-upload).
- Market appraisal deduplicates FetchTCG reads per printing + finish within a job run and caches card detail reads per card id within a batch, so verifying ambiguous names never re-fetches the same candidate.
- Report generation is a single-slice job of pure reads plus one snapshot overwrite; re-runs and duplicate deliveries converge on the same result. At most one report job is queued or running per user (`POST /reports` responds 202 either way, mirroring publish).
- Report staleness: the job captures the latest audit ULID before reading any data; `GET /reports` reports stale when a later audit entry exists or the snapshot is older than 24 hours, so mutations landing mid-generation surface as stale on the next read.
- Report figures count `in_stock` units only for value, price buckets, top sets, top hits, and aging; reserved units appear only in the headline reserved count; `removed` units are excluded everywhere. Intake trends count every unit by `created_at` (preserved across condition edits); sold trends use the sell-time `updated_at`; revenue counts paid orders (`to_pick`, `fulfilled`) as the sum of offer line totals (shipping excluded) bucketed by first-seen month. A unit's price is its SKU's `last_published_price` falling back to appraisal `suggested_price`; SKUs with neither surface as an unpriced count and are excluded from value figures.
- Report week and month bucketing and aging bands use the fixed `Pacific/Auckland` timezone; weeks start Monday. Top hits rank by per-unit price (quantity is display detail), tie-broken by name ascending.
- Photos are immutable after confirm: management exists only on keep rows while the import is in review (max 5, JPEG, 4 MB), and the confirm gate (409 while any keep row appraised at NZ$20+ is photo-less) is the only photo enforcement anywhere — publish never blocks on photos.
- Listing upserts always project full image state: a photographed SKU sends its first (lowest sequence) in-stock unit's photos (`frontImage` first, the rest as `additionalImages`); a photo-less SKU omits `frontImage` (FetchTCG defaults to the stock card image) and sends `additionalImages: []`. An upsert at NZ$50+ with a photo-less first unit logs a warning and proceeds.
- Each photo is uploaded to FetchTCG at most once: the returned `imageUrl` persists on the unit's photo entry as `fetchtcg_url` and is reused thereafter; replayed slices converge on the same URL.
- NM is the default condition where none is provided. Timestamps are epoch seconds; ULIDs order imports, jobs, and audit entries by creation time.

## Source of truth

| Entity                               | Authoritative source                                              | Notes                                                                 |
| ------------------------------------ | ----------------------------------------------------------------- | --------------------------------------------------------------------- |
| Physical stack order and quantity    | ManaBox CSV row order and `Quantity`                              | reversed for review display; raw order = sequence assignment order    |
| Printing identity                    | ManaBox `Scryfall ID` (+ finish, condition columns)               | SKU computable offline from the row                                   |
| FetchTCG card identity               | Verified FetchTCG lookup, cached as `fetchtcg_card_id` on the SKU | set mapping is a generated, checked-in artifact                       |
| Unit existence, status, and position | DynamoDB unit items                                               | append-only; gaps are permanent                                       |
| Stock counts                         | Derived from unit items at read time                              | never stored; publish recounts units for its absolute write           |
| Listing quantity on FetchTCG         | Projection of in-stock unit count                                 | absolute upserts keyed by `cardId` + condition                        |
| Photo bytes                          | S3 object at `users/<user>/photos/<photo_id>.jpg`                 | immutable; never lifecycle-deleted                                    |
| Listing images on FetchTCG           | Projection of the first in-stock unit's photos                    | full image state on every upsert; stock-image default when photo-less |
| New-listing price                    | Pricing policy in this README                                     | computed at appraisal; publish lists the SKU's stored suggested price |
| Order state                          | FetchTCG seller offers list (`status`, `currentAction`)           | mapped to `awaiting_payment` / `to_pick` / `voided`                   |
| Offer vs listed price                | Offer payload at ingest (`items[].price`, `listing.listedPrice`)  | stored on the order line; not re-fetched later                        |
| Market price                         | FetchTCG `pricingData.NZ.tcgMarketPrice`                          | keep filter and pricing benchmark                                     |
| Audit history                        | Append-only audit items                                           | written in the same transaction as each mutation                      |
| Report figures                       | Stored report snapshot item                                       | derived from SKU/unit/order items at generation time                  |
| Report staleness                     | Latest audit ULID vs snapshot `as_of_audit_ulid`                  | plus a fixed 24-hour wall-clock backstop                              |

## Security and privacy

- All endpoints require Basic auth via the shared `auth_api` authorizer; all data is partitioned by user (`pk = USER#<user>…`).
- The FetchTCG refresh token lives only in the Secrets Manager secret; the settings endpoint writes it and never returns it (reads expose presence and last-updated only). Bearer tokens are minted per job run, held in memory, attached only to the four authenticated FetchTCG endpoints, and never logged.
- Buyer PII from offers (names, addresses, payment instructions, bank details, tracking) is never persisted; orders store card identity, quantities, and financial totals only.
- Audit entries and logs exclude credentials and raw FetchTCG response bodies.
- Listing photos live in a private SSE-S3 bucket; clients access them only through short-lived presigned GET URLs, which are never logged. Copies uploaded to FetchTCG are public marketplace images by nature.
- All external requests use HTTPS. FetchTCG automation is unsupported by its terms; the user owns that policy risk.

## Configuration and secrets reference

### Environment variables

| Name             | Required                         | Purpose                                     | Default behavior       |
| ---------------- | -------------------------------- | ------------------------------------------- | ---------------------- |
| `JOBS_QUEUE_URL` | yes (trigger + consumer Lambdas) | SQS queue for job and continuation messages | none; set by Terraform |

Fixed configuration lives in code: request spacing 1–2 s, bounded retries, request budgets, list page size 20, slice sizes (~100 rows per appraise slice, ~100 dirty SKUs per publish listing slice), country `NZ`, currency `NZD`, keep threshold NZ$0.25, price increment NZ$0.05, seller floor NZ$0.25. Photo constants: import gate NZ$20, publish warning NZ$50, 5 photos per row/unit, 4 MB max upload, 15-minute presign TTL. Report constants: staleness backstop 24 h, bucketing timezone `Pacific/Auckland`, price buckets $0.25–$0.50 / $0.50–$1 / $1–$2 / $2–$5 / $5–$10 / $10+ NZD, aging bands 0–30 / 31–90 / 91–180 / 180+ days, top sets 10, top hits 10.

### Secret shape

Secrets Manager secret `tcg_inventory`:

```json
{
  "<user>": "<firebase refresh token>"
}
```

Rotated refresh tokens returned by Firebase are written back to the same key.

## Performance envelope

- Scale target: 10,000+ units, ~5,000–10,000 SKUs/listings per user; DynamoDB request volume at this scale is negligible.
- SKU browse is a single GSI2 query returning identity fields only (no unit fan-out, no counts); detail derives counts from the partition query which returns the SKU and all its units in one shot.
- Job Lambdas: 900 s timeout with the module's default 1769 MB memory (the 1-vCPU point — keeps Java cold starts fast; the GB-second cost of idle FetchTCG pacing still sits far inside the always-free compute allowance). HTTP handlers use module defaults (10 s).
- FetchTCG pacing dominates: an appraise slice of ~100 rows runs minutes; a daily publish run (typical daily delta) runs single-digit minutes; large publish backlogs (for example a first full-inventory publish) checkpoint and re-enqueue every ~100 SKUs; jobs re-enqueue continuations well before timeout.
- Photo volume is negligible (<10 photographed SKUs expected at target scale, ≤5 photos each); each photo uploads to FetchTCG once ever, inside the existing pacing.
- Report generation makes no FetchTCG calls: it pages all SKU records via gsi2 (~5–10 pages) and queries each SKU partition once (~25–100 s sequential at target scale), completing in a single slice. `GET /reports` is one item read plus two small queries.
- SQS consumer maximum concurrency 1; visibility timeout exceeds the function timeout.
- Everything fits the repo's serverless cost posture (Lambda/SQS free tiers; Secrets Manager ~US$0.40/month).

## Testing and quality gates

- Unit tests: pricing policy scenarios (keep filter, undercut tick, deep-discount guard, supported floor, sole-source premium, rounding, floor), condition translation, set mapping, sequence/block/location derivation, FetchTCG client pacing/retries/allowlist/fail-closed auth/delete-of-missing-listing tolerance with fixture responses (including seller-offer `listedPrice` and card `externalReferences.scryfallId` parsing), offer state mapping, report aggregation (price fallback chain, bucket and band edges, NZ-timezone bucketing, top-hits ordering and tie-break, paid-order filter, shipping excluded from revenue, removed-unit exclusion), report staleness comparison (as-of audit ULID and 24 h backstop), image-state projection (object shape, omission and replace rules, NZ$50 warning), and FetchTCG multipart upload encoding.
- Integration tests (DynamoDB Testcontainers, LocalStack SQS): import upload→rows, import list continuation paging, appraisal identity verification (variant printings resolve by Scryfall ID, unverified candidates fall through to review), confirm idempotency and double-confirm rejection, adjustments, reserve/release/sell transitions, chunked reserve and sell for orders past the 100-item transaction cap, crashed-reserve reclaim convergence, publish create/update/delist and conditional clear, publish checkpointing and continuation across listing slices, duplicate-delivery no-ops, masked credential handling, report job snapshot writes, `GET /reports` staleness transitions, `POST /reports` idempotency while active, row photo CRUD against LocalStack S3 (caps, status gates, 204 mutations, `GET` import `photos`/`needs_photos` and presigned reads), confirm photo freeze and gate 409, condition-edit photo carry, publish image projection with one-time `fetchtcg_url` persistence, order-phase `listed_price` capture, and order list/detail offered-vs-listed fields (including null baseline on legacy lines).
- E2E (LocalStack): import → appraise → confirm → publish → order → pull → confirm loop, then report generation and retrieval, plus the photo lifecycle (flagged row → photo → gated confirm → published images → sale swaps the listing to the next unit's photos). The ingested order asserts offered 1.50 against listed 2.00.
- Tests never call the live FetchTCG API.
- Required checks: `bazel build //tcg_inventory_api:all`, `bazel test //tcg_inventory_api:all`, then repo-level `bazel mod tidy` and `bazel run //:format`.

## Local development and smoke checks

- Focused suites: `bazel test //tcg_inventory_api:unit-tests`, `:integration-tests`, `:e2e-tests`.
- Minimal smoke flow (against deployed stack): set the credential via `PUT /settings`; `POST /imports` with a single-card CSV; poll the import to `review`; add a photo to the row from a phone (raw JPEG POST); `GET /imports/{import_id}` and verify it renders via the presigned URL; confirm; `POST /publish`; verify the listing appears on FetchTCG at the policy price; then remove the unit via `DELETE` and run publish again to verify the delist. Use only a throwaway low-value card for live smoke checks.

## End-to-end scenarios

### Scenario 1: daily import to listed stock

1. User uploads a 90-card ManaBox CSV; rows persist and the appraise job runs.
2. Appraisal resolves identities (duplicate printings within the run skip FetchTCG search), applies the keep filter, and prices keepers; three rows become `review` (one non-English, one unmapped set, one below threshold is `discard`).
3. User reviews top-of-stack first, physically removes the discards, sets aside the review cards, and confirms.
4. Confirm allocates sequence numbers 4200–4286, appends 87 units bottom-up, dirties 61 SKUs, and returns placement instructions ("A42-0 through A42-86").
5. User boxes the stack in one motion and triggers publish; the order phase finds nothing new; the publish phase upserts 61 listings (creates priced by policy, updates as absolute quantities) and clears the markers.

### Scenario 2: offer accepted, paid, and pulled

1. A buyer's offer for two copies is accepted on FetchTCG; FetchTCG takes the stock off-market.
2. The next publish run's order phase sees `status = ACCEPTED`, creates the order, captures each line's offered total and listing `listedPrice`, and reserves the two forward-most in-stock units; the SKU is dirtied but its projection (in-stock count) already matches FetchTCG's decrement, so the publish phase makes no write.
3. The buyer pays; a later run sees `currentAction` past payment confirmation and marks the order `to_pick`.
4. The user opens the order, sees whether the offer is below, at, or above list, then pulls both units in one forward pass and confirms; units become `sold` with no FetchTCG write.

### Scenario 3: void releases and relists safely

1. A buyer never pays; the reserved order's offer leaves `ACCEPTED`.
2. The order phase voids the order, releases the units to `in_stock`, dirties the SKU, and flags the order for review.
3. If the seller already used FetchTCG's relist action, the publish phase recount matches the restored listing and converges as a no-op; otherwise the publish phase restores the quantity itself. Reserved stock was never re-projected while the offer was pending.

### Scenario 4: condition edit republishes both SKUs

1. The user regrades a unit from NM to LP.
2. One transaction moves the unit item to the LP SKU (same sequence number), dirties both SKUs, bumps both versions, and writes one audit entry.
3. The next publish updates the NM listing quantity (delisting it if the count reached zero) and creates or updates the LP listing at the policy price.

### Scenario 5: report snapshot after a day's activity

1. After confirming an import and running publish, the user opens the reports tab; `GET /reports` returns the previous snapshot marked stale because new audit entries postdate its as-of audit ULID.
2. The client posts `/reports`; the job captures the latest audit ULID, pages every SKU and its units, pages orders, and overwrites the snapshot.
3. `GET /reports` now returns fresh figures: updated totals, today's intake in the weekly trend, and any newly listed hits.

### Scenario 6: listing a hit with photos

1. An import contains a card appraised at NZ$60; its row is flagged `needs_photos` and confirm returns 409 while it stays photo-less.
2. The user opens the same import on their phone and photographs the card front and back; confirm then freezes the photos onto the created unit.
3. Publish uploads each photo to FetchTCG once, then creates the listing carrying the unit's photos as its images.
4. The unit sells while a second copy (photographed at its own import) remains; the next publish swaps the listing images to that unit's photos.
5. A card that entered below the NZ$20 gate and later reprices past NZ$50 publishes with FetchTCG's stock-image default plus a warning log; remove + re-import captures photos if the user cares to fix it.
