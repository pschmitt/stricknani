#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf 'Usage: %s\n' "$(basename "$0")"
}

ADB_BIN=""
EMULATOR_SERIAL=""
REVERSE_INSTALLED=0

cleanup_reverse() {
  if (( REVERSE_INSTALLED == 1 ))
  then
    "$ADB_BIN" -s "$EMULATOR_SERIAL" reverse --remove tcp:7674 >/dev/null 2>&1 || true
  fi
}

run_e2e() {
  local repo_root qemu_value
  repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)" || return 1
  cd "$repo_root/android" || return 1

  if [[ -z "${E2E_TOKEN:-}" ]]
  then
    printf '%s\n' 'E2E_TOKEN must contain the masked disposable fixture token.' >&2
    return 2
  fi
  if [[ ! "${RUST_FIXTURE_PORT:-}" =~ ^[0-9]{1,5}$ ]] \
    || (( 10#${RUST_FIXTURE_PORT} < 1 || 10#${RUST_FIXTURE_PORT} > 65535 ))
  then
    printf '%s\n' 'RUST_FIXTURE_PORT must be a valid TCP port.' >&2
    return 2
  fi

  ADB_BIN="$(command -v adb)" || return 1
  local devices
  devices="$("$ADB_BIN" devices)" || return 1
  local -a ready_devices=() emulators=()
  while IFS= read -r serial
  do
    read -r serial state _ <<< "$serial"
    if [[ "$state" == device ]]
    then
      ready_devices+=("$serial")
      if [[ "$serial" =~ ^emulator-[0-9]+$ ]]
      then
        emulators+=("$serial")
      fi
    fi
  done <<< "$devices"

  if (( ${#ready_devices[@]} != 1 || ${#emulators[@]} != 1 ))
  then
    printf 'Expected exactly one ready device and it must be an emulator; found %s ready devices.\n' \
      "${#ready_devices[@]}" >&2
    return 1
  fi
  EMULATOR_SERIAL="${emulators[0]}"
  qemu_value="$("$ADB_BIN" -s "$EMULATOR_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" || return 1
  if [[ "$qemu_value" != 1 ]]
  then
    printf '%s\n' 'Selected device is not a QEMU-backed Android emulator.' >&2
    return 1
  fi
  # connectedDebugAndroidTest may otherwise target every ready device visible to adb.
  export ANDROID_SERIAL="$EMULATOR_SERIAL"

  trap cleanup_reverse EXIT

  "$ADB_BIN" -s "$EMULATOR_SERIAL" reverse "tcp:7674" "tcp:${RUST_FIXTURE_PORT}" || return 1
  REVERSE_INSTALLED=1
  ./scripts/run-e2e-tests.sh || return 1
}

main() {
  while (( $# > 0 ))
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      *)
        usage >&2
        return 2
        ;;
    esac
  done

  run_e2e
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
