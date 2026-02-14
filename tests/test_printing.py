from __future__ import annotations


def test_project_print_css_overrides_daisyui_collapse() -> None:
    css = "stricknani/static/css/project_detail_print.css"
    content = open(css, encoding="utf-8").read()

    # Ensure collapsed sections print expanded, regardless of the checkbox state.
    assert ".collapse > input:not(:checked) ~ .collapse-content" in content
    assert ".collapse > input:checked ~ .collapse-content" in content
    # Ensure images are not hidden in print.
    assert "#project-gallery-section," not in content
    assert "#stitch-sample-photos," not in content
    assert ".step-photos-section," not in content
    assert ".pswp-gallery {" in content


def test_project_details_has_print_table_markup() -> None:
    template = "stricknani/templates/projects/detail.html"
    content = open(template, encoding="utf-8").read()

    # Print-only details table for compact output.
    assert "project-details-print-table" in content
    assert "technical-specs-print-table" in content
    assert "instructions-section" in content


def test_print_css_starts_instructions_on_new_page() -> None:
    css = "stricknani/static/css/project_detail_print.css"
    content = open(css, encoding="utf-8").read()

    assert ".instructions-section" in content
    assert "break-before: page" in content or "page-break-before: always" in content


def test_project_detail_js_expands_instructions_before_print() -> None:
    js = "stricknani/templates/projects/detail.js"
    content = open(js, encoding="utf-8").read()

    # Ensure the browser print flow forces instructions open.
    assert "instructions-toggle" in content
    assert "beforeprint" in content or 'matchMedia("print")' in content
