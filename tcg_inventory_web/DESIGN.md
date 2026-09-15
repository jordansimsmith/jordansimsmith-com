---
version: alpha
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

The visual character is a calm, capable desktop utility: a well-organised workbench, accounting ledger, or photo contact sheet. It is dense enough for sustained use, but never cramped or noisy. Hierarchy comes from careful spacing, flat paper-like grouping, and restrained colour rather than dashboard theatre or decorative brand moments.

## Colors

The default state is neutral. White canvas and surfaces, dark graphite text, and quiet gray rules make dense information easy to scan. Pale blue identifies selection without making the interface feel blue.

- **Primary** is for navigation, links, and the normal forward action.
- **Confirmed** is for a verified or completed state and its explicit action.
- **Warning** identifies an item needing attention; it is not ordinary metadata.
- **Danger** is for destructive or irreversible actions only.
- Avoid gradients, glows, large colour fields, and decorative colour coding. Colour communicates state, not ambience.

## Typography

Use the system sans serif at practical sizes. Page titles are compact and strong, not oversized. Body text is comfortably legible at a dense working size. Metadata is smaller and muted but remains readable in a long session.

Use tabular figures for dates, prices, counts, positions, and other values that benefit from vertical scanning. Prefer concise sentence-case labels. Reserve uppercase for very small structural labels where it genuinely improves separation.

## Layout

Use the available desktop width; do not centre normal application pages in a narrow marketing-style column. Begin a page with a compact title and, where helpful, a short muted description or status. Keep controls adjacent to the content they affect.

Create hierarchy with a small number of stable regions: a page header, primary working surface, and supporting panels. Use 8 px inside tight controls, 16 px between related controls or panels, and 24–32 px between page-level groups. On narrow screens, stack the same regions while preserving their order and visual relationship.

## Elevation & Depth

Depth comes from 1 px borders and surface grouping, not shadows. Papers are flat white surfaces on a white canvas; borders and spacing establish their boundary. Shadows are reserved for floating layers that need separation, such as menus, popovers, or a login card.

## Shapes

Use 4 px radii for buttons, inputs, selected rows, and small controls. Use 8 px radii for papers and larger grouped surfaces. Avoid pills except for compact status badges. Keep outlines thin and neutral.

## Components

**Papers and panels:** Use `Paper withBorder` to group a meaningful unit of work: a table, form, comparison, action bar, or supporting detail. Do not wrap every text item in a card; every panel needs a clear job.

**Tables and lists:** Prefer dense rows, clear columns, and a visible selection. A selected row uses pale blue with a restrained outline. Use a small teal indicator for confirmed state, gray for neutral state, and orange for attention. Keep these meanings consistent.

**Buttons and actions:** Give every action area a visible boundary or clear relationship to the content it changes. Group related actions together, with one obvious primary action. Destructive actions stay quieter but are never hidden.

**Forms and search:** Place labels above controls. Use direct, always-visible search when correction is a normal part of a page. Place short, actionable errors near the affected control.

**Images:** Bound images so they support the surrounding information instead of dominating the screen. Use `object-fit: contain` for card imagery and preserve its full shape within a neutral frame.

## Do's and Don'ts

- **Do** create clear grouping with borders, compact whitespace, and dense content.
- **Do** make selected, confirmed, attention, and destructive states visually unambiguous.
- **Do** preserve visible context while someone moves through a list or batch.
- **Do** keep all visible controls usable by pointer and touch.
- **Don't** use oversized page titles, hero sections, metric-card grids, empty whitespace, or illustrations to make a utility page feel important.
- **Don't** add floating action buttons; actions belong with the content they affect.
- **Don't** use glass effects, heavy shadows, rounded-everything styling, or generic premium-SaaS ornament.
