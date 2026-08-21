"""Regression test for /static Cache-Control headers.

Static assets under /static (app.js, Material CSS,
vendored libraries) are not content-addressed -- their filenames stay stable
across deploys. A long-lived `immutable` Cache-Control (as opposed to /media,
whose filenames genuinely are unique per upload) would make browsers keep
serving pre-deploy bytes for up to a year, so CachedStaticFiles instead uses a
short max-age with mandatory revalidation.
"""

from typing import Any

import pytest


@pytest.mark.asyncio
async def test_static_assets_are_not_cached_immutably(test_client: Any) -> None:
    client, _, _, _, _ = test_client
    response = await client.get("/static/js/app.js")
    assert response.status_code == 200
    cache_control = response.headers["cache-control"]
    assert "immutable" not in cache_control
    assert "must-revalidate" in cache_control
