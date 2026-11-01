# Rust audit events

The Rust audit writer is intentionally small: callers pass an authenticated
actor ID, a typed entity and action, and a target ID inside the domain
transaction. It stores no free-form details, project or yarn notes, passwords,
tokens, hashes, or debug output. A failed domain transaction therefore also
rolls back its event.

The administrator view at `/admin/audit` reads both native events and imported
legacy rows. It is restricted to active browser-session administrators,
supports bounded entity/action filters and 50-row pages, and never renders the
legacy `details` JSON. Actor names and imported action/type strings are
HTML-escaped by Askama. A missing legacy actor is shown using its retained
source ID when available.

The native writer and read-only page are implemented here. Mutation sites in
projects, yarns, and media still need parent integration calls; until those
calls land, events are recorded only where an owning slice explicitly invokes
the helper. This is not evidence that every product mutation is audited yet.
