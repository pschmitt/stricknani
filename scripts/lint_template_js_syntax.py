#!/usr/bin/env python3
"""
Best-effort JS syntax check for Jinja2-rendered *.js templates.

We can't directly parse Jinja templates as JS. Instead we replace Jinja blocks
with JS-safe placeholders while preserving line count, then run `node --check`.

This catches the most common class of bugs we hit recently:
- mismatched braces introduced while editing template JS
- broken template expressions that result in stray `}` in the output
"""

from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path

JINJA_COMMENT_RE = re.compile(r"{#([\s\S]*?)#}")
JINJA_STMT_RE = re.compile(r"{%([\s\S]*?)%}")
JINJA_EXPR_RE = re.compile(r"{{([\s\S]*?)}}")


def _replace_keep_lines(match: re.Match[str], placeholder: str) -> str:
    s = match.group(0)
    if "\n" not in s:
        return placeholder
    # Preserve the number of lines by emitting placeholder on the first line and
    # blank lines for the rest.
    lines = s.splitlines(True)
    out: list[str] = []
    first = True
    for chunk in lines:
        if chunk.endswith("\n"):
            nl = "\n"
        else:
            nl = ""
        if first:
            out.append(placeholder + nl)
            first = False
        else:
            out.append(nl)
    return "".join(out)


def preprocess_jinja_to_js(source: str) -> str:
    # Jinja comments can contain anything; strip them.
    source = JINJA_COMMENT_RE.sub(lambda m: _replace_keep_lines(m, ""), source)
    # Jinja statements don't yield JS; remove them.
    source = JINJA_STMT_RE.sub(lambda m: _replace_keep_lines(m, ""), source)
    # Jinja expressions must become a valid JS expression.
    source = JINJA_EXPR_RE.sub(lambda m: _replace_keep_lines(m, "0"), source)
    return source


def node_check(path: Path) -> tuple[int, str]:
    # Node will fail if it can't parse; stderr includes line/column.
    proc = subprocess.run(
        ["node", "--check", str(path)],
        capture_output=True,
        text=True,
    )
    msg = (proc.stderr or proc.stdout or "").strip()
    return proc.returncode, msg


def iter_template_js_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.js"))


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
        cooked = preprocess_jinja_to_js(raw)

        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".js") as tmp:
            tmp_path = Path(tmp.name)
            tmp.write(cooked)

        try:
            rc, msg = node_check(tmp_path)
        finally:
            try:
                tmp_path.unlink()
            except OSError:
                pass

        checked += 1
        if rc != 0:
            failures.append((src_path, msg))

    if failures:
        print(f"Template JS syntax check failed ({len(failures)}/{checked} files).")
        for p, msg in failures:
            print(f"\n- {p}\n{msg}")
        return 1

    print(f"Template JS syntax check passed ({checked} files).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
