# Web and Android parity audit

Audit of 2026-11-09 (rule: `AGENTS.md`, "UI Parity (Web & Android) - hard rule"). Wording is
compared in `stricknani/locales/{en,de}/LC_MESSAGES/messages.po` against
`android/app/src/main/res/values{,-de}/strings.xml`. Where the web had an exact equivalent, the
Android string now uses the web text, English and German. Where Android was clearly better the
web changed (P-11). Keep this file as a checklist: add a row for every difference you find.

Status: `fixed` landed with the audit, `TODO` has an entry in `TODO.md` (T-N) or
`android/TODO.md` (SNA-107), `platform` is an accepted platform convention.

Not covered by the markdown editor work: `MarkdownEditor.svelte` and `MarkdownTextField` were
left alone on purpose.

## Findings

| ID | Screen | Item | Web | Android (before) | Status |
| -- | ------ | ---- | --- | ---------------- | ------ |
| P-1 | Project editor | Section titles | Photos, Project details, Materials, Pattern, Pattern steps | Gallery, Materials and yarn, Description and notes, Instructions | fixed |
| P-2 | Project editor | Description field label | Pattern | Description | fixed |
| P-3 | Project editor | Yarn rows and picker | "Remove {name}" per yarn, "Add yarn", "Search yarn", "No yarn matches that search.", "No yarn is linked to this project yet." | "Unlink yarn", "Link yarns" / "Add or remove yarns", "Search your stash", "Add yarns to your stash..." only when the stash was empty, extra "Linked yarns" subtitle | fixed |
| P-4 | Project list | Search placeholder | Search your projects | Search projects | fixed |
| P-5 | Yarn list | Search placeholder | Search your yarn | Search yarns | fixed |
| P-6 | Project list | Favorites filter | Favorites only | missing (yarn list only) | fixed (chip added) |
| P-7 | Project and yarn list | Empty state | Own text for "no items" and for "no match" (filters) | one text "Pull down to look for new ones..." for both, same on yarns | fixed |
| P-8 | Yarn editor | Field labels | Yarn weight, Skein weight in grams, Skein length in meters, Yarn information, More yarn details | Weight category (German "Lauflaengenklasse", wrong), Weight (g), Length (m), Yarn details, Yarn specifications | fixed |
| P-9 | Yarn detail | Rows | Skein weight "100 g" and Skein length "200 m" as two rows | one row "Amount" "100g / 200m" | fixed |
| P-10 | Gauge calculator | Labels | Pattern gauge (per 10cm), Pattern counts, Your gauge (per 10cm), Calculation results, Adjusted stitches, Adjusted rows, N/A, intro text | The pattern's swatch, What the pattern says, Your swatch, Your numbers, Cast on, Knit rows, a dash, other intro | fixed |
| P-11 | Project list (web) | Filter label | "Favorites" here, "Favorites only" on yarns | Favorites only | fixed on web ("Favorites only") |
| P-12 | Project and yarn detail | Section order setting | fixed order | Settings, Detail cards (default order, drag to reorder) | TODO T134 |
| P-13 | Yarn editor | Structure | Yarn information fieldset, More yarn details disclosure (dye lot inside), Photos last | cards: Yarn details (with dye lot), Photos, specs, Description and notes | TODO T135 |
| P-14 | Project reader and editor | Stitch sample word | Gauge (reader), Stitch sample (editor) | same split | TODO T136 |
| P-15 | Account | API token wording | "API token(s)" everywhere | "token(s)", "access token" | fixed (account screen); onboarding still says "Access token" (the token is created under API Tokens on the web: low risk, left) |
| P-16 | Account | Delete account texts, confirmation label, re-auth text, "Confirm it is you", "Privacy Policy", "This cannot be undone." | web wording | "can't", "it's", "Privacy policy", "Type DELETE or your email address" | fixed |
| P-17 | Account | Self-host prompt hint, session-expired text, password/last-login errors | web wording | variants | fixed |
| P-18 | Admin | Screen titles and card text | Users, Audit log, "Manage accounts and review the audit log." | Accounts, Activity | fixed |
| P-19 | Admin Users | Create dialog | Create user, Create a user account, Email address, Temporary password, Administrator access + hint, badge Administrator | Add account, Add an account, Email, Password, Make this person an admin, Admin | fixed |
| P-20 | Admin Users | Enable / disable / reset dialogs | texts "The user will be able to sign in again.", "...signed out and will not be able to sign in.", "The user will need to sign in with the new password.", buttons Enable account, Disable account, Reset password, "New temporary password" | own variants | fixed (dialog titles stay "Enable {email}?": Android names the account, `platform`) |
| P-21 | Admin Users | Impersonate, Member and Active status | in the row menu and table | Impersonate opens the web (browser), Role and Status chips | fixed (SNA-107) |
| P-22 | Admin audit | Texts | Unknown actor, All entities, Recorded changes, "No changes are available for this event.", "No audit events match these filters." | Unknown person, All, What changed, other texts | fixed |
| P-23 | Sharing | Transfer text, people errors | "Ownership moves to the person you pick, right away...", "People could not be loaded.", "Search by name", "No one matches that search." | variants | fixed |
| P-24 | Sharing | Transfer with linked yarns switch | present | switch existed, hint added | fixed (SNA-107) |
| P-25 | Home | Section and activity texts | Recent projects, "Nothing yet - your changes will show up here." | More recent projects, "Nothing here yet. Edits..." | fixed |
| P-26 | Imports | Draft and warning texts | "Remove some first.", "Import it as a yarn instead?", "Check the fields below." | "Save or dismiss one first.", "Leave it as..." variants | fixed |
| P-27 | Categories | Empty title, delete text | "No categories yet.", "Projects that use this category will no longer show it." | no period, "...uncategorized. This can't be undone." | fixed |
| P-28 | Sign-in | Register prompt, back link | "New to Stricknani?" + "Create an account", "Already have an account?" | "No account yet? Create one", "I already have an account" | fixed |
| P-29 | Various | Trailing punctuation | Download failed., Restore failed., A download is already running., Photo removed., The passwords do not match., Copied. | no period | fixed (notification titles keep no period) |
| P-30 | Gauge calculator | How-to help panel | present | missing | fixed (SNA-107) |
| P-31 | Project reader | Empty placeholders ("Not recorded", "No pattern description yet.", "Your reference") | present | hidden | fixed (SNA-107) |
| P-32 | Recent changes | Empty hint names project or yarn | yes | one generic hint | fixed (SNA-107) |
| P-33 | Create, More | Card texts mention import | yes | short texts (local-only mode has no import) | fixed (SNA-107) |

## Compared and equal (no action)

Library cards and Mine/All scope control; project reader section order (photos, attachments,
project details, materials, pattern with sticky notes, steps, history); materials tiles and yarn
notes; timer dialog texts; sharing role labels (Can edit / Can view); import card sources and
titles; Waiting for review states; import progress steps; favourite and activity headings;
backups texts; language, theme and text-size options; More cards.

## Accepted platform differences

Bottom bar versus sidebar; sheets versus dialogs (yarn picker sheet, share sheet); pull to
refresh; delete in the editor top bar (web: Danger zone section); library paging on the web
versus endless lists; owner-aware dialog titles naming the account.

## Not compared in depth

Onboarding connection modes (QR, token), the Android-only sync, notification and crash screens,
the print dialogs, and the web admin resource pages. Add rows here when they are reviewed.

## Mechanical wording pass

A script matched every Android string to the web msgids. 637 of 1349
Android strings were already identical to a web msgid. About 100 near matches were reviewed; the
ones with the same meaning were aligned (rows above). Remaining differences are strings with
placeholders in a different form (`%1$s` versus `{name}`) and Android-only features (detail
cards, sync, widgets), which have no web text to match.
