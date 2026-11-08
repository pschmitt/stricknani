#!/usr/bin/env bash

set -euo pipefail

run_test() {
  local test_class="$1"
  shift

  local -a args=(
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
    "-Pandroid.testInstrumentationRunnerArguments.e2e_base_url=http://127.0.0.1:7674"
    "-Pandroid.testInstrumentationRunnerArguments.e2e_token=$E2E_TOKEN"
  )
  # A second, ordinary account for the shared-item assertions; optional.
  if [[ -n "${E2E_PEER_TOKEN:-}" ]]; then
    args+=("-Pandroid.testInstrumentationRunnerArguments.e2e_peer_token=$E2E_PEER_TOKEN")
  fi

  ./gradlew connectedDebugAndroidTest --stacktrace "${args[@]}" "$@"
}

case "${E2E_LANE:-smoke}" in
  focused)
    run_test blue.anika.wolle.focused.FocusedAppSemanticsTest
    run_test blue.anika.wolle.focused.FocusedComponentSemanticsTest
    ;;
  full)
    run_test blue.anika.wolle.e2e.StricknaniFullE2eTest
    run_test blue.anika.wolle.e2e.DataBackupE2eTest
    run_test blue.anika.wolle.e2e.StricknaniLiveWriteE2eTest
    ;;
  smoke)
    run_test blue.anika.wolle.e2e.StricknaniSmokeTest
    run_test blue.anika.wolle.e2e.DataBackupE2eTest
    ;;
  backups)
    # Data export and restore only: CI runs it on an older Android as well (see e2e.yaml).
    run_test blue.anika.wolle.e2e.DataBackupE2eTest
    # The same round trip in the local-only mode (skipped without the in-app server library).
    run_test blue.anika.wolle.e2e.LocalModeBackupE2eTest
    ;;
  fgs-demo)
    # The screen recording for Google Play's foreground-service declaration
    # (PlayFgsDemoRecording). The video is kept as a workflow artifact, never committed.
    # /data/local/tmp: newer emulator images do not let the shell user write to /sdcard.
    recording=/data/local/tmp/fgs-demo.mp4
    adb shell rm -f "$recording"
    adb shell screenrecord --time-limit 170 "$recording" &
    recorder=$!
    status=0
    run_test blue.anika.wolle.e2e.PlayFgsDemoRecording \
      "-Pandroid.testInstrumentationRunnerArguments.fgs_demo=true" || status=$?
    adb shell pkill -INT screenrecord || true
    wait "$recorder" || true
    sleep 2
    mkdir -p build/fgs-demo
    adb pull "$recording" build/fgs-demo/fgs-demo.mp4 || true
    exit "$status"
    ;;
  *)
    printf 'Unsupported Android E2E lane: %s\n' "${E2E_LANE}" >&2
    exit 2
    ;;
esac

# vim: set ft=sh et ts=2 sw=2 :
