"""Tests for private disposable Rust replay fixtures."""

from __future__ import annotations

import json
import os
import stat
import subprocess
from pathlib import Path

import pytest

from ci.stricknani import rust_fixture


def test_private_json_is_outside_checkout_and_mode_0600(tmp_path: Path) -> None:
    fixture = tmp_path / "private" / "fixture.json"

    rust_fixture.write_private_json(fixture, {"value": "hidden"})

    assert json.loads(fixture.read_text(encoding="utf-8")) == {"value": "hidden"}
    assert stat.S_IMODE(fixture.stat().st_mode) == 0o600


def test_private_json_rejects_checkout_paths(tmp_path: Path) -> None:
    with pytest.raises(rust_fixture.FixtureError, match="outside the repository"):
        rust_fixture.write_private_json(
            rust_fixture.REPO_ROOT / "fixture.json", {"value": "hidden"}
        )


def test_fixture_environment_load_is_private_and_does_not_mutate_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fixture = tmp_path / "fixture.json"
    values = {
        "url": "http://127.0.0.1:12345",
        "email": "fixture@example.invalid",
        "password": "test-password-value",
    }
    rust_fixture.write_private_json(fixture, values)
    before = dict(os.environ)

    environment = rust_fixture.load_fixture_environment(fixture)

    assert environment == {
        "STRICKNANI_RUST_TEST_URL": values["url"],
        "STRICKNANI_RUST_TEST_EMAIL": values["email"],
        "STRICKNANI_RUST_TEST_PASSWORD": values["password"],
    }
    assert dict(os.environ) == before
    monkeypatch.setenv("STRICKNANI_RUST_TEST_PASSWORD", "ambient-value")
    assert (
        rust_fixture.load_fixture_environment(fixture)["STRICKNANI_RUST_TEST_PASSWORD"]
        == values["password"]
    )


def test_fixture_environment_rejects_group_or_world_readable_file(
    tmp_path: Path,
) -> None:
    fixture = tmp_path / "fixture.json"
    fixture.write_text("{}", encoding="utf-8")
    fixture.chmod(0o644)

    with pytest.raises(rust_fixture.FixtureError, match="private regular file"):
        rust_fixture.load_fixture_environment(fixture)


def test_run_cmd_sends_password_on_stdin_and_suppresses_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def fake_run(
        argv: list[str], **kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        captured["argv"] = argv
        captured.update(kwargs)
        return subprocess.CompletedProcess(argv, 0)

    monkeypatch.setattr(subprocess, "run", fake_run)
    rust_fixture.run_cmd(
        ["server", "create-user", "fixture@example.invalid"],
        env={"PATH": "/bin"},
        input_bytes=b"test-password-value\n",
    )

    assert captured["input"] == b"test-password-value\n"
    assert captured["stdout"] == subprocess.DEVNULL
    assert captured["stderr"] == subprocess.DEVNULL
    argv = captured["argv"]
    assert isinstance(argv, list)
    assert "test-password-value" not in argv


def test_stop_fixture_rejects_mismatched_owner_without_deleting_files(
    tmp_path: Path,
) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    marker = runtime / rust_fixture.OWNER_FILE
    marker.write_text("actual-owner", encoding="ascii")
    state_file = tmp_path / "state.json"
    state_file.write_text(
        json.dumps(
            {
                "pid": 99999999,
                "process_start": "1",
                "runtime": str(runtime),
                "fixture": str(tmp_path / "fixture.json"),
                "owner": "different-owner",
            }
        ),
        encoding="utf-8",
    )
    state_file.chmod(0o600)

    with pytest.raises(rust_fixture.FixtureError, match="owner marker"):
        rust_fixture.stop_fixture(state_file)

    assert marker.read_text(encoding="ascii") == "actual-owner"
    assert state_file.exists()
