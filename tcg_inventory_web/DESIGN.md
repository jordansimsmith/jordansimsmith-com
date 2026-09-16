---
version: beta
name: TCG inventory workspace
description: A dense, calm visual system for the TCG inventory web application.
colors:
  canvas: '#FFFFFF'
  surface: '#FFFFFF'
  ink: '#212529'
  muted: '#868E96'
  border: '#DEE2E6'
  subtle-surface: '#F8F9FA'
  selection: '#E7F5FF'
  primary: '#228BE6'
  confirmed: '#12B886'
  warning: '#FD7E14'
  danger: '#FA5252'
typography:
  body:
    fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif'
    fontSize: 0.875rem
    fontWeight: 400
    lineHeight: 1.5
  page-title:
    fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif'
    fontSize: 1.5rem
    fontWeight: 700
    lineHeight: 1.2
  metadata:
    fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif'
    fontSize: 0.75rem
    fontWeight: 500
    lineHeight: 1.4
rounded:
  sm: 4px
  md: 8px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
components:
  paper:
    backgroundColor: '{colors.surface}'
    rounded: '{rounded.md}'
    padding: '{spacing.md}'
  button-primary:
    backgroundColor: '{colors.primary}'
    textColor: '#FFFFFF'
    rounded: '{rounded.sm}'
  button-confirm:
    backgroundColor: '{colors.confirmed}'
    textColor: '#FFFFFF'
    rounded: '{rounded.sm}'
---

## Overview

The visual character is a calm, capable operations workbench: a well-organised ledger, card catalogue, and photo contact sheet. It is dense enough for sustained use, but never cramped or noisy. Hierarchy comes from careful spacing, flat paper-like grouping, and restrained colour rather than dashboard theatre or decorative brand moments.

## What good design looks like

Good design for TCG Inventory feels like a calm, capable operations workbench. Information is dense but never cramped; every page has a clear title, context, primary action, and bounded work area. Hierarchy comes from spacing, thin borders, restrained typography, and consistent alignment—not decoration. Tables are optimized for scanning, numbers align, selected and actionable states are unmistakable, and colour communicates status only. Desktop uses its width deliberately; mobile re-composes workflows instead of squeezing desktop tables. The interface should preserve context, attach actions to the content they affect, and make the next step obvious without making the product feel loud.

The visual north star combines three qualities:

- The scan workflow's clear borders, section labels, selection treatment, and action placement.
- The inventory tables' compact information density and fast vertical scanning.
- The order pull sheet's strong mobile hierarchy, physical context, and obvious completion action.

## Design principles

### Make the work legible

The interface exists to support repeated operational decisions. Names, locations, prices, counts, and statuses must be easy to compare at a glance. Decoration must never compete with the work.

### Group by purpose

Every bordered surface has one clear job: searching a collection, reviewing a batch, changing a setting, comparing evidence, or completing an action. Related controls and information share a boundary; unrelated tasks do not.

### Preserve context

Keep the relevant collection, progress, identity, status, and next action visible while someone moves through a workflow. Avoid transitions that replace useful context with an isolated control.

### Use density deliberately

Dense does not mean compressed. Use compact rows and modest type, then recover clarity through alignment, spacing, borders, and hierarchy. Large empty areas should reflect meaningful focus, not missing structure.

### Re-compose for the screen

Desktop layouts use width to support comparison and scanning. Mobile layouts change composition and priority; they do not shrink or squeeze a desktop grid until it technically fits.

## Colors

The default state is neutral. White canvas and surfaces, dark graphite text, and quiet gray rules make dense information easy to scan. Pale blue identifies selection without making the interface feel blue.

- **Primary** is for navigation, links, and the normal forward action.
- **Confirmed** is for a verified or completed state and its explicit action.
- **Warning** identifies an item needing attention; it is not ordinary metadata.
- **Danger** is for destructive or irreversible actions only.
- Avoid decorative gradients, glows, large colour fields, and decorative colour coding. The bold rainbow foil and purple-blue etched card-name treatments are the deliberate domain-specific exception. Colour communicates state or card finish, not ambience.

## Typography

Use the system sans serif at practical sizes. Page titles are compact and strong, not oversized. Body text is comfortably legible at a dense working size. Metadata is smaller and muted but remains readable in a long session.

Use tabular figures for dates, prices, counts, positions, percentages, and other values that benefit from vertical scanning. Right-align comparable numeric columns such as prices and counts. Prefer concise sentence-case labels. Reserve uppercase for very small structural labels and status badges where it genuinely improves separation.

Hierarchy should normally have no more than four levels on one screen:

1. Page title
2. Surface or workflow title
3. Primary content
4. Muted metadata or helper text

Avoid solving weak hierarchy with extra font sizes or font weights. Alignment and spacing should do most of the work.

## Layout

Use the available desktop width; do not centre normal application pages in a narrow marketing-style column. Begin a page with a compact title and, where helpful, a short muted description or status. Keep controls adjacent to the content they affect.

Create hierarchy with a small number of stable regions: a page header, primary working surface, and supporting panels. Use 8 px inside tight controls, 16 px between related controls or panels, and 24–32 px between page-level groups. On narrow screens, stack the same regions while preserving their order and visual relationship.

### Application shell

The header and navigation are stable, quiet infrastructure. They should frame the work without becoming a branded hero. The active navigation item uses the same pale blue selection language as selected data rows. Keep header and navigation borders subtle and consistent with content surfaces.

### Page anatomy

A standard page has:

1. A compact page header containing the title, optional description or status, and the primary page-level action.
2. An optional toolbar containing search, filters, upload controls, or batch progress.
3. One primary work surface.
4. Supporting surfaces only when they have a distinct purpose.

Page-level actions align with the title on wide screens and remain close to the header on narrow screens. Workflow actions belong inside or immediately beside the surface they affect.

### Wide-screen composition

Use additional width to reveal useful relationships. A detail page may pair a primary working column with a supporting summary column. Do not leave most of a desktop screen empty merely because the mobile composition uses one narrow column.

### Narrow-screen composition

Stack major regions in task order. Put identity and status first, working content second, and the completion action last or persistently adjacent when appropriate. No page should widen the browser document beyond the viewport.

## Elevation and depth

Depth comes from 1 px borders and surface grouping, not shadows. Papers are flat white surfaces on a white canvas; borders and spacing establish their boundary. Shadows are reserved for floating layers that overlap content, such as menus and popovers. The login paper uses a border alone.

## Shapes

Use 4 px radii for buttons, inputs, selected rows, and small controls. Use 8 px radii for papers and larger grouped surfaces. Avoid pills except for compact status badges. Keep outlines thin and neutral.

## Components

**Papers and panels:** Use `Paper withBorder` to group a meaningful unit of work: a table, form, comparison, action bar, or supporting detail. Do not wrap every text item in a card; every panel needs a clear job.

**Page headers:** Use one consistent arrangement across the app. Titles, descriptions, timestamps, statuses, totals, and actions should read as one header rather than several unrelated rows. Keep the title compact and let muted context sit directly beneath or beside it.

**Toolbars:** Search, upload, filtering, and batch controls belong in a defined toolbar above the content they modify. A toolbar may be inside the primary surface or directly attached to it. Avoid leaving controls floating between the page title and table.

**Tables and desktop lists:** Prefer dense rows, clear columns, and visible selection. Put tables inside a bordered surface with a subtle header background and restrained row rules. A selected row uses pale blue with a thin outline or equally clear edge treatment. Hover is quieter than selection. Avoid strong zebra striping when it competes with selection. Align comparable data consistently and use tabular figures for numeric columns.

**Mobile data:** Do not render wide desktop tables directly into the document. Re-compose list rows into compact stacked items, keeping the primary identity and status prominent and moving secondary fields into metadata lines or a small grid. When a true grid is essential, contain horizontal scrolling inside its bordered surface and preserve the most important identifying column.

**Batch review rows:** Desktop may use a dense table. Mobile uses one compact review surface per row, with card identity and decision first; set, finish, condition, and prices grouped beneath; and photo status and actions attached to that same row.

**Summary figures:** Prefer one segmented summary strip over a grid of unrelated metric cards. Use tint sparingly to distinguish one or two especially meaningful figures, not every number.

**Buttons and actions:** Give every action area a visible boundary or clear relationship to the content it changes. Group related actions together, with one obvious primary action. Destructive actions stay quieter but are never hidden.

**Forms and search:** Place labels above controls. Use direct, always-visible search when correction is a normal part of a page. Place short, actionable errors near the affected control.

**Settings:** Each settings topic is its own bordered section with a short title, explanation, current state, control, and attached save action. Do not present unrelated forms as one unstructured vertical stack.

**Images:** Bound images so they support the surrounding information instead of dominating the screen. Use `object-fit: contain` for card imagery and preserve its full shape within a neutral frame. Remote imagery has a visible neutral loading frame so the layout never presents a large unexplained blank area.

**Status badges:** Badges communicate state, not taxonomy for its own sake. Use the established semantic colours consistently: teal or green for confirmed, blue for active work, orange for attention, gray for neutral or unavailable, and red for failure or destructive outcomes.

**Loading states:** Skeletons should approximate the final surface and remain inside its boundary. Avoid loading arrangements that collapse or substantially rearrange when data arrives.

**Empty states:** Keep empty states compact and local to the surface that is empty. State what is missing and, when relevant, place the next available action nearby. Do not use illustrations or oversized messaging.

**Error states:** Put short, actionable errors next to the failed work. Preserve valid surrounding data when possible. Use red for the error signal, not for a large decorative field.

## Screen patterns

### Collection pages

Inventory, imports, and orders use the same broad structure: page header, attached controls when present, and one bordered collection surface. Desktop uses dense tables. Mobile uses compact list rows that prioritize identity, state, and the value needed to choose the next item.

### Detail pages

SKU, import, and order detail pages begin with one coherent identity and status region. The primary work and supporting information occupy distinct surfaces. On wide screens, use columns when the supporting information helps the active task; on narrow screens, stack in task order.

### Physical workflows

Import review and order pulling must remain usable while handling physical cards. Make the current item, location, decision, and next action visually dominant. Keep touch targets generous, keep context visible, and avoid horizontal document scrolling.

### Reports

Reports use one segmented headline strip followed by bordered analytical figures. Charts share a restrained palette and consistent title treatment. Tables and legends remain contained at narrow widths. Reports may be visually richer than operational pages, but must still feel like the same product.

### Authentication

Login uses a small, bordered paper without a shadow. Keep it direct and quiet; it should introduce the application's tone without adding marketing copy or decorative artwork.

## Responsive behavior

- The application must remain within the viewport at common phone widths, including 390 px.
- Page headers may stack, but title, state, and action must remain clearly related.
- Desktop tables become mobile list compositions when their columns cannot remain legible.
- Controls expand to useful touch widths without making every button full-width by default.
- Primary completion actions may become full-width on mobile when they conclude a physical workflow.
- Supporting desktop columns stack beneath the primary task in a deliberate order.
- Charts, tables, badges, and long identifiers must not create document-level horizontal scrolling.

## Interaction and motion

Selected, hovered, focused, disabled, loading, and completed states must be visually distinct. Pointer hover never carries information that keyboard or touch users cannot obtain.

Use motion only to explain a state change or floating layer. Keep transitions short and subtle. Avoid movement in dense tables and repeated operational flows.

## Accessibility

- Maintain visible keyboard focus on every interactive control.
- Do not rely on colour alone for state; pair it with a label, icon, border, or text treatment.
- Keep touch targets comfortably usable during one-handed physical workflows.
- Preserve readable contrast for muted metadata and disabled controls.
- Give icon-only controls accessible names and enough separation to prevent accidental destructive actions.

## Do's and don'ts

- **Do** create clear grouping with borders, compact whitespace, and dense content.
- **Do** attach controls and actions to the surface they affect.
- **Do** align prices, counts, dates, and locations for rapid comparison.
- **Do** make selected, confirmed, attention, and destructive states visually unambiguous.
- **Do** preserve visible context while someone moves through a list or batch.
- **Do** keep all visible controls usable by pointer and touch.
- **Do** use desktop width to show helpful relationships.
- **Do** design a deliberate mobile composition for every workflow.
- **Don't** use oversized page titles, hero sections, metric-card grids, empty whitespace, or illustrations to make a utility page feel important.
- **Don't** let a desktop table widen the mobile document.
- **Don't** leave tables, forms, or actions floating without a clear visual relationship.
- **Don't** add floating action buttons; actions belong with the content they affect.
- **Don't** use glass effects, heavy shadows, rounded-everything styling, or generic premium-SaaS ornament.

## Visual review checklist

Before considering a screen polished, verify:

- The page title, context, state, and primary action read as one header.
- Every bordered surface has one clear purpose.
- The main task is visually obvious within a few seconds.
- Comparable numbers align and use tabular figures.
- Selection is stronger than hover and distinguishable without colour alone.
- Destructive actions are explicit but do not compete with the primary action.
- Loading, empty, error, and disabled states preserve the page structure.
- The desktop layout uses width deliberately without becoming sparse.
- The mobile layout fits the viewport without document-level horizontal scrolling.
- Actions remain attached to the content they change at both desktop and mobile widths.
