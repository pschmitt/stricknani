# Audit resource and history contract

T120.11 adds useful resource names, separate read-only administrator views,
and on-demand recorded changes. The user explicitly approved cross-owner
read access through these administrator views. Normal resource routes,
mutations, media ownership checks and Android API v1 retain their existing
permissions.

All new reads require an active administrator browser session. Bearer-only
clients and ordinary or disabled accounts gain no access. Resource and media
responses remain private and uncached.

## Audit list

Preserve existing event fields and add:

```typescript
resource: {
  kind: 'project' | 'yarn' | 'category' | null;
  id: number;
  name: string | null;
  name_source: 'event' | 'current' | 'unavailable';
  href: string | null;
  preview_url: string | null;
  exists: boolean;
};
has_changes: boolean;
media: AuditMedia | null; // upload and removal events only
```

`AuditMedia` names the file an upload or removal event was about:
`{ kind: 'image' | 'photo' | 'attachment', id, name, exists, is_image,
content_type, size_bytes, url, thumbnail_url }`. The event stores the kind,
public id and file name (migration 0023; old uploads are backfilled by
timestamp where the file still exists). `exists` is false once the file was
deleted: `url` and `thumbnail_url` are then null and clients say "deleted"
instead of linking. In the administrator log the URLs use the read-only admin
media route; in the owner history they use the owner's `/media/...` routes.
Every event therefore offers a diff (`has_changes`), media, or an explicit
"no details recorded" state. Saves that change nothing record no event, and
media reorders record `project.image_order`, `project.cover_image`,
`project.step_image_order`, `yarn.photo_order` and `yarn.primary_photo`.
Live projects and yarns link to the normal `/app/projects/{id}` and
`/app/yarns/{id}` pages, which administrators may open for any owner.

Prefer recorded event-time names. Historical events may use the current
resource name when available, clearly distinguished by `name_source`.
Unknown historical entity kinds or invalid IDs have no resource link.
Existing resources link to `/app/admin/resources/{kind}/{id}`. Deleted or
unavailable resources link to `/app/admin/audit/{event_id}`, which explains
what remains available. Never invent a deleted name or missing history.
For a live project or yarn, `preview_url` may reference its current title or
primary thumbnail through the same administrator-only, parent-bound media
route. It is current resource media, not an event-time image snapshot; category,
deleted, and imageless resources use `null`. No image metadata is added to old
audit rows. The list must not include full resource text or change payloads.

Owner-scoped `ProjectYarn` and `LinkedProject` previews are derived from the
existing yarn `photos` and project `images` detail DTOs. They retain the normal
owner media URLs and do not grant cross-owner access.

## Read-only resource

`GET /api/v2/admin/resources/{kind}/{id}` returns:

```typescript
{ data: {
  kind: 'project' | 'yarn' | 'category';
  id: number;
  name: string;
  owner: { id: number; email: string; avatar_url?: string | null } | null;
  resource: Project | Yarn | Category;
  read_only: true;
} }
```

The service checks administrator authorization before reading on behalf of
an actual resource owner. Media uses the administrator GET route
`/api/v2/admin/resources/{kind}/{id}/media/{media_kind}/{public_id}`, with
`media_kind` equal to `image`, `attachment`, or `photo`. Image and attachment
public IDs have separate allocation namespaces and may overlap, so an ID
alone cannot identify project media. Each media kind and ID must belong to
the requested resource; thumbnails are available only for images and photos.
Existing owner media
routes remain unchanged. The page offers reading, images and attachments,
with no edit, delete, favorite, OCR, archive or other mutation controls.
Show the owner's avatar beside their identity.

## Lazy changes

`GET /api/v2/admin/audit/{event_id}` returns:

```typescript
{ data: {
  event: AuditEvent;
  changes: ChangeSetV1 | null;
  unavailable_reason: null | 'not_recorded' | 'malformed' | 'too_large';
} }
```

A Show diff action loads this endpoint on demand. `ChangeSetV1` is
`{version: 1, items: [{field, before, after}]}` with allowlisted dotted field
names and tagged, field-compatible values. Resource text, tags, linked yarn
names, ordered steps and numeric fields can have useful before/after values.
Links record presence/change only, without storing potentially credentialed
URLs. Render text with normal escaping, never as raw HTML.

New event names and changes are recorded inside the resource mutation's
transaction, using pre-delete names where appropriate. Rollbacks and failed
optimistic updates produce no successful change event. The nullable migration
preserves old audit rows and legacy details without rewriting or backfilling
unverifiable history.

Project only recognized, bounded fields from compatible Python historical
changes. Validate entity, field, value types and aggregate size. Never return
raw legacy JSON, unknown keys, storage paths, or authentication/security
fields. Malformed, oversized and unrecorded history have distinct honest
fallbacks; a valid resource edit must not fail merely because its history
exceeds display limits.

## Verification and rollout

Cover authorization and media-parent/kind binding, including an image and an
attachment with the same public ID, migration preservation,
transaction rollback, recognized legacy projection, rejected unknown fields,
deleted resources, lazy diff loading and read-only browser navigation.
Back up the isolated preview database with a WAL-safe backup before deploying
the migration. Production data migration and cutover remain separate work.
