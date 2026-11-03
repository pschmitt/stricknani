# Android API v2 migration

Track server work as T121 in [TODO.md](../TODO.md); Android client work is
SNA-87 in [its task queue](../android/TODO.md). Codex coordinates backend
implementation and API/data review with scoped Luna agents. Claude owns the
Android and web UI, client adaptation, and verified UI preview deployments.
Send the client owner each tested server commit so migration can proceed by
area.

API v1 remains available and unchanged throughout this work, including its
public metadata shape. Installed clients and the legacy HTML handlers still
use it. Retirement requires a separate agreed date and a verified consumer
inventory; T121 does not authorize removing v1 or cutting over production.

## Slices

| Task | Scope | Acceptance |
| --- | --- | --- |
| T121.1 | PAT authentication on existing v2 domain, media, import, OCR, archive and admin endpoints; public v2 metadata with optional current-user identity | Valid PAT works without a cookie/CSRF token; invalid Bearer never falls back to a cookie; ownership, roles, cookie CSRF and existing JSON shapes remain enforced |
| T121.2 | Project, yarn and category delta sync | Preserve `since`, `cursor`, `limit`, `next_cursor`, stable ordering, watermarks and tombstones; prove no skipped/repeated changes across pages and no cross-account data |
| T121.3 | Native `POST /api/v2/auth/token` | Reuse password validation, login throttles and API-purpose token minting; preserve generic invalid-credential errors and use the v2 envelope |
| T121.4 | Remaining operation parity and client mapping | Exercise favorites, category updates/deletes, media uploads/deletes, imports and downloads; fill only actual gaps and document intentional v2 differences |
| T121.5 | Integrated Android acceptance and retirement inventory | Prove fresh sync, queued offline writes, conflicts, deletions, retry and account isolation against v2; inventory every v1 caller, including `rust/src/web.rs` |
| T121.6 | Persisted ordering for yarn photos, project title images and step images | Backfill current primary/creation order; accept only an exact ID set; transactionally set positions/primary, audit and bump the parent timestamp; verify ownership and sync visibility |

Start with T121.1, then sync, then native login. Existing v1 token login remains
available while these slices land. Shared services retain business rules and
audit behavior; new HTTP adapters must not invoke the v1 router internally.
The requested media-ordering feature is a separate schema-reviewed checkpoint
after T121.1; its metadata must be represented by the subsequent sync slice.

T121.6 adds `PUT /yarns/{id}/photos/order`,
`PUT /projects/{id}/images/order`, and
`PUT /projects/{id}/steps/{step_id}/images/order` below `/api/v2`, with
`{"ids": [...]}`. Duplicate, missing, foreign, or extra IDs fail with `422`
without partial changes. The first item selects the group's primary image;
step ordering must preserve the project's title-image group. The v2 media
projection includes persisted positions and arrays are ordered accordingly.
Migration backfill, uploads/deletes, primary promotion and audit snapshots need
one consistent grouping/order policy. Freeze the response DTO after the schema
inventory; do not silently change v1 JSON.

Sync ships first for projects and yarns. Existing v1 category sync is a complete
snapshot: it ignores `since` and has no tombstones or category timestamps.
Proper v2 category delta sync therefore needs a separate storage migration;
do not present the old snapshot as a delta. Migration 0015 is reserved for media
positions and 0016 for category sync storage. The category portion of T121.2
stays open until that implementation and its paging/deletion tests pass.

The v2 sync envelope is `{data: {updated, deleted_ids, server_time,
full_resync_required, has_more, next_cursor}}`. Updated records use existing
v2 DTOs. V2 always bounds a page (default/max 50 combined update/delete events),
and its cursors are bound to owner, entity and protocol version. Clients must
reset stored v1 cursors when switching an area to v2. The page sequence keeps
one server watermark; writes after that watermark belong to the next delta.

The first v2 sync for an area must discard its v1 continuation cursor and
finish the complete paged snapshot before saving a new watermark. Reconcile
the category cache against that complete snapshot while preserving pending
local writes: deletions from before category tombstone storage existed cannot
be reconstructed. Account switches must keep both cursors and pending writes
scoped to their original account.

## Authentication and metadata

Domain/resource and administrator routes accept either an API-purpose PAT in
`Authorization: Bearer ...` or their existing browser-purpose session cookie.
Explicit `Authorization` selects token authentication. Malformed, duplicate,
revoked, expired, disabled-user, or browser-purpose bearer credentials fail
without a cookie fallback. Cookie mutations continue to require matching
same-origin and CSRF checks. PAT mutations do not use ambient cookies and do
not require CSRF. Owner scoping and active-admin checks remain in the shared
services, including transaction-time rechecks for administrative writes.

Session/login/signup and self-service account routes retain their existing
browser authentication until a separately specified native use requires a
change. See the [v2 contract](web-v2-contract.md) for supported routes and
error envelopes.

Native password login uses `POST /api/v2/auth/token` with
`{email, password, token_name?}` and returns `200 {data: {token}}`. The token
has API purpose; this endpoint sets no browser cookie and requires no CSRF
bootstrap. Missing or blank token names use the existing native-login default.
The v1 and v2 endpoints share password verification, hash upgrades and login
attempt quotas. Do not retry rejected credentials through v1 to bypass a quota.

Invalid, unknown and disabled accounts share `401 invalid_credentials`.
Throttling returns `429 rate_limited` with `Retry-After`. Malformed JSON or
wrong field types return `400 invalid_json`, oversized bodies return
`413 payload_too_large`, and excessive field lengths return
`422 validation_error` with field errors. Internal failures return a generic
`500 internal_error`. All responses use `no-store`.

`GET /api/v2/meta` returns:

```json
{
  "data": {
    "version": "0.1.0",
    "build_id": "server-build-identifier",
    "is_admin": true,
    "user": {"id": 1, "email": "admin@example.test", "is_admin": true}
  }
}
```

Anonymous requests return `is_admin: false` and `user: null`. A valid browser
session or PAT returns its current active user's identity; explicit invalid
Bearer credentials return `401` in the v2 error envelope. An invalid or expired
cookie without a Bearer credential is anonymous. Responses use `no-store`.
The client reads `data.user.id` for “You” and `data.user.is_admin` for admin
navigation. The projection contains no credential material.

## Current route mapping

The existing v2 web DTOs and service behavior remain the basis for native
calls. The client adapts to those contracts rather than assuming v1 payloads.

| Area | Existing v2 operations / required work |
| --- | --- |
| Projects and yarns | Collection `GET`/`POST`; per-ID `GET`/`PUT`/`DELETE` |
| Favorites | `PUT` and `DELETE /projects/{id}/favorite` and `/yarns/{id}/favorite` already exist; v1's favorite `POST` must become `PUT` |
| Categories | Collection `GET`/`POST`; per-ID `PUT`/`DELETE` already exist |
| Project media | Title/step image and attachment uploads, image/attachment deletes and protected downloads already exist |
| Yarn media | Photo upload/delete and protected downloads already exist |
| Imports | `POST /imports/preview` for text/URL and `/imports/preview-file` for file input; retain feature, size, rate and SSRF protections |
| Admin | Existing account actions, audit list/detail, read-only cross-owner resources and protected media; SNA-86 uses the same PAT support |
| Sync | Add v2 endpoints in T121.2 using the shared delta semantics; freeze exact DTO/envelope mapping before client changes |
| Token login | Add v2 native token issuance in T121.3; browser `/auth/login` remains a cookie/session flow |

All paths in the table are below `/api/v2`. Exact media paths are returned in
the DTOs; clients should use them and attach their PAT to authenticated
downloads. Normal resource pages retain ownership limits; cross-owner admin
views stay read-only and use their separate admin media namespace.
Ordinary downloads retain the shared `/media/...` namespace and its existing
binary response/error behavior. They are not `/api/v1` calls, and clients must
not prepend `/api/v2` to those returned URLs. Existing owner, thumbnail and
attachment download tests cover that shared path.

## Verification and rollout

- Keep meaningful tests for both auth paths, invalid-token precedence,
  ownership, active/last-admin rules, audit logging and media association.
- Prove sync paging, cursors and tombstones with mutations between pages and
  across account switches; do not infer parity from matching route names.
- Preserve native queued-write identity, conflict handling and retry semantics.
- Run remote Rust formatting, Clippy and native tests on each frozen server
  slice, then the relevant Android acceptance against its exact revision.
- Ship small signed checkpoints. Coordinate preview pins with the UI owner so
  neither side rolls back the other's server or UI changes.
- Keep production data and preview fixtures separate. Full migration and
  production cutover remain separately approved operations.
