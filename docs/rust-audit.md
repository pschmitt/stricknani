# Rust audit events

The Rust audit writer is intentionally small: callers pass an authenticated
actor ID, a typed entity and action, and a target ID inside the domain
transaction. It stores no free-form details, project or yarn notes, passwords,
tokens, hashes, or debug output. A failed domain transaction therefore also
rolls back its event.

The administrator audit view in the web app (`/app/admin/audit`) reads both native events and imported
legacy rows. It is restricted to active browser-session administrators,
supports bounded entity/action filters and 50-row pages, and never renders the
legacy `details` JSON. Actor names and imported action/type strings are
escaped when the web app renders them. A missing legacy actor is shown using its retained
source ID when available.

Project, yarn, and category create/update/delete handlers and media upload or
delete handlers record native events in the same transaction as the mutation.
The event rolls back with the resource change. The helper has no free-form
details field, so callers cannot persist imported notes, credentials, or request
bodies as event details.

`rust/tests/audit.rs` covers administrator access, filters, pagination,
escaping, category and yarn mutation events, and the absence of events for
duplicates, conflicts, or missing resources. The project and media integration
tests exercise their respective domain paths. Imported Python audit rows remain
read-only and preserve source metadata for review; they are not converted into
native events.
