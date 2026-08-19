"""Regression tests for the static CSS bundle (T1).

These verify base.html no longer loads Tailwind at runtime (browser JIT /
CDN script) and instead links a prebuilt static CSS bundle. This is a
prerequisite for a strict CSP (no inline/eval-ish runtime script needed for
styling).
"""

import re
from pathlib import Path
from typing import Any

import pytest

STATIC_CSS_DIR = (
    Path(__file__).resolve().parent.parent / "stricknani" / "static" / "css"
)
T88_FEATURE_TEMPLATES = (
    "macros/buttons.html",
    "macros/cards.html",
    "macros/dialogs.html",
    "macros/image_upload.html",
    "macros/wysiwyg.html",
    "shared/_empty_state.html",
    "shared/_empty_search_state.html",
    "shared/_favorite_toggle.html",
    "shared/_import_dialog.html",
    "shared/_search_bar.html",
    "shared/detail_base.html",
    "shared/form_base.html",
    "shared/list_base.html",
    "projects/_cards.html",
    "projects/_cards_page.html",
    "projects/detail.html",
    "projects/form.html",
    "projects/list.html",
    "yarn/_cards_page.html",
    "yarn/detail.html",
    "yarn/form.html",
    "yarn/list.html",
)
LEGACY_FEATURE_CLASS = re.compile(
    r'class="[^"]*(?:^|\s)(?:btn|badge|card-title|card-body|collapse|'
    r'collapse-title|collapse-content|modal-box|modal-action)(?:\s|["}]|$)'
)


@pytest.mark.asyncio
async def test_no_runtime_tailwind_script(test_client: Any) -> None:
    """The rendered page must not load any Tailwind runtime/CDN script."""
    client, _, _, _, _ = test_client
    response = await client.get("/projects/")
    assert response.status_code == 200
    body = response.text
    assert "tailwindcss-browser" not in body
    assert "@tailwindcss/browser" not in body
    assert "cdn.tailwindcss.com" not in body


@pytest.mark.asyncio
async def test_links_material_css_without_legacy_component_stylesheets(
    test_client: Any,
) -> None:
    """The shared shell must use Material CSS instead of DaisyUI/Tailwind."""
    client, _, _, _, _ = test_client
    response = await client.get("/projects/")
    assert response.status_code == 200
    body = response.text
    assert "static/css/material.css" in body
    assert "static/css/tailwind.css" not in body
    assert "vendor/daisyui/daisyui.css" not in body
    assert "vendor/daisyui/themes.css" not in body


@pytest.mark.asyncio
async def test_links_material_theme_and_shared_components(test_client: Any) -> None:
    """The shared Material 3 layer owns the common component vocabulary."""
    client, _, _, _, _ = test_client
    response = await client.get("/projects/")
    assert response.status_code == 200
    body = response.text
    assert body.index("static/css/material.css") < body.index("static/css/app.css")
    assert "md3-top-app-bar" in body
    assert "md3-footer" in body
    assert "md3-list-toolbar" in body
    assert "md3-navigation-menu" in body
    assert "md3-button--filled" in body
    assert "md3-text-field" in body
    css = (STATIC_CSS_DIR / "material.css").read_text()
    assert "--md-sys-color-primary" in css
    assert "--md-sys-typescale-title" in css
    assert "--md-sys-elevation-level-1" in css
    assert ".md3-button--filled" in css
    assert ".md3-card--elevated" in css
    assert ".md3-navigation-menu" in css


def test_feature_templates_use_semantic_material_components() -> None:
    """Feature pages must not regress to DaisyUI component class names."""
    template_root = Path(__file__).resolve().parent.parent / "stricknani" / "templates"
    offenders = []
    for relative_path in T88_FEATURE_TEMPLATES:
        contents = (template_root / relative_path).read_text(encoding="utf-8")
        if LEGACY_FEATURE_CLASS.search(contents):
            offenders.append(relative_path)
    assert not offenders, f"legacy component classes found: {', '.join(offenders)}"
    css = (STATIC_CSS_DIR / "material.css").read_text()
    assert ".md3-text-field" in css
    assert ".md3-dialog" in css
    assert ".md3-navigation-menu" in css
    assert ".md3-feature-card" in css
    assert ".md3-disclosure" in css
    assert ".md3-form-card" in css


def test_remaining_material_surfaces_use_semantic_components() -> None:
    """Navbar, categories, and generated form fragments stay on the M3 vocabulary."""
    template_root = Path(__file__).resolve().parent.parent / "stricknani" / "templates"
    navbar = (template_root / "shared/unified_navbar.html").read_text(encoding="utf-8")
    language = (template_root / "shared/_language_selector.html").read_text(
        encoding="utf-8"
    )
    categories = (template_root / "projects/categories.html").read_text(
        encoding="utf-8"
    )
    assert "md3-navbar__title-menu" in navbar
    assert "md3-app-logo" in navbar
    assert "navbar-nav-dropdown" not in navbar
    assert 'class="join' not in language
    assert 'class="btn' not in language
    assert "md3-language-selector" in language
    assert "md3-category-card" in categories
    assert 'class="card' not in categories
    assert 'class="input' not in categories

    for relative_path in ("projects/form.js", "yarn/form.js"):
        contents = (template_root / relative_path).read_text(encoding="utf-8")
        assert "md3-photo-tile" in contents
        assert "md3-step-item" in contents or relative_path == "yarn/form.js"
        assert not re.search(
            r'class="[^\"]*\s(?:btn|badge|card)(?:\s|[\"]|$)', contents
        )


def test_logo_animation_is_accessible() -> None:
    """The logo has a visible affordance without forcing motion on users."""
    css = (STATIC_CSS_DIR / "material.css").read_text(encoding="utf-8")
    assert ".md3-app-logo__link:hover" in css
    assert ".md3-app-logo__link:focus-visible" in css
    assert "@media (prefers-reduced-motion: reduce)" in css
    assert ".md3-app-logo__link:hover .md3-app-logo__image" in css
