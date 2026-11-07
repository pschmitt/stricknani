# Data export

A user downloads everything they can read in Stricknani as one archive
(`.tar.gz`): their
projects, yarns and categories, with the original photos and attachments. The
web app offers it on the Backups page, the Android app under Settings >
Backups. Both download the same file from the server.

The format is versioned because the feature set still changes. This document is
the contract between the server, which writes the archive, and any reader (a
future restore, other tools). Restore is not built yet (TODO T129).

## Endpoint

`GET /api/v2/account/export`

- Authentication: a browser session or an API PAT (`Authorization: Bearer`),
  like the other `/api/v2/account` routes. No CSRF token is needed: it is a
  `GET` without side effects.
- `?archive=tar.gz` (the default) or `?archive=zip` picks the container; any
  other value is `400 invalid_request`. The content is the same in both.
- Response: `200`, `Content-Type: application/gzip` (zip: `application/zip`),
  `Content-Disposition: attachment; filename="stricknani-export-YYYY-MM-DD.tar.gz"`
  (zip: `.zip`; the date is UTC), `Cache-Control: no-store`. The body is streamed; there is no
  `Content-Length`.
- Errors use the usual v2 error envelope: `401` without valid credentials,
  `429 rate_limited` when the same user already has an export running.
- One export per user at a time; the slot frees when the download ends or the
  client disconnects.
- The list of items is read in one transaction; each item's details and media
  rows are read right after it, so an item edited during the export may show
  either state and one deleted in between is left out. Media files are read
  after that: each file is hashed first (the manifest and JSON come before the
  files and carry `sha256` and the real counts), then streamed. A file that
  changes size meanwhile aborts the download instead of producing a bad
  archive.

## Archive layout

```
manifest.json
projects.json
yarns.json
categories.json
media/projects/<project id>/<file name>
media/yarns/<yarn id>/<file name>
```

The archive is a gzip-compressed tar (POSIX ustar/pax) with everything inside
one top-level directory `stricknani-export-YYYY-MM-DD/`; the paths below are
relative to it. `manifest.json` is the first entry, so a reader can check the
format before reading the rest. All JSON files are UTF-8, pretty-printed. Paths
use `/`; entries are regular files with mode 0644 and the export time as their
modification time.

The zip variant has the same top-level directory and entries, `manifest.json`
first; JSON files are deflated, media files stored (they are already
compressed).

### `manifest.json`

```json
{
  "format": "stricknani-export",
  "format_version": 1,
  "created_at": "2026-11-07T18:30:00Z",
  "server": { "version": "1.5.0", "revision": "c9245acf" },
  "account": { "id": 1, "email": "user@example.org" },
  "counts": { "projects": 12, "yarns": 30, "categories": 4, "media_files": 85 }
}
```

- `server.revision` is null when the server does not know its commit.
- `counts.media_files` counts the files actually written under `media/`.

### `projects.json`

An array of projects, sorted by `id`. Each project has the fields of the API v2
project detail (`GET /api/v2/projects/{id}`): `id`, `name`, `category`,
`needles`, `stitch_sample`, `description`, `notes`, `other_materials`, `tags`,
`link`, `link_archive`, `is_ai_enhanced`, `is_favorite`, `created_at`,
`updated_at`, `yarn_ids`, `steps`, `owner`, `access`. Media are replaced by
media entries (below):

- `images`: the project's own photos (title images).
- `steps[].images`: each step's photos.
- `attachments`: files attached to the project.

### `yarns.json`

An array of yarns, sorted by `id`, with the fields of the API v2 yarn detail:
`id`, `name`, `description`, `brand`, `colorway`, `dye_lot`, `fiber_content`,
`weight_category`, `recommended_needles`, `weight_grams`, `length_meters`,
`notes`, `link`, `link_archive`, `is_ai_enhanced`, `is_favorite`, `created_at`,
`updated_at`, `project_ids`, `owner`, `access`, and `photos` as media entries.

### `categories.json`

An array of the user's own categories, sorted by `id`: `{ "id", "name" }`.

### Media entries

```json
{
  "file": "media/projects/12/3f2a9c.jpg",
  "original_filename": "IMG_2041.jpg",
  "content_type": "image/jpeg",
  "position": 0,
  "is_primary": true,
  "size_bytes": 2481152,
  "sha256": "9b1c…"
}
```

- `file` is the path inside the archive, relative to its top-level directory. Only originals are exported; thumbnails
  are rebuilt from them.
- `position` is the order within its list; `is_primary` marks the cover photo
  (false for attachments).
- A file that is missing on the server is listed with `"file": null` and
  `"missing": true`, so the archive still describes it.
- `original_filename` and `content_type` may be null when the server never
  recorded them. Entries also carry the media `id` and `alt_text`.

## What is in it

- Everything the caller can read at export time, built from the same access
  rules as sync: their own items, items shared with them, and yarns visible
  through a shared project. A revoked share leaves nothing. Derived access is
  not transitive (other projects of a derived yarn are not exported).
- Shared items carry `owner` (the original owner) and `access.role` (the
  caller's role). Own items have `access.role` = `"owner"`. A yarn readable
  only through a shared project has `access.role` = `"viewer"` and
  `access.derived` = `true`; its `project_ids` lists only projects that are in
  the archive.
- Administrators get only what they could read as a normal user, not every
  account's data.

Not in format version 1: history and audit events, project timers, account
settings, API tokens, sessions, the avatar, sharing grants. A later format
version may add some of them.

## Versioning rules

- `format_version` is an integer. Readers check `format` and refuse a version
  they do not know.
- Adding a field or a new file does not change the version; readers ignore
  what they do not know.
- Removing or renaming a field or file, or changing what a value means, bumps
  the version.
- A restore keeps accepting every older version.

## Restore rules (for the future importer)

From `docs/sharing-api.md`, "Backup and export":

- A restore never writes to another account's resource. Every item, shared ones
  included, is restored as a new copy owned by the restoring user.
- An archive never recreates a share or grants access. `owner` and `access` in
  an archive are informational and untrusted.
- IDs in the archive only link items within the archive; a restore assigns new
  IDs.
