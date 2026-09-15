---
version: alpha
name: Personal operations workspace
description: A dense, calm desktop workspace for people doing real-world, repetitive operational work.
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

This is a personal operations workspace: closer to a well-organised workbench than a consumer product landing page. It helps one person work through real inventory, orders, imports, lists, and reviews quickly and accurately. The interface should be quiet enough to support long sessions and dense enough that the user rarely needs to drill into a second screen just to keep working.

The visual reference is a good desktop utility: an accounting ledger, a photo contact sheet, or a warehouse picking desk. Information is arranged in clear, modestly bordered regions. The screen does useful work immediately. There is no dashboard theatre, promotional hero space, or decorative brand moment competing with the task.

Desktop is the primary context. Mobile must remain usable for physical workflows, but it should simplify and stack the same hierarchy rather than becoming a different product.

## Colors

The default state is neutral. White canvas and surface, dark graphite text, and restrained gray rules make dense information easy to scan. A faint blue selection state identifies the current row without making the page look blue.

- **Primary** is for ordinary navigation, links, and one normal forward action.
- **Confirmed** is reserved for a verified or successfully completed state, and for the explicit action that produces it. It is not a confidence colour.
- **Warning** means a human should inspect something before committing it. Use it for low confidence, incomplete review, or an actionable anomaly; do not use it for ordinary metadata.
- **Danger** is for destructive or irreversible actions only.
- Avoid gradients, glows, large colour fields, and decorative colour coding. Colour carries operational meaning, not ambience.

## Typography

Use the system sans serif at practical sizes. Page titles are compact and strong, not oversized. Body text is legible at a dense working size. Metadata is smaller and muted, but never so faint that it becomes hard to read in a long session.

Use tabular figures for dates, prices, counts, positions, and other values that benefit from vertical scanning. Prefer concise sentence-case labels. Uppercase is limited to very small structural labels when it helps distinguish context from content.

## Layout

Use the available desktop width. Do not centre ordinary operational pages in a narrow marketing-style column. Start each page with a compact title and, where useful, a short muted description or state. Put controls alongside the content they affect.

Create hierarchy with a small number of stable regions: a page header, a primary working surface, and supporting panels. Use the spacing scale consistently: 8 px inside tight controls, 16 px between related controls or panels, and 24–32 px between page-level groups.

For comparison and review work, keep the source evidence fixed and place the changing candidate beside it. Keep a persistent, compact queue when the user is moving through a batch. Bound large media so it supports the decision without pushing controls below the fold.

## Elevation & Depth

Depth comes from 1 px borders and surface grouping, not shadows. Papers are flat white surfaces on a white canvas; their border and spacing create the boundary. Shadows are exceptional and may be used only where a floating layer needs separation, such as a menu, popover, or login card.

## Shapes

Use modest 4 px radii for buttons, inputs, selected rows, and small controls. Use 8 px radii for papers and larger grouped surfaces. Avoid pills unless the element is a compact status badge. Keep outlines thin and neutral.

## Components

**Papers and panels:** Use `Paper withBorder` to group a meaningful unit of work: a table, a form, a comparison, or an action bar. Do not wrap every individual text item in a card. A panel needs a job.

**Tables and queues:** Prefer dense rows, clear columns, and a visible current selection. Selection is a pale blue fill with a restrained outline. Confirmed rows use a small teal indicator; unreviewed rows remain gray; rows requiring attention use orange. Keep status meaning consistent across every page.

**Buttons and actions:** Every action area needs a visible boundary or clear relationship to the content it changes. Group navigation together and group commit/destructive actions together inside an action bar. There is one obvious primary action; destructive actions are quieter but never hidden. Disable an action once its state transition is complete.

**Forms and search:** Labels sit above controls. Use direct, always-visible search where correction is a normal part of the workflow; do not hide it behind a modal or an extra trigger. Show a short actionable error near the affected control.

**Images and comparisons:** Preserve the uploaded or physical source image. Candidate changes must only update the candidate side. Use adjacent comparison columns and, when needed, a dedicated details column for high-value discriminators. Images use bounded height and `object-fit: contain`; they never expand merely to fill width.

**Keyboard use:** Keyboard shortcuts accelerate a visible workflow but do not replace visible controls. Avoid a permanent keyboard cheat sheet unless the page is a dedicated expert tool. Focus shortcuts on navigation, confirmation, deletion, and search, and never intercept typing in an input.

## Do's and Don'ts

- **Do** design for a person concentrating on repeated physical work for an hour.
- **Do** make completion, review-required, selected, and destructive states unambiguous.
- **Do** preserve context while moving through queues, lists, and batches.
- **Do** use clear borders, compact whitespace, and dense content before adding decorative treatment.
- **Do** ensure every action is available by pointer or touch, even when a keyboard shortcut exists.
- **Don't** auto-confirm or auto-commit based on confidence, suggestion, or an optimistic default.
- **Don't** use oversized page titles, hero sections, metric cards, empty whitespace, or illustrations to make a utility page feel important.
- **Don't** use floating action buttons. Actions belong to the data, form, review, or action bar they affect.
- **Don't** add gradients, glass effects, heavy shadows, rounded-everything styling, or generic “premium SaaS” ornament.
- **Don't** let a modal interrupt a correction flow that belongs inline with the current task.
