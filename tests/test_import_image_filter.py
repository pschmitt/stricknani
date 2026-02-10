from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from typing import Any

import httpx
import pytest
from PIL import Image as PilImage

from stricknani.utils.files import compute_checksum
from stricknani.utils.image_similarity import build_similarity_image
from stricknani.utils.importer import filter_import_image_urls


@dataclass
class _FakeResponse:
    content: bytes
    headers: dict[str, str]

    def raise_for_status(self) -> None:
        return


class _FakeAsyncClient:
    def __init__(self, url_to_payload: dict[str, tuple[bytes, str]]) -> None:
        self._url_to_payload = url_to_payload

    async def __aenter__(self) -> _FakeAsyncClient:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        return

    async def get(self, url: str) -> _FakeResponse:
        if url not in self._url_to_payload:
            raise httpx.HTTPError("not found")
        content, content_type = self._url_to_payload[url]
        return _FakeResponse(
            content=content,
            headers={"content-type": content_type},
        )


def _png_bytes(color: tuple[int, int, int]) -> bytes:
    img = PilImage.new("RGB", (64, 64), color)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _png_bytes_size(color: tuple[int, int, int], size: tuple[int, int]) -> bytes:
    img = PilImage.new("RGB", size, color)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _jpeg_bytes(color: tuple[int, int, int], *, quality: int = 70) -> bytes:
    img = PilImage.new("RGB", (64, 64), color)
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=quality, optimize=True)
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

    url_to_payload = {
        url1: (png1, "image/png"),
        url2: (png2, "image/png"),
    }
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: _FakeAsyncClient(url_to_payload),
    )

    res = await filter_import_image_urls(
        [url1, url2],
        skip_checksums={checksum1},
    )

    assert res == [url2]


@pytest.mark.asyncio
async def test_filter_import_image_urls_skips_similar_existing_images(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url1 = "https://example.com/a.jpg"
    url2 = "https://example.com/b.png"

    # Same pixels, different encoding: checksum will differ.
    existing_png = _png_bytes((255, 0, 0))
    candidate_jpeg = _jpeg_bytes((255, 0, 0), quality=60)
    png2 = _png_bytes((0, 255, 0))

    with PilImage.open(BytesIO(existing_png)) as img:
        existing_similarity = build_similarity_image(img)

    url_to_payload = {
        url1: (candidate_jpeg, "image/jpeg"),
        url2: (png2, "image/png"),
    }
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: _FakeAsyncClient(url_to_payload),
    )

    res = await filter_import_image_urls(
        [url1, url2],
        skip_similarities=[existing_similarity],
    )

    assert res == [url2]


@pytest.mark.asyncio
async def test_filter_import_image_urls_prefers_larger_duplicate_variant(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    small = "https://example.com/small.png"
    large = "https://example.com/large.png"

    small_png = _png_bytes_size((255, 0, 0), (64, 64))
    large_png = _png_bytes_size((255, 0, 0), (128, 128))

    url_to_payload = {
        small: (small_png, "image/png"),
        large: (large_png, "image/png"),
    }
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: _FakeAsyncClient(url_to_payload),
    )

    res = await filter_import_image_urls([small, large])

    assert res == [large]
