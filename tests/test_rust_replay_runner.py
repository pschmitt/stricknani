"""Safety and orchestration tests for the opt-in Android replay runner."""

from __future__ import annotations

import io
import stat
import subprocess
from pathlib import Path

import pytest

from ci.stricknani import run_rust_replay as runner


def fixture_values(url: str = "http://localhost:7674") -> dict[str, str]:
    return {
        "STRICKNANI_RUST_TEST_URL": url,
        "STRICKNANI_RUST_TEST_EMAIL": "replay@example.invalid",
        "STRICKNANI_RUST_TEST_PASSWORD": "disposable-test-password",
    }


def test_validate_url_only_allows_plain_loopback_urls() -> None:
    normalized, port = runner.validate_url("http://localhost:7674/api/root/")
    assert normalized == "http://127.0.0.1:7674/api/root"
    assert port == 7674
    assert runner.validate_url("https://[::1]:8443")[1] == 8443

    for url in (
        "https://example.invalid",
        "http://127.0.0.1:7674/#fragment",
        "http://user:pass@localhost:7674",
        "http://localhost:7674/?token=x",
    ):
        with pytest.raises(runner.ReplayError):
            runner.validate_url(url)


def test_invalid_serial_is_rejected_before_subprocess_or_credentials(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(runner.shutil, "which", lambda _command: "/usr/bin/adb")
    monkeypatch.setattr(runner.Path, "is_file", lambda _path: True)
    monkeypatch.setattr(
        runner, "_run", lambda *_args, **_kwargs: pytest.fail("adb called")
    )
    monkeypatch.setattr(runner, "read_fixture", lambda: pytest.fail("credentials read"))

    status = runner.main(
        [
            "--adb",
            "/usr/bin/adb",
            "--serial",
            "physical-123",
            "--apk",
            "app.apk",
            "--test-apk",
            "test.apk",
        ]
    )
    captured = capsys.readouterr()
    assert status == 1
    assert "emulator-*" in captured.err
    assert "disposable-test-password" not in captured.err


def test_qemu_property_is_checked_before_install(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        calls.append(command)
        output = b"emulator-5554 device\n" if "devices" in command else b"0\n"
        return subprocess.CompletedProcess(command, 0, output, b"")

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(runner.ReplayError, match="emulator mode"):
        runner.validate_emulator("/usr/bin/adb", "emulator-5554", timeout=10)
    assert not any("install" in command for command in calls)


def test_existing_reverse_mapping_is_reused_without_removal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        calls.append(command)
        return subprocess.CompletedProcess(
            command,
            0,
            b"host-19 tcp:7674 tcp:7674\n",
            b"",
        )

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    assert runner.ensure_reverse("adb", "emulator-5554", 7674, timeout=10) is False
    assert len(calls) == 1
    assert calls[0][-2:] == ["reverse", "--list"]


def test_conflicting_reverse_mapping_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        calls.append(command)
        return subprocess.CompletedProcess(
            command,
            0,
            b"emulator-5554 tcp:7674 tcp:8080\n",
            b"",
        )

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(runner.ReplayError, match="conflicting reverse"):
        runner.ensure_reverse("adb", "emulator-5554", 7674, timeout=10)
    assert len(calls) == 1


def test_success_streams_fixture_to_private_file_and_runs_only_target_class(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    apk = tmp_path / "app-x86_64-debug.apk"
    test_apk = tmp_path / "app-debug-androidTest.apk"
    apk.touch()
    test_apk.touch()
    calls: list[tuple[list[str], dict[str, object]]] = []
    captured_commands: list[list[str]] = []

    def fake_run(
        command: list[str], **kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        calls.append((command, kwargs))
        if command == ["/usr/bin/adb", "devices", "-l"]:
            return subprocess.CompletedProcess(
                command, 0, b"List of devices attached\nemulator-5554 device\n", b""
            )
        if "getprop" in command:
            return subprocess.CompletedProcess(command, 0, b"1\n", b"")
        return subprocess.CompletedProcess(command, 0, b"", b"")

    monkeypatch.setattr(runner.subprocess, "run", fake_run)

    def capture(command: list[str], log_path: Path, *, timeout: int) -> int:
        del timeout
        captured_commands.append(command)
        log_path.write_text(
            f"INSTRUMENTATION_STATUS: class={runner.TEST_CLASS}\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_CODE: -1\nOK (1 test)\n",
            encoding="utf-8",
        )
        return 0

    monkeypatch.setattr(runner, "_capture_instrumentation", capture)
    fixture = runner.read_fixture(fixture_values())
    runner.run_replay(
        adb="/usr/bin/adb",
        serial="emulator-5554",
        apk=apk,
        test_apk=test_apk,
        fixture=fixture,
        timeout=10,
    )

    provision_call = next(
        (command, kwargs)
        for command, kwargs in calls
        if command[-3:] == ["toybox", "tee", "files/.rust-replay-fixture.json.tmp"]
    )
    provision_command, provision_kwargs = provision_call
    assert provision_kwargs["input"] == fixture.json_bytes()
    assert provision_kwargs["stdout"] == subprocess.DEVNULL
    assert fixture.password.encode() not in " ".join(provision_command).encode()
    instrumentation = captured_commands[0]
    assert instrumentation[-4:] == [
        "-e",
        "class",
        runner.TEST_CLASS,
        runner.TEST_COMPONENT,
    ]
    assert sum("install" in command for command, _kwargs in calls) == 2
    assert any("-t" in command for command, _kwargs in calls)
    assert any("--remove" in command for command, _kwargs in calls)
    assert any(
        "rm -f files/rust-replay-fixture.json" in " ".join(command)
        for command, _kwargs in calls
    )
    assert not any("sh" in command or "-c" in command for command, _kwargs in calls)


@pytest.mark.parametrize(
    "output",
    [
        "INSTRUMENTATION_STATUS_CODE: -3\nSKIPPED\n",
        "FAILURES!!!\nINSTRUMENTATION_FAILED\n",
        "INSTRUMENTATION_CODE: -1\n",
    ],
)
def test_instrumentation_skip_or_failure_is_not_a_pass(
    output: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    apk = tmp_path / "app.apk"
    test_apk = tmp_path / "test.apk"
    apk.touch()
    test_apk.touch()
    monkeypatch.setattr(
        runner,
        "validate_emulator",
        lambda *_args, **_kwargs: None,
    )

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        return subprocess.CompletedProcess(command, 0, b"", b"")

    monkeypatch.setattr(runner.subprocess, "run", fake_run)

    def capture(_command: list[str], log_path: Path, *, timeout: int) -> int:
        del timeout
        log_path.write_text(output, encoding="utf-8")
        return 0

    monkeypatch.setattr(runner, "_capture_instrumentation", capture)
    with pytest.raises(runner.ReplayError):
        runner.run_replay(
            adb="adb",
            serial="emulator-5554",
            apk=apk,
            test_apk=test_apk,
            fixture=runner.read_fixture(fixture_values()),
            timeout=10,
        )


def test_failure_diagnostics_redact_credentials_and_pat() -> None:
    fixture = runner.read_fixture(fixture_values())
    diagnostic = runner.redact(
        "failed for replay@example.invalid disposable-test-password "
        "sna_abcdefghijklmno", fixture
    )
    assert "replay@example.invalid" not in diagnostic
    assert "disposable-test-password" not in diagnostic
    assert "sna_abcdefghijklmno" not in diagnostic
    assert len(runner.redact("x" * 10_000, fixture)) <= runner.MAX_DIAGNOSTICS


def test_failed_instrumentation_redacts_secret_output(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    apk = tmp_path / "app.apk"
    test_apk = tmp_path / "test.apk"
    apk.touch()
    test_apk.touch()
    fixture = runner.read_fixture(fixture_values())
    monkeypatch.setattr(runner, "validate_emulator", lambda *_args, **_kwargs: None)

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        return subprocess.CompletedProcess(command, 0, b"", b"")

    monkeypatch.setattr(runner.subprocess, "run", fake_run)

    def capture(_command: list[str], log_path: Path, *, timeout: int) -> int:
        del timeout
        log_path.write_text(
            "FAILURES!!! replay@example.invalid disposable-test-password "
            "sna_abcdefghijklmno",
            encoding="utf-8",
        )
        return 1

    monkeypatch.setattr(runner, "_capture_instrumentation", capture)
    with pytest.raises(runner.ReplayError) as error:
        runner.run_replay(
            adb="adb",
            serial="emulator-5554",
            apk=apk,
            test_apk=test_apk,
            fixture=fixture,
            timeout=10,
        )
    message = str(error.value)
    assert "replay@example.invalid" not in message
    assert "disposable-test-password" not in message
    assert "sna_abcdefghijklmno" not in message


def test_instrumentation_log_is_private_and_bounded(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    payload = b"x" * (runner.MAX_RAW_LOG_BYTES + 100)

    class FakeProcess:
        stdout = io.BytesIO(payload)

        def wait(self, *, timeout: int) -> int:
            assert timeout == 5
            return 0

        def kill(self) -> None:
            pytest.fail("successful fake process was killed")

    monkeypatch.setattr(
        runner.subprocess, "Popen", lambda *_args, **_kwargs: FakeProcess()
    )
    log_path = tmp_path / "raw.log"
    assert runner._capture_instrumentation(["adb"], log_path, timeout=5) == 0
    assert stat.S_IMODE(log_path.stat().st_mode) == 0o600
    assert log_path.stat().st_size == runner.MAX_RAW_LOG_BYTES + len(
        runner.TRUNCATED_LOG_MARKER
    )


def test_instrumentation_timeout_force_stops_app_and_cleans_runner_state(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    apk = tmp_path / "app.apk"
    test_apk = tmp_path / "test.apk"
    apk.touch()
    test_apk.touch()
    calls: list[list[str]] = []
    monkeypatch.setattr(runner, "validate_emulator", lambda *_args, **_kwargs: None)

    def fake_run(
        command: list[str], **_kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        calls.append(command)
        if command[-2:] == ["reverse", "--list"]:
            return subprocess.CompletedProcess(command, 0, b"", b"")
        return subprocess.CompletedProcess(command, 0, b"", b"")

    monkeypatch.setattr(runner.subprocess, "run", fake_run)

    def capture(command: list[str], _log_path: Path, *, timeout: int) -> int:
        raise subprocess.TimeoutExpired(command, timeout=timeout)

    monkeypatch.setattr(runner, "_capture_instrumentation", capture)
    with pytest.raises(runner.ReplayError, match="timed out"):
        runner.run_replay(
            adb="adb",
            serial="emulator-5554",
            apk=apk,
            test_apk=test_apk,
            fixture=runner.read_fixture(fixture_values()),
            timeout=1,
        )
    assert any(
        "force-stop" in command and runner.APP_ID in command for command in calls
    )
    assert any("--remove" in command for command in calls)
    assert any(
        command[-1] == "files/.rust-replay-fixture.json.tmp" for command in calls
    )

