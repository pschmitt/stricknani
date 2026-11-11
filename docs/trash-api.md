# Trash (undo deletions)

Deleting something no longer destroys it. It moves to the trash for 90 days
(`stricknani_server::trash::RETENTION_DAYS`). The owner and administrators can
restore it or delete it forever; the server purges it after the retention
period, with the same effect as the hard delete used to have.

## What is trashed

| Item | Delete path | Storage while trashed |
| --- | --- | --- |
| Project | `DELETE /api/v1|v2/projects/{id}` | `projects.deleted_at` / `deleted_by` |
| Yarn | `DELETE /api/v1|v2/yarns/{id}` | `yarns.deleted_at` / `deleted_by` |
| Category | `DELETE /api/v1|v2/categories/{id}` | `trashed_categories` |
| Step | a project update that leaves the step out | `trashed_steps` |
| Project image / attachment | `DELETE .../projects/{id}/images/{id}`, `.../attachments/{id}` | `trashed_media` |
| Yarn photo | `DELETE .../yarns/{id}/photos/{id}` | `trashed_media` |

Projects and yarns are flagged in place; everything that reads them filters
`deleted_at IS NULL` (list, search, detail, sharing, media routes, sync,
export). A trashed project or yarn keeps its steps, media, links and shares.
Categories, steps and media move into their trash table and the live row is
removed, so no other query needs to know about the trash. Media files stay on
disk until the trashed row is purged; the `media_gc` triggers only queue a file
once neither `media_files` nor `trashed_media` names it.

API v1 keeps its behaviour and status codes: its `DELETE` answers `204` as
before and now also moves the item to the trash.

Deliberately left as hard delete: API tokens, sessions, OIDC identities, push
subscriptions and timer reminders (credentials and devices, nothing the user
wrote), project/yarn favourites, links between a project and a yarn (they stay
with the trashed item), shares (they stay with the trashed item and come back
on restore), import drafts (temporary), the user's avatar, and the deletion of a
whole account (including the account data of an administrator action): the
account and everything it owns is destroyed at once, trashed items included.

## Sync

* Trashing writes the usual deletion tombstone (`deletion_events`, or
  `category_deletion_events` for a category), so clients drop the item.
  Recipients of a share get the "access ended" tombstone as before.
* Restoring removes the tombstones of the item, moves `updated_at` past them
  (or, for a category, the sync clock through the insert trigger) and marks the
  item as newly visible for its share recipients (`access_events`). A client
  that already saw the tombstone fetches the item again on the next delta; a
  fresh full sync lists it as an update with no tombstone.
* Trashing or restoring a child bumps its parent's `updated_at`.

## API v2

Owner or administrator only; anyone else gets `404`.

* `GET /api/v2/projects?trashed=only` and `GET /api/v2/yarns?trashed=only` list
  the trashed projects and yarns (with `trashed_at` and `purge_at`; live items
  carry neither field). They take the same `owner=me|shared|<id>` filter as the
  live lists (`owner_id` stays an alias for administrators); a recipient of a
  share never sees the trash through it. The web Trash pill and the Android Trash
  chip use them (Android fetches them online: trashed items are not synced into
  Room) and open the read-only trashed page of the item.

* `GET /api/v2/trash?scope=mine|all` lists the caller's trash
  (`all`: administrators only, others always get `mine`):
  `{"data": [{kind, id, name, owner, deleted_at, purge_at, days_remaining,
  parent?, detail?}], "meta": {"scope", "retention_days": 90}}`. `kind` is
  `project`, `yarn`, `category`, `step`, `image`, `attachment` or `photo`.
  Children carry `parent` (`{kind: "project"|"yarn", id, name}`).
* `POST /api/v2/trash/{segment}/{id}/restore` answers `204`.
* `DELETE /api/v2/trash/{segment}/{id}` (delete forever) answers `204`.

`segment` is `projects`, `yarns`, `categories`, `steps`, `images`,
`attachments` or `photos`; image, attachment and photo ids are the public media
ids the API already uses.

### Parents and children

A child (step, image, attachment, photo) is listed only while its project or
yarn is live. Restoring a child while its parent is in the trash is **refused**
with `409 parent_in_trash`: restore the parent first. Children that were part of
a project or yarn when it was trashed are not separate items: restoring the
parent brings them back (they never left it). A step trashed on its own takes
its images along; restoring the step restores them.

A category whose name was used again meanwhile cannot be restored: `409
name_taken`. A restored category links the projects that used it again.

A restored image never takes over the primary image or the project cover from a
live one and is appended to the end of its group.

## Audit

Every step is audited and keeps `resource_name_snapshot`:

* `trashed`, `restored`, `purged` for projects, yarns and categories; for files
  on the owning project or yarn with the usual media reference; for steps
  `step_trashed`, `step_restored`, `step_purged` on the project (name
  `Project › Step`).
* The actor is the account that acted. When an administrator acts on someone
  else's item, or the system purges, the owner is the event's `subject`. The
  automatic purge has no actor and is marked `system: true`. Impersonation is
  recorded as `impersonated_by` as for every event.
* Rows written before the trash (`deleted`, `removed`) stay as they are and
  read as "deleted permanently".
* Admin audit events and activity items carry `resource.state` (`active`,
  `in_trash`, `gone`), and while in the trash `resource.trash`
  (`deleted_at`, `purge_at`, `days_remaining`, `can_restore`).
