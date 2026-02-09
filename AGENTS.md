# Agent Instructions

Use this file as the operational source of truth for working in Stricknani.

## Read Order (for future agents)

1. `AGENTS.md` (this file) for workflow and hard rules
2. `TODO.md` for active execution queue
3. `README.md` for runtime/configuration
4. `docs/spec-and-implementation.md` for full product spec and historical implementation context

## Critical Requirements

### Vendored Web Dependencies

- All new frontend dependencies (JS/CSS) must be vendored via `vendir.yml` (no new CDN links).
- After changing `vendir.yml`, run `just vendor-sync` and commit `vendir.lock.yml` and `stricknani/static/vendor/**`.
- Prefer vanilla JS for new UI behavior. Add JS dependencies only when HTMX/browser APIs are insufficient.

### Translations (UI text)

- Add new strings to both:
  - `stricknani/locales/en/LC_MESSAGES/messages.po`
  - `stricknani/locales/de/LC_MESSAGES/messages.po`
- Avoid multiline strings in templates (`{% trans %}` / `{{ _(...) }}`) to prevent brittle `msgid`s.
- After template text changes:
  - `just i18n-update`
  - `just i18n-compile`
  - `just i18n-check`

### Database Migrations

- Always create a migration when SQLAlchemy models change.
- Create migration:
  - `uv run alembic -c stricknani/alembic.ini revision -m "description_of_change"`
- Verify migration runs:
  - `uv run alembic -c stricknani/alembic.ini upgrade head`
- Migrations live in `stricknani/alembic/versions/` and must be committed.

### Code Quality

- Zero tolerance for lint/format issues: fix immediately.
- TODO management:
  - When starting a task from `TODO.md`, mark it `wip` immediately.
  - If asked to "clean up", remove all `done` tasks from `TODO.md`.
- Required checks:
  - Lint: `uv run ruff check .`
  - Type check: `uv run mypy .`
  - Format: `just fmt`
  - Tests: `uv run pytest -v`
  - Nix lint: `statix check`
- Mandatory after any code change:
  - `uv run pytest tests/test_health.py`
- Trim trailing whitespace in edited files.

### UI Consistency (Projects & Yarns)

- Keep project and yarn pages visually and behaviorally consistent.
- Apply layout/styling changes to both domains.

### Release/CI Parity

- `just check` runs lint, tests, and i18n checks.
- When touching build/deploy/release paths, also run:
  - `nix flake check`
  - `just build-image`

## Handy Commands

- Setup: `just setup`
- Dev server: `just run`
- Lint: `just lint`
- Format: `just fmt`
- Tests: `just test`
- i18n Update: `just i18n-update`
- i18n Compile: `just i18n-compile`
- Full checks: `just check`

## Documentation Layout

- Operational rules: `AGENTS.md`
- Work queue: `TODO.md`
- User/developer runbook: `README.md`
- Product spec + implementation snapshot: `docs/spec-and-implementation.md`
