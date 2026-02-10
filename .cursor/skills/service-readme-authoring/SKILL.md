---
name: service-readme-authoring
description: Creates and updates service README specifications that stay synchronized with implementation. Use when creating a new service, adding or changing features, updating assumptions, or modifying architecture, API contracts, data contracts, security, configuration, or time semantics.
---

# Service README authoring

Treat each service README as the canonical product and technical specification for that service.
The goal is for a human or agent to understand the service deeply without crawling implementation first.

## When to use

Use this skill whenever work touches a service's behavior or contracts, including:

- creating a new service
- adding or changing a feature
- changing assumptions or scope boundaries
- updating integrations, APIs, storage, security, or configuration
- changing invariants, time semantics, or quality gates

## Non-negotiable policy

1. Before implementing in any service, read that service's `README.md`.
2. Before creating or updating a service README, read the relevant example first: API services use [example-service-readme-api.md](references/example-service-readme-api.md), web services use [example-service-readme-web.md](references/example-service-readme-web.md). If scope spans both service types, read both examples.
3. For a new service, create `README.md` first before implementation work starts.
4. For an existing service, update `README.md` in the same change as implementation updates.
5. Keep README content in current-state language. Do not write roadmap or status-tracking prose.
6. Prefer precise contracts and examples over broad or aspirational descriptions.

## Workflow

### New service workflow (doc-first)

1. Identify whether the service is API or web and read the matching example first (read both only if the service spans both types).
2. Create `<service>/README.md` from the template in [service-readme-template.md](references/service-readme-template.md).
3. Fill required sections with concrete product requirements and technical contracts.
4. If any critical requirement is unknown, ask targeted questions before proceeding.
5. Use the relevant example as style calibration (not as copy-paste output).
6. Only proceed to implementation after the initial README spec is complete enough to guide changes.

### Existing service workflow (doc-sync)

1. Identify whether the service is API or web and read the matching example first (read both only if the service spans both types).
2. Identify what changed in implementation intent or behavior.
3. Update the matching README sections in the same change.
4. Remove stale statements and examples that no longer match behavior.
5. Keep naming, contracts, invariants, and source-of-truth mapping internally consistent.
6. Ensure examples and section claims match current implementation and tests.

## Section coverage requirements

For every service README, maintain these sections as the default structure (or equivalent):

- service title + one-sentence purpose
- overview
- features and scope boundaries (`in scope`, `out of scope`)
- architecture + primary workflow diagram
- main technical decisions
- integration contracts
- API contracts (include only relevant subsections)
- data and storage contracts (include only relevant subsections)
- behavioral invariants and time semantics
- source of truth
- security and privacy
- configuration and secrets reference
- performance envelope
- testing and quality gates
- local development and smoke checks
- end-to-end scenarios

Use conditional subsections where appropriate (for example API-only, web-only, DynamoDB-only, browser-storage-only).

## Content standards

- Use sentence casing for headings (capitalize only the first word and proper nouns).
- Write deterministic, implementation-aligned requirements.
- Define exact contract expectations, not vague intentions.
- Keep terminology canonical across UI/API/storage/integration boundaries.
- Explicitly state scope boundaries and non-goals.
- Capture tradeoffs in main technical decisions.
- Include representative examples where contracts are non-obvious.

## Exclusions

Do not include these in service READMEs unless the user explicitly asks:

- project-management milestone/status tracking
- deployment/release runbooks
- operability runbooks or incident playbooks
- standalone risk-register sections

## Quality checklist

Before finalizing, verify:

- README can onboard a new reader without code spelunking.
- Product requirements and technical constraints are both present.
- Contracts are complete and consistent (API, integration, data, config, security).
- Invariants/time semantics and source-of-truth ownership are explicit.
- Architecture and workflow diagrams reflect current behavior.
- No stale or contradictory statements remain after implementation changes.

## References

- Template: [service-readme-template.md](references/service-readme-template.md)
- API example: [example-service-readme-api.md](references/example-service-readme-api.md)
- Web example: [example-service-readme-web.md](references/example-service-readme-web.md)
