"""Tests for the SSRF guard and friendly import-fetch error handling."""

from __future__ import annotations

import socket
from typing import TYPE_CHECKING, Any

import pytest

from stricknani.importing.fetch import FetchError, import_fetch_http_error
from stricknani.importing.ssrf import SSRFError, validate_public_url

if TYPE_CHECKING:
    from httpx import AsyncClient
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    TestClientFixture = tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ]


def _addrinfo(ip: str) -> list[tuple[Any, ...]]:
    """Build a minimal getaddrinfo() return value for a single IPv4 address."""
    return [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (ip, 0))]


# --- Guard unit tests ------------------------------------------------------


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/",
        "http://169.254.169.254/latest/meta-data/",
        "http://10.0.0.1/",
        "http://192.168.1.1/",
        "http://[::1]/",
        "http://0.0.0.0/",
    ],
)
def test_validate_public_url_rejects_internal_ip_literals(url: str) -> None:
    """IP literals pointing at internal ranges are rejected without DNS."""
    with pytest.raises(SSRFError):
        validate_public_url(url)


def test_validate_public_url_rejects_localhost(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A hostname resolving to loopback is rejected."""
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: _addrinfo("127.0.0.1"))
    with pytest.raises(SSRFError):
        validate_public_url("http://localhost/admin")


def test_validate_public_url_rejects_public_host_resolving_internal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A public-looking host that resolves to a private IP is rejected."""
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: _addrinfo("10.1.2.3"))
    with pytest.raises(SSRFError):
        validate_public_url("https://evil.example.com/")


def test_validate_public_url_allows_public_host(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A host resolving to a public IP is allowed (deterministic, offline)."""
    monkeypatch.setattr(
        socket, "getaddrinfo", lambda *a, **k: _addrinfo("93.184.216.34")
    )
    validate_public_url("https://example.com/pattern")


def test_validate_public_url_rejects_non_http_scheme() -> None:
    """Only http/https schemes are permitted."""
    with pytest.raises(SSRFError):
        validate_public_url("ftp://example.com/x")
    with pytest.raises(SSRFError):
        validate_public_url("file:///etc/passwd")


def test_validate_public_url_rejects_unresolvable_host(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A host that cannot be resolved is rejected."""

    def _boom(*args: Any, **kwargs: Any) -> Any:
        raise socket.gaierror("no such host")

    monkeypatch.setattr(socket, "getaddrinfo", _boom)
    with pytest.raises(SSRFError):
        validate_public_url("https://does-not-resolve.invalid/")


def test_validate_public_url_allow_private_bypass() -> None:
    """The opt-out flag permits internal hosts."""
    validate_public_url("http://127.0.0.1/", allow_private=True)


# --- fetch_url redirect re-validation --------------------------------------


class _FakeCurlResponse:
    def __init__(
        self, status_code: int, headers: dict[str, str], text: str = ""
    ) -> None:
        self.status_code = status_code
        self.headers = headers
        self.text = text
        self.content = text.encode()
        self.encoding = None

    def raise_for_status(self) -> None:
        return None


class _FakeCurlSession:
    def __init__(self, responses: list[_FakeCurlResponse]) -> None:
        self._responses = responses
        self.requested: list[str] = []

    async def __aenter__(self) -> _FakeCurlSession:
        return self

    async def __aexit__(self, *args: Any) -> bool:
        return False

    async def get(self, url: str, **kwargs: Any) -> _FakeCurlResponse:
        self.requested.append(url)
        return self._responses.pop(0)


@pytest.mark.asyncio
async def test_fetch_url_blocks_redirect_to_internal_host(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A redirect whose target resolves internal is rejected before fetching."""
    import curl_cffi.requests

    # Initial host resolves to a public IP so the first hop is allowed.
    monkeypatch.setattr(
        socket, "getaddrinfo", lambda *a, **k: _addrinfo("93.184.216.34")
    )

    redirect = _FakeCurlResponse(
        302, {"location": "http://169.254.169.254/latest/meta-data/"}
    )
    session = _FakeCurlSession([redirect])
    monkeypatch.setattr(curl_cffi.requests, "AsyncSession", lambda *a, **k: session)

    from stricknani.importing.fetch import fetch_url

    with pytest.raises(SSRFError):
        await fetch_url("http://external.example.com/start")

    # Only the first hop was actually requested; the internal target was not.
    assert session.requested == ["http://external.example.com/start"]


@pytest.mark.asyncio
async def test_fetch_url_blocks_internal_initial_url() -> None:
    """The initial URL is validated before any request is made."""
    from stricknani.importing.fetch import fetch_url

    with pytest.raises(SSRFError):
        await fetch_url("http://169.254.169.254/latest/meta-data/")


# --- import_fetch_http_error mapping ---------------------------------------


def test_import_fetch_http_error_mapping() -> None:
    """Fetch/SSRF failures map to friendly 4xx/5xx statuses."""
    assert import_fetch_http_error(SSRFError("blocked"))[0] == 400
    assert import_fetch_http_error(FetchError("nope", status_code=404))[0] == 400
    assert import_fetch_http_error(FetchError("boom", status_code=503))[0] == 502
    assert import_fetch_http_error(FetchError("timeout"))[0] == 504
    # The raw exception string is never echoed back to the user.
    for exc in (
        SSRFError("secret-internal-host"),
        FetchError("secret-connection-detail", status_code=404),
        FetchError("secret-timeout-detail"),
    ):
        _, detail = import_fetch_http_error(exc)
        assert "secret" not in detail


# --- Route boundary (T54): friendly errors, no 500 / no raw leak -----------


@pytest.mark.asyncio
async def test_project_import_fetch_error_returns_4xx(
    test_client: TestClientFixture,
) -> None:
    """An upstream 4xx during fetch yields 400, not 500, and hides the error."""
    from unittest.mock import patch

    client, _, _, _, _ = test_client

    async def _raise(*args: Any, **kwargs: Any) -> Any:
        raise FetchError("secret-upstream-detail", status_code=404)

    with patch("stricknani.importing.fetch.fetch_url", side_effect=_raise):
        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/missing",
                "use_ai": False,
            },
        )

    assert response.status_code == 400
    assert "secret-upstream-detail" not in response.text


@pytest.mark.asyncio
async def test_project_import_connection_error_is_not_500(
    test_client: TestClientFixture,
) -> None:
    """A connection/timeout error yields a gateway 5xx, never a 500."""
    from unittest.mock import patch

    client, _, _, _, _ = test_client

    async def _raise(*args: Any, **kwargs: Any) -> Any:
        raise FetchError("secret-connection-detail")

    with patch("stricknani.importing.fetch.fetch_url", side_effect=_raise):
        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/down",
                "use_ai": False,
            },
        )

    assert response.status_code in {502, 504}
    assert response.status_code != 500
    assert "secret-connection-detail" not in response.text


@pytest.mark.asyncio
async def test_project_import_ssrf_url_returns_400(
    test_client: TestClientFixture,
) -> None:
    """Importing an internal URL is blocked at the boundary with a 400."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/projects/import",
        data={
            "type": "url",
            "url": "http://169.254.169.254/latest/meta-data/",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert response.status_code != 500


@pytest.mark.asyncio
async def test_yarn_import_fetch_error_returns_4xx(
    test_client: TestClientFixture,
) -> None:
    """The yarn import path also translates fetch failures to friendly 4xx."""
    from unittest.mock import patch

    client, _, _, _, _ = test_client

    async def _raise(*args: Any, **kwargs: Any) -> Any:
        raise FetchError("secret-yarn-detail", status_code=404)

    with patch("stricknani.importing.fetch.fetch_url", side_effect=_raise):
        response = await client.post(
            "/yarn/import",
            data={
                "type": "url",
                "url": "https://example.com/missing-yarn",
                "use_ai": False,
            },
        )

    assert response.status_code == 400
    assert "secret-yarn-detail" not in response.text


@pytest.mark.asyncio
async def test_yarn_import_ssrf_url_returns_400(
    test_client: TestClientFixture,
) -> None:
    """The yarn import path blocks internal URLs with a 400."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/yarn/import",
        data={
            "type": "url",
            "url": "http://127.0.0.1/admin",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert response.status_code != 500
