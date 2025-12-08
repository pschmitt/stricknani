"""CLI tool for Stricknani."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import sys

import httpx
from sqlalchemy import select

from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import User
from stricknani.utils.auth import get_password_hash, get_user_by_email


async def upsert_user(email: str, password: str) -> None:
    """Create or update a user with the given credentials."""
    await init_db()
    async with AsyncSessionLocal() as session:
        user = await get_user_by_email(session, email)
        hashed = get_password_hash(password)

        if user:
            user.hashed_password = hashed
            user.is_active = True
            await session.commit()
            print(f"Updated password for existing user {email}")
            return

        new_user = User(email=email, hashed_password=hashed, is_active=True)
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
            print(f"ID: {user.id}, Email: {user.email}, Active: {user.is_active}")


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
                print(json.dumps(resp.json(), indent=2))
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

    # user list
    user_subparsers.add_parser("list", help="List all users")

    # user delete
    delete_parser = user_subparsers.add_parser("delete", help="Delete a user")
    delete_parser.add_argument("--email", required=True, help="User email")

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
            asyncio.run(upsert_user(args.email, password))
        elif args.user_command == "list":
            asyncio.run(list_users())
        elif args.user_command == "delete":
            asyncio.run(delete_user(args.email))

    elif args.command == "api":
        password = args.password or prompt_password(confirm=False)
        endpoint = ""
        if args.api_command == "projects":
            endpoint = "/projects/"
        elif args.api_command == "yarns":
            endpoint = "/yarn/"

        asyncio.run(api_request(args.url, endpoint, args.email, password))


if __name__ == "__main__":
    main()
