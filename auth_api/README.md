# Auth API

The auth API service provides the single shared API Gateway Lambda authorizer and credential secret used by every authenticated API in this repository.

## Overview

- **Service type**: backend authorizer (`auth_api`)
- **Interface**: AWS API Gateway REST custom (REQUEST) authorizer invocations, not a public HTTP API
- **Runtime**: AWS Lambda (Java 21)
- **Primary storage**: AWS Secrets Manager secret `auth_api`
- **Primary consumers**: `book_tracker_api`, `immersion_tracker_api`, `packing_list_api`, `japanese_dictionary_api`, `anki_backup_api`

## User stories

- As a service owner, I want one authorizer Lambda shared by every authenticated API, so that auth logic is deployed and maintained in exactly one place.
- As a service owner, I want a single credential secret with a JSON map shape, so that Secrets Manager costs do not grow with the number of services.
- As a user of any tracked service, I want my one set of Basic credentials to work across all authenticated APIs, so that I do not manage per-service logins.

## Features and scope boundaries

### In scope

- Validate `Authorization: Basic <base64 user:password>` headers against the global `users` list in the `auth_api` secret.
- Return an IAM policy allowing or denying `execute-api:Invoke`, broadened to `apiId/stage/*/*` so cached policies apply across all endpoints of the calling API stage.
- Grant API Gateway account-wide permission to invoke the authorizer through the stable `live` alias, so new consumer APIs need no wiring in this service.
- Own the shared `auth_api` secret resource that other services may extend with namespaced keys.

### Out of scope

- Per-service user lists or authorization scoping; any valid credential is accepted by every consuming API.
- User management endpoints; credentials are edited directly in Secrets Manager.
- Token issuance, sessions, or OAuth flows.
- Values other services store in the shared secret map (owned by those services).

## Architecture

```mermaid
flowchart TD
  A[API Gateway: book_tracker] -->|authorize request| L[Lambda: auth_api_auth alias live]
  B[API Gateway: immersion_tracker] -->|authorize request| L
  C[API Gateway: packing_list] -->|authorize request| L
  D[API Gateway: japanese_dictionary] -->|authorize request| L
  E[API Gateway: anki_backup] -->|authorize request| L
  L --> S[Secrets Manager: auth_api]
```

### Primary workflow

```mermaid
sequenceDiagram
  participant C as client
  participant G as consumer API Gateway
  participant A as auth_api_auth Lambda
  participant S as Secrets Manager

  C->>G: request with Authorization Basic header
  G->>A: REQUEST authorizer invocation (headers, methodArn)
  A->>S: get secret auth_api
  S-->>A: JSON map with users list
  A-->>G: IAM policy (ALLOW or DENY) for apiId/stage/*/*
  G->>G: cache policy for 300 seconds keyed on header
  G-->>C: forward to handler or 401/403
```

## Main technical decisions

- One authorizer Lambda serves every API Gateway instead of one per service; the trade-off is a shared blast radius, accepted for a personal, low-traffic estate.
- One Secrets Manager secret holds all credentials because secrets are priced per secret; the JSON map shape lets other services add namespaced keys later without creating new secrets.
- A single global `users` list applies to all consumers, avoiding any mapping from API Gateway IDs to services.
- Consumers reference the authorizer by convention (`arn:aws:lambda:<region>:<account>:function:auth_api_auth:live`) rather than Terraform remote state, keeping service stacks independent.
- The `live` alias decouples consumer authorizer URIs from published Lambda versions, which change on every deploy because SnapStart requires published versions.
- Invoke permission is granted account-wide for `apigateway.amazonaws.com` so onboarding a new authenticated API requires no change in this service.

## Integration contracts

None in current scope; all callers are first-party API Gateway instances in this account.

### External systems

- None.

## API contracts

### Conventions

- Input event: API Gateway REQUEST authorizer payload with `headers`, `queryStringParameters`, and `methodArn`.
- Credential scheme: `Authorization: Basic <base64 user:password>`; passwords may contain colons (split on first `:` only).
- Output: `principalId` set to the presented username plus an IAM `policyDocument` for `execute-api:Invoke`.
- A missing or empty `Authorization` header throws `Unauthorized`, which API Gateway maps to `401`; a wrong password or unknown user returns a `DENY` policy, which maps to `403`.

### Endpoint summary

Not applicable; the Lambda is invoked directly by API Gateway authorizers, not via HTTP routes.

### Example request and response

Authorizer event (abridged):

```json
{
  "headers": { "Authorization": "Basic YWxpY2U6c3Ryb25nLXBhc3N3b3Jk" },
  "methodArn": "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/GET/books"
}
```

Response for a valid credential:

```json
{
  "principalId": "alice",
  "policyDocument": {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Action": "execute-api:Invoke",
        "Effect": "Allow",
        "Resource": "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/*/*"
      }
    ]
  }
}
```

## Data and storage contracts

### Secrets Manager

- **Secret name**: `auth_api`
- **Shape**: a single JSON map. The `users` key is owned by this service; other services may add their own namespaced top-level keys (for example a `<service_name>` object) without affecting authorization.
- The Terraform in this service creates the secret resource only; the value is populated manually in AWS.

## Behavioral invariants and time semantics

- Credential comparison is exact (case-sensitive) on both user and password.
- The returned policy resource is always broadened to `apiId/stage/*/*` so API Gateway's 300-second cached policy applies to every endpoint in the calling stage.
- `principalId` is the presented username even on DENY responses.
- The authorizer is stateless; every invocation reads the current secret value (no in-process caching beyond the Lambda instance lifetime of the secrets client).

## Source of truth

| Entity              | Authoritative source                    | Notes                                                |
| ------------------- | --------------------------------------- | ---------------------------------------------------- |
| Credential set      | Secrets Manager `auth_api` `users` list | edited manually, never stored in code or state       |
| Authorizer decision | `auth_api_auth` Lambda                  | consumers must not re-validate passwords in handlers |
| Namespaced keys     | the owning service                      | this service treats them as opaque                   |

## Security and privacy

- Any valid credential grants access to every consuming API; there is no per-service authorization.
- The Lambda role has least-privilege access to the `auth_api` secret only.
- Raw `Authorization` headers and secret payloads must not be logged.
- Invocation is restricted to `apigateway.amazonaws.com` principals within this account.

## Configuration and secrets reference

### Environment variables

| Name         | Required | Purpose                            | Default behavior               |
| ------------ | -------- | ---------------------------------- | ------------------------------ |
| `AWS_REGION` | yes      | region for the Secrets Manager SDK | provided by the Lambda runtime |

### Secret shape

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

Other services may add namespaced top-level keys alongside `users`; the authorizer ignores them.

## Performance envelope

- Single-digit requests per second across all consumers; API Gateway caches ALLOW/DENY policies for 300 seconds per credential, so authorizer invocations are far rarer than API requests.
- SnapStart on published versions keeps cold starts low.
- One secret and one Lambda keep the fixed monthly cost at a single Secrets Manager secret regardless of how many services consume it.

## Testing and quality gates

- Unit tests cover allow/deny decisions, multiple users, colon-containing passwords, methodArn broadening, and malformed or missing headers (`AuthHandlerTest`).
- Consumer services rely on these tests plus Terraform review for authorizer coverage; their LocalStack E2E stacks run without an authorizer because LocalStack community does not enforce CUSTOM authorizers.
- Required checks before merge: `bazel test //auth_api:all` and `bazel build //auth_api:all`.

## Local development and smoke checks

- Run service tests: `bazel test //auth_api:all`
- Build the deployable artifact: `bazel build //auth_api:auth-handler_deploy.jar`

## End-to-end scenarios

### Scenario 1: valid credential across two services

1. A user configured in the `auth_api` secret calls `GET /books` on `book_tracker_api` with Basic auth.
2. API Gateway invokes `auth_api_auth`, which returns an ALLOW policy for the book tracker stage.
3. The same credential is used against `packing_list_api`; its gateway invokes the same Lambda and also receives ALLOW.

### Scenario 2: rejected credential

1. A client sends a request with an unknown user or wrong password.
2. The authorizer returns a DENY policy with the presented username as `principalId`.
3. API Gateway responds `403`; a request with no `Authorization` header instead gets `401` with a `WWW-Authenticate: Basic` response header from the consumer API's gateway response configuration.
