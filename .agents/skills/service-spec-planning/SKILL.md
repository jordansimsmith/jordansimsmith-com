---
name: service-spec-planning
description: Runs a doc-first, multi-turn workflow that turns a service idea or major feature proposal into product requirements, a technical specification, a sequential task list, and canonical service READMEs ready for handover to an implementation agent. Use when the user wants to plan a new service, work out product requirements, design a feature that warrants its own spec documents, or says phrases like "let's plan", "work out the requirements", "start a new service", or "formalise the specs".
---

# Service spec planning

Guide a multi-turn workflow that takes a rough service idea from the user and produces everything another agent needs to implement it: product requirements, a technical specification, a sequential task list, and canonical service READMEs.

## When to use

- User proposes a new service in this monorepo (for example "I want to build a new service that does X").
- User asks to plan, design, or spec a major feature that warrants its own documents.
- User explicitly asks to "work out the requirements", "formalise the specs", or "create a task list" for work that does not yet exist.

Do not use:

- For small changes that fit inside an existing README update; use the `service-readme-authoring` skill instead.
- For ad-hoc questions about how existing services work.

## Workflow

Six phases. Each phase produces a concrete artifact that persists across turns.

### Phase 1: Explore existing patterns

Before drafting anything, read the closest existing analogs so every decision grounds in repo conventions.

For a new backend service:

- `packing_list_api/README.md`, `packing_list_api/BUILD.bazel`, one handler (for example `CreateTripHandler.java`), `AuthHandler.java` (the slim service-specific shell that delegates to `lib/auth.RequestAuthorizer`), `packing_list_api/infra/main.tf`, and `packing_list_api/src/test/resources/init_resources.py`.
- `immersion_tracker_api/src/main/java/com/jordansimsmith/immersiontracker/HttpYoutubeClient.java` when external HTTP integration is in scope.

For a new frontend service:

- `packing_list_web/README.md`, `packing_list_web/BUILD.bazel`, `src/App.tsx`, `src/api/client.ts`, `src/api/http-client.ts`, `src/auth/session.ts`, and `packing_list_web/infra/main.tf`.

Repo-wide:

- `AGENTS.md` for conventions and required post-change commands.
- The root `.gitignore` — confirm `tmp/` is listed so intermediary spec docs will not be committed.

### Phase 2: Product spec (what)

**`tmp/` discipline.** The entire `tmp/` directory is listed in the repository's root `.gitignore`. Files produced in Phases 2 through 4 live there specifically because they are session artifacts, not long-lived repo assets. They must not be referenced from any checked-in artifact (READMEs, `BUILD.bazel`, Terraform, source code, tests, commit messages). Once Phase 5 promotes decisions into the canonical READMEs, the `tmp/` docs are kept only as session history and may be deleted at any time without affecting the repo. The READMEs are the single source of truth for anyone who arrives fresh.

Create `tmp/<SERVICE_NAME>_PRODUCT.md`. `SERVICE_NAME` is upper-snake-case without the `_api` / `_web` suffix, mirroring the existing `tmp/ANKI_BACKUP_PRODUCT.md` naming pattern.

Scaffold the file immediately with these sections:

- Document purpose.
- Product context (problem the user is solving, in the user's own words when possible).
- Known anchors (any requirements the user has already stated, numbered).
- Open questions stubs (one per theme).
- TBD placeholders for the output sections (functional requirements, non-goals, success criteria, constraints, deferred).

Ask focused, themed batches of questions (8–12 per round maximum). Typical themes:

- Data source and canonical identifier.
- Manual vs. strict entry.
- Which states or lifecycle stages are tracked.
- Date semantics (auto-now vs. user-picked, backfill, timezone).
- Uniqueness and duplicate handling.
- Edit and delete behavior.
- Metadata fields captured.
- Analytics and measurement.
- Auth and user model (confirm the repo pattern unless the user asks otherwise).
- Service naming.

Prose questions for nuanced topics. Use the `AskQuestion` tool for discrete choices with clear option sets (typically three to four options).

After each round:

1. Move every answer into a "Resolved decisions" section with rationale per decision.
2. Surface follow-ups that fell out of the answers as new open questions.
3. Fill in or update the functional requirements, non-goals, success criteria, and constraints sections as decisions accumulate.

Stop asking when remaining open questions are implementation details, not product-level ones.

### Phase 3: Technical spec (how)

Create `tmp/<SERVICE_NAME>_TECH.md` (mirroring `tmp/ANKI_BACKUP_TECH.md`).

Read the product spec first. Then read any additional repo files that the technical choices depend on (HTTP clients, DynamoDB item patterns, Terraform modules, frontend API client shapes).

Run live checks on external systems before committing architecture. Use `WebFetch` to probe real response shapes, not guesses. A single unexpected finding (for example "this one endpoint already returns everything we need") can collapse what looked like a complex integration into a single call.

Draft the whole spec with recommendations already baked in. Structure:

1. Document purpose and a pointer to `tmp/<SERVICE_NAME>_PRODUCT.md`.
2. Confirmed decisions (stack, identifiers, runtime shape).
3. Decisions to confirm — the highest-stakes open choices, each with options and a recommended pick plus rationale.
4. Monorepo pattern alignment.
5. Proposed repository layout for both `<service>_api/` and `<service>_web/`.
6. Architecture mermaid + primary workflow mermaid.
7. Main technical decisions (numbered, with rationale and tradeoffs).
8. API contracts (conventions, endpoint table, example request/response, validation rules).
9. Data and storage contracts (DynamoDB model, access patterns, representative record, secret shape).
10. Core business logic (simplified Java sketches for non-obvious handlers).
11. Frontend contract (routes, `ApiClient` shape, session storage, env vars).
12. Security and privacy.
13. Performance envelope.
14. Testing and quality gates (unit, integration, E2E).
15. Infrastructure (Terraform) summary.
16. Deferred from v1 (explicit non-goals at the technical level).

Ask one crisp confirmation question for the highest-stakes decision (typically the integration architecture) via `AskQuestion`. Minor calls ride as defaults the user can push back on.

After confirmation, fold "Decisions to confirm" into "Confirmed decisions" and remove alternatives text so the doc reads as final.

### Phase 4: Task list (when)

Create `tmp/<SERVICE_NAME>_TASKS.md`.

Structure:

- Overview table showing phases and task ranges.
- Working agreements per task (read the READMEs first, follow TDD, run `bazel test` / `bazel mod tidy` / `bazel run //:format`, commit-message style per `AGENTS.md`).
- Numbered tasks, each with:
  - Short imperative title.
  - "Ships:" bullet list of exactly what the commit produces.
  - "Done when:" bullet list of explicit completion checks (usually `bazel build` or `bazel test` targets).

Default phasing for a new service:

- **Phase 1 — Frontend with fake mode**: scaffold, auth, API client + fake, timeline page, search + add, edit, delete, layout polish. Each task is commit-sized; fakes let the UX ship end-to-end before any backend exists.
- **Phase 2 — Backend**: scaffold + slim `AuthHandler` (delegates to `lib/auth.RequestAuthorizer`) + test infrastructure, one handler per task, LocalStack E2E.
- **Phase 3 — Infrastructure and wiring**: Terraform for API, wire real `http-client.ts`, Terraform for web.

Rule of thumb: `1 task = 1 reviewable commit`. Resist splitting further when it would produce a commit with no user-visible output. Resist combining further when the scope starts to outgrow a comfortable review size.

### Phase 5: Promote to canonical READMEs

Use the `service-readme-authoring` skill at `.agents/skills/service-readme-authoring/SKILL.md` to author `<service>_api/README.md` and `<service>_web/README.md`. Read its reference examples (`example-service-readme-api.md` and `example-service-readme-web.md`) before drafting.

Derive README content from the product and tech specs; do not start from scratch. Every section in the README template must be populated with concrete contracts (exact endpoint tables, representative records, validation rules, invariants, example request/response payloads).

After the READMEs exist:

1. Update the task list's working agreements to make the READMEs the primary spec and the `tmp/` docs the secondary context.
2. Remove any task that was about writing READMEs — they already exist.
3. Renumber the remaining tasks so the series stays contiguous.

### Phase 6: Iterate

Small refinements often land after the canonical docs exist. When one does:

1. Edit the docs in one pass, primary first (READMEs), then intermediaries (`tmp/*.md`).
2. Use targeted `StrReplace` edits rather than full rewrites.
3. Verify with `rg` (not `Grep`, which can miss `tmp/`) that no stale references to the old name or shape remain.
4. Note any trade-offs the change accepts in the relevant "Main technical decisions" section of the affected READMEs.

## Content standards

- Use sentence casing for headings.
- `snake_case` for wire fields and DynamoDB attributes; `camelCase` for Java; `kebab-case` for Bazel targets and CLI flags. Be internally consistent when a concept crosses layers (for example `open_library_work_id` snake-case maps to `openLibraryWorkId` camelCase, treating a two-word brand as two words in both forms).
- Current-state language, not roadmap or status tracking.
- Concrete examples for every non-trivial contract.
- Explicit non-goals — easier to decline scope on paper than on a PR.
- Never reference `tmp/*` paths from checked-in artifacts. Anything a permanent reader needs must live in the READMEs or other tracked files.

## Anti-patterns to avoid

- **Starting without reading analogs.** Guessing at conventions creates inconsistency no code review will fix cleanly.
- **Asking twenty questions up front.** Batch by theme, run multiple rounds, let answers reshape subsequent questions.
- **Leaving decisions implicit.** Every answered question must end up in a "Resolved decisions" section with captured rationale.
- **Treating "final" as final.** Specs evolve as new information arrives; iteration is cheap while everything is still in markdown.
- **Conflating product and tech.** A product session that drifts into implementation details, or a tech session that re-litigates product scope, usually produces worse artifacts than two separately disciplined ones.
- **Authoring READMEs too early.** Draft intermediaries in `tmp/` first; promote only once the shape is stable, so the READMEs do not need churning.

## Handoff criteria

The workflow is done when:

- `<service>_api/README.md` and `<service>_web/README.md` exist at their canonical locations, fully populated per `service-readme-authoring`.
- `tmp/<SERVICE_NAME>_TASKS.md` has numbered, commit-sized tasks with "Ships" and "Done when" bullets, pointing at the READMEs as the primary spec.
- `tmp/<SERVICE_NAME>_{PRODUCT,TECH}.md` remain in place as session history and secondary context.
- The next agent can start at Task 1 without needing to re-open any product or technical question.
