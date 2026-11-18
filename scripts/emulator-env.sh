#!/usr/bin/env bash
# Source this file:  source scripts/emulator-env.sh
#
# Finds an AWS Emulator container (or starts one), then exports the env vars the AWS SDK, CLI,
# the app and the tests need. Ported from ../homehub/scripts/emulator-env.sh and extended per
# specs/001-loan-funding-validation/plan.md ("Local Environment"), constitution P-3:
#
#   1. Pick:   EMULATOR_CONTAINER if set, else the single running $EMULATOR_IMAGE container.
#              Several running is an error: choose one with EMULATOR_CONTAINER.
#   2. Port:   the emulator's own port, parsed from `endpoint_url` in its startup log (default 9297).
#   3. Reach:  a published port (`docker port`) -> https://127.0.0.1:<host port>. Not published
#              (plain `docker run`) -> start/reuse the forwarder funding-emulator-proxy (alpine/socat,
#              label funding.managed=true): 127.0.0.1:<port> -> <container bridge IP>:<port>.
#              Docker Desktop for Mac can't route to container IPs; TLS passes through unchanged.
#   4. Creds:  a bind-mounted ~/.aws/emulator -> use the host path. Otherwise `docker cp` ca.pem,
#              config and emulator_credentials into ~/.aws/emulator/reuse-<container>/ (700/600),
#              on EVERY source: the emulator makes new keys and a new CA each time it starts.
#   5. Check:  /__admin/health is ok with that CA, and `aws sts get-caller-identity` returns
#              000000000000 (proves endpoint, CA and credentials match).
#   6. Start:  only if 1-5 found nothing usable: start funding-emulator (label funding.managed=true).
#
# Telemetry is left at the emulator's default (on). This repo does not copy homehub's
# telemetry-off flags (CLAUDE.md, P-3).
#
# Overrides: EMULATOR_IMAGE (default aws-emulator:latest), EMULATOR_CONTAINER, EMULATOR_PORT (9297).

EMULATOR_IMAGE="${EMULATOR_IMAGE:-aws-emulator:latest}"
EMULATOR_PORT="${EMULATOR_PORT:-9297}"
_FE_OWN_NAME="funding-emulator"
_FE_PROXY_NAME="funding-emulator-proxy"
_FE_LABEL="funding.managed=true"

_fe_fail() { echo "emulator-env: $*" >&2; return 1; }
_fe_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }

_fe_pick() {
  if [ -n "${EMULATOR_CONTAINER:-}" ]; then
    _FE_EXPLICIT=1
    _fe_running "$EMULATOR_CONTAINER" || { _fe_fail "EMULATOR_CONTAINER=$EMULATOR_CONTAINER is not running"; return 1; }
    return 0
  fi
  local found count
  found="$(docker ps --filter "ancestor=$EMULATOR_IMAGE" --format '{{.Names}}')"
  count="$(printf '%s' "$found" | grep -c . || true)"
  if [ "$count" -eq 1 ]; then EMULATOR_CONTAINER="$found"; return 0; fi
  if [ "$count" -gt 1 ]; then
    _fe_fail "several emulator containers are running; choose one with EMULATOR_CONTAINER=<name>:
$(printf '%s\n' "$found" | sed 's/^/    /')"
    return 1
  fi
  EMULATOR_CONTAINER=""
  return 0
}

# Internal port from the startup log line {"endpoint_url":"https://127.0.0.1:<port>"}.
_fe_internal_port() {
  local p
  p="$(docker logs "$EMULATOR_CONTAINER" 2>&1 | grep -m1 -o '"endpoint_url":"https://[^"]*"' | grep -o '[0-9]*"$' | tr -d '"')"
  _FE_INTERNAL_PORT="${p:-9297}"
}

_fe_endpoint() {
  local mapping
  mapping="$(docker port "$EMULATOR_CONTAINER" "$_FE_INTERNAL_PORT/tcp" 2>/dev/null | grep -E '^(127\.0\.0\.1|0\.0\.0\.0):' | head -1)"
  if [ -n "$mapping" ]; then
    _FE_HOST_PORT="${mapping##*:}"
    return 0
  fi
  # Not published: forward 127.0.0.1:<port> to the container's bridge IP.
  local ip
  ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$EMULATOR_CONTAINER" | awk '{print $1}')"
  [ -n "$ip" ] || { _fe_fail "$EMULATOR_CONTAINER has no published port and no bridge IP"; return 1; }
  local want="TCP-LISTEN:$_FE_INTERNAL_PORT,fork,reuseaddr TCP:$ip:$_FE_INTERNAL_PORT"
  if _fe_running "$_FE_PROXY_NAME"; then
    local have
    have="$(docker inspect -f '{{join .Config.Cmd " "}}' "$_FE_PROXY_NAME" 2>/dev/null)"
    if [ "$have" != "$want" ]; then
      echo "emulator-env: forwarder points at an old target ($have); recreating" >&2
      docker rm -f "$_FE_PROXY_NAME" >/dev/null 2>&1
    fi
  fi
  if ! _fe_running "$_FE_PROXY_NAME"; then
    docker rm -f "$_FE_PROXY_NAME" >/dev/null 2>&1 || true
    echo "emulator-env: $EMULATOR_CONTAINER publishes no port; starting forwarder $_FE_PROXY_NAME on 127.0.0.1:$_FE_INTERNAL_PORT" >&2
    # shellcheck disable=SC2086
    docker run -d --name "$_FE_PROXY_NAME" --label "$_FE_LABEL" --restart unless-stopped \
      -p "127.0.0.1:$_FE_INTERNAL_PORT:$_FE_INTERNAL_PORT" alpine/socat $want >/dev/null \
      || { _fe_fail "could not start $_FE_PROXY_NAME (is port $_FE_INTERNAL_PORT free?)"; return 1; }
  fi
  _FE_HOST_PORT="$_FE_INTERNAL_PORT"
}

_fe_credentials() {
  local src
  src="$(docker inspect -f '{{range .Mounts}}{{.Source}}|{{.Destination}}{{"\n"}}{{end}}' "$EMULATOR_CONTAINER" \
        | awk -F'|' '$2 ~ /\/\.aws\/emulator\/?$/ {print $1; exit}')"
  if [ -n "$src" ]; then
    _FE_STATE_DIR="$src/$_FE_INTERNAL_PORT"
    return 0
  fi
  local home inside dest
  home="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$EMULATOR_CONTAINER" | sed -n 's/^HOME=//p' | head -1)"
  inside="${home:-/home/emulator}/.aws/emulator/$_FE_INTERNAL_PORT"
  dest="$HOME/.aws/emulator/reuse-$EMULATOR_CONTAINER"
  mkdir -p "$dest" && chmod 700 "$dest"
  local f
  for f in ca.pem config emulator_credentials; do
    docker cp "$EMULATOR_CONTAINER:$inside/$f" "$dest/$f" >/dev/null 2>&1 \
      || { _fe_fail "cannot copy $inside/$f from $EMULATOR_CONTAINER"; return 1; }
    chmod 600 "$dest/$f"
  done
  _FE_STATE_DIR="$dest"
}

_fe_healthy() {
  local i
  for i in $(seq 1 60); do
    if [ -f "$_FE_STATE_DIR/ca.pem" ] && \
       curl -sf --cacert "$_FE_STATE_DIR/ca.pem" "https://127.0.0.1:$_FE_HOST_PORT/__admin/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

_fe_export() {
  [ -f "$_FE_STATE_DIR/emulator_credentials" ] || { _fe_fail "missing $_FE_STATE_DIR/emulator_credentials"; return 1; }
  export AWS_ENDPOINT_URL="https://127.0.0.1:$_FE_HOST_PORT"
  export AWS_SHARED_CREDENTIALS_FILE="$_FE_STATE_DIR/emulator_credentials"
  export AWS_CONFIG_FILE="$_FE_STATE_DIR/config"
  export AWS_CA_BUNDLE="$_FE_STATE_DIR/ca.pem"
  export AWS_EC2_METADATA_DISABLED="true"
  export AWS_DISABLE_HOST_PREFIX_INJECTION="true"
  # us-west-2: regions are isolated in the emulator and AWS verifies fidelity there.
  export AWS_REGION="us-west-2" AWS_DEFAULT_REGION="us-west-2"
  export EMULATOR_CONTAINER
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
}

_fe_identity_ok() {
  command -v aws >/dev/null 2>&1 || return 0   # CLI optional; health + CA already checked
  [ "$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" = "000000000000" ]
}

_fe_start_own() {
  docker image inspect "$EMULATOR_IMAGE" >/dev/null 2>&1 \
    || { _fe_fail "image $EMULATOR_IMAGE not found (build it from ../aws-emulator/Dockerfile)"; return 1; }
  docker rm -f "$_FE_OWN_NAME" >/dev/null 2>&1 || true
  mkdir -p "$HOME/.aws/emulator"
  echo "emulator-env: no usable emulator found; starting $_FE_OWN_NAME on 127.0.0.1:$EMULATOR_PORT" >&2
  local user_flag=()
  [ "$(uname -s)" = "Linux" ] && user_flag=(--user "$(id -u):$(id -g)")
  docker run -d --rm --name "$_FE_OWN_NAME" --label "$_FE_LABEL" "${user_flag[@]}" \
    --memory=2g --memory-swap=2g \
    -p "127.0.0.1:$EMULATOR_PORT:$EMULATOR_PORT" \
    -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
    "$EMULATOR_IMAGE" -b 0.0.0.0 -p "$EMULATOR_PORT" >/dev/null \
    || { _fe_fail "docker run failed"; return 1; }
  EMULATOR_CONTAINER="$_FE_OWN_NAME"
}

_fe_connect() {
  _fe_internal_port && _fe_endpoint && _fe_credentials && _fe_healthy && _fe_export && _fe_identity_ok
}

_fe_main() {
  _fe_pick || return 1
  if [ -n "$EMULATOR_CONTAINER" ] && _fe_connect; then
    echo "emulator-env: reusing $EMULATOR_CONTAINER at $AWS_ENDPOINT_URL region=$AWS_REGION" >&2
    return 0
  fi
  if [ -n "$EMULATOR_CONTAINER" ]; then
    _fe_fail "$EMULATOR_CONTAINER is running but not reachable (health, CA or credentials)"
    [ -n "${_FE_EXPLICIT:-}" ] && return 1
  fi
  _fe_start_own && sleep 1 && _fe_connect \
    && echo "emulator-env: started $EMULATOR_CONTAINER at $AWS_ENDPOINT_URL region=$AWS_REGION" >&2
}

_fe_main
