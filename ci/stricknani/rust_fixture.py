#!/usr/bin/env python3
"""Manage a private, disposable Rust server fixture for Android replay CI."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
OWNER_FILE = ".rust-fixture-owner"


class FixtureError(RuntimeError):
    """A safe-to-display fixture error that does not contain credentials."""


def _outside_repository(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if resolved == REPO_ROOT or REPO_ROOT in resolved.parents:
        raise FixtureError(f"{label} must be outside the repository")
    return resolved


def _private_write(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def write_private_json(path: Path, value: Mapping[str, Any]) -> None:
    """Write JSON to a new private file outside the checkout."""
    target = _outside_repository(path, "fixture file")
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    _private_write(
        target,
        json.dumps(value, separators=(",", ":")).encode("utf-8"),
    )


def load_fixture_environment(path: Path) -> dict[str, str]:
    """Load fixture values from a mode-0600 JSON file without changing env."""
    target = _outside_repository(path, "fixture file")
    try:
        info = target.stat()
        if not target.is_file() or info.st_mode & 0o077:
            raise FixtureError("fixture file must be a private regular file")
        values = json.loads(target.read_text(encoding="utf-8"))
    except FixtureError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise FixtureError("could not read the private fixture file") from error
    if not isinstance(values, dict):
        raise FixtureError("fixture file has an invalid format")
    try:
        url, email, password = (values[key] for key in ("url", "email", "password"))
    except (KeyError, TypeError) as error:
        raise FixtureError("fixture file has an invalid format") from error
    if not all(isinstance(value, str) and value for value in (url, email, password)):
        raise FixtureError("fixture file has an invalid format")
    return {
        "STRICKNANI_RUST_TEST_URL": url,
        "STRICKNANI_RUST_TEST_EMAIL": email,
        "STRICKNANI_RUST_TEST_PASSWORD": password,
    }


def run_cmd(
    argv: Sequence[str],
    *,
    env: Mapping[str, str],
    input_bytes: bytes | None = None,
    timeout: int = 30,
) -> None:
    """Run a fixture command while suppressing its potentially sensitive output."""
    try:
        result = subprocess.run(
            list(argv),
            env=dict(env),
            input=input_bytes,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise FixtureError("fixture command could not be completed") from error
    if result.returncode:
        raise FixtureError("fixture command failed")


def _process_start_time(pid: int) -> str | None:
    try:
        contents = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        fields = contents[contents.rfind(")") + 2 :].split()
        return fields[19]
    except (OSError, IndexError):
        return None


def _safe_terminate(pid: int, expected_start: str) -> None:
    if _process_start_time(pid) != expected_start:
        return
    try:
        os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if _process_start_time(pid) != expected_start:
            return
        time.sleep(0.1)
    if _process_start_time(pid) == expected_start:
        try:
            os.killpg(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def _server_environment(runtime: Path, port: int) -> dict[str, str]:
    return {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": str(runtime),
        "TMPDIR": str(runtime),
        "RUST_DATABASE_URL": f"sqlite:{runtime / 'fixture.sqlite3'}",
        "RUST_MEDIA_ROOT": str(runtime / "media"),
        "BIND_HOST": "127.0.0.1",
        "BIND_PORT": str(port),
        "RUST_AI_ENABLED": "false",
        "RUST_OCR_ENABLED": "false",
        "RUST_EXTERNAL_IMPORTS_ENABLED": "false",
        "RUST_WAYBACK_ENABLED": "false",
    }


def _unused_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _wait_for_health(port: int, process: subprocess.Popen[bytes]) -> None:
    url = f"http://127.0.0.1:{port}/healthz"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise FixtureError("Rust fixture server exited before becoming healthy")
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(0.25)
    raise FixtureError("Rust fixture server did not become healthy")


def start_fixture(
    *,
    binary: Path,
    temp_dir: Path,
    fixture_file: Path,
    state_file: Path,
) -> None:
    """Start one local server and write private replay credentials for the runner."""
    binary_path = binary.expanduser().resolve()
    if not binary_path.is_file() or not os.access(binary_path, os.X_OK):
        raise FixtureError("Rust server binary is not executable")
    fixture_target = _outside_repository(fixture_file, "fixture file")
    state_target = _outside_repository(state_file, "state file")
    base = _outside_repository(temp_dir, "runtime directory")
    base.mkdir(mode=0o700, parents=True, exist_ok=True)
    runtime = base / f"stricknani-rust-{secrets.token_hex(12)}"
    runtime.mkdir(mode=0o700)
    owner = secrets.token_hex(24)
    _private_write(runtime / OWNER_FILE, owner.encode("ascii"))
    (runtime / "media").mkdir(mode=0o700)
    port = _unused_port()
    env = _server_environment(runtime, port)
    server_log = os.open(
        runtime / "server.log", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600
    )
    process: subprocess.Popen[bytes] | None = None
    fixture_created = False
    state_created = False
    try:
        old_umask = os.umask(0o077)
        try:
            with os.fdopen(server_log, "wb") as log:
                process = subprocess.Popen(
                    [str(binary_path)],
                    env=env,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
        finally:
            os.umask(old_umask)
        start_time = _process_start_time(process.pid)
        if start_time is None:
            raise FixtureError("could not track Rust fixture process")
        write_private_json(
            state_target,
            {
                "pid": process.pid,
                "process_start": start_time,
                "runtime": str(runtime),
                "fixture": str(fixture_target),
                "owner": owner,
            },
        )
        state_created = True
        _wait_for_health(port, process)

        email = f"rust-replay-{secrets.token_hex(8)}@example.invalid"
        password = secrets.token_urlsafe(32)
        print(f"::add-mask::{email}", flush=True)
        print(f"::add-mask::{password}", flush=True)
        run_cmd(
            [str(binary_path), "create-user", email],
            env=env,
            input_bytes=f"{password}\n".encode(),
        )
        write_private_json(
            fixture_target,
            {
                "url": f"http://127.0.0.1:{port}",
                "email": email,
                "password": password,
            },
        )
        fixture_created = True
    except BaseException:
        if process is not None:
            start_time = _process_start_time(process.pid)
            if start_time is not None:
                _safe_terminate(process.pid, start_time)
        if fixture_created:
            fixture_target.unlink(missing_ok=True)
        if state_created:
            state_target.unlink(missing_ok=True)
        shutil.rmtree(runtime)
        raise


def stop_fixture(state_file: Path) -> None:
    """Stop and remove only the fixture whose private owner marker matches state."""
    state_target = _outside_repository(state_file, "state file")
    try:
        state = json.loads(state_target.read_text(encoding="utf-8"))
        pid = int(state["pid"])
        process_start = str(state["process_start"])
        runtime = _outside_repository(Path(state["runtime"]), "runtime directory")
        fixture = _outside_repository(Path(state["fixture"]), "fixture file")
        owner = str(state["owner"])
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise FixtureError("fixture state is missing or invalid") from error
    if not runtime.is_dir():
        raise FixtureError("fixture runtime directory is missing")
    marker = runtime / OWNER_FILE
    try:
        if marker.read_text(encoding="ascii") != owner:
            raise FixtureError("fixture owner marker does not match")
    except OSError as error:
        raise FixtureError("fixture owner marker is missing") from error
    _safe_terminate(pid, process_start)
    fixture.unlink(missing_ok=True)
    shutil.rmtree(runtime)
    state_target.unlink(missing_ok=True)


def run_replay_from_file(
    *,
    fixture_file: Path,
    adb: str,
    serial: str,
    apk: Path,
    test_apk: Path,
    timeout: int,
) -> None:
    """Call the reviewed replay runner using credentials held only in a private file."""
    from ci.stricknani import run_rust_replay as runner

    fixture = runner.read_fixture(load_fixture_environment(fixture_file))
    runner.run_replay(
        adb=adb,
        serial=serial,
        apk=apk.resolve(),
        test_apk=test_apk.resolve(),
        fixture=fixture,
        timeout=timeout,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("start")
    start.add_argument("--binary", type=Path, required=True)
    start.add_argument("--temp-dir", type=Path, required=True)
    start.add_argument("--fixture-file", type=Path, required=True)
    start.add_argument("--state-file", type=Path, required=True)
    stop = commands.add_parser("stop")
    stop.add_argument("--state-file", type=Path, required=True)
    replay = commands.add_parser("run-replay")
    replay.add_argument("--fixture-file", type=Path, required=True)
    replay.add_argument("--adb", required=True)
    replay.add_argument("--serial", required=True)
    replay.add_argument("--apk", type=Path, required=True)
    replay.add_argument("--test-apk", type=Path, required=True)
    replay.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args(argv)
    try:
        if args.command == "start":
            start_fixture(
                binary=args.binary,
                temp_dir=args.temp_dir,
                fixture_file=args.fixture_file,
                state_file=args.state_file,
            )
            print("Rust replay fixture started.")
        elif args.command == "stop":
            stop_fixture(args.state_file)
            print("Rust replay fixture stopped.")
        else:
            if not 1 <= args.timeout <= 1800:
                raise FixtureError("replay timeout must be between 1 and 1800 seconds")
            run_replay_from_file(
                fixture_file=args.fixture_file,
                adb=args.adb,
                serial=args.serial,
                apk=args.apk,
                test_apk=args.test_apk,
                timeout=args.timeout,
            )
            print("Rust backend replay instrumentation passed.")
    except FixtureError as error:
        print(f"fixture error: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        from ci.stricknani.run_rust_replay import ReplayError

        if isinstance(error, ReplayError):
            print(f"fixture error: {str(error)[:4000]}", file=sys.stderr)
            return 1
        print("fixture error: unexpected replay failure", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
