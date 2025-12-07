"""CLI helpers for administrative tasks."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import sys

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


def prompt_password() -> str:
    """Prompt for a password twice and return it."""
    first = getpass.getpass("Password: ")
    second = getpass.getpass("Confirm password: ")
    if first != second:
        print("Passwords do not match.", file=sys.stderr)
        sys.exit(1)
    if not first:
        print("Password cannot be empty.", file=sys.stderr)
        sys.exit(1)
    return first


def main() -> None:
    parser = argparse.ArgumentParser(description="Manage Stricknani users.")
    parser.add_argument("--email", required=True, help="User email")
    parser.add_argument(
        "--password",
        help="Password for the user (omit to be prompted)",
    )
    args = parser.parse_args()

    password = args.password or prompt_password()
    asyncio.run(upsert_user(args.email, password))


if __name__ == "__main__":
    main()
