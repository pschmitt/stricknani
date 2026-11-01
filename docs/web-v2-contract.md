# Browser API v2 contract

The browser API is same-origin and uses the app's browser-purpose session
cookie. Android continues to use the bearer-token `/api/v1/` contract. V2
handlers must not accept API tokens from the cookie, return browser session
secrets in JSON, or call the v1 router internally.

All `/api/v2` responses use `Cache-Control: no-store`. The browser API does not
enable cross-origin access. State-changing requests require a matching CSRF
token and a same-origin `Origin`; when `Sec-Fetch-Site` is present, its value
must be `same-origin`.

## Authentication slice

The initial implementation exposes the following routes, mounted below
`/api/v2`:

| Method and path | Authentication | Result |
| --- | --- | --- |
| `GET /session` | None required | Current session or anonymous bootstrap, capabilities, locale, and CSRF token |
| `POST /auth/login` | Pre-auth CSRF | Creates a browser-purpose session and sets its cookie |
| `POST /auth/logout` | Browser session and CSRF | Revokes that session and clears its cookie; returns 204 |

`GET /session` returns HTTP 200 for both authenticated and anonymous users:

```json
{
  "data": {
    "user": { "id": 42, "email": "knitter@example.test", "is_admin": false },
    "csrf_token": "opaque-token",
    "capabilities": {
      "signup": false,
      "external_imports": false,
      "ai": false,
      "ocr": false,
      "archive": false
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
It sets a 14-day browser-purpose cookie and clears the pre-auth CSRF cookie.
The session token is only in the HttpOnly cookie; it is never included in the
response body. Logout accepts `{}` and sends the session-derived CSRF token in
`X-CSRF-Token`.

On secure deployments the session cookie is named
`__Host-stricknani_session`; otherwise it is `stricknani_session`. Both use
`Path=/`, `HttpOnly`, and `SameSite=Lax`; secure deployments also set `Secure`.
No cookie has a `Domain` attribute. The pre-authentication CSRF cookie uses a
separate v2 name, with the `__Host-` prefix on secure deployments.

Password attempts share the existing bounded per-IP and per-email limiter and
password-verification budget. Invalid credentials return the same response
whether the account is missing, inactive, or the password is wrong. V1 remains
bearer-only and keeps its response contract.

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

| Method and path | Authentication | Result |
| --- | --- | --- |
| `GET /projects?page=1&category=...&tag=...&favorite=true&q=...` | Browser session | `{data: ProjectSummary[], meta: {page, per_page, has_more}}` |
| `POST /projects` | Browser session and CSRF | `201 {data: Project}` |
| `GET /projects/{id}` | Browser session | `{data: Project}` |
| `PUT /projects/{id}` | Browser session and CSRF | `{data: Project}` |
| `DELETE /projects/{id}` | Browser session and CSRF | `204` |
| `PUT /projects/{id}/favorite` | Browser session and CSRF | `{data: Project}` |
| `DELETE /projects/{id}/favorite` | Browser session and CSRF | `{data: Project}` |

The project DTO keeps v1 project field names and numeric IDs. It includes
`id`, `name`, `category`, `yarn`, `needles`, `stitch_sample`, `description`,
`notes`, `other_materials`, `tags`, `link`, `link_archive`,
`is_ai_enhanced`, `is_favorite`, `created_at`, `updated_at`, `yarn_ids`,
`steps`, `images`, and `attachments`. V2 adds `description_html`,
`notes_html`, `other_materials_html`, and each step's `description_html`;
these values are rendered and sanitized by the same Rust Markdown helper used
by server-rendered pages. The UI must render these safe HTML companion fields
instead of interpreting raw Markdown or trusting authored HTML itself.

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
    "steps": [{
      "id": 81,
      "title": "Cast on",
      "description": "*64 stitches*",
      "description_html": "<p><em>64 stitches</em></p>\n",
      "step_number": 1
    }],
    "images": [],
    "attachments": []
  }
}
```

## Yarn API slice

Yarn routes use the browser session and same-origin CSRF checks for mutations:

| Method and path | Result |
| --- | --- |
| `GET /yarns?page=1&q=...&favorite=true` | `{data: YarnSummary[], meta: {page, per_page, has_more}}` |
| `POST /yarns` | `201 {data: Yarn}` |
| `GET /yarns/{id}` | `{data: Yarn}` |
| `PUT /yarns/{id}` | `{data: Yarn}` |
| `DELETE /yarns/{id}` | `204` |
| `PUT /yarns/{id}/favorite` | `{data: Yarn}` |
| `DELETE /yarns/{id}/favorite` | `{data: Yarn}` |

Yarn summaries contain `id`, `name`, `brand`, `colorway`, `weight_category`,
`is_favorite`, `updated_at`, and `preview_url`. Detail and write DTOs retain the
v1 names and fields, including `project_ids` and `photos`; v2 adds sanitized
`description_html` and `notes_html` alongside their Markdown source strings.
IDs are JSON numbers. Updates retain optional `expected_updated_at` CAS; a
stale write returns `409` and the latest DTO in `error.current`.

## Category API slice

Category routes also use browser-session auth and CSRF for writes:

| Method and path | Result |
| --- | --- |
| `GET /categories` | `{data: [{id, name}]}` |
| `POST /categories` | `201 {data: {id, name}}` |
| `PUT /categories/{id}` | `{data: {id, name}}` |
| `DELETE /categories/{id}` | `204` |

Repeated creation of the same case-insensitive name returns the existing
category with `201` and does not duplicate its audit event. A rename collision
returns `409` with a `name` field error. Foreign-owned category IDs are hidden
as `404`.

## Proposed contract for remaining resource slices

These resource routes are a starting contract for frontend/backend slices;
they are not part of the initial authentication implementation.

| Resource | Proposed operations |
| --- | --- |
| Media | Multipart upload and delete routes under the owning project, step, or yarn |
| Imports | Synchronous URL/text/file preview routes; creation uses normal project/yarn writes |
| Archive | Enqueue route and owner-scoped status only if the UI needs polling |
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
Missing and foreign-owned IDs both return `404`. Media handlers enforce the
same ownership rule and the existing upload byte, type, decoding, and image
dimension limits. Import previews remain bounded synchronous requests; the
existing archive queue is the only durable asynchronous job in the current
Rust candidate.
