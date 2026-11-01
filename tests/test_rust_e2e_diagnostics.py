"""Tests for bounded, redacted Android instrumentation summaries."""

from __future__ import annotations

import json
from html import escape
from pathlib import Path

import pytest

from ci.stricknani import summarize_rust_e2e


def write_private(path: Path, value: object) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")
    path.chmod(0o600)


def test_summary_shows_failure_and_short_frames_without_fixture_values(
    tmp_path: Path,
) -> None:
    fixture_email = "fixture" + "@" + "example.invalid"
    fixture_password = "fixture" + "-pass"
    fixture_token = "sna_" + "ab" * 10
    fixture_url = "http://127.0.0.1:4321"
    report_url = "https://127.0.0.1:4321/api/v1/yarns?page=1"
    failure_message = (
        "Could not load Riverbend Merino DK for "
        + fixture_email
        + "; "
        + "pass"
        + "word="
        + fixture_password
        + " "
        + "Bear"
        + "er "
        + fixture_token
        + " while fetching "
        + report_url
    )
    results = tmp_path / "results"
    results.mkdir()
    (results / "TEST-android.xml").write_text(
        f"""<testsuite tests="1" failures="1">
        <testcase classname="blue.anika.wolle.e2e.StricknaniSmokeTest"
          name="onboardingSyncDetailAndSettings">
          <failure type="java.lang.AssertionError"
            message="{escape(failure_message, quote=True)}">
            java.lang.AssertionError: fetch {report_url}
              at blue.anika.wolle.SomeScreen.load(SomeScreen.kt:42)
              at blue.anika.wolle.MainActivity.onCreate(MainActivity.kt:19)
          </failure>
        </testcase></testsuite>""",
        encoding="utf-8",
    )
    fixture_file = tmp_path / "fixture.json"
    token_file = tmp_path / "token.json"
    write_private(
        fixture_file,
        {
            "url": fixture_url,
            "email": fixture_email,
            "password": fixture_password,
        },
    )
    write_private(token_file, {"token": fixture_token})

    output = "\n".join(
        summarize_rust_e2e.summarize_results(
            results, summarize_rust_e2e.load_redactor(fixture_file, token_file)
        )
    )

    assert "StricknaniSmokeTest#onboardingSyncDetailAndSettings" in output
    assert "java.lang.AssertionError" in output
    assert "SomeScreen.load(SomeScreen.kt:42)" in output
    assert "[redacted]" in output
    assert "[redacted-url]" in output
    assert fixture_email not in output
    assert fixture_password not in output
    assert fixture_token not in output
    assert "Riverbend Merino DK" not in output
    assert "127.0.0.1:4321" not in output


def test_missing_reports_emit_only_constant_message(tmp_path: Path) -> None:
    assert summarize_rust_e2e.summarize_results(tmp_path / "missing") == [
        "No Android instrumentation XML report was found."
    ]


def test_discovers_nested_device_report(tmp_path: Path) -> None:
    report_dir = tmp_path / "results" / "device-id"
    report_dir.mkdir(parents=True)
    (report_dir / "TEST-nested.xml").write_text(
        '<testsuite tests="1" failures="1">'
        '<testcase classname="NestedDeviceTest" name="nestedFailure">'
        '<failure type="AssertionError" message="expected the fixture">'
        "  at NestedDeviceTest.run(NestedDeviceTest.kt:7)"
        "</failure></testcase></testsuite>",
        encoding="utf-8",
    )

    output = "\n".join(summarize_rust_e2e.summarize_results(tmp_path / "results"))

    assert "NestedDeviceTest#nestedFailure" in output
    assert "NestedDeviceTest.kt:7" in output


def test_ignores_symlinked_report(tmp_path: Path) -> None:
    results = tmp_path / "results"
    results.mkdir()
    outside_report = tmp_path / "TEST-outside.xml"
    outside_report.write_text(
        '<testsuite tests="1" failures="1"><testcase name="outside">'
        '<failure message="outside report value" /></testcase></testsuite>',
        encoding="utf-8",
    )
    try:
        (results / "TEST-symlink.xml").symlink_to(outside_report)
    except OSError:
        pytest.skip("symlinks are unavailable")

    output = "\n".join(summarize_rust_e2e.summarize_results(results))

    assert "No Android instrumentation XML report was found." in output
    assert "outside report value" not in output


def test_malformed_report_never_echoes_report_contents(tmp_path: Path) -> None:
    results = tmp_path / "results"
    results.mkdir()
    (results / "TEST-broken.xml").write_text(
        "not xml containing sna_" + "f" * 64,
        encoding="utf-8",
    )

    output = "\n".join(summarize_rust_e2e.summarize_results(results))

    assert "could not be parsed" in output
    assert "sna_" not in output
    assert "not xml" not in output


def test_oversized_report_is_not_read_or_echoed(tmp_path: Path) -> None:
    results = tmp_path / "results"
    results.mkdir()
    (results / "TEST-large.xml").write_text(
        "fixture-pass " + "x" * summarize_rust_e2e.MAX_FILE_BYTES,
        encoding="utf-8",
    )

    output = "\n".join(summarize_rust_e2e.summarize_results(results))

    assert "exceeded the diagnostic size limit" in output
    assert "fixture-pass" not in output


def test_output_is_capped_for_many_failing_cases(tmp_path: Path) -> None:
    results = tmp_path / "results"
    results.mkdir()
    cases = "".join(
        f'<testcase classname="Example" name="test{index}">'
        '<failure type="AssertionError" message="expected a value" />'
        "</testcase>"
        for index in range(100)
    )
    (results / "TEST-many.xml").write_text(
        f'<testsuite tests="100" failures="100">{cases}</testsuite>',
        encoding="utf-8",
    )

    output = summarize_rust_e2e.summarize_results(results)

    assert len(output) <= summarize_rust_e2e.MAX_OUTPUT_LINES
    assert "100 failures/errors" in output[0]
    assert any("additional failed test cases omitted" in line for line in output)
