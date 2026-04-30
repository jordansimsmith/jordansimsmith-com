# Football calendar API

The football calendar API aggregates fixtures from multiple football sources, stores them in DynamoDB, and serves a public iCal subscription feed for calendar clients.

## Overview

- **Service type**: backend API (`football_calendar_api`)
- **Interfaces**: scheduled fixture ingestion and public HTTPS iCal endpoint
- **Runtime**: AWS Lambda (Java 21) behind API Gateway
- **Primary storage**: DynamoDB table `football_calendar`
- **Primary consumers**: iPhone Calendar and other iCal-compatible clients

## User stories

- As a player or supporter, I want fixtures from multiple football sources merged into one feed, so that I only need one calendar subscription.
- As a maintainer, I want per-team fixture reconciliation with stale-match deletion, so that removed fixtures disappear automatically.
- As a calendar app user, I want a public `GET /calendar` endpoint, so that native iCal clients can subscribe without extra setup.
- As a player, I want email notifications when upcoming fixtures change, so that I notice schedule or venue changes promptly.

## Features and scope boundaries

### In scope

- Poll configured teams from Northern Regional Football v2 API, Football Fix, and Subfootball every 15 minutes.
- Transform source records into a unified fixture model and persist records in DynamoDB.
- Reconcile fixtures per team partition by upserting current fixtures and deleting stale `match_id` values for teams present in a run.
- Expose `GET /calendar` that returns an aggregated iCal calendar across all configured teams.
- Include optional fixture metadata when available from a source (`status`, `latitude`, `longitude`).
- Send an SNS notification when a fixture within the next 7 days is added, removed, or modified (timestamp, venue, address, status, or team names change).

### Out of scope

- Authentication or authorization for `GET /calendar` (endpoint is intentionally public).
- Runtime team management, per-user team configuration, or per-request filtering.
- Manual fixture edits through an API.
- Browser-specific CORS behavior guarantees.

## Architecture

```mermaid
flowchart TD
  scheduler[EventBridge schedule rate 15 minutes] --> updateLambda[Update fixtures Lambda]
  updateLambda --> nrf[Northern Regional Football v2 API]
  updateLambda --> footballFix[Football Fix fixtures page]
  updateLambda --> subfootball[Subfootball iCal feed]
  nrf --> updateLambda
  footballFix --> updateLambda
  subfootball --> updateLambda
  updateLambda --> ddb[DynamoDB football_calendar]
  updateLambda --> sns[SNS fixture_updates]
  sns --> email[Email subscribers]
  client[iCal client] --> apiGateway[API Gateway GET /calendar]
  apiGateway --> getLambda[Get calendar subscription Lambda]
  getLambda --> ddb
  getLambda --> apiGateway
  apiGateway --> client
```

### Primary workflow

```mermaid
sequenceDiagram
  participant Scheduler as EventBridge
  participant Update as UpdateFixturesHandler
  participant NRF as NRFv2API
  participant Fix as FootballFix
  participant Sub as Subfootball
  participant Ddb as DynamoDB
  participant Client as CalendarClient
  participant Api as APIGateway
  participant Get as GetCalendarSubscriptionHandler

  Scheduler->>Update: trigger every 15 minutes
  Update->>NRF: POST fixture/Dates
  NRF-->>Update: fixture list
  Update->>Fix: GET fixtures page
  Fix-->>Update: HTML fixture rows
  Update->>Sub: GET team iCal feed
  Sub-->>Update: VEVENT entries
  Update->>Ddb: upsert current fixtures by team
  Update->>Ddb: delete stale match_ids in team partition
  alt upcoming fixture changed
    participant SNS as SNS
    Update->>SNS: publish fixture change notification
    SNS-->>Update: confirmation
  end
  Client->>Api: GET /calendar
  Api->>Get: invoke lambda
  Get->>Ddb: query TEAM partitions
  Ddb-->>Get: stored fixtures
  Get-->>Api: text/calendar body
  Api-->>Client: 200 iCal response
```

## Main technical decisions

- Use Lambda plus API Gateway for consistency with the repo's API services and simple infrastructure.
- Keep team/source configuration in code (`TeamsFactoryImpl`) instead of runtime configuration to keep deployment simple.
- Use DynamoDB keys `pk = TEAM#<team_id>` and `sk = MATCH#<match_id>` for direct per-team reads and deterministic overwrite behavior.
- Fetch all sources before writing so a failed source call fails the run before reconciliation writes start.
- Build iCal on demand from DynamoDB instead of caching generated calendars to keep output aligned with latest persisted fixtures.
- Compare fetched fixtures against existing DynamoDB state before writing to detect changes, and only notify for fixtures within the next 7 days to keep notifications relevant.

## Domain glossary

- **Fixture**: a single match record with teams, kickoff timestamp, and venue metadata.
- **Team id**: canonical configured team label used in DynamoDB partition keys (for example `Flamingos`).
- **Match id**: upstream fixture identifier (`Id`, `data-fixture-id`, or iCal `UID`) used in sort keys.
- **Reconciliation**: per-team diff that deletes persisted fixtures missing from the latest fetched set for that team.
- **Calendar event**: iCal `VEVENT` generated from one persisted fixture.

## Integration contracts

### External systems

- **Northern Regional Football v2 API**: outbound HTTPS `POST` to `https://www.nrf.org.nz/api/v2/competition/widget/fixture/Dates` with no auth. Required request fields are `CompIds`, `OrgIds`, `GradeIds` (integer arrays), `From`, and `To` (ISO local date-time strings); response fields consumed are `Id`, `HomeTeamName`, `HomeOrgName`, `AwayTeamName`, `AwayOrgName`, `From`, `VenueName`, `VenueAddress`, `LocationLat`, `LocationLng`, and `StatusName`. Team display names are constructed as `"{OrgName} {TeamName}"`. Called every scheduled run. Non-200 or parse errors fail the update run.
- **Football Fix**: outbound HTTPS `GET` to `https://footballfix.spawtz.com/Leagues/Fixtures` with query params `SportId`, `VenueId`, `LeagueId`, `SeasonId`, and `DivisionId`, no auth. Required parsed HTML fields are date headers (`tr.FHeader`), fixture rows (`tr.FRow`), time (`td.FDate`), venue (`td.FPlayingArea`), teams (`td.FHomeTeam`, `td.FAwayTeam`), and `data-fixture-id` from `td.FScore nobr`. Called every scheduled run. Network or parse failures fail the update run; rows missing a fixture id are skipped.
- **Subfootball**: outbound HTTPS `GET` to `https://subfootball.com/teams/calendar/{teamId}` with `Accept: text/calendar`, no auth. Required VEVENT fields are `UID`, `SUMMARY`, `DTSTART`, and `LOCATION`; `DESCRIPTION` is optional and used to derive field/venue text. Called every scheduled run. Non-200 or parse errors fail the update run; malformed events are skipped.

## API contracts

### Conventions

- Base URL: `https://api.football-calendar.jordansimsmith.com`
- Auth: none (public endpoint)
- Versioning: no version segment in path
- Content type: `text/calendar; charset=utf-8`
- Request payload: none
- On handler failures, no custom error envelope is defined by this service.

### Endpoint summary

| Method | Path        | Purpose                                               |
| ------ | ----------- | ----------------------------------------------------- |
| `GET`  | `/calendar` | Return aggregated iCal calendar for configured teams. |

### Example request and response

Request:

```http
GET /calendar HTTP/1.1
Host: api.football-calendar.jordansimsmith.com
Accept: text/calendar
```

Response `200`:

```text
BEGIN:VCALENDAR
PRODID:-//jordansimsmith.com//Football Calendar//EN
VERSION:2.0
BEGIN:VEVENT
SUMMARY:Bucklands Beach AFC Dusties vs Ellerslie AFC Flamingos
DTSTART:20260418T010000Z
LOCATION:Lloyd Elsmore Pk: Field 2, Lloyd Elsmore Park
DESCRIPTION:Status: Confirmed
END:VEVENT
END:VCALENDAR
```

## Data and storage contracts

### DynamoDB model

- **Table name**: `football_calendar`
- **Primary key**:
  - `pk`: `TEAM#<team_id>`
  - `sk`: `MATCH#<match_id>`
- **Stored attributes**:
  - `team`, `match_id`, `home_team`, `away_team`, `timestamp`, `venue`, `address`, `latitude`, `longitude`, `status`
- **Write behavior**:
  - `UpdateFixturesHandler` writes fixtures with `putItem` (upsert by primary key).
  - For teams with fetched fixtures in the current run, existing items with missing `match_id` are deleted.

Representative item:

```json
{
  "pk": "TEAM#Flamingos",
  "sk": "MATCH#6334635",
  "team": "Flamingos",
  "match_id": "6334635",
  "home_team": "Bucklands Beach AFC Dusties",
  "away_team": "Ellerslie AFC Flamingos",
  "timestamp": 1744938000,
  "venue": "Lloyd Elsmore Pk: Field 2",
  "address": "Lloyd Elsmore Park",
  "latitude": -36.910553,
  "longitude": 174.90271,
  "status": "Confirmed"
}
```

### Data ownership expectations

- Upstream source systems own raw fixture facts (`NRF`, `Football Fix`, `Subfootball`).
- `football_calendar` is the service's canonical projected store for calendar serving.
- iCal output is derived from DynamoDB records and is not persisted separately.

## Behavioral invariants and time semantics

- NRF fixture `From` values are parsed as `ISO_LOCAL_DATE_TIME` in `Pacific/Auckland`.
- Football Fix date and time strings are parsed in `Pacific/Auckland`.
- Subfootball event start times are read from iCal `DTSTART` and converted to `Instant`.
- Persisted `timestamp` values use epoch seconds (UTC instant via `EpochSecondConverter`).
- NRF and Football Fix fixtures are team-filtered using case-insensitive substring matching on home/away names.
- Subfootball fixtures are not name-filtered after fetch; all events from configured team feed ids are stored.
- Event order in `GET /calendar` is not contractually guaranteed.
- Only team partitions represented in the current fetched fixture set are reconciled for stale deletions.
- Fixture change notifications are only sent for fixtures with a timestamp between `now` and `now + 7 days`.
- A fixture is considered changed if any of `timestamp`, `venue`, `address`, `status`, `home_team`, or `away_team` differ from the persisted value, or if the fixture is added or removed.
- A single notification is published per update run, aggregating all upcoming changes across all teams.

## Source of truth

| Entity               | Authoritative source                              | Notes                                                                |
| -------------------- | ------------------------------------------------- | -------------------------------------------------------------------- |
| Team configuration   | `TeamsFactoryImpl`                                | Hardcoded team ids, matchers, and source parameters in code.         |
| NRF fixtures         | Northern Regional Football v2 API                 | Pulled every schedule run and projected into DynamoDB.               |
| Football Fix rows    | Football Fix fixtures page                        | Parsed from HTML table rows and projected into DynamoDB.             |
| Subfootball events   | Subfootball iCal feed                             | Parsed from VEVENT entries and projected into DynamoDB.              |
| Calendar feed        | DynamoDB `football_calendar` table                | `GET /calendar` reads persisted fixtures and renders iCal on demand. |
| Change notifications | SNS topic `football_calendar_api_fixture_updates` | Published by `UpdateFixturesHandler` when upcoming fixtures change.  |

## Security and privacy

- `GET /calendar` is intentionally public (`authorization = NONE` in API Gateway).
- Transport is HTTPS through API Gateway custom domain and ACM certificate.
- Service runtime does not read credential secrets in current scope.
- IAM permissions for lambdas are scoped to DynamoDB operations for `football_calendar`, SNS publish and list for `fixture_updates`, plus basic execution logging.
- Stored data is fixture metadata only; no user account data is modeled by this service.

## Configuration and secrets reference

### Environment variables

| Name                                      | Required | Purpose                                                               | Default behavior                             |
| ----------------------------------------- | -------- | --------------------------------------------------------------------- | -------------------------------------------- |
| `FOOTBALL_CALENDAR_NRF_API_URL`           | no       | NRF base URL resolved with `/api/v2/competition/widget/fixture/Dates` | defaults to `https://www.nrf.org.nz`         |
| `FOOTBALL_CALENDAR_FOOTBALL_FIX_BASE_URL` | no       | Football Fix base URL resolved with `/Leagues/Fixtures`               | defaults to `https://footballfix.spawtz.com` |
| `FOOTBALL_CALENDAR_SUBFOOTBALL_BASE_URL`  | no       | Subfootball base URL resolved with `/teams/calendar/{id}`             | defaults to `https://subfootball.com`        |

Current runtime configuration is code and infra defined:

- DynamoDB table name is hardcoded as `football_calendar` in `FootballCalendarModule`.
- Team/source configuration is hardcoded in `TeamsFactoryImpl`.
- Schedule cadence, runtime, memory, timeout, and endpoint wiring are defined in Terraform.

### Secret shape

None in current scope. This service does not read runtime secrets.

## Performance envelope

- Update cadence is fixed at `rate(15 minutes)` via EventBridge schedule.
- Lambda runtime bounds are `java21`, `1024 MB` memory, and `30` second timeout.
- DynamoDB uses `PAY_PER_REQUEST` billing mode with `pk/sk` keyed access.
- No explicit latency or throughput SLOs are defined in current scope.

## Testing and quality gates

- Unit tests validate client parsing and mapping behavior (NRF JSON, Football Fix HTML, Subfootball iCal).
- Integration tests cover update reconciliation, change detection notifications, and iCal response generation against DynamoDB test containers.
- E2E tests run against LocalStack and internal NRF/Football Fix/Subfootball stub hosts on a shared Testcontainers network, so the suite is deterministic and CI-safe with no outbound internet dependency.
- Required checks before merge:
  - `bazel test //football_calendar_api:all`
  - `bazel build //football_calendar_api:all`

## Local development and smoke checks

- Run all service tests: `bazel test //football_calendar_api:all`
- Build deployable artifacts: `bazel build //football_calendar_api:all`
- Quick smoke flow:
  1. Run `bazel test //football_calendar_api:e2e-tests`.
  2. Verify the test invokes `update_fixtures_handler` and `get_calendar_subscription_handler` inside LocalStack.
  3. Verify parsed iCal output contains expected `PRODID`, at least one `VEVENT`, and core event fields (`SUMMARY`, `DTSTART`, `LOCATION`).

## End-to-end scenarios

### Scenario 1: scheduled refresh and calendar subscription

1. EventBridge triggers `update_fixtures` on the 15-minute schedule.
2. Service fetches fixtures from NRF, Football Fix, and Subfootball for configured teams.
3. Service writes current fixtures to DynamoDB and removes stale `match_id` values in processed team partitions.
4. Calendar client calls `GET /calendar`.
5. Service reads team partitions from DynamoDB and returns aggregated iCal events.

### Scenario 2: fixture removed upstream

1. A previously persisted fixture id disappears from an upstream source response for a processed team.
2. Next scheduled update fetches current fixtures and computes the new `match_id` set.
3. Reconciliation deletes the stale DynamoDB item for that team partition.
4. Subsequent `GET /calendar` responses no longer contain that removed fixture.

### Scenario 3: upcoming fixture changed

1. An existing fixture within the next 7 days has its venue or kickoff time changed upstream.
2. Scheduled update fetches current fixtures and compares against persisted DynamoDB state.
3. Handler detects the change, updates DynamoDB, and publishes an SNS notification summarizing the change.
4. Email subscribers receive the notification with details of what changed.
