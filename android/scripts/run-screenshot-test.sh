#!/usr/bin/env bash

set -euo pipefail

offline_marker=/data/local/tmp/stricknani-screenshot-offline
offline_watch_pid=''

watch_for_offline_capture() {
  local attempts=180

  while (( attempts > 0 ))
  do
    if adb shell test -f "$offline_marker" >/dev/null 2>&1
    then
      adb reverse --remove tcp:7674 >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
}

cleanup_offline_capture() {
  if [[ -n "$offline_watch_pid" ]]
  then
    kill "$offline_watch_pid" 2>/dev/null || true
    wait "$offline_watch_pid" 2>/dev/null || true
  fi
  adb reverse tcp:7674 tcp:7674 >/dev/null 2>&1 || true
  adb shell rm -f "$offline_marker" >/dev/null 2>&1 || true
}

adb shell rm -f "$offline_marker" >/dev/null 2>&1 || true
watch_for_offline_capture &
offline_watch_pid=$!
trap cleanup_offline_capture EXIT

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
if ! compgen -G "$GITHUB_WORKSPACE/screenshot-captures/*.png" >/dev/null
then
  adb exec-out screencap -p > "$GITHUB_WORKSPACE/screenshot-captures/${DEVICE}_${SCREENSHOT_THEME}_offline.png" || true
fi
timeout 60s adb logcat -d > "$GITHUB_WORKSPACE/screenshot-logcat.txt" || true
timeout 20s adb exec-out screencap -p > "$GITHUB_WORKSPACE/screenshot-final-frame.png" || true
exit "$test_rc"

# vim: set ft=sh et ts=2 sw=2 :
