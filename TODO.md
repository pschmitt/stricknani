# TODO

Prioritized refactor/tech-debt tasks for Stricknani.

## Status Legend

- `todo`: not started
- `wip`: in progress
- `blocked`: waiting on a dependency/decision
- `done`: completed and merged

## Priority Rubric

- `P0`: high impact, low to medium complexity
- `P1`: high impact, higher complexity or some risk
- `P2`: medium impact or mostly cleanup
- `P3`: nice-to-have

## Task List

| Priority | Status | Task | Impact | Complexity | Primary Files |
| --- | --- | --- | --- | --- | --- |
| P0 | done | Extract reusable browser utilities into static JS (`showToast`, `copyToClipboard`, `confirmAction`, PDF viewer helpers) and remove inline copies | High | Medium | `stricknani/templates/base.html`, `stricknani/static/js/app.js` |
| P0 | done | Remove inline `onclick`/`onchange` handlers by switching to `data-action` plus delegated event listeners (start with projects + yarn forms) | High | Medium | `stricknani/templates/projects/form.html`, `stricknani/templates/yarn/form.html`, `stricknani/static/js/app.js` |
| P0 | done | Move non-critical inline CSS from templates into `static/css/app.css` (markdown image styles, crop overlay, misc layout fixes) | High | Low | `stricknani/templates/base.html`, `stricknani/static/css/app.css`, `stricknani/static/css/project_detail_print.css`, `stricknani/templates/projects/detail.html`, `stricknani/templates/yarn/detail.html` |
| P0 | done | Extract unsaved-changes logic from `shared/form_base.html` into `static/js/forms/unsaved_changes.js` | Medium | Low | `stricknani/templates/shared/form_base.html`, `stricknani/static/js/forms/unsaved_changes.js` |
| P0 | done | Create a small template-emitted JS config/i18n payload for static JS (so static files do not rely on `{{ _("...") }}` inside large inline scripts) | High | Medium | `stricknani/templates/base.html` |
| P1 | todo | Split `routes/projects.py` into services (images, attachments, steps, importing, categories) and make endpoints thin | High | High | `stricknani/routes/projects.py` |
| P1 | todo | Split `utils/importer.py` into an `importing/` package and stop importing underscored helpers from routes | High | High | `stricknani/utils/importer.py` |
| P1 | done | Remove duplicated helpers between projects/yarn (`_parse_import_image_urls`, `_extract_search_token`) by extracting shared functions | Medium | Low | `stricknani/routes/projects.py`, `stricknani/routes/yarn.py`, `stricknani/utils/search_tokens.py` |
| P1 | todo | Reduce coupling to `stricknani/main.py` by moving templating helpers to a dedicated module (and consider an app-factory) | Medium | Medium | `stricknani/main.py`, `stricknani/routes/*.py` |
| P1 | todo | Move blocking PIL/file work off the async event loop (`to_thread`) in upload/thumbnail paths | Medium | Medium | `stricknani/routes/projects.py`, `stricknani/utils/files.py` |
| P2 | todo | Unify shared UI components between Projects and Yarns (import dialogs, favorite toggle, detail sidebar patterns) to enforce parity | Medium | Medium | `stricknani/templates/projects/*`, `stricknani/templates/yarn/*`, `stricknani/templates/shared/*` |
| P2 | todo | Decompose `base.html` by moving independent features into dedicated static modules (global search, PhotoSwipe init, profile cropper, navbar hover, swipe nav) | Medium | Medium | `stricknani/templates/base.html` |
| P2 | todo | Add/expand tests around newly extracted services (importing, image handling) to keep refactors safe | Medium | Medium | `tests/*` |
| P3 | todo | Replace runtime Tailwind-in-browser with a prebuilt static CSS bundle for performance and easier CSP | High | High | `stricknani/templates/base.html`, build tooling (`justfile`, `flake.nix`) |

## Notes

- Keep the smallest possible theme-init snippet inline to avoid FOUC; everything else should prefer static JS/CSS.
- When refactoring JS into static files, avoid inline translation strings in templates; pass a JSON config object instead.
- Maintain UI consistency between Projects and Yarns when extracting shared components.
