import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import Base, Project, ProjectCategory, Step, User
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.auth import get_password_hash


@pytest.fixture
async def test_client(
    tmp_path,
) -> tuple[
    AsyncClient,
    async_sessionmaker[AsyncSession],
    int,
    int,
    int,
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

    original_media_root = config.MEDIA_ROOT
    config.MEDIA_ROOT = tmp_path / "media"
    config.ensure_media_dirs()

    async def override_get_db():
        async with session_factory() as session:
            yield session

    class AuthUser:
        def __init__(self, user_id: int, email: str) -> None:
            self.id = user_id
            self.email = email
            self.profile_image = None

    auth_user = AuthUser(user_id, "tester@example.com")

    async def override_auth() -> AuthUser:
        return auth_user

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[require_auth] = override_auth
    app.dependency_overrides[get_current_user] = override_auth

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client, session_factory, user_id, project_id, step_id

    app.dependency_overrides.clear()
    config.MEDIA_ROOT = original_media_root
    await engine.dispose()
