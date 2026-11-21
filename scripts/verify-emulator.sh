#!/usr/bin/env bash
# make verify-emulator (T608, constitution P-3): re-runs the emulator checks that decided the local
# architecture (plan.md "Local Environment"), so each emulator release can be re-checked:
#
#   V-2  Lambda java21: create AND execute    (today: fails, so the trigger Lambda runs on the host)
#   V-4  a Lambda in the emulator reaches the host app   (today: fails, same consequence)
#   RDS  the emulator's Postgres engine: SERIAL, exact NUMERIC, now()/interval/jsonb, PL/pgSQL,
#        advisory locks + information_schema (today: SQLite behind the wire protocol, so real postgres:18)
#
# It reuses the emulator emulator-env.sh selects, creates only funding-verify-<time> resources, removes
# them on exit, and writes target/verify-emulator.md. It reports; it never changes the app's config.
# If V-2 and V-4 both pass, plan.md says: deploy the real function + mapping under `local` and drop
# LocalLambdaRunner. If every RDS probe passes, the emulator's RDS could replace postgres:18.
# Executed with bash, but kept free of arrays like the sourced scripts (CLAUDE.md).

set -u
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=/dev/null
. scripts/emulator-env.sh || { echo "verify-emulator: no usable emulator (see above)" >&2; exit 1; }
command -v aws >/dev/null || { echo "verify-emulator: needs the AWS CLI" >&2; exit 1; }

RUN="$(date +%s)"
WORK="$(mktemp -d)"
FN_JAVA="funding-verify-java21-$RUN"
FN_PY="funding-verify-host-$RUN"
DB_ID="funding-verify-$RUN"
ROLE="arn:aws:iam::000000000000:role/funding-verify"
HOST_PORT="${VERIFY_HOST_PORT:-18765}"
HTTP_PID=""
REPORT="target/verify-emulator.md"
ROWS=""

cleanup() {
  aws lambda delete-function --function-name "$FN_JAVA" >/dev/null 2>&1
  aws lambda delete-function --function-name "$FN_PY" >/dev/null 2>&1
  aws rds delete-db-instance --db-instance-identifier "$DB_ID" --skip-final-snapshot >/dev/null 2>&1
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

row() { # check, result, detail
  ROWS="$ROWS| $1 | $2 | $3 |
"
  printf '%-34s %-6s %s\n' "$1" "$2" "$3"
}

one_line() { tr '\n' ' ' | sed 's/|/\\|/g' | cut -c1-220; }

wait_active() { # function name
  i=0
  while [ "$i" -lt 30 ]; do
    state="$(aws lambda get-function --function-name "$1" --query 'Configuration.State' --output text 2>/dev/null)"
    [ "$state" = "Active" ] && return 0
    i=$((i + 1)); sleep 1
  done
  return 1
}

echo "verify-emulator: $EMULATOR_CONTAINER at $AWS_ENDPOINT_URL"

# ------------------------------------------------------------------ V-2: java21 create and execute
JAR="$(ls funding-trigger-lambda/target/funding-trigger-lambda-*.jar 2>/dev/null | grep -v original | head -1)"
if [ -n "$JAR" ]; then
  cp "$JAR" "$WORK/java.zip"
else # no build: any zip shows whether the runtime itself is supported
  python3 -c "import zipfile; zipfile.ZipFile('$WORK/java.zip','w').writestr('README','verify')"
fi
if aws lambda create-function --function-name "$FN_JAVA" --runtime java21 --role "$ROLE" \
     --handler com.funding.trigger.lambda.SqsTriggerHandler::handleRequest --timeout 30 \
     --zip-file "fileb://$WORK/java.zip" >/dev/null 2>"$WORK/v2.err" && wait_active "$FN_JAVA"; then
  if aws lambda invoke --function-name "$FN_JAVA" --cli-binary-format raw-in-base64-out \
       --payload '{"Records":[]}' "$WORK/v2.out" >"$WORK/v2.meta" 2>"$WORK/v2.err"; then
    if grep -q FunctionError "$WORK/v2.meta"; then
      row "V-2 Lambda java21 executes" "PASS" "runtime runs (handler error: $(one_line <"$WORK/v2.out"))"
    else
      row "V-2 Lambda java21 executes" "PASS" "invoked: $(one_line <"$WORK/v2.out")"
    fi
  else
    row "V-2 Lambda java21 executes" "FAIL" "$(one_line <"$WORK/v2.err")"
  fi
else
  row "V-2 Lambda java21 executes" "FAIL" "create: $(one_line <"$WORK/v2.err")"
fi

# ------------------------------------------------------------------ V-4: a Lambda reaches the host
python3 -m http.server "$HOST_PORT" --bind 0.0.0.0 >/dev/null 2>&1 &
HTTP_PID=$!
cat >"$WORK/probe.py" <<'PY'
import urllib.request
def handler(event, context):
    try:
        with urllib.request.urlopen(event["url"], timeout=5) as r:
            return {"ok": True, "status": r.status}
    except Exception as e:
        return {"ok": False, "error": str(e)[:300]}
PY
python3 -c "import zipfile; z=zipfile.ZipFile('$WORK/py.zip','w'); z.write('$WORK/probe.py','probe.py')"
if aws lambda create-function --function-name "$FN_PY" --runtime python3.13 --role "$ROLE" --handler probe.handler \
     --timeout 15 --zip-file "fileb://$WORK/py.zip" >/dev/null 2>"$WORK/v4.err" && wait_active "$FN_PY"; then
  aws lambda invoke --function-name "$FN_PY" --cli-binary-format raw-in-base64-out \
    --payload "{\"url\":\"http://host.docker.internal:$HOST_PORT/\"}" "$WORK/v4.out" >/dev/null 2>"$WORK/v4.err"
  if grep -q '"ok": *true' "$WORK/v4.out" 2>/dev/null; then
    row "V-4 Lambda reaches the host" "PASS" "$(one_line <"$WORK/v4.out")"
  else
    row "V-4 Lambda reaches the host" "FAIL" "$(cat "$WORK/v4.out" "$WORK/v4.err" 2>/dev/null | one_line)"
  fi
else
  row "V-4 Lambda reaches the host" "FAIL" "create python3.13: $(one_line <"$WORK/v4.err")"
fi

# ------------------------------------------------------------------ RDS probe
if aws rds create-db-instance --db-instance-identifier "$DB_ID" --engine postgres --db-instance-class db.t3.micro \
     --allocated-storage 20 --master-username verify --master-user-password verify-pass-1 >/dev/null 2>"$WORK/rds.err"; then
  i=0; status=""
  while [ "$i" -lt 60 ] && [ "$status" != "available" ]; do
    status="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null)"
    i=$((i + 1)); [ "$status" = "available" ] || sleep 2
  done
  port="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].Endpoint.Port' --output text 2>/dev/null)"
  if [ "$status" != "available" ] || [ -z "$port" ] || [ "$port" = "None" ]; then
    row "RDS instance" "FAIL" "status '$status', port '$port'"
  else
    # The endpoint binds 127.0.0.1 inside the emulator container: run psql in its network namespace.
    psql_run() {
      docker run --rm --network "container:$EMULATOR_CONTAINER" postgres:18 \
        psql "postgresql://verify:verify-pass-1@127.0.0.1:$port/postgres" -v ON_ERROR_STOP=1 -tA -c "$1" 2>&1
    }
    probe() { # name, sql, expected output (empty: success is enough)
      out="$(psql_run "$2")"; rc=$?
      if [ "$rc" -eq 0 ] && { [ -z "$3" ] || [ "$(printf '%s' "$out" | tail -1)" = "$3" ]; }; then
        row "RDS $1" "PASS" "$(printf '%s' "$out" | one_line)"
      else
        row "RDS $1" "FAIL" "$(printf '%s' "$out" | one_line)"
      fi
    }
    psql_run "CREATE TABLE verify_p (id SERIAL PRIMARY KEY, n NUMERIC(12,2))" >/dev/null
    probe "SERIAL returns an id" "INSERT INTO verify_p (n) VALUES (1) RETURNING id" "1"
    probe "NUMERIC is exact" "SELECT 0.1::numeric + 0.2::numeric" "0.3"
    probe "now()/interval/jsonb" "SELECT (now() - interval '1 day') < now() AND jsonb_build_object('a', 1) IS NOT NULL" "t"
    probe "PL/pgSQL (DO block)" "DO \$\$ BEGIN PERFORM 1; END \$\$" ""
    probe "advisory lock + information_schema" "SELECT pg_try_advisory_lock(42) AND (SELECT count(*) >= 0 FROM information_schema.tables)" "t"
  fi
else
  row "RDS instance" "FAIL" "create: $(one_line <"$WORK/rds.err")"
fi

# ------------------------------------------------------------------ report
mkdir -p target
v2="$(printf '%s' "$ROWS" | grep -c '^| V-2 .*| PASS |')"
v4="$(printf '%s' "$ROWS" | grep -c '^| V-4 .*| PASS |')"
rds_fail="$(printf '%s' "$ROWS" | grep '^| RDS ' | grep -c '| FAIL |')"
{
  echo "# Emulator re-check ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  echo
  echo "Emulator: \`$EMULATOR_CONTAINER\` at $AWS_ENDPOINT_URL. See plan.md \"Local Environment\" for what each check decided."
  echo
  echo "| Check | Result | Detail |"
  echo "|---|---|---|"
  printf '%s' "$ROWS"
  echo
  if [ "$v2" -eq 1 ] && [ "$v4" -eq 1 ]; then
    echo "**V-2 and V-4 pass:** per plan.md, \`local\` can deploy the real Java 21 function and its event source mapping and drop \`LocalLambdaRunner\`."
  else
    echo "**V-2 / V-4 still fail:** keep \`LocalLambdaRunner\` (the trigger Lambda runs on the host)."
  fi
  if [ "$rds_fail" -eq 0 ]; then
    echo "**Every RDS probe passes:** the emulator's RDS could replace \`postgres:18\` (the app only needs \`FUNDING_DB_URL\`)."
  else
    echo "**RDS probes still fail:** keep \`postgres:18\` in Docker."
  fi
} >"$REPORT"
echo "verify-emulator: wrote $REPORT"
