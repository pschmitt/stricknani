# Rust CLI selection and differences

The Rust server keeps an operator-focused CLI. It does not reproduce the
Python `stricknani-cli` command syntax or output format. Domain CRUD and import
automation should use the authenticated versioned API rather than a second
direct-database implementation. This matrix records where the Rust product
offers a browser or API workflow and where behavior is intentionally different.

## Command inventory

| Python command group | Rust command or product equivalent | CLI parity status |
| --- | --- | --- |
| `user create` (create or update), `user list`, `promote`, `demote`, `delete`, `reset-password` | `create-user EMAIL` and `create-admin EMAIL` create accounts; duplicate creation returns an error rather than updating an existing account. `disable-user EMAIL` deactivates an account `set-avatar EMAIL PATH` and `clear-avatar EMAIL` set or remove an active user's avatar through the app's upload path, and `revoke-token ID` revokes a PAT. The browser admin page lists, creates, disables, changes roles, and resets accounts. | The Rust CLI does not expose account listing, role changes, deletion, or password reset. The browser admin workflow remains available; disable replaces delete to retain account history. |
| `project list`, `show`, `add`, `import`, `delete` | Browser project routes and `/api/v1/projects` provide list, detail, create, update, delete, and favorite operations. `/projects/import` and `POST /projects/import` provide browser and Android import flows. | No dedicated Rust CLI equivalent by design. Shell automation uses the authenticated API; no separate direct-database CLI path is planned. |
| `project export` (PDF) | No Rust PDF export endpoint or CLI command is present. The browser can print or save a project page as PDF. | The legacy generated PDF layout is not ported; browser print/save is a useful but non-equivalent alternative. |
| `yarn list`, `show`, `add`, `import`, `delete` | Browser yarn routes and `/api/v1/yarns` provide list, detail, create, update, delete, and favorite operations. Browser import creates yarns from reviewed local import drafts. | No dedicated Rust CLI equivalent by design. Shell automation uses the authenticated API. |
| `audit list` | Active administrators can browse `/admin/audit`; domain mutation handlers write native events transactionally. | No CLI listing command; the administrator browser page is the supported review workflow. |
| `ai schema`, `ai ingest` | Optional AI enhancement is available through the import flow when the feature and user selection are enabled. | Schema/debug and direct-ingest helpers are internal tooling, not a shipped compatibility surface; the reviewed import flow is the supported product workflow. |
| `api projects`, `api yarns` | Authenticated `GET /api/v1/projects` and `GET /api/v1/yarns` expose the versioned API. | No Rust command-line HTTP client. Call the documented API directly from automation. |
| `alembic ...` | Embedded SQLx migrations apply to a fresh Rust-owned database at startup. `import-snapshot` imports a separate copied legacy snapshot. | Deliberately replaced by native schema migrations and the one-time importer; Alembic compatibility is out of scope. |

Rust command documentation is in `stricknani-server --help` and the
[NixOS module guide](rust-nixos.md). The actual command dispatch is in
[`src/main.rs`](../src/main.rs); command behavior tests are in
[`tests/cli.rs`](../tests/cli.rs). Browser and API alternatives are
implemented in [`src/web.rs`](../src/web.rs),
[`src/web_accounts.rs`](../src/web_accounts.rs), and the versioned
domain routers.

## Scope and migration status

The Rust CLI selection is intentional: operational commands remain in the
binary, while user-facing domain operations use the authenticated browser and
API. This is a product-workflow replacement, not CLI parity. The Python source
and its commands remain available for the current production service until a
separate cutover is approved; this matrix does not remove or change them.

The one prominent product difference is generated project PDF export. Browser
print/save is available, but its layout and output are not equivalent to the
legacy generated document. Keep that limitation visible when assessing
acceptance rather than implying parity.
