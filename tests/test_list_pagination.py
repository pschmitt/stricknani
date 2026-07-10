"""Tests for paginated + SQL-ordered project/yarn list views (T64)."""

import re

import pytest
from httpx import AsyncClient
from sqlalchemy import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.models import Project, Yarn, user_favorite_yarns, user_favorites
from stricknani.routes.projects import LIST_PAGE_SIZE

_PROJECT_ID_RE = re.compile(r'data-project-card data-project-id="(\d+)"')
_YARN_ID_RE = re.compile(r'data-yarn-card data-yarn-id="(\d+)"')


def _ids(pattern: re.Pattern[str], html: str) -> list[int]:
    return [int(match) for match in pattern.findall(html)]


@pytest.mark.asyncio
async def test_project_list_pagination_and_ordering(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, user_id, existing_project_id, _step_id = test_client

    # Insert enough projects to require a second page. Names are shuffled and
    # inserted in reverse-alphabetical order so a created_at sort would differ
    # from the expected name sort, proving the ORDER BY happens in SQL.
    total_extra = LIST_PAGE_SIZE + 6
    names = [f"Proj-{i:02d}" for i in range(total_extra)]
    async with session_factory() as session:
        for name in reversed(names):
            session.add(Project(name=name, owner_id=user_id))
        await session.commit()

        # Mark two projects as favorites whose names would otherwise sort in the
        # middle, so favorites-first ordering is observable.
        fav_names = {"Proj-20", "Proj-25"}
        rows = (await session.execute(Project.__table__.select())).mappings().all()
        favorite_ids = {row["id"] for row in rows if row["name"] in fav_names}
        for project_id in favorite_ids:
            await session.execute(
                insert(user_favorites).values(user_id=user_id, project_id=project_id)
            )
        await session.commit()

        all_rows = [(row["id"], row["name"]) for row in rows]

    # Expected order: favorites first, then case-insensitive name, then id.
    expected = [
        pid
        for pid, _name in sorted(
            all_rows,
            key=lambda item: (
                item[0] not in favorite_ids,
                item[1].casefold(),
                item[0],
            ),
        )
    ]
    total = len(expected)
    assert total == total_extra + 1  # + the fixture's "Sample Project"

    # Page 1
    page1 = await client.get("/projects/?page=1")
    assert page1.status_code == 200
    page1_ids = _ids(_PROJECT_ID_RE, page1.text)
    assert len(page1_ids) == LIST_PAGE_SIZE
    assert page1_ids == expected[:LIST_PAGE_SIZE]
    # Favorites come first.
    assert set(page1_ids[: len(favorite_ids)]) == favorite_ids

    # Page 2
    page2 = await client.get("/projects/?page=2")
    assert page2.status_code == 200
    page2_ids = _ids(_PROJECT_ID_RE, page2.text)
    assert len(page2_ids) == total - LIST_PAGE_SIZE
    assert page2_ids == expected[LIST_PAGE_SIZE:]

    # No overlap; the two pages reconstruct the full ordered set.
    assert set(page1_ids).isdisjoint(page2_ids)
    assert page1_ids + page2_ids == expected
    assert existing_project_id in expected


@pytest.mark.asyncio
async def test_project_list_htmx_infinite_scroll_fragment(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    total_extra = LIST_PAGE_SIZE + 6
    async with session_factory() as session:
        for i in range(total_extra):
            session.add(Project(name=f"Scroll-{i:02d}", owner_id=user_id))
        await session.commit()

    # First page (HTMX search/reset) carries a sentinel pointing at page 2.
    partial = await client.get("/projects/", headers={"HX-Request": "true"})
    assert partial.status_code == 200
    assert "data-infinite-scroll" in partial.text
    assert "page=2" in partial.text
    # It is a fragment: no full HTML document chrome.
    assert "<html" not in partial.text.lower()

    # Second page fragment returns cards only; it is the last page, so there is
    # no further sentinel.
    fragment = await client.get("/projects/?page=2", headers={"HX-Request": "true"})
    assert fragment.status_code == 200
    assert "data-project-card" in fragment.text
    assert "data-infinite-scroll" not in fragment.text
    assert "page=3" not in fragment.text


@pytest.mark.asyncio
async def test_yarn_list_pagination_and_ordering(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    total = LIST_PAGE_SIZE + 4
    names = [f"Yarn-{i:02d}" for i in range(total)]
    async with session_factory() as session:
        for name in reversed(names):
            session.add(Yarn(name=name, owner_id=user_id))
        await session.commit()

        fav_names = {"Yarn-18", "Yarn-22"}
        rows = (await session.execute(Yarn.__table__.select())).mappings().all()
        favorite_ids = {row["id"] for row in rows if row["name"] in fav_names}
        for yarn_id in favorite_ids:
            await session.execute(
                insert(user_favorite_yarns).values(user_id=user_id, yarn_id=yarn_id)
            )
        await session.commit()
        all_rows = [(row["id"], row["name"]) for row in rows]

    expected = [
        yid
        for yid, _name in sorted(
            all_rows,
            key=lambda item: (
                item[0] not in favorite_ids,
                item[1].casefold(),
                item[0],
            ),
        )
    ]

    page1 = await client.get("/yarn/?page=1")
    assert page1.status_code == 200
    page1_ids = _ids(_YARN_ID_RE, page1.text)
    assert len(page1_ids) == LIST_PAGE_SIZE
    assert page1_ids == expected[:LIST_PAGE_SIZE]
    assert set(page1_ids[: len(favorite_ids)]) == favorite_ids

    page2 = await client.get("/yarn/?page=2")
    assert page2.status_code == 200
    page2_ids = _ids(_YARN_ID_RE, page2.text)
    assert len(page2_ids) == total - LIST_PAGE_SIZE
    assert page2_ids == expected[LIST_PAGE_SIZE:]
    assert page1_ids + page2_ids == expected
