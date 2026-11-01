#!/usr/bin/env python3
"""Run the Rust-backend replay test on one explicitly selected emulator."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

APP_ID = "blue.anika.wolle.debug"
TEST_COMPONENT = "blue.anika.wolle.debug.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "blue.anika.wolle.sync.RustBackendReplayIntegrationTest"
FIXTURE_PATH = "files/rust-replay-fixture.json"
MAX_DIAGNOSTICS = 4_000
MAX_RAW_LOG_BYTES = 1_000_000
TRUNCATED_LOG_MARKER = b"\n[runner: instrumentation log truncated]\n"


class ReplayError(RuntimeError):
    """A safe-to-display runner error with no fixture values attached."""


@dataclass(frozen=True, repr=False)
class Fixture:
    url: str
    source_url: str
    email: str
    password: str
    port: int

    def json_bytes(self) -> bytes:
        return json.dumps(
            {"url": self.url, "email": self.email, "password": self.password},
            separators=(",", ":"),
        ).encode("utf-8")


def validate_url(value: str) -> tuple[str, int]:
    """Require a plain loopback HTTP(S) origin suitable for adb reverse."""
    if not value or any(
        character.isspace() or ord(character) < 32 for character in value
    ):
        raise ReplayError("STRICKNANI_RUST_TEST_URL must be a valid loopback URL")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise ReplayError(
            "STRICKNANI_RUST_TEST_URL must be a valid loopback URL"
        ) from error
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or parsed.query
    ):
        raise ReplayError("STRICKNANI_RUST_TEST_URL must be a valid loopback URL")
    host = parsed.hostname
    is_loopback = host.lower() == "localhost"
    if not is_loopback:
        try:
            is_loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            is_loopback = False
    if not is_loopback:
        raise ReplayError("STRICKNANI_RUST_TEST_URL must use a loopback host")
    if port is None:
        port = 443 if parsed.scheme == "https" else 80
    if not 1 <= port <= 65535:
        raise ReplayError("STRICKNANI_RUST_TEST_URL has an invalid port")
    normalized = urlunsplit(
        (parsed.scheme, f"127.0.0.1:{port}", parsed.path.rstrip("/"), "", "")
    )
    return normalized, port


def read_fixture(environment: Mapping[str, str] = os.environ) -> Fixture:
    """Read the three documented fixture variables without echoing their values."""
    url = environment.get("STRICKNANI_RUST_TEST_URL", "")
    email = environment.get("STRICKNANI_RUST_TEST_EMAIL", "")
    password = environment.get("STRICKNANI_RUST_TEST_PASSWORD", "")
    if not url or not email or not password:
        raise ReplayError(
            "all STRICKNANI_RUST_TEST_URL/EMAIL/PASSWORD variables are required"
        )
    normalized_url, port = validate_url(url)
    if len(email.encode("utf-8")) > 320 or not email.strip():
        raise ReplayError("STRICKNANI_RUST_TEST_EMAIL is invalid")
    if not 12 <= len(password.encode("utf-8")) <= 1024:
        raise ReplayError("STRICKNANI_RUST_TEST_PASSWORD must be 12 to 1024 bytes")
    return Fixture(normalized_url, url, email, password, port)


def redact(text: str, fixture: Fixture) -> str:
    """Remove exact fixture strings and common PAT forms from diagnostics."""
    values = [fixture.password, fixture.email, fixture.url, fixture.source_url]
    values.sort(key=len, reverse=True)
    for value in values:
        if value:
            text = text.replace(value, "[redacted]")
    text = re.sub(r"sna_[A-Za-z0-9_-]{8,}", "[redacted-token]", text)
    text = re.sub(r"(?i)Bearer\s+\S+", "Bearer [redacted-token]", text)
    return text[-MAX_DIAGNOSTICS:]


def _run(
    command: Sequence[str],
    *,
    timeout: int,
    input_bytes: bytes | None = None,
    stdout: int = subprocess.PIPE,
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            list(command),
            input=input_bytes,
            stdout=stdout,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise ReplayError("Android replay command timed out") from error
    except OSError as error:
        raise ReplayError("could not execute the Android replay command") from error
    if result.returncode:
        # stderr is deliberately not exposed here; callers can choose to provide a
        # separately redacted bounded diagnostic from the instrumentation log.
        raise ReplayError("Android replay command failed")
    return result


def _adb(adb: str, serial: str, *args: str) -> list[str]:
    return [adb, "-s", serial, *args]


def _tcp_port(value: str) -> int | None:
    if not value.startswith("tcp:"):
        return None
    try:
        port = int(value.removeprefix("tcp:"))
    except ValueError:
        return None
    return port if 1 <= port <= 65535 else None


def ensure_reverse(adb: str, serial: str, port: int, *, timeout: int) -> bool:
    """Reuse an exact reverse mapping, create an absent one, or reject conflicts."""
    result = _run(_adb(adb, serial, "reverse", "--list"), timeout=timeout)
    for line in result.stdout.decode("utf-8", errors="replace").splitlines():
        fields = line.split()
        # adb scopes --list to the selected transport; its label can be host-N.
        if len(fields) == 3:
            fields = fields[1:]
        if len(fields) != 2:
            continue
        guest_port = _tcp_port(fields[0])
        if guest_port != port:
            continue
        if _tcp_port(fields[1]) == port:
            return False
        raise ReplayError("selected emulator already has a conflicting reverse mapping")
    _run(
        _adb(
            adb,
            serial,
            "reverse",
            "--no-rebind",
            f"tcp:{port}",
            f"tcp:{port}",
        ),
        timeout=timeout,
    )
    return True


def validate_emulator(adb: str, serial: str, *, timeout: int) -> None:
    if (
        not serial.startswith("emulator-")
        or not serial.removeprefix("emulator-").isdigit()
    ):
        raise ReplayError("--serial must name an emulator-* device")
    devices = _run([adb, "devices", "-l"], timeout=timeout).stdout.decode(
        "utf-8", errors="replace"
    )
    if not any(
        line.split()[:2] == [serial, "device"]
        for line in devices.splitlines()
        if line.strip()
    ):
        raise ReplayError("selected emulator is not connected and ready")
    emulator = (
        _run(_adb(adb, serial, "shell", "getprop", "ro.kernel.qemu"), timeout=timeout)
        .stdout.decode("utf-8", errors="replace")
        .strip()
    )
    if emulator != "1":
        raise ReplayError("selected Android device does not report emulator mode")


def instrumentation_succeeded(output: str) -> bool:
    expected = f"INSTRUMENTATION_STATUS: class={TEST_CLASS}"
    if expected not in output or "INSTRUMENTATION_CODE: -1" not in output:
        return False
    if re.search(r"INSTRUMENTATION_STATUS_CODE:\s*-3\b|\bSKIPPED\b", output):
        return False
    if "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output:
        return False
    summary = re.search(r"\bOK \((\d+) tests?\)", output)
    return summary is not None and int(summary.group(1)) > 0


def _capture_instrumentation(
    command: Sequence[str], log_path: Path, *, timeout: int
) -> int:
    """Drain instrumentation output while storing at most one megabyte privately."""
    log_fd = os.open(log_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        process = subprocess.Popen(
            list(command),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )
    except OSError as error:
        os.close(log_fd)
        raise ReplayError("could not start instrumentation command") from error
    with os.fdopen(log_fd, "wb") as log:
        if process.stdout is None:
            process.kill()
            process.wait()
            raise ReplayError("could not capture instrumentation output")
        pipe = process.stdout
        stored = 0
        truncated = False
        reader_error: OSError | None = None

        def drain() -> None:
            nonlocal stored, truncated, reader_error
            try:
                while chunk := pipe.read(8192):
                    available = MAX_RAW_LOG_BYTES - stored
                    if available > 0:
                        selected = chunk[:available]
                        log.write(selected)
                        stored += len(selected)
                    if len(chunk) > available:
                        truncated = True
            except OSError as error:
                reader_error = error

        reader = threading.Thread(target=drain, name="rust-replay-log-reader")
        reader.start()
        try:
            return_code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            reader.join()
            raise
        reader.join()
        if reader_error is not None:
            raise ReplayError(
                "could not capture instrumentation output"
            ) from reader_error
        if truncated:
            log.write(TRUNCATED_LOG_MARKER)
        return return_code


def _safe_path(raw: str, label: str) -> Path:
    path = Path(raw).expanduser()
    if not path.is_file():
        raise ReplayError(f"{label} must point to an existing APK file")
    return path.resolve()


def run_replay(
    *,
    adb: str,
    serial: str,
    apk: Path,
    test_apk: Path,
    fixture: Fixture,
    timeout: int,
) -> None:
    # Device checks deliberately precede package installation or fixture writes.
    validate_emulator(adb, serial, timeout=timeout)
    reverse_created = ensure_reverse(adb, serial, fixture.port, timeout=timeout)
    provision_started = False
    try:
        _run(_adb(adb, serial, "install", "-r", str(apk)), timeout=timeout)
        _run(
            _adb(adb, serial, "install", "-r", "-t", str(test_apk)),
            timeout=timeout,
        )
        _run(
            _adb(adb, serial, "shell", "run-as", APP_ID, "mkdir", "-p", "files"),
            timeout=timeout,
        )
        _run(
            _adb(adb, serial, "shell", "run-as", APP_ID, "chmod", "700", "files"),
            timeout=timeout,
        )
        provision_started = True
        temp_fixture = "files/.rust-replay-fixture.json.tmp"
        _run(
            _adb(
                adb,
                serial,
                "shell",
                "run-as",
                APP_ID,
                "toybox",
                "tee",
                temp_fixture,
            ),
            timeout=timeout,
            input_bytes=fixture.json_bytes(),
            stdout=subprocess.DEVNULL,
        )
        _run(
            _adb(
                adb,
                serial,
                "shell",
                "run-as",
                APP_ID,
                "chmod",
                "600",
                temp_fixture,
            ),
            timeout=timeout,
        )
        _run(
            _adb(
                adb,
                serial,
                "shell",
                "run-as",
                APP_ID,
                "mv",
                "-f",
                temp_fixture,
                FIXTURE_PATH,
            ),
            timeout=timeout,
        )
        _run(
            _adb(adb, serial, "shell", "run-as", APP_ID, "chmod", "600", FIXTURE_PATH),
            timeout=timeout,
        )
        with tempfile.TemporaryDirectory(prefix="stricknani-rust-replay-") as temp_dir:
            os.chmod(temp_dir, 0o700)
            log_path = Path(temp_dir) / "instrumentation.log"
            instrumentation_command = _adb(
                adb,
                serial,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                TEST_CLASS,
                TEST_COMPONENT,
            )
            try:
                return_code = _capture_instrumentation(
                    instrumentation_command, log_path, timeout=timeout
                )
            except subprocess.TimeoutExpired as error:
                try:
                    _run(
                        _adb(adb, serial, "shell", "am", "force-stop", APP_ID),
                        timeout=timeout,
                    )
                except ReplayError:
                    pass
                raise ReplayError("Rust replay instrumentation timed out") from error
            if return_code:
                raw = log_path.read_text(encoding="utf-8", errors="replace")
                raise ReplayError(redact(raw, fixture) or "instrumentation failed")
            raw = log_path.read_text(encoding="utf-8", errors="replace")
            if not instrumentation_succeeded(raw):
                raise ReplayError(
                    redact(raw, fixture) or "instrumentation did not pass"
                )
    finally:
        # Remove only the private fixture written by this runner and its exact
        # reverse mapping. No app data reset or package uninstall is performed.
        if provision_started:
            try:
                _run(
                    _adb(
                        adb,
                        serial,
                        "shell",
                        "run-as",
                        APP_ID,
                        "rm",
                        "-f",
                        FIXTURE_PATH,
                        "files/.rust-replay-fixture.json.tmp",
                    ),
                    timeout=timeout,
                )
            except ReplayError:
                pass
        if reverse_created:
            try:
                _run(
                    _adb(adb, serial, "reverse", "--remove", f"tcp:{fixture.port}"),
                    timeout=timeout,
                )
            except ReplayError:
                pass


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="path to the adb executable")
    parser.add_argument("--serial", required=True, help="explicit emulator-* serial")
    parser.add_argument("--apk", required=True, help="ABI-matched debug app APK")
    parser.add_argument(
        "--test-apk", required=True, help="matching instrumentation APK"
    )
    parser.add_argument(
        "--timeout", type=int, default=300, help="per-command timeout in seconds"
    )
    args = parser.parse_args(argv)
    if args.timeout <= 0 or args.timeout > 1800:
        print(
            "runner error: --timeout must be between 1 and 1800 seconds",
            file=sys.stderr,
        )
        return 2
    adb = shutil.which(args.adb) if os.sep not in args.adb else args.adb
    if not adb or not Path(adb).is_file():
        print("runner error: --adb must point to an executable", file=sys.stderr)
        return 2
    try:
        apk = _safe_path(args.apk, "--apk")
        test_apk = _safe_path(args.test_apk, "--test-apk")
        # Reject obviously non-emulator targets before even loading credentials.
        if (
            not args.serial.startswith("emulator-")
            or not args.serial.removeprefix("emulator-").isdigit()
        ):
            raise ReplayError("--serial must name an emulator-* device")
        fixture = read_fixture()
        run_replay(
            adb=adb,
            serial=args.serial,
            apk=apk,
            test_apk=test_apk,
            fixture=fixture,
            timeout=args.timeout,
        )
    except ReplayError as error:
        print(f"runner error: {str(error)[:MAX_DIAGNOSTICS]}", file=sys.stderr)
        return 1
    print("Rust backend replay instrumentation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
