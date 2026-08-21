#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run|--upload]

The default validates the listing and performs no network or Play Console mutation. Upload mode
requires PLAY_STORE_UPLOAD_CONFIRMATION=I_UNDERSTAND_THIS_UPLOADS_TO_GOOGLE_PLAY and an already
authenticated gpc (playconsole-cli) installation.
EOF
}

validate_listing() {
  local extra_args=()
  if [[ "${1:-}" == "--require-ready" ]]
  then
    extra_args+=(--require-ready --require-screenshots)
  fi
  python3 "$root/android/scripts/validate-play-store-metadata.py" --root "$root" "${extra_args[@]}"
}

verify_package() {
  local apps_json
  apps_json="$(mktemp)"
  if ! gpc apps list --output json > "$apps_json"
  then
    rm -f "$apps_json"
    return 1
  fi
  if ! python3 - "$apps_json" "$play_package" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
expected = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))

def contains_package(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"package_name", "packageName"} and item == expected:
                return True
            if contains_package(item):
                return True
    elif isinstance(value, list):
        return any(contains_package(item) for item in value)
    return False

if not contains_package(data):
    raise SystemExit(f"Play Console package {expected} was not returned by gpc apps list")
PY
  then
    rm -f "$apps_json"
    return 1
  fi
  rm -f "$apps_json"
}

upload_images() {
  local image_root="$root/fastlane/metadata/android/en-US/images"
  local bucket
  local image
  local images
  local -a buckets=(phoneScreenshots sevenInchScreenshots tenInchScreenshots)

  for bucket in "${buckets[@]}"
  do
    shopt -s nullglob
    images=("$image_root/$bucket"/*.png)
    shopt -u nullglob
    gpc --package "$play_package" images delete-all --locale en-US --type "$bucket" --confirm
    for image in "${images[@]}"
    do
      gpc --package "$play_package" images upload \
        --locale en-US \
        --type "$bucket" \
        --file "$image"
    done
  done

  gpc --package "$play_package" images upload \
    --locale en-US \
    --type icon \
    --file "$root/branding/playstore/icon-512.png"
  gpc --package "$play_package" images upload \
    --locale en-US \
    --type featureGraphic \
    --file "$root/branding/playstore/feature-graphic.png"
}

main() {
  local mode=dry-run
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  play_package="${PLAY_PACKAGE:-blue.anika.wolle}"

  while [[ -n "${1:-}" ]]
  do
    case "$1" in
      --dry-run)
        mode=dry-run
        shift
        ;;
      --upload)
        mode=upload
        shift
        ;;
      -h|--help)
        usage
        return 0
        ;;
      *)
        printf 'Unknown option: %s\n' "$1" >&2
        usage >&2
        return 2
        ;;
    esac
  done

  if [[ "$mode" == dry-run ]]
  then
    validate_listing
    printf 'Dry run complete; no Play Console credentials or upload command was used.\n'
    return 0
  fi

  if [[ "${PLAY_STORE_UPLOAD_CONFIRMATION:-}" != I_UNDERSTAND_THIS_UPLOADS_TO_GOOGLE_PLAY ]]
  then
    printf 'Upload refused: set PLAY_STORE_UPLOAD_CONFIRMATION to the exact confirmation string.\n' >&2
    return 2
  fi
  if ! command -v gpc >/dev/null 2>&1
  then
    printf 'gpc (playconsole-cli) is required for Play Console uploads.\n' >&2
    return 2
  fi

  validate_listing --require-ready
  verify_package
  upload_images
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
