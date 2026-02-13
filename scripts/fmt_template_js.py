#!/usr/bin/env python3
"""
Format Jinja2-templated *.js files using Biome.

Biome can't parse Jinja syntax directly, so we temporarily replace:
- {{ ... }} expressions with JS identifier placeholders
- {% ... %} statements and {# ... #} comments with JS block-comment placeholders

Then we run `biome format` on the transformed JS and restore the original Jinja
blocks using placeholder maps.

This is best-effort. If a template uses Jinja blocks *inside* JS comments or
strings in unusual ways, restoration could fail. The script will error if any
placeholder remains unreplaced.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

JINJA_COMMENT_RE = re.compile(r"{#([\s\S]*?)#}")
JINJA_STMT_RE = re.compile(r"{%([\s\S]*?)%}")
JINJA_EXPR_RE = re.compile(r"{{([\s\S]*?)}}")


@dataclass(frozen=True)
class Replacement:
    token: str
    original: str
    kind: str  # "expr" | "stmt"


def iter_template_js_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.js"))


def run_biome_format(path: Path, *, cwd: Path) -> tuple[int, str]:
    proc = subprocess.run(
        ["biome", "format", "--write", str(path)],
        cwd=str(cwd),
        capture_output=True,
        text=True,
    )
    msg = (proc.stderr or proc.stdout or "").strip()
    return proc.returncode, msg


def transform_jinja(source: str) -> tuple[str, list[Replacement]]:
    replacements: list[Replacement] = []
    counter = 0

    def next_token(prefix: str) -> str:
        nonlocal counter
        counter += 1
        return f"__STRICKNANI_JINJA_{prefix}_{counter:04d}__"

    def repl_comment(m: re.Match[str]) -> str:
        token = next_token("CMT")
        replacements.append(Replacement(token=token, original=m.group(0), kind="stmt"))
        # Use a block comment so Biome won't introduce semicolons around it.
        return f"/*{token}*/"

    def repl_stmt(m: re.Match[str]) -> str:
        token = next_token("STMT")
        replacements.append(Replacement(token=token, original=m.group(0), kind="stmt"))
        return f"/*{token}*/"

    def repl_expr(m: re.Match[str]) -> str:
        token = next_token("EXPR")
        replacements.append(Replacement(token=token, original=m.group(0), kind="expr"))
        # Identifier placeholder; safe in expression context and in strings/templates.
        return token

    out = JINJA_COMMENT_RE.sub(repl_comment, source)
    out = JINJA_STMT_RE.sub(repl_stmt, out)
    out = JINJA_EXPR_RE.sub(repl_expr, out)
    return out, replacements


def restore_jinja(source: str, replacements: list[Replacement]) -> str:
    out = source

    # Restore stmt/comment placeholders first. Biome may add spaces inside `/* */`.
    for r in replacements:
        if r.kind != "stmt":
            continue
        pattern = re.compile(r"/\*\s*" + re.escape(r.token) + r"\s*\*/")
        out, n = pattern.subn(r.original, out)
        if n == 0:
            raise RuntimeError(f"Failed to restore token {r.token}")

    # Restore expression placeholders (exact).
    for r in replacements:
        if r.kind != "expr":
            continue
        if r.token not in out:
            raise RuntimeError(f"Failed to restore token {r.token}")
        out = out.replace(r.token, r.original)

    # Sanity check: no placeholder tokens left behind.
    if "__STRICKNANI_JINJA_" in out:
        raise RuntimeError("Unrestored placeholder token(s) remain")
    return out


def format_one(path: Path, *, repo_root: Path, write: bool) -> bool:
    raw = path.read_text(encoding="utf-8")
    transformed, repls = transform_jinja(raw)

    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".js") as tmp:
        tmp_path = Path(tmp.name)
        tmp.write(transformed)

    try:
        rc, msg = run_biome_format(tmp_path, cwd=repo_root)
        if rc != 0:
            raise RuntimeError(f"Biome failed to format: {msg}")
        formatted_transformed = tmp_path.read_text(encoding="utf-8")
    finally:
        try:
            tmp_path.unlink()
        except OSError:
            pass

    restored = restore_jinja(formatted_transformed, repls)
    changed = restored != raw

    if changed and write:
        path.write_text(restored, encoding="utf-8")

    return changed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--templates-dir",
        default="stricknani/templates",
        help="Templates directory to scan (default: stricknani/templates)",
    )
    ap.add_argument(
        "--write",
        action="store_true",
        help="Write formatted output back to files",
    )
    ap.add_argument(
        "paths",
        nargs="*",
        help="Optional specific template *.js files (defaults to scanning all)",
    )
    ns = ap.parse_args()

    repo_root = Path.cwd()
    templates_dir = Path(ns.templates_dir)
    if ns.paths:
        files = [Path(p) for p in ns.paths]
    else:
        files = iter_template_js_files(templates_dir)

    changed_files: list[Path] = []
    for p in files:
        if not p.exists():
            print(f"Missing: {p}", file=sys.stderr)
            return 2
        if p.suffix != ".js":
            continue
        if templates_dir not in p.parents and not ns.paths:
            continue

        changed = format_one(p, repo_root=repo_root, write=ns.write)
        if changed:
            changed_files.append(p)

    if changed_files and not ns.write:
        print("Template JS files need formatting:")
        for p in changed_files:
            print(f"- {p}")
        print("\nRun: uv run python scripts/fmt_template_js.py --write")
        return 1

    if changed_files:
        print(f"Formatted {len(changed_files)} file(s).")
    else:
        print("No changes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
