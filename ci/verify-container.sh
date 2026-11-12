#!/usr/bin/env bash

wait_for_health() {
  local container_name="$1"
  local health_url="$2"
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline ))
  do
    if curl --fail --silent --header 'Host: localhost' "$health_url" >/dev/null
    then
      return 0
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]]
    then
      printf '%s\n' 'Container exited before becoming healthy.' >&2
      return 1
    fi
    sleep 2
  done
  printf '%s\n' 'Container health check timed out after 60 seconds.' >&2
  return 1
}

main() (
  set -Eeuo pipefail
  local image="${NIX_IMAGE:-ghcr.io/pschmitt/stricknani:latest}"
  local container_name="stricknani-image-test-$$"
  local port
  local app
  local asset
  trap 'docker rm --force --volumes "$container_name" >/dev/null 2>&1 || true' EXIT

  [[ "$(docker image inspect --format '{{.Config.User}}' "$image")" == 65532:65532 ]] || return 1
  docker run --detach --publish 127.0.0.1::7674 --name "$container_name" \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
    --tmpfs /var/lib/stricknani:rw,uid=65532,gid=65532,mode=0750 \
    "$image" >/dev/null || return 1
  port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "7674/tcp") 0).HostPort}}' "$container_name")" || return 1
  if ! wait_for_health "$container_name" "http://127.0.0.1:$port/healthz"
  then
    docker logs "$container_name" >&2 || true
    return 1
  fi
  docker exec "$container_name" /bin/stricknani-server health-check || return 1
  app="$(curl --fail --silent --show-error --header 'Host: localhost' "http://127.0.0.1:$port/app/")" || return 1
  asset="$(sed -n 's/.*href="\([^"]*\/immutable\/[^\"]*\.js\)".*/\1/p' <<< "$app" | head -n 1)" || return 1
  [[ -n "$asset" ]] || return 1
  curl --fail --silent --show-error --header 'Host: localhost' "http://127.0.0.1:$port$asset" >/dev/null || return 1
  printf '%s\n' 'Nix image: non-root database access, CLI health check, app shell and immutable asset passed.'
)

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
