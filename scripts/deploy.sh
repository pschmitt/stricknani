#!/usr/bin/env bash

prod_host="rofl-10.brkn.lol"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --commit, -c    Commit flake.lock changes after deployment
  --help, -h      Show this help message
EOF
}

deploy() {
  local commit
  commit=false

  # Parse arguments
  while [[ -n $* ]]
  do
    case "$1" in
      --commit|-c)
        commit=1
        shift
        ;;
      --help|-h)
        usage
        return 0
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

  # Change to nixos directory
  cd /etc/nixos || return 1

  # Update stricknani flake input
  if ! nix flake update stricknani
  then
    echo "ERROR: Failed to update stricknani flake input" >&2
    return 1
  fi

  # Rebuild with nixos
  if ! nixos::rebuild --target "${prod_host}"
  then
    echo "ERROR: nixos::rebuild failed" >&2
    return 3
  fi

  # Commit changes if requested
  if [[ -n "${commit:-}" ]]
  then
    if ! git diff --cached --quiet
    then
      echo "ERROR: Won't commit, there are staged changes!" >&2
      return 1
    fi

    git add flake.lock
    git commit -m "chore: update stricknani"
    git push
  fi
}

main() {

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
      --)
        shift
        break
        ;;
      *)
        break
        ;;
    esac
  done

  # Default action is to deploy
  deploy "$@"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi
