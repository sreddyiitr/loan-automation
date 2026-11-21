#!/usr/bin/env bash
# Source this file:  source scripts/oidc-env.sh
#
# A local OIDC provider for spec 002's sign-in (navikt/mock-oauth2-server, constitution P-3).
#   1. Reuse: OIDC_CONTAINER if set, else the single running mock-oauth2-server container that
#             publishes its port 8080. Reusing never pulls or starts anything.
#   2. Refuse: if one is running but publishes no port, stop and say how to fix it, instead of
#             starting a second one next to it.
#   3. Start: only if none is running, start funding-oidc (label funding.managed=true,
#             127.0.0.1:8090:8080) with scripts/oidc-config.json. The image is pulled only if it
#             isn't already local.
#   4. Check: the provider must run with scripts/oidc-config.json (plan §4), so browser-login
#             tokens carry aud=funding-api. One token request proves it; a reused container
#             without the config is refused, with the command to recreate it.
# The issuer is http://127.0.0.1:<port>/<OIDC_ISSUER_ID>. mock-oauth2-server builds the token's
# `iss` from the URL it was called on, so browser, agent and API must all use this same host
# (127.0.0.1, not localhost), or Spring rejects the token.
# Exports OIDC_CONTAINER, OIDC_URL, OIDC_ISSUER_URI.

OIDC_IMAGE="${OIDC_IMAGE:-ghcr.io/navikt/mock-oauth2-server:6.1.5}"
OIDC_ISSUER_ID="${OIDC_ISSUER_ID:-funding}"
OIDC_CONFIG="${OIDC_CONFIG:-$(git rev-parse --show-toplevel 2>/dev/null)/scripts/oidc-config.json}"
_FO_REPO="${OIDC_IMAGE%:*}"
_FO_OWN_NAME="funding-oidc"
_FO_PORT=8090

_fo_fail() { echo "oidc-env: $*" >&2; return 1; }

_fo_published() {
  docker port "$1" 8080/tcp 2>/dev/null | grep -E '^(127\.0\.0\.1|0\.0\.0\.0):' | head -1
}

_fo_pick() {
  if [ -n "${OIDC_CONTAINER:-}" ]; then
    [ "$(docker inspect -f '{{.State.Running}}' "$OIDC_CONTAINER" 2>/dev/null)" = "true" ] \
      || { _fo_fail "OIDC_CONTAINER=$OIDC_CONTAINER is not running"; return 1; }
    [ -n "$(_fo_published "$OIDC_CONTAINER")" ] \
      || { _fo_fail "OIDC_CONTAINER=$OIDC_CONTAINER publishes no port; recreate it with -p 127.0.0.1:$_FO_PORT:8080"; return 1; }
    return 0
  fi
  # Plain strings, no arrays: this file is sourced from bash and zsh (macOS default), whose
  # arrays index differently.
  local found="" count=0 unpublished="" name image
  while read -r name image; do
    [ -n "$name" ] || continue
    case "$image" in "$_FO_REPO"|"$_FO_REPO":*) ;; *) continue ;; esac
    if [ -n "$(_fo_published "$name")" ]; then
      found="$found $name"; count=$((count + 1))
    else
      unpublished="$unpublished $name"
    fi
  done <<EOF_PS
$(docker ps --format '{{.Names}} {{.Image}}')
EOF_PS
  if [ "$count" -gt 1 ]; then
    _fo_fail "several mock-oauth2-server containers publish a port; choose one with OIDC_CONTAINER=<name>:$found"
    return 1
  fi
  if [ "$count" -eq 0 ] && [ -n "$unpublished" ]; then
    _fo_fail "mock-oauth2-server is running but publishes no port:$unpublished
  Recreate it with a port (the image is already local, so nothing is downloaded):
    docker rm -f${unpublished}
    docker run -d --name $_FO_OWN_NAME -p 127.0.0.1:$_FO_PORT:8080 $OIDC_IMAGE
  or remove it and source this script again to let it start $_FO_OWN_NAME."
    return 1
  fi
  OIDC_CONTAINER="${found# }"
}

_fo_start_own() {
  # Never remove a running container: if ours is up, reuse it (discovery can miss it transiently).
  if [ "$(docker inspect -f '{{.State.Running}}' "$_FO_OWN_NAME" 2>/dev/null)" = "true" ]; then
    OIDC_CONTAINER="$_FO_OWN_NAME"
    return 0
  fi
  docker rm "$_FO_OWN_NAME" >/dev/null 2>&1 || true   # a stopped leftover only
  if docker image inspect "$OIDC_IMAGE" >/dev/null 2>&1; then
    echo "oidc-env: no running OIDC provider; starting $_FO_OWN_NAME on 127.0.0.1:$_FO_PORT (image is local)" >&2
  else
    echo "oidc-env: no running OIDC provider and $OIDC_IMAGE isn't local; pulling it, then starting $_FO_OWN_NAME" >&2
  fi
  [ -f "$OIDC_CONFIG" ] || { _fo_fail "provider config not found: $OIDC_CONFIG (set OIDC_CONFIG)"; return 1; }
  docker run -d --name "$_FO_OWN_NAME" --label funding.managed=true \
    -p "127.0.0.1:$_FO_PORT:8080" -e JSON_CONFIG="$(cat "$OIDC_CONFIG")" "$OIDC_IMAGE" >/dev/null \
    || { _fo_fail "docker run failed"; return 1; }
  OIDC_CONTAINER="$_FO_OWN_NAME"
}

_fo_main() {
  _fo_pick || return 1
  local how="reusing"
  if [ -z "$OIDC_CONTAINER" ]; then _fo_start_own || return 1; how="started"; fi
  local mapping port i
  mapping="$(_fo_published "$OIDC_CONTAINER")"
  port="${mapping##*:}"
  for i in $(seq 1 60); do
    curl -sf "http://127.0.0.1:$port/$OIDC_ISSUER_ID/.well-known/openid-configuration" >/dev/null 2>&1 && break
    [ "$i" -eq 60 ] && { _fo_fail "$OIDC_CONTAINER not answering OIDC discovery after 30s"; return 1; }
    sleep 0.5
  done
  _fo_check_config "http://127.0.0.1:$port/$OIDC_ISSUER_ID" || return 1
  export OIDC_CONTAINER OIDC_URL="http://127.0.0.1:$port"
  export OIDC_ISSUER_URI="$OIDC_URL/$OIDC_ISSUER_ID"
  echo "oidc-env: $how $OIDC_CONTAINER, issuer $OIDC_ISSUER_URI" >&2
}

# The dashboard client's tokens must carry aud=funding-api: true only with scripts/oidc-config.json.
_fo_check_config() {
  local aud
  aud="$(curl -s -X POST "$1/token" -d grant_type=client_credentials -d client_id=funder-dashboard \
          -d client_secret=check -d scope=openid 2>/dev/null \
        | python3 -c 'import base64,json,sys
try:
    p = json.load(sys.stdin)["access_token"].split(".")[1]
    a = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4))).get("aud")
    print(" ".join(a) if isinstance(a, list) else (a or ""))
except Exception:
    print("")' 2>/dev/null)"
  case " $aud " in *" funding-api "*) return 0 ;; esac
  _fo_fail "$OIDC_CONTAINER runs without scripts/oidc-config.json: browser-login tokens would get aud=${aud:-none}, not funding-api (plan §4).
  Recreate it with the config (the image is local, so nothing is downloaded):
    docker rm -f $OIDC_CONTAINER
    docker run -d --name $_FO_OWN_NAME -p 127.0.0.1:$_FO_PORT:8080 -e JSON_CONFIG=\"\$(cat scripts/oidc-config.json)\" $OIDC_IMAGE
  or remove it and source this script again to let it start $_FO_OWN_NAME."
}

_fo_main
