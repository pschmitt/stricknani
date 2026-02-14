from __future__ import annotations


def test_project_print_css_overrides_daisyui_collapse() -> None:
    css = "stricknani/static/css/project_detail_print.css"
    content = open(css, encoding="utf-8").read()

    # Ensure collapsed sections print expanded, regardless of the checkbox state.
    assert ".collapse > input:not(:checked) ~ .collapse-content" in content
    assert ".collapse > input:checked ~ .collapse-content" in content


def test_project_details_has_print_table_markup() -> None:
    template = "stricknani/templates/projects/detail.html"
    content = open(template, encoding="utf-8").read()

    # Print-only details table for compact output.
    assert "project-details-print-table" in content
