# TODO

Execution-oriented backlog for Stricknani.

## Status Legend

- `todo`: not started
- `wip`: in progress
- `blocked`: waiting on a dependency/decision
- `done`: completed and merged

## Priority Rubric

- `P0`: bug or production risk, do first
- `P1`: high product impact
- `P2`: medium impact / tech debt
- `P3`: quality-of-life or cleanup

## Rules

- If a task is actively being worked on, mark it `wip` immediately.
- Keep only actionable, implementation-ready tasks here.

## Now

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T3 | P2 | wip | ux | Implement keyboard shortcuts for project/yarn list and view pages |

## Next

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T6 | P0 | wip | data-integrity | Make DB/file operations atomic-ish: avoid deleting files before successful DB commit |
| T7 | P0 | todo | auth/security | Enforce `is_active` in auth resolution so disabled users lose access immediately |
| T8 | P1 | todo | architecture | Split oversized route modules into route/controller + service layers |
| T9 | P1 | todo | import | Consolidate duplicated import/image-dedupe logic into a single reusable pipeline |
| T10 | P1 | todo | projects | Extract shared create/update project import workflows to common services |
| T11 | P1 | todo | i18n/web | Remove per-request global Jinja i18n mutation to avoid cross-request language bleed |
| T12 | P1 | todo | web/templating | Remove hidden DB/auth lookups from `render_template`; require explicit `current_user` context |
| T13 | P2 | todo | reliability | Replace broad exception swallowing in import/parse paths with explicit error handling |
| T14 | P2 | todo | security | Simplify and harden CSRF token flow (single source of truth for token location) |
| T15 | P2 | todo | data-model | Add DB invariant for a single primary yarn image and simplify fallback logic |
| T5 | P2 | todo | ux | cropping of pictures via photoswipe (only when on the edit pages!) |
| T1 | P3 | todo | frontend/build | Replace runtime Tailwind with prebuilt static CSS bundle |


## Done

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T4 | P2 | done | docs | Reorganize project documents for faster agent onboarding and maintenance |
| T2 | P3 | done | ai/import | Add OpenRouter and Groq support for AI imports |


## Task Details

### T5: cropping of pictures via photoswipe (only when on the edit pages!)

- **Area**: ux
- **Priority**: P2
- **Status**: todo

### T6: Make DB/file operations atomic-ish: avoid deleting files before successful DB commit

- **Area**: data-integrity
- **Priority**: P0
- **Status**: wip
- **Notes**:
  - For delete endpoints, commit DB changes first, then perform best-effort filesystem cleanup.
  - For import/dedupe flows, avoid irreversible file deletion before transaction success.

### T4: Reorganize project documents for faster agent onboarding and maintenance

- **Goal**: Separate operational instructions from product spec/history and add a clear document index.
- **Exit Criteria**:
  - `AGENTS.md` is concise and action-oriented.
  - Long-lived product/spec content is moved under `docs/`.
  - `README.md` points to the new doc structure.

### T3: Implement keyboard shortcuts for project/yarn list and view pages

- **Primary Files**: `stricknani/static/js/app.js`, `stricknani/templates/projects/list.html`, `stricknani/templates/yarn/list.html`, `stricknani/templates/projects/view.html`, `stricknani/templates/yarn/view.html`
- **Description**:
  - `D` on view pages: open delete project/yarn dialog
  - `c` on list pages: create new project/yarn
  - `n`/`p` on view pages: navigate to next/previous project/yarn (same behavior as swipe)
  - `e` on view pages: edit current project/yarn
  - `i` on list pages: open import dialog
- **Implementation Notes**:
  - Keep project and yarn UX consistent
  - Avoid inline translation strings in templates; pass config as JSON

### T1: Replace runtime Tailwind with prebuilt static CSS bundle

- **Primary Files**: `stricknani/templates/base.html`, `justfile`, `flake.nix`
- **Description**: Replace runtime Tailwind-in-browser usage with a prebuilt static CSS bundle for performance and easier CSP hardening.

### T2: Add OpenRouter and Groq support for AI imports

- **Verification Notes**:
  - Provider selection and defaults are implemented in `stricknani/utils/ai_provider.py`.
  - Coverage exists in `tests/test_ai_provider.py` and `tests/test_ai_ingest.py`.
