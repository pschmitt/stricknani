"""CLI tool for Stricknani."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import sys

import httpx
from rich import print_json
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import Project, User, Yarn
from stricknani.utils.auth import get_password_hash, get_user_by_email


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
            print(f"Updated password for existing user {email}")
            return

        new_user = User(
            email=email,
            hashed_password=hashed,
            is_active=True,
            is_admin=bool(is_admin),
        )
        session.add(new_user)
        await session.commit()
        print(f"Created user {email}")


async def list_users() -> None:
    """List all users."""
    await init_db()
    async with AsyncSessionLocal() as session:
        result = await session.execute(select(User))
        users = result.scalars().all()
        for user in users:
            print(
                f"ID: {user.id}, Email: {user.email}, "
                f"Active: {user.is_active}, Admin: {user.is_admin}"
            )


async def list_projects(owner_email: str | None) -> None:
    """List projects."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                print(f"User {owner_email} not found.", file=sys.stderr)
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
        for project in projects:
            category = project.category or "-"
            owner_email_value = project.owner.email if project.owner else "-"
            print(
                f"ID: {project.id}, Name: {project.name}, "
                f"Category: {category}, Owner: {owner_email_value}"
            )


async def delete_project(project_id: int, owner_email: str | None) -> None:
    """Delete a project."""
    await init_db()
    async with AsyncSessionLocal() as session:
        project = await session.get(Project, project_id)
        if not project:
            print(f"Project {project_id} not found.", file=sys.stderr)
            return

        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                print(f"User {owner_email} not found.", file=sys.stderr)
                return
            if project.owner_id != owner.id:
                print(
                    f"Project {project_id} is not owned by {owner_email}.",
                    file=sys.stderr,
                )
                return

        await session.delete(project)
        await session.commit()
        print(f"Deleted project {project_id}")


async def list_yarns(owner_email: str | None) -> None:
    """List yarns."""
    await init_db()
    async with AsyncSessionLocal() as session:
        owner_id = None
        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                print(f"User {owner_email} not found.", file=sys.stderr)
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
        for yarn in yarns:
            brand = yarn.brand or "-"
            colorway = yarn.colorway or "-"
            owner_email_value = yarn.owner.email if yarn.owner else "-"
            print(
                f"ID: {yarn.id}, Name: {yarn.name}, "
                f"Brand: {brand}, Colorway: {colorway}, "
                f"Owner: {owner_email_value}"
            )


async def delete_yarn(yarn_id: int, owner_email: str | None) -> None:
    """Delete a yarn."""
    await init_db()
    async with AsyncSessionLocal() as session:
        yarn = await session.get(Yarn, yarn_id)
        if not yarn:
            print(f"Yarn {yarn_id} not found.", file=sys.stderr)
            return

        if owner_email:
            owner = await get_user_by_email(session, owner_email)
            if not owner:
                print(f"User {owner_email} not found.", file=sys.stderr)
                return
            if yarn.owner_id != owner.id:
                print(
                    f"Yarn {yarn_id} is not owned by {owner_email}.",
                    file=sys.stderr,
                )
                return

        await session.delete(yarn)
        await session.commit()
        print(f"Deleted yarn {yarn_id}")


async def delete_user(email: str) -> None:
    """Delete a user."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            print(f"User {email} not found.", file=sys.stderr)
            return
        await session.delete(user)
        await session.commit()
        print(f"Deleted user {email}")


async def set_user_admin(email: str, is_admin: bool) -> None:
    """Promote or demote a user."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            print(f"User {email} not found.", file=sys.stderr)
            return
        user.is_admin = is_admin
        await session.commit()
        state = "admin" if is_admin else "regular"
        print(f"Updated {email} to {state} user")


async def reset_password(email: str, password: str) -> None:
    """Reset a user's password."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        if not user:
            print(f"User {email} not found.", file=sys.stderr)
            return
        user.hashed_password = get_password_hash(password)
        user.is_active = True
        await session.commit()
        print(f"Reset password for {email}")


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
            print("Login failed.", file=sys.stderr)
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
                print(resp.text)
        else:
            print(f"Error: {resp.status_code}", file=sys.stderr)
            print(resp.text, file=sys.stderr)


def prompt_password(confirm: bool = True) -> str:
    """Prompt for a password."""
    first = getpass.getpass("Password: ")
    if not confirm:
        return first

    second = getpass.getpass("Confirm password: ")
    if first != second:
        print("Passwords do not match.", file=sys.stderr)
        sys.exit(1)
    if not first:
        print("Password cannot be empty.", file=sys.stderr)
        sys.exit(1)
    return first


def main() -> None:
    parser = argparse.ArgumentParser(description="Stricknani CLI tool.")
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
    project_list_parser.add_argument(
        "--owner-email", help="Filter by owner email"
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

    args = parser.parse_args()

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
        elif args.project_command == "delete":
            asyncio.run(delete_project(args.id, args.owner_email))
    elif args.command == "yarn":
        if args.yarn_command == "list":
            asyncio.run(list_yarns(args.owner_email))
        elif args.yarn_command == "delete":
            asyncio.run(delete_yarn(args.id, args.owner_email))


if __name__ == "__main__":
    main()
