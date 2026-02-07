from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from typing import Any

import httpx
import pytest
from PIL import Image as PilImage

from stricknani.utils.files import compute_checksum
from stricknani.utils.importer import filter_import_image_urls


@dataclass
class _FakeResponse:
    content: bytes
    headers: dict[str, str]

    def raise_for_status(self) -> None:
        return


class _FakeAsyncClient:
    def __init__(self, url_to_bytes: dict[str, bytes]) -> None:
        self._url_to_bytes = url_to_bytes

    async def __aenter__(self) -> _FakeAsyncClient:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        return

    async def get(self, url: str) -> _FakeResponse:
        if url not in self._url_to_bytes:
            raise httpx.HTTPError("not found")
        return _FakeResponse(
            content=self._url_to_bytes[url],
            headers={"content-type": "image/png"},
        )


def _png_bytes(color: tuple[int, int, int]) -> bytes:
    img = PilImage.new("RGB", (64, 64), color)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


@pytest.mark.asyncio
async def test_filter_import_image_urls_skips_known_checksums(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url1 = "https://example.com/a.png"
    url2 = "https://example.com/b.png"

    png1 = _png_bytes((255, 0, 0))
    png2 = _png_bytes((0, 255, 0))
    checksum1 = compute_checksum(png1)

    url_to_bytes = {url1: png1, url2: png2}
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: _FakeAsyncClient(url_to_bytes),
    )

    res = await filter_import_image_urls(
        [url1, url2],
        skip_checksums={checksum1},
    )

    assert res == [url2]
