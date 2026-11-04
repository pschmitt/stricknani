#!/usr/bin/env bash

set -uo pipefail

usage() {
  printf 'Usage: %s\n' "$(basename "$0")"
}

main() {
  if (( $# != 0 ))
  then
    usage >&2
    return 2
  fi
  if [[ -z "${RUNNER_TEMP:-}" ]]
  then
    printf '%s\n' 'RUNNER_TEMP must identify the private fixture directory.' >&2
    return 2
  fi

  local repo_root adb_bin serial
  repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)" || return 1
  cd "$repo_root" || return 1
  adb_bin="$(command -v adb)" || return 1
  local -a emulators=()
  while IFS= read -r serial
  do
    emulators+=("$serial")
  done < <("$adb_bin" devices | awk '$2 == "device" && $1 ~ /^emulator-[0-9]+$/ { print $1 }')
  if (( ${#emulators[@]} != 1 ))
  then
    printf 'Expected exactly one ready emulator; found %s.\n' "${#emulators[@]}" >&2
    return 1
  fi

  # npm runs the script from web/, so every path below is absolute.
  npm --prefix web run --silent fixture -- run-replay \
    --fixture-file "$RUNNER_TEMP/stricknani-rust-fixture.json" \
    --adb "$adb_bin" \
    --serial "${emulators[0]}" \
    --apk "$repo_root/android/app/build/outputs/apk/debug/app-x86_64-debug.apk" \
    --test-apk "$repo_root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
    2>&1 | tee "$RUNNER_TEMP/rust-replay-sanitized.log" || return 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]
then
  main "$@"
fi

# vim: set et ts=2 sw=2 :
