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

## Proposed resource contract for subsequent slices

These resource routes are a starting contract for frontend/backend slices;
they are not part of the initial authentication implementation.

| Resource | Proposed operations |
| --- | --- |
| Projects | `GET/POST /projects`, `GET/PUT/DELETE /projects/{id}`, favorite/unfavorite |
| Yarns | `GET/POST /yarns`, `GET/PUT/DELETE /yarns/{id}`, favorite/unfavorite |
| Categories | `GET/POST /categories`, `PUT/DELETE /categories/{id}` |
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
