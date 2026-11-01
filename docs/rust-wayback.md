# Rust Wayback foundation

`WaybackClient` has no usable default transport and constructing it performs no
requests. The server creates the production client only when
`RUST_WAYBACK_ENABLED=true`; the feature defaults off. Clone the client into
the single background worker so the two-request limit applies across calls.

The route or background worker supplies the original source URL to
`archive(&str)`. The client accepts only bounded HTTP(S) URLs without
credentials, fragments, non-default ports, reserved/local hostnames, or DNS
answers that include a non-public address. It sends no request to the source
host. It calls only the fixed `web.archive.org` save endpoint and
`archive.org` availability endpoint. The production transport disables
ambient proxies and automatic redirects, bounds each request to 15 seconds,
the overall operation to 30 seconds, and response data to 64 KiB. A save
redirect is accepted only when it points directly to a trusted HTTPS Wayback
snapshot URL; redirects are never followed. Errors do not contain the submitted
URL, response body, or OS/network details.

The result is `WaybackOutcome::Pending`, `Success { snapshot_url }`, or
`Failure { error }`. An accepted save with no available snapshot returns
`Pending`; if save fails, availability is still checked to preserve the Python
fallback behavior.

## Durable integration

`archive_jobs` stores the owner, entity kind and ID, original source URL, job
state, attempt count, lease, retry time, accepted-save flag, and a redacted
error code. A unique entity/source key makes enqueue idempotent. Project/yarn
create and update enqueue work in the same transaction as their domain change.
The worker claims jobs in short transactions, performs network I/O after the
claim commits, and uses a claim token so an expired worker cannot overwrite a
reclaimed job. It recovers expired leases, retries transient failures at most
five times, and bounds pending-availability polling at twelve attempts. A
source change or deleted entity supersedes a stale job; a validated snapshot
URL is saved only if the entity and source still match.

Bearer-authenticated API routes at `/api/v1/projects/{id}/archive` and
`/api/v1/yarns/{id}/archive` return an accepted state. Browser routes require
the application session and CSRF token, then redirect back to the entity.
Disabled requests are rejected without enqueueing. The server polls one job
every 30 seconds while the flag is enabled. `rust/tests/archive_service.rs`
tests auth, CSRF, feature gating, foreign-owner denial, and idempotent enqueue;
`rust/tests/archive_jobs.rs` uses fixture transports to cover persistence,
recovery, retry bounds, stale entities and workers, and save/availability
behavior without contacting the archive.

DNS validation occurs before disclosing the source URL to the archive API, but
the remote archive performs its own later fetch and DNS resolution. This client
cannot pin or control the archive's destination resolution; enabling the
feature therefore trusts the Internet Archive's fetch policy as well as the
validated public-source check here.

Tests inject both the resolver and transport. They make no external requests
and do not submit real archive jobs.
