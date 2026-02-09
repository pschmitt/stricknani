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
