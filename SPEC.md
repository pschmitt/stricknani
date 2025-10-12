# 🧶 Stricknani — Specification

> “A Mealie for knitting.”

Stricknani is a self-hosted web app for managing knitting projects — think **[Mealie](https://github.com/mealie-recipes/mealie)**, but for knitters.
It lets users organize projects with photos, gauge calculations, and notes in a clean, modern interface.

---

## 1. Purpose & Vision

- Provide a modern, private, self-hosted space for knitters to document their projects.
- Recreate the quality and usability of *Mealie*, adapted for fiber arts.
- Focus on usability, reproducibility, and maintainability.

---

## 2. Guiding Principles

- 🧠 **Don’t re-invent the wheel.**
  Always use established Python libraries where possible (e.g., FastAPI auth/session middlewares, SQLAlchemy ORM, Jinja2, existing Markdown renderers, etc.).

- ⚡ **FastAPI + HTMX.**
  Use FastAPI for API + server-side rendering (Jinja2 templates).
  Use HTMX for progressive enhancement and dynamic interactivity.

- 🪶 **Modern but simple.**
  Avoid SPA frameworks. Keep it responsive, clean, and keyboard-friendly.

- 🧩 **Reproducible + portable.**
  Built and tested through Nix + Docker, pushed to GHCR via CI.

- 🔒 **Privacy-respecting.**
  No external analytics, no external asset CDN.

---

## 3. Core Use Cases

1. Create, update, and delete knitting projects.
2. Attach photos, diagrams, and notes.
3. Calculate stitch counts via the gauge tool.
4. Browse and filter projects.
5. Authenticate as a local user.

---

## 4. Functional Requirements

### 4.1 Project Data

| Field | Type | Required | Description |
|-------|------|-----------|--------------|
| Name | string | ✓ | Project title |
| Category | enum | ✓ | Pullover, Jacke, Schal, Mütze, Stirnband (extensible) |
| Yarn | string |  | Yarn type/brand |
| Needles | string |  | Needle size/type |
| Gauge | int,int |  | Stitches + rows per 10 cm |
| Instructions | markdown |  | Rendered safely |
| Diagrams | images |  | Optional |
| Photos | images |  | Multiple uploads |
| Comment | text |  | Free-form note |
| Owner | user ref | ✓ | Creator |
| Created/Updated | timestamp | ✓ | Auto-set |

Operations:
- CRUD by owner.
- Filter and sort by category, date, or name.
- Multi-image upload with thumbnail gallery.

---

### 4.2 Gauge Calculator

- Input: pattern gauge, user gauge, and target width.
- Output: adjusted stitch & row count, rounded intelligently.
- Example:
  Pattern = 20 sts/10 cm, User = 18 sts/10 cm, Target = 50 cm → ≈ 90 sts.
- Inline in project form and available standalone.

---

### 4.3 Auth

- Local user accounts only (email + password).
- Hashed with bcrypt/argon2.
- Session/cookie auth with CSRF protection.
- Rate-limited login attempts.

---

### 4.4 Comments & Content

- Markdown rendering (sanitized HTML).
- Optional comment field per project.
- Photos and diagrams displayed in a unified gallery.

---

## 5. Non-Functional Requirements

| Area | Requirement |
|------|--------------|
| **UI/UX** | Modern, responsive layout; light/dark mode; WCAG AA accessibility. |
| **Performance** | Fast initial load (<200 KB critical path). |
| **Security** | HTTPS-only, CSP, secure cookies, validated uploads. |
| **Extensibility** | Modular design for additional features (inventory, patterns). |
| **Reliability** | Versioned DB migrations, documented backup process. |

---

## 6. Architecture Overview

- **Framework:** FastAPI + Jinja2 + HTMX
- **Database:** SQLite (dev), PostgreSQL (optional)
- **Auth:** built on standard FastAPI middlewares
- **Storage:** local filesystem for user uploads (later S3-compatible option)
- **Frontend:** Tailwind-styled templates with HTMX for interactivity
- **Tests:** pytest for unit + integration

---

## 7. Deployment & Operations

### 7.1 Docker
- Single container exposing `$PORT` (default 7674)
- Config via env vars (`DATABASE_URL`, `SECRET_KEY`, `MEDIA_ROOT`, `ALLOWED_HOSTS`)
- `/healthz` endpoint for liveness
- Graceful shutdown (SIGTERM → ≤10 s)

### 7.2 CI/CD
- Build, lint, test, and push to **GHCR** at
  [`ghcr.io/pschmitt/stricknani`](https://ghcr.io/pschmitt/stricknani)
- Tags: `<git-sha>` and `latest`
- CI must run `just check` and `nix flake check`

---

## 8. Developer Experience

### 8.1 Toolchain

| Tool | Purpose |
|------|----------|
| **Python 3.13+** | Language runtime |
| **uv** | Dependency management |
| **pytest** | Unit & integration tests |
| **ruff** | Linter + formatter |
| **mypy (strict)** | Type checking |
| **just** | Task runner |
| **nix** | Reproducible builds |

### 8.2 Linting Policy

Linting is **mandatory** in CI.
- Ruff enforces style, formatting, and common error checks.
- MyPy runs in strict mode (`--strict`) for full typing discipline.
- All code must be Ruff- and MyPy-clean before merge.
- Optional pre-commit hooks run `ruff --fix` and `mypy`.

### 8.3 justfile Tasks (expected)

| Command | Description |
|----------|--------------|
| `just setup` | Setup uv environment |
| `just run` | Run dev server with reload |
| `just lint` | Run Ruff + MyPy |
| `just fmt` | Format code |
| `just test` | Run pytest |
| `just build-image` | Build Docker image |
| `just push-image` | Push image to GHCR |
| `just check` | Aggregate lint + test + flake checks |
| `just demo-data` | Seed demo user and sample projects |

### 8.4 Nix Flake

- `packages.${system}.stricknani` → runnable app
- `packages.${system}.stricknani-docker` → Docker image
- `devShells.${system}.default` → uv + just + pytest + ruff
- `checks` → build + tests + flake validation
- Reproducible build required.

---

## 9. Testing & CI Validation

- Unit: gauge math, auth, models.
- Integration: CRUD + uploads + session flows.
- CI runs:
  1. `ruff check .`
  2. `mypy .`
  3. `pytest -q`
  4. `nix flake check`
- Coverage ≥ 80 %.
- Image verification: start container → `/healthz` returns 200.

---

## 10. Information Architecture & UX

### 10.1 Navigation

| Section | Content |
|----------|----------|
| **Projects** | List + filters |
| **New Project** | Form + inline gauge tool |
| **Gauge Calculator** | Standalone tool |
| **User Menu** | Login / Signup / Logout |
| **Footer** | Version + Privacy note |

### 10.2 Screens

- **Projects List:** cards with preview image, name, category, date.
- **Project Detail:** title, metadata, rendered instructions, photos gallery, comments.
- **New/Edit Project:** form with drag-drop uploads and inline calculator.
- **Auth:** login/signup/logout pages.

---

## 11. Accessibility

- Important: the smartphone is the primary device for knitters. We need a responsive, touch-friendly design.
- Full keyboard navigation, visible focus indicators.
- ARIA labels for interactive components.
- Mandatory alt text for all uploaded images.

---

## 12. Observability

- Structured JSON logs (request_id, user_id, route, status, latency).
- Error tracebacks logged; no external telemetry.
- Optional metrics: request count, latency histogram, media size totals.

---

## 13. Configuration & Environments

| Env | Description |
|-----|--------------|
| **Dev** | SQLite, local media, `.env` file for secrets |
| **Prod** | Configurable DB URL, media volume mount, HTTPS reverse proxy |
| **Flags** | `FEATURE_SIGNUP_ENABLED` (toggleable) |

---

## 14. Data Model (logical)

- `User` 1–N `Project`
- `Project` 1–N `Image` (type: photo | diagram)
- `Project` 1–N `Comment`
- Indices: `(owner_id, created_at)`, `(name)`

---

## 15. Acceptance Criteria (v0.1 milestone)

- New user can register, log in, create project (with photo), view details, log out.
- Gauge calculator produces correct results for reference cases.
- List view filters by category and search term.
- `just check` and `nix flake check` pass in CI.
- GHCR image builds and responds at `/healthz` = 200.
- Basic accessibility tests pass (keyboard nav + contrast).

---

## 16. Roadmap (post-v0.1)

- Multi-user sharing.
- Yarn stash tracking.
- Pattern import/export.
- Mobile editing view.
- Optional integrations with community APIs.

---

## 17. Licensing & Versioning

- **License:** GPL-3.0-only
- **Versioning:** *Leading-zero* incremental scheme, not semantic.
  Examples:
  - `v0.1`, `v0.2`, `v0.3` → sequential milestones.
  - `v1.0` only when stable maturity is reached.
  - No semantic meaning to minor digits.

---

## 18. Repository

**URL:** [github.com/pschmitt/stricknani](https://github.com/pschmitt/stricknani)
**Maintainer:** Philipp Schmitt (@pschmitt)
**License:** GPL-3.0-only
**Spec version:** 0.4

---

*End of Specification*
