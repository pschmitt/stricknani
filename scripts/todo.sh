#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [OPTIONS] [TICKET_ID_OR_PARTIAL_NAME]

List tasks from TODO.md in TSV format, or show details for a specific task.

Options:
  --open           Alias for --todo
  --done           List completed tasks
  --wip            List only work-in-progress tasks
  --todo           List only pending tasks
  --bug            Include bug-category tasks
  --feat           Include feat-category tasks
  --ref            Include refactor-category tasks
  --docs           Include docs-category tasks
EOF_USAGE
}

trim_field() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

normalize_category() {
  local value="$1"
  case "$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')" in
    ref)
      printf 'refactor'
      ;;
    *)
      printf '%s' "$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
      ;;
  esac
}

is_query_match() {
  local query="$1"
  local id="$2"
  local summary="$3"
  local q_lower id_lower summary_lower normalized_id digits

  q_lower="$(printf '%s' "$query" | tr '[:upper:]' '[:lower:]')"
  id_lower="$(printf '%s' "$id" | tr '[:upper:]' '[:lower:]')"
  summary_lower="$(printf '%s' "$summary" | tr '[:upper:]' '[:lower:]')"

  if [[ "$q_lower" =~ ^t?[0-9]+$ ]]
  then
    digits="${q_lower#t}"
    normalized_id="t${digits}"
    [[ "$id_lower" == "$normalized_id" ]] && return 0
  fi

  [[ "$id_lower" == *"$q_lower"* ]] && return 0
  [[ "$summary_lower" == *"$q_lower"* ]] && return 0
  return 1
}

print_task_details() {
  local task_id="$1"
  local found

  found="$(
    awk -v id="$task_id" '
      BEGIN { in_section = 0 }
      $0 ~ "^### " id ":" {
        in_section = 1
      }
      in_section && $0 ~ "^### T[0-9]+:" && $0 !~ "^### " id ":" {
        exit
      }
      in_section {
        print
      }
    ' TODO.md
  )"

  if [[ -z "$found" ]]
  then
    printf 'No Task Details section found for %s\n' "$task_id"
    return 1
  fi

  printf '%s\n' "$found"
  return 0
}

main() {
  local status_filter="todo"
  local status_explicit=""
  local query=""
  local line
  local row_data
  local id
  local priority
  local status
  local area
  local category
  local summary
  local fifth
  local sixth
  local category_norm
  local matches=0
  local details_ok=0
  local details_found=0
  declare -A query_seen_ids=()
  declare -A wanted_categories=()

  while [[ -n "$*" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      --open|--todo)
        status_filter="todo"
        status_explicit=1
        shift
        ;;
      --done)
        status_filter="done"
        status_explicit=1
        shift
        ;;
      --wip)
        status_filter="wip"
        status_explicit=1
        shift
        ;;
      --bug)
        wanted_categories["bug"]=1
        shift
        ;;
      --feat)
        wanted_categories["feat"]=1
        shift
        ;;
      --ref|--refactor)
        wanted_categories["refactor"]=1
        shift
        ;;
      --docs)
        wanted_categories["docs"]=1
        shift
        ;;
      --)
        shift
        break
        ;;
      -*)
        echo "Unknown option: $1" >&2
        usage >&2
        return 2
        ;;
      *)
        if [[ -n "$query" ]]
        then
          echo "Only one task query is supported" >&2
          usage >&2
          return 2
        fi
        query="$1"
        shift
        ;;
    esac
  done

  if [[ -z "$status_explicit" && -n "$query" ]]
  then
    status_filter=""
  fi

  cd "$(dirname "$0")/.."

  if [[ -z "$query" ]]
  then
    local output
    local -A list_seen_ids=()
    output="$(
      printf 'ID\tPRIO\tSTATUS\tAREA\tCATEGORY\tSUMMARY\n'

      while IFS= read -r line
      do
        [[ ! "$line" =~ ^\|[[:space:]]*T[0-9]+[[:space:]]*\| ]] && continue

        row_data="${line#|}"
        row_data="${row_data%|}"

        IFS='|' read -r id priority status area fifth sixth _ <<< "$row_data"

        id="$(trim_field "${id:-}")"
        priority="$(trim_field "${priority:-}")"
        status="$(trim_field "${status:-}")"
        area="$(trim_field "${area:-}")"
        fifth="$(trim_field "${fifth:-}")"
        sixth="$(trim_field "${sixth:-}")"

        if [[ -n "$sixth" ]]
        then
          category="$fifth"
          summary="$sixth"
        else
          category=""
          summary="$fifth"
        fi

        category_norm="$(normalize_category "$category")"

        [[ -n "$status_filter" && "$status" != "$status_filter" ]] && continue
        if [[ "${#wanted_categories[@]}" -gt 0 ]]
        then
          [[ -z "$category_norm" ]] && continue
          [[ -z "${wanted_categories[$category_norm]:-}" ]] && continue
        fi

        if [[ -n "${list_seen_ids[$id]:-}" ]]
        then
          continue
        fi
        list_seen_ids["$id"]=1

        if [[ -z "$category_norm" ]]
        then
          category_norm="-"
        fi

        printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
          "$id" "$priority" "$status" "$area" "$category_norm" "$summary"
      done < TODO.md
    )"

    tsvtool <<< "$output"

    return 0
  fi

  while IFS= read -r line
  do
    [[ ! "$line" =~ ^\|[[:space:]]*T[0-9]+[[:space:]]*\| ]] && continue

    row_data="${line#|}"
    row_data="${row_data%|}"

    IFS='|' read -r id priority status area fifth sixth _ <<< "$row_data"

    id="$(trim_field "${id:-}")"
    priority="$(trim_field "${priority:-}")"
    status="$(trim_field "${status:-}")"
    area="$(trim_field "${area:-}")"
    fifth="$(trim_field "${fifth:-}")"
    sixth="$(trim_field "${sixth:-}")"

    if [[ -n "$sixth" ]]
    then
      category="$fifth"
      summary="$sixth"
    else
      category=""
      summary="$fifth"
    fi

    category_norm="$(normalize_category "$category")"

    [[ -n "$status_filter" && "$status" != "$status_filter" ]] && continue
    if [[ "${#wanted_categories[@]}" -gt 0 ]]
    then
      [[ -z "$category_norm" ]] && continue
      [[ -z "${wanted_categories[$category_norm]:-}" ]] && continue
    fi

    if ! is_query_match "$query" "$id" "$summary"
    then
      continue
    fi

    if [[ -n "${query_seen_ids[$id]:-}" ]]
    then
      continue
    fi
    query_seen_ids["$id"]=1

    matches=$((matches + 1))
    printf 'ID: %s\n' "$id"
    printf 'Priority: %s\n' "$priority"
    printf 'Status: %s\n' "$status"
    printf 'Area: %s\n' "$area"
    printf 'Category: %s\n' "${category_norm:-unknown}"
    printf 'Summary: %s\n\n' "$summary"

    if print_task_details "$id"
    then
      details_found=1
    else
      details_ok=1
    fi
    printf '\n'
  done < TODO.md

  if [[ "$matches" -eq 0 ]]
  then
    echo "No task matched query: $query" >&2
    return 2
  fi

  if [[ "$details_found" -eq 0 && "$details_ok" -eq 1 ]]
  then
    return 1
  fi

  return 0
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=bash ts=2 sw=2 et:
