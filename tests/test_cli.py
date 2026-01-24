import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

import stricknani.utils.importer as importer
from stricknani.models import Project, ProjectCategory, Step, Yarn
from stricknani.scripts import cli


@pytest.mark.asyncio
async def test_cli_lists_projects_and_yarns(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _client, session_factory, user_id, project_id, _step_id = test_client

    async with session_factory() as session:
        yarn = Yarn(name="CLI Yarn", owner_id=user_id)
        session.add(yarn)
        await session.commit()
        await session.refresh(yarn)
        yarn_id = yarn.id

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    await cli.list_projects("tester@example.com")
    output = capsys.readouterr().out
    assert f"ID: {project_id}" in output

    await cli.list_yarns("tester@example.com")
    output = capsys.readouterr().out
    assert f"ID: {yarn_id}" in output


@pytest.mark.asyncio
async def test_cli_deletes_project_and_yarn(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        project = Project(
            name="CLI Delete Project",
            category=ProjectCategory.SCHAL.value,
            owner_id=user_id,
        )
        yarn = Yarn(name="CLI Delete Yarn", owner_id=user_id)
        session.add_all([project, yarn])
        await session.commit()
        await session.refresh(project)
        await session.refresh(yarn)
        project_id = project.id
        yarn_id = yarn.id

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    await cli.delete_project(project_id, "tester@example.com")
    await cli.delete_yarn(yarn_id, "tester@example.com")

    async with session_factory() as session:
        project_result = await session.execute(
            select(Project).where(Project.id == project_id)
        )
        yarn_result = await session.execute(select(Yarn).where(Yarn.id == yarn_id))

    assert project_result.scalar_one_or_none() is None
    assert yarn_result.scalar_one_or_none() is None


@pytest.mark.asyncio
async def test_cli_adds_project_and_yarn(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _client, session_factory, _user_id, _project_id, _step_id = test_client

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    await cli.add_project(
        "tester@example.com",
        "CLI Added Project",
        "Schal",
        "Merino",
        "4.0mm",
        None,
        20,
        28,
        "Notes",
        "one, two",
        "https://example.com",
    )

    await cli.add_yarn(
        "tester@example.com",
        "CLI Added Yarn",
        "Brand",
        "Blue",
        "D123",
        "Wool",
        "DK",
        "4.0mm",
        100,
        220,
        "Soft",
        "https://example.com/yarn",
    )

    async with session_factory() as session:
        project_result = await session.execute(
            select(Project).where(Project.name == "CLI Added Project")
        )
        yarn_result = await session.execute(
            select(Yarn).where(Yarn.name == "CLI Added Yarn")
        )

    project = project_result.scalar_one()
    yarn = yarn_result.scalar_one()

    assert project.tag_list() == ["one", "two"]
    assert yarn.brand == "Brand"


@pytest.mark.asyncio
async def test_cli_imports_project_url(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _client, session_factory, _user_id, _project_id, _step_id = test_client

    async def fake_init_db() -> None:
        return None

    async def fake_fetch_and_parse(self: object) -> dict[str, object]:
        return {
            "title": "Imported CLI Project",
            "needles": "3.5mm",
            "yarn": "Imported Yarn",
            "gauge_stitches": 22,
            "gauge_rows": 30,
            "comment": "Imported notes",
            "link": "https://example.com/pattern",
            "steps": [
                {
                    "step_number": 1,
                    "title": "Cast on",
                    "description": "Cast on 100 stitches.",
                },
                {
                    "step_number": 2,
                    "title": "Knit",
                    "description": "Knit every row.",
                },
            ],
        }

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)
    monkeypatch.setattr(
        importer.PatternImporter, "fetch_and_parse", fake_fetch_and_parse
    )

    await cli.import_project_url(
        "tester@example.com",
        "https://example.com/pattern",
        use_ai=False,
        import_images=False,
    )

    async with session_factory() as session:
        project_result = await session.execute(
            select(Project).where(Project.name == "Imported CLI Project")
        )
        project = project_result.scalar_one()
        steps_result = await session.execute(
            select(Step)
            .where(Step.project_id == project.id)
            .order_by(Step.step_number)
        )

    steps = steps_result.scalars().all()

    assert project.needles == "3.5mm"
    assert project.link == "https://example.com/pattern"
    assert [step.title for step in steps] == ["Cast on", "Knit"]
