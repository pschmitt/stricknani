#!/usr/bin/env bash

set -euo pipefail

offline_marker=/data/local/tmp/stricknani-screenshot-offline
capture_marker_prefix=/data/local/tmp/stricknani-screenshot-capture-
capture_watch_pid=''
capture_names=(
  onboarding
  home
  project-list
  project
  project-stitch-sample
  project-notes
  yarn-list
  yarn
  settings
  offline
  final-state
)

watch_for_capture_markers() {
  local attempts=900

  while (( attempts > 0 ))
  do
    for name in "${capture_names[@]}"
    do
      local marker="${capture_marker_prefix}${name}"
      local output="$GITHUB_WORKSPACE/screenshot-captures/${DEVICE}_${SCREENSHOT_THEME}_${name}.png"

      if adb shell test -f "$marker" >/dev/null 2>&1
      then
        adb exec-out screencap -p > "$output" || true
        if [[ ! -s "$output" ]]
        then
          rm -f "$output"
        fi
        adb shell rm -f "$marker" >/dev/null 2>&1 || true
      fi
    done

    if adb shell test -f "$offline_marker" >/dev/null 2>&1
    then
      adb reverse --remove tcp:7674 >/dev/null 2>&1 || true
      adb shell rm -f "$offline_marker" >/dev/null 2>&1 || true
    fi

    sleep 1
    attempts=$((attempts - 1))
  done
}

cleanup_capture_markers() {
  if [[ -n "$capture_watch_pid" ]]
  then
    kill "$capture_watch_pid" 2>/dev/null || true
    wait "$capture_watch_pid" 2>/dev/null || true
  fi
  adb reverse tcp:7674 tcp:7674 >/dev/null 2>&1 || true
  adb shell rm -f "$offline_marker" >/dev/null 2>&1 || true
  for name in "${capture_names[@]}"
  do
    adb shell rm -f "${capture_marker_prefix}${name}" >/dev/null 2>&1 || true
  done
}

mkdir -p "$GITHUB_WORKSPACE/screenshot-captures"
adb shell rm -f "$offline_marker" >/dev/null 2>&1 || true
for name in "${capture_names[@]}"
do
  adb shell rm -f "${capture_marker_prefix}${name}" >/dev/null 2>&1 || true
done
watch_for_capture_markers &
capture_watch_pid=$!
trap cleanup_capture_markers EXIT

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

sleep 2
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
