#!/usr/bin/env bash
# Source this file:  source scripts/wiremock-env.sh
#
# WireMock for every external API (plan.md "WireMock", constitution P-3/P-5).
#   1. Reuse: WIREMOCK_CONTAINER if set, else the single running wiremock/wiremock container that
#             publishes a port.
#   2. Start: only if none is reachable, start funding-wiremock (label funding.managed=true,
#             127.0.0.1:8089:8080, --disable-banner). The request journal stays ON, because every
#             functional test asserts /__admin/requests/unmatched is empty (P-5.7).
# Stubs are NOT loaded here: the app's MockStubLoader (local profile) and the tests generate and
# import them, replacing only this env's stubs (metadata env = FUNDING_ENV).
# Exports WIREMOCK_URL, FUNDING_INCOME_BASE_URL, FUNDING_DISBURSEMENT_BASE_URL.

WIREMOCK_IMAGE="${WIREMOCK_IMAGE:-wiremock/wiremock}"
_FW_OWN_NAME="funding-wiremock"
_FW_PORT=8089

_fw_fail() { echo "wiremock-env: $*" >&2; return 1; }

_fw_pick() {
  if [ -n "${WIREMOCK_CONTAINER:-}" ]; then
    [ "$(docker inspect -f '{{.State.Running}}' "$WIREMOCK_CONTAINER" 2>/dev/null)" = "true" ] \
      || { _fw_fail "WIREMOCK_CONTAINER=$WIREMOCK_CONTAINER is not running"; return 1; }
    return 0
  fi
  local found=() name image
  while read -r name image; do
    [ -n "$name" ] || continue
    case "$image" in "$WIREMOCK_IMAGE"|"$WIREMOCK_IMAGE":*) ;; *) continue ;; esac
    docker port "$name" 8080/tcp 2>/dev/null | grep -qE '^(127\.0\.0\.1|0\.0\.0\.0):' && found+=("$name")
  done < <(docker ps --format '{{.Names}} {{.Image}}')
  if [ "${#found[@]}" -gt 1 ]; then
    _fw_fail "several WireMock containers publish a port; choose one with WIREMOCK_CONTAINER=<name>: ${found[*]}"
    return 1
  fi
  WIREMOCK_CONTAINER="${found[0]:-}"
}

_fw_start_own() {
  docker rm -f "$_FW_OWN_NAME" >/dev/null 2>&1 || true
  echo "wiremock-env: no reachable WireMock; starting $_FW_OWN_NAME on 127.0.0.1:$_FW_PORT" >&2
  docker run -d --name "$_FW_OWN_NAME" --label funding.managed=true \
    -p "127.0.0.1:$_FW_PORT:8080" "$WIREMOCK_IMAGE" --disable-banner >/dev/null \
    || { _fw_fail "docker run failed"; return 1; }
  WIREMOCK_CONTAINER="$_FW_OWN_NAME"
}

_fw_main() {
  _fw_pick || return 1
  local how="reusing"
  if [ -z "$WIREMOCK_CONTAINER" ]; then _fw_start_own || return 1; how="started"; fi
  local mapping port i
  mapping="$(docker port "$WIREMOCK_CONTAINER" 8080/tcp | grep -E '^(127\.0\.0\.1|0\.0\.0\.0):' | head -1)"
  port="${mapping##*:}"
  for i in $(seq 1 60); do
    curl -sf "http://127.0.0.1:$port/__admin/health" >/dev/null 2>&1 && break
    [ "$i" -eq 60 ] && { _fw_fail "$WIREMOCK_CONTAINER not healthy after 30s"; return 1; }
    sleep 0.5
  done
  export WIREMOCK_CONTAINER WIREMOCK_URL="http://127.0.0.1:$port"
  export FUNDING_INCOME_BASE_URL="$WIREMOCK_URL" FUNDING_DISBURSEMENT_BASE_URL="$WIREMOCK_URL"
  echo "wiremock-env: $how $WIREMOCK_CONTAINER at $WIREMOCK_URL" >&2
}

_fw_main
