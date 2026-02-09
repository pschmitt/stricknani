#!/usr/bin/env bash

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -h, --help   Show this help message
  --trace      Enable shell tracing
EOF
}

should_skip() {
  case "$1" in
    stricknani/static/vendor/*) return 0 ;;
    *.png|*.jpg|*.jpeg|*.gif|*.webp|*.avif|*.ico|*.svg) return 0 ;;
    *.pdf|*.db) return 0 ;;
    *.woff|*.woff2|*.ttf|*.otf|*.eot) return 0 ;;
    *.mp3|*.mp4|*.mov) return 0 ;;
    *.zip|*.gz|*.tgz|*.xz|*.tar) return 0 ;;
  esac
  return 1
}

trim_whitespace() {
  local file

  echo "Trimming trailing whitespace..."

  while IFS= read -r -d '' file
  do
    if should_skip "$file"
    then
      continue
    fi
    if [[ -f "$file" && ! -L "$file" ]]
    then
      sed -i 's/[[:space:]]\+$//' "$file"
    fi
  done < <(git ls-files -z)
}

main() {
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

  trim_whitespace
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi
