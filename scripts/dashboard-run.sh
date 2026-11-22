#!/usr/bin/env bash
# Spec 002 AS-10.1 / plan §8: one command starts the funder dashboard (`make dashboard`).
#   1. the preflight (AS-10.2);
#   2. the funding API as `make run` does (reusing Temporal, Postgres, the emulator, WireMock and
#      the OIDC provider, P-3), then submits the seeded scenarios still in DRAFT (AS-10.3);
#   3. the agent (port 8091); 4. the web app (port 5173); 5. prints the URL.
# Each part that's already running, and is ours, is reused instead. Ctrl+C stops only what this
# script started; anything it reused keeps running. Logs: target/dashboard/*.log.
# No arrays and no word splitting (CLAUDE.md).
set -u
cd "$(dirname "$0")/.." || exit 1
LOGS=target/dashboard
mkdir -p "$LOGS"
API_PORT="${SERVER_PORT:-8080}"
AGENT_PORT="${AGENT_PORT:-8091}"
WEB_PORT=5173
started_app="" started_agent="" started_web="" sleeper=""

say() { printf 'dashboard: %s\n' "$*"; }
listening() { lsof -ti ":$1" -sTCP:LISTEN >/dev/null 2>&1; }
# descendants <pid>: every process under pid, deepest first, one per line.
descendants() { pgrep -P "$1" 2>/dev/null | while read -r c; do descendants "$c"; echo "$c"; done; }
alive_any() { while read -r p; do kill -0 "$p" 2>/dev/null && return 0; done; return 1; }

# stop <pid> <port>: stops the whole tree this script started (the Maven wrapper forks the JVM, and
# npm/uv fork their servers), plus whatever listens on the port. The tree is listed before anything
# is killed, so a child the kill reparents is still in the list. TERM, up to 20s, then KILL.
stop() {
  tree="$({ descendants "$1"; echo "$1"; lsof -ti ":$2" -sTCP:LISTEN 2>/dev/null; } | sort -u)"
  printf '%s\n' "$tree" | while read -r p; do [ -n "$p" ] && kill -TERM "$p" 2>/dev/null; done
  i=0
  while [ "$i" -lt 40 ] && printf '%s\n' "$tree" | alive_any; do sleep 0.5; i=$((i + 1)); done
  printf '%s\n' "$tree" | while read -r p; do [ -n "$p" ] && kill -KILL "$p" 2>/dev/null; done
  if listening "$2"; then say "port $2 is still taken after stopping; check lsof -i :$2"; fi
}

cleanup() {
  trap - INT TERM EXIT
  [ -n "$sleeper" ] && kill "$sleeper" 2>/dev/null
  [ -n "$started_web" ] && { say "stopping the web app"; stop "$started_web" "$WEB_PORT"; }
  [ -n "$started_agent" ] && { say "stopping the agent"; stop "$started_agent" "$AGENT_PORT"; }
  [ -n "$started_app" ] && { say "stopping the funding API"; stop "$started_app" "$API_PORT"; }
  exit 0
}
trap cleanup INT TERM EXIT

# wait_for <what> <seconds> <pid or ""> <log> <command...>: polls the command until it succeeds.
wait_for() {
  what="$1" secs="$2" pid="$3" log="$4"; shift 4
  i=0
  while [ "$i" -lt "$secs" ]; do
    "$@" >/dev/null 2>&1 && return 0
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      say "$what stopped while starting; the end of $log:"; tail -20 "$log" >&2; return 1
    fi
    sleep 1; i=$((i + 1))
  done
  say "$what didn't come up in ${secs}s; see $log"; return 1
}

api_is_ours() {   # healthy, and the funder API refuses a call without a token (spec 002 FR-11)
  curl -sf "localhost:$API_PORT/actuator/health" >/dev/null 2>&1 \
    && [ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$API_PORT/api/v1/applications/LA-1006")" = "401" ]
}
agent_is_ours() { curl -sf "localhost:$AGENT_PORT/.well-known/agent-card.json" 2>/dev/null | grep -q '"Funder dashboard agent"'; }
web_is_ours() { curl -sf "localhost:$WEB_PORT/agent/.well-known/agent-card.json" 2>/dev/null | grep -q '"Funder dashboard agent"'; }

./scripts/dashboard-preflight.sh || { trap - EXIT; exit 1; }

# --- 2. the funding API
if api_is_ours; then
  say "reusing the funding API on :$API_PORT"
elif listening "$API_PORT"; then
  say "port $API_PORT is taken by something that isn't the funding API; free it, or set SERVER_PORT"; exit 1
else
  say "starting the funding API (make run; the first build takes a few minutes): $LOGS/app.log"
  ./run-local.sh >"$LOGS/app.log" 2>&1 &
  started_app=$!
  wait_for "the funding API" 600 "$started_app" "$LOGS/app.log" api_is_ours || exit 1
fi
submitted=0
for id in $(ls test/data/scenarios | grep -E '^LA-[0-9]+' | sed -E 's/^(LA-[0-9]+).*/\1/' | sort -u); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "localhost:$API_PORT/api/v1/dev/applications/$id/submit")"
  [ "$code" = "200" ] && submitted=$((submitted + 1))
done
say "seeded scenarios: $submitted newly submitted (the rest were already)"

# --- 3. the agent
if agent_is_ours; then
  say "reusing the agent on :$AGENT_PORT"
elif listening "$AGENT_PORT"; then
  say "port $AGENT_PORT is taken by something that isn't the agent; free it, or set AGENT_PORT"; exit 1
else
  say "starting the agent: $LOGS/agent.log"
  (cd dashboard/agent && FUNDER_API_URL="http://localhost:$API_PORT/api/v1" AGENT_PORT="$AGENT_PORT" exec uv run python main.py) >"$LOGS/agent.log" 2>&1 &
  started_agent=$!
  wait_for "the agent" 90 "$started_agent" "$LOGS/agent.log" agent_is_ours || exit 1
fi

# --- 4. the web app
if web_is_ours; then
  say "reusing the web app on :$WEB_PORT"
elif listening "$WEB_PORT"; then
  say "port $WEB_PORT is taken by something that isn't the dashboard; free it"; exit 1
else
  if [ ! -d dashboard/web/node_modules ]; then
    say "installing the web app's packages (npm ci)"
    (cd dashboard/web && npm ci) >"$LOGS/npm-ci.log" 2>&1 || { say "npm ci failed; see $LOGS/npm-ci.log"; exit 1; }
  fi
  say "starting the web app: $LOGS/web.log"
  (cd dashboard/web && exec npm run dev -- --port "$WEB_PORT" --strictPort) >"$LOGS/web.log" 2>&1 &
  started_web=$!
  wait_for "the web app" 90 "$started_web" "$LOGS/web.log" web_is_ours || exit 1
fi

# --- 5. ready
cat <<MSG

  Funder dashboard: http://localhost:$WEB_PORT
  Sign in as docs-1, gap-1, income-1, risc-1, payment-1, general-1 or supervisor-1 (no password),
  then open a deal, e.g. LA-1006 (docs-1's kind of work).
  Temporal UI: http://localhost:8233   Funder API: http://localhost:$API_PORT/swagger-ui.html

MSG
if [ -z "$started_app$started_agent$started_web" ]; then
  say "everything was already running; nothing to stop"
  trap - EXIT; exit 0
fi
say "Ctrl+C stops what this started; anything reused keeps running"
# `wait` is interrupted by signals; a foreground sleep would hold Ctrl+C until it finished.
while :; do sleep 3600 & sleeper=$!; wait "$sleeper"; done
