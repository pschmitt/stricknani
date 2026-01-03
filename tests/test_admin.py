from typing import Any

import pytest

from stricknani.main import app
from sqlalchemy import select

from stricknani.models import User
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.auth import get_password_hash


class AdminUser:
    def __init__(self, user_id: int, email: str) -> None:
        self.id = user_id
        self.email = email
        self.profile_image = None
        self.is_admin = True


@pytest.mark.asyncio
async def test_admin_users_requires_admin(test_client: Any) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/admin/users")

    assert response.status_code == 403


@pytest.mark.asyncio
async def test_admin_users_page_renders(test_client: Any) -> None:
    client, _session_factory, user_id, _project_id, _step_id = test_client

    async def override_admin() -> AdminUser:
        return AdminUser(user_id, "tester@example.com")

    app.dependency_overrides[require_auth] = override_admin
    app.dependency_overrides[get_current_user] = override_admin

    response = await client.get("/admin/users")

    assert response.status_code == 200
    assert "User Management" in response.text


@pytest.mark.asyncio
async def test_admin_toggle_active_updates_user(
    test_client: Any,
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        other = User(
            email="other@example.com",
            hashed_password=get_password_hash("secret"),
            is_active=True,
        )
        session.add(other)
        await session.commit()
        await session.refresh(other)
        other_id = other.id

    async def override_admin() -> AdminUser:
        return AdminUser(user_id, "tester@example.com")

    app.dependency_overrides[require_auth] = override_admin
    app.dependency_overrides[get_current_user] = override_admin

    response = await client.post(f"/admin/users/{other_id}/toggle-active")

    assert response.status_code == 303

    async with session_factory() as session:
        updated = await session.get(User, other_id)
        assert updated is not None
        assert updated.is_active is False


@pytest.mark.asyncio
async def test_admin_create_user(test_client: Any) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    async def override_admin() -> AdminUser:
        return AdminUser(user_id, "tester@example.com")

    app.dependency_overrides[require_auth] = override_admin
    app.dependency_overrides[get_current_user] = override_admin

    response = await client.post(
        "/admin/users/create",
        data={
            "email": "new-user@example.com",
            "password": "temporary-pass",
            "is_admin": "on",
        },
    )

    assert response.status_code == 303

    async with session_factory() as session:
        created = await session.execute(
            select(User).where(User.email == "new-user@example.com")
        )
        user = created.scalar_one_or_none()
        assert user is not None
        assert user.is_admin is True
