#!/usr/bin/env bash
# shellcheck shell=bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly script_dir
repo_root="$(cd -- "${script_dir}/.." && pwd)"
readonly repo_root
readonly port="${E2E_PORT:-8765}"
readonly artifact_dir="${E2E_ARTIFACT_DIR:-${repo_root}/e2e-artifacts}"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/stricknani-e2e.XXXXXX")"
readonly temp_dir
server_pid=""

cleanup() {
  local status=$?

  if [[ -n "${server_pid}" ]]; then
    kill "${server_pid}" 2>/dev/null || true
    wait "${server_pid}" 2>/dev/null || true
  fi

  mkdir -p "${artifact_dir}"
  if [[ -f "${temp_dir}/server.log" ]]; then
    cp "${temp_dir}/server.log" "${artifact_dir}/server.log"
  fi
  rm -rf "${temp_dir}"
  trap - EXIT

  return "${status}"
}

wait_for_health() {
  local attempts=0
  local max_attempts=60

  while ((attempts < max_attempts)); do
    if ! kill -0 "${server_pid}" 2>/dev/null; then
      echo "Stricknani exited before becoming ready; server log follows:" >&2
      cat "${temp_dir}/server.log" >&2
      return 1
    fi
    if curl --fail --silent "http://127.0.0.1:${port}/healthz" >/dev/null; then
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done

  echo "Stricknani did not become ready; server log follows:" >&2
  if [[ -f "${temp_dir}/server.log" ]]; then
    cat "${temp_dir}/server.log" >&2
  fi
  return 1
}

main() {
  trap cleanup EXIT

  mkdir -p "${artifact_dir}/screenshots" "${temp_dir}/media"
  export E2E_BASE_URL="http://127.0.0.1:${port}"
  export E2E_EMAIL="${E2E_EMAIL:-e2e@example.invalid}"
  export E2E_PASSWORD="${E2E_PASSWORD:-ci-e2e-password}"
  export E2E_SCREENSHOT_DIR="${artifact_dir}/screenshots"
  export BIND_HOST=127.0.0.1
  export BIND_PORT="${port}"
  export DATABASE_URL="sqlite:///${temp_dir}/stricknani.db"
  export MEDIA_ROOT="${temp_dir}/media"
  export SECRET_KEY="${SECRET_KEY:-stricknani-e2e-secret-key}"
  export CSRF_SECRET_KEY="${CSRF_SECRET_KEY:-stricknani-e2e-csrf-secret-key}"
  export INITIAL_ADMIN_EMAIL="${E2E_EMAIL}"
  export INITIAL_ADMIN_PASSWORD="${E2E_PASSWORD}"
  export FEATURE_SIGNUP_ENABLED=false
  export FEATURE_WAYBACK_ENABLED=false
  export FEATURE_AI_IMPORT_ENABLED=false
  export DEFAULT_LANGUAGE=en

  uv run uvicorn stricknani.main:app \
    --host "${BIND_HOST}" \
    --port "${BIND_PORT}" \
    --log-level info \
    >"${temp_dir}/server.log" 2>&1 &
  server_pid=$!

  wait_for_health
  uv run pytest -q \
    -o addopts="" \
    --junitxml="${artifact_dir}/e2e-results.xml" \
    tests/e2e "$@"
}

main "$@"

# modeline: shell: bash
