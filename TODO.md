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

| Priority | Status | Task                                                                                                 | Impact | Complexity | Primary Files                                                              |
| -------- | ------ | ---------------------------------------------------------------------------------------------------- | ------ | ---------- | -------------------------------------------------------------------------- |
| P1       | done   | ai import: use PDF-to-image conversion as fallback when direct PDF upload to OpenAI fails            | High   | Medium     | `stricknani/importing/extractors/ai.py`, `stricknani/importing/extractors/pdf.py` |
| P0       | done   | bug: imported images from PDF are not saved when project is created (lost on save)                    | High   | Medium     | `stricknani/routes/projects.py`, `stricknani/services/projects/`           |
| P0       | done   | bug: step photos from PDF import are not saved and preview is broken                                 | High   | Medium     | `stricknani/routes/projects.py`, `stricknani/templates/projects/form.html` |
| P0       | done   | AI import: Direct PDF upload support instead of local text extraction                                | High   | Medium     | `stricknani/importing/extractors/ai.py`, `stricknani/routes/projects.py`   |
| P1       | done   | AI import: Support for step images (investigate schema/model tweaks)                                 | Medium | Medium     | `stricknani/importing/extractors/ai.py`, `stricknani/importing/models.py`  |
| P3       | todo   | Replace runtime Tailwind-in-browser with a prebuilt static CSS bundle for performance and easier CSP | High   | High       | `stricknani/templates/base.html`, build tooling (`justfile`, `flake.nix`) |
| P3       | todo   | Add OpenRouter and Groq support for AI imports as an alternative to OpenAI                           | Medium | Medium     | `stricknani/importing/extractors/ai.py`                                    |
| P1       | done   | feat: on the project/yarn list views I want to be able to drag and drop files -> automatically run the import from file | High   | Medium     | `stricknani/templates/projects/list.html`, `stricknani/templates/yarn/list.html`, `stricknani/static/js/htmx/` |
| P1       | done   | ai import: we should instruct that we should not drop text. If we don't know where to put it, just add it to the description. | Medium | Low        | `stricknani/importing/extractors/ai.py`                                    |
| P1       | done   | feat: when import dialog is shown, allow dragging files onto it to auto-switch to "file upload" mode with the dropped files | High   | Medium     | `stricknani/templates/projects/_import_dialog.html`, `stricknani/templates/yarn/_import_dialog.html`, `stricknani/static/js/htmx/` |
| P2       | done   | feat: right-click/long-press on project/yarn cards to show favorite/print/re-import/delete context menu (same as "..." button) | Medium | Medium     | `stricknani/templates/projects/_cards.html`, `stricknani/templates/yarn/_cards.html`, `stricknani/static/js/htmx/` |
| P0       | done   | bug: spinning circle in project deletion confirmation dialog is transparent (no color) and looks weird | High   | Low        | `stricknani/templates/projects/_cards.html`, `stricknani/static/css/app.css` |
| P0       | done   | AI import: never upload PDFs directly, always convert to images first for better reliability and consistency | High   | Medium     | `stricknani/importing/extractors/ai.py`, `stricknani/importing/extractors/pdf.py` |
| P0       | done   | bug: AI importer sometimes incorrectly attaches PDF page images to random steps instead of as project attachments | High   | Medium     | `stricknani/importing/extractors/ai.py`, `stricknani/importing/models.py`, `stricknani/prompts/ai_ingest_baseline.txt` |
| P1       | done   | bug: context menu favorite/unfavorite actions don't update menu item text after successful operation | Medium | Low        | `stricknani/templates/projects/_cards.html`, `stricknani/templates/yarn/_list_partial.html`, `stricknani/static/js/features/context_menu.js` |
| P0       | done   | bug: PDF file not showing in attachments list on new project form (though it saves correctly) | High   | Medium     | `stricknani/templates/projects/form.html`, `stricknani/routes/projects.py` |
| P0       | done   | bug: PDF page images we created are not saved as attachments (but appear in gallery incorrectly) | High   | Medium     | `stricknani/routes/projects.py`, `stricknani/services/projects/attachments.py` |

## Notes

- Keep the smallest possible theme-init snippet inline to avoid FOUC; everything else should prefer static JS/CSS.
- When refactoring JS into static files, avoid inline translation strings in templates; pass a JSON config object instead.
- Maintain UI consistency between Projects and Yarns when extracting shared components.
