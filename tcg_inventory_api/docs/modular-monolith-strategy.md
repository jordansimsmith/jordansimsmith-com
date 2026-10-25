# Modular monolith engineering strategy

This strategy defines how to evolve the TCG inventory API into a loosely coupled monolith with explicit service contracts, exclusive data ownership, and enforceable build boundaries. The purpose is to keep domain logic understandable as the service grows and prevent verticals from accumulating dependencies on one another's implementation and persistence details.

This is a target architecture and an incremental migration strategy, not a description of boundaries already enforced everywhere. The [service README](../README.md) remains the specification for current behavior. Update it as each migration changes the implementation or observable contracts.

## Starting point

The service already groups Java code into verticals: games, catalog, scans, imports, inventory, orders, reports, publish, settings, and pricing. Bazel gives these verticals separate libraries, but consumers can still depend on implementation libraries and persistence models. Shared infrastructure includes the top-level Dagger factory/module, job records and dispatch, audit records, table configuration, and storage helpers.

Examples of coupling to remove include:

- Import confirmation constructs inventory persistence models and calls `InventoryRepository` directly.
- Reports query inventory and order records directly and aggregate their persistence models.
- Pricing, orders, and publish depend on inventory implementation code.
- HTTP handlers perform application work and return HTTP errors from within that work.

Existing import confirmation and order processing already use durable progress and replay to complete work in smaller transactions. Preserve those guarantees while changing ownership; service interfaces alone do not provide them.

## Goals and scope

- Keep vertical slices as the organizing structure, revisiting a boundary when its invariants require a different owner.
- Give each vertical a public client package and a private server package.
- Make cross-vertical access go through runtime-independent service contracts.
- Give each persisted record and business invariant one authoritative owner.
- Keep direct in-process calls initially and preserve one Lambda per HTTP endpoint.
- Make future RPC implementations possible without changing callers' business logic.
- Replace cross-vertical atomicity with explicit, recoverable workflows.

Immediate service extraction, an RPC framework, an event bus for every interaction, and a database split are outside the initial scope. Deployments may continue sharing a DynamoDB table and bundling multiple vertical implementations.

## Package and build boundaries

Each vertical has a `client` package containing its service interface and the request, response, and vertical-specific error types needed to use it. Its `server` package contains the implementation, repositories, persistence models, internal policies, operation services, and the vertical's Dagger module. Shared types such as `ServiceException` and `RequestContext` belong in `//lib/http`.

Each `client` and `server` directory has its own `BUILD.bazel`, making them separate Bazel packages. The client BUILD defines the public contract target and its tests; the server BUILD defines the private implementation target and its tests. HTTP adapter targets remain separate from server implementation targets. A single BUILD at the vertical root does not replace these two build files. Configure visibility to enforce the service boundary.

An illustrative package structure is:

```text
tcginventory/
  inventory/
    client/
      BUILD.bazel            # public client target and contract tests
      InventoryService.java
      ...                    # request and response types
    server/
      BUILD.bazel            # private server target and implementation tests
      InventoryModule.java   # provides InventoryService
      ...                    # one operation service per interface method
  imports/
    client/
      BUILD.bazel
      ImportService.java
      ...                    # request and response types
    server/
      BUILD.bazel
      ImportModule.java      # provides ImportService, consumes InventoryService
      ...                    # operation services, repositories, persistence models
```

This illustrates responsibilities; exact source paths and target names are chosen during implementation. There is no separate contracts package: vertical contracts live in their client packages, and shared HTTP/service types live in `//lib/http`. RPC transport packages are deferred until RPC is introduced. Dagger modules live in their owning server packages; no separate wiring package is required. Bazel target names remain kebab-case.

| Consumer                   | Allowed dependencies                                                            |
| -------------------------- | ------------------------------------------------------------------------------- |
| Any target                 | Public client targets and shared types from `//lib/http`                        |
| A vertical's server        | Its own client, other verticals' clients, and required infrastructure libraries |
| HTTP adapter               | Service clients and HTTP/runtime libraries                                      |
| Top-level composition root | Server modules needed to construct and expose service interfaces                |
| Server tests               | The implementation under test and required test setup                           |

No other production target may depend on a vertical's server. Enforce this with Bazel target visibility and strict Java dependencies (`strict_java_deps`): visibility restricts which packages can declare a server dependency, and strict dependencies require imported classes to come from declared direct dependencies. These checks run during normal Bazel builds and tests; no separate architecture-checking framework is required. Do not bypass them through `exports`, a shared implementation library, broad source globs, or factories that expose repositories. The top-level composition root is the deliberate construction exception and contains no business logic.

Bazel visibility operates at package granularity, not individual target granularity. Keep the composition root in a small Bazel package limited to construction responsibilities when enforcing that exception. If the existing top-level package temporarily contains both construction and business targets, document the broader access as a migration gap rather than claiming visibility isolates one factory target.

The dependency graph between verticals should remain acyclic and intentional. A client package being public permits access; it does not make every dependency desirable. Put orchestration in the vertical that owns the workflow. If calls repeatedly go both ways, reconsider ownership or introduce a deliberate workflow boundary.

Shared code should provide infrastructure or common types in `//lib/http`. Vertical-specific contracts remain in the owning client's package. Shared libraries must not become another home for domain logic that avoids vertical ownership.

## Service contracts

Services expose business operations rather than generic database access. For example, imports asks inventory to receive confirmed stock; it does not construct `SkuItem`, `UnitItem`, or DynamoDB transaction fragments.

An illustrative service declaration is `CreateImportResponse createImport(RequestContext ctx, CreateImportRequest request) throws ServiceException` on `ImportService`.

Contract requirements:

- Every service operation accepts a dedicated, operation-specific request object and returns a dedicated, operation-specific response object, both defined in the owning vertical's client package. Follow the signature `OperationResponse operation(RequestContext ctx, OperationRequest request) throws ServiceException` even when either object has zero fields or only one field. For example, define `GetAccountRequest()` for a read with no input fields and `DeleteListingResponse()` for a mutation with no output fields. `RequestContext` is a separate parameter and does not replace the request object. Do not use bare scalar/collection parameters or return values, `void`, `Void`, or null in place of these objects.
- Every service interface method explicitly declares `throws ServiceException`. Use the same declaration on its operation-class method and named or anonymous delegate implementation. Define the checked base `ServiceException extends Exception` and the required HTTP-named subclasses in `//lib/http` before introducing client interfaces that depend on them. Callers must handle/translate the checked exception or propagate it through their own declared contract. Service implementations and delegates preserve the typed exception until a caller or runtime adapter handles it.
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

The object implementing `FooService` is a thin delegate. A named `FooServiceImpl`, if used, contains delegation only; it does not accumulate the operation bodies. Prefer an anonymous implementation inside `FooModule` when that avoids an otherwise empty implementation class.

Each vertical defines a Dagger `FooModule` in `foo.server` with one `@Provides` method returning `FooService`. Construct private repositories, adapters, and operation services inside that provider, using private construction helpers when useful. The provider accepts required infrastructure and other verticals' client interfaces as parameters. It does not expose operation classes, repositories, tokens, or persistence models as public Dagger bindings, and it does not include another vertical's server module.

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

Modules can alternatively be included by the existing top-level application module. Dagger supplies cross-vertical dependencies by interface type; `FooModule` does not construct `BarService` or depend on `BarModule`. The factory exposes service interfaces for migrated verticals, with required shared infrastructure bindings supplied by the application. Existing factory accessors exposing implementation details are migration gaps to remove as their callers move.

Scope the service provider to the component lifecycle where appropriate. A singleton belongs to one component in one process; it does not create shared instances across Lambda environments. The same client interface can later be provided by an RPC implementation without changing callers.

## Data ownership

Each vertical exclusively owns its persisted records, schema changes, migrations, reads, writes, and invariants. Physical co-location is a deployment choice. For the current DynamoDB single-table design, non-overlapping ownership means item types and access patterns, not necessarily separate tables or partition keys.

No vertical may read another vertical's records through its repositories, raw DynamoDB access, shared indexes, or a transaction containing the other owner's items. References to foreign resources are contract IDs, not dependencies on the other service's key encoding or item layout.

An ownership change does not automatically require a data migration. Retain compatible persisted records when their ownership can simply change. When new metadata can be initialized through a normal operation, document that initialization and the behavior before it occurs rather than requiring an automatic backfill. Use an explicit migration only when preserving existing data or behavior requires one.

The starting ownership model is:

| Vertical  | Authoritative responsibility                                                               |
| --------- | ------------------------------------------------------------------------------------------ |
| Games     | Registered game identity, supported finishes, and capabilities                             |
| Catalog   | Normalized catalog lookup, provider adapters, and catalog snapshots                        |
| Scans     | Scan sessions, recognition/review progress, and source-image lifecycle                     |
| Imports   | Intake rows, appraisal/review progress, and import confirmation workflow                   |
| Inventory | SKUs, physical units, sequence/location allocation, and stock transitions                  |
| Orders    | Offer/order records, allocations recorded for the order workflow, and fulfillment progress |
| Pricing   | Pricing policy, price-floor configuration, and repricing orchestration                     |
| Publish   | Marketplace projection and publication progress                                            |
| Reports   | Report generation and report snapshots/projections                                         |
| FetchTCG  | Marketplace account, credentials, seller identity, and provider integration                |
| Settings  | Application settings not assigned to another vertical                                      |

Inventory owns authoritative unit reservations and availability. Orders asks inventory to reserve, release, or sell units and records its own workflow progress. Pricing asks inventory to apply price changes; publish obtains listing data and acknowledges publication through inventory's contract. These callers cannot mutate stock or SKU records themselves.

Listing photos cross lifecycle boundaries: imports owns review-time attachments, while inventory must retain the immutable photo references needed by confirmed units. Shared S3 storage does not imply shared authority to change those references.

Before completing a vertical migration, assign ownership for its audit records, job state, idempotency records, and any shared operational gates. These cannot remain implicit shared database access. Keep an inventory mutation and its authoritative audit entry in inventory's local transaction. A combined audit view can aggregate published records without becoming a synchronous dependency of every mutation.

The existing per-user active-job gate also needs an explicit design. Preserve its externally visible conflict behavior during migration and decide whether a coordinator owns it or vertical-local concurrency rules replace it. A remote gate cannot simply participate in every vertical's local DynamoDB transaction.

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
3. Inventory owns sequence allocation, stock creation, associated audit writes, and deduplication. Replaying an accepted operation returns its prior outcome without creating more units or allocating a new sequence range.
4. Imports persists progress and marks the import confirmed only after inventory confirms all required effects.

If inventory commits and imports crashes before saving progress, imports repeats the same operation. A new attempt must not generate a new idempotency key. Deduplication and its authoritative mutation must be committed atomically within inventory, or have an equivalent recoverable protocol. Define retention of deduplication records against the workflow's replay window.

Preserve existing ordering, location, photo, and audit guarantees. Decide whether stock becomes visible incrementally or only after finalization; changing that behavior requires an explicit product contract change. Bulk service operations do not imply one unbounded DynamoDB transaction.

Where committing local state must reliably schedule follow-up work, use a transactional outbox or an equivalent durable delivery mechanism. Sending a queue message after a database commit is not atomic. This is especially relevant to existing paths whose queue-send failures leave durable jobs awaiting manual enqueue.

Compensation is a business operation with its own rules, not an automatic rollback. Receiving physical stock and reserving a unit for an order may require different recovery policies.

## Reads and reporting

Cross-vertical reads use service contracts. Start with paginated or bounded bulk reads where they are sufficient. Reports may aggregate inventory and order contract responses without depending on their persistence models.

If fan-out or reporting cost justifies it, reports can own a read model populated from published changes. Such a projection needs duplicate handling, ordering/version semantics, a rebuild strategy, and an explicit freshness contract. Event-driven reporting is an option, not a prerequisite for the first migration.

Separate owners do not provide a globally consistent snapshot merely because reads happen in one process. Specify whether a report is best-effort, uses per-owner watermarks, or needs another snapshot protocol. Preserve or deliberately replace the current audit-based staleness behavior when audit ownership changes.

## Lambda handlers and deployment

Keep one Lambda per HTTP endpoint. A handler decodes the HTTP request, constructs runtime-independent context, calls the owning service, and maps the result or typed error to the existing HTTP response contract. Domain validation, persistence, and workflow orchestration belong in services. Background consumers likewise enter through the appropriate service boundary.

Initially, bind service interfaces to direct implementations through each vertical's server-local Dagger module and the top-level component/factory. Business code depends only on client targets. Existing Lambda handlers provide HTTP adaptation without introducing an RPC transport package.

Separate Lambda functions do not share a JVM. Initially, each deployment artifact bundles the implementations required by its reachable call graph: an imports Lambda making direct inventory calls includes both implementations. Instances may share the same physical database while remaining separate processes. A single-host deployment could use the same interfaces and direct wiring.

Future extraction replaces an interface binding with an RPC adapter and gives the remote service its own deployment and data configuration. It also requires transport security, compatibility, monitoring, and data migration. It must not require callers to adopt new business workflows because local calls previously hid partial failure.

## Service errors and HTTP mapping

Define the shared checked base `ServiceException extends Exception` in `//lib/http` before adding the service client declarations that use `throws ServiceException`, naming subclasses after their HTTP statuses: `BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`, and `ServiceUnavailableException`. All subclasses are checked exceptions as well. These types remain runtime-independent, without Lambda or API Gateway dependencies. Vertical-specific error contracts belong in the owning client's package. Lambda handlers catch `ServiceException` and use shared HTTP error mapping from `//lib/http`:

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

Service errors carry a stable machine-readable code, a safe user message, and optional structured details. HTTP exception names do not require services to construct HTTP responses. Keep API Gateway response types and internal stack traces out of service contracts. The adapter preserves existing public error bodies unless an API change is intentional; an internal error code need not become a new HTTP field immediately.

A calling service interprets dependency errors in the context of its operation. It can propagate the checked `ServiceException` unchanged through its declared contract or catch a specific subtype to translate its meaning. An inventory `NotFoundException` does not automatically mean an imports endpoint should return `404`; it may represent invalid input or a broken internal invariant. Unexpected invariant and infrastructure failures remain loud and are not disguised as validation failures or silent fallbacks.

Future RPC transports encode the defined error vocabulary and reconstruct client-side exceptions. They do not serialize arbitrary Java exception objects. Expected service exceptions must reach the adapter without being obscured by generic runtime wrappers. Background adapters preserve retryable failures for the job runtime rather than converting them into HTTP responses.

## Incremental migration

Apply these steps to any vertical. Choose the next vertical from its ownership boundaries, dependency graph, and coupling rather than following a fixed domain order. Before changing code, identify its authoritative records, invariants, callers, dependencies, and cross-vertical workflows.

1. First establish shared context/error types in `//lib/http`, including `ServiceException` and the required HTTP-named subclasses. Then define the selected vertical's contracts in its client package, with `throws ServiceException` on every operation. Add a `BUILD.bazel` in each of its client and server packages, restrict server visibility, and provide the service through its server-local Dagger module.
2. Migrate one complete operation behind the vertical's service interface, with a dedicated operation class and a thin delegate from the provided service. Keep its persistence models and mutations in its server package, replace cross-vertical implementation access with client contracts, and preserve validation, authorization, concurrency, and replay behavior. Define recovery wherever the operation crosses owners.
3. Enforce the migrated boundary in Bazel and remove the old repository/model dependencies for that path. Mark remaining migration gaps explicitly; do not describe a vertical as isolated while its other callers still bypass the interface.
4. Move endpoint application logic into services and adopt the shared HTTP error mapper while preserving public responses.
5. Repeat for the vertical's remaining operations and callers, then apply the same process to other verticals. Choose the order from the dependency graph and ownership decisions. Cross-vertical reads use client contracts or explicitly owned projections.
6. Shrink shared domain state and infrastructure access as ownership becomes explicit. Resolve ownership and recovery for any shared jobs, concurrency gates, audit records, or resource lifecycles used by the selected vertical before declaring isolation complete.
7. Consider RPC or separate databases only when a deployment need justifies them. No extraction is required to benefit from the boundaries.

Each migration should be small enough to review and leave a working system. Update the README's current architecture, dependency graph, and behavioral contracts in the same implementation change.

## Verification and completion criteria

A vertical is migrated when its callers use only client contracts, its records have exclusive ownership, its handlers are adapters, and its cross-vertical workflows recover without shared atomicity.

Verify this with:

- Bazel visibility and dependency checks that prevent implementation access outside explicit wiring and tests.
- Service-level tests for domain rules, authorization, and expected errors without a Lambda runtime.
- Integration tests of real persistence behavior, including concurrent/repeated requests and atomic idempotency enforcement.
- Recovery tests for a real dependency committing before its caller records completion, plus duplicate delivery and workflow resumption.
- Adapter tests preserving HTTP status codes, response bodies, and safe error disclosure.
- Existing end-to-end import, publish, order, pricing, scanning, photo, and reporting workflows where affected.

Use existing repository quality gates for implementation work. Tests should verify observable invariants and recovery guarantees rather than reproduce service wiring.

## Decisions to resolve during implementation

The boundary principles above are the strategy. Before migrating affected paths, settle these implementation details in concrete contracts:

- Exact service operation shapes, bulk bounds, idempotency retention, and concurrency semantics.
- Ownership of shared job admission, durable dispatch, audit aggregation, and long-lived photo references.
- The dependency graph and whether any current vertical boundary splits an invariant that must remain atomic.
- Reporting freshness and consistency across owners.
- Exact Bazel labels and composition-root package boundaries needed to keep module-construction access narrow.
- The exact shared error/context types in `//lib/http` and the eventual wire representation if RPC is introduced.

Record these decisions alongside the migration that needs them; do not leave a cross-vertical persistence dependency as the permanent solution.
