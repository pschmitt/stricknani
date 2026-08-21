"""Regression tests for the vendored TipTap bundle (T73).

These verify wysiwyg_editor.js no longer imports TipTap from esm.sh (a CDN
import that also violates the strict CSP's `script-src 'self'` from T71) and
instead loads a single self-contained bundle built by `just vendor-tiptap`.
"""

import subprocess
from pathlib import Path
from typing import Any

import pytest

VENDOR_TIPTAP_DIR = (
    Path(__file__).resolve().parent.parent / "stricknani" / "static" / "vendor-tiptap"
)
BUNDLE_PATH = VENDOR_TIPTAP_DIR / "tiptap-bundle.min.js"


@pytest.mark.asyncio
async def test_wysiwyg_editor_js_has_no_cdn_import(test_client: Any) -> None:
    """The editor script must not import TipTap from a CDN."""
    client, _, _, _, _ = test_client
    response = await client.get("/static/js/features/wysiwyg_editor.js")
    assert response.status_code == 200
    body = response.text
    assert "https://esm.sh" not in body
    assert "/static/vendor-tiptap/tiptap-bundle.min.js" in body


@pytest.mark.asyncio
async def test_tiptap_bundle_is_served_and_self_contained(
    test_client: Any,
) -> None:
    """The vendored bundle must be servable and free of external imports."""
    client, _, _, _, _ = test_client
    response = await client.get("/static/vendor-tiptap/tiptap-bundle.min.js")
    assert response.status_code == 200
    body = response.text

    # A bundle that still failed to resolve an import would leave a literal
    # `from"@tiptap/..."`/`from"prosemirror-..."` specifier in the output
    # (as opposed to e.g. a CSS class name that merely contains the text
    # "prosemirror-").
    assert "https://esm.sh" not in body
    assert 'from"@tiptap/' not in body
    assert "from'@tiptap/" not in body
    assert 'from"prosemirror-' not in body
    assert "from'prosemirror-" not in body

    # Sanity check it's the real bundle, not an empty/stub file.
    assert len(body) > 100_000
    for expected in ("Editor", "StarterKit", "mergeAttributes"):
        assert expected in body


_ESBUILD_BIN = VENDOR_TIPTAP_DIR / "node_modules" / ".bin" / "esbuild"


@pytest.mark.skipif(
    not _ESBUILD_BIN.exists(),
    reason="node_modules/.bin/esbuild missing; run `just vendor-tiptap` first "
    "(this test avoids `npm install`/`ci` so the suite stays offline)",
)
def test_tiptap_bundle_builds_reproducibly(tmp_path: Path) -> None:
    """Rebuilding from entry.js with the already-installed esbuild must
    reproduce the exact committed bundle -- i.e. `tiptap-bundle.min.js` isn't
    stale relative to `entry.js`/the pinned versions in `package.json`."""
    output = tmp_path / "tiptap-bundle.min.js"
    subprocess.run(
        [
            str(_ESBUILD_BIN),
            "entry.js",
            "--bundle",
            "--format=esm",
            "--minify",
            f"--outfile={output}",
        ],
        cwd=VENDOR_TIPTAP_DIR,
        check=True,
        capture_output=True,
    )

    assert output.read_text() == BUNDLE_PATH.read_text()
