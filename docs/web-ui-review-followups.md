# Web UI review follow-ups

This records Claude Code's 2026-11-02 review and the resulting work plan.
The user subsequently confirmed both review parts as the implementation queue,
with each ordered step verified before the next. The chevron addendum replaces
the previous browser-history behavior with logical-parent navigation.
The observations below were not browser-reproduced in this pass. “Source confirmed”
means the relevant structure or style exists in the current source; it does not
confirm the reported visual effect on every viewport or dataset.

## Reader and project content

**Source confirmed at the review baseline:** the project reader put pattern and notes, then every step,
in one column beside a narrower details column. The source uses a flexible first
column and a 15–20rem second column; the layout collapses at 900px. Pattern,
notes, and steps can contain rich HTML. See [ProjectReader.svelte](../web/src/lib/features/projects/ProjectReader.svelte#L675),
especially its [step list and progress display](../web/src/lib/features/projects/ProjectReader.svelte#L707)
and [column rules](../web/src/lib/features/projects/ProjectReader.svelte#L1391).

**Review observation; validate on representative long projects:** the long
reading column can dominate the page while the materials/details rail remains
short, and mobile nesting may leave a narrow text measure. The review also
reported repeated pattern/step content, the cover appearing late in the page,
and note images rendered at full size. Check these against real examples before
changing the information order or media sizing.

**Selected by the user:** a single reading column capped near 70 characters,
with a compact cover and key facts near the start and materials in a collapsible
section. Implementation and mobile filter refinements are tracked as T120.15.

The first checkpoint is implemented in
[`d00c66c`](https://github.com/pschmitt/stricknani/commit/d00c66c3b983b63bc947d9dce194b69ae2060bb8):
one reading column, compact cover/facts, keyboard-operated Materials disclosure,
and compact phone filters. Canonical-package core browser, health and print
checks passed, with EN/DE phone and desktop layout assertions. The administrator
journey's owner-yarn-card check now opens Materials first; its complete rerun
against the canonical package passed. The preview rollout is in progress.
Avoid interpreting this as completion of the later pattern-presentation work.

Do not deduplicate pattern text or steps automatically. Step IDs are stable
anchors (`step-${step.id}`) used by navigation, and progress ordinals are based
on the ordered step list. Preserve IDs, saved progress, and shared links while
checking whether any content is genuinely redundant. The current rich-text
render points are visible in [ProjectReader.svelte](../web/src/lib/features/projects/ProjectReader.svelte#L682);
any later change must retain the existing sanitization, CSP, and owned-media
boundaries.

**Needs reproduction:** a resume at `#step-3` reportedly displayed “step 1 of 2”
with an offset heading. Verify this on a known multi-step project and determine
whether the ordinal, target, or scroll position is wrong before filing a fix.
The current ordinal and stable element IDs are at [the step navigation](../web/src/lib/features/projects/ProjectReader.svelte#L734).

## Libraries, yarns, and forms

These are review observations; inspect at the stated viewport and content before
changing component contracts:

- At 390px, project-library filters reportedly use two columns and truncate a
  category label. The source has responsive filter grids in [ProjectLibrary.svelte](../web/src/lib/features/projects/ProjectLibrary.svelte#L420).
- Yarn cards reportedly use oversized no-photo placeholders and clip long
  names. Check both photo and no-photo cards in [ResourceCard.svelte](../web/src/lib/components/ResourceCard.svelte#L1).
- The review reported imported yarn records named after needles, hooks and
  buttons. Investigate classification in [import_service.rs](../rust/src/import_service.rs)
  and the source data before changing the model. The yarn detail's recommended
  needle field is a separate, legitimate field.
- Login alignment and decorative contrast were reported; the review requested
  clearer examples, an expander cue, and stronger optional-field text. Review
  [SignInForm.svelte](../web/src/lib/components/SignInForm.svelte#L1) and
  [GaugeCalculator.svelte](../web/src/lib/features/gauge/GaugeCalculator.svelte#L198)
  at phone and desktop widths, including keyboard and contrast checks.
- A gauge example reportedly exposes raw `&nbsp;` or a Markdown image. Locate
  the exact rendered example and its source before changing escaping or
  Markdown handling; no such display is confirmed by this static pass.
- Yarn photo management has a dedicated empty state and thumbnail grid in
  [YarnPhotoManager.svelte](../web/src/lib/features/yarns/YarnPhotoManager.svelte#L274).
  Review the reported placeholder size and cropped names with actual no-photo
  and long-name records.
- Generic resource cards reportedly lack distinct visual identity. Treat this
  as a design proposal, not a defect; keep shared card navigation, accessible
  names, and owner-scoped media behavior intact.

## Reproduction queue and proposal batches

### Ordered consistency work

Claude's second measured pass covered 16 routes at 1440×900 and 390×844 on the
deployed preview. These are reviewer measurements, not a fresh run in this doc:

- Classic scrollbars caused 15px horizontal shifts after data arrived; headers
  varied in position and desktop size (38.4–52.8px).
- Button roles had fragmented heights, borders, radii, weights and states.
  Long yarn-row actions reached 92–140px tall, including vertically wrapped text.
- Project/yarn back links, destructive actions, photo editing, favorites and
  Cancel behavior differed. Editor Save sat below a long form beside Delete.
- Input sizes and shapes differed; several text controls were below 16px.

Follow T120.16–T120.21 in the order recorded in
[the rewrite brief](../WEB-UI-REWRITE.md#ordered-review-work-queue), then finish
T120.15's reading presentation. Re-run the shared
[ui-audit inventory and motion checks](https://github.com/pschmitt/nixos-config/tree/7a9f685dc/home-manager/devel/skills/ui-audit)
after each step. Expected checks include stable H1 positions per page type,
consistent button signatures for declared variants/sizes, no wrapped labels
above 52px, hover feedback, and no scrollbar-induced 15px shifts.

Header navigation must use one logical-parent map. Top-level sections have no
chevron; its reserved slot keeps the logo at the same x position on lists and
details at both widths. Deep links stay in the app, targets do not overlap the
logo link, and parent navigation retains the existing dirty-form guard.

The shared-header and parent-navigation source checkpoint is
[`e3ee284`](https://github.com/pschmitt/stricknani/commit/e3ee284), with the
updated administrator browser journey in
[`2b1cdaa`](https://github.com/pschmitt/stricknani/commit/2b1cdaa95aea3f1b471a5528cf108bc4a04692e0).
Prettier, Svelte-check (zero errors/warnings) and 168 unit cases pass. The
canonical package builds; browser integration and viewport inventory are still
running. Print preserves the visible title and full-resolution media while
hiding header actions. Browser unload confirmation remains separate from
guarded in-app navigation.

### Additional source and visual checks

1. **Reader layout/content:** reproduce the wide-screen imbalance, mobile text
   measure, repeated content, cover order, and note-image sizing. Bring the
   selected single-column design into a scoped implementation and review.
2. **Resume and anchors:** reproduce the `#step-3` report with saved progress;
   preserve stable step IDs, ordinal semantics, scroll targeting, and copied
   links unless evidence identifies a specific failure.
3. **Library and yarn cards:** verify the 390px filter/category behavior and
   no-photo/long-name card cases. Propose compact layout changes without
   changing filtering, saved data, or resource semantics.
4. **Login and gauge polish:** reproduce alignment, contrast, example rendering,
   expander affordance, and optional-field readability. Keep sanitization and
   accessible labels intact.

The reported screenshot header gap also needs a fresh reproduction. No browser
checks or visual tests were run for this plan. Implement authorized UI batches
with scoped review. Data repair, migrations and production cutover remain
separate decisions.

## Further review requirements

The subsequent form, feedback, icon and branding reviews extend the ordered
queue; they do not authorize changing stored content or replacing explicit
saves with autosave.

- **Buttons and tiles (T120.17):** one icon token family (20px default, 18px
  compact, 22–24px tile, consistent stroke), one Create/More tile layout, and
  one sidebar icon treatment. Align tile icons with the title rather than the
  two-line block. Replace repeated “Available now” text with useful descriptions.
  Compare icon/title centers and prevent unequal mobile tile heights.
- **Forms (T120.19–21):** one section/required-marker/media-grid system; consistent
  Cancel and destructive-action placement. Search yarns before showing the stash;
  merge duplicate selected/added lists. Support existing categories and inline
  creation. Clarify when new-project photos become available and which media
  changes take effect immediately. Use desktop-appropriate select/drop hints.
  Group yarn identity and properties; show units beside numeric fields. Preserve
  custom weight strings and nonnumeric needle recommendations. Provide a safe
  pattern/notes preview without bypassing sanitization or CSP.
- **Feedback (T120.20):** a shared viewport snackbar above phone navigation or in
  a desktop corner, one message at a time. Announce success after the server
  acknowledges the write; success dismisses after about four seconds, errors
  stay until dismissed and offer retry only when safe. Keep immediate pending
  feedback and prevent duplicate writes. Every CRUD/media action needs feedback.
  Keep errors and form status visible beside sticky Save/Cancel, focus invalid
  fields, support Ctrl/Cmd+S, and preserve drafts after failure. Prefer consistent
  create/save-to-detail navigation with H1 focus. Do not focus Skip to content.
  Use shared in-app confirmation dialogs; browser unload protection must use
  `preventDefault`/`returnValue`, never a nested `window.confirm`. Keep navigation
  focus rings visually distinct from the active destination. Offer Undo only
  where an actual restoration API exists.
- **Login branding (T120.22):** use the existing mascot and bundled branding.
  A larger desktop illustration and compact phone hero should fit the viewport
  without an empty panel or duplicate wordmark. Respect reduced motion and keep
  the form plain and readable. Verify any font against the branding source and
  include its license rather than adding a CDN dependency.
- **Motion (T120.23):** purposeful, restrained feedback using built-in primitives,
  typically 150–250ms; no repeated section entrances or idle loops. Reduced
  motion disables movement. Button pressed states, favorite changes, dialogs,
  disclosures and reordered steps may communicate state changes. Larger page
  morphs, mascot animation, Knit mode and row counters remain proposals for a
  separate scope decision; do not delay the consistency fixes for them.

Reference behaviors: one-column recipe reading, hierarchical Up navigation,
viewport snackbars, shared button/header components, and a mascot-led sign-in
page. Keep Stricknani's identity. Validate each adopted behavior using long
synthetic content at phone and desktop widths, including slow, failed and
offline writes. Pending feedback should appear promptly; a slow request must
never produce an early false success.
