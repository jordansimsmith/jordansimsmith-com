# Packing list web

The packing list web service is a responsive single-page app that lets an authenticated user generate, edit, and pack per-trip packing lists using a base template plus optional variations.

Instead of maintaining a manual spreadsheet each holiday, the web app generates a custom packing list for that specific trip, lets you tweak it, and then makes it easy to check items off while packing.

## System architecture

```mermaid
graph TD
  A[Browser] --> B[CloudFront Distribution]
  B --> C[S3 Bucket: packing-list.jordansimsmith.com]
  C --> D[React SPA (Vite + Mantine)]
  D -->|HTTPS (Basic auth)| E[Packing list API]
```

## Requirements

### Functional requirements

- **Login**:
  - collect username/password
  - store a Basic auth token in `sessionStorage` (clears when tab is closed)
  - logout clears the stored token
- **Route protection**:
  - unauthenticated access to `/trips/*` redirects to `/`
  - after login, navigate to `/trips`
- **Trips home** (`/trips`):
  - list trips ordered by `departure_date` descending
  - link to create flow and to individual trips
- **Create trip** (`/trips/create`):
  - enter trip metadata: name, destination, departure date, return date
  - load templates from the backend (`GET /templates`)
  - select zero or more variations (base template is fixed in M1)
  - generate a draft list client-side by applying merge rules (see `packing_list_api/README.md`)
  - allow edits before persisting:
    - remove items
    - add one-off items
    - edit quantity/tags/status
  - persist by creating the trip in one shot (`POST /trips`)
- **Trip view / packing** (`/trips/:trip_id`):
  - load full trip (`GET /trips/{trip_id}`)
  - update by replacing the full trip (`PUT /trips/{trip_id}`)
  - group items by category
  - categories ordered alphabetically
  - hide categories with zero visible items
  - “hide packed” toggle
  - fast status controls supporting `unpacked`, `packed`, and `pack-just-in-time`
  - display tags at a glance
- **Responsive UX**: usable on both mobile and desktop

### Technical specifications

- **Framework**: React
- **Language**: TypeScript
- **Build tool**: Vite
- **UI library**: Mantine
- **Routing**: React Router (`react-router-dom`)
- **Forms**: `@mantine/form`
- **Notifications**: `@mantine/notifications`
- **State strategy**:
  - UI state in React component state
  - server state via typed `fetch` calls (no React Query)
- **Auth/session**:
  - Basic token stored in `sessionStorage`
  - request helper adds `Authorization` header and `?user=...` query param
- **Hosting**: S3 + CloudFront (SPA deep-link support)
- **Infra**: Terraform (aligned with repo patterns from `personal_website_web`)
- **Build system**: Bazel (aligned with repo patterns)

## Implementation details

### Route map

- `/` login/home
- `/trips` trips list (requires auth)
- `/trips/create` create trip (requires auth)
- `/trips/:trip_id` trip view + packing (requires auth)

### Client-side generation and snapshot behavior

- The web app generates and edits the packing list client-side, then persists the fully-materialized `items` array via `POST /trips`.
- This gives “snapshot” behavior naturally: once the trip is created, it is independent from any future changes to templates/variations.
- There is no server-side draft/finalization workflow in M1; drafts exist only within the create flow before the `POST /trips` call.

### UX notes

- **Generation flow**: the create experience is a 2-step flow — generate a draft list (base + selected variations) → edit/remove/add one-offs → create the trip (persist snapshot).
- **Make merges obvious**: because item identity is based on normalized name, the UI should make merges visible (e.g. quantity > 1 is clearly shown).
- **Status ergonomics**: support fast interactions for all 3 statuses:
  - quick toggle for `packed` vs `unpacked` (checkbox-like)
  - a distinct “pack later” interaction for `pack-just-in-time`
- **Categories and tags**:
  - categories are grouped and sorted alphabetically
  - encourage reuse of existing categories, but allow creating new ones at any time
  - tags are free text and created on the fly; show tags at a glance in packing view

### Module layout

#### API integration

- `api/client.ts`
  - typed endpoint functions (no generic transport exported):
    - `getTemplates()`
    - `createTrip(...)`
    - `getTrips()`
    - `getTrip(tripId)`
    - `updateTrip(trip)`
  - injects:
    - `Authorization` header
    - `?user=...` query param
  - normalizes error handling to `{"message":"..."}`

#### Auth/session

- `auth/session.ts`
  - read/write/clear Basic token in `sessionStorage`

#### Domain helpers

- `domain/normalize.ts`
  - `normalizedName(...)` used for item identity rules
- `domain/generate.ts`
  - merge logic (sum quantities, union tags, first category wins, status defaults to `unpacked`)

### Page/component architecture

#### Pages

- `LoginPage` (`/`)
- `TripsPage` (`/trips`)
- `CreateTripPage` (`/trips/create`)
- `TripPage` (`/trips/:trip_id`)

#### Layout and routing helpers

- `AppShellLayout` (Mantine `AppShell` header + main container)
- `RequireAuth` (redirect unauthenticated users)
- `ErrorBoundary` (route-level fallback)

#### Feature components

- `TripForm` (name/destination/departure/return)
- `TemplatesPicker` (base template read-only + variation multi-select)
- `PackingListEditor` (add/remove/edit items during create flow)
- `PackingListView` (packing view with grouping + status toggles)

#### Reusable components

- `CategorySection` (category header + list of items)
- `ItemRow` (name, quantity, tags, status controls)
- `TagsInput` (free-text tags)
- `StatusControl` (supports `unpacked` / `packed` / `pack-just-in-time`)

### Local development

- Run the Vite dev server via Bazel:
  - `bazel run //packing_list_web:vite -- dev`
- Use a Vite dev proxy so local development can call the deployed API without adding `localhost` to API CORS:
  - proxy `/api/*` -> `https://api.packing-list.jordansimsmith.com/*`

### Testing

- **Unit tests**: Vitest + React Testing Library (jsdom)
- **Bazel**: `bazel test //packing_list_web:unit-tests`
- Focus areas:
  - packing list editor interactions (add/remove/edit; dedupe-by-normalized-name)
  - route protection and login redirect behavior
  - create trip happy path with `api/client.ts` mocked

### Deployment (Terraform) notes

- **Bucket**: `packing-list.jordansimsmith.com`
- **CloudFront**:
  - origin access control (OAC) for private S3 access
  - viewer protocol policy redirect-to-https
  - SPA routing support:
    - map `403` and `404` to `/index.html` with `200` so deep links work on refresh
- Terraform implementation should follow the existing pattern used by `personal_website_web/infra/main.tf`:
  - locate Bazel output via `data.external` and `tools/terraform/resolve_location.sh`
  - upload built assets using `hashicorp/dir/template` + `aws_s3_object`
