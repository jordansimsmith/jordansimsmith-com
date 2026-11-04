# Inventory mutation migration plan

## Ordered task list

Each unchecked task is one reviewable commit, including its implementation, relevant tests, caller/build changes, and current-state README updates. Implement in order. Do not introduce all interface methods up front with unsupported placeholder implementations. Task details and acceptance criteria appear below.

**Listings prerequisite complete:** the separate [Listings migration plan](listings-migration-plan.md) absorbs task 5. Tasks 6 through 11 are complete; continue at task 12. Production activation still requires the controlled Listings data copy. Do not build a second publication acknowledgement operation.

- [x] **1. Migrate unit removal.** Add `deleteUnit`, server-owned transactional mutation/audit infrastructure, and the thin Inventory API handler. This is the first implementation change.
- [x] **2. Migrate condition changes.** Add `updateUnit`, the shared enum `Condition`, server-owned SKU identity derivation and lazy SKU initialization, and migrate the existing handler. `fetchtcg_set_id` is required; `getSku` is strongly consistent.
- [x] **3. Expose the SKU facts needed by workers.** Extend shared SKU/unit contracts and `findSkus` with exclusive browse and dirty/clean query modes, without root persistence access. The client SKU exposes one nullable `price` sourced from `suggested_price`; Listings now takes over marketplace association ownership in the prerequisite below.
- [x] **4. Migrate price writes and repricing access.** Add last-write-wins `updateSku` and migrate repricing's SKU reads/writes while preserving its workflow.
- [x] **5. Complete the Listings prerequisite (absorbs this task).** Finish [Listings tasks 1–7](listings-migration-plan.md), including versioned dirty patches, publication/Orders/Reports caller changes, and transfer/removal of legacy listing fields. No separate Inventory implementation task remains here.
- [x] **6. Implement retry-safe unit creation.** Add singular `createUnit`, sequence allocation, lazy initialize-only SKU creation, and immutable operation results.
- [x] **7. Make import placement instructions handle gaps.** Split placement groups at sequence gaps as well as storage-block boundaries; preserve the frontend's existing instruction shape. Placement instructions now describe contiguous runs of assigned units and can repeat a block label.
- [x] **8. Migrate import confirmation.** Create one unit per keep row through Inventory, persist actual results, and recover by stable operation ID.
- [x] **9. Implement single-unit allocation.** Add `reserveUnit`, Inventory-owned selection, durable allocation results, and reservation lifecycle records.
- [x] **10. Implement reservation release.** Add ownership-checked, replay-safe `releaseUnit`, including compatibility with existing reserved units.
- [x] **11. Implement unit sale.** Add ownership-checked, replay-safe `sellUnit`, sharing the same reservation lifecycle foundation.
- [x] **12. Stop writing non-inventory audits.** Remove Orders' lifecycle audit writes and the cross-vertical audit-builder dependency; defer Reports freshness redesign.
- [ ] **13. Migrate cancellation and reservation reads.** Use Inventory for release and the reads needed by cancellation/fulfillment checks. Keep legacy reservation selection working until task 15.
- [ ] **14. Migrate fulfillment.** Use `sellUnit` from the fulfillment processor, with explicit dependencies and preserved resumable order progress.
- [ ] **15. Migrate Orders allocation and reservation progress.** Let Inventory select units; save actual allocations, recover partial orders, and remove explicit-unit planning.
- [ ] **16. Remove retired mutation access and document remaining gaps.** Delete the legacy mutation repository and unused write helpers/bindings, update strategy ownership statements, and retain only explicitly identified legacy reads.

## Purpose and scope

Build on the existing Inventory client/server/API packages and their `findSkus`, `getSku`, `findUnits`, and `getUnit` operations. Move mutations and their callers behind the client boundary in small changes. The target is Inventory-owned mutations, not a new deployment, database split, or Jobs redesign.

Follow the [modular monolith strategy](../tcg_inventory_api/docs/modular-monolith-strategy.md). The [API README](../tcg_inventory_api/README.md) describes current behavior and must change with each implementation commit. The drafts in [archived](archived/) are historical context; this file supersedes their outstanding Inventory mutation assumptions.

All contracts below describe the end state. Supporting read extensions and receipt layouts are concrete implementation defaults needed to realize the agreed behavior; they do not require bulk mutation APIs or new public HTTP routes.

## Confirmed decisions

1. Inventory owns SKUs, physical units, sequence/location allocation, stock transitions, mutation deduplication, and the audits for its mutations.
2. Audit is an Inventory implementation concern, not a shared cross-vertical service. Orders stops emitting order-lifecycle audit entries. Reports must eventually stop reading audit records; its redesign is separate work.
3. Use singular `createUnit` and `reserveUnit`. Consumers loop in their required order. Neither an import nor an order becomes one unbounded DynamoDB transaction.
4. Every service method has its own request and response records, including empty responses, and explicitly declares checked `throws ServiceException`.
5. Use domain verbs `reserveUnit`, `releaseUnit`, and `sellUnit`. These agreed names intentionally depart from the repository's usual CRUD-only mutation naming convention.
6. Use `updateSku` patch semantics for straightforward editable attributes, initially suggested price. Do not add a separate `updateSkuPrice` method or an arbitrary unit-status setter.
7. Creation and allocation accept opaque, caller-owned operation IDs. Inventory scopes deduplication to user and operation kind. The first successful result wins. Do not compare reused IDs' payloads or reject a changed payload.
8. A caller does not create the SKU first. `createUnit` lazily initializes a missing SKU. Existing Inventory SKU metadata and suggested price survive stock arrivals. Listings-owned associations are separate and are not copied or mutated by Inventory.
9. Inventory allocates sequences. Contiguous ranges per import and gapless counters are not promised. Imports saves actual results and explains every placement group, including gaps within one storage block.
10. Inventory selects the earliest available sequence when reserving stock. Orders asks for one unit of a SKU and records the returned selection.
11. Release and sale target an exact allocated unit and require its reservation owner. Repeats of the same completed transition succeed without another mutation or audit.
12. Ordinary SKU patches use last-write-wins. Repricing no longer supplies its old expected SKU version.
13. Listings acknowledges a captured version through `updateSku(dirty=false, version=...)`. A mismatch propagates DynamoDB’s conditional-check exception, leaving the SKU dirty and allowing the publish attempt to retry. Dirty-only patches generate no audit; no dedicated publication method exists.
14. `version` is part of the shared `Sku` service contract and therefore also appears in HTTP SKU responses. The frontend may ignore it. It is not a DynamoDB key or a hidden server-only value.
15. Deletion is soft removal. Removing an already-removed unit succeeds without another audit. The first removal still writes its inventory audit. Reserved/sold units cannot be manually removed.
16. Condition changes move only in-stock units. An unchanged condition succeeds without writes or audit. A completed move retried against its old SKU may return `NotFoundException`; no operation receipt is added for this action.
17. No synchronous mutation consults active-job state. Background admission, per-user FIFO execution, queues, job history, and existing task-start checks remain unchanged.
18. Trust other verticals' successful contracts and opaque identifiers. Do not add Catalog/Photos/FetchTCG prevalidation, defensive fallback queries, or broad transaction-failure translation.

## Ownership and remaining scope

| Owner                | Responsibility                                                                                                                                                             |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Inventory            | SKU/unit records and indexes; sequence counters; reservation ownership and allocation; creation/allocation receipts; stock/price mutation audits; versioned dirty tracking |
| Imports              | Review/confirmation lifecycle, ordered rows, operation-ID construction, row-level creation progress, and placement instructions                                            |
| Orders               | Offer interpretation, order lines/quantities/prices, reservation references, saved allocated sequences, payment/cancellation/fulfillment lifecycle, and workflow progress  |
| Pricing              | Price calculation and repricing orchestration; it supplies the calculated amount to Inventory                                                                              |
| Listings             | Current provider listing pointers, retained listing-ID-to-SKU associations, single-SKU marketplace synchronization, and captured-version dirty clearing                    |
| Publish workflow     | Existing order-before-listing orchestration, background admission/adapters, dirty-batch draining, counts, and checkpoints                                                  |
| Photos               | Photo IDs, JPEG storage/bytes, and URL resolution; Inventory stores ordered IDs only                                                                                       |
| Games                | Game metadata used when Inventory needs it to derive a new SKU identity or check supported finishes                                                                        |
| Reports, future work | Its read boundary and freshness model, without audit-table dependency                                                                                                      |

The Listings prerequisite moves existing `fetchtcg_listing_id` associations to separate Listings-owned records and removes that field from Inventory contracts/models. Drop `last_published_quantity`, `last_published_price`, and `last_published_at` from application code instead of moving them. Reports reads current `Sku.price()` through Inventory. A controlled one-off Python copy preserves existing listing associations before cutover; there is no runtime compatibility fallback. Inventory never owns a Listings pointer, historical association, or publication snapshot.

Do not put import rows, order records, settings, job records, or provider calls into Inventory transactions. Inventory audit writes are local to their authoritative mutation. Cross-owner completion is a resumable sequence of service calls and owner-local progress saves.

This series does not extract Imports, Orders, Reports, or Jobs into full client/server packages. It does not add bulk APIs, a general audit service, post-confirm photo editing, new price/photo caches, automatic compensation, or a cleanup framework.

## Package, wiring, and error conventions

Continue using:

```text
tcg_inventory_api/src/main/java/com/jordansimsmith/tcginventory/inventory/
  api/       BUILD.bazel, thin HTTP handlers
  client/    BUILD.bazel, InventoryService and public records
  server/    BUILD.bazel, operation services, persistence, InventoryModule, Locations
```

Every public client type lives in its own file. Use `@JsonProperty` with snake_case names and `javax.annotation.Nullable` on nullable values. Clients depend on `//lib/service`, Jackson annotations, and nullable annotations only. Add no client unit-test target. Extend the existing client test-helper target with happy-path fake behavior as consumers need it; no interaction logs or failure injection.

Keep one operation class per service method and the anonymous delegate in `InventoryModule`. Add low-level DynamoDB, clock, ULID, and Games client dependencies directly when actually needed. Keep the server independent of root `:lib`, `:inventory-lib`, and legacy models. Extend the server-owned read models as needed; writes must preserve unrelated persisted attributes.

Keep current server visibility for root wiring and dedicated tests. The root-package visibility gap remains until the other verticals move out. Factories expose `InventoryService`, not repositories, receipt tables, audit builders, or provider models.

Use explicit dependencies in migrated processors. Construct them inline in `JobsHandler`; do not add processor-construction wrappers or factory-taking overloads to avoid updating callers.

Expected validation, absence, and lifecycle failures use shared HTTP-named checked exceptions. Unexpected invariant/storage failures propagate. Let `ServiceException` bubble naturally. Do not catch transaction failures just to issue several rereads for a more precise HTTP response. An actual storage race may fail the request/job attempt normally. Catch a known condition only where recovery is required, such as a committed operation receipt after a duplicate race. Versioned dirty-patch mismatches propagate as ordinary storage failures so background callers can retry.

## End-state service interface

```java
public interface InventoryService {
  FindSkusResponse findSkus(
      RequestContext ctx, FindSkusRequest request) throws ServiceException;

  GetSkuResponse getSku(
      RequestContext ctx, GetSkuRequest request) throws ServiceException;

  FindUnitsResponse findUnits(
      RequestContext ctx, FindUnitsRequest request) throws ServiceException;

  GetUnitResponse getUnit(
      RequestContext ctx, GetUnitRequest request) throws ServiceException;

  CreateUnitResponse createUnit(
      RequestContext ctx, CreateUnitRequest request) throws ServiceException;

  DeleteUnitResponse deleteUnit(
      RequestContext ctx, DeleteUnitRequest request) throws ServiceException;

  UpdateUnitResponse updateUnit(
      RequestContext ctx, UpdateUnitRequest request) throws ServiceException;

  UpdateSkuResponse updateSku(
      RequestContext ctx, UpdateSkuRequest request) throws ServiceException;

  ReserveUnitResponse reserveUnit(
      RequestContext ctx, ReserveUnitRequest request) throws ServiceException;

  ReleaseUnitResponse releaseUnit(
      RequestContext ctx, ReleaseUnitRequest request) throws ServiceException;

  SellUnitResponse sellUnit(
      RequestContext ctx, SellUnitRequest request) throws ServiceException;
}
```

Only the existing manual removal and condition HTTP routes migrate to new handlers. User-wide and dirty/clean `findSkus` modes are internal service operations; no endpoint is added for them. Do not introduce HTTP PATCH SKU pricing or reservation routes in this series.

## Shared read contracts and worker support

Reuse the shared `Sku`, extending it with the fields workers currently obtain from legacy SKU records. Monetary values are nullable JSON numbers/`BigDecimal`; timestamps are nullable epoch seconds.

```java
public record Sku(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") Condition condition,
    @JsonProperty("price") @Nullable BigDecimal price,
    @JsonProperty("fetchtcg_card_id") @Nullable String fetchtcgCardId,
    @JsonProperty("fetchtcg_set_id") int fetchtcgSetId,
    @JsonProperty("version") int version) {}

public record Unit(
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location,
    @JsonProperty("status") UnitStatus status,
    @JsonProperty("photos") List<String> photos,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") @Nullable Long updatedAt,
    @JsonProperty("reservation_id") @Nullable String reservationId) {}
```

The stored `reservation_id` already represents reservation ownership; map it to client `reservation_id` without renaming existing storage attributes. Retain owner information on sold units. Released units have no current owner; their historical reservation record still proves the completed release.

`UnitStatus` retains `IN_STOCK`, `RESERVED`, `SOLD`, and `REMOVED`, with existing lowercase JSON values. `created_at` remains required. Do not reintroduce nullable creation timestamps, image URLs, unit counts, or embedded units on `Sku`.

Extend `FindSkusRequest.game` to be nullable for user-wide worker reads. A supplied game remains nonblank and opaque. With no game, query the existing user-wide GSI2 prefix and require an absent/blank search; do not invent cross-game name-prefix semantics. Existing frontend requests continue supplying game. Preserve the HTTP browse route's required-game behavior in its adapter, even though the internal service can now list all games. Existing search, ordering, 20-item default, 1–100 limits, one-page behavior, and opaque DynamoDB continuations otherwise remain.

`FindSkusRequest.dirty` is nullable. A null value selects browse mode: game is optional, search requires game, and GSI2 preserves its existing game/name ordering. A supplied `true` or `false` selects the matching dirty or clean GSI1 partition; any supplied game or search value is forbidden in this mode. The service validates and branches between two repository methods: `findSkus` queries only the browse index, while `findDirtySkus` queries the dirty/clean index. The two modes are exclusive and use their indexes directly without DynamoDB filter expressions. Both default to 20 items, accept limits from 1 through 100, and return one page. The public `/skus` handler continues requiring a game and always sends `dirty=null`; worker callers use the client directly.

```java
public record FindSkusRequest(
    @JsonProperty("game") @Nullable String game,
    @JsonProperty("search") @Nullable String search,
    @JsonProperty("dirty") @Nullable Boolean dirty,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}

public record FindSkusResponse(
    @JsonProperty("skus") List<Sku> skus,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}

```

Dirty/clean reads use GSI1 and are eventually consistent. They do not reserve or claim SKUs. The Publish worker supplies a candidate SKU ID to Listings. Listings strongly reads that SKU and captures its version before reading units and constructing its projection; it must not fetch a newer version just before acknowledging old inputs. `getSku` and exact unit reads remain strongly consistent.

Keep existing strongly consistent `getSku` and strongly consistent unit reads. Reuse those authoritative repository reads for mutations rather than introducing a duplicate `getSkuForMutation`; do not use an eventually consistent browse lookup as the authority for stock transitions.

Consumer loops follow every continuation, including an empty page with a continuation. Keep the small loops local to consumers and use `do/while`; no shared cross-vertical paginator. Malformed continuation encoding produces safe `BadRequestException`; no request-parameter binding or user/SKU binding validation is added. Database query failures propagate.

Adding fields to the common records changes their JSON responses too. Update typed fixtures/fakes where constructors require it; frontend consumers may ignore additive fields and need no new version workflow.

The other existing read wrappers remain:

```java
public record GetSkuRequest(@JsonProperty("sku_id") String skuId) {}

public record GetSkuResponse(@JsonProperty("sku") Sku sku) {}

public record GetUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber) {}

public record GetUnitResponse(@JsonProperty("unit") Unit unit) {}

public record FindUnitsRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}

public record FindUnitsResponse(
    @JsonProperty("units") List<Unit> units,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
```

## Mutation contracts

The following sketches are separate source files, not nested types. Imports of `JsonProperty`, `Nullable`, `BigDecimal`, and `List` are omitted for readability.

### Create one unit

```java
public record CreateUnitRequest(
    @JsonProperty("operation_id") String operationId,
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") Condition condition,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("price") @Nullable BigDecimal price,
    @JsonProperty("fetchtcg_card_id") @Nullable String fetchtcgCardId,
    @JsonProperty("fetchtcg_set_id") int fetchtcgSetId,
    @JsonProperty("photos") List<String> photos) {}

public record CreateUnitResponse(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location) {}
```

- Require a nonblank operation ID and usable stock identity/creation fields. Use the Inventory client Condition enum. Require a nonnull ordered photo-ID list; empty is valid. Keep existing supported finish and condition rules.
- Once the operation ID is usable, check for its committed result before validating/deriving the rest of a new creation. A replay returns that result without comparing or reinterpreting replacement creation fields.
- Treat accepted IDs as opaque. Games is consulted when deriving SKU identity because its external-source metadata is actually required. Do not call Catalog, Pricing, Photos, or FetchTCG. Supplied photo IDs and card/provider metadata are trusted.
- Inventory derives the existing SKU ID encoding from game metadata, external ID, finish, and condition. Callers do not supply SKU IDs, sequence numbers, locations, timestamps, or initial stock status. New units are in stock.
- Supplied metadata and price initialize a missing SKU only. Preserve existing Inventory metadata, price, and card/set provider references. Do not read, copy, or initialize Listings-owned listing pointers or historical associations. A stock arrival dirties and versions an existing SKU without refreshing its descriptive fields.
- Preserve existing string-backed monetary persistence. New supplied amounts must be nonnegative and representable at cent precision; store normalized cent values. Null initialization prices/provider references retain the current optionality; an unpublishable SKU fails when a workflow actually needs the missing fact.
- Allocate one sequence for the authenticated user and game, using the existing counter/layout and `Locations` rules. Allocation may commit before the creation transaction. A later failure may consume a number; no gap-repair framework is introduced.
- The bounded creation transaction commits the unit, SKU initialization/dirty/version update, inventory audit, and immutable creation result. A separate counter allocation is an accepted gap, not a second stock creation.
- Return assigned SKU ID, sequence number, and formatted location; creation timestamps remain on the unit. A replay returns the original placement result even if that unit has moved, been removed, or changed status later. It must never recreate or reset the unit.
- No import identifier is required for correctness. Existing `import_id` attributes may remain as legacy provenance; operation identity and Imports' own row progress replace dependence on that field for deduplication. Do not pass an Import model into Inventory.

The default result receipt has no expiry. This keeps arbitrarily delayed owner retries safe without retention-window coordination. Expiring receipts is separate work requiring an explicit replay window.

### Remove one unit

```java
public record DeleteUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("reason") @Nullable String reason) {}

public record DeleteUnitResponse() {}
```

| Stored state     | Result                                                                                          |
| ---------------- | ----------------------------------------------------------------------------------------------- |
| In stock         | Atomically mark removed, set update time, dirty/version the SKU, and write one adjustment audit |
| Removed          | Success with no writes, version change, or additional audit                                     |
| Reserved or sold | `ConflictException("unit is not in stock")`                                                     |
| Absent           | `NotFoundException("Not Found")`                                                                |

Require a nonblank SKU ID and a valid nonnegative sequence number; zero is a valid sequence. Perform strongly consistent server-internal reads including removed records. The public `getUnit` operation continues hiding removed units. Do not delete physical photo objects or the SKU record. Preserve optional reason semantics.

### Change condition

```java
public record UpdateUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("condition") String condition) {}

public record UpdateUnitResponse(
    @JsonProperty("sku_id") String skuId) {}
```

Validate the owned condition value, require the source SKU/unit, and require in-stock status. A same-condition request succeeds with the current SKU ID and no writes/audit. Missing resources produce not found; incompatible stock states conflict.

Derive the destination SKU through Inventory-owned identity logic and Games metadata. Lazily initialize a missing destination from the source SKU; preserve existing destination values. Atomically delete the source unit conditionally on in-stock state, put the destination unit only when that key is free, dirty/version both SKUs, and write one adjustment audit.

Keep sequence, creation time, provenance, and ordered photo IDs. Set the mutation time consistently with existing update conventions. Preserve unrelated SKU attributes and do not fetch photo bytes/URLs.

No operation ID is required. A completed move retried against the old SKU may return not found. The frontend keeps navigating to the returned destination SKU on success. This accepted recovery behavior avoids a receipt protocol for manual moves.

### Patch a SKU

```java
public record UpdateSkuRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("price") @Nullable BigDecimal price,
    @JsonProperty("dirty") @Nullable Boolean dirty,
    @JsonProperty("version") @Nullable Integer version) {}

public record UpdateSkuResponse() {}
```

Price (stored as the SKU's suggested price) and versioned dirty transitions are patchable. Omitted/null price leaves it unchanged and does not clear it; with no price or dirty supplied, an empty patch succeeds without writes. The response is empty because current callers do not need the resulting SKU or a dirty-transition outcome. Unknown JSON fields are rejected normally; no ignore-unknown annotation.

The Listings prerequisite adds `dirty` and `version`. Require version whenever dirty is supplied, reject simultaneous nonnull price and dirty, and reject version without dirty. `dirty=false` clears an existing dirty SKU only when its captured version still matches, without incrementing version. `dirty=true` conditionally marks dirty and increments version so an older acknowledgement cannot erase the explicit republish request. A mismatched condition propagates DynamoDB’s conditional-check exception without diagnostic rereads. Missing stored version matches captured zero. Dirty-only updates emit no audit and cannot recreate an absent SKU. Preserve initial missing-SKU detection and propagate unrelated storage failures.

A supplied price must be nonnegative and cent-representable, normalized to two decimals. Zero is valid. Missing SKU returns not found. Updates cannot create an absent SKU or overwrite unrelated attributes.

No caller-supplied `expectedVersion` exists. Applying a price calculated before another mutation is allowed. Read the previous value inside Inventory for the audit, then update price/dirty/indexes/version and the audit locally. Require that the SKU still exists, but do not compare against the version captured by the repricing caller. Ordinary storage contention may fail normally rather than introducing elaborate retry/translation code.

Preserve the existing behavior that a supplied unchanged price still dirties/versions the SKU and records the price-update effect. Do not introduce an implicit equality no-op into repricing. A repeat without an operation ID may produce another price audit; exactly-once price patches were not requested.

Inventory generates audit IDs/times. The generic mutation does not require job records, a job ID, or a Pricing-specific audit builder. Use an inventory price-update event with before/after values. Historical `reprice` audit entries remain readable as history; Reports must not depend on event names for freshness after its future migration.

### Allocate and reserve one unit

```java
public record ReserveUnitRequest(
    @JsonProperty("operation_id") String operationId,
    @JsonProperty("reservation_id") String reservationId,
    @JsonProperty("sku_id") String skuId) {}

public record ReserveUnitResponse(
    @JsonProperty("sequence_number") int sequenceNumber) {}
```

Require nonblank IDs and scope everything to `ctx.user()`. The operation ID identifies one allocation attempt across retries. The reservation ID is the opaque owner reference shared by an order's allocations. These values are not interchangeable.

After checking for a prior result, Inventory queries ascending sequences through the full unit partition until it finds the earliest in-stock unit. Follow pages containing only removed/reserved/sold units. A query is selection, not a lock; the transaction must condition on the selected unit still being in stock and unowned.

Commit that unit's reservation owner/status/time, SKU dirty/version update, reserve audit, immutable allocation result, and reservation lifecycle state in one bounded Inventory transaction. Each call allocates exactly one unit; there is no hidden multi-unit transaction.

The normal insufficient-stock result is safe `ConflictException("No in-stock units are available for this SKU")`. A missing SKU is not found. A conditional selection race may fail as an ordinary storage failure; the owner can retry the same operation ID. Do not add a reservation retry framework or arbitrary backoff in the service.

A replay returns the original selected sequence number without allocating again, including when its reservation has since been released or sold. Do not revive a terminal reservation. Reused operation IDs are trusted and do not cause payload-comparison validation.

### Release or sell an allocated unit

```java
public record ReleaseUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("reservation_id") String reservationId) {}

public record ReleaseUnitResponse() {}

public record SellUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("reservation_id") String reservationId) {}

public record SellUnitResponse() {}
```

Both operations require the specified unit to belong to that reservation. Neither chooses a substitute. A first successful transition atomically writes unit state/time, SKU bookkeeping, the terminal reservation state, and its inventory audit.

- Release changes reserved to in stock and removes the current unit owner. Dirty/version the SKU because available quantity changes.
- Sale changes reserved to sold and retains the historical owner. Preserve the existing SKU version bump and dirty semantics; reserved stock was already excluded from available listing quantity.
- A repeated matching terminal transition returns success without another audit or version bump.
- A sale after release, release after sale, or attempt against another owner's reservation conflicts.
- Missing unit/reservation is not found unless the durable terminal reservation proves this exact operation already completed.

Do not interpret any unowned in-stock unit as proof that a particular reservation was released. A delayed retry of reservation A's release must not release reservation B or fail merely because the unit moved after A's release. The terminal reservation record supplies that proof.

The unit and reservation records are both Inventory-owned. They may participate in the same transaction. An Order record never participates.

### Acknowledge synchronization through a dirty patch

Listings owns listing IDs and remote persistence. Inventory receives only the SKU ID and captured version through the existing patch operation:

```java
inventoryService.updateSku(
    ctx, new UpdateSkuRequest(sku.skuId(), null, false, capturedVersion));
```

The response remains empty. A stale version propagates DynamoDB’s conditional-check exception; no publication-specific response, snapshot, quantity, price, or timestamp is accepted or stored. Listings saves its own current pointer/history after provider success and before this call, so a local failure cannot falsely acknowledge an unsaved association.

The version condition is essential even though price patches are last-write-wins: a late acknowledgement must not erase evidence that another stock/price mutation needs synchronization. It does not make provider calls transactional. A stale marketplace projection until a later publish is accepted. The [Listings prerequisite](listings-migration-plan.md) owns implementation and acceptance for this flow.

## Atomic replay and persistence details

Keep existing SKU/unit/counter/audit key layouts, attributes, GSI1 dirty queue, GSI2 browsing, and GSI3 physical-location access. Preserve standalone domain fields independently of key strings. Unit ownership uses `reservation_id`; rename legacy unit `order_id` through `006-rename-unit-reservation-id.py` before cutover. Pause processing and writes, execute the migration, deploy matching readers/writers, then resume. Both legacy and server-owned unit models use the new attribute, with no fallback or dual writes. Do not rewrite photo lists or backfill stock data just to change ownership.

Introduce additive, private Inventory receipt/lifecycle records. One workable layout is an Inventory-owned user partition with keys:

```text
pk = USER#<user>#INVENTORY
sk = OPERATION#CREATE_UNIT#<operation_id>
sk = OPERATION#RESERVE_UNIT#<operation_id>
sk = RESERVATION#<reservation_id>#SKU#<sku_id>#UNIT#<sequence>
```

Store original IDs as standalone attributes and original response values in operation receipts. Creation operation IDs occupy the entire fixed-prefix suffix and are stored verbatim. Reservation IDs follow the caller convention of containing no `#`; SKU IDs remain plain inside reservation keys. No Base64 encoding is needed. No caller knows this layout.

Reservation lifecycle state is reserved/released/sold. It may be a small dedicated record rather than overwriting immutable operation results. Retain terminal state without TTL initially. This is the minimal durable evidence for release/sale replay, not an Orders aggregate or shared workflow engine.

The critical rule is that the operation-result put and its authoritative mutation commit together:

```text
createUnit:
  strongly read creation receipt; if present, return its original result
  derive identity and allocate one sequence
  transact:
    conditionally put unit
    initialize missing SKU fields; dirty/version SKU
    put inventory audit
    put immutable creation result, conditioned absent

reserveUnit:
  strongly read allocation receipt; if present, return its original result
  select earliest available unit
  transact:
    conditionally reserve selected unit
    dirty/version SKU
    put inventory audit
    put reservation lifecycle record
    put immutable allocation result, conditioned absent
```

Two duplicate attempts can both initially see no receipt. The absent-receipt condition permits at most one transaction to commit. Let transaction failures propagate without receipt recovery catches; the next caller retry reads the winning receipt. A failed creation attempt may burn an allocated sequence, which is explicitly allowed.

Distinguish creating a missing SKU from dirtying an existing one. A missing-SKU branch can conditionally put the complete initial SKU; an existing-SKU branch updates only dirty/index/version bookkeeping. Do not full-put a partial server read bean. In particular, `if_not_exists(price, :incomingPrice)` on an existing SKU would fill its absent price from an arrival, contrary to the agreed initialize-only-new-SKU rule. Existing optional null/absent metadata remains unchanged too. A race between two SKU creators may fail normally and be retried by the owner; it must not replace the winning SKU. Unit moves preserve all retained unit facts and prevent overwriting an occupied destination.

## Import confirmation and placement

Imports creates one unit at a time in stable row order. For each keep row, derive an operation ID from the durable import identity and original row position; do not generate a fresh UUID on each delivery or use its current loop index after filtering.

```java
var operationId = "import:" + importId + ":row:" + row.getPosition();
var created = inventoryService.createUnit(ctx, requestFor(operationId, row));

importsRepository.recordCreatedUnit(
    user, importId, row.getPosition(),
    created.skuId(), created.sequenceNumber(), created.location());
```

`requestFor` is illustrative construction, not a requirement for a private forwarding method. Build requests inline where clear.

Record returned SKU ID, sequence, and display location on the Import row using targeted owner-local writes. Retain the row's original creation inputs for repeat calls. If Inventory committed but the row save failed, repeat the same call and save the original result. Mark the parent confirmed only after every keep row has a durable creation result. Created stock is visible incrementally.

Do not reserve one range then infer later row sequences. Do not replay by looking at the current unit's import ID or original SKU location; it may have moved or been removed.

Placement instructions retain the existing frontend fields: block, from/to location, from/to card name, and unit count. Split a group whenever either:

```java
var hasGap = current.sequenceNumber() != previous.sequenceNumber() + 1;
var changedBlock = !blockOf(current.location()).equals(blockOf(previous.location()));
if (hasGap || changedBlock) {
  // finish the preceding instruction and start another
}
```

Use actual saved results in original row order. Location strings retain the documented `block-offset` display form; a small Import-local presentation helper may extract the block label, while Inventory owns generating location values. Do not format new result locations by importing `inventory.server.Locations`.

For `101, 102, 108, 109`, emit two groups, even though all four belong to one block. A normal block boundary also splits groups. Use actual row count rather than `last - first + 1` across a gap. The frontend must render multiple instructions with the same block label; do not key instructions solely by block.

Old confirmed imports already have saved sequences. Backfill their SKU IDs by matching the user, game, and sequence to the existing Inventory unit, and derive their locations from the saved sequences before removing the read-time compatibility path. A unit's current SKU is sufficient as the row's completion marker if its condition changed after import. Do not invent creation receipts or recreate historical stock.

## Orders allocation and progress

Orders still fetches every offers page before applying provider-derived changes. Preserve acceptance cutoff, offer mapping, stored offered/listed amounts, fulfillment metadata, user isolation, and order-before-listing execution.

Before allocations, validate offer shape and resolve listing IDs through `ListingService#findListings` in batches of at most 100, including known historical associations. Preserve the lookup already migrated by the Listings prerequisite; never rebuild it from Inventory SKU fields. Orders no longer queries units to preassign sequences. It first saves a durable `reserving` order with stable lines and empty/partial actual allocation lists.

Use immutable stored line ordinals and quantity ordinals to construct operation IDs. Preserve provider line order on initial creation; retries iterate the stored lines rather than rebuilding their identity from a later offer response. Repeated SKU lines require distinct IDs.

```java
for (int lineIndex = 0; lineIndex < order.lines().size(); lineIndex++) {
  var line = order.lines().get(lineIndex);
  for (int ordinal = 0; ordinal < line.quantity(); ordinal++) {
    var operationId =
        "order:" + order.id() + ":line:" + lineIndex + ":unit:" + ordinal;
    var allocation = inventoryService.reserveUnit(
        ctx, new ReserveUnitRequest(operationId, order.id(), line.skuId()));
    orderRepository.recordAllocation(
        user, order.id(), lineIndex, ordinal, allocation.sequenceNumber());
  }
}
```

The code illustrates ownership and identity; actual calls use the existing Order model's accessors. Saving the same ordinal again must preserve that allocation rather than append duplicates. Inventory commit followed by Order save failure is recovered by the same operation ID. Completion requires every line to have its requested number of actual allocations.

One order with 150 copies makes 150 bounded Inventory calls; avoid rewriting all accumulated allocation data in a cross-owner transaction. Use owner-local targeted progress updates as appropriate. Keep cancellation/fulfillment replay based on saved exact allocations.

### Accepted change from complete-history stock preflight

The existing code preflights stock demand across all new offers before reserving any. Inventory-selected singular allocation deliberately replaces that guarantee. Structural offer/listing validation can still precede allocation, but availability is decided by each Inventory transaction.

A later shortage can leave earlier units/orders reserved and the current order partially `reserving`. Preserve that state and stop the publish before listing publication. A later run retries the same operations; there is no automatic release/compensation. Stock shortages may require restocking or manual intervention.

Ensure a partially allocated order can observe provider cancellation and release its saved allocations. Do not repeatedly fail shortage recovery before ever loading the provider's cancellation state. Read the complete offer history before deciding whether such an order should resume reservation or enter voiding. Missing offers retain existing no-inferred-cancellation behavior. This is a necessary recovery adjustment when partial allocation becomes an expected state, not a redesign of background admission.

Cancel/fulfill only exact allocations persisted on the Order. For normal reservation recovery, repeating the stable operation ID recovers a lost response. Cancellation must not call `reserveUnit` to discover whether an attempt committed, because that could allocate new stock for a cancelled order.

Instead, before releasing a partially allocated order, read every `findUnits` page for each SKU on its stored lines and collect reserved units with this order's `reservation_id`. Merge any committed-but-unsaved selection into Orders' durable allocation progress, and save the reconciled exact set before releasing it. This uses the owner field on the existing unit contract and needs no new receipt lookup endpoint. Subsequent cancellation retries use that saved set, including units already released. Keep this reconciliation narrow to incomplete allocation recovery, not a new general reservation polling framework.

### Existing orders and in-flight rollout

Existing awaiting-payment/to-pick orders already have exact allocations and units carrying `reservation_id`, but no new reservation lifecycle records. Inventory can adopt that existing reserved ownership on the first release/sale: require the current unit to be reserved for the supplied owner and atomically create the terminal reservation evidence with its transition. This preserves compatibility without an automatic bulk backfill.

Changing from preplanned allocations to selected allocations while an old `reserving` job is partially complete needs an explicit handoff. For this application's rollout, finish/drain old import-confirmation and order `reserving`/`voiding`/`fulfilling` executions before activating the new creation/allocation callers. This is a deployment precondition to check at implementation, not an action performed by this plan. Merely giving old in-flight rows new operation IDs is unsafe.

If draining is not possible, implement a narrow adoption of already-saved legacy progress before cutover; do not silently allocate replacement stock. Do not add an exact-unit reservation method to the permanent client solely as a rollout shortcut. Existing ordinary reserved orders need not be paid/cancelled before rollout; the first-transition compatibility above covers them.

## Price and publication consumer behavior

Repricing uses `findSkus` user-wide pages with its existing candidate/checkpoint counting, Games metadata where actually needed, and `findUnits` to check eligibility. It calls Pricing only for eligible in-stock candidates and then `updateSku` with the returned amount. Preserve audit effects and replay/checkpoints while intentionally removing the old caller-version condition. There are no market/floor caches or new provider calls in Inventory.

The existing Publish worker reads dirty candidates through Inventory `findSkus(dirty=true)` and calls `ListingService#updateListing` once per SKU. Listings strongly reads the SKU, captures its version, gathers every unit page before acting, selects the first in-stock unit's ordered photo IDs, gets bytes through Photos, and passes bytes to FetchTCG. Listings commits its own association state, then clears dirty through a versioned Inventory patch. The publish status handler uses the same dirty query and follows all pages to calculate its remaining dirty count; job-record reads stay in Jobs/application wiring. Preserve warnings, photo ordering, zero-stock delisting, provider retry behavior, and batching/count checkpoints. There is no stored FetchTCG photo-URL cache.

Keep the dirty queue's draining semantics: fill a bounded publication batch from its pages, and start the next batch at the current dirty queue's beginning. Do not persist a GSI position that permanently skips a version-conflicted SKU or one that became dirty earlier in key order. Counts/checkpoint tokens are workflow progress, not evidence that an acknowledgement applied.

At each job boundary, retain retryable service unavailability for SQS, safe terminal messages for other expected service errors, and unexpected runtime/storage failure propagation. Do not swallow a failed service call and continue with partial provider pages or incomplete allocations.

## Audit and Reports transition

Inventory generates audits for first stock creation, first removal, actual condition moves, applied price patches, first reservation, first release, and first sale. Audits and effects commit in the same local transaction. Replayed creation/allocation/release/sale and unchanged-condition/already-removed no-ops add no audits.

Orders' `order_reserved`, `payment`, `order_voided`, and `order_fulfilled` lifecycle writes are removed rather than routed through an Inventory audit API. Historical events are retained. No other vertical can call `buildAuditPut` or write a new Inventory audit.

Reports currently reads the audit table and captures an audit watermark. The agreed target is that Reports does not use audits. Replacing that model belongs to its separate extraction, not this series. Until then, retain the existing reader as an explicit legacy ownership gap and document its weaker freshness signal when payment-only/order-only events cease producing audits. Do not add a new Inventory audit-reading endpoint or fake stock event just to keep Reports freshness working. Report generation and stock/revenue calculations remain intact.

## Task details and commit acceptance

### 1. Migrate unit removal

**Ships:** `DeleteUnitRequest/Response`, `DeleteUnitService`, a server-private mutation repository and audit mapper, and the module delegate. Extend server SKU/unit mappings only as needed. Move the existing removal handler to `inventory.api.DeleteUnitHandler` and make it delegate once. Keep its route, artifact key, and `204` success response; an empty service response does not require an HTTP body.

Update Bazel handler ownership/deployment jar, Terraform class, manifest mapping, and LocalStack class registration together, retaining deployment identifiers. The handler has the public default constructor and testing factory constructor; checked errors are caught in `handleRequest`, with `doHandleRequest throws ServiceException`. Authentication/CORS and existing query/path inputs remain.

**Done when:** focused real-DynamoDB server tests cover first removal, removed replay without extra audit, state restrictions, absence, and isolation. Focused adapter coverage checks delegation and status mapping without repeating service behavior. Existing Inventory mutation integration coverage and the affected handler/jar build pass. No tests merely asserting a removed global job guard's absence.

### 2. Migrate condition changes

**Ships:** the operation/request/response, shared values-only `Condition` enum used by both `UpdateUnitRequest` and `Sku`, server-local identity derivation and initialize-only SKU logic, and `inventory.api.UpdateUnitHandler`. Preserve the existing PUT route and `{ "sku_id": ... }` response. Update deployment class references and direct dependencies. Remove the legacy condition mutation after its final handler caller moves.

**Done when:** real DynamoDB covers the successful move, source/destination dirty/version changes, exact sequence/photos preservation, destination metadata/price preservation, unchanged-condition no-op, and invalid stock state. Preserve frontend navigation behavior. Keep `Locations` as business logic; inline simple record mapping rather than creating a mapping layer.

### 3. Expose worker SKU facts and version

**Ships:** the expanded common records, server model projections, user-wide and dirty/clean `findSkus` modes, and fake read support. Map legacy reservation ownership to `Unit.reservationId`. This is operational read behavior, not a contract-only scaffold. Preserve frontend browse's required-game adapter behavior.

**Done when:** the existing Inventory read integration file covers numeric/null worker fields, version, owner projection, user-wide and dirty pagination, and user isolation. Update existing Java constructors and JSON assertions. Build API and client/server targets; touch web fixtures only if required by their explicit response contracts. The public Inventory list and detail screens display the single `price` field, mapped from the suggested price.

### 4. Add SKU patches and migrate repricing

**Ships:** `UpdateSkuService`, targeted price/audit persistence, and direct repricing service calls. Replace repricing's typed SKU table and legacy mutation repository with Inventory reads/patches. Keep construction inline in `JobsHandler`. Remove the retired price helper after its final caller migrates.

**Done when:** service coverage verifies saved/zero prices, normalization, empty patch, no caller-version requirement, existence, and unrelated-attribute preservation. Repricing coverage retains eligibility, Games behavior, checked counts, checkpoints, earlier-item partial effects, and repeated writes. Explicitly update tests expecting stale-caller-version rejection to the accepted last-write-wins behavior.

### 5. Complete the Listings prerequisite

This former implementation task is absorbed by [Listings tasks 1–7](listings-migration-plan.md). Complete that plan before resuming Inventory task 6. Do not add `updateSkuPublication`, duplicate Listings persistence, or retain marketplace listing fields on Inventory to satisfy the old task.

**Done when:** Listings owns current/historical associations and single-SKU synchronization; the Publish worker and pending count use clients; Orders resolves listing IDs through Listings; Reports uses Inventory SKU prices; and existing associations have a controlled migration tool. Inventory exposes only the extended `updateSku` dirty/version patch. Mark this prerequisite complete when those changes pass their scoped acceptance; it requires no separate verification-only commit.

### 6. Implement unit creation

**Ships:** `CreateUnitService`, additive creation receipts, single sequence allocation, bounded atomic creation, and fake happy-path creation. Reuse the initialization/identity code from task 2 after the Listings cleanup; initialize/preserve only Inventory metadata and never recreate listing/publication fields. No consumer switch or new endpoint yet.

**Done when:** real DynamoDB verifies first creation, lazy SKU creation, initialize-only existing SKU behavior, sequence/location, ordered IDs, user scoping, and replay without another unit/audit. Include a meaningful replay after the unit has moved/been removed. Verify a reused ID returns the first result without payload comparison. Do not create a broad duplicate-delivery stress matrix.

### 7. Prepare gap-aware import placement

**Ships:** placement grouping splits at discontinuities, using actual rows rather than implied contiguous ranges. Existing contiguous imports render identically. Frontend/fake instruction grouping supports repeated block labels and stable list keys.

**Done when:** one same-block gap example and one normal block-boundary example produce accurate instructions/counts; existing placement display remains. Run scoped web tests/typecheck only if the frontend changes. This task is useful before new sequence allocation is activated.

### 8. Migrate import confirmation

**Ships:** row-level persisted creation results, sequential `createUnit` calls, stable row operation IDs, and explicit processor dependencies. Remove range allocation/inference, `SkuItem`/`UnitItem` construction, and `confirmImportUnit` use from Imports. Add a one-time migration that fills historical confirmed rows from matching Inventory units and saved sequences, then remove the read-time location fallback.

**Done when:** confirmation integration covers normal ordering, a repeated delivery recovering a committed creation before row progress, and the saved noncontiguous placement results. Preserve keep-row/photo/lifecycle gates, empty confirmation, and final parent completion. Document/check the in-flight rollout precondition. Delete obsolete range and import-creation helpers after their callers disappear.

### 9. Implement Inventory-selected reservation

**Ships:** `ReserveUnitService`, immutable allocation receipts, reservation lifecycle records, ascending available-unit selection, module delegate, and fake happy-path allocation. It is available but Orders continues its legacy path until task 15.

**Done when:** real DynamoDB verifies earliest available selection, non-stock page traversal, insufficient stock, owner/isolation, and original-result replay without extra stock/audit effects. Verify two sequential operation IDs allocate distinct units. Keep each transaction bounded independently of total order quantity.

### 10. Implement release

**Ships:** `ReleaseUnitService` and its terminal reservation transition. Support lazy adoption of an existing legacy reserved unit owned by the supplied reservation, without a bulk backfill.

**Done when:** cover first release, wrong owner, legacy reserved ownership, and repeat success without another audit. Include one regression where A is released, the unit is reserved for B, and retrying A's release does not change B. Use real repository tests rather than a fake repository.

### 11. Implement sale

**Ships:** `SellUnitService`, terminal sale evidence, legacy reserved-owner compatibility, and happy-path fake behavior.

**Done when:** cover first sale, repeat success, wrong owner, and incompatible terminal state. Preserve existing reserved-to-sold SKU bookkeeping. No Orders code switches yet, keeping this commit focused on the Inventory guarantee.

### 12. Remove non-inventory audit writes

**Ships:** Orders transitions update their own lifecycle without constructing Inventory audit entries. Remove the generic audit-builder dependency and affected assertions; keep stock audits and historical records. Retain only the unit-read/reservation dependencies still needed until later tasks.

Update the README and strategy to state the agreed audit scope and Reports deferral. Do not redesign Reports, remove its endpoints, add an audit service, or generate synthetic events.

**Done when:** existing order lifecycle tests verify their actual states/progress; inventory stock audits remain transactional. Reports tests no longer assume payment-only changes emit Inventory events. Document current freshness limitations rather than adding replacement tests for a removed audit requirement.

### 13. Migrate release and reservation ownership reads

**Ships:** Orders cancellation uses `releaseUnit`. Replace `OrderRepository`'s raw unit lookups with `getUnit` and its owner/status facts where checks remain necessary. Keep actual Inventory state enforcement in the mutation; avoid extra prevalidation reads that merely duplicate it. Use client SKU reads for the portions of ownership checking touched here.

**Done when:** cancellation/voiding integration passes for existing legacy allocations, repeats, and a partly completed release. Fulfillment admission retains its meaningful local order-line validation. Legacy explicit-unit reservation remains operational, and new release can adopt its owner state.

### 14. Migrate fulfillment

**Ships:** `OrderFulfillmentJobProcessor` depends explicitly on `InventoryService` and calls `sellUnit` for each stored allocation. Update `ConfirmOrderHandler`, `OrderRepository`, `JobsHandler`, and all constructor sites. Preserve order/job start transactions only within the still-legacy execution framework; do not expand them to include Inventory records.

**Done when:** existing fulfillment integration covers normal and greater-than-100-unit completion, retry after partial sale, and user ownership. Orders is marked fulfilled only after every Inventory call succeeds. Remove the old sale helper after its final caller moves.

### 15. Activate Inventory allocation in Orders

**Ships:** order-owned stable line/ordinal progress, calls to `reserveUnit`, recording returned sequences, and resumable partial-order handling. Keep listing-to-SKU resolution through `ListingService#findListings`, already migrated by the Listings prerequisite. Do not reconstruct the association from Inventory SKU records. Remove preassigned unit selection and complete-history availability preflight while retaining structural validation and complete offers pagination.

Cancellation must recover an allocation whose Inventory result committed before Order progress, and partially reserving orders must be able to observe cancellation. Reuse the release/sale client paths already active from tasks 13–14. Stop listing publication when order processing fails.

**Done when:** focused existing order integration covers first selection, repeated delivery, a later shortage leaving recoverable progress, cancellation of partial work, and a large same-SKU order without one large transaction. Verify line identity is stable across retries and actual selected sequences are saved. Remove the last explicit-unit reservation/availability-planning helpers. Check the transitional rollout precondition before activation.

### 16. Remove retired mutation implementation access

**Ships:** delete legacy `InventoryRepository` once all production callers are gone, including generic transaction builders. Remove dead mutation models/helpers/dependencies/bindings and exclusive tests. Inventory operation tests replace old implementation tests; consumer integration fixtures use service contracts or raw seed attributes rather than server types.

Keep any root `SkuItem`, `UnitItem`, `InventoryLocation`, and shared audit table bindings still used by explicitly deferred reads. Listings has already removed marketplace listing/snapshot fields, and Reports already uses Inventory SKU reads; do not restore those fields or dependencies. Do not claim the entire vertical is isolated while those readers exist, and do not broaden visibility to hide their dependencies.

Update the strategy's import-confirmation, allocation, last-write-wins, publication/version, and audit ownership wording. Bring current READMEs up to date with the final implementation. Do not link a tracked file to this ignored draft.

**Done when:** production code outside Inventory constructs no inventory write models, mutates no SKU/unit/counter/receipt/audit records, and imports no server implementation except root wiring. Review root/test direct dependencies. Record the remaining read gaps below, rather than expanding this commit into a Reports or Orders extraction.

## Verification agreements

Tests and documentation belong to each task; no verification-only tasks or wiring integration suite. Favor focused existing real DynamoDB/S3 integration coverage, plus narrow adapter tests. Keep ordinary fake behavior in `FakeInventoryService`; use mocks for intentional failures or interactions. Do not add tests solely proving removed guards/requirements are absent.

Run container suites sequentially to limit Docker disk usage. Scope tests/builds to affected API/web packages. Typical commands, narrowed further per task:

```text
bazel test //tcg_inventory_api/src/test/java/com/jordansimsmith/tcginventory/inventory/server:server-integration-tests
bazel test //tcg_inventory_api:unit-tests
bazel test //tcg_inventory_api:integration-tests --test_filter='<affected class>'
bazel build //tcg_inventory_api/src/main/java/com/jordansimsmith/tcginventory/inventory/client
bazel build //tcg_inventory_api/src/main/java/com/jordansimsmith/tcginventory/inventory/server
bazel build //tcg_inventory_api:lib //tcg_inventory_api:inventory-lib
```

Build the specific import/pricing/publish/order libraries, jobs handler, affected API handler and deployment-jar targets when their code changes. Once a legacy target is removed, stop listing it as a check. Update changed handler deployment class references in Bazel, Terraform, manifest, and LocalStack together.

Existing affected integration classes include `InventoryHandlerIntegrationTest`, `ConfirmImportHandlerIntegrationTest`, `ImportsHandlerIntegrationTest`, `JobsHandlerIntegrationTest`, `RepriceJobProcessorIntegrationTest`, `ListingPhaseProcessorIntegrationTest`, `OrderReservationIntegrationTest`, `OrderVoidingIntegrationTest`, and `OrdersHandlerIntegrationTest`. Select what the current commit changes; do not run every suite for every task.

For task 7 frontend changes, use only scoped web unit tests and typecheck/build. No E2E execution or new E2E scenarios are required. Update a stale existing E2E contract assertion only if necessary to keep its source aligned.

After reviewing each implementation commit, run relevant scoped checks, then `bazel mod tidy`, `bazel run //:format`, and `git diff --check`. Never run repository-wide builds/tests. Do not make a commit or push merely because a task is marked complete; follow the user's explicit commit/push instructions for the implementation session.

## Accepted tradeoffs

- Creation/allocation are one unit per call. Large imports/orders use more local transactions and future RPC calls. Bulk APIs can be added later without changing ownership.
- Sequence gaps and interleaving are accepted. Placement instructions must describe actual results, including multiple groups in the same block.
- Deduplication retains results indefinitely initially and trusts caller IDs. A reused ID with different input still returns the original result.
- Stock and order/import completion can be partially visible. Receipts plus owner progress provide forward recovery, without cross-vertical transactions or automatic rollback.
- Later allocation shortages can leave partial reservations. The former all-offers stock preflight guarantee is intentionally removed.
- Ordinary SKU prices are last-write-wins. A repricing run can overwrite an intervening price, fail mid-run, or need rerunning.
- Publication may temporarily show stale price/quantity/photos. Versioned acknowledgement preserves a pending republish; it does not provide a distributed transaction with FetchTCG.
- Configuration changes remain allowed during jobs. Reporting can combine observations from different times. Physical stock discrepancies may still require manual intervention.
- A moved unit retried at its old SKU can be not found. No condition-move receipt protocol is introduced.
- Legacy live work must be handed off deliberately before changing its allocation/creation protocol. No automatic blanket data migration or backfill is required for unchanged ordinary stock.

## Remaining work after this series

- Reports uses Inventory client SKU/unit reads and current prices after the Listings prerequisite, but still has legacy order/audit reads. Its extraction must remove audit-based freshness and choose its own eventual-consistency behavior. Do not make new report code depend on Inventory audits.
- `GetOrderHandler` still has specialized physical-block/neighbor reads and some legacy SKU/unit projections. A later Inventory read operation should own authoritative block-position/neighbor facts; do not expose GSI3 or persistence models through the client to finish this mutation work.
- Historical import placement results use the one-time backfill from matching Inventory units and saved sequences. Runtime placement display requires the persisted SKU and location result.
- Other verticals' package extraction and Jobs/admission/framework cleanup remain independent. Existing root-package server visibility remains an acknowledged enforcement gap.

Completion here means every Inventory mutation is invoked through the client and enforced by Inventory-owned persistence. It does not mean that all legacy read access, root shared models, or framework coupling has disappeared.
