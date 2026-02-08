#!/usr/bin/env bash

PROD_HOST="rofl-10.brkn.lol"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --commit, -c    Commit flake.lock changes after deployment
  --help, -h      Show this help message
EOF
}

deploy() {
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
