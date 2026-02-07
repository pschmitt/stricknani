#!/usr/bin/env bash
set -euo pipefail

main() {
  ./hack/vendir-sync.sh

  if ! git diff --exit-code -- vendir.lock.yml stricknani/static/vendor
  then
    echo "Vendored assets are out of date. Run: ./hack/vendir-sync.sh" >&2
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi
