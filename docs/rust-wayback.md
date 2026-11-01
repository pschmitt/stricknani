# Rust Wayback foundation

`WaybackClient` is disabled by default (`WaybackClient::default()`), and
constructing it performs no requests. The configuration owner should build it
with the archival feature flag; keep that flag default-off. Clone one client
into shared state so the two-request limit applies across callers.

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
fallback behavior. The service deliberately does not persist state or start
background work. A route should return existing project/yarn data and enqueue
archival work only when enabled, rather than holding a user request open.

## Durable integration recommendation

When the feature is wired into the server, prefer a durable outbox/job table
over detached request tasks. A compact table can store `id`, entity kind and
ID, source URL, state (`pending`, `running`, `archived`, `failed`), attempt
count, `next_attempt_at`, `lease_until`, last redacted error code, and created/
updated timestamps. Add a unique active-job key for entity plus normalized
source URL so retries and concurrent edits do not create duplicate saves.
Workers should claim jobs transactionally, use bounded backoff, update the
snapshot URL only after validating that it belongs to `https://web.archive.org/web/`,
and clear or supersede jobs when the entity's source URL changes. Do not
persist query-bearing URLs in logs or error messages; storing a source URL in
the database is a product/privacy decision for the future schema owner.

The Rust route should resolve the owned entity and authorize its user before
requesting any work. An explicit retry action should create or requeue a job
only for an enabled feature. The feature flag should remain false by default,
and the route must report disabled state without contacting the archive.

DNS validation occurs before disclosing the source URL to the archive API, but
the remote archive performs its own later fetch and DNS resolution. This client
cannot pin or control the archive's destination resolution; enabling the
feature therefore trusts the Internet Archive's fetch policy as well as the
validated public-source check here.

Tests inject both the resolver and transport. They make no external requests
and do not submit real archive jobs.
