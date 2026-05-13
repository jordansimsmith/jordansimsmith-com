# Japanese dictionary API

The Japanese dictionary API service provides an authenticated HTTP API for prefix-matching a single shared corpus of Japanese terms (~210k canonical headwords) by `expression`, `reading`, or `reading_romaji`, returning the top-10 matches ordered by exact match then frequency, plus per-user bookmarks for terms the user wants to revisit.

## Overview

- **Service type**: backend API (`japanese_dictionary_api`)
- **Interface**: REST over HTTPS
- **Runtime**: AWS Lambda (Java 21) behind API Gateway REST
- **Primary storage**: DynamoDB single table `japanese_dictionary` with three GSIs; shared `TERM#<sequence>` corpus rows and per-user `USER#<user>` bookmark rows coexist in the same table.
- **Auth model**: API Gateway custom REQUEST authorizer backed by `AuthHandler`
- **Primary consumer**: `japanese_dictionary_web`
- **Data refresh path**: standalone Python migration script (`migrations/000-rebuild-terms.py`) running with local AWS credentials; no Lambda-side ingest API

## User stories

- As a Japanese learner, I want to look up a term by its kanji prefix, so that I can find the canonical entry quickly while reading.
- As a learner who knows only the reading, I want to look up by hiragana or katakana prefix, so that I can find an entry without knowing the kanji.
- As a learner typing on a Latin keyboard, I want to look up by romaji prefix (Hepburn, kunrei, or wapuro), so that I can search without an IME.
- As an authenticated user, I want frequency-ordered results, so that the most commonly used senses surface first.
- As a learner who knows the exact word I'm looking for, I want a term that matches my query exactly to surface above prefix-only matches, so that I can find the canonical entry without scrolling past more frequent compounds.
- As a learner doing lookups, I want to bookmark a term, so that I can come back to it later when building flashcards.
- As a learner returning to the search page, I want my existing bookmarks to be visible, so that I don't accidentally bookmark the same term twice.
- As an operator refreshing the corpus, I want a single command on my laptop that clears existing terms and re-uploads from local Yomitan zips, so that the dictionary stays current.

## Features and scope boundaries

### In scope

- Require HTTP Basic authentication on every endpoint via the shared custom authorizer.
- Run prefix searches against three dimensions in parallel (`expression`, `reading`, `reading_romaji`) and union the results.
- Normalise incoming romaji queries (kunrei / wapuro / macron forms) to Modified Hepburn before matching.
- Return up to 10 results: exact matches on `expression`, `reading`, or `reading_romaji` first, then by `frequency_rank` ascending with NULLs last, with `sequence` ascending as final tie-break.
- Pass through Yomitan structured-content `glossary_raw` JSON unchanged for client-side rendering.
- Validate query length (≤ 64 characters after NFC + trim); short-circuit empty queries to a 200 with no results.
- Per-user bookmarks: idempotent `PUT /bookmarks/{sequence}` to flag a term, idempotent `DELETE /bookmarks/{sequence}` to remove the flag, `GET /bookmarks` to list every bookmarked sequence the calling user owns.
- Provide an operator migration script that destructively reloads the corpus from local Yomitan zips.

### Out of scope

- Substring or wildcard matching (prefix only).
- Fuzzy / typo-tolerant matching.
- Reverse English → Japanese lookup (no full-text search over gloss content).
- Deinflection of conjugated forms.
- Multi-dictionary support; single source `japanese_dictionary` only.
- Alternate-spelling / variant lookup; only the canonical headword is indexed.
- Pagination or "load more" beyond the top-10 cap.
- Per-user partitioning of the term corpus; corpus rows are read-shared. Per-user partitioning is in scope only for bookmark rows.
- Image-binary hosting for the ~595 Jitendex entries that reference image files; v1 renders inline placeholder text in the consumer.
- Hydrated bookmark listing (the listing endpoint returns sequence integers only — clients hydrate via `/search` or by holding existing result records).
- Existence validation of `{sequence}` against the term corpus on bookmark write; dangling bookmarks are tolerated.
- Anki / flashcard rendering and audio playback; the bookmark feature is a queue for downstream flashcard tooling, not a card renderer.
- Runtime corpus refresh endpoints.

## Architecture

```mermaid
flowchart TD
  webClient["japanese_dictionary_web"] -->|"HTTPS Basic auth"| apiGateway["API Gateway REST"]
  apiGateway -->|"Custom REQUEST authorizer"| authLambda["AuthHandler Lambda"]
  authLambda -->|"Read users"| secretsManager["Secrets Manager: japanese_dictionary_api"]
  apiGateway -->|"Lambda proxy integration"| searchLambda["SearchHandler Lambda"]
  apiGateway -->|"Lambda proxy integration"| createBookmarkLambda["CreateBookmarkHandler Lambda"]
  apiGateway -->|"Lambda proxy integration"| deleteBookmarkLambda["DeleteBookmarkHandler Lambda"]
  apiGateway -->|"Lambda proxy integration"| findBookmarksLambda["FindBookmarksHandler Lambda"]
  searchLambda -->|"3 parallel GSI queries + BatchGetItem"| dynamoTable["DynamoDB: japanese_dictionary"]
  createBookmarkLambda -->|"PutItem USER#user / BOOKMARK#seq"| dynamoTable
  deleteBookmarkLambda -->|"DeleteItem USER#user / BOOKMARK#seq"| dynamoTable
  findBookmarksLambda -->|"Query pk = USER#user begins_with BOOKMARK#"| dynamoTable
  operator["Operator (local AWS creds)"] -->|"BatchWriteItem"| dynamoTable
  zips["Yomitan zips: Jitendex / JPDB / Kanjium"] --> migration["migrations/000-rebuild-terms.py"]
  migration --> operator
```

### Primary workflow — search-as-you-type

```mermaid
sequenceDiagram
  participant User as User
  participant Web as japanese_dictionary_web
  participant Gateway as API Gateway
  participant Auth as AuthHandler
  participant Search as SearchHandler
  participant Dynamo as DynamoDB

  User->>Web: type "shin"
  Note right of Web: 250 ms debounce
  Web->>Gateway: GET /search?q=shin (Authorization: Basic)
  Gateway->>Auth: validate Authorization header
  Auth-->>Gateway: allow
  Gateway->>Search: invoke SearchHandler
  Search->>Search: NFC + trim, validate length, romaji-normalise
  par parallel GSI queries
    Search->>Dynamo: query gsi1 (EXPRESSION, begins_with "shin")
    Search->>Dynamo: query gsi2 (READING, begins_with "shin")
    Search->>Dynamo: query gsi3 (ROMAJI, begins_with "shin")
  end
  Dynamo-->>Search: candidate (sequence, frequency_rank) pairs
  Search->>Search: union, dedup, sort, take top 10
  Search->>Dynamo: BatchGetItem(top 10 sequences) on main table
  Dynamo-->>Search: full term records
  Search-->>Gateway: 200 { results: [...] }
  Gateway-->>Web: results payload
```

### Bookmark workflow

```mermaid
sequenceDiagram
  participant User as User
  participant Web as japanese_dictionary_web
  participant Gateway as API Gateway
  participant Find as FindBookmarksHandler
  participant Create as CreateBookmarkHandler
  participant Delete as DeleteBookmarkHandler
  participant Dynamo as DynamoDB

  Note over Web: on SPA mount (after login)
  Web->>Gateway: GET /bookmarks (Authorization: Basic)
  Gateway->>Find: invoke FindBookmarksHandler
  Find->>Dynamo: query pk = USER#alice, begins_with(sk, "BOOKMARK#")
  Dynamo-->>Find: bookmark rows
  Find-->>Web: 200 { sequences: [...] }

  Note over Web: user clicks the bookmark icon on a result
  Web->>Gateway: PUT /bookmarks/1316830 (Authorization: Basic)
  Gateway->>Create: invoke CreateBookmarkHandler
  Create->>Dynamo: putItem USER#alice / BOOKMARK#1316830 (idempotent)
  Dynamo-->>Create: write success
  Create-->>Web: 201 { sequence: 1316830, created_at: <epoch> }

  Note over Web: user clicks the filled icon again to undo
  Web->>Gateway: DELETE /bookmarks/1316830 (Authorization: Basic)
  Gateway->>Delete: invoke DeleteBookmarkHandler
  Delete->>Dynamo: deleteItem USER#alice / BOOKMARK#1316830 (idempotent)
  Dynamo-->>Delete: write success
  Delete-->>Web: 204 No Content
```

### Corpus refresh workflow

```mermaid
sequenceDiagram
  participant Operator as Operator
  participant Script as 000-rebuild-terms.py
  participant Local as Local Yomitan zips
  participant Dynamo as DynamoDB

  Operator->>Script: python3 migrations/000-rebuild-terms.py --execute
  Script->>Local: read jitendex / jpdb / kanjium zips
  Script->>Script: build ~210k term records in memory
  Script->>Dynamo: scan with begins_with(pk, "TERM#")
  Script->>Dynamo: BatchWriteItem (delete) existing TERM items
  Script->>Dynamo: BatchWriteItem (put) ~210k new records
  Script-->>Operator: summary (cleared, uploaded, elapsed, coverage %)
```

## Main technical decisions

- Use API Gateway + Lambda + DynamoDB to stay consistent with every other backend service in the repo and keep infrastructure lightweight.
- Use one DynamoDB table for both the read-shared term corpus and per-user bookmark rows. Term rows partition on `pk = TERM#<sequence>`, bookmark rows on `pk = USER#<user>`; the prefixes never collide so the existing migration script (which clears `begins_with(pk, "TERM#")`) leaves bookmarks untouched and bookmark writes leave the corpus untouched. Single-table is the repo default and avoids a second IAM grant + Terraform table.
- Use three single-shard GSIs (`gsi1` keyed by `EXPRESSION`, `gsi2` by `READING`, `gsi3` by `ROMAJI`) so prefix matching by each lookup dimension is one `query(begins_with)` call. Constant partition keys keep all data in a single partition (~600 MB, well under the 10 GB limit); first-character sharding is a forward-compatible additive change if scale ever demands it. Bookmark rows do not write any `gsi*` attributes and therefore do not appear in any GSI.
- Use a slim `INCLUDE [sequence, frequency_rank]` GSI projection (the covering-index pattern) so a 1-char common-prefix query fits in one ~150 KB DynamoDB page; the bulky `glossary_raw` is fetched only for the 10 winning records via `BatchGetItem` on the main table. This is a deliberate divergence from the repo default `Projection: ALL` (justified by the corpus being ~1000× larger than other services').
- Store `glossary_raw` as a JSON-serialised string attribute, not a DynamoDB map, to bypass the 32-level nesting cap and to simplify enhanced-client mapping. Average serialised size 2–8 KB, well under DynamoDB's 400 KB item limit.
- Always run all three GSI queries in parallel and union the results, rather than auto-detecting input character class to query a single GSI. The wasted RCUs on empty queries are negligible (~3 RCU each), the wall-clock is `max(q1, q2, q3)` rather than the sum, and the union-and-dedup approach handles mixed-script input (e.g., `食べ`) without character-class branching.
- Compute `reading_romaji` (Modified Hepburn with vowel-doubling) at ingest time and persist it; normalise incoming queries (kunrei / wapuro / macron) to the same form at request time before matching.
- Key bookmark rows on the term `sequence` (not a server-generated UUID) so `(user, sequence)` is the natural primary key and `PUT /bookmarks/{sequence}` is idempotent without any read-before-write or conditional expression — a single `PutItem` upserts the row and refreshes `created_at`. `DELETE /bookmarks/{sequence}` is symmetrically idempotent: a single `DeleteItem` that always responds `204`, with no existence check.
- Skip term-existence validation on bookmark write to keep the create path to a single `PutItem` round-trip. Dangling bookmarks (referencing a `sequence` no longer in the corpus after a Yomitan refresh) are tolerated; the listing endpoint returns the bare integer and the consumer simply won't render a button for a sequence it doesn't recognise.
- Return `GET /bookmarks` as a flat sequence list rather than hydrated term records. The single stated client need is a fast in-memory membership check ("is this result already bookmarked?"), which is O(1) over a sequence `Set<number>`. Hydrated bookmarks would couple this endpoint to `BatchGetItem` and inflate per-call cost; a future "my bookmarks" page can add a hydrated variant without breaking the existing contract.
- Sort the bookmark listing by `created_at` descending in-memory rather than encoding the timestamp into the sort key. Per-user bookmark counts are bounded (single-user app, hundreds at most), so the sort is cheap and the `(user, sequence)` uniqueness guarantee from the simpler sort key is more valuable than a server-side ordered scan.
- Make the migration script the only data-loading mechanism for term rows. No `POST /term` write API; `BatchWriteItem` direct from the operator's laptop, with a destructive clear-then-rebuild semantics keyed on `begins_with(pk, "TERM#")`. Matches the existing `migrations/NNN-*.py` pattern in `immersion_tracker_api/` and `event_calendar_api/`.

## Domain glossary

- **Term**: one canonical JMdict headword keyed by its `sequence` integer.
- **Sequence**: JMdict's stable per-headword integer identifier; survives upstream Jitendex revisions, used as the primary key.
- **Expression**: the canonical writing of a term (kanji or kana mixture). Indexed for prefix lookup on `gsi1`.
- **Reading**: the canonical kana-only reading of a term. Indexed for prefix lookup on `gsi2`.
- **Reading romaji**: Modified Hepburn (vowel-doubled, no macrons) computed from `reading` at ingest. Indexed for prefix lookup on `gsi3`.
- **Frequency rank**: JPDB-derived integer (lower = more common); NULL for the ~39% of terms not in JPDB's corpus.
- **Pitch**: kanjium-derived downstep position; `0` = heiban, `1` = atamadaka, `2..N-1` = nakadaka, `N` = odaka where `N` = mora count of `reading`. NULL for the ~59% of terms with no kanjium match or no valid pitch.
- **Glossary raw**: the verbatim Yomitan structured-content JSON tree, stored as a serialised string and rendered client-side.
- **Bookmark**: a per-user record flagging a `sequence` for future reference (typically downstream flashcard creation). Stored as one DynamoDB row per `(user, sequence)` pair with the time it was first added; there is no per-bookmark payload beyond the sequence and timestamp.

## Integration contracts

### External systems

- None at runtime. The Lambda handlers do not call any external APIs; the corpus is loaded once via the operator-run migration script and served thereafter from DynamoDB.

### Upstream data sources (consumed only by the migration script, not by Lambda)

- **Jitendex (`jitendex-yomitan.zip`)**: Yomitan-format dictionary providing canonical JMdict headwords plus structured-content glossary trees. The migration script extracts `term_bank_*.json` files, applies argmax-by-score per JMdict `sequence`, and discards redirects.
- **JPDB Frequency Kana (`JPDB_v2.2_Frequency_Kana_*.zip`)**: Yomitan-format frequency dictionary. Joined per `(expression, reading)`; the script takes `min(rank)` across both kanji-form and kana-form (`㋕`-flagged) ranks to surface kana-dominant verbs correctly.
- **Kanjium Pitch Accents (`kanjium_pitch_accents.zip`)**: Yomitan-format pitch dictionary. Joined per `(expression, reading)`; the script picks `pitches[0].position` falling through to subsequent entries if the first fails mora-count validation.

## API contracts

### Conventions

- Base URL: `https://api.japanese-dictionary.jordansimsmith.com`
- Auth: `Authorization: Basic <base64(user:password)>`
- Request and response JSON fields use `snake_case`.
- No path version segment.
- Non-2xx response shape:

```json
{
  "message": "validation error details"
}
```

### Endpoint summary

| Method   | Path                    | Purpose                                                    |
| -------- | ----------------------- | ---------------------------------------------------------- |
| `GET`    | `/search`               | prefix-match a query and return up to 10 ranked terms      |
| `GET`    | `/bookmarks`            | list every term sequence the calling user has bookmarked   |
| `PUT`    | `/bookmarks/{sequence}` | bookmark a term for the calling user (idempotent upsert)   |
| `DELETE` | `/bookmarks/{sequence}` | remove a bookmark for the calling user (idempotent delete) |

### `GET /search`

Query parameters:

| Name | Required | Type   | Notes                                                                                     |
| ---- | -------- | ------ | ----------------------------------------------------------------------------------------- |
| `q`  | yes      | string | URL-encoded user query. Empty allowed. Max 64 characters after URL-decoding + NFC + trim. |

Behaviour:

- Empty `q` → `200 { "results": [] }`. Used by the SPA's session-validation step at login, analogous to `GET /templates` in `packing_list_web`.
- Non-empty `q` → run the parallel three-GSI query, union, dedup by `sequence`, sort by exact-match-first (`q` equals `expression` OR `reading`, or normalised `qRomaji` equals `reading_romaji`) then `frequency_rank` ASC nulls last with `sequence` ASC tie-break, take top 10, `BatchGetItem` full records, return.
- `q` length > 64 → `400 {"message": "q too long"}`.

Example request:

```
GET /search?q=shin HTTP/1.1
Host: api.japanese-dictionary.jordansimsmith.com
Authorization: Basic <base64>
```

Example response `200`:

```json
{
  "results": [
    {
      "sequence": 1316830,
      "expression": "新橋",
      "reading": "しんばし",
      "reading_romaji": "shinbashi",
      "frequency_rank": 18472,
      "pitch": 0,
      "glossary_raw": { "tag": "div", "content": "..." }
    }
  ]
}
```

Field semantics:

- `sequence` — integer, JMdict ID, stable across upstream Jitendex refreshes.
- `expression` — string, canonical writing.
- `reading` — string, canonical reading (kana).
- `reading_romaji` — string, Modified Hepburn vowel-doubled form computed at ingest.
- `frequency_rank` — integer or `null`. Lower = more common. `null` when the term is not present in JPDB's corpus.
- `pitch` — integer or `null`. `0` = heiban; `1` = atamadaka; `2..N-1` = nakadaka; `N` = odaka where `N` = mora count of `reading`. `null` when no kanjium match or no valid pitch.
- `glossary_raw` — Yomitan structured-content JSON tree, passed through unchanged for client-side rendering.

Representative key failures:

| Status | Body                              | Cause                                         |
| ------ | --------------------------------- | --------------------------------------------- |
| `400`  | `{"message":"q too long"}`        | `q` length > 64 after URL-decode + NFC + trim |
| `401`  | `{"message":"<gateway message>"}` | Missing or invalid Basic auth                 |

### `PUT /bookmarks/{sequence}`

Path parameters:

| Name       | Required | Type    | Notes                                                                                      |
| ---------- | -------- | ------- | ------------------------------------------------------------------------------------------ |
| `sequence` | yes      | integer | JMdict sequence to bookmark. Must parse as a positive integer (≥ 1). No upper bound check. |

Behaviour:

- Idempotent upsert. Always responds `201` with the persisted bookmark record. Re-issuing the same `PUT` overwrites `created_at` to the current server time.
- The bookmarked `sequence` is not validated against the term corpus; dangling bookmarks are tolerated.
- The acting user is read from the request context (`Authorization: Basic`) and used as the partition key. A bookmark created by user `alice` is invisible to user `bob`.

Example request:

```
PUT /bookmarks/1316830 HTTP/1.1
Host: api.japanese-dictionary.jordansimsmith.com
Authorization: Basic <base64>
```

Example response `201`:

```json
{
  "sequence": 1316830,
  "created_at": 1731974400
}
```

Field semantics:

- `sequence` — integer, the bookmarked term's JMdict sequence (echo of the path parameter).
- `created_at` — integer, epoch seconds at which this bookmark was last written. Refreshed by every `PUT`.

Representative key failures:

| Status | Body                                                | Cause                                    |
| ------ | --------------------------------------------------- | ---------------------------------------- |
| `400`  | `{"message":"sequence must be a positive integer"}` | `{sequence}` cannot be parsed, or is ≤ 0 |
| `401`  | `{"message":"<gateway message>"}`                   | Missing or invalid Basic auth            |

### `DELETE /bookmarks/{sequence}`

Path parameters:

| Name       | Required | Type    | Notes                                                                                         |
| ---------- | -------- | ------- | --------------------------------------------------------------------------------------------- |
| `sequence` | yes      | integer | JMdict sequence to un-bookmark. Must parse as a positive integer (≥ 1). No upper bound check. |

Behaviour:

- Idempotent delete. Always responds `204 No Content` with an empty body, whether or not a bookmark row existed for `(user, sequence)`. A single `DeleteItem` round-trip; no read-before-write, no conditional expression.
- The acting user is read from the request context (`Authorization: Basic`) and used as the partition key. A user can only delete their own bookmarks; `bob` calling `DELETE /bookmarks/1316830` never affects `alice`'s rows.
- The deleted `sequence` is not validated against the term corpus.

Example request:

```
DELETE /bookmarks/1316830 HTTP/1.1
Host: api.japanese-dictionary.jordansimsmith.com
Authorization: Basic <base64>
```

Example response `204`: empty body.

Representative key failures:

| Status | Body                                                | Cause                                    |
| ------ | --------------------------------------------------- | ---------------------------------------- |
| `400`  | `{"message":"sequence must be a positive integer"}` | `{sequence}` cannot be parsed, or is ≤ 0 |
| `401`  | `{"message":"<gateway message>"}`                   | Missing or invalid Basic auth            |

### `GET /bookmarks`

Query parameters: none.

Behaviour:

- Lists every bookmark row owned by the calling user via a single primary-key `Query` (`pk = USER#<user> AND begins_with(sk, "BOOKMARK#")`).
- Returns just the bare `sequence` integers in `created_at` descending order (most recent first), so a client can build a `Set<number>` in O(n) for membership checks against rendered search results.
- Empty list when the user has no bookmarks; never `404`.

Example request:

```
GET /bookmarks HTTP/1.1
Host: api.japanese-dictionary.jordansimsmith.com
Authorization: Basic <base64>
```

Example response `200`:

```json
{
  "sequences": [1316830, 1362050, 1591600]
}
```

Representative key failures:

| Status | Body                              | Cause                         |
| ------ | --------------------------------- | ----------------------------- |
| `401`  | `{"message":"<gateway message>"}` | Missing or invalid Basic auth |

### Validation rules

- `q` is decoded once from the URL, NFC-normalised, and trimmed of leading/trailing ASCII whitespace before length and character checks.
- Length > 64 (after NFC + trim) is rejected with `400`.
- Empty `q` is allowed and short-circuits to `{"results": []}` without touching DynamoDB.
- No character whitelisting beyond NFC; users may paste arbitrary Unicode (it just won't match anything if it isn't a valid prefix).
- `{sequence}` on `PUT /bookmarks/{sequence}` and `DELETE /bookmarks/{sequence}` must parse as a positive `long` (regex `[1-9][0-9]*` after stripping URL escapes). Anything else returns `400`.

### Romaji query normalisation

Incoming `q` is run through `RomajiNormaliser` before matching `gsi3`. Steps in fixed order:

1. Lowercase.
2. Replace each macron / circumflex with the doubled vowel (`ō → ou`, `ô → ou`, `ā → aa`, `ē → ee`, `ī → ii`, `ū → uu`).
3. Replace kunrei / wapuro digraphs:
   - `sy[aiueo]` → `sh[aiueo]` (`sya → sha`, etc.)
   - `ty[aiueo]` → `ch[aiueo]`
   - `zy[aiueo]` → `j[aiueo]`
   - Standalone consonants: `si → shi`, `ti → chi`, `tu → tsu`, `hu → fu`, `zi → ji`, `di → ji`, `du → zu`.
4. Strip apostrophes (`n'a → na`).

Idempotent: running the normaliser on already-normalised input is a no-op.

## Data and storage contracts

### DynamoDB model

- **Table name**: `japanese_dictionary`
- **Billing**: `PAY_PER_REQUEST`.
- **Primary key**:
  - `pk`: string. `TERM#<sequence>` for corpus rows, `USER#<user>` for bookmark rows.
  - `sk`: string. `TERM#<sequence>` (identical to `pk`) for corpus rows, `BOOKMARK#<sequence>` for bookmark rows.
- **Item types**:
  - `TERM#<sequence>` — the read-shared term corpus row. Carries the full term record (expression, reading, romaji, frequency, pitch, glossary).
  - `BOOKMARK#<sequence>` — a per-user bookmark row. Carries `user`, `sequence`, and `created_at` (epoch seconds). No GSI projections.
- **Point-in-time recovery**: enabled.
- **Deletion protection**: enabled.

### Global secondary indexes

| GSI    | Partition key (S)       | Sort key (S)                | Projection                           |
| ------ | ----------------------- | --------------------------- | ------------------------------------ |
| `gsi1` | `gsi1pk = "EXPRESSION"` | `gsi1sk = <expression>`     | `INCLUDE [sequence, frequency_rank]` |
| `gsi2` | `gsi2pk = "READING"`    | `gsi2sk = <reading>`        | `INCLUDE [sequence, frequency_rank]` |
| `gsi3` | `gsi3pk = "ROMAJI"`     | `gsi3sk = <reading_romaji>` | `INCLUDE [sequence, frequency_rank]` |

Each partition key is a constant string (single-shard design); each sort key is the raw indexed value with no decoration. DynamoDB GSIs accept duplicate `(pk, sk)` tuples — homophones (e.g., `こころ` for 心 / 真 / 衷) coexist as multiple `gsi2` rows with identical `gsi2sk`. The Lambda dedups by `sequence` after unioning the three GSI query results. `begins_with(gsi1sk, "新")` matches `"新橋"`, `"新しい"`, etc. with natural prefix semantics.

The slim `INCLUDE` projection is exactly what the handler needs to dedup, sort with NULLs last, and pick the top 10 before fetching full records — `glossary_raw` and other text fields stay off the GSIs to keep per-row size at ~100 bytes.

### Representative records

`TERM#` row (corpus):

```json
{
  "pk": "TERM#1316830",
  "sk": "TERM#1316830",
  "sequence": 1316830,
  "expression": "新橋",
  "reading": "しんばし",
  "reading_romaji": "shinbashi",
  "frequency_rank": 18472,
  "pitch": 0,
  "glossary_raw": "{\"tag\":\"div\",\"content\":\"...\"}",
  "gsi1pk": "EXPRESSION",
  "gsi1sk": "新橋",
  "gsi2pk": "READING",
  "gsi2sk": "しんばし",
  "gsi3pk": "ROMAJI",
  "gsi3sk": "shinbashi"
}
```

`BOOKMARK#` row (per-user bookmark):

```json
{
  "pk": "USER#alice",
  "sk": "BOOKMARK#1316830",
  "user": "alice",
  "sequence": 1316830,
  "created_at": 1731974400
}
```

Required attributes on every `TERM` item: `pk`, `sk`, `sequence`, `expression`, `reading`, `reading_romaji`, `glossary_raw`, `gsi1pk`, `gsi1sk`, `gsi2pk`, `gsi2sk`, `gsi3pk`, `gsi3sk`.

Optional attributes on `TERM` items: `frequency_rank`, `pitch` (integer or absent; absent attribute means NULL — no JPDB / kanjium match).

Required attributes on every `BOOKMARK` item: `pk`, `sk`, `user`, `sequence`, `created_at`. Bookmark rows do not write any `gsi*` attributes and therefore do not surface on any GSI.

### Access patterns

| Use case                                     | Operation                                                                          | Notes                                                                                                                       |
| -------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Prefix search by expression (kanji + mixed)  | `query(gsi1)` with `pk = "EXPRESSION" AND begins_with(sk, q)`                      | returns `(sequence, frequency_rank)` pairs                                                                                  |
| Prefix search by reading (kana)              | `query(gsi2)` with `pk = "READING" AND begins_with(sk, q)`                         | same shape                                                                                                                  |
| Prefix search by romaji (post-normalisation) | `query(gsi3)` with `pk = "ROMAJI" AND begins_with(sk, qNormalised)`                | same shape                                                                                                                  |
| Hydrate top 10 with full glossary            | `BatchGetItem` on main table                                                       | `pk = sk = "TERM#<sequence>"` for each of up to 10 sequences                                                                |
| Create or refresh a user bookmark            | `PutItem` `pk = USER#<user>`, `sk = BOOKMARK#<sequence>` with current `created_at` | idempotent; no condition expression                                                                                         |
| Remove a user bookmark                       | `DeleteItem` `pk = USER#<user>`, `sk = BOOKMARK#<sequence>`                        | idempotent; no condition expression; succeeds whether or not the row existed                                                |
| List a user's bookmarks                      | `Query` main table with `pk = USER#<user> AND begins_with(sk, "BOOKMARK#")`        | returned rows sorted in-memory by `created_at` desc                                                                         |
| Migration clear                              | `scan` with `FilterExpression="begins_with(pk, :p)"`, `BatchWriteItem` deletes     | full table scan; chunks of 25 deletes; retry on `UnprocessedItems`. Filter prefix is `TERM#` so bookmarks are not affected. |
| Migration upload                             | `BatchWriteItem` puts                                                              | chunks of 25; retry on `UnprocessedItems`; adaptive sleep on throttling                                                     |

## Behavioral invariants and time semantics

- The dictionary corpus is shared, read-only data; the search path never references the calling user. Auth gates access only.
- `q` is NFC-normalised and trimmed before length validation and matching.
- Empty `q` deterministically returns `{"results": []}` without touching DynamoDB.
- Non-empty `q` always runs all three GSI queries in parallel; the result is the union deduplicated by `sequence`.
- Result ordering: exact matches first (`q` equals `expression` OR `reading`, or normalised `qRomaji` equals `reading_romaji`), then `frequency_rank` ascending with NULLs last, then `sequence` ascending as final tie-break. Stable across requests for a fixed corpus.
- Top-10 cap is hard; clients cannot request more.
- Romaji normalisation is idempotent: `normalise(normalise(x)) == normalise(x)`.
- Bookmarks are partitioned per user; a `BOOKMARK#<sequence>` row is only ever visible to the user whose `pk` matches.
- `PUT /bookmarks/{sequence}` is idempotent in `(user, sequence)`. Repeated calls leave the same single row in place but refresh `created_at` to the current server time.
- `DELETE /bookmarks/{sequence}` is idempotent in `(user, sequence)`. The handler always responds `204 No Content` and only ever deletes rows whose `pk` matches the calling user; rows for other users are never touched, and `TERM#` corpus rows are unreachable from this code path.
- `GET /bookmarks` returns sequences in `created_at` descending order. Order is deterministic for a fixed write history; the in-memory sort uses `sequence` ascending as a tie-break for rows with identical timestamps.
- Bookmarks survive corpus rebuilds. The migration script's clear step is keyed on `begins_with(pk, "TERM#")` so `USER#...` / `BOOKMARK#...` rows are not affected.
- Each migration run fully replaces the corpus; there is no incremental upsert path. The act of running the script is the version bump (no `corpus_version` attribute).

## Source of truth

| Entity                 | Authoritative source                                          | Notes                                                                             |
| ---------------------- | ------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| User identity          | Basic auth username                                           | parsed from `Authorization` header in authorizer and request context              |
| Credential set         | Secrets Manager secret `japanese_dictionary_api`              | read by `AuthHandler`; never logged                                               |
| Term records           | DynamoDB `TERM#<sequence>` items                              | populated exclusively by `migrations/000-rebuild-terms.py`                        |
| Frequency rank values  | JPDB Frequency Kana zip (consumed at ingest)                  | persisted on each term as `frequency_rank`; `null` when not in JPDB               |
| Pitch values           | Kanjium Pitch Accents zip (consumed at ingest)                | persisted on each term as `pitch`; `null` when no kanjium match or no valid pitch |
| Glossary content       | Jitendex zip (consumed at ingest)                             | persisted verbatim as `glossary_raw` JSON string                                  |
| Romaji form            | Computed from `reading` at ingest by the Python script        | persisted as `reading_romaji`                                                     |
| Search result ordering | Lambda code (`SearchHandler`)                                 | derived from persisted `frequency_rank` and `sequence`                            |
| Bookmark records       | DynamoDB `BOOKMARK#<sequence>` items under `pk = USER#<user>` | written by `CreateBookmarkHandler`, removed by `DeleteBookmarkHandler`; per-user  |
| Bookmark `created_at`  | `Clock` injected into `CreateBookmarkHandler`                 | epoch seconds at the moment of the most recent successful `PUT`                   |
| Bookmark listing order | Lambda code (`FindBookmarksHandler`)                          | in-memory sort by `created_at` desc, `sequence` asc as tie-break                  |

## Security and privacy

- API Gateway custom REQUEST authorizer (`AuthHandler`) enforces HTTP Basic authentication before any handler executes. `OPTIONS` preflight is `NONE` (MOCK integration with CORS headers).
- Credentials live in AWS Secrets Manager (`japanese_dictionary_api`); read by `AuthHandler` at runtime via least-privilege IAM. Never logged.
- The migration script uses the operator's local AWS credentials (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`); no in-AWS credential is involved in the data load.
- The term corpus is shared and the Basic auth gate exists as access control. Bookmark rows are partitioned by Basic-auth username; bookmark queries always pin `pk = USER#<authenticated user>` so a user cannot read or write another user's bookmarks.
- No PII; the corpus is publicly available Japanese language data and the bookmark records carry only `(user, sequence, created_at)` triples.
- Transport is HTTPS via API Gateway custom domain `api.japanese-dictionary.jordansimsmith.com`.
- CORS allowed origin is `https://japanese-dictionary.jordansimsmith.com` only. Allowed methods include `GET`, `PUT`, and `OPTIONS`.
- CloudWatch logs include `q` query strings (the words the user looked up) and bookmarked sequences. Acceptable at personal scale; explicitly noted.

## Configuration and secrets reference

### Environment variables

No service-specific environment variables are consumed by handlers in current scope. Behaviour is configured via code constants and Terraform-managed resources (table name, secret name, CORS origin).

| Name     | Required | Purpose                                                                  | Default behaviour                                           |
| -------- | -------- | ------------------------------------------------------------------------ | ----------------------------------------------------------- |
| `(none)` | n/a      | configuration is via code constants and Terraform-managed resources only | table name, secret name, and CORS origin come from defaults |

### Secret shape

Expected JSON payload for the `japanese_dictionary_api` Secrets Manager secret:

```json
{
  "users": [
    {
      "user": "alice",
      "password": "strong-password"
    }
  ]
}
```

No third-party API keys (the dictionary corpus is locally produced — no runtime calls to external APIs).

## Performance envelope

- Lambda sizing: `AuthHandler` runs at `512 MB` memory / `10 s` timeout (repo default); `SearchHandler` at `1024 MB` memory / `5 s` timeout (bumped above default for parallel SDK calls + Jackson tree parsing); `CreateBookmarkHandler`, `DeleteBookmarkHandler`, and `FindBookmarksHandler` run at the repo default `512 MB` / `10 s`.
- DynamoDB: `PAY_PER_REQUEST` billing. Three GSIs with slim `INCLUDE` projection. Estimated table size: ~600 MB main + ~50 MB GSI projections = ~650 MB. Bookmark rows add a negligible amount (≪ 1 KB per row, single-user app).
- Read path latency budget (warm path):
  - 30–50 ms HTTPS round-trip (Auckland → ap-southeast-2)
  - ~30 ms for the parallel three-GSI query (max of three)
  - ~30 ms for the `BatchGetItem` of top 10
  - ~10 ms for JSON parsing of `glossary_raw` × 10
  - **Total keystroke-to-pixel ~400 ms** (250 ms debounce dominates).
- Bookmark path latency budget (warm path):
  - `PUT /bookmarks/{sequence}`: ~30 ms HTTPS RTT + ~10 ms `PutItem` ≈ ~50 ms.
  - `DELETE /bookmarks/{sequence}`: ~30 ms HTTPS RTT + ~10 ms `DeleteItem` ≈ ~50 ms.
  - `GET /bookmarks`: ~30 ms HTTPS RTT + one primary-key `Query` (≤ 1 page at expected scale) ≈ ~50 ms total.
- Cold start adds ~500 ms one-off.
- Migration write path: ~1000 WCU sustained = ~1 MB/s. Full ~210k-item reload completes in ~10–15 minutes including retries.
- No formal latency SLO at v1; sized for personal workload only.

## Testing and quality gates

- Unit tests cover `RomajiNormaliser` per-rule cases plus idempotency, `TermItem` key formatting, and `BookmarkItem` key formatting. Authorizer logic (Basic header parsing, allow/deny + Base64 edge cases) is covered by `lib/auth`'s `RequestAuthorizerTest`.
- Integration tests run `SearchHandler` against DynamoDB Testcontainers with hand-picked seed terms covering: kanji-only, kana-only, kanji+reading with non-NULL `frequency_rank`, term with non-NULL `pitch`, term whose glossary references an image (placeholder rendering case). Asserts prefix match across all three dimensions, exact-match-first ordering followed by frequency-asc with NULLs last (including a defensive case proving a null-frequency exact match displaces high-frequency prefix-only matches from the top-10), top-10 cap, dedup when a term is reachable via multiple GSIs, kunrei romaji normalisation, and validation paths (`q` too long, missing/empty/whitespace `q`, NFC + trim before length check).
- Integration tests for `CreateBookmarkHandler` cover: happy-path creation writes the row at `clock.now()`; second `PUT` for the same `(user, sequence)` is idempotent and refreshes `created_at`; non-integer / non-positive `{sequence}` returns `400`; bookmarks created by one user are invisible to a different user's listing; bookmark writes never touch `TERM#` rows.
- Integration tests for `DeleteBookmarkHandler` cover: happy-path delete removes the row and returns `204`; second `DELETE` for the same `(user, sequence)` is idempotent and still returns `204`; non-integer / non-positive `{sequence}` returns `400`; deletes never affect another user's bookmarks; deletes never touch `TERM#` rows.
- Integration tests for `FindBookmarksHandler` cover: empty list when the user has no bookmarks; only the calling user's rows are returned; sort order is `created_at` desc with `sequence` ascending tie-break; pre-seeded `TERM#` rows in the same table are ignored.
- E2E tests use LocalStack (API Gateway + Lambda + DynamoDB + Secrets Manager) and exercise the full HTTP flow: `GET /search?q=新` returns the seed fixture, `GET /search?q=` returns `[]`, missing `Authorization` returns `401`, `q` length > 64 returns `400`, `PUT /bookmarks/{sequence}` creates a bookmark and `GET /bookmarks` lists it, `DELETE /bookmarks/{sequence}` removes the bookmark and is idempotent on a non-existent sequence. Deterministic; no outbound internet; no real AWS credentials.
- Required service checks:
  - `bazel build //japanese_dictionary_api:all`
  - `bazel test //japanese_dictionary_api:all`
- Repository-level post-change checks (per `AGENTS.md`):
  - `bazel mod tidy`
  - `bazel run //:format`

## Local development and smoke checks

- Run focused suites:
  - `bazel test //japanese_dictionary_api:unit-tests`
  - `bazel test //japanese_dictionary_api:integration-tests`
  - `bazel test //japanese_dictionary_api:e2e-tests`
- Run the migration script in dry-run mode (no writes):
  - `python3 japanese_dictionary_api/migrations/000-rebuild-terms.py --jitendex <path> --jpdb <path> --kanjium <path>`
  - Prints the count summary and frequency / pitch coverage without touching DynamoDB.
- Run the migration script in destructive mode:
  - Same command with `--execute` appended.
  - Requires `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` exported in the shell.
  - Clears all `TERM#...` items, then uploads ~210k fresh records. ~10–15 minutes.
- Minimal smoke flow against the deployed sandbox:
  1. `GET /search?q=新` returns one or more `SearchResult`s with `expression` starting with `新`.
  2. `GET /search?q=しん` returns kana-prefix matches.
  3. `GET /search?q=shin` returns romaji-prefix matches.
  4. `GET /search?q=` returns `{"results": []}`.
  5. Missing `Authorization` header returns `401` with `WWW-Authenticate: Basic`.
  6. `PUT /bookmarks/1316830` returns `201 { "sequence": 1316830, "created_at": ... }`.
  7. `GET /bookmarks` returns `{ "sequences": [1316830, ...] }` containing the sequence just written.
  8. `DELETE /bookmarks/1316830` returns `204` with an empty body and the sequence no longer appears in a subsequent `GET /bookmarks`.

## End-to-end scenarios

### Scenario 1: kanji prefix lookup

1. The user types `新` into `japanese_dictionary_web`.
2. After 250 ms of no further keystrokes, the SPA sends `GET /search?q=%E6%96%B0`.
3. API Gateway authorises the request via `AuthHandler`.
4. `SearchHandler` runs three parallel GSI queries, finds matches in `gsi1` (`EXPRESSION` partition), unions and dedups, sorts exact matches first then by `frequency_rank` ASC nulls last.
5. `SearchHandler` `BatchGetItem`s the top 10 sequences and returns full term records including `glossary_raw`.
6. The SPA renders 10 expanded entries.

### Scenario 2: romaji prefix lookup with normalisation

1. The user types `tu` (kunrei-shiki for `つ`) into the SPA.
2. The SPA sends `GET /search?q=tu`.
3. `SearchHandler` runs `RomajiNormaliser` over `q`, producing `tsu`.
4. The three GSI queries fire; `gsi3` (`ROMAJI`) finds prefix matches against `tsu`.
5. Top-10 sorted by frequency are returned.

### Scenario 3: bookmark a term during a lookup

1. The user types `新橋` in `japanese_dictionary_web` and the search returns the matching entry.
2. The user clicks the bookmark icon next to the result. The SPA optimistically marks the entry as bookmarked and sends `PUT /bookmarks/1316830`.
3. API Gateway authorises the request via `AuthHandler`, then routes to `CreateBookmarkHandler`.
4. The handler reads the username from the request context, computes `created_at = clock.now()`, and `PutItem`s a single `pk = USER#alice / sk = BOOKMARK#1316830` row.
5. Handler responds `201 { "sequence": 1316830, "created_at": <epoch> }`.
6. On the next page load, the SPA's mount-time `GET /bookmarks` returns `{ "sequences": [1316830, ...] }`, and the bookmark icon for that result is rendered as already bookmarked.

### Scenario 4: un-bookmark a term

1. The user is on the search results page with `新橋` rendered in the bookmarked (filled icon) state.
2. The user clicks the filled bookmark icon to undo. The SPA optimistically clears the local bookmark and sends `DELETE /bookmarks/1316830`.
3. API Gateway authorises the request via `AuthHandler`, then routes to `DeleteBookmarkHandler`.
4. The handler reads the username from the request context and `DeleteItem`s the `pk = USER#alice / sk = BOOKMARK#1316830` row.
5. Handler responds `204 No Content`.
6. On the next page load, the SPA's mount-time `GET /bookmarks` no longer includes `1316830`, and the `新橋` entry renders with the empty bookmark icon.

### Scenario 5: refresh the corpus from new upstream Yomitan zips

1. The operator downloads fresh `jitendex-yomitan.zip`, `JPDB_*.zip`, and `kanjium_pitch_accents.zip` to the local machine.
2. The operator exports `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.
3. Operator runs `python3 japanese_dictionary_api/migrations/000-rebuild-terms.py --jitendex ... --jpdb ... --kanjium ...` (no `--execute`) to dry-run.
4. Reviews the printed summary (item counts, coverage percentages) for sanity.
5. Re-runs with `--execute`. The script clears all `TERM#...` items, then uploads ~210k fresh records.
6. The next `GET /search` call hits the freshly populated table.
