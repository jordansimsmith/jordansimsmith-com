# Japanese dictionary Anki add-on

The Japanese dictionary Anki desktop add-on imports terms the user has bookmarked through `japanese_dictionary_api` into the user's Anki collection as `Animecards`-format notes.

## Overview

- **Service type**: Anki desktop add-on (Python 3.9+ inside Anki's PyQt6 runtime)
- **Interface**: Tools menu action `Import Japanese dictionary bookmarks`
- **Distribution**: Bazel-built `.ankiaddon` zip (`bazel build //japanese_dictionary_api:addon-package`)
- **Auth**: HTTP Basic via `JAPANESE_DICTIONARY_USER` / `JAPANESE_DICTIONARY_PASSWORD` environment variables, same credentials as the SPA
- **API surface consumed**: `GET /bookmarks?include=term`, `DELETE /bookmarks/{sequence}` on `japanese_dictionary_api`; jpod101 audio CDN (`https://assets.languagepod101.com/...` redirecting to `https://cdn.innovativelanguage.com/...`)
- **Anki API surface consumed**: `aqt.mw`, `aqt.gui_hooks.main_window_did_init`, `aqt.qt.QAction`, `aqt.utils.tooltip`, `aqt.utils.askUser`, `aqt.operations.QueryOp`, plus `mw.col.find_notes`, `mw.col.add_note`, `mw.col.media.write_data`, `mw.col.decks.all_names_and_ids`, `mw.col.models.all_names_and_ids`

## User stories

- As a learner who bookmarked terms on my phone, I want a single menu action on Anki desktop that pulls those bookmarks, builds matching cards, and adds them to my deck, so that I don't have to manually re-look-up each term.
- As an Animecards user, I want imported cards to populate the same `Word`, `Reading`, `Glossary`, `Audio`, `Graph` fields my Yomitan + AnkiConnect workflow produces, so that the new cards look indistinguishable from the ones I've made before.
- As a learner reviewing a batch of bookmarks before commit, I want duplicates auto-detected against my current deck and unchecked by default, so that I don't accidentally add the same word twice.
- As a learner cleaning up the queue, I want unchecked rows on commit to also be unbookmarked at the API, so that rejected items don't reappear in every future dialog.
- As an operator, I want each successful import to clear the corresponding bookmark at the API automatically, so that the SPA on my phone reflects the up-to-date queue without manual cleanup.

## Features and scope boundaries

### In scope

- Register a single Tools menu action labelled `Import Japanese dictionary bookmarks` on Anki startup via `gui_hooks.main_window_did_init`.
- Fetch bookmarks with hydrated term records (`GET /bookmarks?include=term`).
- Derive `kana_form` per bookmark by walking `glossary_raw` (looking for the Jitendex `"uk"` misc-info marker), falling back to `expression == reading`.
- Build the prospective note in memory: `Word` (kana form picks `reading`, otherwise `expression`), `Reading`, `Glossary` (Python port of Yomitan's `structured-content-generator.js`, inline styles, no bundled CSS), `Graph` (Python port of Yomitan's `pronunciation-generator.js` simple-style SVG, inline styles), `Audio` (jpod101 URL → MP3 download → `[sound:<filename>]`).
- Open one modal dialog with the bookmark list, checkboxes (all checked except duplicates), deck and note-type pickers, footer warning + drop-count when any rows are unchecked, and a `Cancel` / commit button pair.
- On commit, run a two-step background pipeline split by an explicit user confirmation:
  - **Import** `QueryOp`: parallel audio download + sequential `mw.col.media.write_data`, then sequential `mw.col.add_note`.
  - Show a confirmation dialog summarising imports / dropped / failed counts; the user picks `Yes` to clear or `No` to keep bookmarks queued for a re-run.
  - **Cleanup** `QueryOp` (only on `Yes`): sequential `DELETE /bookmarks/{sequence}` for both successful imports and explicitly-dropped rows.
- Tag every imported note with `japanese_dictionary`.
- Detect duplicates locally with `mw.col.find_notes(f'deck:"<deck>" "<duplicate_field>:<word>"')` at dialog-open time only.
- Surface a summary tooltip on completion (imported / imported-without-audio / dropped / duplicates skipped / failed).
- Render glossary image nodes as inline placeholder text (`[image: <description-or-path>]`), matching the SPA.
- Rewrite internal glossary links (`href` starting with `?`) to `https://japanese-dictionary.jordansimsmith.com/search<href>` so clicking from Anki opens the SPA in a browser.

### Out of scope

- Automatic / scheduled / sync-lifecycle imports. Manual menu action only.
- Editing of any field inside the dialog before commit. The only controls are the per-row checkbox, deck picker, and note-type picker.
- Per-row sentence input. The data model has no sentence field; users add sentences in Anki post-import if desired.
- Importing into a different note type per row. Note type is one global picker.
- Audio sources other than jpod101. No fallback to Jisho / TTS / custom URL in v1.
- Image-binary download or hosting; image nodes render as `[image: ...]` placeholders.
- An `imported` status flag server-side or an audit log. Successful import = bookmark deleted = forgotten by the API.
- AnkiMobile or AnkiWeb support. Desktop Anki only.
- A `clear all` / `remove from queue` affordance separate from import.
- Bundled CSS. Inline styles in emitted HTML and SVG carry all visual rendering.
- Server-side `kana_form` storage; derived client-side.

## Architecture

```mermaid
flowchart TD
  user[User] -->|Tools menu click| addon[Anki desktop add-on]
  addon -->|GET /bookmarks?include=term| api[japanese_dictionary_api]
  api --> dynamo[(DynamoDB japanese_dictionary)]
  addon -->|GET audiomp3.php| jpod101[(jpod101 audio CDN)]
  addon -->|mw.col.find_notes / add_note| ankiCol[(Anki collection)]
  addon -->|mw.col.media.write_data| ankiMedia[(Anki media store)]
  addon -->|DELETE /bookmarks/{seq}| api
```

### Primary workflow

```mermaid
sequenceDiagram
  participant U as User
  participant A as Add-on (Anki desktop)
  participant API as japanese_dictionary_api
  participant J as jpod101
  participant C as mw.col

  U->>A: Tools > Import Japanese dictionary bookmarks
  Note over A: QueryOp: fetch + duplicate detection
  A->>API: GET /bookmarks?include=term
  API-->>A: { bookmarks: [{ sequence, created_at, expression, ..., glossary_raw }] }
  loop per bookmark
    A->>A: derive kana_form, build Word/Reading/Glossary/Graph
    A->>C: find_notes(deck:"<deck>" "<duplicate_field>:<word>") -> dupe?
  end
  A-->>U: open dialog (count, dupe pills, deck/model picker, checkboxes)
  U->>A: review checkboxes, click commit
  Note over A,C: QueryOp: import (audio + add_note)
  par per selected row
    A->>J: GET audio mp3 (User-Agent: browser-style)
    J-->>A: bytes (or 403/404/timeout/jpod101 invalid-audio stub)
  end
  loop per successful download
    A->>C: media.write_data(filename, bytes)
  end
  loop per selected row
    A->>C: add_note(note, deck_id, tags=["japanese_dictionary"])
  end
  A-->>U: confirmation dialog (imported / dropped / failed) -> clear server bookmarks?
  alt user confirms
    Note over A,API: QueryOp: cleanup
    loop per (successful | dropped) row
      A->>API: DELETE /bookmarks/{sequence}
    end
    A-->>U: summary tooltip
  else user keeps bookmarks
    A-->>U: tooltip noting bookmarks were kept for re-run
  end
```

## Main technical decisions

- **Manual menu trigger only**: matches the existing `Run backup now` action in `anki_backup_api/addon/anki_backup.py`. No automatic polling, no sync-lifecycle hook. Single explicit user action keeps state simple and predictable.
- **Single dialog, no per-row preview**: the dialog renders one row per bookmark with Word / Reading / freq / duplicate pill. No per-row expansion. Rationale: the user already reviewed each term when bookmarking on phone; the dialog's job is to confirm and let them drop entries they no longer want.
- **Default-checked except duplicates**: every row is pre-checked unless `mw.col.find_notes` flags it as already present in the configured deck. The user can flip any checkbox manually.
- **Drop-on-commit semantics**: unchecked rows are unbookmarked at the API on commit (just like imported rows). Treats the dialog as a complete review pass. Cancel always preserves all bookmarks. Signposted in three ways: commit button label includes drop count when non-zero (`"Import 7 (drop 3)"`), footer warning label visible when any rows are unchecked, summary tooltip reports drops separately from imports.
- **Confirm-before-delete checkpoint**: between the import and cleanup `QueryOp`s the addon shows the user the import summary and asks `Delete N bookmarks from the server?`. The user picks `Yes` (run cleanup) or `No` (keep bookmarks queued for a re-run). Notes are still added to Anki regardless of the answer; `No` only suppresses the server-side `DELETE /bookmarks/{sequence}` calls. Rationale: a single dropped row or wrong deck pick used to be silently destructive because the bookmark was wiped before the user could see the imported note; a one-click checkpoint catches it.
- **Client-side `kana_form` derivation**: walk `glossary_raw` for the Jitendex `"uk"` misc-info marker; fall back to `expression == reading`. No new API field, no migration. If the heuristic ever proves fragile across Jitendex versions, we can promote `kana_form` to a `TermItem` attribute via a one-line migration update without changing the add-on's public seam.
- **Faithful Yomitan port for glossary HTML and pitch graph SVG**: matches the visual style of cards the user already makes with Yomitan + AnkiConnect. Inline styles in emitted HTML/SVG so no CSS bundling is required. The pitch-graph SVG carries an inline `style="display:inline-block;vertical-align:middle;height:1.5em;"` matching Yomitan's `display-pronunciation.css` so the graph sits at one line of text height instead of stretching to its intrinsic 100px viewBox.
- **jpod101 only for audio, with stub-detection**: deterministic URL `https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=<expression>&kana=<reading>` (kanji omitted when `kana_form`). Per `_getInfoJpod101` in Yomitan's `audio-downloader.js`. Two non-obvious behaviours of the upstream that the addon mirrors from Yomitan:
  - jpod101 redirects matched terms to `cdn.innovativelanguage.com` on CloudFront, which rejects the default `python-requests/X.Y.Z` User-Agent with `403`. The downloader sends a browser-style `User-Agent` to bypass this.
  - jpod101 returns `200` + `audio/mpeg` for terms with no recording, but the body is a fixed "audio not available" stub. The downloader filters this by SHA-256 (`ae6398b5...17098906`) so the stub never lands in the user's media store.
  - Audio download failure (network error, non-2xx after the redirect, non-`audio/*` content type, or stub) is degraded gracefully — the note is imported without the Audio field populated and counted as `imported_without_audio`.
- **Non-blocking pipeline via chained `QueryOp`s**: every step that touches the network or `mw.col` runs in a background `QueryOp` so the Anki UI stays responsive. The chain is fetch+dupe → dialog (foreground) → audio+add_note → confirmation dialog (foreground) → cleanup deletes. Per-step parallelism: audio download is parallelised via `ThreadPoolExecutor`; `mw.col.media.write_data` and `mw.col.add_note` stay sequential because the collection is not thread-safe; `DELETE /bookmarks/{sequence}` stays sequential because the API is cheap and ordering keeps log output legible.
- **Tests via pytest, py_test target**: pure-function ports (`structured_content`, `pitch_graph`, `kana_form`, `audio`) have unit tests using `@pytest.mark.parametrize` for table-driven cases. UI (dialog) and integration (entry point, HTTP, file I/O) code is exercised by manual smoke in Anki rather than automated tests.

## Configuration and secrets reference

### Environment variables

| Name                           | Required | Purpose                                                         | Default                                              |
| ------------------------------ | -------- | --------------------------------------------------------------- | ---------------------------------------------------- |
| `JAPANESE_DICTIONARY_USER`     | yes      | HTTP Basic auth username for `japanese_dictionary_api`          | n/a — error tooltip if unset at import time          |
| `JAPANESE_DICTIONARY_PASSWORD` | yes      | HTTP Basic auth password for `japanese_dictionary_api`          | n/a — error tooltip if unset at import time          |
| `JAPANESE_DICTIONARY_API_URL`  | no       | Override the API base URL for testing against a different stack | `https://api.japanese-dictionary.jordansimsmith.com` |

Anki has no built-in env-var injection; the user exports these in their shell before launching Anki (typical pattern: `~/.zshrc` or platform equivalent). Same shape as `anki_backup_api/addon/anki_backup.py`'s env-var contract.

### `config.json` (Anki add-on config)

Shipped defaults persisted via Anki's add-on config (`mw.addonManager.getConfig`):

```json
{
  "deck": "Mining",
  "note_type": "Animecards",
  "field_mapping": {
    "Word": "Word",
    "Reading": "Reading",
    "Glossary": "Glossary",
    "Audio": "Audio",
    "Graph": "Graph"
  },
  "tag": "japanese_dictionary",
  "duplicate_field": "Word",
  "api_url": "https://api.japanese-dictionary.jordansimsmith.com"
}
```

Users edit via Anki's standard `Config` button on the Add-ons screen.

## Behavioral invariants

- A bookmark is either present (queued) or absent (resolved). There is no `imported` flag server-side; the Anki collection is the source of truth for what's been imported.
- Successful imports and explicit drops both clear the bookmark at the API on `Yes` to the post-import confirmation. `No` to the confirmation skips all server `DELETE`s; the imported notes still land in Anki and the bookmarks stay queued for the next run (where they will be flagged as duplicates).
- Failures (e.g. `add_note` rejected, jpod101 timeout when audio is required) leave the bookmark in place even on `Yes` because failed sequences are not added to the cleanup list.
- A row is counted as `imported_without_audio` when `add_note` succeeds but the Audio field could not be populated (download or content-type check failed, or the response matched the jpod101 invalid-audio stub). The bookmark is still cleared on `Yes`.
- Dangling bookmarks (sequence absent from the corpus, e.g. after a Jitendex refresh) are silently omitted from the dialog. They remain bookmarked at the API and will only re-surface if a future corpus refresh re-adds the term.
- Duplicate detection runs once at dialog open, not at commit time. The user can manually re-enable a duplicate to force-add it; Anki's own duplicate-detection on `add_note` will then decide whether to accept.
- The add-on never modifies the `Animecards` model, deck templates, or styling. It only reads existing model / field metadata and writes new notes.

## Source of truth

| Entity                  | Authoritative source                                              | Notes                                                                                                 |
| ----------------------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Queued bookmarks        | `japanese_dictionary_api`'s `BOOKMARK#` rows                      | fetched per `GET /bookmarks?include=term`; the addon never writes bookmarks server-side               |
| Term records            | `japanese_dictionary_api`'s `TERM#` rows                          | hydrated into each `Bookmark` row by the API when `?include=term` is set                              |
| Audio binaries          | jpod101 CDN                                                       | fetched anonymously; downloaded bytes are written to Anki's media store and never persisted otherwise |
| Imported notes          | User's Anki collection                                            | created via `mw.col.add_note`; the addon never updates or deletes existing notes                      |
| Deck / note-type config | Anki add-on config persisted by `mw.addonManager`                 | seeded from the shipped `config.json` defaults                                                        |
| HTTP credentials        | User shell environment (`JAPANESE_DICTIONARY_USER` / `_PASSWORD`) | read fresh on every request; never persisted by the add-on                                            |

## Security and privacy

- Credentials are read from environment variables on every request and passed to `requests.request` via `auth=(user, password)`. Never logged. Errors that quote env-var presence (e.g. `"JAPANESE_DICTIONARY_USER is not set"`) never include the value.
- HTTPS for the API and jpod101 audio. No certificate pinning beyond `requests`'s defaults.
- Audio binary validation is minimal: confirm `Content-Type` starts with `audio/`. Worst case a "no audio available" stub file ends up in the user's media store; the user can re-record manually.
- No PII crossing the network. Bookmarks are JMdict sequence integers + epoch timestamps; jpod101 fetches are anonymous headword/reading lookups.
- Anki collection writes are scoped to `mw.col.media.write_data` (write-only, new files only) and `mw.col.add_note` (new notes only). The addon never updates or deletes existing notes, models, or templates.

## Performance envelope

- **Dialog open**: runs in the background `QueryOp`, so the Anki UI stays responsive while the addon does `GET /bookmarks?include=term` (~150–300 ms warm path) + per-row `find_notes` queries (~1 ms each, ~100 ms for 100 bookmarks). The dialog itself appears within Anki's "instant" budget once the background work returns.
- **Import**: dominated by audio download (~200–800 ms per row). Parallelised via `ThreadPoolExecutor`; wall-clock ≈ `max(per-row download)` capped at the executor pool size. For 10 rows, audio is ~1 s instead of ~5 s sequential. `add_note` is ~10 ms × N rows. Total import phase for 10 rows: ~2 s.
- **Cleanup**: ~50 ms × N rows for sequential `DELETE`s. ~500 ms for 10 rows. Skipped entirely if the user picks `No` at the confirmation.
- **Glossary HTML render**: single-pass tree walk, ~5–20 ms per term in pure Python with `xml.etree.ElementTree`. Done in memory before dialog opens.
- **Pitch graph SVG render**: ~1 ms per term. Negligible.
- **Memory footprint**: hundred bookmarks × ~5 KB per `Bookmark` ≈ 500 KB at dialog open. Trivial.

No formal SLO; sized for personal workload only.

## Testing and quality gates

- Pure-function ports (`structured_content`, `pitch_graph`, `kana_form`, `audio`) have `pytest` unit tests with `@pytest.mark.parametrize` for table-driven cases. New `addon/test_*.py` files are picked up automatically by the `addon-unit-tests` py_test target via `pytest` discovery.
- UI (`dialog.py`) and integration (`japanese_dictionary.py` orchestration, `api_client.py` HTTP) code is exercised by manual smoke in Anki.
- Required checks:
  - `bazel build //japanese_dictionary_api:addon-package`
  - `bazel test //japanese_dictionary_api:addon-unit-tests`
- Repository-level checks (per `AGENTS.md`):
  - `bazel mod tidy`
  - `bazel run //:format`

## Local development and smoke checks

- Build the `.ankiaddon` zip:
  - `bazel build //japanese_dictionary_api:addon-package`
  - Output: `bazel-bin/japanese_dictionary_api/japanese-dictionary-importer.ankiaddon`
- Install in Anki desktop:
  - Tools > Add-ons > Install from file → pick the `.ankiaddon`.
  - Restart Anki.
- Run the addon unit tests:
  - `bazel test //japanese_dictionary_api:addon-unit-tests`
- Manual smoke flow (after installing in Anki):
  1. Export `JAPANESE_DICTIONARY_USER` and `JAPANESE_DICTIONARY_PASSWORD` in the shell that launches Anki.
  2. Use the SPA on phone to bookmark a few terms.
  3. In Anki desktop, click Tools → Import Japanese dictionary bookmarks.
  4. The progress bar `Fetching Japanese dictionary bookmarks...` appears (UI stays interactive).
  5. The dialog should show one row per bookmark with Word / Reading / freq, duplicates flagged.
  6. Confirm; the import progress bar runs, then the confirmation dialog appears summarising imports / drops / failures.
  7. Pick `Yes` — the cleanup progress bar runs, then the summary tooltip appears.
  8. Check the Mining deck — new `Animecards`-tagged notes appear with Word, Reading, Glossary, Audio, Graph populated.
  9. Reload the SPA on phone — the imported bookmarks are gone from the queue.
  10. Repeat with `No` at the confirmation step to verify bookmarks stay queued and re-appear (flagged as duplicates) on the next run.

## End-to-end scenarios

### Scenario 1: typical bookmark-import-clear loop

1. The user bookmarks 10 terms via the SPA on phone over the course of a day.
2. At their laptop, they launch Anki with `JAPANESE_DICTIONARY_USER` / `_PASSWORD` exported.
3. They click Tools → Import Japanese dictionary bookmarks. The fetch progress bar appears.
4. The dialog opens with all 10 rows pre-checked.
5. They commit. The import progress bar runs (audio download + notes added).
6. The confirmation dialog appears: `"Imported 10 note(s) into Anki. Delete 10 bookmark(s) from the server now?"`.
7. They pick `Yes`. Cleanup progress bar runs, then the summary tooltip: `"Imported 10"`.
8. Reloading the SPA on phone shows zero queued bookmarks.

### Scenario 2: duplicate handling

1. The user has previously imported `新橋` (sequence 1316830) and re-bookmarks it on the phone.
2. They run the import flow on desktop.
3. The dialog shows the row unchecked, with a `[duplicate]` tag (the configured deck already has a note where `Word == 新橋`).
4. The user leaves it unchecked and clicks commit.
5. The import phase adds nothing to Anki; the confirmation dialog asks whether to clear the 1 dropped row.
6. They pick `Yes`. Cleanup runs `DELETE /bookmarks/1316830`, removing the stale bookmark.
7. Summary tooltip: `"Imported 0 (1 dropped)"`.

### Scenario 3: audio failure degrades gracefully

1. The user bookmarks a rare term whose jpod101 audio is missing (network error, non-2xx after redirect, or the jpod101 invalid-audio stub matched by SHA-256).
2. They run the import flow.
3. The audio download returns `None`; the Audio field is left blank.
4. `add_note` succeeds. The confirmation dialog summary notes `1 imported without audio`.
5. They pick `Yes`. Cleanup runs `DELETE /bookmarks/{sequence}` — the import succeeded structurally.
6. Summary tooltip: `"Imported 1 (1 without audio)"`. The user can re-record audio in Anki later.

### Scenario 4: dangling bookmark after corpus refresh

1. A previous Jitendex refresh removed sequence 9999999, but the user's old bookmark for it remains in DynamoDB.
2. They run the import flow.
3. `GET /bookmarks?include=term` silently drops the dangling row server-side.
4. The dialog shows the user's other bookmarks but not the dangling one.
5. The dangling bookmark stays in DynamoDB; no surface to clean it up in v1. It would only re-appear if a future corpus refresh re-adds the term.

### Scenario 5: user spots a mistake at the confirmation checkpoint

1. The user runs the import flow and accidentally picks the wrong deck before clicking commit.
2. The import phase finishes; notes have already been added to the wrong deck.
3. The confirmation dialog appears: `"Imported 5 note(s) into Anki. Delete 5 bookmark(s) from the server now?"`.
4. They pick `No`. The cleanup phase is skipped.
5. Summary tooltip: `"Imported 5; 5 bookmarks kept on server"`.
6. They delete the wrongly-decked notes from Anki, then re-run the import flow with the correct deck. The dialog re-appears with the original 5 bookmarks (now flagged as duplicates because the wrongly-decked notes still exist if they didn't delete them; otherwise they re-import cleanly).
