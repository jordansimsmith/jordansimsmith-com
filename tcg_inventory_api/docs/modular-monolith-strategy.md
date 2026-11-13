# Modular monolith engineering strategy

This strategy defines how to evolve the TCG inventory API into a loosely coupled monolith with explicit service contracts, exclusive data ownership, and enforceable build boundaries. The purpose is to keep domain logic understandable as the service grows and prevent verticals from accumulating dependencies on one another's implementation and persistence details.

This is a target architecture and an incremental migration strategy, not a description of boundaries already enforced everywhere. The [service README](../README.md) remains the specification for current behavior. Update it as each migration changes the implementation or observable contracts.

The adjacent [worker strategy](modular-monolith-worker-strategy.md) defines background-work ownership, internal queue adapters, concurrency, retries, and operational recovery. It replaces the earlier proposal for a generic Jobs vertical and global background-task admission.

## Starting point

The service groups Java code into verticals: games, catalog, scans, imports, inventory, orders, reports, publish, and pricing. Bazel gives these verticals separate libraries. Catalog exposes bulk printing lookup through its server module and client contract. Its bulk read adapters are independent of the legacy Catalog package. The shared Jobs records and dispatcher now serve Scans confirmation only. The top-level composition root keeps the server-visibility exception needed for Dagger wiring; because Bazel visibility is package-scoped, every target in that package can currently declare those dependencies. Move other root-package code into vertical packages or `//lib` packages to close that enforcement gap.

Remaining ownership gaps include Scans confirmation on shared Jobs and Catalog browser routes and TCGCSV snapshot publication in the legacy package. Bulk read logic is temporarily duplicated so the Catalog server can read the existing provider and storage contracts independently. The root composition visibility exception also permits non-composition targets in that package to depend on server implementations until those targets move. Imports, Orders, Reports, Pricing, and Publish use their vertical-owned workers. Orders detail and Reports read Inventory and Orders facts through service clients.

Existing import confirmation and order processing already use durable progress and replay to complete work in smaller transactions. Preserve those guarantees while changing ownership; service interfaces alone do not provide them.

## Goals and scope

- Keep vertical slices as the organizing structure, revisiting a boundary when its invariants require a different owner.
- Give each vertical a public client package, a private server package, and separate HTTP (`api`) and queue (`worker`) adapter packages where needed.
- Make cross-vertical access go through runtime-independent service contracts.
- Give each persisted record and business invariant one authoritative owner.
- Keep background-work lifecycle, progress, internal messages, and admission in the vertical that owns the workflow.
- Keep direct in-process calls initially and preserve one Lambda per HTTP endpoint.
- Make future RPC implementations possible without changing callers' business logic.
- Replace cross-vertical atomicity with explicit, recoverable workflows.

Immediate service extraction, an RPC framework, an event bus for every interaction, and a database split are outside the initial scope. Deployments may continue sharing a DynamoDB table and bundling multiple vertical implementations.

## Package and build boundaries

Each vertical has `client` and `server` packages, with `api` for HTTP adapters and `worker` for queue-consumption adapters where needed. The `api` package translates HTTP input into client requests, calls the client interface, and translates service errors into HTTP responses. It does not depend directly on the vertical's server implementation. The `worker` package contains thin queue adapters that may call their own vertical's server entry points directly. Background business logic, dispatch decisions, progress, and recovery remain in `server`.

The `client` package contains the service interface and the request, response, and vertical-specific error types needed by callers. Services expose business operations that can initiate background work; internal worker messages and processing entry points stay in `server`. Name queue Lambda adapters `FooWorkerHandler` in `worker` and their processing classes `FooMessageHandler` in `server`, such as `PublishWorkerHandler` delegating to `PublishMessageHandler`. The internal entry point is `void handleMessage(RequestContext ctx, FooMessage message)`, declaring checked exceptions such as `ServiceException` when needed. It owns message processing and progress without returning a service response or batch-result object. A concrete message handler is sufficient; introducing a public worker interface is unnecessary. The `server` package also contains repositories, persistence models, internal policies, operation services, and the vertical's Dagger module. Shared types such as `ServiceException` and `RequestContext` belong in `//lib/service`.

The JDK-only `//lib/service:service` target contains `RequestContext`, `ServiceException`, and its shared subclasses. `ServiceException` is sealed to the six final HTTP-named exception categories, keeping the set of service errors closed for exhaustive HTTP mapping. Future service clients depend directly on this target, which has no Lambda, API Gateway, Dagger, or JSON dependencies. The existing `//lib/http:lib` target depends on and re-exports `//lib/service:service` for HTTP adapters and existing consumers.

Each `api`, `worker`, `client`, and `server` directory that exists has its own `BUILD.bazel`, making them separate Bazel packages. Adapter BUILD files define their runtime targets separately from server implementation targets; the client BUILD defines the public contract target and any test helpers; the server BUILD defines the private implementation target and its tests. A single BUILD at the vertical root does not replace these package build files. Configure visibility to enforce the service boundary.

An illustrative package structure is:

```text
tcginventory/
  inventory/
    api/
      BUILD.bazel            # HTTP handlers
      ...                    # delegates to InventoryService
    client/
      BUILD.bazel            # public client target and optional test helpers
      InventoryService.java
      ...                    # request and response types
    server/
      BUILD.bazel            # private server target and implementation tests
      InventoryModule.java   # provides InventoryService
      ...                    # one operation service per interface method
  imports/
    api/
      BUILD.bazel
      ...                    # HTTP handlers, delegates to ImportService
    worker/
      BUILD.bazel            # queue adapters, may depend on imports.server
      ...                    # Lambda entry points, no business logic
    client/
      BUILD.bazel
      ImportService.java
      ...                    # request and response types
    server/
      BUILD.bazel
      ImportModule.java      # provides ImportService, consumes InventoryService
      ...                    # operations, message handlers, messages, persistence
```

This illustrates responsibilities; exact target names are chosen during implementation. Once a vertical's migration is complete, its production classes live under `api`, `worker`, `client`, or `server`; no production classes remain directly in the vertical root. Legacy root-level classes may remain temporarily during migration, but they are an explicit unfinished boundary and must be moved or removed before declaring the vertical complete. There is no separate contracts package: vertical contracts live in their client packages, shared service/context types live in `//lib/service`, and HTTP response mapping lives in `//lib/http`. RPC transport packages are deferred until RPC is introduced. Dagger modules live in their owning server packages; no separate wiring package is required. Bazel target names remain kebab-case.

| Consumer                   | Allowed dependencies                                                            |
| -------------------------- | ------------------------------------------------------------------------------- |
| Any target                 | Public client targets and shared service/context types from `//lib/service`     |
| A vertical's server        | Its own client, other verticals' clients, and required infrastructure libraries |
| HTTP adapter in `api`      | Service clients, HTTP/runtime libraries, and narrow composition-root factories  |
| Queue adapter in `worker`  | Its own server entry points, queue/runtime libraries, and narrow wiring         |
| Top-level composition root | Server modules needed to construct services and internal worker entry points    |
| Server tests               | The implementation under test and required test setup                           |

No other production target may depend on a vertical's server. The owning `worker` package has an explicit runtime-entry exception; other verticals' workers still use only client contracts. Enforce this with Bazel target visibility and strict Java dependencies (`strict_java_deps`): visibility restricts which packages can declare a server dependency, and strict dependencies require imported classes to come from declared direct dependencies. These checks run during normal Bazel builds and tests; no separate architecture-checking framework is required. Do not bypass them through `exports`, a shared implementation library, broad source globs, or factories that expose repositories. The top-level composition root is the deliberate construction exception and must contain no business logic.

Bazel visibility operates at package granularity, not individual target granularity. Keep the composition root limited to construction responsibilities. It may remain in the top-level Java/Bazel package while other code moves into vertical and `//lib` packages. Until that migration is complete, allowing the root package to depend on a vertical server also permits every target in that package to declare the dependency; document this as an enforcement gap rather than claiming visibility isolates one factory target. Close the gap by moving non-wiring root code out, without requiring a separate composition package.

The dependency graph between verticals should remain acyclic and intentional. A client package being public permits access; it does not make every dependency desirable. Put orchestration in the vertical that owns the workflow. If calls repeatedly go both ways, reconsider ownership or introduce a deliberate workflow boundary.

Shared code should provide infrastructure in focused libraries. Common service/context types belong in `//lib/service`; HTTP response mapping belongs in `//lib/http`. Vertical-specific contracts remain in the owning client's package. Shared libraries must not become another home for domain logic that avoids vertical ownership.

## Service contracts

Services expose business operations rather than generic database access. For example, imports asks inventory to receive confirmed stock; it does not construct `SkuItem`, `UnitItem`, or DynamoDB transaction fragments.

An illustrative service declaration is `CreateImportResponse createImport(RequestContext ctx, CreateImportRequest request) throws ServiceException` on `ImportService`.

Contract requirements:

- Every service operation accepts a dedicated, operation-specific request object and returns a dedicated, operation-specific response object, both defined in the owning vertical's client package. Follow the signature `OperationResponse operation(RequestContext ctx, OperationRequest request) throws ServiceException` even when either object has zero fields or only one field. For example, define `GetAccountRequest()` for a read with no input fields and `DeleteListingResponse()` for a mutation with no output fields. `RequestContext` is a separate parameter and does not replace the request object. Do not use bare scalar/collection parameters or return values, `void`, `Void`, or null in place of these objects.
- Every service interface method explicitly declares `throws ServiceException`. Use the same declaration on its operation-class method and named or anonymous delegate implementation. Define the checked, sealed base `ServiceException extends Exception` and the six permitted final HTTP-named subclasses in `//lib/service` before introducing client interfaces that depend on them. Callers must handle/translate the checked exception or propagate it through their own declared contract. Service implementations and delegates preserve the typed exception until a caller or runtime adapter handles it.
- A `get` operation returns the requested resource in its response or raises `NotFoundException` when that resource does not exist; HTTP adapters map this to `404`. Do not represent a missing resource with a successful empty response, null/optional result, or an existence/configuration flag. A `find` operation returns a response containing a collection, which can be empty when no results match.
- Use serializable values with explicit meanings, validation, and absence semantics.
- Keep persistence annotations, database keys, SDK types, Lambda events/responses, lazy values, and transaction handles out of client packages.
- Do not rely on shared object identity, mutation of a caller's objects, thread-local state, or a shared transaction.
- Make user identity and authorization context explicit. Each service enforces access to its own resources, including calls from other services or background jobs.
- Keep `RequestContext` independent of API Gateway and Lambda. Carry the authenticated actor/user scope and correlation information needed by the operation.
- Define bounded bulk operations for workflows such as imports and reporting; do not assume one call per unit remains cheap remotely.
- Keep continuation tokens opaque and follow the repository's `continuation` and `next_continuation` naming conventions.
- Specify idempotency and concurrency behavior for mutating operations.

The direct implementation and a future RPC client implement the same interface. RPC must also define serialization, error reconstruction, authentication, deadlines, and compatibility rules. Preserving business logic is the objective; separate deployment still requires transport and infrastructure work.

## Operation implementations and Dagger wiring

Each service interface method has its own implementation class in the owning server package. For example, `FooService#findBar` delegates to `FindBarService#findBar`. That operation class owns its validation, authorization, orchestration, and persistence calls. Shared server-private repositories and helpers can be dependencies of multiple operation classes.

This one-class-per-method rule applies to service operations. A private provider HTTP adapter may group related endpoints that share transport behavior, such as base URI, headers, pacing, retries, and response parsing.

The object implementing `FooService` is a thin delegate. A named `FooServiceImpl`, if used, contains delegation only; it does not accumulate the operation bodies. Prefer an anonymous implementation inside `FooModule` when that avoids an otherwise empty implementation class.

Each vertical defines a Dagger `FooModule` in `foo.server` with one `@Provides` method returning `FooService`. Construct private repositories, adapters, and operation services inside that provider, using private construction helpers when useful. The provider accepts required infrastructure, runtime configuration, and other verticals' client interfaces as parameters. Resolve environment-specific values in the application composition root and pass them into the vertical module; server modules should not read environment variables. It does not expose operation classes, repositories, tokens, or persistence models as public Dagger bindings, and it does not include another vertical's server module.

A vertical with background work may additionally provide a server-owned message handler to its own `worker` adapter through application wiring. This internal runtime binding does not belong in `client` and does not make the message handler available to other verticals. Keep worker construction inside the owning server module; it is not a reason to expose repositories or persistence models. The one-class-per-service-method rule does not require an interface or service-operation wrapper around each internal worker entry point.

An illustrative module is:

```java
@Module
public class FooModule {
  @Provides
  @Singleton
  public FooService fooService(
      DynamoDbEnhancedClient dynamoDbClient, BarService barService) {
    var repository = new FooRepository(dynamoDbClient);
    var findBarService = new FindBarService(repository, barService);

    return new FooService() {
      @Override
      public FindBarResponse findBar(RequestContext ctx, FindBarRequest request)
          throws ServiceException {
        return findBarService.findBar(ctx, request);
      }
    };
  }
}
```

Add another delegate and operation class for each additional interface method. Keep business logic in those classes rather than the module or anonymous implementation.

The top-level Dagger component/factory includes the server modules and exposes client interfaces:

```java
@Singleton
@Component(modules = {FooModule.class, BarModule.class})
public interface AppFactory {
  FooService fooService();

  BarService barService();
}
```

Modules can alternatively be included by the existing top-level application module. Dagger supplies cross-vertical dependencies by interface type; `FooModule` does not construct `BarService` or depend on `BarModule`. The factory exposes service interfaces for cross-vertical and HTTP callers, with required shared infrastructure bindings supplied by the application. Worker wiring may expose a vertical's internal message handler only to its own queue adapter. Other factory accessors exposing implementation details are migration gaps to remove as their callers move.

Scope the service provider to the component lifecycle where appropriate. A singleton belongs to one component in one process; it does not create shared instances across Lambda environments. The same client interface can later be provided by an RPC implementation without changing callers.

## Data ownership

Each vertical exclusively owns its persisted records, schema changes, migrations, reads, writes, and invariants. Physical co-location is a deployment choice. For the current DynamoDB single-table design, non-overlapping ownership means item types and access patterns, not necessarily separate tables or partition keys.

No vertical may read another vertical's records through its repositories, raw DynamoDB access, shared indexes, or a transaction containing the other owner's items. References to foreign resources are contract IDs, not dependencies on the other service's key encoding or item layout.

An ownership change does not automatically require a data migration. Retain compatible persisted records when their ownership can simply change. When new metadata can be initialized through a normal operation, document that initialization and the behavior before it occurs rather than requiring an automatic backfill. Use an explicit migration only when preserving existing data or behavior requires one.

The starting ownership model is:

| Vertical  | Authoritative responsibility                                                                                  |
| --------- | ------------------------------------------------------------------------------------------------------------- |
| Games     | Registered game identity, supported finishes, and capabilities                                                |
| Catalog   | Normalized catalog lookup, provider adapters, and catalog snapshots                                           |
| Scans     | Scan sessions, recognition/review progress, and source-image lifecycle                                        |
| Imports   | Intake rows, appraisal/review progress, and import confirmation workflow                                      |
| Inventory | SKUs, physical units, sequence/location allocation, and stock transitions                                     |
| Orders    | Offer/order records, order-tracking settings, allocations recorded for its workflow, and fulfillment progress |
| Pricing   | Pricing policy, price-floor configuration, and repricing orchestration                                        |
| Listings  | Single-SKU marketplace synchronization and current/historical listing associations                            |
| Publish   | Publication admission, Orders-before-Listings orchestration, and publication progress                         |
| Reports   | Report generation and report snapshots/projections                                                            |
| Photos    | Physical-photo bytes, identifiers, and retrieval                                                              |
| FetchTCG  | Marketplace account, credentials, seller identity, and provider integration                                   |

Inventory owns SKUs, units, sequence and location allocation, receipts, reservation lifecycle, stock transitions, and stock and price mutation audits. It selects and reserves one unit per operation; callers save returned allocations as their own workflow progress. Imports creates units through a stable per-row operation ID, then records the returned location and SKU on its row. Creation is visible incrementally, and sequence gaps are accepted. If a downstream progress save fails, callers retry forward with the same operation ID.

Orders asks Inventory to reserve, release, or sell units and records its own workflow progress. Reservation receipts return the original unit on replay; lifecycle records make completed releases and sales replay-safe. Releasing or selling a unit updates its lifecycle, the unit, related SKU bookkeeping, and an audit in one Inventory transaction. Pricing applies last-write-wins price patches through Inventory. Listings owns marketplace synchronization and current and historical listing associations; it acknowledges publication through a versioned dirty-state patch on Inventory. These callers do not write Inventory records directly.

Inventory exposes read clients for SKU and unit facts used by other verticals. Orders composes order detail through those clients, using `findUnitLocations` for `to_pick` allocations and `getSku` for allocated-card metadata in other states; it no longer reads Inventory persistence models. Confirmation and fulfillment are also owned by Orders: it admits orders locally, sends its internal message, and processes saved allocations through Inventory's client. Reports reads order data through the Orders client and marks snapshots stale only by age, after 24 hours. The root Bazel package remains an enforcement gap because application wiring and business targets still share it.

Listing photos cross lifecycle boundaries: imports owns review-time attachments, while inventory must retain the immutable photo references needed by confirmed units. Shared S3 storage does not imply shared authority to change those references.

Before completing a vertical migration, assign ownership for its audit records, job state, idempotency records, and any shared operational gates. These cannot remain implicit shared database access. Inventory owns audits for its stock and price mutations and commits each audit with the corresponding mutation. Orders lifecycle transitions update Orders records without writing Inventory audits; historical order audit records remain untouched. A combined audit view can aggregate published records without becoming a synchronous dependency of every mutation.

## Background workflows and admission

Each vertical owns its background-operation lifecycle, messages, progress, dispatch, and any admission rules. Existing domain lifecycle and row progress may be sufficient; a separate job record is optional. There is no target Jobs vertical, shared job record, or global rule limiting a user to one background task. Shared libraries may provide queue infrastructure without owning workflow state or business policy. See the [worker strategy](modular-monolith-worker-strategy.md) for the full execution and migration rules.

Different verticals' work may execute concurrently. Keep lifecycle restrictions and ordering at the owner and resource that require them. Publish atomically admits at most one active publication per user, including queued work, continuation batches, and retries; duplicate requests return `202`. It waits for Orders synchronization to complete successfully through an Orders service operation before synchronizing Listings. No independent order-synchronization path may bypass this ordering without revisiting its concurrency guarantees. Publish owns its admission state; Orders and Listings retain exclusive ownership of their own records.

Remove global active-job checks from endpoints as their workflows migrate, while preserving local scan lifecycle restrictions and order-fulfillment transitions. Publish owns publication admission; Orders owns fulfillment admission. Synchronous edits do not acquire a cross-vertical mutation permit. Configuration changes during jobs, stale marketplace projections, mixed-time reports, and recoverable pricing conflicts remain accepted application tradeoffs. Shared Jobs dispatch remains for Scans confirmation.

## Transactions and workflow recovery

Transactions remain available within a vertical. Cross-vertical operations have no shared transaction, including when services are wired directly and share a DynamoDB client or table. A service must commit its own work without constructing a transaction over another owner's records.

Choose boundaries around invariants that require atomic enforcement. Inventory can atomically update a unit, related SKU state, and its audit record. If an invariant genuinely requires atomic changes owned by two verticals, revisit ownership before replacing that guarantee with eventual consistency.

Every workflow that changes more than one vertical must specify:

1. The owner of the workflow and its durable progress state.
2. The order of operations and the state visible during partial completion.
3. Stable idempotency keys and behavior when a key is reused with different input.
4. Recovery after a crash, duplicate delivery, concurrency conflict, or a dependency committing without returning a response.
5. Whether recovery retries forward, performs a defined compensation, or requires operator intervention.
6. When completion is reported and what reads guarantee while work remains incomplete.

For import confirmation, the intended flow is:

1. Imports durably accepts confirmation and records stable operation identity.
2. Imports calls inventory with ordered stock data and a stable identity for each retriable operation.
3. Inventory owns sequence allocation, stock creation, associated audit writes, and deduplication. Replaying an accepted operation returns its original SKU, sequence, and location without another unit or audit.
4. Imports persists progress and marks the import confirmed only after inventory confirms all required effects.

If inventory commits and imports crashes before saving progress, imports repeats the same operation. A new attempt must not generate a new idempotency key. Deduplication and its authoritative mutation must be committed atomically within inventory, or have an equivalent recoverable protocol. Define retention of deduplication records against the workflow's replay window.

Stock becomes visible as each row succeeds. Preserve row ordering, photo references, and each row's actual location; gaps and interleaving with other stock creation are allowed. Bulk service operations do not imply one unbounded DynamoDB transaction.

Sending a queue message after a database commit is not atomic. Manual recovery is accepted initially for both initial dispatch and continuation-send failures: the owner retains durable operation state so an operator can enqueue or resume the same work with its existing identity. Do not claim guaranteed scheduling or introduce a shared dispatch owner. Consider an owner-local transactional outbox or equivalent durable delivery mechanism if these gaps become an operational problem.

Compensation is a business operation with its own rules, not an automatic rollback. Receiving physical stock and reserving a unit for an order may require different recovery policies.

## Reads and reporting

Cross-vertical reads use service contracts. Start with paginated or bounded bulk reads where they are sufficient. Reports may aggregate inventory and order contract responses without depending on their persistence models.

If fan-out or reporting cost justifies it, reports can own a read model populated from published changes. Such a projection needs duplicate handling, ordering/version semantics, a rebuild strategy, and an explicit freshness contract. Event-driven reporting is an option, not a prerequisite for the first migration.

Separate owners do not provide a globally consistent snapshot merely because reads happen in one process. Reports snapshots are best-effort and become stale only after 24 hours; intervening Inventory and Orders changes do not alter freshness. Explicit report generation reads current Inventory and Orders data.

## Lambda handlers and deployment

Keep one Lambda per HTTP endpoint in `api`. A handler decodes the HTTP request, constructs runtime-independent context, calls the owning service, and maps the result or typed error to the existing HTTP response contract. Domain validation, persistence, and workflow orchestration belong in services and server-owned message handlers.

Use a queue and worker Lambda handler per vertical initially, with additional queue/handler pairs when workloads need different runtimes or execution settings. Place queue adapters in `worker`; `FooWorkerHandler` decodes the message, constructs `RequestContext`, and delegates to its server-owned `FooMessageHandler#handleMessage` directly. Calls into another vertical always use that vertical's client interface, including requests that schedule background work. Internal worker messages and entry points are not public service contracts.

Initially, bind service interfaces to direct implementations through each vertical's server-local Dagger module and the top-level component/factory. Business code depends only on client targets. Existing Lambda handlers provide HTTP adaptation without introducing an RPC transport package.

Separate Lambda functions do not share a JVM. Initially, each deployment artifact bundles the implementations required by its reachable call graph: an imports Lambda making direct inventory calls includes both implementations. Instances may share the same physical database while remaining separate processes. A single-host deployment could use the same interfaces and direct wiring.

Queue ownership is independent of JVM ownership. One worker JVM can host consumers for multiple vertical queues, while API and worker processes may be separate. Provider calls may execute concurrently; process-local caches, synchronization, and pacing do not provide coordination across JVMs. Shared FetchTCG rate limiting and additional credential-refresh coordination are deferred until needed.

Future extraction replaces an interface binding with an RPC adapter and gives the remote service its own deployment and data configuration. It also requires transport security, compatibility, monitoring, and data migration. It must not require callers to adopt new business workflows because local calls previously hid partial failure.

## Service errors and HTTP mapping

Define the shared checked, sealed base `ServiceException extends Exception` and its six permitted subclasses in `//lib/service` before adding the service client declarations that use `throws ServiceException`. The exception types remain runtime-independent, without Lambda or API Gateway dependencies. Vertical-specific error contracts belong in the owning client's package. HTTP Lambda handlers catch `ServiceException` and use shared HTTP error mapping from `//lib/http`:

| Exception                     | HTTP response                                                                    |
| ----------------------------- | -------------------------------------------------------------------------------- |
| `BadRequestException`         | `400 Bad Request`                                                                |
| `UnauthorizedException`       | `401 Unauthorized`                                                               |
| `ForbiddenException`          | `403 Forbidden`                                                                  |
| `NotFoundException`           | `404 Not Found`                                                                  |
| `ConflictException`           | `409 Conflict`                                                                   |
| `ServiceUnavailableException` | `503 Service Unavailable`                                                        |
| Unexpected internal failure   | Generic `500 Internal Server Error` response, with diagnostics logged internally |

Use `UnauthorizedException` when authentication is missing or invalid, and `ForbiddenException` when an authenticated caller lacks permission. Use `BadRequestException` for invalid arguments rather than introducing a separate `InvalidArgumentException` name.

`ServiceException` is a checked exception with `(String message)` and `(String message, Throwable cause)` constructors. The message is the safe user-facing explanation; the optional cause is retained for internal diagnostics. Do not serialize causes or stack traces into responses. Defer error codes and structured details until a contract requires them. HTTP exception names do not require services to construct HTTP responses. Keep API Gateway response types out of service contracts. The adapter preserves existing public error bodies unless an API change is intentional.

`HttpResponseFactory.serviceError(ServiceException)` exhaustively switches over the six permitted exception categories and returns the existing `{"message":"..."}` response shape. Unexpected failures outside this checked exception hierarchy use a generic `500` response, and the HTTP adapter retains responsibility for logging them without exposing their details.

A calling service interprets dependency errors in the context of its operation. It can propagate the checked `ServiceException` unchanged through its declared contract or catch a specific subtype to translate its meaning. An inventory `NotFoundException` does not automatically mean an imports endpoint should return `404`; it may represent invalid input or a broken internal invariant. Unexpected invariant and infrastructure failures remain loud and are not disguised as validation failures or silent fallbacks.

Future RPC transports encode the defined error vocabulary and reconstruct client-side exceptions. They do not serialize arbitrary Java exception objects. Expected service exceptions must reach the HTTP adapter without being obscured by generic runtime wrappers.

Worker adapters default to failing the invocation for every processing failure, including all `ServiceException` categories. SQS retries and then routes exhausted messages to the owning queue's DLQ. An HTTP-named exception does not automatically become an acknowledged terminal business failure in a worker. Define explicit business-failure handling only when a concrete workflow needs it; manual DLQ recovery is the initial default.

## Incremental migration

Apply these steps to any vertical. Choose the next vertical from its ownership boundaries, dependency graph, and coupling rather than following a fixed domain order. Before changing code, identify its authoritative records, invariants, callers, dependencies, and cross-vertical workflows.

1. First establish shared context/error types in `//lib/service`, including `ServiceException` and the required HTTP-named subclasses. Then define the selected vertical's contracts in its client package, with `throws ServiceException` on every operation. Place HTTP adapters in `api` and queue adapters in `worker`, with a separate `BUILD.bazel` for each package that exists. Restrict server visibility to its own worker adapters, construction wiring, and implementation tests, and provide the service through its server-local Dagger module.
2. Migrate one complete operation behind the vertical's service interface, with a dedicated operation class and a thin delegate from the provided service. Keep its persistence models and mutations in its server package, replace cross-vertical implementation access with client contracts, and preserve validation, authorization, concurrency, and replay behavior. Define recovery wherever the operation crosses owners.
3. Enforce the migrated boundary in Bazel and remove the old repository/model dependencies for that path. Mark remaining migration gaps explicitly; do not describe a vertical as isolated while its other callers still bypass the interface.
4. Move endpoint application logic into services and adopt the shared HTTP error mapper while preserving public responses.
5. Repeat for the vertical's remaining operations and callers, then apply the same process to other verticals. Choose the order from the dependency graph and ownership decisions. Cross-vertical reads use client contracts or explicitly owned projections.
6. Shrink shared domain state and infrastructure access as ownership becomes explicit. Move background records, dispatch, and progress into their workflow owners; replace global active-job checks with required local lifecycle rules and Publish-owned admission. Preserve replay and manual recovery before removing legacy job access. Resolve any remaining audit or resource-lifecycle ownership before declaring isolation complete.
7. Consider RPC or separate databases only when a deployment need justifies them. No extraction is required to benefit from the boundaries.

Each migration should be small enough to review and leave a working system. When replacing an existing implementation, build the service implementation to behavioral parity first, switch consumers to the service boundary, then remove the legacy implementation after its final callers move. Temporary duplication is acceptable when it keeps the new implementation independent of legacy callers during parity work. Update the README's current architecture, dependency graph, and behavioral contracts in the same implementation change.

## Verification and completion criteria

A vertical is migrated when its callers use only client contracts, its records have exclusive ownership, its handlers are adapters, and its cross-vertical workflows recover without shared atomicity.

Verify this with:

- Bazel visibility and dependency checks that prevent implementation access outside the owning worker adapters, explicit wiring, and implementation tests.
- Service-level tests for domain rules, authorization, and expected errors without a Lambda runtime.
- Integration tests of real persistence behavior, including concurrent/repeated requests and atomic idempotency enforcement.
- Recovery tests for a real dependency committing before its caller records completion, plus duplicate delivery and workflow resumption.
- Adapter tests preserving HTTP status codes, response bodies, and safe error disclosure.
- Focused worker coverage for replay, owner-local progress, Publish admission, and failure propagation to queue retries and DLQ recovery. Avoid a concurrency matrix or a generic worker framework solely for testing.
- Existing end-to-end import, publish, order, pricing, scanning, photo, and reporting workflows where affected.

Use existing repository quality gates for implementation work. Tests should verify observable invariants and recovery guarantees rather than reproduce service wiring.

## Decisions to resolve during implementation

The boundary principles above are the strategy. Before migrating affected paths, settle these implementation details in concrete contracts:

- Exact service operation shapes, bulk bounds, idempotency retention, and concurrency semantics.
- Concrete owner-local operation records, replay identities, FIFO grouping, and manual recovery procedures; outbox delivery is deferred until justified.
- Audit aggregation and long-lived photo references.
- The dependency graph and whether any current vertical boundary splits an invariant that must remain atomic.
- Reporting freshness and consistency across owners.
- Exact Bazel labels and composition-root package boundaries needed to keep module-construction access narrow.
- The exact shared error/context types in `//lib/service` and the eventual wire representation if RPC is introduced.

Record these decisions alongside the migration that needs them; do not leave a cross-vertical persistence dependency as the permanent solution.
