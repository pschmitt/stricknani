import sys

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

import stricknani.utils.importer as importer
from stricknani.models import AuditLog, Project, ProjectCategory, Step, Yarn
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
    assert str(project_id) in output
    assert "Sample Project" in output

    await cli.list_yarns("tester@example.com")
    output = capsys.readouterr().out
    assert str(yarn_id) in output
    assert "CLI Yarn" in output


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
            "notes": "Imported notes",
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
        "https://example.com/pattern",
        use_ai=False,
        import_images=False,
        owner_email="tester@example.com",
    )

    async with session_factory() as session:
        project_result = await session.execute(
            select(Project).where(Project.name == "Imported CLI Project")
        )
        project = project_result.scalar_one()
        steps_result = await session.execute(
            select(Step).where(Step.project_id == project.id).order_by(Step.step_number)
        )

    steps = steps_result.scalars().all()

    assert project.needles == "3.5mm"
    assert project.link == "https://example.com/pattern"
    assert [step.title for step in steps] == ["Cast on", "Knit"]


@pytest.mark.asyncio
async def test_cli_lists_audit_entries(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _client, session_factory, user_id, project_id, _step_id = test_client

    async with session_factory() as session:
        session.add(
            AuditLog(
                actor_user_id=user_id,
                entity_type="project",
                entity_id=project_id,
                action="created",
            )
        )
        await session.commit()

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    await cli.list_audit_entries(entity_type="project", entity_id=project_id, limit=10)
    output = capsys.readouterr().out
    assert "created" in output


def test_cli_project_defaults_to_list(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_list_projects(owner_email: str | None) -> None:
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "list_projects", fake_list_projects)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "project"])

    cli.main()

    assert captured["owner_email"] is None


def test_cli_yarn_defaults_to_list(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_list_yarns(owner_email: str | None) -> None:
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "list_yarns", fake_list_yarns)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "yarn"])

    cli.main()

    assert captured["owner_email"] is None


def test_cli_user_list_dispatches(monkeypatch: pytest.MonkeyPatch) -> None:
    captured = {"called": False}

    async def fake_list_users() -> None:
        captured["called"] = True

    monkeypatch.setattr(cli, "list_users", fake_list_users)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "user", "list"])
    cli.main()

    assert captured["called"] is True


def test_cli_project_list_with_owner_email_dispatches(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_list_projects(owner_email: str | None) -> None:
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "list_projects", fake_list_projects)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "project",
            "list",
            "--owner-email",
            "tester@example.com",
        ],
    )
    cli.main()

    assert captured["owner_email"] == "tester@example.com"


def test_cli_yarn_list_with_owner_email_dispatches(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_list_yarns(owner_email: str | None) -> None:
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "list_yarns", fake_list_yarns)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "yarn",
            "list",
            "--owner-email",
            "tester@example.com",
        ],
    )
    cli.main()

    assert captured["owner_email"] == "tester@example.com"


def test_cli_audit_list_dispatches(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_list_audit_entries(
        *,
        entity_type: str,
        entity_id: int,
        limit: int,
    ) -> None:
        captured["entity_type"] = entity_type
        captured["entity_id"] = entity_id
        captured["limit"] = limit

    monkeypatch.setattr(cli, "list_audit_entries", fake_list_audit_entries)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "audit",
            "list",
            "--entity-type",
            "project",
            "--entity-id",
            "7",
            "--limit",
            "12",
        ],
    )
    cli.main()

    assert captured["entity_type"] == "project"
    assert captured["entity_id"] == 7
    assert captured["limit"] == 12


def test_cli_api_projects_dispatches(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_api_request(
        url: str,
        endpoint: str,
        email: str,
        password: str,
    ) -> None:
        captured["url"] = url
        captured["endpoint"] = endpoint
        captured["email"] = email
        captured["password"] = password

    monkeypatch.setattr(cli, "api_request", fake_api_request)
    monkeypatch.setattr(cli, "prompt_password", lambda confirm=False: "secret")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "api",
            "--url",
            "http://localhost:7674",
            "--email",
            "tester@example.com",
            "projects",
        ],
    )
    cli.main()

    assert captured["url"] == "http://localhost:7674"
    assert captured["endpoint"] == "/projects/"
    assert captured["email"] == "tester@example.com"
    assert captured["password"] == "secret"


def test_cli_project_lookup_dispatches_to_show(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_show_project(query: str, owner_email: str | None) -> None:
        captured["query"] = query
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "show_project", fake_show_project)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "project", "Sample"])
    cli.main()

    assert captured["query"] == "Sample"
    assert captured["owner_email"] is None


def test_cli_project_show_accepts_positional_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_show_project(query: str, owner_email: str | None) -> None:
        captured["query"] = query
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "show_project", fake_show_project)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "project", "show", "Sample"])
    cli.main()

    assert captured["query"] == "Sample"
    assert captured["owner_email"] is None


def test_cli_project_show_accepts_legacy_query_flag(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_show_project(query: str, owner_email: str | None) -> None:
        captured["query"] = query
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "show_project", fake_show_project)
    monkeypatch.setattr(
        sys,
        "argv",
        ["stricknani-cli", "project", "show", "--query", "Sample"],
    )
    cli.main()

    assert captured["query"] == "Sample"
    assert captured["owner_email"] is None


def test_cli_yarn_lookup_dispatches_to_show(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_show_yarn(query: str, owner_email: str | None) -> None:
        captured["query"] = query
        captured["owner_email"] = owner_email

    monkeypatch.setattr(cli, "show_yarn", fake_show_yarn)
    monkeypatch.setattr(sys, "argv", ["stricknani-cli", "yarn", "Wool"])
    cli.main()

    assert captured["query"] == "Wool"
    assert captured["owner_email"] is None


def test_cli_project_export_accepts_positional_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_resolve_project_export_target(
        query: str, owner_email: str | None
    ) -> tuple[int, str]:
        captured["query"] = query
        captured["owner_email"] = owner_email
        return 123, "owner@example.com"

    async def fake_export_project_pdf(
        project_id: int,
        output_path: str,
        api_url: str,
        email: str,
        password: str,
    ) -> None:
        captured["project_id"] = project_id
        captured["output_path"] = output_path
        captured["api_url"] = api_url
        captured["email"] = email
        captured["password"] = password

    monkeypatch.setattr(
        cli, "resolve_project_export_target", fake_resolve_project_export_target
    )
    monkeypatch.setattr(cli, "export_project_pdf", fake_export_project_pdf)
    monkeypatch.setattr(cli, "prompt_password", lambda confirm=False: "secret")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "project",
            "export",
            "Sample",
            "--url",
            "http://localhost:7674",
        ],
    )
    cli.main()

    assert captured["query"] == "Sample"
    assert captured["owner_email"] is None
    assert captured["project_id"] == 123
    assert captured["output_path"] == "project_123.pdf"
    assert captured["api_url"] == "http://localhost:7674"
    assert captured["email"] == "owner@example.com"
    assert captured["password"] == "secret"


def test_cli_project_export_accepts_legacy_id_flag(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_resolve_project_export_target(
        query: str, owner_email: str | None
    ) -> tuple[int, str]:
        captured["query"] = query
        captured["owner_email"] = owner_email
        return 777, "owner@example.com"

    async def fake_export_project_pdf(
        project_id: int,
        output_path: str,
        api_url: str,
        email: str,
        password: str,
    ) -> None:
        captured["project_id"] = project_id
        captured["output_path"] = output_path
        captured["api_url"] = api_url
        captured["email"] = email
        captured["password"] = password

    monkeypatch.setattr(
        cli, "resolve_project_export_target", fake_resolve_project_export_target
    )
    monkeypatch.setattr(cli, "export_project_pdf", fake_export_project_pdf)
    monkeypatch.setattr(cli, "prompt_password", lambda confirm=False: "secret")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "stricknani-cli",
            "project",
            "export",
            "--id",
            "777",
            "--url",
            "http://localhost:7674",
            "--login-email",
            "login@example.com",
        ],
    )
    cli.main()

    assert captured["query"] == "777"
    assert captured["owner_email"] is None
    assert captured["project_id"] == 777
    assert captured["output_path"] == "project_777.pdf"
    assert captured["api_url"] == "http://localhost:7674"
    assert captured["email"] == "login@example.com"
    assert captured["password"] == "secret"


@pytest.mark.asyncio
async def test_show_project_json_output(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _client, session_factory, _user_id, project_id, _step_id = test_client

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    old_json_output = cli.JSON_OUTPUT
    cli.JSON_OUTPUT = True
    try:
        await cli.show_project(str(project_id), None)
    finally:
        cli.JSON_OUTPUT = old_json_output

    output = capsys.readouterr().out
    assert '"project"' in output
    assert '"id"' in output


@pytest.mark.asyncio
async def test_show_yarn_json_output(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        yarn = Yarn(name="Lookup Yarn", owner_id=user_id)
        session.add(yarn)
        await session.commit()
        await session.refresh(yarn)
        yarn_id = yarn.id

    async def fake_init_db() -> None:
        return None

    monkeypatch.setattr(cli, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(cli, "init_db", fake_init_db)

    old_json_output = cli.JSON_OUTPUT
    cli.JSON_OUTPUT = True
    try:
        await cli.show_yarn(str(yarn_id), None)
    finally:
        cli.JSON_OUTPUT = old_json_output

    output = capsys.readouterr().out
    assert '"yarn"' in output
    assert '"id"' in output
