# Packing list API

The packing list API service provides an authenticated HTTP API for reading packing templates and creating, listing, fetching, and updating per-user trip packing lists.

Instead of maintaining a manual spreadsheet each holiday, this service supports a separate packing list per trip that can be generated from shared templates (base + variations), edited for that specific holiday, then tracked via simple per-item packing statuses.

## System architecture

```mermaid
graph TD
  A[Browser] -->|HTTPS| B[API Gateway]
  B -->|Lambda proxy| C[Lambda handlers (Java 17)]
  C --> D[DynamoDB: packing_list]
  C --> E[Secrets Manager: packing_list_api]
```

## Requirements

### Functional requirements

- Require authentication for all endpoints via HTTP Basic
- Enforce user scoping on every request using the `?user=<id>` query parameter
- Return the base packing template and available variations for client-side list generation (`GET /templates`)
- Create trips with metadata (`name`, `destination`, `departure_date`, `return_date`) and a fully-materialized snapshot of trip items (`POST /trips`)
- List trips for the authenticated user ordered by `departure_date` descending (`GET /trips`)
- Fetch a single trip by id including its items (`GET /trips/{trip_id}`)
- Update trips after creation (edit trip details and items; add/remove items; update quantity/tags/status) by replacing the full trip object (metadata + items) (`PUT /trips/{trip_id}`)
- Support trip items with categories, quantities, tags, and packing status (`unpacked`, `packed`, `pack-just-in-time`)
- Snapshot behavior: once a trip is created, it is independent of future template/variation changes
- Validate inputs (required fields, item uniqueness, enums, quantity bounds) and return consistent JSON errors
- Planned: delete trips (hard delete)
- Planned: trip lifecycle enforcement (editable through the end of the departure day; read-only starting the next local day; inactive trips separated in listing)

### Technical specifications

- **Runtime**: AWS Lambda (Java 17), built with Bazel
- **API layer**: API Gateway REST API with Lambda proxy integration
- **Auth**: API Gateway custom REQUEST authorizer backed by an `AuthHandler` Lambda using HTTP Basic
- **Storage**: DynamoDB single-table style with `pk`/`sk` prefixes and snake_case attributes
- **JSON**:
  - request/response bodies are JSON
  - all fields use snake_case (`@JsonProperty(...)`)
- **IDs**: `trip_id` is a UUID (string)
- **Dates**:
  - `departure_date` and `return_date` are local date strings (`YYYY-MM-DD`)
  - timestamps are epoch seconds (number)
- **API versioning**: none (no `/v1`)
- **CORS**:
  - allow origin: `https://packing-list.jordansimsmith.com`
  - allow headers: `Authorization`, `Content-Type`
  - allow methods: `GET`, `POST`, `PUT`, `OPTIONS`
- **Hostnames**:
  - frontend: `packing-list.jordansimsmith.com`
  - api: `api.packing-list.jordansimsmith.com`

## Implementation details

### Core concepts

- **Trip**: a holiday with metadata (name, destination, departure date, return date) and a persisted `items` list.
- **Base template**: the shared default packing list. In M1 the service ships with **one** base template.
- **Variation**: a shared additive set of items layered on top of the base template (e.g. skiing, tramping). Variations are additive only: they do not remove or override items.
- **Trip list snapshot**: the fully-materialized `items` array stored on the trip at creation time; this makes the trip independent of future template/variation changes.
- **Item**:
  - `name`: free text
  - `category`: free-text label used for grouping (recommended fallback: `misc/uncategorised`)
  - `quantity`: integer; defaults to 1; must be `>= 1`
  - `tags`: list of free-text labels (e.g. `hand luggage`, `leave in car`, `wear on plane`, `optional`)
  - `status`: `unpacked` | `packed` | `pack-just-in-time` (defaults to `unpacked` when generated)

### API conventions

- **Base url**: `https://api.packing-list.jordansimsmith.com`
- **Auth**:
  - all endpoints require `Authorization: Basic …`
  - all endpoints require a `user` query parameter (e.g. `?user=alice`)
  - unauthorized requests are rejected at API Gateway with `WWW-Authenticate: Basic`
- **Content types**:
  - requests with a body: `Content-Type: application/json`
  - responses: `Content-Type: application/json; charset=utf-8`
- **Error shape** (non-2xx):

```json
{
  "message": "quantity must be >= 1"
}
```

### Domain model

#### Item statuses

Trip items support three packing statuses:

- `unpacked`
- `packed`
- `pack-just-in-time`

#### Item identity and uniqueness

- Items are uniquely identified **within a trip** by their **normalized name**.
- Normalization algorithm:
  - case-insensitive (lowercase)
  - trimmed
  - collapsed internal whitespace
- The backend rejects any trip payload containing two items whose normalized names collide (both on `POST /trips` and `PUT /trips/{trip_id}`).

#### Template/variation merge rules (client-side)

Template generation and merging are intentionally performed client-side (the API only serves templates and persists trips), but the merge rules are part of the domain contract:

- **Identity**: same normalized item name
- **Merge behavior**:
  - quantities are **summed**
  - tags are **unioned** (deduplicated)
  - **first category wins** when merged
- **Status default**: generated trip items default to `unpacked`

#### Template authoring guidance (to avoid accidental merges)

- If two things are meaningfully different, make item names more specific (e.g. `ski goggles` vs `swim goggles`).
- Avoid including the same normalized item name in both base + variation unless the intention is to sum quantities.

#### Tag registry

Tags are embedded on items within templates and trips; there is no global tag registry.

### API types (JSON)

**Template item**

```json
{
  "name": "passport",
  "category": "travel",
  "quantity": 1,
  "tags": ["hand luggage"]
}
```

**Base template**

```json
{
  "base_template_id": "generic",
  "name": "generic",
  "items": [
    {
      "name": "passport",
      "category": "travel",
      "quantity": 1,
      "tags": ["hand luggage"]
    }
  ]
}
```

**Variation**

```json
{
  "variation_id": "skiing",
  "name": "skiing",
  "items": [
    {
      "name": "ski jacket",
      "category": "clothes",
      "quantity": 1,
      "tags": []
    }
  ]
}
```

**Trip item**

```json
{
  "name": "passport",
  "category": "travel",
  "quantity": 1,
  "tags": ["hand luggage"],
  "status": "unpacked"
}
```

**Trip**

```json
{
  "trip_id": "6f7a0dbe-3c7a-4f5c-9c9f-74e7d9c0a5f5",
  "name": "Japan 2026",
  "destination": "Tokyo",
  "departure_date": "2026-01-12",
  "return_date": "2026-01-26",
  "items": [
    {
      "name": "passport",
      "category": "travel",
      "quantity": 1,
      "tags": ["hand luggage"],
      "status": "unpacked"
    }
  ],
  "created_at": 1766884800,
  "updated_at": 1766885625
}
```

### Endpoints

#### `GET /templates`

Returns the single base template and all available variations.

Response 200:

```json
{
  "base_template": {
    "base_template_id": "generic",
    "name": "generic",
    "items": []
  },
  "variations": [
    {
      "variation_id": "skiing",
      "name": "skiing",
      "items": []
    }
  ]
}
```

#### `POST /trips`

Creates a trip with the fully-materialized list items.

Notes:

- The client is responsible for applying the merge rules described above.
- The backend validates input (`quantity >= 1`, status enum, unique items by normalized-name).
- The backend generates `trip_id` and timestamps.

#### `GET /trips`

Lists trip summaries for the authenticated `user`, ordered by `departure_date` descending.

Response 200:

```json
{
  "trips": [
    {
      "trip_id": "6f7a0dbe-3c7a-4f5c-9c9f-74e7d9c0a5f5",
      "name": "Japan 2026",
      "destination": "Tokyo",
      "departure_date": "2026-01-12",
      "return_date": "2026-01-26",
      "created_at": 1766884800,
      "updated_at": 1766884800
    }
  ]
}
```

#### `GET /trips/{trip_id}`

Fetches a single trip by id, including `items`.

- Returns `404` when not found:

```json
{
  "message": "Not Found"
}
```

#### `PUT /trips/{trip_id}`

Replaces the entire trip (metadata + items). The request body should include the full trip object.

Validation notes:

- `trip_id` in the path must match `trip_id` in the body; mismatch => `400 {"message":"trip_id mismatch"}`
- the handler preserves `created_at` from the existing item and updates `updated_at`

### Authentication / authorization

- **Secret name**: `packing_list_api`
- **Secret format** (JSON):

```json
{
  "users": [
    {
      "user": "alice",
      "password": "123"
    }
  ]
}
```

- **User scoping**:
  - all requests include `?user=<id>`
  - the authorizer denies requests where the authenticated Basic username does not match the query param

### Data persistence model (DynamoDB)

- **Table name**: `packing_list`
- **Primary key**:
  - `pk` (String): `USER#<user>`
  - `sk` (String): `TRIP#<trip_id>`
- **Item type**:
  - `TRIP#...` items store the full trip (including `items` array) in a single DynamoDB item
- **Attributes** (snake_case):
  - `user` (String)
  - `trip_id` (String)
  - `name` (String)
  - `destination` (String)
  - `departure_date` (String; `YYYY-MM-DD`)
  - `return_date` (String; `YYYY-MM-DD`)
  - `items` (List<Map>; each element is a `TripItem`)
  - `created_at` (Number; epoch seconds)
  - `updated_at` (Number; epoch seconds)

#### Global secondary index (trip listing)

- **GSI name**: `gsi1`
- **Purpose**: list trips for a user ordered by `departure_date` descending
- **Keys**:
  - `gsi1pk` (String): `USER#<user>`
  - `gsi1sk` (String): `DEPARTURE#<YYYY-MM-DD>#TRIP#<trip_id>`
- **Query**:
  - `gsi1pk = USER#<user>`
  - `scanIndexForward = false` (departure date desc)
- **Projection**: `ALL`

### Infrastructure notes (Terraform)

Terraform patterns should support:

- `GET` on `/templates`
- `GET` + `POST` on `/trips`
- `GET` + `PUT` on `/trips/{trip_id}` (path parameter resource)
- `OPTIONS` on `/templates`, `/trips`, and `/trips/{trip_id}` returning `200` with CORS headers

API Gateway/custom domain setup:

- Custom domain: `api.packing-list.jordansimsmith.com`
- Edge-optimized custom domain with ACM certificate in `us-east-1`
- Stage `prod` with base path mapping at root (no `/v1`)

DynamoDB safety settings:

- Point-in-time recovery enabled
- Deletion protection enabled

### Observability

- Logs emitted to CloudWatch using slf4j/logback (consistent with other services in this repo)
- Prefer structured log fields (or consistent prefixes) including `user` and `trip_id` where applicable
- Avoid logging secrets or auth tokens

### Testing

Recommended test coverage (consistent with repo patterns):

- **Unit tests**:
  - request/response parsing and validation
  - item name normalization + uniqueness enforcement
- **Integration tests**:
  - handler tests using a DynamoDB test container
  - auth handler tests using fake secrets
- **E2E tests** (optional):
  - localstack-based tests for API Gateway/Lambda wiring patterns

## Example templates and variations

These examples are intended to seed initial hardcoded templates/variations and to clarify merge/name-normalization behavior; they are expected to evolve.

### Base template: generic

- **travel**:
  - passport (qty 1, tags: `hand luggage`)
  - driver's licence (qty 1, tags: `hand luggage`)
  - tickets / booking confirmation (qty 1, tags: `hand luggage`)
  - wallet (qty 1, tags: `hand luggage`)
  - house/car keys (qty 1)
- **electronics**:
  - mobile phone (qty 1, tags: `hand luggage`)
  - phone charger (qty 1, tags: `hand luggage`)
  - power bank (qty 1, tags: `hand luggage`)
  - earbuds (qty 1, tags: `hand luggage`)
- **toiletries**:
  - toothbrush (qty 1)
  - toothpaste (qty 1)
  - deodorant (qty 1)
  - sunscreen (qty 1)
  - moisturiser (qty 1)
  - insect repellent (qty 1)
  - personal meds / basic first aid (qty 1)
- **clothes** (illustrative quantities; expected to be edited during generation):
  - underwear (qty 7)
  - socks (qty 7)
  - t-shirts (qty 5)
  - shorts (qty 1)
  - pants (qty 1)
  - jumper (qty 1)
  - raincoat (qty 1)
  - sleepwear (qty 1)
- **misc/uncategorised**:
  - drink bottle (qty 1)
  - snacks (qty 1)

### Variation: tramping

- **gear**:
  - sleeping bag (qty 1)
  - sleeping mat (qty 1)
  - lightweight tramping tent (qty 1)
  - tramping stove + fuel (qty 1)
  - headlamp (qty 1)
  - water purifier (qty 1)
  - dry bags (qty 1)
  - toilet paper (qty 1)
  - map (qty 1)
  - lighter / matches (qty 1, tags: `hand luggage`)
- **clothes**:
  - tramping boots (qty 1)
  - wool tramping socks (qty 2)
  - sandfly tights (qty 1, tags: `optional`)

### Variation: camping

- **gear**:
  - car camping tent (qty 1)
  - camping stove + fuel (qty 1)
  - cooking utensils (qty 1)
  - eating utensils (qty 1)
  - dishwashing liquid (qty 1)
  - tea towel / dishcloth (qty 1)
  - pegs + rope (qty 1)
  - torch / lantern (qty 1)
  - solar shower (qty 1, tags: `optional`)
  - fishing gear (qty 1, tags: `optional`)

### Variation: skiing

- **clothes**:
  - ski pants (qty 1)
  - ski jacket (qty 1)
  - ski helmet (qty 1)
  - ski goggles (qty 1)
  - ski gloves (qty 1)
  - ski glove liners (qty 1, tags: `optional`)
  - ski socks (qty 1)
  - balaclava (qty 1)
  - neck tube (qty 1)
- **gear**:
  - knee brace (qty 1, tags: `optional`)

### Variation: cycling

- **clothes**:
  - cycling gloves (qty 1)
  - padded bike shorts (qty 1)
- **gear**:
  - cycling helmet (qty 1)
  - gel seat (qty 1, tags: `optional`)
  - panniers (qty 1)
  - dry bags (qty 1, tags: `optional`)
