#!/usr/bin/env bash

# The production host comes from STRICKNANI_PROD_HOST in the environment or in
# the private Android dev config, not from this public repository.
ANDROID_DEV_CONFIG="${ANDROID_DEV_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/android-dev/env}"
if [[ -z "${STRICKNANI_PROD_HOST:-}" && -r "$ANDROID_DEV_CONFIG" ]]
then
  # shellcheck source=/dev/null
  source "$ANDROID_DEV_CONFIG"
fi
PROD_HOST="${STRICKNANI_PROD_HOST:-}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --commit, -c    Commit flake.lock changes after deployment
  --help, -h      Show this help message
EOF
}

deploy() {
  if [[ -z "$PROD_HOST" ]]
  then
    echo "STRICKNANI_PROD_HOST is not set" >&2
    return 1
  fi

  # Change to nixos directory
  cd /etc/nixos || return 1

  # Update stricknani flake input
  if ! nix flake update stricknani
  then
    echo "ERROR: Failed to update stricknani flake input" >&2
    return 1
  fi

  # Rebuild with nixos
  if ! zhj nixos::rebuild --target "$PROD_HOST"
  then
    echo "ERROR: nixos::rebuild failed" >&2
    return 3
  fi

  # Commit changes if requested
  if [[ -n "${COMMIT:-}" ]]
  then
    if ! git diff --cached --quiet
    then
      echo "ERROR: Won't commit, there are staged changes!" >&2
      return 1
    fi

    git add flake.lock
    git commit -m "${COMMIT_MSG:-chore: update stricknani}"
    git push
  fi
}

main() {
  local COMMIT

  # global flags
  while [[ -n $* ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      --trace)
        set -x
        shift
        ;;
      --commit|-c)
        COMMIT=1
        shift
        ;;
      --)
        shift
        break
        ;;
      *)
        break
        ;;
    esac
  done

  deploy "$@"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi
