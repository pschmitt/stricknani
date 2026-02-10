#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PORT="${PORT:-7674}"
DONT_OPEN_BROWSER="${DONT_OPEN_BROWSER:-}"
DEBUG="${DEBUG:-}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -p, --port PORT   Port to bind (default: 7674 or \$PORT env)
  -b, --background  Do not open browser
  -d, --debug       Set DEBUG=true
EOF
}

wait_for_health() {
  local health_url="http://localhost:${PORT}/healthz"
  local timeout_seconds=20
  local elapsed=0

  # Give uvicorn a brief head start before polling.
  sleep 2

  while (( elapsed < timeout_seconds ))
  do
    if curl --silent --show-error --fail --output /dev/null "${health_url}"
    then
      return 0
    fi

    sleep 1
    (( elapsed += 1 ))
  done

  echo "ERROR: Timed out waiting for ${health_url} after ${timeout_seconds}s" >&2
  return 1
}

run_dev_server() {
  local -a nix_args

  cd "$REPO_ROOT" || return 1

  local cmd=(
    uv
    run
    uvicorn
    stricknani.main:app
    --reload
    --host 0.0.0.0
    --port "${PORT}"
    --log-level debug
    --access-log
  )

  local env_vars=(
    IMPORT_TRACE_ENABLED=1
  )

  if [[ -n "$DEBUG" ]]
  then
    env_vars+=("DEBUG=true")
  fi

  if [[ -z "${IN_NIX_SHELL:-}" ]]
  then
    if ! command -v nix &>/dev/null
    then
      echo "WARNING: nix not found; running without nix develop" >&2
    else
      nix_args=(--port "$PORT")
      if [[ -n "$DONT_OPEN_BROWSER" ]]
      then
        nix_args+=(--background)
      fi
      if [[ -n "$DEBUG" ]]
      then
        nix_args+=(--debug)
      fi
      exec nix develop -c "${SCRIPT_DIR}/$(basename "$0")" "${nix_args[@]}"
    fi
  fi

  if [[ -z "$DONT_OPEN_BROWSER" ]]
  then
    (
      if wait_for_health
      then
        "${BROWSER:-xdg-open}" "http://localhost:${PORT}"
      fi
    ) &
  fi

  env "${env_vars[@]}" "${cmd[@]}"
}

main() {
  while [[ -n $* ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      -p|--port)
        if [[ -z "${2:-}" ]]
        then
          echo "ERROR: --port requires a value" >&2
          return 1
        fi
        PORT="$2"
        shift 2
        ;;
      --port=*)
        PORT="${1#--port=}"
        shift
        ;;
      -b|--background)
        DONT_OPEN_BROWSER=1
        shift
        ;;
      -d|--debug)
        DEBUG=true
        shift
        ;;
      --)
        shift
        break
        ;;
      *)
        echo "ERROR: Unknown option: $1" >&2
        usage
        return 1
        ;;
    esac
  done

  run_dev_server
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=bash ts=2 sw=2 et:
