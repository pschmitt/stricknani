# Browser API v2 contract

The browser API is same-origin and uses the app's browser-purpose session
cookie. Android continues to use the bearer-token `/api/v1/` contract. V2
handlers must not accept API tokens from the cookie, return browser session
secrets in JSON, or call the v1 router internally.

The application-composed v2 router mounts authentication, projects, yarns,
categories, media, synchronous import previews, OCR, and archive status/request
routes. The standalone router used
by API tests has no document worker or AI client; its imports return
`import_unavailable` and its AI capability is false, even if the compatibility
availability hint is true. Composed applications pass the actual worker and
configured AI client, so the capability reflects a usable service.

All `/api/v2` responses use `Cache-Control: no-store`. The browser API does not
enable cross-origin access. State-changing requests require a matching CSRF
token and a same-origin `Origin`; when `Sec-Fetch-Site` is present, its value
must be `same-origin`.

## Authentication slice

The initial implementation exposes the following routes, mounted below
`/api/v2`:

| Method and path     | Authentication                           | Result                                                                       |
| ------------------- | ---------------------------------------- | ---------------------------------------------------------------------------- |
| `GET /session`      | None required                            | Current session or anonymous bootstrap, capabilities, locale, and CSRF token |
| `POST /auth/login`  | Pre-auth CSRF                            | Creates a browser-purpose session and sets its cookie                        |
| `POST /auth/signup` | Pre-auth CSRF; signup capability enabled | Creates a member account; does not create a session                          |
| `POST /auth/logout` | Browser session and CSRF                 | Revokes that session and clears its cookie; returns 204                      |

`GET /session` returns HTTP 200 for both authenticated and anonymous users:

```json
{
  "data": {
    "user": {
      "id": 42,
      "email": "knitter@example.test",
      "is_admin": false,
      "avatar_url": "/media/thumbnails/users/42/thumb_0123456789abcdef0123456789abcdef.jpg"
    },
    "csrf_token": "opaque-token",
    "capabilities": {
      "signup": false,
      "external_imports": false,
      "ai": false,
      "ocr": false,
      "archive": false,
      "media_upload": false,
      "media_delete": false,
      "import_preview": false,
      "archive_state": false,
      "account_actions": true,
      "admin_actions": false
    },
    "locale": "en"
  }
}
```

When signed out, `user` is `null`. The server sets a short-lived, HttpOnly
double-submit CSRF cookie and returns the matching signed token in the JSON
response. The token expires after ten minutes and becomes invalid when the
server process restarts. `POST /auth/login` sends the token both as the
`X-CSRF-Token` header and in that cookie, and sends:

```json
{ "email": "knitter@example.test", "password": "..." }
```

A successful login returns the same session envelope with a non-null `user`.
The session user includes `avatar_url`, which is `null` when no profile image
is set and otherwise a same-origin thumbnail path under
`/media/thumbnails/users/{user_id}/`. This is presentation metadata only; it
does not add a separate authorization capability. After an avatar upload or
deletion, the client updates the current session's avatar field only if the
same authenticated session is still active.

The five established capabilities (`signup`, `external_imports`, `ai`, `ocr`,
and `archive`) retain their existing configuration semantics. The additive
action hints are all `false` when no valid active browser session is present;
API authorization and same-origin CSRF checks remain authoritative:

| Capability | `true` when |
| --- | --- |
| `media_upload` | The active browser user is authenticated and a media root is configured. |
| `media_delete` | The active browser user is authenticated and a media root is configured. |
| `import_preview` | The active browser user is authenticated and import preview services are configured. |
| `archive_state` | The active browser user is authenticated and archive support is enabled. |
| `account_actions` | The session belongs to an active browser user. |
| `admin_actions` | The session belongs to an active administrator. |

The media flags report configuration presence; they do not preflight storage
or grant access to another user's resources. Browser routes continue to enforce
ownership, account status, CSRF, and administrator authorization.
The login handler sets a 14-day browser-purpose cookie and clears the pre-auth
CSRF cookie.
The session token is only in the HttpOnly cookie; it is never included in the
response body. Logout accepts `{}` and sends the session-derived CSRF token in
`X-CSRF-Token`.

When `capabilities.signup` is true, the browser may submit
`POST /api/v2/auth/signup` with the pre-authentication cookie and CSRF header,
plus a same-origin `Origin` and `Sec-Fetch-Site: same-origin`:

```json
{ "email": "knitter@example.test", "password": "a-long-password" }
```

The server applies the existing password policy and signup rate limit and
creates an ordinary member. Success returns HTTP 201 with
`{ "data": { "id": 42, "email": "knitter@example.test", "is_admin": false } }`
and clears the pre-authentication CSRF cookie. Signup does not sign the new
member in; the client must call `GET /api/v2/session` again to bootstrap a fresh
CSRF token before sign-in. Disabled signup returns HTTP 404 with
`error.code = "signup_disabled"`; the session capability is false in that
configuration.

On secure deployments the session cookie is named
`__Host-stricknani_session`; otherwise it is `stricknani_session`. Both use
`Path=/`, `HttpOnly`, and `SameSite=Lax`; secure deployments also set `Secure`.
No cookie has a `Domain` attribute. The pre-authentication CSRF cookie uses a
separate v2 name, with the `__Host-` prefix on secure deployments.

Password attempts share the existing bounded per-IP and per-email limiter and
password-verification budget. Invalid credentials return the same response
whether the account is missing, inactive, or the password is wrong. V1 remains
bearer-only and keeps its response contract.

## Account API slice

Account routes use the current browser-purpose session. Every mutation requires
same-origin `Origin` and the matching `X-CSRF-Token`. Token secrets are returned
only in the successful create response; list responses never include token
hashes or secret values. The token quota is five API-token creations per hour
per owner, counting revoked API tokens as issued; browser-session tokens do not
consume that quota.

| Method and path                   | Request                                 | Result                                                                           |
| --------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------- |
| `GET /account`                    | —                                       | `{data: {id, email, is_admin, avatar_url}}`                                      |
| `GET /account/api-tokens`         | —                                       | `{data: [{id, name, created_at, expires_at}]}`                                   |
| `POST /account/api-tokens`        | JSON `{name?: string}`                  | `201 {data: {id, name, created_at, token}}`                                      |
| `DELETE /account/api-tokens/{id}` | —                                       | `204`; only owned API-purpose tokens can be revoked; repeat revoke is idempotent |
| `PUT /account/password`           | JSON `{current_password, new_password}` | `204`; revokes browser sessions and clears the current cookie after commit       |
| `POST /account/avatar`            | Multipart `file`                        | `{data: {avatar_url}}`                                                           |
| `DELETE /account/avatar`          | —                                       | `204`                                                                            |

`created_at` and non-null `expires_at` values are RFC3339 UTC timestamps.
Token listing includes active, unexpired API-purpose tokens only. Token names
are trimmed, default to `API token` when empty, and are limited to 255 UTF-8
bytes. Creation returns the secret exactly once. Password changes require the
current password and the existing 12-character minimum / common-password
checks; the password update and browser-session revocations commit atomically.
The avatar upload uses the shared image decoder and storage lifecycle, accepts
one `file` field, caps the file at 25 MiB and the request at 26 MiB, and returns
the same thumbnail URL used by the browser pages. All account reads and writes
are owner-scoped by the authenticated account; token IDs owned by another user
or belonging to browser sessions return `404`.

## Gauge tool

`POST /tools/gauge` is a stateless calculation and does not require a session
or CSRF token. It accepts the same six field names as the existing gauge form;
the five required values and the optional `pattern_row_count` must be positive
32-bit integers when present:

```json
{
  "pattern_gauge_stitches": 20,
  "pattern_gauge_rows": 28,
  "user_gauge_stitches": 22,
  "user_gauge_rows": 30,
  "pattern_cast_on_stitches": 80,
  "pattern_row_count": 100
}
```

Success returns `{ "data": { "adjusted_stitches": "88",
"adjusted_rows": "107" } }`; calculated counts are decimal strings so large
valid Rust results remain exact in JavaScript. Omitted `pattern_row_count`
yields `"adjusted_rows": null`. The v2 endpoint and the existing HTML form
call the same validated Rust input and calculation. Invalid field values return `422`
with `error.code: "validation_error"` and a `fields` map. Invalid JSON or
unknown fields return `400` with `error.code: "invalid_json"`. Bodies larger
than the endpoint's 8 KiB JSON limit return `413` with
`error.code: "payload_too_large"`.

## Error envelope

Errors have a stable shape; `fields` is present only when a caller can act on a
field-specific validation error, and `current` is present only for a conflict
that carries the latest resource representation:

```json
{
  "error": {
    "code": "invalid_credentials",
    "message": "Email or password is incorrect",
    "fields": { "email": "..." },
    "current": { "id": 42 }
  }
}
```

Authentication uses `401` for missing, invalid, or expired browser sessions;
`403` for missing, invalid, expired, or cross-origin CSRF checks; `422` for
well-formed requests with invalid field values; `429` for rate limits (with
`Retry-After`); and `500` with a generic message for internal failures. A
signed-out `GET /session` is the intentional exception: it returns an anonymous
bootstrap rather than `401`.

## Projects API slice

The first resource slice implements browser projects on the same cookie and
CSRF model as authentication:

| Method and path                                                 | Authentication           | Result                                                       |
| --------------------------------------------------------------- | ------------------------ | ------------------------------------------------------------ |
| `GET /projects?page=1&category=...&tag=...&favorite=true&q=...` | Browser session          | `{data: ProjectSummary[], meta: {page, per_page, has_more}}` |
| `POST /projects`                                                | Browser session and CSRF | `201 {data: Project}`                                        |
| `GET /projects/{id}`                                            | Browser session          | `{data: Project}`                                            |
| `PUT /projects/{id}`                                            | Browser session and CSRF | `{data: Project}`                                            |
| `DELETE /projects/{id}`                                         | Browser session and CSRF | `204`                                                        |
| `PUT /projects/{id}/favorite`                                   | Browser session and CSRF | `{data: Project}`                                            |
| `DELETE /projects/{id}/favorite`                                | Browser session and CSRF | `{data: Project}`                                            |

The project DTO keeps v1 project field names and numeric IDs. It includes
`id`, `name`, `category`, `yarn`, `needles`, `stitch_sample`, `description`,
`notes`, `other_materials`, `tags`, `link`, `link_archive`,
`is_ai_enhanced`, `is_favorite`, `created_at`, `updated_at`, `yarn_ids`,
`steps`, `images`, and `attachments`. V2 adds `description_html`,
`notes_html`, `other_materials_html`, and each step's `description_html`;
these values are rendered and sanitized by the same Rust Markdown helper used
by server-rendered pages. The UI must render these safe HTML companion fields
instead of interpreting raw Markdown or trusting authored HTML itself.
In v2, each string in `tags` is one complete label and is preserved exactly;
commas, `#`, and internal spaces are not delimiters. V1 retains its legacy
comma/hash/whitespace splitting and case-insensitive deduplication behavior.

Project creation accepts the established project fields, `yarn_ids`, and
`steps`. If the existing external image import capability is enabled, gallery
and step `image_urls` use the existing bounded import path. Project updates
accept project, yarn, and step fields; image URL updates are rejected until the
media API slice is available. Existing image and attachment metadata is
returned by detail and write responses.

Every project lookup is owner-scoped; missing and foreign-owned IDs both
return `404`. Mutations require a same-origin `Origin`, a browser-purpose
session, and the matching `X-CSRF-Token`. Updates may send
`expected_updated_at`; stale writes return `409` with `error.code: "conflict"`
and the current DTO under `error.current`.

Example create response:

```json
{
  "data": {
    "id": 42,
    "name": "Wool socks",
    "category": "Socks",
    "yarn": "Blue wool",
    "needles": "4 mm",
    "stitch_sample": null,
    "description": "**warm**",
    "description_html": "<p><strong>warm</strong></p>\n",
    "notes": null,
    "notes_html": null,
    "other_materials": null,
    "other_materials_html": null,
    "tags": ["socks"],
    "link": null,
    "link_archive": null,
    "is_ai_enhanced": false,
    "is_favorite": false,
    "created_at": "2026-11-01T12:00:00.000000Z",
    "updated_at": "2026-11-01T12:00:00.000000Z",
    "yarn_ids": [7],
    "steps": [
      {
        "id": 81,
        "title": "Cast on",
        "description": "*64 stitches*",
        "description_html": "<p><em>64 stitches</em></p>\n",
        "step_number": 1
      }
    ],
    "images": [],
    "attachments": []
  }
}
```

## Yarn API slice

Yarn routes use the browser session and same-origin CSRF checks for mutations:

| Method and path                         | Result                                                    |
| --------------------------------------- | --------------------------------------------------------- |
| `GET /yarns?page=1&q=...&favorite=true` | `{data: YarnSummary[], meta: {page, per_page, has_more}}` |
| `POST /yarns`                           | `201 {data: Yarn}`                                        |
| `GET /yarns/{id}`                       | `{data: Yarn}`                                            |
| `PUT /yarns/{id}`                       | `{data: Yarn}`                                            |
| `DELETE /yarns/{id}`                    | `204`                                                     |
| `PUT /yarns/{id}/favorite`              | `{data: Yarn}`                                            |
| `DELETE /yarns/{id}/favorite`           | `{data: Yarn}`                                            |

Yarn summaries contain `id`, `name`, `brand`, `colorway`, `weight_category`,
`is_favorite`, `updated_at`, and `preview_url`. Detail and write DTOs retain the
v1 names and fields, including `project_ids` and `photos`; v2 adds sanitized
`description_html` and `notes_html` alongside their Markdown source strings.
IDs are JSON numbers. Updates retain optional `expected_updated_at` CAS; a
stale write returns `409` and the latest DTO in `error.current`.

## Category API slice

Category routes also use browser-session auth and CSRF for writes:

| Method and path           | Result                   |
| ------------------------- | ------------------------ |
| `GET /categories`         | `{data: [{id, name}]}`   |
| `POST /categories`        | `201 {data: {id, name}}` |
| `PUT /categories/{id}`    | `{data: {id, name}}`     |
| `DELETE /categories/{id}` | `204`                    |

Repeated creation of the same case-insensitive name returns the existing
category with `201` and does not duplicate its audit event. A rename collision
returns `409` with a `name` field error. Foreign-owned category IDs are hidden
as `404`.

## Media API slice

Media uploads and deletes use the same browser session and same-origin CSRF
checks as other v2 writes. These routes mirror existing v1 operations and call
the same typed storage functions, so Android's bearer-token routes and wire
shapes stay unchanged:

| Method and path                                             | Multipart fields / result                           |
| ----------------------------------------------------------- | --------------------------------------------------- |
| `POST /projects/{project_id}/images/title`                  | `file` and optional `alt_text`; `201 {data: Image}` |
| `POST /projects/{project_id}/steps/{step_id}/images`        | `file` and optional `alt_text`; `201 {data: Image}` |
| `POST /projects/{project_id}/attachments`                   | `file`; `201 {data: Attachment}`                    |
| `POST /yarns/{yarn_id}/photos`                              | `file`; `201 {data: YarnPhoto}`                     |
| `DELETE /projects/{project_id}/images/{image_id}`           | `204`                                               |
| `DELETE /projects/{project_id}/attachments/{attachment_id}` | `204`                                               |
| `DELETE /yarns/{yarn_id}/photos/{photo_id}`                 | `204`                                               |

Each upload requires a multipart field named `file`. Project image uploads may
also set `alt_text`; other fields are rejected. Upload responses use existing
v1 DTO field names and numeric public IDs, wrapped in `{ "data": ... }`:

```json
{
  "data": {
    "id": 17,
    "url": "/media/projects/42/example.png",
    "thumbnail_url": "/media/thumbnails/projects/42/thumb_example.jpg",
    "alt_text": "Sleeve detail",
    "image_type": "photo",
    "is_title_image": false,
    "is_stitch_sample": false,
    "step_id": 81
  }
}
```

An `Image` contains `id`, `url`, `thumbnail_url`, `alt_text`, `image_type`,
`is_title_image`, `is_stitch_sample`, and nullable `step_id`. An `Attachment`
contains `id`, `filename`, `original_filename`, `content_type`, `size_bytes`,
and `url`. A `YarnPhoto` contains `id`, `url`, `thumbnail_url`, `alt_text`, and
`is_primary`. Upload responses include only the new media DTO; clients refresh
the owning resource to get its full current media list.

The request body limit is 26 MiB to allow multipart overhead. The file part is
limited to 25 MiB; image decoding retains its pixel, dimension, and allocation
limits. The server continues to normalize supported images, create thumbnails,
classify safe attachments, use opaque filenames, stage and clean files around
the owner-scoped database transaction, and write existing upload/remove audit
events. Adding or deleting media advances its project's or yarn's
`updated_at`; deleting a primary yarn photo promotes the next photo. Uploads do
not accept `expected_updated_at`; the existing transaction serializes each
media mutation and updates the resource timestamp.

Missing or foreign-owned targets return `404`. Invalid multipart returns `400`
with `error.code: "invalid_multipart"`; unsupported images return `400` with
`error.code: "invalid_image"`; files over the limit return `413` with
`error.code: "payload_too_large"`. Media serving remains on the existing
authenticated `/media/...` routes.

## Import preview API slice

Import previews use the browser session and same-origin CSRF checks. They run
synchronously through the existing bounded document worker; no import draft is
stored server-side and no new background job is created.

| Method and path              | Request                                                                                                            | Result                |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------ | --------------------- |
| `POST /imports/preview`      | `{ "type": "text", "text": "...", "use_ai": false }` or `{ "type": "url", "url": "https://...", "use_ai": false }` | `200 {data: Preview}` |
| `POST /imports/preview-file` | Multipart `file` and optional `use_ai` (`true`/`false`)                                                            | `200 {data: Preview}` |

The response contains `draft`, `content_sha256`, `ai_requested`,
`is_ai_enhanced`, and `ai_fallback`. The draft uses the import parser's camelCase
fields, including `project.imageUrls` and `project.steps[].images`. The
fingerprint is informational; clients must treat the draft as editable user
input, not as an authorization token. After review, clients create a resource
through the ordinary `POST /projects` or `POST /yarns` contract. That keeps
domain validation, image URL safety checks, ownership, audit records, and
transactions in the shared resource services.

Pasted text and uploaded files are each limited to 5 MiB. The JSON endpoint
allows up to 32 MiB of encoded body so valid text containing JSON escapes still
fits while the decoded source remains capped at 5 MiB. Multipart allows 5 MiB
plus bounded framing overhead. URL fetching remains gated by the configured
`external_imports` capability and uses the existing SSRF-resistant fetcher and
its timeout/body limits. AI runs only when `use_ai: true`; when unavailable or
when enhancement fails, the original parsed draft is returned with
`ai_fallback: true`. The source sent to AI retains its existing text/image caps.

Errors use the common v2 envelope. Invalid source fields return `422` with
`validation_error`; disabled URL fetching returns `503 feature_disabled`;
unsafe URLs return `422 unsafe_url`; parser failures return
`422 unsupported_document` or `422 invalid_document`; timeouts return `504
import_timeout`; and oversized requests return `413 payload_too_large`.
Preview work is limited to 20 requests per authenticated owner per 10 minutes
with a bounded 4096-owner in-memory table; excess requests return `429
rate_limited` with `Retry-After`. This limiter is separate from password-login
attempt accounting.

## OCR API

`POST /utils/ocr` accepts `{ "src": "/media/projects/42/image.png", "lang":
"deu+eng", "force": false }` and returns `{ "data": { "text": "..." } }`.
The route requires a browser session and same-origin CSRF. It accepts only
canonical project title/step images or yarn photos owned by the current active
user, verifies the stored media type and canonical path, and reads at most 25
MiB. Attachments and foreign or missing media return `404`. The existing OCR
decoder pixel/allocation limits, two-process concurrency bound, and 30-second
process timeout apply. `force` is accepted for compatibility; OCR is not
cached. The configured OCR service and `ocr_enabled` flag must both be present,
otherwise the route returns `503 ocr_unavailable` and the session capability
is false. OCR is limited to five requests per authenticated owner per five
minutes using a bounded in-memory limiter separate from login and import
accounting; excess requests return `429 rate_limited` with `Retry-After`. The
v1 OCR endpoint and its bearer-token behavior are unchanged.

Invalid JSON/source/language returns `422`; oversized input returns `413`;
invalid images return `422 invalid_image`; OCR timeout returns `504
ocr_timeout`. Other OCR failures use stable v2 error codes without exposing
process output.

## Archive request and status API

`POST /projects/{id}/archive` and `POST /yarns/{id}/archive` enqueue the
current source link using the existing durable, idempotent archive service and
return `202 { "data": { "state": "pending" } }`. These writes require the
browser session and same-origin CSRF. `GET` on either path returns
`{ "data": { "state": "pending", "updated_at": 1793558400 } }` for the
current source link, or `{ "data": { "state": null, "updated_at": null } }`
when the owned entity has no link or has no archive job yet. State is one of
`pending`, `running`, `archived`, `failed`, or `superseded`. Missing and
foreign-owned entities both return `404`; a missing source link cannot be
enqueued and returns `404`. Disabled archive support returns `503
archive_unavailable`. The owner-scoped status response excludes source URLs,
worker errors, leases, and job identifiers. Clients can poll this durable
status; no streaming connection is needed.

## Administrator user API slice

Administrator routes require an active browser session whose user is an active
administrator. Mutations also require same-origin `Origin` and the matching
`X-CSRF-Token`. Request JSON is limited to 8 KiB and rejects unknown fields.
User creation hashes the validated password before opening a serialized write
transaction, then rechecks that the actor is still an active administrator
inside that transaction. Account creation, role assignment, active-state
changes, password reset, and browser-session revocation use shared typed
operations also called by the legacy HTML handlers. Public signup always
creates a member.

| Method and path                         | Request                                                          | Result                                                        |
| --------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------- |
| `GET /admin/users?page=N`               | Optional `page`, default 1; values 1 through 10000               | `{data: UserSummary[], meta: {page, per_page: 50, has_more}}` |
| `POST /admin/users`                     | JSON `{email, password, is_admin?}`; omitted `is_admin` is false | `201 {data: UserSummary}`                                     |
| `PATCH /admin/users/{id}/active`        | JSON `{active: boolean}`                                         | `200 {data: UserSummary}`                                     |
| `POST /admin/users/{id}/reset-password` | JSON `{new_password}`                                            | `200 {data: {id, session_revoked: true}}`                     |

`UserSummary` is `{id, email, active, is_admin, avatar_url}`. IDs are JSON numbers.
`avatar_url` is null when no registered avatar exists; otherwise it points to
`GET /admin/users/{id}/avatar/{filename}` under `/api/v2`. This browser-only
endpoint independently requires an active administrator and serves only the
registered JPEG thumbnail with `nosniff` and `Cache-Control: no-store`.
Anonymous or inactive sessions receive `401`, members receive `403`, and
missing or mismatched thumbnails receive `404`. Existing self-only avatar
and original-image permissions are unchanged.
User pages contain at most 50 rows, ordered by `email COLLATE NOCASE` then `id`;
`has_more` indicates whether another row exists. Invalid pages return
`400 invalid_query`. Invalid JSON, unknown fields, and oversized bodies use
stable `invalid_json`/`payload_too_large` errors. Validation errors return
`422` with `error.code` and a `fields` mapping. A non-admin receives `403
admin_required`; an existing email returns `409 email_in_use` with an `email`
field error; a missing target receives `404 not_found`; attempts to disable the
current administrator or the last active administrator receive `409
admin_conflict`.

Disabling an account revokes its browser sessions in the same transaction.
Password reset sets the target active and revokes all of its browser sessions
atomically. Resetting another user leaves the administrator's cookie intact;
resetting oneself clears that cookie after the database commit. API-purpose
tokens are not revoked by password reset. The server does not return password
hashes or other credential material.

## Administrator audit API slice

The audit endpoint is read-only and requires an active browser session for an
active administrator. API bearer tokens do not authenticate it; no CSRF token
is needed for this GET request. The v1 HTML audit page and this endpoint use the
same query parser and typed safe projection.

| Method and path    | Query parameters                    | Result                                                       |
| ------------------ | ----------------------------------- | ------------------------------------------------------------ |
| `GET /admin/audit` | Optional `page`, `entity`, `action` | `{data: AuditEvent[], meta: {page, per_page: 50, has_more}}` |

`AuditEvent` contains only `{id, actor_email, actor_avatar_url,
legacy_actor_user_id, entity_type, entity_id, action, created_at}`.
`actor_avatar_url` is a nullable administrator-thumbnail URL derived only from
the current referenced actor account, never from an imported legacy ID.
The other two actor fields are nullable:
`actor_email` is present when the referenced account still exists, while
`legacy_actor_user_id` preserves an imported actor ID. The endpoint never
returns the arbitrary historical `details` JSON, which may contain user text or
credential-like values.

`entity` accepts `all`, `project`, `yarn`, or `category`; it defaults to `all`.
`action` is an optional exact historical action string, trimmed and limited to
64 UTF-8 bytes; it is not restricted to the current action enum. The raw query
is limited to 512 bytes and eight key/value pairs. Unknown keys, duplicate
keys, invalid filters, page values outside 1 through 10000, and control
characters in `action` return `400 invalid_query`. Results are ordered by
`created_at DESC, id DESC` in pages of 50. Out-of-range pages within the
allowed numeric range clamp to the last available page, matching the legacy
HTML audit view. `has_more` indicates whether another permitted page exists.
V2 responses use `Cache-Control: no-store`.

## Proposed contract for remaining resource slices

These resource routes are a starting contract for frontend/backend slices;
they are not part of the initial authentication implementation.

| Resource      | Proposed operations                                               |
| ------------- | ----------------------------------------------------------------- |
| Account/admin | Only the self-service and administrator operations used by the UI |

List endpoints use bounded page-number pagination, stable ordering, and an
`data`/`meta` envelope:

```json
{
  "data": [{ "id": 42, "name": "..." }],
  "meta": { "page": 1, "per_page": 50, "has_more": false }
}
```

Detail, create, and update responses use `{ "data": <DTO> }`. Search and filter
state is expressed in the query string. Keep existing v1 DTO field names for
the first v2 resource slices unless a concrete browser need justifies a
change; IDs are JSON numbers. Updates send `expected_updated_at`; a stale update
returns `409` with `error.code: "conflict"` and the current DTO in
`error.current`, so the editor can preserve its input and offer reload/merge.
V2 responses use the common error envelope.

All resource lookups and mutations are scoped to the authenticated owner.
Missing and foreign-owned IDs both return `404`. Import previews remain
bounded synchronous requests; archive status exposes the existing durable job
without adding a queue or streaming transport.
