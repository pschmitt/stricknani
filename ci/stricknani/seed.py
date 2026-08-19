#!/usr/bin/env python3
"""Validate and authenticate the disposable Stricknani Android E2E fixture."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def request(
    base_url: str,
    path: str,
    *,
    method: str = "GET",
    payload: dict[str, object] | None = None,
    token: str | None = None,
) -> dict[str, object]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/{path.lstrip('/')}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"{method} {request.full_url} returned {error.code}: {body}"
        ) from error


def seed(base_url: str, email: str, password: str) -> str:
    """Mint the CI-only PAT and prove that demo records and media are present."""
    token_response = request(
        base_url,
        "api/v1/auth/token",
        method="POST",
        payload={"email": email, "password": password, "token_name": "Android E2E"},
    )
    token = token_response.get("token")
    if not isinstance(token, str) or not token:
        raise RuntimeError("fixture did not return an API token")

    categories = request(base_url, "api/v1/categories", token=token)
    projects = request(base_url, "api/v1/projects?limit=100", token=token)
    yarns = request(base_url, "api/v1/yarns?limit=100", token=token)

    category_names = {
        item["name"] for item in categories if isinstance(item, dict)
    }
    project_items = [
        item for item in projects.get("items", []) if isinstance(item, dict)
    ]
    yarn_items = [item for item in yarns.get("items", []) if isinstance(item, dict)]
    project_names = {item["name"] for item in project_items}
    yarn_names = {item["name"] for item in yarn_items}
    expected_category = "Schal"
    expected_project = "Heirloom Baby Blanket"
    expected_yarn = "Riverbend Merino DK"
    missing = [
        name
        for name, values in (
            (expected_category, category_names),
            (expected_project, project_names),
            (expected_yarn, yarn_names),
        )
        if name not in values
    ]
    if missing:
        raise RuntimeError(f"fixture is missing seeded records: {', '.join(missing)}")

    expected_project_item = next(
        item for item in project_items if item.get("name") == expected_project
    )
    expected_yarn_item = next(
        item for item in yarn_items if item.get("name") == expected_yarn
    )
    if not expected_project_item.get("preview_url"):
        raise RuntimeError(f"fixture project has no preview image: {expected_project}")
    if not expected_yarn_item.get("preview_url"):
        raise RuntimeError(f"fixture yarn has no preview image: {expected_yarn}")

    return token


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--email", default="demo@stricknani.local")
    parser.add_argument("--password", default="demo-password")
    args = parser.parse_args()
    try:
        # stdout is deliberately only the token: the workflow stores it in GITHUB_ENV
        # without echoing it, so the disposable credential does not end up in CI logs.
        print(seed(args.base_url, args.email, args.password))
    except (OSError, RuntimeError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"Stricknani Android E2E seed failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
