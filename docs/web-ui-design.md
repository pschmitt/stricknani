# T120 shared UI decisions

These decisions fix the shared
interfaces for the first Luna wave; they do not mark feature work complete.

## Application routes and navigation

Build the candidate at `/app` while the current browser pages remain usable.
The static build has `/app` as its base; `/api/v2` remains outside that base.
Only known browser routes receive a shell fallback. Missing bundles, API and
media requests retain their real error status.

| Screen | Phone behavior | Wider screens |
| --- | --- | --- |
| Sign in | Focused form, language choice, optional signup; no protected navigation | Same focused form with a bounded width |
| Projects | Search, compact filter control, photo/name summaries, explicit pagination | Responsive library with persistent filters where space permits |
| Project reader | Back/context header, readable instructions and steps, reference-image zoom, action menu | Bounded reading column with useful metadata alongside |
| Project editor | Grouped fields, accessible step ordering, formatting/preview, clear save state | Same form with more space, without changing field order |
| Yarns | Matching library/detail/editor patterns; useful color and photo information | Responsive library and bounded detail forms |
| Imports | Source selection, pending state, editable preview, explicit creation | Same sequence; no separate wizard for every input format |
| More/account/tools | Account, categories, imports, gauge; admin links only for authorized users | Compact navigation rail and secondary settings navigation |

Phone navigation has Projects, Yarns, Create and More. Create is an action that
offers the relevant creation/import choices, not a misleading selected tab.
Signed-out screens never show these protected destinations. Retain normal
links, browser history, open-in-new-tab behavior and URL-based search state.

Preserve useful vertical space on phones. Put the authenticated mobile language
choice in Account settings, reachable from the existing account link, rather
than a dedicated toolbar or the global header. Keep language selection available
in a compact footer on sign-in/signup screens and on the public gauge tool.

The reader is a working surface rather than a dashboard. Preserve scroll and
step context when viewing an image or following a linked yarn. Image viewing
has a reachable close action and keyboard equivalent; zoom does not disable
browser pinch zoom. Printing includes every instruction and step.

## Shared components and state

Use one owner for tokens, shell, form fields, buttons, dialogs, upload controls,
API client and shared types. Feature agents consume those interfaces. Native
controls are preferred; use Bits UI where focus/keyboard interactions warrant
it. Confirm the assembled control works under CSP and with assistive input.

| State | Required behavior |
| --- | --- |
| Loading | Stable layout and an accessible pending indication; abort stale search requests |
| Empty library | Explain the next useful action |
| No matches | Preserve filters and offer to clear them |
| Invalid form | Field-linked errors, focusable summary where useful, entered values retained |
| Save conflict | Keep the draft and show the latest saved version; never silently overwrite |
| Failed request/upload | Explain recovery and permit a safe retry without duplicate creation |
| Expired session | Clear cached session data and require sign-in; never reuse another account's draft |
| Offline | Honest network status/fallback; no hidden queued mutations |
| Destructive action | Explicit confirmation; completion returns to a useful screen |

Use the palette, type scale and restrained motion in the brief. Dark theme is
an opaque, contrast-checked token set. Borders used to identify controls must
have adequate contrast; decorative separators need not be the control outline.
The user now prefers blue accents with neutral slate surfaces in both themes.
Use semantic foreground tokens for accent buttons so dark-theme text remains
readable. Keep meaningful success colors and user-selected yarn colors intact.
No layout depends on hover, a precise pointer, or a fixed phone height. Verify
safe areas, virtual keyboards, 200% text zoom and reduced motion.

## Browser protocol

The initial authentication contract is recorded in `web-v2-contract.md` by
the API owner. The shared JSON client uses `credentials: same-origin`, an
`X-CSRF-Token` for mutations, bounded requests and explicit response decoding.
It handles empty 204 responses and the common error envelope. No session secret
is returned to JavaScript or stored in local storage.

Resource API contracts are added per feature before frontend/backend work is
split. Use `{ data, meta? }` for successful resource responses and
`{ error: { code, message, fields? } }` for errors. Keep v1 Android behavior
independent. Reuse shared Rust operations instead of calling an internal HTTP
router or duplicating validation SQL across versions.

Translations use literal English message IDs and the existing EN/DE PO source.
The generated public `/app/messages.json` contains `{ en: {...}, de: {...} }`;
it contains UI strings only. The browser falls back to the English message ID
while loading. CI must reject missing reviewed translations before acceptance.

## Build and review boundaries

The frontend build emits `200.html`, static assets and `csp.json`. That manifest
contains only `script_hashes` and `style_hashes`, each an array of SHA-256 CSP
hash strings for actual inline content in generated HTML. Rust validates them
and attaches a typed policy to frontend responses; the global middleware keeps
its strict default elsewhere. A CSP meta tag alone is insufficient.

`WEB_ROOT` selects the built frontend. Until it is configured, current pages
continue to operate. Package/container integration supplies this directory;
Node and package managers remain build tools. All checks use disposable data.
Preview rollout retains existing isolated state and native login. No production
cutover or migration is authorized by T120.
