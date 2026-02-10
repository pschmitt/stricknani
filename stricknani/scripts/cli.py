"""CLI tool for Stricknani."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from types import ModuleType
from typing import Literal, cast

import httpx
from rich import print_json
from rich.console import Console
from rich.logging import RichHandler
from rich.table import Table
from sqlalchemy import func, select
from sqlalchemy import inspect as sa_inspect
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import AuditLog, Project, Step, User, Yarn
from stricknani.services.audit import create_audit_log, serialize_audit_log
from stricknani.utils.ai_ingest import (
    DEFAULT_INSTRUCTIONS as AI_DEFAULT_INSTRUCTIONS,
)
from stricknani.utils.ai_ingest import (
    build_schema_for_target,
    ingest_with_openai,
)
from stricknani.utils.ai_provider import has_ai_api_key
from stricknani.utils.auth import get_password_hash, get_user_by_email
from stricknani.utils.importer import (
    GarnstudioPatternImporter,
    PatternImporter,
    is_garnstudio_url,
)
from stricknani.utils.project_import import (
    build_ai_hints,
    import_images_from_urls,
    normalize_tags,
    serialize_tags,
    sync_project_categories,
)

console = Console()
error_console = Console(stderr=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(message)s",
    datefmt="[%X]",
    handlers=[RichHandler(console=error_console, rich_tracebacks=True)],
)
logger = logging.getLogger("stricknani.cli")


JSON_OUTPUT = False
VERBOSE = False

PROJECT_SUBCOMMANDS = {"list", "add", "import", "delete", "export", "show"}
YARN_SUBCOMMANDS = {"list", "add", "import", "delete", "show"}


def suppress_alembic_logging() -> None:
    if VERBOSE:
        return
    for name in (
        "alembic",
        "alembic.runtime.migration",
        "alembic.runtime.environment",
    ):
        target = logging.getLogger(name)
        target.setLevel(logging.CRITICAL)
        target.propagate = False
        target.disabled = True
        target.handlers.clear()


def output_json(payload: dict[str, object]) -> None:
    print_json(data=payload)


def output_ok(message: str, payload: dict[str, object] | None = None) -> None:
    if JSON_OUTPUT:
        data: dict[str, object] = {"status": "ok"}
        if payload:
            data.update(payload)
        output_json(data)
        return
    console.print(message)


def output_table(
    headers: list[str],
    rows: list[list[str]],
    styles: list[str] | None = None,
) -> None:
    table = Table(show_header=True, box=None, show_edge=False, show_lines=False)
    for index, header in enumerate(headers):
        style = styles[index] if styles and index < len(styles) else None
        table.add_column(header, overflow="fold", style=style)
    for row in rows:
        table.add_row(*row)
    console.print(table)


def _normalize_entity_lookup_args(raw_args: list[str]) -> list[str]:
    """Rewrite `project|yarn ID_OR_NAME` invocations to `... show --query ...`."""
    if len(raw_args) < 2:
        return raw_args

    command = raw_args[0]
    token = raw_args[1]
    if token.startswith("-"):
        return raw_args

    if command == "project" and token not in PROJECT_SUBCOMMANDS:
        return [command, "show", "--query", token, *raw_args[2:]]

    if command == "yarn" and token not in YARN_SUBCOMMANDS:
        return [command, "show", "--query", token, *raw_args[2:]]

    return raw_args


def _serialize_value(value: object) -> object:
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def serialize_columns(obj: object) -> dict[str, object]:
    inspected = sa_inspect(obj, raiseerr=False)
    if inspected is None:
        return {}
    mapper = inspected.mapper
    return {
        column.key: _serialize_value(getattr(obj, column.key))
        for column in mapper.columns
    }


def serialize_project(project: Project) -> dict[str, object]:
    data = serialize_columns(project)
    data["tags_list"] = project.tag_list()
    data["owner"] = serialize_columns(project.owner) if project.owner else None
    data["images"] = [serialize_columns(img) for img in project.images]
    data["steps"] = [
        {
            **serialize_columns(step),
            "images": [serialize_columns(img) for img in step.images],
        }
        for step in project.steps
    ]
    data["yarns"] = [serialize_columns(yarn) for yarn in project.yarns]
    return data


def serialize_yarn(yarn: Yarn) -> dict[str, object]:
    data = serialize_columns(yarn)
    data["owner"] = serialize_columns(yarn.owner) if yarn.owner else None
    data["photos"] = [serialize_columns(photo) for photo in yarn.photos]
    data["projects"] = [serialize_columns(project) for project in yarn.projects]
    return data


async def upsert_user(email: str, password: str, is_admin: bool | None) -> None:
    """Create or update a user with the given credentials."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        hashed = get_password_hash(password)

        if user:
            user.hashed_password = hashed
            user.is_active = True
            if is_admin is not None:
                user.is_admin = is_admin
            await session.commit()
            output_ok(
                f"[green]Updated password[/green] for [cyan]{email}[/cyan]",
                {"email": email},
            )
            return

        new_user = User(
            email=email,
            hashed_password=hashed,
            is_active=True,
            is_admin=bool(is_admin),
        )
        session.add(new_user)
        await session.commit()
        output_ok(f"[green]Created user[/green] [cyan]{email}[/cyan]", {"email": email})


async def list_users() -> None:
    """List all users."""
    await init_db()
    async with AsyncSessionLocal() as session:
        result = await session.execute(select(User))
        users = result.scalars().all()
        if JSON_OUTPUT:
            output_json({"users": [serialize_columns(user) for user in users]})
            return

        rows = [
            [
                str(user.id),
                user.email,
                "true" if user.is_active else "false",
                "true" if user.is_admin else "false",
            ]
            for user in users
        ]
        output_table(
            ["ID", "EMAIL", "ACTIVE", "ADMIN"],
            rows,
            styles=["cyan", "cyan", "green", "magenta"],
        )


async def list_projects(owner_email: str | None) -> None:
    """List projects."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            owner_id = owner.id

        query = (
            select(Project)
            .options(
                selectinload(Project.owner),
                selectinload(Project.images),
                selectinload(Project.steps).selectinload(Step.images),
                selectinload(Project.yarns),
            )
            .order_by(Project.created_at.desc())
        )
        if owner_id is not None:
            query = query.where(Project.owner_id == owner_id)

        result = await session.execute(query)
        projects = result.scalars().all()
        if JSON_OUTPUT:
            output_json(
                {"projects": [serialize_project(project) for project in projects]}
            )
            return

        rows = [
            [
                str(project.id),
                project.name,
                project.category or "-",
                project.owner.email if project.owner else "-",
            ]
            for project in projects
        ]
        output_table(
            ["ID", "NAME", "CATEGORY", "OWNER"],
            rows,
            styles=["cyan", "yellow", "magenta", "cyan"],
        )


async def show_project(query: str, owner_email: str | None) -> None:
    """Show one project by ID or (partial) name."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            owner_id = owner.id

        base_query = select(Project).options(
            selectinload(Project.owner),
            selectinload(Project.images),
            selectinload(Project.steps).selectinload(Step.images),
            selectinload(Project.yarns),
        )
        if owner_id is not None:
            base_query = base_query.where(Project.owner_id == owner_id)

        project: Project | None = None
        query_clean = query.strip()
        if query_clean.isdigit():
            by_id = base_query.where(Project.id == int(query_clean))
            by_id_result = await session.execute(by_id)
            project = by_id_result.scalars().first()

        if project is None:
            by_exact_name = base_query.where(
                func.lower(Project.name) == query_clean.lower()
            )
            exact_result = await session.execute(by_exact_name)
            project = exact_result.scalars().first()

        if project is None:
            by_partial_name = base_query.where(
                Project.name.ilike(f"%{query_clean}%")
            ).order_by(Project.id.asc())
            partial_result = await session.execute(by_partial_name)
            matches = partial_result.scalars().all()
            if len(matches) == 1:
                project = matches[0]
            elif len(matches) > 1:
                if JSON_OUTPUT:
                    output_json(
                        {"matches": [serialize_project(item) for item in matches]}
                    )
                else:
                    rows = [
                        [
                            str(item.id),
                            item.name,
                            item.category or "-",
                            item.owner.email if item.owner else "-",
                        ]
                        for item in matches
                    ]
                    output_table(
                        ["ID", "NAME", "CATEGORY", "OWNER"],
                        rows,
                        styles=["cyan", "yellow", "magenta", "cyan"],
                    )
                    console.print(
                        f"[yellow]Multiple projects match[/yellow] "
                        f"[cyan]{query}[/cyan]. Use an ID."
                    )
                return

        if project is None:
            error_console.print(
                f"[red]No project matched query [cyan]{query}[/cyan].[/red]"
            )
            return

        data = serialize_project(project)
        if JSON_OUTPUT:
            output_json({"project": data})
            return

        rows = [
            ["ID", str(project.id)],
            ["Name", project.name],
            ["Owner", project.owner.email if project.owner else "-"],
            ["Category", project.category or "-"],
            ["Needles", project.needles or "-"],
            ["Yarn", project.yarn or "-"],
            ["Tags", ", ".join(project.tag_list()) or "-"],
            ["Images", str(len(project.images))],
            ["Steps", str(len(project.steps))],
            ["Linked Yarns", str(len(project.yarns))],
            ["Link", project.link or "-"],
        ]
        output_table(["FIELD", "VALUE"], rows, styles=["cyan", "white"])
        if project.description:
            console.print(f"[bold]Description:[/bold] {project.description}")
        if project.notes:
            console.print(f"[bold]Notes:[/bold] {project.notes}")


async def delete_project(project_id: int, owner_email: str | None) -> None:
    """Delete a project."""
    await init_db()
    async with AsyncSessionLocal() as session:
        project = await session.get(Project, project_id)
        if not project:
            error_console.print(
                f"[red]Project [cyan]{project_id}[/cyan] not found.[/red]"
            )
            return

        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            if project.owner_id != owner.id:
                error_console.print(
                    f"[red]Project [cyan]{project_id}[/cyan] is not owned by "
                    f"[cyan]{owner_email}[/cyan].[/red]"
                )
                return

        await create_audit_log(
            session,
            actor_user_id=project.owner_id,
            entity_type="project",
            entity_id=project.id,
            action="deleted",
            details={"name": project.name, "source": "cli"},
        )
        await session.delete(project)
        await session.commit()
        output_ok(
            f"[green]Deleted project[/green] [cyan]{project_id}[/cyan]",
            {"project_id": project_id},
        )


async def list_yarns(owner_email: str | None) -> None:
    """List yarns."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            owner_id = owner.id

        query = (
            select(Yarn)
            .options(
                selectinload(Yarn.owner),
                selectinload(Yarn.photos),
                selectinload(Yarn.projects),
            )
            .order_by(Yarn.created_at.desc())
        )
        if owner_id is not None:
            query = query.where(Yarn.owner_id == owner_id)

        result = await session.execute(query)
        yarns = result.scalars().all()
        if JSON_OUTPUT:
            output_json({"yarns": [serialize_yarn(yarn) for yarn in yarns]})
            return

        rows = [
            [
                str(yarn.id),
                yarn.name,
                yarn.brand or "-",
                yarn.colorway or "-",
                yarn.owner.email if yarn.owner else "-",
            ]
            for yarn in yarns
        ]
        output_table(
            ["ID", "NAME", "BRAND", "COLORWAY", "OWNER"],
            rows,
            styles=["cyan", "yellow", "magenta", "blue", "cyan"],
        )


async def show_yarn(query: str, owner_email: str | None) -> None:
    """Show one yarn by ID or (partial) name."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            owner_id = owner.id

        base_query = select(Yarn).options(
            selectinload(Yarn.owner),
            selectinload(Yarn.photos),
            selectinload(Yarn.projects),
        )
        if owner_id is not None:
            base_query = base_query.where(Yarn.owner_id == owner_id)

        yarn: Yarn | None = None
        query_clean = query.strip()
        if query_clean.isdigit():
            by_id = base_query.where(Yarn.id == int(query_clean))
            by_id_result = await session.execute(by_id)
            yarn = by_id_result.scalars().first()

        if yarn is None:
            by_exact_name = base_query.where(
                func.lower(Yarn.name) == query_clean.lower()
            )
            exact_result = await session.execute(by_exact_name)
            yarn = exact_result.scalars().first()

        if yarn is None:
            by_partial_name = base_query.where(
                Yarn.name.ilike(f"%{query_clean}%")
            ).order_by(Yarn.id.asc())
            partial_result = await session.execute(by_partial_name)
            matches = partial_result.scalars().all()
            if len(matches) == 1:
                yarn = matches[0]
            elif len(matches) > 1:
                if JSON_OUTPUT:
                    output_json({"matches": [serialize_yarn(item) for item in matches]})
                else:
                    rows = [
                        [
                            str(item.id),
                            item.name,
                            item.brand or "-",
                            item.colorway or "-",
                            item.owner.email if item.owner else "-",
                        ]
                        for item in matches
                    ]
                    output_table(
                        ["ID", "NAME", "BRAND", "COLORWAY", "OWNER"],
                        rows,
                        styles=["cyan", "yellow", "magenta", "blue", "cyan"],
                    )
                    console.print(
                        f"[yellow]Multiple yarns match[/yellow] "
                        f"[cyan]{query}[/cyan]. Use an ID."
                    )
                return

        if yarn is None:
            error_console.print(
                f"[red]No yarn matched query [cyan]{query}[/cyan].[/red]"
            )
            return

        data = serialize_yarn(yarn)
        if JSON_OUTPUT:
            output_json({"yarn": data})
            return

        rows = [
            ["ID", str(yarn.id)],
            ["Name", yarn.name],
            ["Owner", yarn.owner.email if yarn.owner else "-"],
            ["Brand", yarn.brand or "-"],
            ["Colorway", yarn.colorway or "-"],
            ["Dye Lot", yarn.dye_lot or "-"],
            ["Weight Category", yarn.weight_category or "-"],
            ["Recommended Needles", yarn.recommended_needles or "-"],
            [
                "Weight (g)",
                str(yarn.weight_grams) if yarn.weight_grams is not None else "-",
            ],
            [
                "Length (m)",
                str(yarn.length_meters) if yarn.length_meters is not None else "-",
            ],
            ["Photos", str(len(yarn.photos))],
            ["Linked Projects", str(len(yarn.projects))],
            ["Link", yarn.link or "-"],
        ]
        output_table(["FIELD", "VALUE"], rows, styles=["cyan", "white"])
        if yarn.description:
            console.print(f"[bold]Description:[/bold] {yarn.description}")
        if yarn.notes:
            console.print(f"[bold]Notes:[/bold] {yarn.notes}")


async def list_audit_entries(
    *,
    entity_type: str,
    entity_id: int,
    limit: int,
) -> None:
    """List audit entries for one project or yarn."""
    if entity_type not in {"project", "yarn"}:
        error_console.print(
            f"[red]Invalid entity type: [cyan]{entity_type}[/cyan].[/red]"
        )
        return

    await init_db()
    async with AsyncSessionLocal() as session:
        entity_exists = False
        if entity_type == "project":
            entity_exists = await session.get(Project, entity_id) is not None
        else:
            entity_exists = await session.get(Yarn, entity_id) is not None
        if not entity_exists:
            error_console.print(
                f"[red]{entity_type.title()} [cyan]{entity_id}[/cyan] not found.[/red]"
            )
            return

        result = await session.execute(
            select(AuditLog)
            .where(
                AuditLog.entity_type == entity_type,
                AuditLog.entity_id == entity_id,
            )
            .options(selectinload(AuditLog.actor))
            .order_by(AuditLog.created_at.desc(), AuditLog.id.desc())
            .limit(max(1, min(limit, 500)))
        )
        entries = list(result.scalars())
        payload = [serialize_audit_log(entry) for entry in entries]
        if JSON_OUTPUT:
            output_json({"audit_logs": payload})
            return

        rows = []
        for item in payload:
            details = item["details"]
            summary = json.dumps(details, ensure_ascii=True) if details else "-"
            rows.append(
                [
                    str(item["id"]),
                    str(item["created_at"]),
                    str(item["actor_email"] or item["actor_user_id"]),
                    str(item["action"]),
                    summary,
                ]
            )
        output_table(
            ["ID", "CREATED_AT", "ACTOR", "ACTION", "DETAILS"],
            rows,
            styles=["cyan", "green", "yellow", "magenta", "white"],
        )


async def delete_yarn(yarn_id: int, owner_email: str | None) -> None:
    """Delete a yarn."""
    await init_db()
    async with AsyncSessionLocal() as session:
        yarn = await session.get(Yarn, yarn_id)
        if not yarn:
            error_console.print(f"[red]Yarn [cyan]{yarn_id}[/cyan] not found.[/red]")
            return

        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                error_console.print(
                    f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
                )
                return
            if yarn.owner_id != owner.id:
                error_console.print(
                    f"[red]Yarn [cyan]{yarn_id}[/cyan] is not owned by "
                    f"[cyan]{owner_email}[/cyan].[/red]"
                )
                return

        await create_audit_log(
            session,
            actor_user_id=yarn.owner_id,
            entity_type="yarn",
            entity_id=yarn.id,
            action="deleted",
            details={"name": yarn.name, "source": "cli"},
        )
        await session.delete(yarn)
        await session.commit()
        output_ok(
            f"[green]Deleted yarn[/green] [cyan]{yarn_id}[/cyan]",
            {"yarn_id": yarn_id},
        )


async def add_project(
    owner_email: str,
    name: str,
    category: str | None,
    yarn: str | None,
    needles: str | None,
    notes: str | None,
    tags: str | None,
    link: str | None,
) -> None:
    """Create a project."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner = await get_user_by_email(session, owner_email)
        if not owner:
            error_console.print(
                f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
            )
            return

        serialized_tags = serialize_tags(normalize_tags(tags))
        project = Project(
            name=name,
            category=category,
            yarn=yarn,
            needles=needles,
            notes=notes,
            tags=serialized_tags,
            link=link,
            owner_id=owner.id,
        )
        session.add(project)
        await session.flush()
        await create_audit_log(
            session,
            actor_user_id=owner.id,
            entity_type="project",
            entity_id=project.id,
            action="created",
            details={"name": project.name, "source": "cli"},
        )
        await session.commit()
        await session.refresh(project)

        if category:
            await sync_project_categories(session, owner.id)

        output_ok(
            f"[green]Created project[/green] [cyan]{project.id}[/cyan]",
            {"project_id": project.id},
        )


async def add_yarn(
    owner_email: str,
    name: str,
    brand: str | None,
    colorway: str | None,
    dye_lot: str | None,
    fiber_content: str | None,
    weight_category: str | None,
    recommended_needles: str | None,
    weight_grams: int | None,
    length_meters: int | None,
    notes: str | None,
    link: str | None,
) -> None:
    """Create a yarn."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner = await get_user_by_email(session, owner_email)
        if not owner:
            error_console.print(
                f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
            )
            return

        yarn_entry = Yarn(
            name=name,
            brand=brand,
            colorway=colorway,
            dye_lot=dye_lot,
            fiber_content=fiber_content,
            weight_category=weight_category,
            recommended_needles=recommended_needles,
            weight_grams=weight_grams,
            length_meters=length_meters,
            notes=notes,
            link=link,
            owner_id=owner.id,
        )
        session.add(yarn_entry)
        await session.flush()
        await create_audit_log(
            session,
            actor_user_id=owner.id,
            entity_type="yarn",
            entity_id=yarn_entry.id,
            action="created",
            details={"name": yarn_entry.name, "source": "cli"},
        )
        await session.commit()
        await session.refresh(yarn_entry)
        output_ok(
            f"[green]Created yarn[/green] [cyan]{yarn_entry.id}[/cyan]",
            {"yarn_id": yarn_entry.id},
        )


async def import_project_url(
    url: str, use_ai: bool, import_images: bool, owner_email: str | None = None
) -> None:
    """Import a project from a URL."""
    basic_importer: PatternImporter
    if is_garnstudio_url(url):
        basic_importer = GarnstudioPatternImporter(url)
    else:
        basic_importer = PatternImporter(url)
    data = await basic_importer.fetch_and_parse()

    if use_ai and has_ai_api_key():
        try:
            ai_importer: ModuleType | None
            import stricknani.utils.ai_importer as ai_importer
        except ImportError:
            ai_importer = None

        if ai_importer and ai_importer.OPENAI_AVAILABLE:
            try:
                ai_importer_instance = ai_importer.AIPatternImporter(
                    url, hints=build_ai_hints(data)
                )
                data = await ai_importer_instance.fetch_and_parse()
            except Exception as exc:
                logger.warning("AI import failed, using basic parser: %s", exc)

    if not owner_email:
        # Debug mode: just print the data
        output_json(data)
        return

    await init_db()
    async with AsyncSessionLocal() as session:
        owner = await get_user_by_email(session, owner_email)
        if not owner:
            error_console.print(
                f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
            )
            return

        name = data.get("name") or data.get("title") or "Imported Project"
        project = Project(
            name=name,
            category=data.get("category"),
            yarn=data.get("yarn"),
            needles=data.get("needles"),
            notes=data.get("notes") or data.get("comment"),
            description=data.get("description"),
            link=data.get("link") or url,
            owner_id=owner.id,
        )
        session.add(project)
        await session.flush()
        await create_audit_log(
            session,
            actor_user_id=owner.id,
            entity_type="project",
            entity_id=project.id,
            action="created",
            details={"name": project.name, "source": "cli_import"},
        )

        steps = data.get("steps")
        if isinstance(steps, list):
            for index, step in enumerate(steps, start=1):
                if not isinstance(step, dict):
                    continue
                title = step.get("title") or f"Step {index}"
                description = step.get("description")
                step_number = step.get("step_number") or index
                session.add(
                    Step(
                        title=title,
                        description=description,
                        step_number=step_number,
                        project_id=project.id,
                    )
                )

        if import_images:
            config.ensure_media_dirs()
            image_urls = data.get("image_urls")
            if isinstance(image_urls, list):
                await import_images_from_urls(session, project, image_urls)

        await session.commit()
        await session.refresh(project)

        if project.category:
            await sync_project_categories(session, owner.id)

        output_ok(
            f"[green]Imported project[/green] [cyan]{project.id}[/cyan]",
            {"project_id": project.id},
        )


async def import_yarn_url(
    url: str, use_ai: bool, owner_email: str | None = None
) -> None:
    """Import a yarn from a URL."""
    basic_importer = PatternImporter(url)
    data = await basic_importer.fetch_and_parse()

    if use_ai and has_ai_api_key():
        try:
            ai_importer: ModuleType | None
            import stricknani.utils.ai_importer as ai_importer
        except ImportError:
            ai_importer = None

        if ai_importer and ai_importer.OPENAI_AVAILABLE:
            try:
                # We reuse the same AI logic for yarns for now
                ai_importer_instance = ai_importer.AIPatternImporter(
                    url, hints=build_ai_hints(data)
                )
                data = await ai_importer_instance.fetch_and_parse()
            except Exception as exc:
                logger.warning("AI import failed, using basic parser: %s", exc)

    if not owner_email:
        # Debug mode: just print the data
        output_json(data)
        return

    await init_db()
    async with AsyncSessionLocal() as session:
        owner = await get_user_by_email(session, owner_email)
        if not owner:
            error_console.print(
                f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
            )
            return

        name = data.get("name") or data.get("title") or "Imported Yarn"
        recommended_needles = data.get("recommended_needles")
        if isinstance(recommended_needles, str):
            recommended_needles = recommended_needles.strip() or None

        yarn_entry = Yarn(
            name=name,
            brand=data.get("brand"),
            colorway=data.get("colorway"),
            fiber_content=data.get("fiber_content"),
            weight_grams=data.get("weight_grams"),
            length_meters=data.get("length_meters"),
            weight_category=data.get("weight_category"),
            recommended_needles=recommended_needles,
            notes=data.get("description") or data.get("comment"),
            link=data.get("link") or url,
            owner_id=owner.id,
        )
        session.add(yarn_entry)
        await session.flush()
        await create_audit_log(
            session,
            actor_user_id=owner.id,
            entity_type="yarn",
            entity_id=yarn_entry.id,
            action="created",
            details={"name": yarn_entry.name, "source": "cli_import"},
        )
        await session.commit()
        await session.refresh(yarn_entry)

        output_ok(
            f"[green]Imported yarn[/green] [cyan]{yarn_entry.id}[/cyan]",
            {"yarn_id": yarn_entry.id},
        )


async def export_project_pdf(
    project_id: int,
    output_path: str,
    api_url: str,
    email: str,
    password: str,
) -> None:
    """Export a project to PDF."""
    try:
        from weasyprint import HTML
    except ImportError:
        error_console.print(
            "[red]WeasyPrint not installed. "
            "Install with 'pip install stricknani[pdf]'[/red]"
        )
        sys.exit(1)

    async with httpx.AsyncClient() as client:
        # Login
        login_resp = await client.post(
            f"{api_url}/auth/login",
            data={"email": email, "password": password},
            follow_redirects=False,
        )

        if (
            login_resp.status_code not in (302, 303)
            and "session_token" not in login_resp.cookies
        ):
            error_console.print("[red]Login failed.[/red]")
            sys.exit(1)

        cookies = login_resp.cookies

        # Fetch Project Page
        console.print(f"Fetching project {project_id} from {api_url}...")
        resp = await client.get(f"{api_url}/projects/{project_id}", cookies=cookies)

        if resp.status_code != 200:
            error_console.print(
                f"[red]Failed to fetch project: {resp.status_code}[/red]"
            )
            error_console.print(resp.text[:500])  # Show some context
            sys.exit(1)

        html_content = resp.text

        # Generate PDF
        console.print("Generating PDF...")
        try:
            HTML(string=html_content, base_url=api_url).write_pdf(output_path)
            output_ok(
                f"[green]Exported project[/green] to [cyan]{output_path}[/cyan]",
                {"path": output_path},
            )
        except Exception as e:
            error_console.print(f"[red]PDF generation failed: {e}[/red]")
            sys.exit(1)


async def delete_user(email: str) -> None:
    """Delete a user."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            error_console.print(f"[red]User [cyan]{email}[/cyan] not found.[/red]")
            return
        await session.delete(user)
        await session.commit()
        output_ok(
            f"[green]Deleted user[/green] [cyan]{email}[/cyan]",
            {"email": email},
        )


async def set_user_admin(email: str, is_admin: bool) -> None:
    """Promote or demote a user."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            error_console.print(f"[red]User [cyan]{email}[/cyan] not found.[/red]")
            return
        user.is_admin = is_admin
        await session.commit()
        state = "admin" if is_admin else "regular"
        output_ok(
            f"[green]Updated[/green] [cyan]{email}[/cyan] to "
            f"[yellow]{state}[/yellow] user",
            {"email": email, "state": state},
        )


async def reset_password(email: str, password: str) -> None:
    """Reset a user's password."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            error_console.print(f"[red]User [cyan]{email}[/cyan] not found.[/red]")
            return
        user.hashed_password = get_password_hash(password)
        user.is_active = True
        await session.commit()
        output_ok(
            f"[green]Reset password[/green] for [cyan]{email}[/cyan]",
            {"email": email},
        )


async def api_request(url: str, endpoint: str, email: str, password: str) -> None:
    """Make an authenticated API request."""
    async with httpx.AsyncClient() as client:
        # Login
        login_resp = await client.post(
            f"{url}/auth/login",
            data={"email": email, "password": password},
            follow_redirects=False,
        )

        if (
            login_resp.status_code not in (302, 303)
            and "session_token" not in login_resp.cookies
        ):
            error_console.print("[red]Login failed.[/red]")
            sys.exit(1)

        cookies = login_resp.cookies

        # Request
        resp = await client.get(
            f"{url}{endpoint}",
            cookies=cookies,
            headers={"Accept": "application/json"},
        )

        if resp.status_code == 200:
            try:
                print_json(data=resp.json())
            except json.JSONDecodeError:
                console.print(resp.text)
        else:
            error_console.print(f"[red]Error: {resp.status_code}[/red]")
            error_console.print(resp.text)


def prompt_password(confirm: bool = True) -> str:
    """Prompt for a password."""
    first = getpass.getpass("Password: ")
    if not confirm:
        return first

    second = getpass.getpass("Confirm password: ")
    if first != second:
        error_console.print("[red]Passwords do not match.[/red]")
        sys.exit(1)
    if not first:
        error_console.print("[red]Password cannot be empty.[/red]")
        sys.exit(1)
    return first


async def ai_print_schema(target: str) -> None:
    if target not in ("project", "yarn"):
        raise ValueError("target must be 'project' or 'yarn'")
    schema = build_schema_for_target(cast(Literal["project", "yarn"], target))
    output_json(schema)


async def ai_ingest(
    *,
    target: str,
    url: str | None,
    text: str | None,
    text_file: str | None,
    file_paths: list[str] | None,
    schema_file: str | None,
    instructions_file: str | None,
    instructions: str | None,
    model: str | None,
    temperature: float | None,
    max_output_tokens: int,
) -> None:
    if target not in ("project", "yarn"):
        raise ValueError("target must be 'project' or 'yarn'")

    if schema_file:
        schema = json.loads(Path(schema_file).read_text(encoding="utf-8"))
    else:
        schema = build_schema_for_target(cast(Literal["project", "yarn"], target))

    instructions_parts: list[str] = [AI_DEFAULT_INSTRUCTIONS]
    if instructions_file:
        instructions_parts.append(Path(instructions_file).read_text(encoding="utf-8"))
    if instructions:
        instructions_parts.append(instructions)
    combined_instructions = "\n".join([p.strip() for p in instructions_parts if p])

    resolved_text = text
    if text_file:
        resolved_text = Path(text_file).read_text(encoding="utf-8")

    data = await ingest_with_openai(
        target=cast(Literal["project", "yarn"], target),
        schema=schema,
        source_url=url,
        source_text=resolved_text,
        file_paths=[Path(p) for p in file_paths] if file_paths else None,
        instructions=combined_instructions,
        model=model,
        temperature=temperature,
        max_output_tokens=max_output_tokens,
    )
    output_json(data)


def main() -> None:
    raw_args = [arg for arg in sys.argv[1:] if arg not in ("--json", "--verbose")]
    raw_args = _normalize_entity_lookup_args(raw_args)
    global JSON_OUTPUT, VERBOSE
    JSON_OUTPUT = "--json" in sys.argv[1:]
    VERBOSE = "--verbose" in sys.argv[1:]
    suppress_alembic_logging()

    parser = argparse.ArgumentParser(description="Stricknani CLI tool.")
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output JSON (useful for scripts)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Show verbose logs (including Alembic output)",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # User management
    user_parser = subparsers.add_parser("user", help="Manage users")
    user_subparsers = user_parser.add_subparsers(dest="user_command", required=True)

    # user create
    create_parser = user_subparsers.add_parser("create", help="Create or update a user")
    create_parser.add_argument("--email", required=True, help="User email")
    create_parser.add_argument("--password", help="Password (omit to prompt)")
    admin_group = create_parser.add_mutually_exclusive_group()
    admin_group.add_argument(
        "--admin", action="store_true", help="Grant admin privileges"
    )
    admin_group.add_argument(
        "--no-admin", action="store_true", help="Revoke admin privileges"
    )

    # user list
    user_subparsers.add_parser("list", help="List all users")

    # user promote/demote
    promote_parser = user_subparsers.add_parser("promote", help="Promote a user")
    promote_parser.add_argument("--email", required=True, help="User email")

    demote_parser = user_subparsers.add_parser("demote", help="Demote a user")
    demote_parser.add_argument("--email", required=True, help="User email")

    # user delete
    delete_parser = user_subparsers.add_parser("delete", help="Delete a user")
    delete_parser.add_argument("--email", required=True, help="User email")

    # user reset-password
    reset_parser = user_subparsers.add_parser(
        "reset-password", help="Reset a user's password"
    )
    reset_parser.add_argument("--email", required=True, help="User email")
    reset_parser.add_argument("--password", help="Password (omit to prompt)")

    # Project management
    project_parser = subparsers.add_parser("project", help="Manage projects")
    project_subparsers = project_parser.add_subparsers(
        dest="project_command", required=False
    )
    project_list_parser = project_subparsers.add_parser("list", help="List projects")
    project_list_parser.add_argument("--owner-email", help="Filter by owner email")
    project_show_parser = project_subparsers.add_parser(
        "show", help="Show one project by ID or name"
    )
    project_show_parser.add_argument(
        "--query", required=True, help="ID or partial name"
    )
    project_show_parser.add_argument("--owner-email", help="Filter by owner email")
    project_add_parser = project_subparsers.add_parser("add", help="Add a project")
    project_add_parser.add_argument("--owner-email", required=True, help="Owner email")
    project_add_parser.add_argument("--name", required=True, help="Project name")
    project_add_parser.add_argument("--category", help="Project category")
    project_add_parser.add_argument("--yarn", help="Yarn description")
    project_add_parser.add_argument("--needles", help="Needle size")
    project_add_parser.add_argument("--notes", help="Project notes")
    project_add_parser.add_argument("--tags", help="Comma-separated tags")
    project_add_parser.add_argument("--link", help="Project link")
    project_import_parser = project_subparsers.add_parser(
        "import", help="Import a project from a URL"
    )
    project_import_parser.add_argument("--owner-email", help="Owner email")
    project_import_parser.add_argument("--url", required=True, help="URL to import")
    project_import_parser.add_argument(
        "--no-ai",
        action="store_true",
        help="Disable AI import even if configured",
    )
    project_import_parser.add_argument(
        "--import-images",
        action="store_true",
        help="Download and attach images from the URL",
    )
    project_delete_parser = project_subparsers.add_parser(
        "delete", help="Delete a project"
    )
    project_delete_parser.add_argument(
        "--id", type=int, required=True, help="Project ID"
    )
    project_delete_parser.add_argument(
        "--owner-email", help="Ensure the project belongs to this user"
    )
    project_export_parser = project_subparsers.add_parser(
        "export", help="Export a project to PDF"
    )
    project_export_parser.add_argument(
        "--id", type=int, required=True, help="Project ID"
    )
    project_export_parser.add_argument(
        "-o", "--output", help="Output PDF path (default: project_ID.pdf)"
    )
    project_export_parser.add_argument(
        "--url", default="http://localhost:7674", help="API URL"
    )
    project_export_parser.add_argument("--email", required=True, help="User email")
    project_export_parser.add_argument(
        "--password", help="User password (omit to prompt)"
    )

    # Yarn management
    yarn_parser = subparsers.add_parser("yarn", help="Manage yarns")
    yarn_subparsers = yarn_parser.add_subparsers(dest="yarn_command", required=False)
    yarn_list_parser = yarn_subparsers.add_parser("list", help="List yarns")
    yarn_list_parser.add_argument("--owner-email", help="Filter by owner email")
    yarn_show_parser = yarn_subparsers.add_parser(
        "show", help="Show one yarn by ID or name"
    )
    yarn_show_parser.add_argument("--query", required=True, help="ID or partial name")
    yarn_show_parser.add_argument("--owner-email", help="Filter by owner email")
    yarn_add_parser = yarn_subparsers.add_parser("add", help="Add a yarn")
    yarn_add_parser.add_argument("--owner-email", required=True, help="Owner email")
    yarn_add_parser.add_argument("--name", required=True, help="Yarn name")
    yarn_add_parser.add_argument("--brand", help="Brand")
    yarn_add_parser.add_argument("--colorway", help="Colorway")
    yarn_add_parser.add_argument("--dye-lot", help="Dye lot")
    yarn_add_parser.add_argument("--fiber-content", help="Fiber content")
    yarn_add_parser.add_argument("--weight-category", help="Weight category")
    yarn_add_parser.add_argument("--recommended-needles", help="Recommended needles")
    yarn_add_parser.add_argument("--weight-grams", type=int, help="Weight in grams")
    yarn_add_parser.add_argument("--length-meters", type=int, help="Length in meters")
    yarn_add_parser.add_argument("--notes", help="Notes")
    yarn_add_parser.add_argument("--link", help="Link")
    yarn_import_parser = yarn_subparsers.add_parser(
        "import", help="Import a yarn from a URL"
    )
    yarn_import_parser.add_argument("--owner-email", help="Owner email")
    yarn_import_parser.add_argument("--url", required=True, help="URL to import")
    yarn_import_parser.add_argument(
        "--no-ai",
        action="store_true",
        help="Disable AI import even if configured",
    )
    yarn_delete_parser = yarn_subparsers.add_parser("delete", help="Delete a yarn")
    yarn_delete_parser.add_argument("--id", type=int, required=True, help="Yarn ID")
    yarn_delete_parser.add_argument(
        "--owner-email", help="Ensure the yarn belongs to this user"
    )

    # Audit management
    audit_parser = subparsers.add_parser("audit", help="Inspect audit logs")
    audit_subparsers = audit_parser.add_subparsers(dest="audit_command", required=True)
    audit_list_parser = audit_subparsers.add_parser(
        "list", help="List audit entries for an entity"
    )
    audit_list_parser.add_argument(
        "--entity-type",
        choices=["project", "yarn"],
        required=True,
        help="Entity type",
    )
    audit_list_parser.add_argument("--entity-id", type=int, required=True, help="ID")
    audit_list_parser.add_argument(
        "--limit",
        type=int,
        default=100,
        help="Maximum number of entries (default: 100)",
    )

    # AI ingestion (CLI-first)
    ai_parser = subparsers.add_parser("ai", help="AI ingestion helpers (CLI-only)")
    ai_subparsers = ai_parser.add_subparsers(dest="ai_command", required=True)

    ai_schema_parser = ai_subparsers.add_parser(
        "schema",
        help="Print the JSON Schema used for AI ingestion (OpenAI strict json_schema)",
    )
    ai_schema_parser.add_argument(
        "--target",
        choices=["project", "yarn"],
        default="project",
        help="Schema target (default: project)",
    )

    ai_ingest_parser = ai_subparsers.add_parser(
        "ingest",
        help="Ingest from URL/text/file and return strict JSON",
    )
    ai_ingest_parser.add_argument(
        "--target",
        choices=["project", "yarn"],
        default="project",
        help="Ingestion target (default: project)",
    )
    ai_source = ai_ingest_parser.add_mutually_exclusive_group(required=True)
    ai_source.add_argument("--url", help="Source URL")
    ai_source.add_argument("--text", help="Source text")
    ai_source.add_argument("--text-file", help="Path to a text file to ingest")
    ai_source.add_argument(
        "--file",
        dest="file_paths",
        action="append",
        help="Path to a PDF/image. Repeat to attach multiple files.",
    )

    ai_ingest_parser.add_argument(
        "--schema-file",
        help="Path to a JSON Schema file. If omitted, schema is derived from models.",
    )
    ai_ingest_parser.add_argument(
        "--instructions-file",
        help="Additional instructions appended to the default prompt.",
    )
    ai_ingest_parser.add_argument(
        "--instructions",
        help="Additional instructions appended to the default prompt.",
    )
    ai_ingest_parser.add_argument(
        "--model",
        default=None,
        help="Model override (default: provider-specific)",
    )
    ai_ingest_parser.add_argument(
        "--temperature",
        type=float,
        default=None,
        help=(
            "Sampling temperature. Some models (e.g. GPT-5*) may not support it; "
            "omit to use the model default."
        ),
    )
    ai_ingest_parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=8000,
        help="Max output tokens (default: 8000)",
    )

    # API interaction
    api_parser = subparsers.add_parser("api", help="Interact with the API")
    api_parser.add_argument("--url", default="http://localhost:7674", help="API URL")
    api_parser.add_argument("--email", required=True, help="User email")
    api_parser.add_argument("--password", help="User password (omit to prompt)")

    api_subparsers = api_parser.add_subparsers(dest="api_command", required=True)

    api_subparsers.add_parser("projects", help="List projects")
    api_subparsers.add_parser("yarns", help="List yarns")

    # Alembic
    alembic_parser = subparsers.add_parser("alembic", help="Run alembic migrations")
    alembic_parser.add_argument(
        "alembic_args",
        nargs=argparse.REMAINDER,
        help="Arguments passed to alembic",
    )

    args = parser.parse_args(raw_args)

    if args.command == "user":
        if args.user_command == "create":
            password = args.password or prompt_password(confirm=True)
            is_admin: bool | None = None
            if args.admin:
                is_admin = True
            elif args.no_admin:
                is_admin = False
            asyncio.run(upsert_user(args.email, password, is_admin))
        elif args.user_command == "list":
            asyncio.run(list_users())
        elif args.user_command == "promote":
            asyncio.run(set_user_admin(args.email, True))
        elif args.user_command == "demote":
            asyncio.run(set_user_admin(args.email, False))
        elif args.user_command == "delete":
            asyncio.run(delete_user(args.email))
        elif args.user_command == "reset-password":
            password = args.password or prompt_password(confirm=True)
            asyncio.run(reset_password(args.email, password))

    elif args.command == "api":
        password = args.password or prompt_password(confirm=False)
        endpoint = ""
        if args.api_command == "projects":
            endpoint = "/projects/"
        elif args.api_command == "yarns":
            endpoint = "/yarn/"

        asyncio.run(api_request(args.url, endpoint, args.email, password))
    elif args.command == "ai":
        if args.ai_command == "schema":
            asyncio.run(ai_print_schema(args.target))
        elif args.ai_command == "ingest":
            asyncio.run(
                ai_ingest(
                    target=args.target,
                    url=args.url,
                    text=args.text,
                    text_file=args.text_file,
                    file_paths=args.file_paths,
                    schema_file=args.schema_file,
                    instructions_file=args.instructions_file,
                    instructions=args.instructions,
                    model=args.model,
                    temperature=args.temperature,
                    max_output_tokens=args.max_output_tokens,
                )
            )
    elif args.command == "project":
        project_command = args.project_command or "list"
        if project_command == "list":
            owner_email = getattr(args, "owner_email", None)
            asyncio.run(list_projects(owner_email))
        elif project_command == "show":
            asyncio.run(show_project(args.query, args.owner_email))
        elif project_command == "add":
            asyncio.run(
                add_project(
                    args.owner_email,
                    args.name,
                    args.category,
                    args.yarn,
                    args.needles,
                    args.notes,
                    args.tags,
                    args.link,
                )
            )
        elif project_command == "import":
            asyncio.run(
                import_project_url(
                    args.url,
                    not args.no_ai,
                    args.import_images,
                    args.owner_email,
                )
            )
        elif project_command == "delete":
            asyncio.run(delete_project(args.id, args.owner_email))
        elif project_command == "export":
            password = args.password or prompt_password(confirm=False)
            output = args.output or f"project_{args.id}.pdf"
            asyncio.run(
                export_project_pdf(
                    args.id,
                    output,
                    args.url,
                    args.email,
                    password,
                )
            )
    elif args.command == "yarn":
        yarn_command = args.yarn_command or "list"
        if yarn_command == "list":
            owner_email = getattr(args, "owner_email", None)
            asyncio.run(list_yarns(owner_email))
        elif yarn_command == "show":
            asyncio.run(show_yarn(args.query, args.owner_email))
        elif yarn_command == "add":
            asyncio.run(
                add_yarn(
                    args.owner_email,
                    args.name,
                    args.brand,
                    args.colorway,
                    args.dye_lot,
                    args.fiber_content,
                    args.weight_category,
                    args.recommended_needles,
                    args.weight_grams,
                    args.length_meters,
                    args.notes,
                    args.link,
                )
            )
        elif yarn_command == "import":
            asyncio.run(
                import_yarn_url(
                    args.url,
                    not args.no_ai,
                    args.owner_email,
                )
            )
        elif yarn_command == "delete":
            asyncio.run(delete_yarn(args.id, args.owner_email))
    elif args.command == "audit":
        if args.audit_command == "list":
            asyncio.run(
                list_audit_entries(
                    entity_type=args.entity_type,
                    entity_id=args.entity_id,
                    limit=args.limit,
                )
            )

    elif args.command == "alembic":
        from pathlib import Path

        from alembic.config import main as alembic_main

        ini_path = Path(__file__).parent.parent / "alembic.ini"
        alembic_args = ["-c", str(ini_path)] + args.alembic_args
        if args.alembic_args == ["downgrade"]:
            alembic_args.append("-1")
        elif args.alembic_args == ["upgrade"]:
            alembic_args.append("head")
        alembic_main(argv=alembic_args)


if __name__ == "__main__":
    main()
