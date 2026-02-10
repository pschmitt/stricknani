#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [--done | --wip | --todo]

List tasks from TODO.md in TSV format.

Options:
  --done    List completed tasks
  --wip     List only work-in-progress tasks
  --todo    List only pending tasks
EOF_USAGE
}

main() {
  local mode="todo"
  local status_filter="todo"

  while [[ -n "$*" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      --done)
        mode="done"
        shift
        ;;
      --wip)
        mode="wip"
        shift
        ;;
      --todo)
        mode="todo"
        shift
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        return 2
        ;;
    esac
  done

  case "$mode" in
    done)
      status_filter="done"
      ;;
    wip)
      status_filter="wip"
      ;;
    todo)
      status_filter="todo"
      ;;
  esac

  cd "$(dirname "$0")/.."

  if ! command -v mq >/dev/null 2>&1
  then
    echo "Missing dependency: mq" >&2
    return 2
  fi

  printf 'ID\tPriority\tStatus\tArea\tSummary\n'

  mq '.' TODO.md | awk -F'|' -v want="$status_filter" '
    function trim(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s)
      return s
    }

    /^\|T[0-9]+\|/ {
      id = trim($2)
      priority = trim($3)
      status = trim($4)
      area = trim($5)
      summary = trim($6)

      if (status == want) {
        if (seen[id]++) {
          next
        }
        print id "\t" priority "\t" status "\t" area "\t" summary
      }
    }
  '
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ts=2 sw=2 et:
