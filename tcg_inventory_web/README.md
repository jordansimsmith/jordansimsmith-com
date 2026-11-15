# TCG inventory web

TCG inventory web is the seller workspace for the
[TCG inventory API](../tcg_inventory_api/README.md). It helps sellers review
incoming cards, find them in physical storage, and fulfill FetchTCG orders.
Import review, placement, and order picking support one-handed use at the boxes.

## Features

- **Intake.** Upload a ManaBox CSV or a batch of scanner JPEGs, review the
  cards and their appraisals, add required listing photos, and confirm keepers
  to get placement instructions.
- **Inventory and pricing.** Search inventory by game, view individual copies
  and their locations, remove units or correct their condition, and set a price
  floor or reprice stock.
- **Publishing and fulfillment.** Connect a FetchTCG account, publish changes,
  and use location-ordered pull sheets and shipping details to fulfill orders.
- **Reports.** View stock, valuation, and sales across games.

Magic: The Gathering supports CSV and scanner intake. English Pokémon shares
the inventory, pricing, publishing, order, and reporting workflows.

## Local development, build, and deployment

The app uses React, TypeScript, Vite, Mantine, and React Router. Start the dev
server from the repository root.

```bash
pnpm install
cd tcg_inventory_web
pnpm vite dev
```

Development uses a seeded, in-memory fake client. Sign in with any credentials
to exercise the workflows locally. Production uses the HTTP client.
Set `VITE_API_BASE_URL` at build time to override the default
`https://api.tcg-inventory.jordansimsmith.com`.

Run the Bazel checks from the repository root.

```bash
bazel test //tcg_inventory_web:unit-tests
bazel build //tcg_inventory_web:typecheck //tcg_inventory_web:build
```

Deploy the static Vite build to S3 and CloudFront with the
[Terraform runner](../tools/terraform/apply.py), using the
[web infrastructure](infra/main.tf).

```bash
bazel run //:apply -- --path tcg_inventory_web/infra
```

## Frontend architecture

- [App.tsx](src/App.tsx) defines the routes and authenticated workspace.
  [Pages](src/pages) own workflow state, with Vitest and React Testing Library
  tests alongside the code.
- [ApiClient](src/api/client.ts) defines the types and operations shared by the
  [HTTP client](src/api/http-client.ts) and the
  [seeded fake](src/api/fake-client.ts). The API owns durable state, card
  locations, appraisal decisions, and report figures.
- [GamesProvider](src/GamesProvider.tsx) loads game names, languages, finishes,
  and intake capabilities from `GET /games` for the authenticated workspace.
  Use those values when rendering game labels and choosing available actions.
- The Basic auth session persists in browser storage and clears on logout.
  The FetchTCG refresh token is write-only in the browser. Scan uploads use
  presigned S3 URLs without the API authorization header.

## Workflow rules

- **Keep physical order.** Preserve the API's row order. ManaBox review starts
  at the top of the stack. Scan filenames sort in ASCII order from bottom to
  top, and the first scan becomes the first import row. Pull sheets group by
  game and follow the location order from the API.
- **Preserve the two photo workflows.** Upload original scanner JPEGs and
  verify the complete batch before starting identification. The API retains
  completed uploads and recognition suggestions across sessions. An incomplete
  batch requires a new upload. Listing photos are added separately during import
  review. Use the API's `needs_photos` flag to gate confirmation and refresh the
  review when the page becomes visible so photos added on a phone appear.
- **Follow server progress.** Poll while work is active and stop when it
  completes or the workflow view unmounts. During confirmation, keep the review
  visible and read-only. Open the linked import after a scan confirms. Reports
  regenerate automatically when missing or stale and keep the previous snapshot
  visible during refresh.
- **Keep keyboard and touch controls.** In inventory, `/` focuses the current
  game's search. In scan review, `j`/`k` move between rows, `h`/`l` move between
  printings, `/` focuses search, and `c` confirms the selected printing.
  Shortcuts pause while editing text. Use explicit buttons for deleting a row
  and confirming the whole scan.

## Design and verification

These patterns were checked in the seeded app at 1440 px and 390 px.

- **Shared styles.** Use Mantine's system typography, white surfaces, thin gray
  borders, and muted supporting text. Primary buttons default to blue, and
  scan review uses teal for **Confirm match**. Active navigation and selected
  scan rows use pale blue with a blue edge. Pair state colors with text.
  Keep keyboard focus visible and give icon buttons accessible names.
  [Theme](src/main.tsx) · [Scan review styles](src/components/ScanReview.module.css)
- **Shell and collections.** Use the shared page header and workspace shell.
  The desktop sidebar collapses to a phone menu. Keep controls with the
  content they affect, as inventory does with search. Use compact rows and
  aligned numbers on desktop, then reflow into labeled fields on phones.
  [Page header](src/components/PageHeader.tsx) ·
  [Workspace shell](src/layouts/AppShellLayout.tsx) ·
  [Collection styles](src/components/CollectionTable.module.css)
- **Review.** Place the scan, selected printing, and detail crops side by side
  on desktop. On phones, stack the comparison panels, use a horizontal card
  selector, and keep match actions in a fixed bottom bar. Keep condition,
  decision, and photo controls with each import row and confirmation below.
  [Scan review](src/components/ScanReview.tsx) ·
  [Import review](src/components/ImportReviewTable.tsx)
- **Order pulling.** Make the current location the strongest text in each row.
  Keep the thumbnail and price beside it and show neighboring cards below.
  Put supporting panels beside the pull sheet on desktop and above it on phones,
  with a full-width **Confirm pull** button at the end.
  [Order detail](src/pages/OrderDetailPage.tsx)
- **Reports.** Lead with combined valuation and revenue, followed by trends
  and game tabs. Pair charts on desktop and stack them on phones.
  [Reports](src/pages/ReportsPage.tsx)

Check changed screens at desktop and phone widths. Keep horizontal scrolling
inside controls that need it, such as the scan card selector.

For a quick smoke check at 390 px in dev mode, review a Magic import, add a
photo to a flagged row, and confirm through to placement. Then open the seeded
mixed-game order ready to pick. Check that the pull sheet fits the viewport,
shows the current positions and neighboring cards, and reaches fulfilled
status after confirming the pull.
