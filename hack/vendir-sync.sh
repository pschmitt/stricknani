#!/usr/bin/env bash
set -euo pipefail

ensure_vendir() {
  local version os arch url tmpdir bin

  # renovate: datasource=github-releases depName=carvel-dev/vendir
  version="0.42.0"

  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$os" in
    linux|darwin)
      ;;
    *)
      echo "Unsupported OS for vendir bootstrap: $os" >&2
      return 1
      ;;
  esac

  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64)
      arch="amd64"
      ;;
    aarch64|arm64)
      arch="arm64"
      ;;
    *)
      echo "Unsupported CPU arch for vendir bootstrap: $arch" >&2
      return 1
      ;;
  esac

  url="https://github.com/carvel-dev/vendir/releases/download/v${version}/vendir-${os}-${arch}"

  tmpdir="$(mktemp -d)"
  bin="${tmpdir}/vendir"

  if command -v curl >/dev/null 2>&1
  then
    curl -fsSL "$url" -o "$bin"
  else
    wget -qO "$bin" "$url"
  fi

  chmod +x "$bin"
  echo "$bin"
}

main() {
  local vendir_bin

  vendir_bin="$(command -v vendir || true)"
  if [[ -z "${vendir_bin:-}" ]]
  then
    vendir_bin="$(ensure_vendir)"
  fi

  "$vendir_bin" sync --file vendir.yml
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

