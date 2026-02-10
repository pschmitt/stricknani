from unittest.mock import patch

import pytest

from stricknani.utils.wayback import _request_wayback_snapshot, should_skip_wayback_url


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://example.com/foo", True),
        ("https://example.org/foo", True),
        ("https://example.net/foo", True),
        ("https://foo.example.com/bar", True),
        ("https://foo.example.org/bar", True),
        ("https://foo.example.net/bar", True),
        ("https://localhost:7674/path", True),
        ("https://test/path", True),
        ("https://invalid/path", True),
        ("https://ravelry.com/patterns/library/foo", False),
        ("https://www.garnstudio.com/pattern.php?id=1234", False),
    ],
)
def test_should_skip_wayback_url(url: str, expected: bool) -> None:
    assert should_skip_wayback_url(url) is expected


@pytest.mark.asyncio
async def test_request_wayback_snapshot_skips_reserved_urls() -> None:
    with patch("waybackpy.Url") as wayback_url:
        result = await _request_wayback_snapshot("https://example.com/pattern")

    assert result is None
    wayback_url.assert_not_called()
