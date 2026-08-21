import socket
from collections.abc import AsyncGenerator, Generator
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import Base, Project, ProjectCategory, Step, User
from stricknani.routes.auth import (
    get_current_user,
    require_auth,
    require_auth_or_api_token,
)
from stricknani.utils.auth import get_password_hash
from stricknani.utils.rate_limit import reset_rate_limits


@pytest.fixture
def anyio_backend() -> str:
    """Run AnyIO tests on asyncio backend in this test environment."""
    return "asyncio"


@pytest.fixture(autouse=True)
def _hermetic_ssrf_dns(monkeypatch: pytest.MonkeyPatch) -> None:
    """Keep the suite offline.

    The SSRF guard (T52) resolves hostnames via ``socket.getaddrinfo`` before
    every import fetch. Stub it to a fixed public address so import-path tests
    that only mock HTTP don't require real DNS. Tests that assert DNS-based
    blocking (``tests/test_ssrf.py``) override this in-test.
    """

    def _public_getaddrinfo(*args: Any, **kwargs: Any) -> list[Any]:
        return [
            (
                socket.AF_INET,
                socket.SOCK_STREAM,
                socket.IPPROTO_TCP,
                "",
                ("93.184.216.34", 0),
            )
        ]

    monkeypatch.setattr(socket, "getaddrinfo", _public_getaddrinfo)


@pytest.fixture(autouse=True)
def _testing_flag() -> Generator[None]:
    """Force ``config.TESTING`` on for every test and restore it afterwards.

    ``config.TESTING`` is evaluated at import time, before pytest sets
    ``PYTEST_CURRENT_TEST``, so it defaults to ``False``. The suite relies on it
    being ``True`` (it short-circuits the global CSRF dependency). Previously
    only the ``test_client`` fixture set it, and it was never reset, so the
    value leaked between tests: whether a CSRF-agnostic test saw ``True``
    depended purely on ordering. Establishing it here makes the default
    deterministic; tests that need real CSRF validation flip it off explicitly.
    """
    original = config.TESTING
    config.TESTING = True
    try:
        yield
    finally:
        config.TESTING = original


@pytest.fixture(autouse=True)
def _reset_rate_limits() -> Generator[None]:
    """Clear auth rate-limit state (T69) so tests don't leak attempts across
    each other via the shared client IP used by the test transport."""
    reset_rate_limits()
    try:
        yield
    finally:
        reset_rate_limits()


@pytest.fixture
async def test_client(
    tmp_path: Any,
) -> AsyncGenerator[
    tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
]:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:?cache=shared")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async with session_factory() as session:
        user = User(
            email="tester@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)

        project = Project(
            name="Sample Project",
            category=ProjectCategory.SCHAL.value,
            owner_id=user.id,
        )
        session.add(project)
        await session.commit()
        await session.refresh(project)

        step = Step(title="Initial Step", step_number=1, project_id=project.id)
        session.add(step)
        await session.commit()
        await session.refresh(step)

        project_id = project.id
        user_id = user.id
        step_id = step.id

    config.TESTING = True
    original_media_root = config.MEDIA_ROOT
    config.MEDIA_ROOT = tmp_path / "media"
    config.ensure_media_dirs()

    async def override_get_db() -> AsyncGenerator[AsyncSession]:
        async with session_factory() as session:
            yield session

    class AuthUser:
        def __init__(self, user_id: int, email: str, is_admin: bool = False) -> None:
            self.id = user_id
            self.email = email
            self.profile_image = None
            self.is_admin = is_admin

    auth_user = AuthUser(user_id, "tester@example.com")

    async def override_auth() -> AuthUser:
        return auth_user

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[require_auth] = override_auth
    app.dependency_overrides[require_auth_or_api_token] = override_auth
    app.dependency_overrides[get_current_user] = override_auth

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client, session_factory, user_id, project_id, step_id

    app.dependency_overrides.clear()
    config.MEDIA_ROOT = original_media_root
    await engine.dispose()
