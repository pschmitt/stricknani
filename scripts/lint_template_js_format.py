#!/usr/bin/env python3
"""
Biome formatting enforcement for Jinja2-templated *.js files.

Biome can't parse Jinja syntax directly, so we replace Jinja blocks with
JS-safe placeholders (same strategy as `scripts/fmt_template_js.py`), run
`biome format` on the transformed JS, and fail if Biome would change it.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

JINJA_COMMENT_RE = re.compile(r"{#([\s\S]*?)#}")
JINJA_STMT_RE = re.compile(r"{%([\s\S]*?)%}")
JINJA_EXPR_RE = re.compile(r"{{([\s\S]*?)}}")


@dataclass(frozen=True)
class Replacement:
    token: str
    kind: str  # "expr" | "stmt"


def iter_template_js_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.js"))


def transform_jinja(source: str) -> str:
    counter = 0

    def next_token(prefix: str) -> str:
        nonlocal counter
        counter += 1
        return f"__STRICKNANI_JINJA_{prefix}_{counter:04d}__"

    def repl_comment(m: re.Match[str]) -> str:
        token = next_token("CMT")
        return f"/*{token}*/"

    def repl_stmt(m: re.Match[str]) -> str:
        token = next_token("STMT")
        return f"/*{token}*/"

    def repl_expr(m: re.Match[str]) -> str:
        token = next_token("EXPR")
        return token

    out = JINJA_COMMENT_RE.sub(repl_comment, source)
    out = JINJA_STMT_RE.sub(repl_stmt, out)
    out = JINJA_EXPR_RE.sub(repl_expr, out)
    return out


def biome_format_text(text: str, *, cwd: Path) -> tuple[int, str, str]:
    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".js") as tmp:
        tmp_path = Path(tmp.name)
        tmp.write(text)

    try:
        proc = subprocess.run(
            ["biome", "format", "--write", str(tmp_path)],
            cwd=str(cwd),
            capture_output=True,
            text=True,
        )
        formatted = tmp_path.read_text(encoding="utf-8")
        msg = (proc.stderr or proc.stdout or "").strip()
        return proc.returncode, formatted, msg
    finally:
        try:
            tmp_path.unlink()
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--templates-dir",
        default="stricknani/templates",
        help="Templates directory to scan (default: stricknani/templates)",
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

    failures: list[tuple[Path, str]] = []
    checked = 0

    for src_path in files:
        if not src_path.exists():
            failures.append((src_path, "file not found"))
            continue
        if src_path.suffix != ".js":
            continue
        if templates_dir not in src_path.parents and not ns.paths:
            continue

        raw = src_path.read_text(encoding="utf-8")
        cooked = transform_jinja(raw)
        rc, formatted, msg = biome_format_text(cooked, cwd=repo_root)
        checked += 1

        if rc != 0:
            failures.append((src_path, msg or "Biome failed to format file"))
            continue

        if formatted != cooked:
            failures.append(
                (
                    src_path,
                    "Biome would reformat this file. Run `just fmt-template-js`.",
                )
            )

    if failures:
        print(f"Template JS formatting check failed ({len(failures)}/{checked} files).")
        for p, msg in failures:
            print(f"\n- {p}\n{msg}")
        return 1

    print(f"Template JS formatting check passed ({checked} files).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
