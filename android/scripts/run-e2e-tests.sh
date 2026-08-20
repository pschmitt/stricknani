#!/usr/bin/env bash

set -euo pipefail

run_test() {
  local test_class="$1"

  ./gradlew connectedDebugAndroidTest --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.class="$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.e2e_base_url=http://127.0.0.1:7674 \
    -Pandroid.testInstrumentationRunnerArguments.e2e_token="$E2E_TOKEN"
}

case "${E2E_LANE:-smoke}" in
  focused)
    run_test blue.anika.wolle.focused.FocusedAppSemanticsTest
    run_test blue.anika.wolle.focused.FocusedComponentSemanticsTest
    ;;
  full)
    run_test blue.anika.wolle.e2e.StricknaniFullE2eTest
    run_test blue.anika.wolle.e2e.StricknaniLiveWriteE2eTest
    ;;
  smoke)
    run_test blue.anika.wolle.e2e.StricknaniSmokeTest
    ;;
  *)
    printf 'Unsupported Android E2E lane: %s\n' "${E2E_LANE}" >&2
    exit 2
    ;;
esac

# vim: set ft=sh et ts=2 sw=2 :
