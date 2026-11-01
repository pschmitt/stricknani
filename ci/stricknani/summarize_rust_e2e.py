"""Print a bounded, redacted summary of Android instrumentation failures."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import xml.etree.ElementTree as ET
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RESULTS_DIR = (
    REPO_ROOT / "android/app/build/outputs/androidTest-results/connected/debug"
)
MAX_REPORT_FILES = 32
MAX_SCAN_ENTRIES = 512
MAX_REPORT_DEPTH = 3
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
MAX_FAILURES = 40
MAX_OUTPUT_LINES = 64
MAX_LINE_CHARS = 360
MAX_PRIVATE_FILE_BYTES = 16 * 1024

_EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_URL = re.compile(r"(?i)\bhttps?://[^\s\"'<>]+")
_BEARER = re.compile(r"(?i)\bBearer\s+[^\s\"'<>]+")
_PAT = re.compile(r"\bsna_[A-Za-z0-9_-]{8,}\b")
_CREDENTIAL_FIELD = re.compile(
    r"(?i)\b(password|passwd|passphrase|token|secret|authorization)\b"
    r"\s*(?:[:=]|\bis\b)\s*[\"']?[^\s,;\"']+"
)
_OFFLINE_RECORD = re.compile(
    r"\bE2E (?:offline yarn|project)\s+\d+\b|"
    r"\bRust replay [A-Za-z0-9-]{4,} (?:project|yarn)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True, repr=False)
class Redactor:
    secrets: tuple[str, ...] = field(default=(), repr=False)

    def clean(self, value: str) -> str:
        result = _URL.sub("[redacted-url]", value)
        for secret in self.secrets:
            if secret:
                result = result.replace(secret, "[redacted]")
        result = _BEARER.sub("Bearer [redacted]", result)
        result = _PAT.sub("[redacted-token]", result)
        result = _EMAIL.sub("[redacted-email]", result)
        result = _CREDENTIAL_FIELD.sub(r"\1=[redacted]", result)
        result = _OFFLINE_RECORD.sub("[fixture-record]", result)
        for fixture_value in ("Heirloom Baby Blanket", "Riverbend Merino DK", "Schal"):
            result = result.replace(fixture_value, "[fixture-record]")
        result = " ".join(result.split())
        return result[:MAX_LINE_CHARS]


def _private_strings(path: Path | None) -> tuple[str, ...]:
    if path is None:
        return ()
    try:
        candidate = path.expanduser()
        resolved = candidate.resolve(strict=True)
        info = candidate.lstat()
        if (
            stat.S_ISLNK(info.st_mode)
            or not stat.S_ISREG(info.st_mode)
            or resolved == REPO_ROOT
            or REPO_ROOT in resolved.parents
            or info.st_mode & 0o077
            or info.st_size > MAX_PRIVATE_FILE_BYTES
        ):
            return ()
        value = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError, RuntimeError):
        return ()
    if not isinstance(value, dict):
        return ()
    return tuple(
        item
        for key in ("url", "email", "password", "token")
        if isinstance((item := value.get(key)), str) and item
    )


def load_redactor(fixture_file: Path | None, token_file: Path | None) -> Redactor:
    """Read only the known private fixture fields for output redaction."""
    values = _private_strings(fixture_file) + _private_strings(token_file)
    return Redactor(tuple(sorted(set(values), key=len, reverse=True)))


def _report_files(results_dir: Path) -> tuple[list[Path], bool]:
    try:
        if results_dir.is_symlink() or not results_dir.is_dir():
            return [], False
        files: list[Path] = []
        truncated = False
        directories = [(results_dir, 0)]
        scanned_entries = 0
        while directories:
            directory, depth = directories.pop()
            with os.scandir(directory) as entries:
                for entry in entries:
                    scanned_entries += 1
                    if scanned_entries > MAX_SCAN_ENTRIES:
                        truncated = True
                        return files, truncated
                    try:
                        if entry.is_symlink():
                            continue
                        if entry.is_file(follow_symlinks=False):
                            is_report = entry.name.startswith(
                                "TEST-"
                            ) and entry.name.endswith(".xml")
                            if not is_report:
                                continue
                            if len(files) == MAX_REPORT_FILES:
                                truncated = True
                                return files, truncated
                            files.append(Path(entry.path))
                        elif depth < MAX_REPORT_DEPTH and entry.is_dir(
                            follow_symlinks=False
                        ):
                            directories.append((Path(entry.path), depth + 1))
                    except OSError:
                        continue
        return files, truncated
    except OSError:
        return [], False


def _failure_details(
    root: ET.Element, redactor: Redactor
) -> tuple[int, int, list[str]]:
    cases = list(root.iter("testcase"))
    details: list[str] = []
    failure_count = 0
    failed_case_count = 0
    detailed_case_count = 0
    for case in cases:
        failures = [child for child in case if child.tag in {"failure", "error"}]
        if not failures:
            continue
        failure_count += len(failures)
        failed_case_count += 1
        if detailed_case_count >= MAX_FAILURES:
            continue
        test_name = case.attrib.get("name", "unknown test")
        class_name = case.attrib.get("classname", "unknown class")
        failure = failures[0]
        kind = "error" if failure.tag == "error" else "failure"
        failure_type = failure.attrib.get("type", "")
        message = failure.attrib.get("message", "")
        if not message and failure.text:
            message = next(
                (line.strip() for line in failure.text.splitlines() if line.strip()),
                "",
            )
        label = redactor.clean(f"{class_name}#{test_name}")
        summary = redactor.clean(f"{kind} {failure_type}: {message}")
        details.append(f"- {label}: {summary or kind}")
        detailed_case_count += 1
        if failure.text:
            frame_count = 0
            for line in failure.text.splitlines():
                stripped = line.strip()
                if not stripped.startswith("at "):
                    continue
                details.append(f"  {redactor.clean(stripped)}")
                frame_count += 1
                if frame_count == 2:
                    break
    if failed_case_count > detailed_case_count:
        omitted_count = failed_case_count - detailed_case_count
        details.append(f"- {omitted_count} additional failed test cases omitted.")
    return len(cases), failure_count, details


def summarize_results(results_dir: Path, redactor: Redactor | None = None) -> list[str]:
    """Summarize JUnit failures without exposing full reports or fixture values."""
    redactor = redactor or Redactor()
    files, file_list_truncated = _report_files(results_dir)
    if not files:
        return ["No Android instrumentation XML report was found."]

    output: list[str] = []
    total_cases = 0
    total_failures = 0
    total_bytes = 0
    detail_lines: list[str] = []
    report_count = 0
    skipped_reports = 0
    for path in files:
        try:
            remaining_bytes = MAX_TOTAL_BYTES - total_bytes
            read_limit = min(MAX_FILE_BYTES, remaining_bytes)
            no_follow = getattr(os, "O_NOFOLLOW", 0)
            close_on_exec = getattr(os, "O_CLOEXEC", 0)
            descriptor = os.open(
                path,
                os.O_RDONLY | close_on_exec | no_follow,
            )
            with os.fdopen(descriptor, "rb") as report:
                contents = report.read(read_limit + 1)
            if len(contents) > read_limit:
                output.append(
                    "An Android test report exceeded the diagnostic size limit; "
                    "omitted."
                )
                skipped_reports += 1
                continue
        except OSError:
            output.append("An Android test report could not be read; omitted.")
            skipped_reports += 1
            continue
        total_bytes += len(contents)
        report_count += 1
        if b"<!DOCTYPE" in contents.upper() or b"<!ENTITY" in contents.upper():
            output.append(
                "An Android test report used unsupported XML declarations; omitted."
            )
            skipped_reports += 1
            continue
        try:
            root = ET.fromstring(contents)
        except ET.ParseError:
            output.append("An Android test report could not be parsed; omitted.")
            skipped_reports += 1
            continue
        case_count, failure_count, details = _failure_details(root, redactor)
        total_cases += case_count
        total_failures += failure_count
        detail_lines.extend(details)

    output.insert(
        0,
        "Android instrumentation summary: "
        f"{total_cases} tests, {total_failures} failures/errors, "
        f"{report_count} report files inspected.",
    )
    if file_list_truncated:
        output.append("Report scan stopped at its file or directory-entry limit.")
    if total_failures == 0 and skipped_reports == 0:
        output.append("No failing test cases were present in the parsed reports.")
    available_lines = max(0, MAX_OUTPUT_LINES - len(output))
    if len(detail_lines) > available_lines and available_lines:
        output.extend(detail_lines[: available_lines - 1])
        output.append("Additional failure details omitted by the output limit.")
    else:
        output.extend(detail_lines[:available_lines])
    return output[:MAX_OUTPUT_LINES]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    parser.add_argument("--fixture-file", type=Path)
    parser.add_argument("--token-file", type=Path)
    args = parser.parse_args(argv)
    redactor = load_redactor(args.fixture_file, args.token_file)
    for line in summarize_results(args.results_dir, redactor):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
