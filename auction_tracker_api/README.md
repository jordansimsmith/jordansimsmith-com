# Auction tracker API

The auction tracker API service runs scheduled backend workflows that scrape Trade Me listings, judge configured searches with per-search LLM listing filters, store discovered items, and send a daily digest email for newly found listings.

## Overview

- **Service type**: backend scheduled worker (`auction_tracker_api`)
- **Interface**: EventBridge scheduled events to AWS Lambda handlers (`RequestHandler<ScheduledEvent, Void>`)
- **Runtime**: AWS Lambda (Java 21)
- **Primary storage**: DynamoDB table `auction_tracker` with `gsi1` for duplicate checks
- **Primary consumers**: email subscribers on SNS topic `auction_tracker_api_digest`

## User stories

- As a bargain hunter, I want Trade Me listings scraped automatically, so that I do not miss relevant new items.
- As a digest subscriber, I want one daily deduplicated summary, so that I can review new listings quickly.
- As a maintainer, I want duplicate detection per search and listing URL, so that persisted records and digests stay clean.
- As an MTG bulk-lot hunter, I want junk listings (wrong game, single cards, basic lands, store repacks) filtered by an LLM judge, so that the digest only surfaces lots worth a look.
- As a RAM kit hunter, I want mismatched listings (wrong family, DDR generation, configuration, speed, timings, or form factor) filtered by an LLM judge, so that the digest only surfaces kits matching my existing G.Skill Trident Z 2x16GB DDR4-3200 CL16 kit.

## Features and scope boundaries

### In scope

- Run `UpdateItemsHandler` every 15 minutes to scrape predefined Trade Me searches.
- Build search URLs with term, optional price filters, condition filter, and `sort_order=expirydesc`.
- Fetch listing pages, normalize listing URLs, and skip listings marked as reserve not met.
- Judge new listings on searches with a configured judge (all six searches: the three MTG searches `bulk`, `collection`, `assorted` and the three RAM searches `g.skill`, `gskill`, `trident z`) using an OpenAI LLM against the judge's six binary criteria, and persist the overall verdict.
- Carry judge configuration (prompt resource, model, reasoning effort, criteria) per search: the MTG searches share one judge config, the RAM searches share another.
- Store newly discovered items in DynamoDB with deterministic key prefixes and 30-day TTL.
- Prevent duplicate inserts for the same `(search_url, item_url)` pair using GSI `gsi1`.
- Run `SendDigestHandler` daily and publish a digest for listings discovered in the last 24 hours, excluding listings judged `fail`.
- Deduplicate digest entries by listing URL when the same listing appears in multiple searches.

### Out of scope

- Exposing public HTTP endpoints or interactive UI contracts.
- User-configurable search management at runtime (searches are code-defined in `SearchFactoryImpl`).
- Scraping paginated result pages beyond the first page of each search result.
- Persisting listing descriptions in DynamoDB (descriptions are extracted during scraping, passed to the judge, but not stored).
- Custom retry orchestration beyond default AWS retry behavior and Lambda re-invocation semantics.
- Re-judging listings after their first verdict (judgments are permanent for a record's lifetime), including records persisted by the removed narrow RAM search (`g.skill trident z 32gb ddr4`), which age out via TTL.
- Spec-based RAM searches (`32gb ddr4`, `ddr4 ram`): result volume exceeds the single scraped page and is mostly junk; revisit only if the brand searches miss listings.

## Architecture

```mermaid
flowchart TD
  updateSchedule[EventBridge rate 15 minutes] --> updateHandler[UpdateItemsHandler Lambda]
  updateHandler --> searchFactory[SearchFactoryImpl]
  updateHandler --> tradeMe[Trade Me website]
  updateHandler --> listingJudge[LlmListingJudge]
  listingJudge --> openAi[OpenAI chat completions API]
  updateHandler --> auctionTable[DynamoDB auction_tracker]
  digestSchedule[EventBridge daily schedule] --> digestHandler[SendDigestHandler Lambda]
  digestHandler --> auctionTable
  digestHandler --> digestTopic[SNS auction_tracker_api_digest]
  digestTopic --> subscribers[Email subscribers]
```

### Primary workflow

```mermaid
sequenceDiagram
  participant EventBridge
  participant UpdateHandler
  participant TradeMe
  participant OpenAI
  participant DynamoDB
  participant DigestHandler
  participant SNS

  EventBridge->>UpdateHandler: invoke every 15 minutes
  UpdateHandler->>TradeMe: fetch search page and listing pages
  UpdateHandler->>DynamoDB: query gsi1 for duplicate check
  alt search has judge config
    UpdateHandler->>OpenAI: judge new listing title and description with the search's model and prompt
    OpenAI-->>UpdateHandler: six-criteria JSON verdict
  end
  UpdateHandler->>DynamoDB: put new SEARCH/TIMESTAMP item records with judgment
  EventBridge->>DigestHandler: invoke daily
  DigestHandler->>DynamoDB: query each search partition for last 24 hours
  DigestHandler->>DigestHandler: exclude judgment=fail, deduplicate by listing URL
  alt new items exist
    DigestHandler->>SNS: publish digest subject and message
  end
```

## Main technical decisions

- Use EventBridge + Lambda for low operational overhead and fixed scraping/digest cadence.
- Use Jsoup scraping against Trade Me server-rendered pages instead of a browser automation stack.
- Use DynamoDB `pk`/`sk` prefixes with `gsi1` so duplicate checks are direct key lookups, not scans.
- Keep table and topic names code-defined (`auction_tracker`, `auction_tracker_api_digest`) to reduce configuration complexity.
- Keep search definitions in code (`SearchFactoryImpl`) for deterministic behavior and easy testability.
- Treat digest timing as a daily NZ-local intent while current infrastructure executes at `21:00 UTC` (`cron(0 21 * * ? *)`).
- Use browser-like headers and cookies in scrape requests to improve compatibility with Trade Me page delivery.
- Judge listings at scrape time (the only moment descriptions exist in memory) and persist the verdict, so each listing is judged at most once per search and the digest filters purely from storage.
- Carry judge configuration as a nullable nested `Judge` record (`prompt`, `model`, `reasoningEffort`, `criteria`) on each `SearchFactory.Search`, with one shared constant per judge in `SearchFactoryImpl`; criteria ride with the config because verdict validation is per-judge.
- MTG judge: `gpt-5.4-mini` with reasoning effort `none` via the shared `lib/llm` client; selected by the eval harness in `evals/mtg_bulk/` (perfect test-split TPR/TNR at the lowest cost and latency).
- RAM judge: `gpt-5.4-nano` with reasoning effort `low`; selected by the eval harness in `evals/ram/` (perfect test-split TPR/TNR at roughly 3.6x lower cost than the mini candidate).
- Broaden RAM coverage with three brand searches (`g.skill`, `gskill`, `trident z`) because Trade Me tokenizes `g.skill` and `gskill` differently and the previous narrow term returned almost nothing; spec-based terms stay out to keep results within the single scraped page.
- Freeze each production system prompt (winning eval prompt plus train-split few-shot examples) as a checked-in resource loaded through `lib/prompts`: `src/main/resources/prompts/mtg-bulk-judge.md` (mtg_bulk v2) and `src/main/resources/prompts/ram-judge.md` (ram v3).
- Fail closed on judge errors: exceptions fail the invocation and the run retries on the next 15-minute tick; already-persisted items are not re-judged.
- Memoize judgments per `(judge prompt, listing URL)` within an invocation so overlapping judged searches trigger one LLM call per listing.

## Domain glossary

- **Search definition**: one configured Trade Me query with base URL, search term, optional price bounds, condition, and optional judge configuration.
- **Judge configuration**: a prompt resource name, OpenAI model, reasoning effort, and ordered criteria list shared by the searches that use it (one config for MTG, one for RAM).
- **Discovered item**: one listing found and parsed from Trade Me with normalized URL and title.
- **Duplicate item**: an item where the same search URL and listing URL already exists in `gsi1`.
- **Judged search**: a search definition with a judge configuration (currently all six searches: three MTG sharing `prompts/mtg-bulk-judge.md`, three RAM sharing `prompts/ram-judge.md`).
- **Judgment**: the LLM verdict for a listing, `pass` or `fail`; overall pass requires all of the judge's six criteria to pass (MTG: `mtg_cards`, `bulk_scale`, `not_basic_lands`, `not_universes_beyond`, `civilian_seller`, `fixed_collection`; RAM: `trident_z_family`, `ddr4`, `kit_2x16gb`, `speed_3200`, `timings_cl16`, `desktop_udimm`).
- **Digest window**: rolling 24-hour interval from the digest handler execution timestamp.
- **Cross-search duplicate**: the same listing URL appearing in multiple search definitions.

## Integration contracts

### External systems

- **Trade Me website**: outbound `GET` requests to search and listing pages derived from configured searches. The base origin defaults to `https://www.trademe.co.nz` and can be overridden with `AUCTION_TRACKER_TRADEME_BASE_URL` (used in E2E tests). Requests include browser-like headers/cookies and a 30-second timeout. Item-page fetch failures are logged and skipped; unrecoverable search errors fail the invocation.
- **Amazon DynamoDB**: outbound reads/writes against table `auction_tracker`. Update flow performs duplicate checks and inserts; digest flow queries per-search partitions for recent items.
- **Amazon SNS**: outbound publish to topic `auction_tracker_api_digest` when at least one new item exists in the digest window. Topic ARN is resolved by listing topics and matching by topic-name suffix.
- **Amazon EventBridge**: scheduled invocation source for both handlers (`rate(15 minutes)` and `cron(0 21 * * ? *)`).
- **OpenAI chat completions API**: outbound `POST /v1/chat/completions` for new listings on judged searches, with the search's configured model and reasoning effort (`gpt-5.4-mini`/`none` for MTG, `gpt-5.4-nano`/`low` for RAM) and JSON response format. The base origin defaults to `https://api.openai.com` and can be overridden with `AUCTION_TRACKER_OPENAI_BASE_URL` (used in E2E tests). The API key is read from the `auction_tracker_api` secret. Request failures and malformed verdicts fail the invocation.
- **AWS Secrets Manager**: outbound read of secret `auction_tracker_api` for the OpenAI API key, resolved lazily on the first judged listing per Lambda instance.

## API contracts

### Conventions

- This service does not expose public HTTP endpoints in current scope.
- Invocation contract is Lambda scheduled execution with input `ScheduledEvent` and output `null`.
- Handler exceptions are logged and rethrown as runtime exceptions, causing invocation failure.

### Endpoint summary

| Interface             | Contract                                | Purpose                                 |
| --------------------- | --------------------------------------- | --------------------------------------- |
| EventBridge -> Lambda | scheduled event to `UpdateItemsHandler` | scrape listings and persist new records |
| EventBridge -> Lambda | scheduled event to `SendDigestHandler`  | find recent items and publish digest    |

### Example request and response

Invocation event (representative):

```json
{
  "source": "aws.events",
  "detail-type": "Scheduled Event"
}
```

Handler result on success:

```json
null
```

## Data and storage contracts

### DynamoDB model

- **Table name**: `auction_tracker`
- **Primary key**:
  - `pk` (string): `SEARCH#<full_search_url>`
  - `sk` (string): `TIMESTAMP#<epoch_seconds_zero_padded_to_10_digits>ITEM#<item_url>`
- **Attributes**:
  - `title` (string): listing title
  - `url` (string): normalized listing URL with query parameters removed
  - `timestamp` (number): epoch seconds (`Clock.now()`)
  - `judgment` (string, optional): LLM verdict `pass` or `fail`; absent for items from searches without a judge configuration and for records created before judging existed
  - `ttl` (number): epoch seconds at `timestamp + 30 days`
  - `version` (number): optimistic locking version (`@DynamoDbVersionAttribute`)
  - `gsi1pk` (string): `SEARCH#<full_search_url>`
  - `gsi1sk` (string): `ITEM#<item_url>`
- **Global secondary index `gsi1`**:
  - hash key: `gsi1pk`
  - range key: `gsi1sk`
  - projection: `ALL`
  - usage: exact duplicate existence check before inserting an item
- **Access patterns**:
  - duplicate check: query `gsi1` on exact `gsi1pk` + `gsi1sk`
  - digest query: query one search partition for items with `sk` greater than a rolling 24-hour threshold
- **Retention behavior**:
  - DynamoDB TTL is enabled on `ttl`; items expire approximately 30 days after discovery

Representative record:

```json
{
  "pk": "SEARCH#https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/search?search_string=trident+z&price_max=200&condition=used&sort_order=expirydesc",
  "sk": "TIMESTAMP#1751139600ITEM#https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/listing/6021068148",
  "title": "32gb (2x 16gb) Trident Z RGB 3200Mhz DDR4 Memory",
  "url": "https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/listing/6021068148",
  "timestamp": 1751139600,
  "judgment": "pass",
  "ttl": 1753731600,
  "version": 1,
  "gsi1pk": "SEARCH#https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/search?search_string=trident+z&price_max=200&condition=used&sort_order=expirydesc",
  "gsi1sk": "ITEM#https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/listing/6021068148"
}
```

## Behavioral invariants and time semantics

- Every update invocation iterates all configured searches and attempts to process each one.
- Duplicate insert prevention is deterministic by exact `(search_url, item_url)` match through `gsi1`.
- A listing on a judged search is judged at most once per search: the `gsi1` duplicate check skips persisted records before the judge is consulted, and the persisted `judgment` never changes.
- Every judged search's verdict is validated against its own criteria list; a response missing any configured criterion is malformed and fails the invocation.
- Judging is fail-closed: an LLM error or malformed verdict fails the invocation; the affected listing is retried on the next scheduled run.
- Within one invocation, the same listing URL under the same judge prompt produces exactly one LLM call; all matching searches persist the shared verdict.
- Items with `judgment` = `fail` are never included in digest messages; items with `judgment` = `pass` or no judgment are included.
- Digest selection window is deterministic: items newer than `clock.now().minus(1, ChronoUnit.DAYS)`.
- Digest output deduplicates by listing URL across all configured searches.
- Listing URLs are canonicalized by stripping query parameters before persistence and digesting.
- Listings marked `Reserve not met` are filtered out and never persisted.
- Search-result pagination beyond the first page is not processed; the handler logs a warning when pagination is detected.
- `sk` includes zero-padded epoch seconds, preserving deterministic lexicographic time ordering.
- TTL is always computed as `timestamp + 30 days`.

## Source of truth

| Entity                     | Authoritative source                                                                      | Notes                                                                                       |
| -------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Search definitions         | `SearchFactoryImpl` in service code                                                       | current definitions are static and code-controlled                                          |
| Listing content snapshot   | Trade Me listing pages at scrape time                                                     | title/url are persisted; description is transient in scrape logic                           |
| Judge prompts              | `src/main/resources/prompts/mtg-bulk-judge.md`, `src/main/resources/prompts/ram-judge.md` | frozen system prompts validated by the eval harnesses in `evals/mtg_bulk/` and `evals/ram/` |
| Judge model and effort     | `Judge` constants in `SearchFactoryImpl`                                                  | MTG `gpt-5.4-mini`/`none`, RAM `gpt-5.4-nano`/`low`                                         |
| Persisted discovered items | DynamoDB `auction_tracker` table                                                          | canonical history used for duplicate checks, verdicts, and digests                          |
| Digest recipients          | SNS topic subscriptions in Terraform                                                      | email endpoints are infra-managed                                                           |
| Schedule cadence           | EventBridge rules in Terraform                                                            | update `rate(15 minutes)`, digest `cron(0 21 * * ? *)`                                      |

## Security and privacy

- Service is schedule-driven and does not expose public HTTP interfaces.
- Lambda IAM role grants required access for DynamoDB operations, SNS publish/list-topics operations, and reading the `auction_tracker_api` secret.
- The OpenAI API key lives only in Secrets Manager; it is never logged or persisted in DynamoDB.
- AWS credentials and region resolve through the AWS SDK default provider chain in Lambda/runtime environments.
- Integrations use HTTPS transport (Trade Me, OpenAI, and AWS APIs).
- Listing titles and descriptions (public Trade Me content) are sent to the OpenAI API for judging; no user data is involved.
- Logging uses standard INFO/WARN/ERROR levels; avoid introducing logs that include sensitive operational data such as subscription endpoints.
- Scraping uses browser-like request headers/cookies; these are implementation details and should be reviewed when upstream page behavior changes.

## Configuration and secrets reference

### Environment variables

| Name                               | Required | Purpose                                           | Default behavior                                            |
| ---------------------------------- | -------- | ------------------------------------------------- | ----------------------------------------------------------- |
| `AUCTION_TRACKER_TRADEME_BASE_URL` | no       | override Trade Me base origin for search/listings | defaults to `https://www.trademe.co.nz` when unset or blank |
| `AUCTION_TRACKER_OPENAI_BASE_URL`  | no       | override OpenAI base origin for judging           | defaults to `https://api.openai.com` when unset or blank    |

### Secret shape

Secrets Manager secret `auction_tracker_api` (value set manually after Terraform apply):

```json
{
  "openai_api_key": "sk-..."
}
```

## Performance envelope

- Update schedule runs every 15 minutes; digest schedule runs daily (`21:00 UTC`) and represents a daily NZ-local intent.
- Lambda runtime settings are `memory_size = 1024` MB for both handlers.
- Lambda timeout is `300` seconds for `UpdateItemsHandler` (sized for sequential judging at roughly 2 seconds per new judged listing, including first-run backfill) and `30` seconds for `SendDigestHandler`.
- Jsoup HTTP requests use a `30` second timeout per request.
- Judging costs roughly $0.011 per judged MTG listing and $0.0014 per judged RAM listing at current model pricing; steady-state runs judge only newly discovered listings.
- Per-item scrape failures are non-fatal for a run (warn and continue), while handler-level failures (including judge errors) bubble as invocation errors.

## Testing and quality gates

- Unit tests (`JsoupTradeMeClientTest`) cover URL generation, listing parsing, query-parameter stripping, and reserve filtering.
- Unit tests (`LlmListingJudgeTest`) cover verdict parsing, criterion failure, malformed responses, and the exact LLM request shape (per-judge model, effort, and criteria) against both real checked-in prompt resources.
- Unit tests (`SearchFactoryImplTest`) cover the six search definitions, their filters, and judge config wiring.
- Integration tests cover update persistence, duplicate prevention, multi-search processing, judgment persistence, judge-once memoization, fail-closed judge errors, 24-hour digest filtering, fail-judged exclusion, and digest deduplication (LLM calls faked via `FakeLlmClient`).
- E2E tests validate the LocalStack pipeline (Lambda invoke plus SNS/SQS notification path) against local Trade Me website and OpenAI stub containers and are CI-safe.
- Required checks before merge:
  - `bazel test //auction_tracker_api:unit-tests`
  - `bazel test //auction_tracker_api:integration-tests`
  - `bazel build //auction_tracker_api:update-items-handler`
  - `bazel build //auction_tracker_api:send-digest-handler`

## Local development and smoke checks

- Run unit and integration suites: `bazel test //auction_tracker_api:unit-tests //auction_tracker_api:integration-tests`
- Build handler artifacts: `bazel build //auction_tracker_api:update-items-handler //auction_tracker_api:send-digest-handler`
- Optional local E2E path (requires local image load): `bazel test //auction_tracker_api:e2e-tests`
- Minimal smoke flow:
  - run `UpdateItemsHandler` with known fake search responses and verify only new items are inserted
  - validate inserted keys follow `SEARCH#...` and `TIMESTAMP#...ITEM#...` formats
  - run `SendDigestHandler` and verify one digest publish when recent items exist, and no publish when none exist

## End-to-end scenarios

### Scenario 1: scheduled scrape ingests new listings

1. EventBridge triggers `UpdateItemsHandler` on the 15-minute schedule.
2. Handler loads static searches from `SearchFactoryImpl` and scrapes Trade Me search/listing pages.
3. For each discovered listing, handler checks `gsi1` for an existing `(search_url, item_url)` record.
4. Handler writes only new items to DynamoDB with timestamp, TTL, and prefixed primary/GSI keys.

### Scenario 2: new listing on a judged search is judged before persistence

1. `UpdateItemsHandler` discovers a new listing on a judged search (an MTG search or a RAM search).
2. Handler checks the per-run memo; on a miss it sends the listing title and description to the OpenAI API with the search's configured model, reasoning effort, and frozen system prompt.
3. The judge parses the JSON verdict against the search's six criteria; overall pass requires all six to pass, and failed criteria are logged with their reasoning.
4. Handler persists the record with `judgment` = `pass` or `fail`; other searches sharing the same judge prompt that discover the same listing in the same run reuse the memoized verdict.

### Scenario 3: daily digest publishes recent unique listings

1. EventBridge triggers `SendDigestHandler` on the daily schedule (`21:00 UTC`).
2. Handler queries each search partition for records newer than the rolling 24-hour threshold.
3. Handler excludes records with `judgment` = `fail` and deduplicates merged results by listing URL across searches.
4. Handler publishes one SNS digest when at least one item exists; otherwise it logs that no new items were found.
