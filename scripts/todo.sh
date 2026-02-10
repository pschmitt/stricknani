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
  local status_filter="todo"
  local mq_query='select(., test(to_text(.), "^\\|T[0-9]+\\|")) | select(., contains(to_text(.), join(array("|", get_variable("status"), "|"), "")))'
  local row
  local row_data
  local id
  local priority
  local status
  local area
  local summary
  declare -A seen_ids=()

  while [[ -n "$*" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      --done)
        status_filter="done"
        shift
        ;;
      --wip)
        status_filter="wip"
        shift
        ;;
      --todo)
        status_filter="todo"
        shift
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        return 2
        ;;
    esac
  done

  cd "$(dirname "$0")/.."

  if ! command -v mq >/dev/null 2>&1
  then
    echo "Missing dependency: mq" >&2
    return 2
  fi

  printf 'ID\tPriority\tStatus\tArea\tSummary\n'

  while IFS= read -r row
  do
    [[ -z "${row// /}" ]] && continue
    row_data="${row#|}"
    row_data="${row_data%|}"

    IFS='|' read -r id priority status area summary <<< "$row_data"
    [[ -z "${id:-}" ]] && continue

    if [[ -n "${seen_ids[$id]:-}" ]]
    then
      continue
    fi

    seen_ids["$id"]=1
    printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$priority" "$status" "$area" "$summary"
  done < <(
    mq 'select(., is_table_cell(.))' TODO.md \
      | mq -I text -F text --args status "$status_filter" "$mq_query"
  )
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ts=2 sw=2 et:
