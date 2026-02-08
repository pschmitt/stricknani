#!/usr/bin/env bash

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --help, -h      Show this help message
  --version, -v   Show Renovate version
  --token TOKEN   GitHub token (optional, uses gh CLI if not provided)
EOF
}

renovate() {
  nix run nixpkgs#renovate -- "$@"
}

run_renovate() {
  local github_token
  github_token="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null)}"

  # Check if we have a token
  if [[ -z "${github_token}" ]]
  then
    {
      echo "ERROR: GitHub token not available. Either:" >&2
      echo "  1. Set GITHUB_TOKEN environment variable" >&2
      echo "  2. Install GitHub CLI (gh) and run 'gh auth login'"
    } >&2
    return 1
  fi

  echo "Running Renovate in $(pwd)..."
  echo "Using GitHub token: ${github_token:0:10}..."

  # Run Renovate with --platform local
  renovate -- \
    --token "${github_token}" \
    --platform local \
    "$@"
}

main() {
  # Change to git root directory for --platform local
  cd "$(git rev-parse --show-toplevel)" || return 9

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

  # Default action is to run renovate
  run_renovate "$@"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi
