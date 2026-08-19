#!/usr/bin/env bash

set -euo pipefail

test_rc=0
if ./gradlew connectedDebugAndroidTest --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.class=blue.anika.wolle.screenshots.StricknaniScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.e2e_base_url=http://127.0.0.1:7674 \
    -Pandroid.testInstrumentationRunnerArguments.e2e_token="$E2E_TOKEN" \
    -Pandroid.testInstrumentationRunnerArguments.screenshot_device="$DEVICE" \
    -Pandroid.testInstrumentationRunnerArguments.screenshot_theme="$SCREENSHOT_THEME"
then
  test_rc=0
else
  test_rc=$?
fi

mkdir -p "$GITHUB_WORKSPACE/screenshot-captures"
adb pull /sdcard/Android/data/blue.anika.wolle.debug/files/screenshot-captures/. \
  "$GITHUB_WORKSPACE/screenshot-captures/" || true
timeout 60s adb logcat -d > "$GITHUB_WORKSPACE/screenshot-logcat.txt" || true
timeout 20s adb exec-out screencap -p > "$GITHUB_WORKSPACE/screenshot-final-frame.png" || true
exit "$test_rc"

# vim: set ft=sh et ts=2 sw=2 :
