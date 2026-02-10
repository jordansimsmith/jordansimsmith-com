# Service name

Write one sentence describing what this service does and the primary value it provides.
Use this heading and opening sentence in every service README.

## Overview

Document at-a-glance context such as service type, interface, runtime/stack, primary dependencies, and primary consumers.
Use this section in every service README.

## Features and scope boundaries

Describe implemented capabilities and explicit boundaries for what the service does not do.
Use this section in every service README.

### In scope

List concrete behaviors and responsibilities that are implemented.
Use this subsection in every service README.

### Out of scope

List explicit non-goals and exclusions to prevent ambiguity.
Use this subsection in every service README.

## Architecture

Provide a high-level Mermaid diagram showing main components and interactions.
Use this section in every service README.

### Primary workflow

Provide a Mermaid sequence or workflow diagram for at least one primary happy path from trigger to outcome.
Use this subsection in every service README.

## Main technical decisions

Capture key design choices, rationale, and notable tradeoffs or constraints.
Use this section in every service README.

## Domain glossary

Define core terms and canonical naming used across API, storage, UI, and integrations.
Use this section when the service has non-trivial domain vocabulary.

## Integration contracts

Document only true third-party integrations (for example vendor APIs and external webhooks). For each integration, state required fields inline along with auth method, cadence, and failure behavior.
Use this section in every service README; if none exist, state "none in current scope."

### External systems

Describe each external provider as bullets or subsections, with required fields written inline in the description.
Use this subsection in every service README.

## API contracts

Document request and response contract details relevant to this service.
Use this section in every service README, but keep only the subsections that match the service role.

### Conventions

Document the base URL, auth scheme, naming conventions (for example snake_case), error shape, and path or versioning conventions.
Use this subsection when the service exposes an API.

### Endpoint summary

Provide a method/path/purpose summary of exposed endpoints.
Use this subsection when the service exposes an API.

### Example request and response

Provide representative payload examples for at least one important endpoint, including success and key failure shapes where useful.
Use this subsection when the service exposes an API.

### Consumed backend endpoints

List endpoints this service consumes from upstream services.
Use this subsection when the service is a client (for example a web app, worker, or downstream consumer).

### UI contract expectations

Describe client-side assumptions about payload shape, required fields, idempotency behavior, and error handling behavior.
Use this subsection when the service is a UI or client that consumes APIs.

## Data and storage contracts

Document persistence, cache, or storage contract details and representative records.
Use this section when the service persists data or stores client-side state.

### DynamoDB model

Document table and index design, key schema, item types, and representative items.
Use this subsection when the service uses DynamoDB.

### Browser storage

Document local or session storage keys, their purpose, and retention behavior.
Use this subsection when the service is a web client that stores state in the browser.

### Data ownership expectations

Explain which data is authoritative upstream versus projected locally, and what is intentionally not persisted locally.
Use this subsection when the service consumes upstream data and transforms it for presentation or use.

## Behavioral invariants and time semantics

Describe deterministic rules for ordering, dedupe or idempotency, normalization, timezone or date handling, and derived values.
Use this section in every service README.

## Source of truth

Map each entity to its authoritative source and define ownership boundaries between systems.
Use this section in every service README.

## Security and privacy

Describe the authn/authz model, secret handling, encryption or transport expectations, and logging redaction constraints.
Use this section in every service README.

## Configuration and secrets reference

Document config and environment variables plus secret references, including purpose, requiredness, and default behavior (never secret values).
Use this section in every service README.

### Environment variables

List each variable name, required or optional status, purpose, and default or fallback behavior.
Use this subsection in every service README.

### Secret shape

Describe only the expected secret key structure or schema.
Use this subsection for backend or API services that read secrets at runtime.

### Secrets handling

Describe how credentials are entered, stored, and transmitted, and what is intentionally not persisted.
Use this subsection for web or client services that handle user-provided credentials or tokens.

## Performance envelope

Define expected load and cadence, latency and timeout targets, and scale or cost boundaries (including always-free-tier compatibility where relevant).
Use this section in every service README.

## Testing and quality gates

Specify required test types, key coverage areas, and pre-merge build and test commands.
Use this section in every service README.

## Local development and smoke checks

Describe the fastest local run path and a minimal smoke flow that validates core behavior.
Use this section in every service README.

## End-to-end scenarios

Provide one to two realistic input-to-output flows that cover critical paths.
Use this section in every service README.

### Scenario 1: name

Describe a step-by-step happy path from user or system action to persisted or output result.
Use this subsection in every service README.

### Scenario 2: name

Describe a second high-value flow (for example update, edit, retry, or failure-recovery path).
Use this subsection in every service README.
