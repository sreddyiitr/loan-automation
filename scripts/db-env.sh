#!/usr/bin/env bash
# Source this file:  source scripts/db-env.sh
#
# PostgreSQL 18 for local runs and functional tests (plan.md "Database", constitution P-3).
#   1. Reuse: DB_CONTAINER if set, else the single running postgres:18 container that publishes a port
#             (Testcontainers containers excluded: they disappear when their test JVM exits).
#   2. Start: only if none is reachable, start funding-postgres (label funding.managed=true,
#             127.0.0.1:55432:5432, named volume funding-pgdata so data survives restarts).
#             55432 avoids clashing with a Homebrew Postgres on 5432.
#   3. Ensure database $FUNDING_DB_NAME (default funding) exists.
# Exports FUNDING_DB_URL, FUNDING_DB_USER, FUNDING_DB_PASSWORD (and FUNDING_DB_HOST/PORT/NAME).
#
# Overrides: DB_CONTAINER, DB_IMAGE (postgres:18), FUNDING_DB_NAME, FUNDING_DB_USER, FUNDING_DB_PASSWORD.

DB_IMAGE="${DB_IMAGE:-postgres:18}"
_FD_OWN_NAME="funding-postgres"
_FD_PORT=55432

_fd_fail() { echo "db-env: $*" >&2; return 1; }

_fd_pick() {
  if [ -n "${DB_CONTAINER:-}" ]; then
    [ "$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null)" = "true" ] \
      || { _fd_fail "DB_CONTAINER=$DB_CONTAINER is not running"; return 1; }
    return 0
  fi
  # Plain strings, no arrays: sourced from bash and zsh, whose arrays index differently.
  local found="" count=0 name
  while read -r name; do
    [ -n "$name" ] || continue
    # Testcontainers' throwaway postgres:18 (make test-unit) is reaped seconds after its JVM exits: never reuse it.
    [ "$(docker inspect -f '{{index .Config.Labels "org.testcontainers"}}' "$name" 2>/dev/null)" = "true" ] && continue
    if docker port "$name" 5432/tcp 2>/dev/null | grep -qE '^(127\.0\.0\.1|0\.0\.0\.0):'; then
      found="$found $name"; count=$((count + 1))
    fi
  done <<EOF_PS
$(docker ps --filter "ancestor=$DB_IMAGE" --format '{{.Names}}')
EOF_PS
  if [ "$count" -gt 1 ]; then
    _fd_fail "several $DB_IMAGE containers publish a port; choose one with DB_CONTAINER=<name>:$found"
    return 1
  fi
  DB_CONTAINER="${found# }"
}

_fd_creds() {
  local env
  env="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$DB_CONTAINER")"
  FUNDING_DB_USER="${FUNDING_DB_USER:-$(printf '%s\n' "$env" | sed -n 's/^POSTGRES_USER=//p')}"
  FUNDING_DB_USER="${FUNDING_DB_USER:-postgres}"
  FUNDING_DB_PASSWORD="${FUNDING_DB_PASSWORD:-$(printf '%s\n' "$env" | sed -n 's/^POSTGRES_PASSWORD=//p')}"
  local mapping
  mapping="$(docker port "$DB_CONTAINER" 5432/tcp | grep -E '^(127\.0\.0\.1|0\.0\.0\.0):' | head -1)"
  FUNDING_DB_PORT="${mapping##*:}"
}

_fd_start_own() {
  if docker ps -a --format '{{.Names}}' | grep -qx "$_FD_OWN_NAME"; then
    docker start "$_FD_OWN_NAME" >/dev/null || { _fd_fail "cannot start existing $_FD_OWN_NAME"; return 1; }
  else
    echo "db-env: no reachable $DB_IMAGE container; starting $_FD_OWN_NAME on 127.0.0.1:$_FD_PORT" >&2
    docker run -d --name "$_FD_OWN_NAME" --label funding.managed=true \
      -p "127.0.0.1:$_FD_PORT:5432" -v funding-pgdata:/var/lib/postgresql \
      -e POSTGRES_USER=funding -e POSTGRES_PASSWORD=funding -e POSTGRES_DB=funding \
      "$DB_IMAGE" >/dev/null || { _fd_fail "docker run failed"; return 1; }
  fi
  DB_CONTAINER="$_FD_OWN_NAME"
}

_fd_ready() {
  local i
  for i in $(seq 1 60); do
    docker exec "$DB_CONTAINER" pg_isready -U "$FUNDING_DB_USER" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  _fd_fail "$DB_CONTAINER not ready after 30s"
}

_fd_ensure_db() {
  local exists
  exists="$(docker exec "$DB_CONTAINER" psql -U "$FUNDING_DB_USER" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '$FUNDING_DB_NAME'" 2>/dev/null)"
  [ "$exists" = "1" ] || docker exec "$DB_CONTAINER" psql -U "$FUNDING_DB_USER" -d postgres -qc \
    "CREATE DATABASE \"$FUNDING_DB_NAME\"" >/dev/null || { _fd_fail "cannot create database $FUNDING_DB_NAME"; return 1; }
}

_fd_main() {
  FUNDING_DB_NAME="${FUNDING_DB_NAME:-funding}"
  _fd_pick || return 1
  local how="reusing"
  if [ -z "$DB_CONTAINER" ]; then _fd_start_own || return 1; how="started"; fi
  _fd_creds && _fd_ready && _fd_ensure_db || return 1
  export FUNDING_DB_HOST=127.0.0.1 FUNDING_DB_PORT FUNDING_DB_NAME FUNDING_DB_USER FUNDING_DB_PASSWORD DB_CONTAINER
  export FUNDING_DB_URL="jdbc:postgresql://127.0.0.1:$FUNDING_DB_PORT/$FUNDING_DB_NAME"
  echo "db-env: $how $DB_CONTAINER, $FUNDING_DB_URL (user $FUNDING_DB_USER)" >&2
}

_fd_main
