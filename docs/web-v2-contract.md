# Browser API v2 contract

The browser API is same-origin and uses the app's browser-purpose session
cookie. Native clients are migrating to the PAT-enabled resource and
administrator routes described below; v1 remains available during that
migration. V2
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
enable cross-origin access. Cookie-authenticated state-changing requests require
a matching CSRF token and a same-origin `Origin`; when `Sec-Fetch-Site` is present, its value
must be `same-origin`.

## Authentication slice

The initial implementation exposes the following routes, mounted below
`/api/v2`:

| Method and path     | Authentication                           | Result                                                                       |
| ------------------- | ---------------------------------------- | ---------------------------------------------------------------------------- |
| `GET /session`      | None required                            | Current session or anonymous bootstrap, capabilities, locale, and CSRF token |
| `POST /auth/login`  | Pre-auth CSRF                            | Creates a browser-purpose session and sets its cookie                        |
| `POST /auth/signup` | Pre-auth CSRF; signup capability enabled | Creates a member account; does not create a session                          |
| `POST /auth/register` | None; signup capability enabled        | Native clients: creates a member account and returns `{data:{token}}` (201)  |
| `POST /auth/logout` | Browser session and CSRF                 | Revokes that session and clears its cookie; returns 204                      |

`GET /session` returns HTTP 200 for both authenticated and anonymous users:

```json
{
  "data": {
    "user": {
      "id": 42,
      "email": "knitter@example.test",
      "is_admin": false,
      "avatar_url": "/media/thumbnails/users/42/thumb_0123456789abcdef0123456789abcdef.jpg",
      "avatar_source": "upload"
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
does not add a separate authorization capability. `avatar_source` is `"upload"`,
`"gravatar"` or `null` (see [Gravatar](#gravatar)). After an avatar upload or
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
member in; the web app then calls `GET /api/v2/session` again to bootstrap a
fresh CSRF token and signs in with `POST /api/v2/auth/login`. An email that is
already registered answers HTTP 409 `registration_failed` with the same generic
message as any other refusal, so the reply never names the cause. Registration
is off unless the operator sets `SIGNUP_ENABLED=true`
(`services.stricknani.registration.enable` on NixOS). Disabled signup returns
HTTP 404 with `error.code = "signup_disabled"` and creates nothing; the session
capability `signup` and `registration_enabled` in `GET /api/v2/meta` are false
in that configuration.

Native clients use `POST /api/v2/auth/register` instead, which has no cookies,
CSRF or origin check:

```json
{
  "email": "knitter@example.test",
  "password": "a-long-password",
  "device_name": "Pixel 9"
}
```

It shares the body limit, validation, rate limit and error codes of
`/auth/signup`, and on success returns HTTP 201 with `{ "data": { "token": "…" } }`,
an API-purpose PAT named like the one `POST /auth/token` would mint. Creating
the account is not an audited event, as with the admin console and CLI.

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

Account routes accept the current browser-purpose session or a strict
API-purpose bearer PAT. Browser mutations require same-origin `Origin` and the
matching `X-CSRF-Token`. A request with an `Authorization` header is
authenticated only as a PAT: an invalid bearer gives `401 session_expired` and
never falls back to a cookie, and a valid PAT needs no `Origin`/CSRF. Token secrets are returned
only in the successful create response; list responses never include token
hashes or secret values. The token quota is five API-token creations per hour
per owner, counting revoked API tokens as issued; browser-session tokens do not
consume that quota.

| Method and path                   | Request                                 | Result                                                                           |
| --------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------- |
| `GET /account`                    | —                                       | `{data: {id, email, is_admin, avatar_url, avatar_source, use_gravatar, gravatar_enabled, error_reporting_enabled, error_reporting_available, strip_photo_metadata, detail_card_order}}` |
| `PATCH /account`                  | JSON `{use_gravatar?: boolean, error_reporting_enabled?: boolean, strip_photo_metadata?: boolean, detail_card_order?: {project?: string[], yarn?: string[]}}` (unknown fields rejected) | `200 {data: <same as GET /account>}`                              |
| `GET /account/api-tokens`         | —                                       | `{data: [{id, name, created_at, expires_at, last_used_at}]}`; `last_used_at` is RFC 3339 or `null` for a token never used |
| `POST /account/api-tokens`        | JSON `{name?, expires_at?, current_password?}` | `201 {data: {id, name, created_at, expires_at, token}}`                   |
| `PATCH /account/api-tokens/current` | JSON `{name}` (1 to 100 characters after trimming) | bearer PAT only: `200 {data: {id, name, created_at, expires_at, last_used_at}}`; renames the calling token. A cookie session gets `403 forbidden`; an empty, blank or over-long name gets `422 validation_error` with `error.fields.name`; an invalid or revoked PAT gets `401 session_expired`. Not audit-logged: the auth audit has a closed action set and no fitting event |
| `DELETE /account/api-tokens/{id}` | —                                       | browser: `204`; bearer: `200 {data: {id, revoked: true, current_token}}`; only owned API-purpose tokens can be revoked; repeat revoke is idempotent |
| `PUT /account/password`           | JSON `{current_password, new_password}` | `204`; revokes every browser session and PAT and clears the current cookie after commit |
| `POST /account/delete`            | JSON `{confirmation, current_password?}` | `204`; permanently deletes the account and all its data, see [Account deletion](#account-deletion) |
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

### Gravatar

Users without an uploaded avatar can show their Gravatar. Clients never contact
Gravatar: the server computes the SHA-256 hash of the trimmed, lowercased email,
fetches `https://gravatar.com/avatar/{hash}?s=256&d=404` (fixed host, 5 s
timeout, no off-host redirects, image content types only, 1 MiB cap) and caches
the image, and 404s, on disk under `{media_root}/gravatar/` for 24 hours. A
transient failure is remembered for five minutes and serves a stale copy when
one exists. Lookups are lazy: payloads never wait for Gravatar.

- **Route.** The Gravatar is served through the existing protected avatar
  routes under the fixed name `gravatar`: `GET /media/thumbnails/users/{id}/gravatar`
  (self only, like uploaded avatars: other users receive `403`) and
  `GET /api/v2/admin/users/{id}/avatar/gravatar` (active administrators). The
  response carries the real image content type, `nosniff` and `no-store`.
  It is `404` when an upload exists (an upload always wins), the user opted out,
  Gravatar is disabled, or Gravatar has no image. Clients treat `404` as "no
  avatar" and show their initials.
- **Payloads.** `avatar_url` in `GET /meta` (`user`), `GET /account`, the
  session user and the admin user, resource-owner and audit-actor views points
  at the Gravatar route when there is no upload, the user has not opted out and
  the server enables Gravatar; it may still `404` at fetch time.
  `avatar_source` is `"upload"`, `"gravatar"` or `null` (`/meta` `user`,
  `/account`, session `user`).
  After `DELETE /account/avatar` clients re-read `GET /account`, because the
  avatar may fall back to the Gravatar.
- **Settings.** `GET /account` also returns `use_gravatar` (boolean, default
  `true`) and `gravatar_enabled` (whether the server offers it). `PATCH /account`
  with `{use_gravatar}` stores the preference for browser sessions (with CSRF)
  and bearer PATs, and returns the updated account.
- **Server.** `GRAVATAR_ENABLED` (default `true`; NixOS option
  `services.stricknani.gravatar.enable`) switches the feature off for the whole
  instance. Migration `0019_user_gravatar.sql` adds `users.use_gravatar`.

### Detail card order

`detail_card_order` is the per-user order of the
reorderable detail-page sections, shared by the web app and Android
(`users.detail_order_project` / `detail_order_yarn`, migration `0029`, NULL is the
default). `GET /account` and the session `user` always return both lists in full:
`project` is `details, materials, description, steps` and `yarn` is
`details, description, notes, used_in` by default. `PATCH /account` names only the
pages it changes; an empty list resets a page. An unknown or repeated section id
answers `422 validation_error` (`error.fields["detail_card_order.project"]` or
`.yarn`) and changes nothing; a section left out is appended in its default
position. Bearer PATs and cookie sessions (Origin and CSRF) use the same route.

### Error reporting

Optional, operator-configured Sentry reporting (`STRICKNANI_SENTRY_DSN_FILE`; NixOS
`services.stricknani.sentry.*`; see `rust-nixos.md`).

- **Availability.** `GET /session` (`data.error_reporting`) and `GET /meta`
  (`data.error_reporting`) return `{available, dsn, tunnel_path, environment,
  enabled}`. Without a configured DSN, `available` is `false`, `dsn`,
  `tunnel_path` and `environment` are `null`, and clients must not report or show
  the setting. `dsn` is the public DSN (never a secret key). `enabled` is the
  signed-in user's choice, or the instance default (`true`) when signed out.
- **Setting.** `GET /account` returns `error_reporting_enabled` (default `true`)
  and `error_reporting_available`. `PATCH /account` with
  `{error_reporting_enabled}` stores the choice (`users.error_reporting`,
  migration `0021`).
- **Tunnel.** `POST /api/v2/telemetry/sentry` (and `.../sentry/api/{project}/envelope/`, for SDKs given a DSN that points at this server) takes a Sentry envelope (optionally
  gzip) of at most 256 KiB whose header `dsn` has the key and project id of the server or frontend DSN and
  whose items are `event`, `transaction`, `session`, `sessions` or
  `client_report`. It answers `204` after forwarding, `204` without forwarding for
  a signed-in user who opted out, `403 wrong_dsn`, `400 invalid_request`, `413`,
  `429` or `502`, and `404` when error reporting is not configured. Cookie and
  bearer callers are both accepted; signed-out callers follow the default. Events
  are scrubbed again on the server.
- **Server events.** Panics and `error!` events go to Sentry with headers, cookies,
  bodies, query strings, emails, IPs and tokens removed; the only user field is
  the numeric id, and events from requests of opted-out users are dropped.

### Account deletion

`POST /api/v2/account/delete` deletes the caller's own account for good. It is a
POST with a JSON body (not `DELETE`) so clients that cannot send a body with
`DELETE` need no special case. Body: `{confirmation, current_password?}`, unknown
fields rejected.

- **Confirmation.** `confirmation` must be the literal `DELETE` or the account
  email (case-insensitive, trimmed); otherwise `422 validation_error` with
  `error.fields.confirmation`.
- **Cookie sessions** need the usual Origin/CSRF checks (`403 csrf_failed`) and
  fresh authentication: either `current_password` in the body, or a session
  stamp from `POST /account/reauth` (password) or the OIDC browser step-up, both
  with action `delete_account` and no target. Without either: `403
  reauth_required`. On success the session cookie is cleared.
- **Bearer PATs** need `current_password` in the body or a single-use
  `X-Reauth-Token` proof for action `delete_account` (from `POST /account/reauth`
  or `POST /account/reauth/oidc/start` + `/exchange`), bound to the calling PAT.
  Without either: `403 reauth_required`. A wrong password is `401
  invalid_credentials`; passwords share the reauthentication attempt budget
  (`429 rate_limited`). A failed or refused attempt does not consume the proof.
- **Last administrator.** The only remaining active administrator gets `409
  last_admin` and nothing is deleted.
- **What is deleted**, in one transaction: the user row and, by cascade, projects,
  steps, yarns, photos, attachments (all media rows), favourites, categories,
  deletion tombstones, sync state, archive jobs, avatar, OIDC identities, API
  tokens and browser sessions, pending reauth proofs; plus the resource audit
  history authored by the account (it carries titles and change sets). Media and
  avatar files are removed from disk right after the commit through the durable
  `media_gc`/`avatar_gc` queue (a failure stays queued for the background
  sweeper).
- **What is kept.** An `account_deleted` row in the authentication audit with no
  actor, no target and only a timestamp, so an operator can see that a deletion
  happened but not whose. Administrator audit rows that merely mention the
  account keep only its numeric id (names and change sets are cleared).
  Backups are the operator's to rotate.
- **Public page.** `/app/delete-account` explains all of this and works signed
  out; it is the Play Console "Delete account URL".

### Bearer PAT access to the account routes

All seven routes above accept a bearer PAT with these rules (T121.8):

- **Read.** `GET /account` and `GET /account/api-tokens` work as for a browser.
- **Which token am I.** For a bearer request `GET /api/v2/meta` adds
  `data.current_token_id` (the numeric id of the PAT that made the request, as
  listed by `GET /account/api-tokens`). It is absent for browser sessions and
  anonymous callers. Neither the token value nor its hash is ever returned.
- **Revoke.** A bearer PAT may revoke any of the owner's API tokens, including
  its own. The response is `200 {data: {id, revoked: true, current_token}}`.
  `current_token: true` means the caller just signed itself out: its next
  request returns `401 session_expired` and the client must discard the token.
  Revoking another token leaves the caller signed in.
- **Avatar.** `POST`/`DELETE /account/avatar` behave as for the browser, with the
  same 25 MiB file / 26 MiB request caps and image validation.
- **Change password.** `PUT /account/password` needs `current_password` in the
  body exactly as in the browser. On success `204` is returned and the existing
  policy applies: every browser session and every PAT of the account, including
  the calling one, is revoked, and nothing is minted. The app must treat `204`
  as a sign-out and sign in again with the new password.
- **Create a token (step-up).** A bearer PAT must prove recent authentication or
  the request is refused and nothing is created. Either send
  `current_password` in the JSON body, or send an `X-Reauth-Token` proof for
  action `create_api_token` obtained from `POST /account/reauth` (password) or
  `POST /account/reauth/oidc/start` + `/exchange` (the path for accounts with
  `password_enabled = false`); see [the OIDC contract](oidc-v2-contract.md#recent-authentication).
  A proof is single-use and bound to the calling PAT; if the header is present it
  is used and the password is ignored. Errors: `403 reauth_required` (no or
  invalid/replayed proof, or neither field sent), `401 invalid_credentials`
  (wrong password, or the account has no usable password), `401 session_expired`
  (calling PAT no longer valid), `429 rate_limited` (shared password-attempt
  budget with `/account/reauth`, plus the five-per-hour creation quota).
- **Expiry clamp.** `expires_at` is an optional RFC3339 timestamp in the future
  (`422 validation_error` on `fields.expires_at` otherwise). A token created via
  bearer never outlives the calling token: its expiry is the earlier of the
  requested expiry and the caller's expiry, and equals the caller's when none is
  requested and the caller expires. The response carries the effective
  `expires_at` (`null` for a non-expiring token).
- **Audit.** Every token creation (browser or bearer) is recorded as
  `api_token_created` in the authentication audit (actor and target only, no
  token material).
- **Unchanged.** Browser CSRF/Origin checks and behaviour (including `204` on
  revoke and the cookie clear on password change) are unchanged. Servers
  without these changes answer a bearer call on these routes with `401`; Android
  hides the account features then.

## Photo metadata setting

`GET /account` returns `strip_photo_metadata` (default `false`) and
`PATCH /account {strip_photo_metadata}` stores the choice (`users.strip_photo_metadata`,
migration `0034`). When it is on, every image the account uploads from then on (project
title and step images, yarn photos, avatar, and images imported from URLs by the account)
is cleaned before it is stored: EXIF (GPS, camera make/model/serial, dates, maker notes,
thumbnails), XMP, IPTC, comments, text chunks and trailing data are removed at container
level, without re-encoding. The ICC profile and the EXIF Orientation tag are kept (the
Orientation is rewritten into a minimal EXIF block; pixels are not rotated). A TIFF upload
is re-encoded as PNG with the orientation applied. A file that cannot be cleaned is
rejected as an invalid image. Photos stored earlier are never changed.

## Gauge tool

`POST /tools/gauge` is a stateless calculation and does not require a session
or CSRF token. The five required count values and optional
`pattern_row_count` must be positive
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

Four optional JSON-number fields set swatch dimensions in centimeters:
`pattern_swatch_width_cm`, `pattern_swatch_height_cm`,
`user_swatch_width_cm`, and `user_swatch_height_cm`. Omitted or null means 10.
Values must be above 0 and at most 100, with no more than three decimals.
Strings, booleans, zero, negatives, values above 100 and excess precision return
422 with `error.fields.<name>` equal to
`Must be a number above 0 and at most 100 with up to 3 decimals`.

Counts use exact u128 integer arithmetic with sizes in thousandths of a
centimeter: `cast_on × user × pattern_dimension / (pattern × user_dimension)`,
rounded half-up. Android uses the same rounding. Omitting all dimensions retains
the previous result, including ties (`121 × 19 / 22` becomes `105`).

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

Authentication uses `401` for missing, invalid, or expired session/PAT credentials;
`403` for missing, invalid, expired, or cross-origin CSRF checks; `422` for
well-formed requests with invalid field values; `429` for rate limits (with
`Retry-After`); and `500` with a generic message for internal failures. A
signed-out `GET /session` is the intentional exception: it returns an anonymous
bootstrap rather than `401`.

## Projects API slice

Project routes accept a session or PAT. Cookie-authenticated writes require
same-origin and CSRF checks:

| Method and path                                                 | Authentication           | Result                                                       |
| --------------------------------------------------------------- | ------------------------ | ------------------------------------------------------------ |
| `GET /projects?page=1&category=...&tag=...&favorite=true&q=...` | Session or PAT          | `{data: ProjectSummary[], meta: {page, per_page, has_more}}` |
| `POST /projects`                                                | Session or PAT | `201 {data: Project}`                                        |
| `GET /projects/tags`                                             | Session or PAT          | `{data: [{tag, count}]}`, see below                          |
| `GET /projects/tags`                                             | Session or PAT          | `{data: [{tag, count}]}`, see below                          |
| `GET /projects/{id}`                                            | Session or PAT          | `{data: Project}`                                            |
| `PUT /projects/{id}`                                            | Session or PAT | `{data: Project}`                                            |
| `DELETE /projects/{id}`                                         | Session or PAT | `204`                                                        |
| `PUT /projects/{id}/favorite`                                   | Session or PAT | `{data: Project}`                                            |
| `DELETE /projects/{id}/favorite`                                | Session or PAT | `{data: Project}`                                            |

`GET /projects/tags` feeds the tag suggestions of the project editor. It returns
the distinct tags of the projects the caller owns or that are shared with them
(the "Mine" library set, never other users' private projects) as
`{data: [{tag, count}]}`, most used first, then by name. A tag counts once per
project; tags that differ only in case are one entry shown in its most common
spelling. `tags` is a literal path segment, not a project id.

### Project status

Every project has a `status`: `in_progress` (the default, also for projects that
existed before the field) or `done`. v2 project summaries and DTOs carry it; a
v2 create or update may send it (absent keeps the current status, a create
starts `in_progress`), and an unknown value is a `422` on `status`. API v1
neither exposes nor accepts it. `GET /projects?status=in_progress|done` filters
the list. `PUT /projects/{id}/status` with `{"status": "done"}` is the quick
toggle for editors: it returns `{data: Project}`, bumps `updated_at` only when
the status changes and is idempotent. Export and restore round-trip the field;
an archive without it restores as `in_progress`.

### Owner filter

`GET /projects?owner=...` and `GET /yarns?owner=...` narrow what the caller
already sees: `mine` (alias `me`), `shared` (everybody else's items) or a
positive user id. The filter can never widen visibility, so asking for an owner
whose items the caller cannot see returns an empty page. It combines with the
other filters and is ignored by v1. `GET /projects/owners?scope=` and
`GET /yarns/owners?scope=` list the filter options: the owners other than the
caller of the visible items as `{data: [{id, name, email, avatar_url}]}`, by
name. Share recipients get an empty `email`; administrators in the `all` scope
see every other owner with their email. The web lists preselect the filter from
`/app/projects?owner=` and `/app/yarns?owner=` (`me`, `shared` or a user id).
Items in the trash are never owner options and never count towards them.

`owner` is the one owner parameter of every list: `?trashed=only` (the trash,
see `trash-api.md`) honours it too. Non-administrators only ever list their own
trashed items, so `owner=shared` (or another user's id) with `trashed=only` is
empty for them. Administrators' `owner_id` stays as an alias of `owner=<id>`.

### Filter suggestions

`GET /search/suggestions?q=&kind=projects|yarns` (`kind` defaults to
`projects`) feeds the typeahead under the search bars of the project and yarn
lists. It answers `{data: {tags: [{tag, count}], categories: [{name}], owners:
[{id, name, avatar_url}]}}`, at most five entries per group, for values that
start (or whose words start) with the typed text, ignoring case and
diacritics (`mu` and `mü` both find `Mütze`). Tags and categories come from the
projects the caller sees (everybody's for administrators); yarns have neither.
`owners` is filled for administrators only and is always empty for everyone
else, and never carries an email address. A blank `q` answers three empty
lists. The clients add the fixed filters (status, Mine and Shared with me,
Favorites only, Trash) themselves from the same rules, and picking a suggestion
sets the same filter and URL as the filter pills. Trashed projects and yarns,
their owners and their categories are never suggested; `trashed=only` (the
Trash filter is on) draws the suggestions from the trash instead (own trash,
everybody's for administrators), and `owner=me|shared|<id>` narrows tags and
categories like it narrows the lists. Tags come most used first, then by name,
on the web and on Android.

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
return `404`. Cookie-authenticated mutations require a same-origin `Origin`
and matching `X-CSRF-Token`; PAT mutations use the explicit credential. Updates may send
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

## Project preview API slice

`POST /projects/preview` renders Markdown for unsaved project descriptions and
notes. It creates or changes no project data.

| Method and path          | Request                                                                    | Result                                       |
| ------------------------ | -------------------------------------------------------------------------- | -------------------------------------------- |
| `POST /projects/preview` | JSON object with optional nullable string fields `description` and `notes` | `200 {data: {description_html, notes_html}}` |

Missing or `null` input fields produce `null` HTML fields; empty strings
produce empty strings. The response contains only the HTML fields. Markdown is
rendered through the shared sanitizer, which removes unsafe HTML and URL
schemes. Unknown fields, values other than strings or null, and invalid JSON return
`422 validation_error`. The aggregate JSON body is limited to 2 MiB; larger
bodies return `413 payload_too_large`.

The endpoint requires a valid PAT or a browser-purpose session with matching
same-origin `Origin` and `X-CSRF-Token`. Authentication checks run before the
request body is read. It returns `401 session_expired` for invalid credentials and
`403 csrf_failed` for a missing or invalid origin/CSRF check. Responses use
`Cache-Control: no-store`.

Previews are limited to 20 requests per authenticated owner per 60 seconds,
with a bounded 4096-owner limiter. At most four renders run concurrently;
excess requests return `429 rate_limited` with `Retry-After`. Render failures
return `500 internal_error`.

## Yarn API slice

Yarn routes accept a session or PAT; cookie mutations retain same-origin and
CSRF checks:

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

Category routes accept a session or PAT; cookie writes require same-origin and
CSRF checks:

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

Media uploads and deletes accept a session or PAT, with same-origin and CSRF
checks for cookie writes. They call the same typed storage functions as v1;
existing v1 routes and wire shapes stay unchanged:

| Method and path                                             | Multipart fields / result                           |
| ----------------------------------------------------------- | --------------------------------------------------- |
| `POST /projects/{project_id}/images/title`                  | `file` and optional `alt_text`; `201 {data: Image}` |
| `POST /projects/{project_id}/steps/{step_id}/images`        | `file` and optional `alt_text`; `201 {data: Image}` |
| `POST /projects/{project_id}/attachments`                   | `file`; `201 {data: Attachment}`                    |
| `POST /yarns/{yarn_id}/photos`                              | `file`; `201 {data: YarnPhoto}`                     |
| `DELETE /projects/{project_id}/images/{image_id}`           | `204`                                               |
| `DELETE /projects/{project_id}/attachments/{attachment_id}` | `204`                                               |
| `DELETE /yarns/{yarn_id}/photos/{photo_id}`                 | `204`                                               |

Uploaded images are decoded with their EXIF orientation applied: thumbnails and
stored dimensions are upright, and the original file keeps its bytes. The
`regenerate-thumbnails` server command rewrites older thumbnails and sizes the
same way.

Project images and yarn photos can be rotated and cropped in place, with the
same authentication as the uploads (JSON body, not multipart):

| Method and path                                        | Result                    |
| ------------------------------------------------------ | ------------------------- |
| `POST /projects/{project_id}/images/{image_id}/edit`   | `200 {data: Image}`       |
| `POST /yarns/{yarn_id}/photos/{photo_id}/edit`         | `200 {data: YarnPhoto}`   |

The body is `{"rotate": 0|90|180|270, "crop": {"x", "y", "width", "height"}}`:
a clockwise rotation (default `0`) and an optional crop in fractions (0 to 1)
of the upright image after the rotation. At least one of them is required and
the result must be at least 16 by 16 pixels; anything else is `422
invalid_edit`. The item keeps its id, position, alt text and cover or primary
flag; a new file replaces the old one, so `url` and `thumbnail_url` change.
JPEG originals stay JPEG, other formats become PNG.

The description of an image (title and step images share the project route) or
yarn photo changes with a JSON body, same authentication and permissions as
the other media mutations (owner or editor; viewers `403`):

| Method and path                                        | Result                    |
| ------------------------------------------------------ | ------------------------- |
| `PATCH /projects/{project_id}/images/{image_id}`       | `200 {data: Image}`       |
| `PATCH /yarns/{yarn_id}/photos/{photo_id}`             | `200 {data: YarnPhoto}`   |

The body is exactly `{"alt_text": "..."}`. The text is trimmed and at most 4096
bytes (`422 validation_error` otherwise, also for a missing or extra field);
empty falls back to the original file name, like an upload without a
description. An unchanged description is a no-op. A change bumps the parent's
`updated_at` (sync cursor) and adds an `updated` history entry for the image.

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

Import previews accept a session or PAT, with same-origin and CSRF checks for
cookie writes. They run
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
The route accepts a session or PAT and requires same-origin and CSRF checks
for cookie requests. It accepts only
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
session or PAT, with same-origin and CSRF checks for cookie requests. `GET`
on either path returns
`{ "data": { "state": "pending", "updated_at": 1793558400 } }` for the
current source link, or `{ "data": { "state": null, "updated_at": null } }`
when the owned entity has no link or has no archive job yet. State is one of
`pending`, `running`, `archived`, `failed`, or `superseded`. Missing and
foreign-owned entities both return `404`; a missing source link cannot be
enqueued and returns `404`. Disabled archive support returns `503
archive_unavailable`. The owner-scoped status response excludes source URLs,
worker errors, leases, and job identifiers. Clients can poll this durable
status; no streaming connection is needed.

## Token last use

`api_tokens.last_used_at` records when an API-purpose PAT last authenticated a
request. The server writes it on a successful bearer authentication only when
the stored value is `NULL` or at least 5 minutes old; the write runs off the
request path and a failure is logged, never surfaced. `GET
/account/api-tokens` returns it as `last_used_at` (RFC 3339, or `null` for a
token never used). Browser-session cookies do not update it. Administrator
routes do not list tokens.

## Native delta synchronization

`GET /api/v2/sync/projects` and `GET /api/v2/sync/yarns` accept the same
resource authentication as ordinary v2 reads: a strict API-purpose PAT or a
browser session. Explicit invalid Bearer credentials never fall back to a
browser cookie.

The response is `{data: {updated, deleted_ids, server_time,
full_resync_required, has_more, next_cursor}}`. Updated records use the
corresponding v2 resource DTO; `deleted_ids` contains resource IDs.
`full_resync_required` is currently false. Every page contains at most 50
combined update and deletion events, with a default limit of 50.

Use an optional RFC3339 `since` timestamp for the first delta page, or an
opaque `cursor` for continuation. These parameters are mutually exclusive.
An optional integer `limit` must be from 1 through 50. Continuations retain
their original limit when it is omitted. `server_time` stays fixed throughout
the page sequence; after consuming all pages, save it for the next delta.
Later writes and deletions belong to the following delta. Cursors are bound
to the authenticated owner, entity type and protocol version; discard stored
v1 cursors when migrating a resource to v2.

Invalid `since` or `limit` values return 422 `validation_error` with the
corresponding `error.fields` entry. Unknown or duplicate query parameters,
or combining `since` and `cursor`, return 400 `invalid_query`. Malformed,
oversized, foreign-owner, foreign-entity or v1 cursors return 400
`invalid_cursor`. Responses retain the v2 `no-store` policy.

Category delta sync remains pending its storage migration. The existing
category list is a full snapshot, and must not be treated as a delta feed.

## Native resource and administrator authentication

Native clients can mint an API-purpose PAT with `POST /api/v2/auth/token`,
sending `{email, password, token_name?, device_name?}` and receiving `200 {data: {token}}`.
This password exchange sets no browser cookie and does not require CSRF.
Missing or blank token names use `Android (password login)`. A non-blank
`device_name` (trimmed, at most 100 characters; longer returns `422
validation_error` with `error.fields.device_name`) takes precedence and names the
token `Android · <device_name>`; the app can later rename it with
`PATCH /account/api-tokens/current`. The v1 token endpoint ignores it. The shared
password verifier, hash-upgrade policy and attempt quotas also serve v1.

Unknown, disabled and invalid credentials all return `401 invalid_credentials`.
Throttling returns `429 rate_limited` with `Retry-After`; malformed JSON or
incorrect field types return `400 invalid_json`; bodies over 8 KiB return
`413 payload_too_large`. Field length limits are 320 bytes for the normalized
email, 1024 bytes for the password, and 255 bytes for the supplied token name;
exceeding them returns `422 validation_error` with `error.fields`.
Internal failures return generic `500 internal_error`. Success and failure
responses retain `no-store`.

Project, yarn, category, media, import, OCR, archive and draft-preview routes
accept a browser session or `Authorization: Bearer <PAT>`. Administrator
routes use the same authentication and additionally require an active
administrator. The bearer credential must be an unrevoked,
unexpired API-purpose token belonging to an active user. Browser-purpose tokens
are rejected when sent as bearer credentials. The server checks administrator
status on every request and retains the shared service's transaction-time
authorization checks.

Any `Authorization` header selects the PAT path, including when a cookie is
also present. Malformed, duplicate, invalid, revoked, expired, or disabled-user
credentials return `401 session_expired`; they never fall back to a cookie.
A valid member PAT on an administrator route returns `403 admin_required`.
PAT requests do not require
`Origin` or `X-CSRF-Token`. Without `Authorization`, browser mutations retain
both same-origin and CSRF checks. Response shapes, audit behavior, media
authorization, and `Cache-Control: no-store` are shared between both paths.
Session/login/signup routes retain their existing browser authentication; the
self-service account routes additionally accept a PAT, see
[Bearer PAT access to the account routes](#bearer-pat-access-to-the-account-routes).

Android can discover server metadata and its current user through
`GET /api/v2/meta`, returning `{data: {version, build_id, is_admin, registration_enabled, user, current_token_id}}`.
`current_token_id` is the id of the calling PAT, or absent for browser sessions.
`user` is `{id, email, is_admin, avatar_url, avatar_source}` for a valid session or PAT; anonymous or
invalid-cookie requests return `user: null` and `is_admin: false`.
Explicit invalid Bearer credentials return the v2 `401 session_expired`
envelope. Metadata responses are not cacheable. The public v1 metadata
contract stays unchanged.
The nullable avatar URL uses the same protected self-thumbnail path as the
browser session. Native clients attach their PAT; other users' avatar access
is unchanged. A replacement avatar is reflected by the next metadata read.

Read-only administrator resources use the same authentication:

| Method and path | Result |
| --- | --- |
| `GET /admin/resources/{kind}/{id}` | `{data: {kind, id, name, owner, resource, read_only: true}}` |
| `GET /admin/resources/{kind}/{id}/media/{media_kind}/{media_id}` | Protected original media or attachment download |
| `GET /admin/resources/{kind}/{id}/media/{media_kind}/{media_id}/thumbnail` | Protected thumbnail |

`kind` is `project`, `yarn`, or `category`. Resource views retain
`read_only: true` and return administrator media URLs for their nested media.
Clients send their PAT when loading those URLs. The resource/media association
and active-administrator checks apply to every download.

## Administrator user API slice

Administrator routes use the browser-session or PAT authentication described
above. Cookie-authenticated mutations require same-origin `Origin` and the
matching `X-CSRF-Token`. Request JSON is limited to 8 KiB and rejects unknown
fields.
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
`avatar_url` is null when no registered avatar exists and no Gravatar applies
(see [Gravatar](#gravatar)); otherwise it points to
`GET /admin/users/{id}/avatar/{filename}` under `/api/v2`, where `filename` is
`gravatar` for the server-fetched Gravatar. This
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

## Owner resource history

`GET /api/v2/projects/{id}/history?page=N` and
`GET /api/v2/yarns/{id}/history?page=N` return
`{data: AuditEvent[], meta: {page, per_page: 20, has_more}}`, newest first by
creation time and event ID. Page defaults to 1 and must be an integer from
1 through 10000; invalid, unknown or duplicate query parameters return
`400 invalid_query`. Events include all recorded actions attributed to that
parent, including image/photo, attachment and step changes.

`GET /api/v2/projects/{id}/history/{event_id}` and the corresponding yarn
route return `{data: {event, changes, unavailable_reason}}`, using the same
safe `ChangeSetV1` projection and legacy-detail allowlist as administrator
audit details. Changes are nullable; raw legacy details are never returned.

Events (list items and the detail's `event`) may carry an optional
`media_thumbnails` object (the event's existing `media` field is the single file
of an upload/removal and is unchanged):

```json
"media_thumbnails": {
  "19413.jpg": {
    "url": "/media/projects/42/3f2a9c.jpg",
    "thumbnail_url": "/media/thumbnails/projects/42/thumb_3f2a9c.jpg"
  }
}
```

Keys are the exact label strings in the event's `project.image_order`,
`project.step_image_order`, `project.cover_image`, `yarn.photo_order` and
`yarn.primary_photo` change values, plus the event's media name. Values are the
URLs of the resource's current picture with that label (the same `url` and
`thumbnail_url` the resource detail returns). A label is
`media_label(original_filename, filename, public_id)`; project pictures are
searched title pictures first, then step pictures, each by position, and a
label shared by several pictures maps to the first. Labels without a current
picture (deleted photos) are absent, and the field is omitted entirely when no
label resolves. It is resolved at read time, so events recorded earlier get
thumbnails too. It is present only for readers of the history, and only
describes the resource's own pictures.

Both routes require a strict API-purpose PAT or browser session. The resource
must still exist and belong to that authenticated user. Foreign resources,
including requests by an administrator, and events belonging to another
resource return `404 not_found`; cross-owner administrator access retains its
separate routes. Explicit invalid Bearer credentials never fall back to a cookie.

The event shape matches administrator audit events, but resource links and
thumbnails use ordinary owner-readable paths. The current user's actor avatar
uses the protected self-avatar path. Other actors' avatar URLs are null rather
than exposing an administrator-only or globally readable avatar endpoint.
Responses retain the v2 `no-store` policy.

## Recent activity feed

`GET /api/v2/activity?limit=N&before=EVENT_ID` returns the signed-in user's
recent project and yarn audit events for the Home page, newest first:
`{data: ActivityItem[], meta: {limit, has_more, next_before}}`. `limit`
defaults to 10 and must be 1 through 50. `before` is optional: an audit event
ID (use `meta.next_before` from the previous response) and returns only events
older than it, for "load more". Any invalid, unknown or duplicate query
parameter returns `400 invalid_query`. Requires a strict API-purpose PAT or a
browser session (`401 session_expired` otherwise); the response is `no-store`.

Scope: events on projects and yarns the user currently owns (whoever acted,
so an administrator's edit shows up), plus events on resources that have since
been deleted when the user was the actor (a deleted resource has no owner row
left to check). Nothing about other users' resources is returned. Category
events are not included. Actions are the existing audit vocabulary
(`created`, `updated`, `deleted`, `uploaded`, `removed`, and the legacy
imported ones such as `step_updated`).

Coalescing happens server side: consecutive feed entries for the same
resource, with the same action and the same actor, where each event is at most
10 minutes after the previous older one, collapse into one item with
`count` > 1 (autosave bursts). `created` and `deleted` never collapse. The
item carries the newest event in `id`/`created_at`, the oldest in
`first_event_id`/`first_created_at`, and the union of `changed_fields`. A run is
never split across pages, so a page can hold slightly more events than
`limit`; `next_before` is the oldest event of the last item.

```json
{
  "data": [
    {
      "id": 412,
      "first_event_id": 409,
      "created_at": "2026-11-03T10:09:12.000000Z",
      "first_created_at": "2026-11-03T10:00:03.000000Z",
      "action": "updated",
      "entity_type": "project",
      "entity_id": 17,
      "entity_name": "Winter socks",
      "exists": true,
      "href": "/app/projects/17",
      "thumbnail_url": "/media/thumbnails/projects/17/thumb_cover.jpg",
      "actor": {"name": "philipp", "is_self": true, "avatar_url": "/media/thumbnails/users/1/thumb_a.jpg"},
      "changed_fields": ["project.name", "project.needles"],
      "count": 4
    },
    {
      "id": 405,
      "first_event_id": 405,
      "created_at": "2026-11-02T18:30:00.000000Z",
      "first_created_at": "2026-11-02T18:30:00.000000Z",
      "action": "deleted",
      "entity_type": "yarn",
      "entity_id": 3,
      "entity_name": "Old merino",
      "exists": false,
      "href": null,
      "thumbnail_url": null,
      "actor": {"name": "philipp", "is_self": true, "avatar_url": null},
      "changed_fields": [],
      "count": 1
    }
  ],
  "meta": {"limit": 10, "has_more": true, "next_before": 405}
}
```

`entity_name` is the current name while the resource exists and the name
recorded at the event otherwise (nullable for very old events without a
snapshot). `href` and `thumbnail_url` are null once the resource is gone;
`thumbnail_url` is also null without a cover photo, and is an ordinary
owner-readable media path. `actor.name` is the local part of the actor's
email for the current user and null for anyone else (for example an
administrator); `actor.avatar_url` is likewise only set for the current user.
`changed_fields` lists the safe change-field keys (such as `project.name`)
when the event recorded a typed diff, otherwise it is empty.

## Administrator audit API slice

The audit endpoint is read-only and requires an active administrator through a
browser session or PAT; no CSRF token is needed for this GET request.
The v1 HTML audit page and this endpoint use the
same query parser and typed safe projection.

| Method and path    | Query parameters                    | Result                                                       |
| ------------------ | ----------------------------------- | ------------------------------------------------------------ |
| `GET /admin/audit` | Optional `page`, `entity`, `action` | `{data: AuditEvent[], meta: {page, per_page: 50, has_more}}` |
| `GET /admin/audit/{event_id}` | — | `{data: AuditDetail}` with recorded, safe field changes |

`AuditEvent` contains only `{id, actor_email, actor_avatar_url,
legacy_actor_user_id, entity_type, entity_id, action, created_at}`.
`actor_avatar_url` is a nullable administrator-thumbnail URL derived only from
the current referenced actor account, never from an imported legacy ID.
The other two actor fields are nullable:
`actor_email` is present when the referenced account still exists, while
`legacy_actor_user_id` preserves an imported actor ID. The endpoint never
returns the arbitrary historical `details` JSON, which may contain user text or
credential-like values.

Administrator events (list items and the detail's `event`) carry the same
optional `media_thumbnails` object as the resource history (label to
`{url, thumbnail_url}`, same label rules, omitted when nothing resolves), resolved
against each event's own project or yarn whoever owns it. The URLs are the
read-only administrator media routes
(`/api/v2/admin/resources/{project|yarn}/{id}/media/{image|photo}/{public_id}`
and `.../thumbnail`), never the owner `/media/...` routes. A list page reads the
pictures of all its resources with one bounded query per resource kind. The
per-event `media` object is unchanged.

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
