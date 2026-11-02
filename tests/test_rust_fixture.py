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


def test_web_root_is_only_added_when_explicit_and_must_be_a_directory(
    tmp_path: Path,
) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    web_root = tmp_path / "web-build"
    web_root.mkdir()

    default_environment = rust_fixture._server_environment(runtime, 32123)
    web_environment = rust_fixture._server_environment(runtime, 32123, web_root)
    signup_environment = rust_fixture._server_environment(
        runtime, 32123, signup_enabled=True
    )

    assert "WEB_ROOT" not in default_environment
    assert "RUST_SIGNUP_ENABLED" not in default_environment
    assert "TRUSTED_HOSTS" not in default_environment
    assert web_environment["WEB_ROOT"] == str(web_root.resolve())
    assert signup_environment["RUST_SIGNUP_ENABLED"] == "true"
    assert "TRUSTED_HOSTS" not in web_environment
    assert (
        web_environment["RUST_DATABASE_URL"] == default_environment["RUST_DATABASE_URL"]
    )
    with pytest.raises(
        rust_fixture.FixtureError, match="web root must be an existing directory"
    ):
        rust_fixture._server_environment(runtime, 32123, tmp_path / "missing")


def test_browser_fixture_authority_adds_only_exact_synthetic_host(
    tmp_path: Path,
) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir()

    environment = rust_fixture._server_environment(
        runtime,
        32123,
        allowed_browser_authority="stricknani-device-012345abcdef.localhost:18762",
    )

    assert environment["BIND_PORT"] == "32123"
    assert environment["TRUSTED_HOSTS"] == (
        "localhost,127.0.0.1,[::1],stricknani-device-012345abcdef.localhost:18762"
    )


@pytest.mark.parametrize(
    "authority",
    [
        "stricknani-device-012345abcdef.localhost:0",
        "stricknani-device-012345abcdef.localhost:65536",
        "stricknani-device-012345abcdef.localhost:018762",
        "stricknani-device-012345abcde.localhost:18762",
        "stricknani-device-012345ABCDEf.localhost:18762",
        "stricknani-device-012345abcdef.localhost:18762,*.localhost",
        "user@stricknani-device-012345abcdef.localhost:18762",
        "stricknani-device-012345abcdef.localhost:18762/path",
        "other-012345abcdef.localhost:18762",
        "*.localhost:18762",
        " stricknani-device-012345abcdef.localhost:18762",
    ],
)
def test_browser_fixture_authority_rejects_noncanonical_values(
    tmp_path: Path, authority: str
) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir()

    with pytest.raises(
        rust_fixture.FixtureError,
        match="browser authority must be a synthetic device hostname",
    ):
        rust_fixture._server_environment(
            runtime, 32123, allowed_browser_authority=authority
        )


def test_start_fixture_rejects_browser_authority_before_runtime_or_user_creation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    binary = tmp_path / "stricknani-server"
    binary.write_bytes(b"server")
    binary.chmod(0o700)
    runtime = tmp_path / "runtime"
    create_user_called = False

    def unexpected_create_user(*_args: object, **_kwargs: object) -> None:
        nonlocal create_user_called
        create_user_called = True

    monkeypatch.setattr(rust_fixture, "run_cmd", unexpected_create_user)

    with pytest.raises(
        rust_fixture.FixtureError,
        match="browser authority must be a synthetic device hostname",
    ):
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=runtime,
            fixture_file=tmp_path / "fixture.json",
            state_file=tmp_path / "state.json",
            allowed_browser_authority="localhost:18762",
        )

    assert not runtime.exists()
    assert not (tmp_path / "fixture.json").exists()
    assert not (tmp_path / "state.json").exists()
    assert not create_user_called


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


@pytest.mark.parametrize("github_actions", [False, True])
def test_start_fixture_masks_generated_credentials_only_in_actions(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    github_actions: bool,
) -> None:
    binary = tmp_path / "stricknani-server"
    binary.write_bytes(b"fixture binary placeholder")
    binary.chmod(0o700)
    fixture_server = tmp_path / "optional-fixture"
    fixture_server.write_bytes(b"fixture companion placeholder")
    fixture_server.chmod(0o700)
    fixture_file = tmp_path / "private" / "fixture.json"
    state_file = tmp_path / "private" / "state.json"

    class FakeProcess:
        pid = 424242

        def poll(self) -> None:
            return None

    popen_args: list[object] = []
    popen_environment: dict[str, str] = {}

    def fake_popen(*args: object, **kwargs: object) -> FakeProcess:
        popen_args.extend(args)
        env = kwargs.get("env")
        assert isinstance(env, dict)
        popen_environment.update(env)
        return FakeProcess()

    create_user_args: list[list[str]] = []

    monkeypatch.setenv("GITHUB_ACTIONS", str(github_actions).lower())
    monkeypatch.setattr(subprocess, "Popen", fake_popen)
    monkeypatch.setattr(rust_fixture, "_unused_port", lambda: 56789)
    monkeypatch.setattr(rust_fixture, "_process_start_time", lambda _pid: "start")
    monkeypatch.setattr(rust_fixture, "_safe_terminate", lambda _pid, _start: None)
    monkeypatch.setattr(rust_fixture, "_wait_for_health", lambda _port, _process: None)
    monkeypatch.setattr(
        rust_fixture,
        "run_cmd",
        lambda args, **_kwargs: create_user_args.append(list(args)),
    )

    rust_fixture.start_fixture(
        binary=binary,
        temp_dir=tmp_path / "runtime",
        fixture_file=fixture_file,
        state_file=state_file,
        server_binary=fixture_server,
        ocr_mode="failure",
        signup_enabled=True,
    )

    runtime = Path(json.loads(state_file.read_text(encoding="utf-8"))["runtime"])
    assert popen_args == [
        [
            str(fixture_server.resolve()),
            "--document-worker",
            str(binary.resolve()),
            "--fixture-runtime",
            str(runtime),
            "--ocr-mode",
            "failure",
            "--archive-mode",
            "success",
        ]
    ]
    assert len(create_user_args) == 1
    assert create_user_args[0][:2] == [str(binary.resolve()), "create-user"]
    assert popen_environment["RUST_SIGNUP_ENABLED"] == "true"

    credentials = json.loads(fixture_file.read_text(encoding="utf-8"))
    output = capsys.readouterr().out
    if github_actions:
        assert f"::add-mask::{credentials['email']}\n" in output
        assert f"::add-mask::{credentials['password']}\n" in output
    else:
        assert credentials["email"] not in output
        assert credentials["password"] not in output
        assert output == ""


def test_start_fixture_rejects_non_executable_companion(tmp_path: Path) -> None:
    binary = tmp_path / "stricknani-server"
    binary.write_bytes(b"server")
    binary.chmod(0o700)
    companion = tmp_path / "not-executable"
    companion.write_bytes(b"companion")

    with pytest.raises(
        rust_fixture.FixtureError, match="fixture server binary is not executable"
    ):
        rust_fixture.start_fixture(
            binary=binary,
            server_binary=companion,
            temp_dir=tmp_path / "runtime",
            fixture_file=tmp_path / "fixture.json",
            state_file=tmp_path / "state.json",
        )


@pytest.mark.parametrize("option", ["ocr_mode", "archive_mode"])
def test_start_fixture_rejects_invalid_service_mode(
    tmp_path: Path, option: str
) -> None:
    binary = tmp_path / "stricknani-server"
    binary.write_bytes(b"server")
    binary.chmod(0o700)
    companion = tmp_path / "fixture-server"
    companion.write_bytes(b"fixture server")
    companion.chmod(0o700)
    arguments: dict[str, object] = {
        "binary": binary,
        "server_binary": companion,
        "temp_dir": tmp_path / "runtime",
        "fixture_file": tmp_path / "fixture.json",
        "state_file": tmp_path / "state.json",
        option: "arbitrary-argument",
    }

    with pytest.raises(rust_fixture.FixtureError, match="fixture mode must be"):
        rust_fixture.start_fixture(**arguments)  # type: ignore[arg-type]


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
