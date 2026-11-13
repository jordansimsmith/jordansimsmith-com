# Modular monolith worker strategy

This document extends the [modular monolith engineering strategy](modular-monolith-strategy.md) with the ownership, execution, and recovery rules for background work. Each vertical may have synchronous service operations and asynchronous processing. Both belong to the same domain owner.

The [service README](../README.md) describes the runtime currently implemented. Imports, Orders, Pricing, Reports, and Publish own their workflow records or lifecycle, messages, and queue adapters. Scan confirmation HTTP admission still publishes through shared Jobs. The Scans Java worker consumes its own confirmation FIFO. Historical Imports and Orders Jobs records remain stored, but their runtime work uses owner-local queues and workers. Do not introduce a generic Jobs vertical.

## Ownership and public contracts

Each vertical owns its workflow logic, internal message schemas, lifecycle, progress, dispatch, and any admission state. Records remain exclusively owned even while verticals share a physical DynamoDB table. Another vertical must not read or write them directly.

A separate job record is optional. An import's lifecycle and per-row creation results, for example, may already provide the durable state needed to execute and resume confirmation. Choose the smallest owner-local model that supports the workflow; there is no shared job entity or mandatory lifecycle framework.

Cross-vertical callers use business service operations. A service may accept work, record its identity, and send an internal queue message before returning. Callers do not construct another vertical's messages or publish directly to its queue. Internal worker entry points and message types stay out of `client`.

```text
Caller -> ImportsService -> Imports server operation -> Imports queue

Imports queue -> Imports worker adapter -> Imports server message handler
                                            -> InventoryService
```

Contracts must distinguish acceptance from completion. A successful request that starts background work does not imply its downstream effects have finished. Public status and completion semantics belong to the owning service, using the normal request/response objects and checked service exceptions.

## Packages and wiring

Use `worker` for queue-consumption adapters, alongside the existing packages:

```text
publish/
  api/       # HTTP adapters calling PublishService
  worker/    # queue adapters calling Publish's server entry points
  client/    # public business service contracts
  server/    # service operations, message handlers, messages, and persistence
```

Every package that exists has its own `BUILD.bazel`. `api` remains the HTTP adapter package. A worker Lambda handler handles SQS input and invocation success/failure; the server message handler owns business dispatch, processing, progress, and completion. Keep AWS Lambda events out of processing logic so the same message handler can run in another queue runtime.

Name the Lambda adapter `FooWorkerHandler`, such as `PublishWorkerHandler` in `worker`. Name the server processing class `FooMessageHandler`, such as `PublishMessageHandler`, and keep its `FooMessage` type in `server`. Use the internal entry-point signature `void handleMessage(RequestContext ctx, FooMessage message)`, declaring checked exceptions when required. For example:

```java
public void handleMessage(RequestContext ctx, PublishMessage message)
    throws ServiceException;
```

The Lambda adapter deserializes the message, constructs `RequestContext` from the queued operation's user scope, and calls `handleMessage`. The message handler owns progress and continuation scheduling. Returning normally means that message's work was handled; the overall operation may still have continuation work remaining. It returns no service response or batch-result object. This internal `void` signature is separate from the request/response requirements for public service operations.

A concrete message handler is sufficient; no worker interface or service wrapper is required merely to cross these packages. Any useful internal worker interface also belongs in `server`. Checked service errors propagate to the Lambda adapter for the default queue-failure handling.

Allow the owning `worker` package, application composition root, and implementation tests to depend on the server target. Other verticals, including their workers, depend only on the public client. HTTP adapters continue to use client interfaces. Preserve strict Java dependencies and avoid re-exporting server types.

The owning server module constructs its message handlers and exposes the entry points needed by its own worker adapter through application wiring. Repositories and persistence models remain internal. Sharing infrastructure code in `//lib/queue` or other focused libraries is appropriate; shared libraries must not own business lifecycle, admission, progress, or records.

## Queues and deployment

Start with a queue and handler per vertical, with its own DLQ and execution settings. A handler can dispatch multiple internal operation types owned by that vertical. Add queue/handler pairs when workloads need distinct runtimes or settings, such as Python scan recognition alongside Java processing. Do not introduce a queue per operation without a concrete need.

Several specialized handlers must not compete on one shared queue expecting SQS to route messages by business type. Each queue has its intended consumer; message distribution is not business routing.

Queue ownership does not determine JVM ownership. One worker JVM may host consumers for several vertical queues, and API and worker processes may be separate. With Lambda, separate functions run in separate JVMs. Each artifact includes the implementations reachable through its direct service bindings.

Future extraction moves the owning consumer and data configuration into its own deployment and replaces cross-vertical service bindings with RPC clients. The private queue schema remains an implementation detail; callers keep using business service contracts. Transport, authentication, and deployment work still need to be implemented for extraction.

## Concurrency and local restrictions

Unrelated background work may execute concurrently. Remove endpoint checks that reject a request merely because the user has another queued or running task. Do not replace them with a global lock, shared job reader, or cross-vertical mutation permit.

Retain lifecycle restrictions where the owning resource needs them: an import must be in review to begin confirmation, a scan must be in the appropriate state, and an order must transition through its fulfillment lifecycle. Repeated requests retain their owner-defined acceptance or completion behavior.

Use FIFO ordering at the scope that needs it:

| Work                           | Default message grouping |
| ------------------------------ | ------------------------ |
| Publication                    | User                     |
| Import or scan processing      | Import or scan           |
| Order fulfillment              | Order                    |
| Report generation or repricing | Operation run            |

Choose concrete grouping keys during each migration. A vertical can own multiple operation types with different grouping needs. Distinct imports, orders, or report runs need not serialize merely because they belong to the same user.

FIFO orders messages within one queue and group, not across queues. A continuation joins behind messages already waiting; ordering alone does not give one logical run exclusive ownership across all its batches. Delivery can repeat, so FIFO does not replace replay-safe effects and progress.

Inventory's transactions protect unit ownership and stock transitions. Concurrent workflows may encounter conditional failures or shortages, and earlier effects may remain committed. Sequence gaps, interleaved import placements, last-write-wins repricing, mixed-time reports, and marketplace projections that need another synchronization are accepted tradeoffs. Synchronous edits and configuration updates remain available subject to their local lifecycle rules.

## Publish-owned admission and ordering

Publish atomically admits at most one active publication per user. Use a conditional owner-local write; a separate read-then-create check is insufficient. If a publication is already active, return `202` without scheduling another run.

The admitted run includes queued work, processing, continuation batches, and retries. Keep its identity and admission until completion or an explicit terminal outcome. Another vertical's work does not acquire this admission or block it through shared job state.

Within that run, Publish waits for Orders synchronization to complete successfully through an Orders client operation before calling Listings. Orders owns offer ingestion, reservation progress, and order transitions; Listings owns single-SKU marketplace synchronization and associations. Publish owns the orchestration and publication progress. Do not add an independently executing Orders synchronization path without revisiting these ordering guarantees.

This serialization addresses two concrete risks. Overlapping publications can send an older quantity after a newer run has cleared the SKU's dirty flag; the old acknowledgement fails, but a later ordinary publish may skip the already-clean SKU. Concurrent upsert/deletion can also interfere with Listings' current pointer. Overlapping order-ingestion runs can overwrite initialized rows and saved reservation progress under the current creation protocol. Preserve the serialized path until any replacement protocol explicitly supports overlap.

Orders-before-Listings reduces the chance of re-advertising stock committed to offers already visible to the synchronization run. It does not eliminate offers arriving afterward or concurrent Inventory changes. Inventory's versioned dirty acknowledgement remains necessary.

Admission prevents separate runs from overlapping. Keep Publish's FIFO queue grouped by user and preserve replay-safe handling for duplicate deliveries of the same run. An active database flag alone is not an execution mutex.

## Failures, retries, and DLQ recovery

By default, every processing failure fails the invocation. Let SQS retry according to the queue's configured receive policy, then move exhausted messages to that queue's DLQ. This includes business-looking failures and all checked `ServiceException` categories, as well as unexpected runtime and storage failures.

An HTTP-named exception has no automatic terminal meaning in a worker. Do not introduce a generic classifier that acknowledges `BadRequestException`, `NotFoundException`, or `ConflictException` while retrying only `ServiceUnavailableException`. A Lambda adapter may wrap a checked exception when required by its runtime signature, preserving the failure rather than treating the message as successfully processed.

Handle legitimate business-failure outcomes explicitly when a concrete workflow requires them. Such a change must define the owner's saved state, partial effects, and recovery behavior. Do not design speculative failure handling for every workflow before migration.

DLQs are passive by default. Exhausting delivery attempts does not automatically mark the operation failed, release Publish admission, or roll back earlier effects. An operator investigates and repairs the cause, then redrives or resumes the existing operation. A stuck Publish run continues blocking a new publication until recovery or an explicit terminal decision. Do not automatically expire admission while an older execution could still resume and issue provider writes.

## Progress, replay, and dispatch gaps

Owners persist enough progress to resume work after partial completion. Use stable operation identities across queue deliveries and dependency calls. If a dependency commits before the caller saves progress, replay the same operation to recover its original result. Do not generate a fresh creation or reservation identity on each delivery.

Keep continuation encoding and progress semantics in the owning server. Complete and persist the batch's required effects and progress before sending its continuation. Earlier effects remain after a failure; there is no cross-vertical rollback. Successful duplicate deliveries of completed work should avoid repeating durable effects through the owning workflow's replay protocol.

SQS deduplication does not provide indefinite or exactly-once execution. Internal message formats and deduplication IDs must support the owner's batching and retry behavior without exposing those details through the public client.

Saving local state and sending SQS are separate operations. Manual recovery is accepted initially for the gap between them, for both initial dispatch and continuations. A failed send may leave durable accepted work without a queued message. Retain its identity and progress so an operator can enqueue or resume the same work; a new operation is not the recovery mechanism.

The same limitation applies when one service accepts a follow-up operation before its caller records that result. Specify stable identity and recovery at that handoff. An owner-local transactional outbox or equivalent delivery mechanism can be added if these gaps become an operational problem. No shared dispatch table or outbox framework is required now.

## Cross-vertical workflows

Worker implementations obey the same client-only dependencies as synchronous server operations. Publish calls Orders and Listings services. The Scans Java worker validates all selected printings through `CatalogService` on each attempt before calling `ImportService#createImport`. Both the Scans worker and the legacy Jobs processor use the scan ID as the Imports operation ID and save the returned `import_id` on the scan. Imports owns appraisal and confirmation dispatch on the Imports FIFO. The HTTP scan confirmation route currently publishes to the legacy processor.

Pass the owning operation's user scope through `RequestContext`. Transactions stay within the owning vertical, and a successful service call commits that owner's work independently. The orchestrator records its own progress without sharing transaction handles or database models.

Ordering that depends on another service's completion must use that service's documented completion semantics. Merely accepting an asynchronous request is not enough to proceed with a dependent step. Exact public operation names and response shapes are defined during the relevant migration, rather than exposing internal `processBatch` methods as service contracts.

## Provider concurrency and accepted limits

Allow FetchTCG calls from different workers to execute concurrently. Process-local pacing, token caches, and synchronization do not coordinate separate JVMs, and a singleton Dagger binding is local to one component. Do not depend on a shared FIFO queue to provide provider-wide rate limiting.

Throttling, extra token exchanges, and the existing refresh/shared-secret overwrite gap are accepted initially. Shared rate limiting or additional credential-refresh coordination can be added within FetchTCG when observed behavior justifies it. Worker failures follow the same retry-to-DLQ default; another vertical must not inspect FetchTCG storage to coordinate requests.

The strategy does not promise globally consistent snapshots, atomic cross-vertical workflows, automatic DLQ recovery, or guaranteed dispatch after a database commit. These limitations are explicit choices and do not justify bypassing data ownership.

## Incremental migration and verification

For each workflow:

1. Identify the owner, authoritative lifecycle/progress, current callers, and recovery identity. Define only the business service contracts callers need.
2. Move processing and internal messages into `server`, and its thin queue adapter into `worker`. Establish the owning queue/DLQ and appropriate FIFO group while keeping runtime wiring explicit.
3. Route cross-vertical requests through clients. Replace shared job writes and global active-job checks with owner-local state and required lifecycle restrictions. Add owner-local admission before removing any guard that protects the workflow being migrated.
4. Preserve forward recovery, stable dependency-operation IDs, and checkpoint behavior. Apply the default failure-to-SQS policy; document any concrete business-failure handling that needs different semantics.
5. Remove the migrated path's shared dispatcher, job-model, and foreign implementation dependencies after their final callers move. Update the service README to describe the behavior actually implemented, including manual recovery gaps.

Use focused tests of ordinary processing, durable progress/replay, required admission, and failure propagation. Test real persistence where its conditions matter, and adapter behavior where invocation success or failure matters. Avoid exhaustive concurrent-edge-case matrices, wiring-only suites, or introducing a generic worker framework for test reuse.

Migration is complete when each owner controls its workflow records and execution, other verticals call only its service contracts, and the remaining shared code provides infrastructure rather than business ownership. This document does not itself migrate queues, handlers, records, or existing business-error behavior.
