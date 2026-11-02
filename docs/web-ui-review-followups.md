# Web UI review follow-ups

This records Claude Code's 2026-11-02 review and the resulting work plan.
The observations below were not browser-reproduced in this pass. “Source confirmed”
means the relevant structure or style exists in the current source; it does not
confirm the reported visual effect on every viewport or dataset.

## Reader and project content

**Source confirmed:** the project reader puts pattern and notes, then every step,
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
