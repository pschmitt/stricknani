"""Tests for project-yarn cascading deletion and auto-linking."""

from typing import TYPE_CHECKING

import pytest
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from stricknani.models import Project, Yarn
from stricknani.routes.projects import _ensure_yarns_by_text, _get_exclusive_yarns

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


@pytest.mark.asyncio
async def test_ensure_yarns_by_text(test_client: "TestClientFixture") -> None:
    """Test matching and creating yarns by text."""
    _, session_factory, user_id, _, _ = test_client

    async with session_factory() as db:
        # 1. Create a yarn to match against
        existing_yarn = Yarn(name="Merino Soft", brand="Drops", owner_id=user_id)
        db.add(existing_yarn)
        await db.commit()

        # 2. Test matching existing yarn
        yarn_ids = await _ensure_yarns_by_text(
            db, user_id, "Merino Soft", [], yarn_brand="Drops"
        )
        assert len(yarn_ids) == 1
        assert yarn_ids[0] == existing_yarn.id

        # 3. Test creating new yarn
        new_yarn_ids = await _ensure_yarns_by_text(
            db, user_id, "Alpaca Cloud", [], yarn_brand="Garnstudio"
        )
        assert len(new_yarn_ids) == 1
        new_yarn_id = new_yarn_ids[0]
        assert new_yarn_id != existing_yarn.id

        res = await db.execute(select(Yarn).where(Yarn.id == new_yarn_id))
        new_yarn = res.scalar_one()
        assert new_yarn.name == "Alpaca Cloud"
        assert new_yarn.brand == "Garnstudio"

        # 4. Test multiple yarns (comma separated)
        multi_ids = await _ensure_yarns_by_text(
            db, user_id, "Merino Soft, Silk Dream", []
        )
        assert len(multi_ids) == 2
        # Merino Soft should match existing, Silk Dream should be new
        assert existing_yarn.id in multi_ids


@pytest.mark.asyncio
async def test_get_exclusive_yarns(test_client: "TestClientFixture") -> None:
    """Test identifying yarns used only by a single project."""
    _, session_factory, user_id, _, _ = test_client

    async with session_factory() as db:
        # Create two yarns
        yarn1 = Yarn(name="Exclusive Yarn", owner_id=user_id)
        yarn2 = Yarn(name="Shared Yarn", owner_id=user_id)
        db.add_all([yarn1, yarn2])
        await db.commit()

        # Create two projects
        project1 = Project(name="Project 1", owner_id=user_id)
        project2 = Project(name="Project 2", owner_id=user_id)
        project1.yarns = [yarn1, yarn2]
        project2.yarns = [yarn2]
        db.add_all([project1, project2])
        await db.commit()

        # Re-load project1 with yarns
        res = await db.execute(
            select(Project)
            .where(Project.id == project1.id)
            .options(selectinload(Project.yarns))
        )
        project1 = res.scalar_one()

        # identify exclusive yarns for project1
        # Only yarn1 should be exclusive
        exclusive = await _get_exclusive_yarns(db, project1)
        assert len(exclusive) == 1
        assert exclusive[0].id == yarn1.id


@pytest.mark.asyncio
async def test_delete_project_cascade_yarns(test_client: "TestClientFixture") -> None:
    """Test that exclusive yarns are deleted when requested."""
    client, session_factory, user_id, _, _ = test_client

    async with session_factory() as db:
        # Setup: Project with one exclusive and one shared yarn
        yarn_excl = Yarn(name="To be deleted", owner_id=user_id)
        yarn_shared = Yarn(name="To be kept", owner_id=user_id)
        db.add_all([yarn_excl, yarn_shared])
        await db.commit()

        project = Project(name="Delete Me", owner_id=user_id)
        project.yarns = [yarn_excl, yarn_shared]
        other_project = Project(name="Keep Me", owner_id=user_id)
        other_project.yarns = [yarn_shared]
        db.add_all([project, other_project])
        await db.commit()
        p_id = project.id
        excl_id = yarn_excl.id
        shared_id = yarn_shared.id

    # Delete project with delete_yarns=true (non-HTMX)
    response = await client.delete(f"/projects/{p_id}?delete_yarns=true")
    assert response.status_code == 303

    async with session_factory() as db:
        # Check project is gone
        res = await db.execute(select(Project).where(Project.id == p_id))
        assert res.scalar_one_or_none() is None

        # Check exclusive yarn is gone
        res = await db.execute(select(Yarn).where(Yarn.id == excl_id))
        assert res.scalar_one_or_none() is None

        # Check shared yarn still exists
        res = await db.execute(select(Yarn).where(Yarn.id == shared_id))
        assert res.scalar_one_or_none() is not None


@pytest.mark.asyncio
async def test_delete_project_htmx_status(test_client: "TestClientFixture") -> None:
    """Test that HTMX delete returns 200."""
    client, _, user_id, _, _ = test_client

    # Create project to delete
    from stricknani.models import Project

    async with test_client[1]() as db:
        project = Project(name="HTMX delete", owner_id=user_id)
        db.add(project)
        await db.commit()
        p_id = project.id

    response = await client.delete(f"/projects/{p_id}", headers={"HX-Request": "true"})
    assert response.status_code == 200
