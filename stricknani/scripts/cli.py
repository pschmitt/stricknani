"""CLI tool for Stricknani."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import logging
import sys
from types import ModuleType

import httpx
from rich import print_json
from rich.console import Console
from rich.logging import RichHandler
from rich.table import Table
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import Project, Step, User, Yarn
from stricknani.utils.project_import import (
    build_ai_hints,
    import_images_from_urls,
    normalize_tags,
    serialize_tags,
    sync_project_categories,
)
from stricknani.utils.auth import get_password_hash, get_user_by_email
from stricknani.utils.importer import PatternImporter


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
            output_json(
                {
                    "users": [
                        {
                            "id": user.id,
                            "email": user.email,
                            "is_active": user.is_active,
                            "is_admin": user.is_admin,
                        }
                        for user in users
                    ]
                }
            )
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
            .options(selectinload(Project.owner))
            .order_by(Project.created_at.desc())
        )
        if owner_id is not None:
            query = query.where(Project.owner_id == owner_id)

        result = await session.execute(query)
        projects = result.scalars().all()
        if JSON_OUTPUT:
            output_json(
                {
                    "projects": [
                        {
                            "id": project.id,
                            "name": project.name,
                            "category": project.category,
                            "owner_email": project.owner.email
                            if project.owner
                            else None,
                        }
                        for project in projects
                    ]
                }
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
            .options(selectinload(Yarn.owner))
            .order_by(Yarn.created_at.desc())
        )
        if owner_id is not None:
            query = query.where(Yarn.owner_id == owner_id)

        result = await session.execute(query)
        yarns = result.scalars().all()
        if JSON_OUTPUT:
            output_json(
                {
                    "yarns": [
                        {
                            "id": yarn.id,
                            "name": yarn.name,
                            "brand": yarn.brand,
                            "colorway": yarn.colorway,
                            "owner_email": yarn.owner.email if yarn.owner else None,
                        }
                        for yarn in yarns
                    ]
                }
            )
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


async def delete_yarn(yarn_id: int, owner_email: str | None) -> None:
    """Delete a yarn."""
    await init_db()
    async with AsyncSessionLocal() as session:
        yarn = await session.get(Yarn, yarn_id)
        if not yarn:
            error_console.print(
                f"[red]Yarn [cyan]{yarn_id}[/cyan] not found.[/red]"
            )
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
    recommended_needles: str | None,
    gauge_stitches: int | None,
    gauge_rows: int | None,
    comment: str | None,
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
            recommended_needles=recommended_needles,
            gauge_stitches=gauge_stitches,
            gauge_rows=gauge_rows,
            comment=comment,
            tags=serialized_tags,
            link=link,
            owner_id=owner.id,
        )
        session.add(project)
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
        await session.commit()
        await session.refresh(yarn_entry)
        output_ok(
            f"[green]Created yarn[/green] [cyan]{yarn_entry.id}[/cyan]",
            {"yarn_id": yarn_entry.id},
        )


async def import_project_url(
    owner_email: str, url: str, use_ai: bool, import_images: bool
) -> None:
    """Import a project from a URL."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner = await get_user_by_email(session, owner_email)
        if not owner:
            error_console.print(
                f"[red]User [cyan]{owner_email}[/cyan] not found.[/red]"
            )
            return

        basic_importer = PatternImporter(url)
        data = await basic_importer.fetch_and_parse()

        if use_ai and config.OPENAI_API_KEY:
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

        name = data.get("name") or data.get("title") or "Imported Project"
        project = Project(
            name=name,
            category=data.get("category"),
            yarn=data.get("yarn"),
            needles=data.get("needles"),
            recommended_needles=data.get("recommended_needles"),
            gauge_stitches=data.get("gauge_stitches"),
            gauge_rows=data.get("gauge_rows"),
            comment=data.get("comment"),
            link=data.get("link") or url,
            owner_id=owner.id,
        )
        session.add(project)
        await session.flush()

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


def main() -> None:
    raw_args = [
        arg
        for arg in sys.argv[1:]
        if arg not in ("--json", "--verbose")
    ]
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
        dest="project_command", required=True
    )
    project_list_parser = project_subparsers.add_parser("list", help="List projects")
    project_list_parser.add_argument("--owner-email", help="Filter by owner email")
    project_add_parser = project_subparsers.add_parser("add", help="Add a project")
    project_add_parser.add_argument("--owner-email", required=True, help="Owner email")
    project_add_parser.add_argument("--name", required=True, help="Project name")
    project_add_parser.add_argument("--category", help="Project category")
    project_add_parser.add_argument("--yarn", help="Yarn description")
    project_add_parser.add_argument("--needles", help="Needle size")
    project_add_parser.add_argument(
        "--recommended-needles", help="Recommended needles"
    )
    project_add_parser.add_argument(
        "--gauge-stitches", type=int, help="Gauge stitches per 10cm"
    )
    project_add_parser.add_argument(
        "--gauge-rows", type=int, help="Gauge rows per 10cm"
    )
    project_add_parser.add_argument("--comment", help="Project comment")
    project_add_parser.add_argument("--tags", help="Comma-separated tags")
    project_add_parser.add_argument("--link", help="Project link")
    project_import_parser = project_subparsers.add_parser(
        "import-url", help="Import a project from a URL"
    )
    project_import_parser.add_argument(
        "--owner-email", required=True, help="Owner email"
    )
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

    # Yarn management
    yarn_parser = subparsers.add_parser("yarn", help="Manage yarns")
    yarn_subparsers = yarn_parser.add_subparsers(dest="yarn_command", required=True)
    yarn_list_parser = yarn_subparsers.add_parser("list", help="List yarns")
    yarn_list_parser.add_argument("--owner-email", help="Filter by owner email")
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
    yarn_delete_parser = yarn_subparsers.add_parser("delete", help="Delete a yarn")
    yarn_delete_parser.add_argument(
        "--id", type=int, required=True, help="Yarn ID"
    )
    yarn_delete_parser.add_argument(
        "--owner-email", help="Ensure the yarn belongs to this user"
    )

    # API interaction
    api_parser = subparsers.add_parser("api", help="Interact with the API")
    api_parser.add_argument("--url", default="http://localhost:7674", help="API URL")
    api_parser.add_argument("--email", required=True, help="User email")
    api_parser.add_argument("--password", help="User password (omit to prompt)")

    api_subparsers = api_parser.add_subparsers(dest="api_command", required=True)

    api_subparsers.add_parser("projects", help="List projects")
    api_subparsers.add_parser("yarns", help="List yarns")

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
    elif args.command == "project":
        if args.project_command == "list":
            asyncio.run(list_projects(args.owner_email))
        elif args.project_command == "add":
            asyncio.run(
                add_project(
                    args.owner_email,
                    args.name,
                    args.category,
                    args.yarn,
                    args.needles,
                    args.recommended_needles,
                    args.gauge_stitches,
                    args.gauge_rows,
                    args.comment,
                    args.tags,
                    args.link,
                )
            )
        elif args.project_command == "import-url":
            asyncio.run(
                import_project_url(
                    args.owner_email,
                    args.url,
                    not args.no_ai,
                    args.import_images,
                )
            )
        elif args.project_command == "delete":
            asyncio.run(delete_project(args.id, args.owner_email))
    elif args.command == "yarn":
        if args.yarn_command == "list":
            asyncio.run(list_yarns(args.owner_email))
        elif args.yarn_command == "add":
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
        elif args.yarn_command == "delete":
            asyncio.run(delete_yarn(args.id, args.owner_email))


if __name__ == "__main__":
    main()
