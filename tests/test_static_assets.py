"""Regression tests for the static CSS bundle (T1).

These verify base.html no longer loads Tailwind at runtime (browser JIT /
CDN script) and instead links a prebuilt static CSS bundle. This is a
prerequisite for a strict CSP (no inline/eval-ish runtime script needed for
styling).
"""

import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest

STATIC_CSS_DIR = (
    Path(__file__).resolve().parent.parent / "stricknani" / "static" / "css"
)
TAILWIND_INPUT = STATIC_CSS_DIR / "tailwind.input.css"


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
    assert "md3-filter-surface" in body
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
    assert ".md3-text-field" in css
    assert ".md3-dialog" in css
    assert ".md3-navigation-menu" in css


@pytest.mark.skipif(
    shutil.which("tailwindcss") is None,
    reason="tailwindcss CLI not on PATH (expected inside the nix devShell)",
)
def test_tailwind_css_bundle_builds_and_contains_expected_utilities(
    tmp_path: Path,
) -> None:
    """`just build-css` must produce a non-trivial CSS bundle with the
    utility classes actually used by the templates (e.g. daisyUI-backed
    color tokens and dark-mode variants)."""
    output = tmp_path / "tailwind.css"
    subprocess.run(
        [
            "tailwindcss",
            "-i",
            str(TAILWIND_INPUT),
            "-o",
            str(output),
            "--minify",
        ],
        check=True,
        capture_output=True,
    )

    css = output.read_text()

    # Non-trivial size: a bare `@import "tailwindcss";` with no matched
    # classes would produce only a few hundred bytes of resets/preflight.
    assert len(css) > 20_000

    for expected in (
        ".flex{",
        ".bg-base-300",
        ".text-base-content",
        ".rounded-box",
        "dark\\:bg-base-300",
    ):
        assert expected in css, (
            f"expected utility {expected!r} missing from build output"
        )
