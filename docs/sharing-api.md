# Sharing and ownership transfer API (v2)

Frozen server contract for the web and Android clients (T-122). API v1 does not
know about sharing: it never returns shared resources and its bodies are
unchanged. Everything below is `/api/v2`.

## Concepts

- **Roles.** `viewer` reads. `editor` also changes the resource and its media.
  Both exist on projects and yarns. Only the **owner** or an active
  **administrator** may share, change a role, revoke, transfer or delete.
  Editors cannot. Any recipient may **leave** their own share.
- **No acceptance.** A share or a transfer applies at once.
- **Derived yarn access.** A recipient of a project share can read the yarns
  linked to that project (their pages, stock fields, photos). This is not
  stored: it exists exactly while the project share and the link both exist,
  and it is always read-only, even when the project share is `editor`. It is
  not transitive: a derived yarn does not open the other projects linked to it,
  and a yarn share opens nothing on the projects that use the yarn.
- **Linking.** An editor of a shared project may link only yarns of the
  project's *owner* (see `yarn-candidates`), never their own stash. Keeping an
  existing link in a save is always fine.
- **Transfer.** The previous owner keeps an `editor` share (a mistyped
  recipient must not lock anyone out); the new owner can revoke it and the
  previous owner can leave. Any share the new owner already had is removed.

## Authentication, errors

Same as the other v2 resource routes: `Authorization: Bearer <PAT>` (API
purpose; an invalid bearer token never falls back to a cookie) or a browser
session cookie, in which case every non-GET request needs the same-origin
check and `X-CSRF-Token`. Responses are `{"data": ...}`; errors are
`{"error": {"code", "message", "fields"?}}`.

| Status | `error.code` | Meaning |
| --- | --- | --- |
| 401 | `session_expired` | Not signed in, bad/expired token, disabled account |
| 403 | `csrf_failed` | Cookie request without a valid CSRF token / origin |
| 403 | `forbidden` | The caller can see the resource but may not do this |
| 404 | `not_found` | Missing **or not visible to the caller** (also after revocation) |
| 409 | `conflict` | Already shared; the resource changed owner meanwhile; unique-name clash |
| 422 | `validation_error` | Bad body, bad role, sharing with yourself/the owner/a disabled or unknown account, unavailable yarn |

`403 forbidden` is used only where the resource is visible to the caller. For
everything else a stranger gets `404`, so the existence of a project or yarn is
never revealed.

## Role information on resources

Project and yarn detail responses, list items, create/update responses and
sync entries carry:

```json
"owner":  {"id": 7, "email": "", "name": "Anika", "avatar_url": "/api/v2/people/7/avatar/..."},
"access": {"role": "editor", "derived": false, "can_edit": true, "can_manage": false}
```

- `role`: `owner`, `admin` (an administrator acting on someone else's
  resource), `editor`, `viewer`. `derived` is `true` only for a yarn the caller
  reads solely through a shared project (role is then `viewer`).
- `can_edit`: may change content and media. `can_manage`: may share, transfer
  and delete. Use these flags rather than comparing role strings.
- `owner.email` is the empty string unless the caller is the owner or an
  administrator. `owner.avatar_url` is readable by any signed-in user.
- Yarn `project_ids` lists only the linked projects the caller can open.
- `GET /projects` and `GET /yarns` with the default `scope=mine` return the
  caller's own items **plus** shared and derived ones. Administrators' default
  `scope=all` is unchanged. Each item has its own `access`.

Favourites are personal: any reader may `PUT/DELETE .../favorite`.

## Routes

`{kind}` is `projects` or `yarns`. A `Person` is `{"id", "name", "avatar_url"}`
(never an email, admin flag or activity).

### List shares: `GET /{kind}/{id}/shares`
Owner or administrator only (viewer/editor get 403, strangers 404).

```json
{"data": {"owner": Person,
          "shares": [{"user": Person, "role": "viewer", "created_at": "...", "updated_at": "..."}]}}
```

### Add: `POST /{kind}/{id}/shares`
Body `{"user_id": 12, "role": "viewer" | "editor"}` (no other fields). `201`
with one share entry. `409` if the account already has a share (use PATCH).
`422` for yourself, the owner, an unknown or disabled account, or another role.

### Change role: `PATCH /{kind}/{id}/shares/{user_id}`
Body `{"role": "..."}`. `200` with the share entry, `404` if there is no share.

### Revoke: `DELETE /{kind}/{id}/shares/{user_id}`
`204`. `404` if there is no share.

### Leave: `POST /{kind}/{id}/leave`
No body, `204`. Removes the caller's own share. `404` when the caller has no
direct share: the owner, strangers, and people who only hold derived access
(that ends with the project share or the link).

### Transfer: `POST /{kind}/{id}/transfer`
Body `{"new_owner_id": 12, "move_linked_yarns": false}`; `move_linked_yarns`
is optional, projects only (`true` on a yarn is a 422).

- Linked yarns stay with the previous owner unless `move_linked_yarns` is
  `true`; then exactly the linked yarns **the previous owner owned** move (yarns
  of anyone else stay). Each moved yarn also gives the previous owner an editor
  share, so their other projects that link it keep working.
- The project's category follows by name: the new owner's category of the same
  name (case-insensitive) is used, or created.
- Media, audit history and archive jobs move with it.
- Errors: `422` for the current owner, an unknown or disabled account; `409`
  if the owner changed meanwhile or a unique-name constraint is hit.

```json
{"data": {"new_owner": Person,
          "access": {"role": "editor", "derived": false, "can_edit": true, "can_manage": false},
          "moved_yarn_ids": [3, 5]}}
```
`access` is the caller's access after the transfer.

### Yarn picker for editors: `GET /projects/{id}/yarn-candidates?q=&page=`
Needs edit access (viewer 403, stranger 404). Lists the **owner's** yarns, 50 a
page, ordered by name: `{"data": {"items": [{"id", "name", "brand",
"colorway", "weight_category", "linked"}], "page", "per_page", "has_more"}}`.
No photos (the caller cannot read them before the link exists). Saving a
project with any other yarn id as an editor is a `422` and nothing is saved;
the owner and administrators keep the old behaviour (unknown ids are ignored).

### People search: `GET /people?q=&page=`
Any signed-in user. Active accounts other than the caller, ordered by name,
20 a page: `{"data": {"items": [Person], "page", "per_page": 20, "has_more"}}`.
`q` (at most 64 characters) is a case-insensitive substring of the display
name (the part of the email before the `@`, which is what `name` shows).
Without `q` the whole directory is paged.

`GET /people/{id}/avatar/{filename}` serves the thumbnail from a `Person`'s
`avatar_url` to any signed-in user (404 for disabled or unknown accounts).

## Writes by role

| Route | viewer | editor | owner / admin |
| --- | --- | --- | --- |
| `GET` resource, history, media | yes | yes | yes |
| `PUT .../favorite`, `DELETE .../favorite` | yes | yes | yes |
| `PUT` resource | 403 | yes | yes |
| Upload, delete, reorder media | 403 | yes | yes |
| `DELETE` resource | 403 | 403 | yes |
| Shares, transfer | 403 | 403 | yes |
| `POST .../leave` | yes | yes | no (404) |

Derived yarn access counts as `viewer`, whatever the project role.

**Queued writes.** A client that replays a queued write and gets `403
forbidden` has lost edit rights (role lowered): keep the server copy, mark the
local change as rejected/conflicting and tell the user; do not retry. `404`
means access ended entirely: drop the local copy and the queued writes (the
sync tombstone says the same). `409 conflict` keeps its existing meaning.
Replays must not escalate: do not re-share or re-link on the user's behalf.

## Media and history

- Media URLs in DTOs are unchanged (`/media/projects/{id}/...`,
  `/media/yarns/{id}/...`, thumbnails under `/media/thumbnails/...`). They are
  keyed by the project or yarn id, never by owner, and are served to anyone who
  may read the parent resource (owner, administrator, recipient, derived yarn
  viewer); everyone else gets `403`, anonymous `401`. After access ends the
  same URL stops working.
- Uploads by an editor are stored in the owner's storage and belong to the
  owner.
- `GET /projects/{id}/history` and `/yarns/{id}/history` (and the detail
  routes) are readable by everyone who may read the resource.
- New history `action` values: `shared`, `share_role_changed`,
  `share_revoked`, `share_left`, `ownership_transferred`. These events carry
  `subject: {"id", "name", "role"}` (the other account, never an email; `role`
  is the share role, absent for transfers; `id` and `name` become `null` when
  that account was deleted). The `actor` of an administrator's action is the
  administrator. Other events have no `subject` field.

## Sync

`GET /sync/projects` and `GET /sync/yarns` (scope `mine`, the default) return
everything the caller can currently see: own items, direct shares and derived
yarns. Delta semantics:

- `updated` entries carry `owner` and `access`. An item **enters** the delta
  when it becomes visible or when its role or owner changes, even if its
  `updated_at` is older than the client's cursor or `since`. Apply `updated`
  entries by id, always.
- `deleted_ids` also contains items the caller **lost access to**, not just
  deleted ones: revoke, leave, ownership transfer away without a remaining
  share, the owner deleting the item, the owner's account being deleted,
  unlinking a yarn from the only shared project that exposed it, and the end of
  the project share. The client cannot tell these cases apart and should not
  need to.
- Within one response, apply `deleted_ids` first and `updated` second: an item
  that left and came back is in both and must end up present. An item that is
  not visible now is never in `updated`.
- A transfer changes `owner` and `access` of the project (and moved yarns) for
  the previous owner (now `editor`), the new owner (`owner`) and all other
  recipients; they receive the item in `updated`.
- Cursors keep working: a cursor stays bound to its snapshot (events after the
  snapshot are not part of a walk that started before them), to its user and
  to its scope. Replaying an old cursor is safe and returns the same page;
  `scope_mismatch` (409) still means: restart without the cursor.
- Administrators' `scope=all` is unchanged and never contains access-loss
  tombstones; API v1 sync never contains shared items or such tombstones.
- Categories: a recipient sees a shared project's `category` as the owner's
  category name; category sync stays per account.

## Not shared

Backup and export, archive jobs (link snapshots), OCR and URL import keep
working on owned items only. Deleting an account removes the shares it gave
and received; its owned items disappear for recipients through the tombstones
above.
