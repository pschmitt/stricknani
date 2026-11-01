# T120 implementation checkpoint

The new web UI is in progress on branch `rust`. The implementation brief is
`WEB-UI-REWRITE.md`; shared design decisions are in `web-ui-design.md`.
The existing Rust preview remains usable while new routes are built at `/app`.

## First wave

- Luna foundation: SvelteKit/TypeScript static build, shared components,
  responsive shell, typed API client and actual browser login/session flows.
- Luna API: cookie-authenticated v2 bootstrap/login/logout and focused security
  tests, leaving Android bearer v1 independent.
- Luna packaging: bounded static frontend router, strict CSP manifest handling,
  reproducible asset package, container and frontend CI integration.
- Coordinator: shared interfaces, router wiring, ownership conflict resolution,
  code review and acceptance evidence.

These are active implementation scopes. No new frontend feature has yet passed
integration acceptance. Remaining work includes project/yarn workflows, editing,
uploads, imports, settings/admin, translation completion, browser testing,
performance measurements and isolated preview rollout.

Production continues to serve Python with separate storage. Preserve the user's
uncommitted `REWRITE.md` and unrelated primary-checkout changes.
