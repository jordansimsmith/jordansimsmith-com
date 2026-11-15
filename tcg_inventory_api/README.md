# TCG inventory API

The TCG inventory API is the source of truth for physical card stock and storage
locations. It assigns cards to boxes in intake order so sellers can store them
without sorting by set or name. It projects available stock to FetchTCG and
uses marketplace orders to reserve cards and generate location-ordered pull
sheets. The main consumer is [TCG inventory web](../tcg_inventory_web/README.md).

## Features

- **Intake.** Import a ManaBox CSV or upload a batch of card-front JPEGs for
  recognition and printing review. Appraise the cards, review what to keep, and
  confirm the keepers into inventory. Cards appraised at NZ$20 or more require
  listing photos before confirmation.
- **Inventory and pricing.** Browse SKUs and individual physical copies, remove
  units or correct their condition with an audit trail, set a per-user price
  floor, and reprice available stock.
- **Marketplace and fulfillment.** Publish stock and prices to FetchTCG, reserve
  units for accepted offers, release reservations for cancelled orders, and
  pick and fulfill paid orders. Pull sheets give the current card positions and
  the details needed to ship.
- **Reporting.** Regenerate a stored dashboard of stock, valuation, and sales
  across supported games.

Magic: The Gathering and English Pokémon share the inventory, publishing,
order, and reporting workflows. CSV and scanner intake are enabled for Magic
only. The review catalog uses Scryfall for Magic and a published TCGCSV snapshot
for Pokémon.

## Build and deployment

The REST API runs on API Gateway with one Java 21 Lambda per endpoint and the
shared [Auth API](../auth_api/README.md) authorizer. Java workers consume
vertical-owned SQS FIFO queues. Scan recognition runs in a separate Python
Lambda container with an offline CollectorVision catalog. DynamoDB holds
service records, and S3 stores scans, listing photos, and catalog snapshots.
The Pokémon catalog refresh worker builds as a standalone JAR. Production has
no refresh Lambda or schedule.

Run these commands from the repository root. The full build and deployment
include the Linux AMD64 scan image. Integration and end-to-end tests use Docker.

```bash
bazel build //tcg_inventory_api/...
bazel test --build_tests_only //tcg_inventory_api/...
bazel test //tcg_inventory_api:e2e-tests
```

Deploy through the repository's Terraform runner. It builds the Lambda JARs,
pushes the scan worker image to ECR by digest, and applies the infrastructure.

```bash
bazel run //:apply -- --path tcg_inventory_api/infra --path platform/infra
```

## Modular monolith

Organize code into domain verticals such as Imports, Inventory, Orders, and
Listings. Each vertical owns its records and invariants, including on the shared
DynamoDB table. For example, Orders owns the order and its fulfillment progress,
Inventory owns unit reservations and stock audits, and Listings owns marketplace
listing associations.

- `client` exposes the service interface and runtime-independent request and
  response types. Operations take `RequestContext` first and declare
  `ServiceException`. Calls between verticals use these interfaces.
- `server` owns persistence, operation implementations, private queue messages,
  and workflow processing. Give each public service operation its own
  implementation class and wire it through the vertical's Dagger module.
- `api` translates HTTP requests and service responses. `worker` decodes queue
  messages and passes them to the owning server's message handler.

Bazel gives each of these packages its own build file and enforces the
boundaries through visibility and strict Java dependencies.
Server targets are accessible to their own worker adapters, implementation
tests, and the application composition root for wiring. Dagger binds clients to
in-process implementations within each Lambda. Those contracts provide a place
to introduce RPC bindings if a vertical needs a separate deployment.

## Workflow guarantees

- **Ownership and ordering.** Each workflow's vertical owns admission, dispatch,
  progress, and completion. Unrelated workflows can run concurrently. Publish
  runs on demand and allows one active run per user, including retries and
  continuation batches.
  It synchronizes Orders before asking Listings to project available quantities.
  Listings clears a SKU's dirty flag only if the published version still matches.
- **Replay and partial progress.** Transactions stay within one vertical.
  Workflows that span verticals use stable operation IDs and durable checkpoints.
  For example, Imports retries a row with the same operation ID if Inventory
  creates the unit before Imports saves the result. FIFO ordering applies within
  each message group, and workers can receive the same message more than once.
  A request that accepts background work acknowledges admission. The owning
  service reports completion after the required effects finish.
- **Recovery.** Processing failures fail the invocation. SQS retries the
  message and moves it to the owning queue's dead-letter queue after retries
  run out. Saving progress and sending SQS messages are separate operations, so
  dispatch can also fail after progress is saved. Investigate the failure and
  redrive or resume the existing operation with its saved identity and
  progress. A stuck Publish run continues to hold admission until it is resolved.

## Where to find the details

- [Terraform routes and queues](infra/main.tf) and the
  [deployment artifact manifest](../tools/terraform/manifest.json)
- [Java verticals and service contracts](src/main/java/com/jordansimsmith/tcginventory)
  and [Dagger wiring](src/main/java/com/jordansimsmith/tcginventory/TcgInventoryFactory.java)
- [End-to-end workflows](src/test/java/com/jordansimsmith/tcginventory/TcgInventoryE2ETest.java)
