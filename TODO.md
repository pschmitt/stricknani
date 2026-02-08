# TODO

Prioritized refactor/tech-debt tasks for Stricknani.

## Status Legend

- `todo`: not started
- `wip`: in progress
- `blocked`: waiting on a dependency/decision
- `done`: completed and merged

## Priority Rubric

- `P0`: high impact, low to medium complexity (bugs are ASAP, highest priority)
- `P1`: high impact, higher complexity or some risk
- `P2`: medium impact or mostly cleanup
- `P3`: nice-to-have

## Task List

| Priority | Status | Task                                                                                                 | Impact | Complexity | Primary Files                                                                                                                                |
| -------- | ------ | ---------------------------------------------------------------------------------------------------- | ------ | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| P0       | todo   | Bug: Copy-to-clipboard buttons next to source URLs put "[object HTMLButtonElement]" into clipboard | High   | Low        | Template files with copy buttons, JavaScript clipboard functionality                                                                         |
| P2       | todo   | Replace runtime Tailwind-in-browser with a prebuilt static CSS bundle for performance and easier CSP | High   | High       | `stricknani/templates/base.html`, build tooling (`justfile`, `flake.nix`)                                                                    |

## Notes

- Keep the smallest possible theme-init snippet inline to avoid FOUC; everything else should prefer static JS/CSS.
- When refactoring JS into static files, avoid inline translation strings in templates; pass a JSON config object instead.
- Maintain UI consistency between Projects and Yarns when extracting shared components.
